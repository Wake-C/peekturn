package app.peekturn.reader.narration

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import app.peekturn.reader.reader.ReaderActivity
import app.peekturn.reader.reader.isSafeReaderLocator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Foreground media service for continuous narration. It owns the narration publication and the
 * TtsNavigator, so screen-off or lockscreen playback is not tied to an open learning popup or to
 * ReaderActivity. The visible reader only binds while foregrounded to follow locators and highlight
 * the current sentence; background progress is persisted here.
 *
 * There is no alternate paginator and no second speech provider: playback is the same official
 * Readium TTS unit as the in-app controls, with Microsoft Read Aloud as the only online source.
 */
@OptIn(ExperimentalReadiumApi::class)
class NarrationService : MediaSessionService() {
    private val graph get() = (application as PeekturnApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = Binder()

    private var playback: NarrationPlayback? = null
    private var session: MediaSession? = null
    private var publication: Publication? = null
    private var opening: Job? = null
    private var bookId: Long = -1L

    inner class Binder : android.os.Binder() {
        private val mutablePlayback = MutableStateFlow<NarrationPlayback?>(null)
        internal val playback: StateFlow<NarrationPlayback?> = mutablePlayback.asStateFlow()

        private val mutableFailure = MutableStateFlow<String?>(null)
        val failure: StateFlow<String?> = mutableFailure.asStateFlow()

        fun start(bookId: Long, locatorJson: String?, speed: Float, startSentence: String?) {
            ensureCaller()
            this@NarrationService.startSelf()
            this@NarrationService.begin(bookId, locatorJson, speed, startSentence)
        }

        fun play() { ensureCaller(); playback.value?.play() }
        fun pause() { ensureCaller(); playback.value?.pause() }

        /**
         * A lookup-scoped pause that remembers its own eligibility. It returns false when narration
         * was not playing, so a reader that was already manually paused stays paused.
         */
        fun pauseForLookup(): Boolean {
            ensureCaller()
            return playback.value?.pauseForLookup() ?: false
        }

        /** Resumes only the lookup pause this session still owns; every other case returns false. */
        fun resumeAfterLookup(): Boolean {
            ensureCaller()
            return playback.value?.resumeAfterLookup() ?: false
        }

        fun next() { ensureCaller(); playback.value?.next() }
        fun previous() { ensureCaller(); playback.value?.previous() }
        fun retry() { ensureCaller(); playback.value?.retry() }
        fun setSpeed(speed: Float) { ensureCaller(); playback.value?.setSpeed(speed) }
        fun stop() { ensureCaller(); this@NarrationService.stopNarration() }

        /**
         * Authorization is checked per transaction. onBind is a lifecycle callback and does not
         * retain the initiating binder transaction's UID; the capability token in the bind intent
         * plus this check are what make the endpoint app-private.
         */
        private fun ensureCaller() {
            check(android.os.Binder.getCallingUid() == Process.myUid()) { "Narration control is app-private." }
        }

        internal fun attach(playback: NarrationPlayback) {
            mutableFailure.value = null
            mutablePlayback.value = playback
        }

        internal fun detach() {
            mutablePlayback.value = null
        }

        internal fun fail(message: String) {
            mutableFailure.value = message
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Playback is opened only through the app-private binder. An external explicit start must
        // not open or synthesize a private book.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == BIND_ACTION) {
            // A random per-process capability. An external explicit bind cannot guess it.
            if (intent.getStringExtra(EXTRA_CONTROL_TOKEN) != controlToken) return null
            super.onBind(intent)
            return binder
        }
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopNarration()
    }

    override fun onDestroy() {
        opening?.cancel()
        opening = null
        session?.release()
        session = null
        playback?.stop(notify = false)
        playback = null
        publication?.close()
        publication = null
        binder.detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSelf() {
        startService(Intent(this, NarrationService::class.java))
    }

    private fun begin(requestedBook: Long, locatorJson: String?, speed: Float, startSentence: String?) {
        if (requestedBook < 0L) return
        val locator = locatorJson?.let(::locator)

        val current = playback
        if (current != null && bookId == requestedBook) {
            if (locator != null) current.go(locator, startSentence)
            else current.play()
            return
        }

        releasePlayback(stopService = false)
        opening?.cancel()
        opening = scope.launch {
            try {
                val book = graph.store.findBook(requestedBook)
                    ?: error("The book is no longer in the library.")
                val opened = graph.engine.open(File(book.filePath))
                publication = opened
                bookId = requestedBook
                val created = NarrationPlayback(
                    application = application,
                    bookId = requestedBook,
                    publication = opened,
                    cache = graph.sentenceAudio,
                    accent = graph.store.settings.value.accent,
                    initialSpeed = speed,
                    providerGate = graph.narrationProviderGate,
                    onEnded = { scope.launch { stopNarration() } },
                    onLocator = { position -> persist(requestedBook, position) }
                )
                playback = created
                binder.attach(created)
                if (!created.start(locator, startSentence)) return@launch
                val player = created.asMedia3Player() ?: return@launch
                val mediaSession = MediaSession.Builder(this@NarrationService, player)
                    .setSessionActivity(readerIntent(requestedBook))
                    .setId(requestedBook.toString())
                    .build()
                session = mediaSession
                addSession(mediaSession)
                created.play()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                binder.fail(getString(R.string.reader_narration_error_open))
                scope.launch { stopNarration() }
            }
        }
    }

    private fun persist(bookId: Long, locator: Locator) {
        val json = locator.toJSON().toString()
        graph.scope.launch {
            runCatching {
                // A chapter-local progression is not book progress. The narration utterance locator
                // may omit totalProgression, so keep the book's existing value rather than resetting
                // it or storing the chapter fraction as the whole-book fraction.
                val progression = locator.locations.totalProgression
                    ?: graph.store.findBook(bookId)?.progression
                    ?: 0.0
                graph.store.savePosition(bookId, json, progression)
            }
        }
    }

    private fun stopNarration() {
        opening?.cancel()
        opening = null
        releasePlayback(stopService = true)
    }

    private fun releasePlayback(stopService: Boolean) {
        // MediaSessionService removes a released session from its controller/notification list
        // automatically; explicitly doing removeSession in addition would double-release it.
        session?.release()
        session = null
        playback?.stop(notify = false)
        playback = null
        binder.detach()
        publication?.close()
        publication = null
        bookId = -1L
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun locator(json: String): Locator? = runCatching {
        Locator.fromJSON(JSONObject(json))
    }.getOrNull()?.takeIf(::isSafeReaderLocator)

    private fun readerIntent(bookId: Long): PendingIntent {
        // No SINGLE_TOP: ReaderActivity has no onNewIntent, so a notification tap for a different
        // book must recreate the reader from the book id and durable locator instead of reusing it.
        val intent = ReaderActivity.intent(this, bookId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            bookId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val BIND_ACTION = "app.peekturn.reader.narration.BIND"
        private const val EXTRA_CONTROL_TOKEN = "control_token"
        private val controlToken = UUID.randomUUID().toString()

        /**
         * Stops narration through the app-private binder, waits for playback cancellation, and only
         * then clears the cache behind the shared synthesis gate. A queued synthesis cannot run
         * between the stop and the clear.
         */
        suspend fun clearCache(context: Context) {
            val app = context.applicationContext as PeekturnApp
            var connection: ServiceConnection? = null
            try {
                val binder = suspendCancellableCoroutine { continuation ->
                    val created = object : ServiceConnection {
                        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
                            val bound = service as? Binder
                            if (bound != null) continuation.resume(bound)
                            else continuation.resumeWith(Result.failure(IllegalStateException("Narration binder unavailable")))
                        }

                        override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
                        override fun onNullBinding(name: android.content.ComponentName?) {
                            continuation.resumeWith(Result.failure(IllegalStateException("Narration binder unavailable")))
                        }
                    }
                    connection = created
                    if (!context.bindService(
                            Intent(context, NarrationService::class.java)
                                .setAction(BIND_ACTION)
                                .putExtra(EXTRA_CONTROL_TOKEN, controlToken),
                            created,
                            Context.BIND_AUTO_CREATE
                        )
                    ) {
                        continuation.resumeWith(Result.failure(IllegalStateException("Narration service unavailable")))
                    }
                }
                binder.stop()
                app.graph.narrationProviderGate.withLock { app.graph.sentenceAudio.clear() }
            } finally {
                connection?.let { runCatching { context.unbindService(it) } }
            }
        }

        fun bind(context: Context, connection: ServiceConnection) {
            context.bindService(
                Intent(context, NarrationService::class.java)
                    .setAction(BIND_ACTION)
                    .putExtra(EXTRA_CONTROL_TOKEN, controlToken),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }

        fun unbind(context: Context, connection: ServiceConnection) {
            runCatching { context.unbindService(connection) }
        }
    }
}
