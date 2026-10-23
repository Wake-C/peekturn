package app.peekturn.reader.narration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import app.peekturn.reader.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground owner of saved-audio preparation. It does not play audio and has no media session;
 * it only keeps one manager job above the process lifetime limit and exposes real progress plus a
 * pause action. Preparation is resumable, so the service is non-sticky and a process death leaves a
 * durable PAUSED version for a manual resume.
 *
 * Android's foreground-service limits are handled honestly: API 35 uses `mediaProcessing`, older
 * releases `dataSync`, and a platform timeout pauses and joins the manager before the service stops.
 */
class PreparedAudioService : Service() {
    private val graph get() = (application as PeekturnApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications get() = getSystemService(NotificationManager::class.java)

    private var work: Job? = null
    private var foregroundStarted = false
    private var bookId: Long = -1L
    private var lastVersionId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted && !startForegroundSafely(startingNotification())) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PREPARE -> {
                val requested = intent.getLongExtra(EXTRA_BOOK, -1L)
                if (requested >= 0L) request(requested, null)
            }
            ACTION_RESUME -> {
                val version = intent.getStringExtra(EXTRA_VERSION)
                if (!version.isNullOrBlank()) request(null, version)
            }
            ACTION_PAUSE -> pauseFromNotification()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android expects the service to stop within seconds. Cancelling the job makes the manager
        // checkpoint PAUSED in its own NonCancellable teardown; clips are published atomically, so
        // a later process death still leaves a resumable version.
        work?.cancel()
        releaseWakeLock()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        work?.cancel()
        releaseWakeLock()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun request(prepareBook: Long?, resumeVersion: String?) {
        if (work?.isActive == true) {
            // The manager's single job is already running; this start must still promote itself
            // to foreground for its own startId without launching a second generation.
            startForegroundSafely(startingNotification())
            return
        }
        if (prepareBook != null) bookId = prepareBook
        resumeVersion?.let { lastVersionId = it }
        if (!foregroundStarted && !startForegroundSafely(startingNotification())) return
        acquireWakeLock()
        work = scope.launch {
            try {
                graph.preparedAudio.run(prepareBook, resumeVersion)
            } finally {
                finishTerminal()
            }
        }
    }

    private fun pauseFromNotification() {
        scope.launch {
            work?.cancelAndJoin()
            graph.preparedAudio.pause()
            finishTerminal()
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeProgress() {
        scope.launch {
            combine(
                graph.preparedAudio.activeVersionId,
                graph.preparedAudio.versions,
                graph.preparedAudio.busy
            ) { active, versions, busy -> Triple(active, versions, busy) }
                .sample(1_000L)
                .collect { (active, versions, busy) ->
                    if (!foregroundStarted) return@collect
                    if (active != null) lastVersionId = active
                    val version = lastVersionId?.let { id -> versions.firstOrNull { it.id == id } }
                    if (version != null) {
                        bookId = version.bookId
                        acquireWakeLock()
                        updateNotification(version, busy)
                    }
                }
        }
    }

    private suspend fun finishTerminal() {
        releaseWakeLock()
        val id = lastVersionId ?: graph.preparedAudio.lastFinishedVersionId
        val version = id?.let { versionId ->
            graph.preparedAudio.versions.value.firstOrNull { it.id == versionId }
        }
        val final = finalNotification(version)
        if (foregroundStarted) {
            notifications.notify(NOTIFICATION_ID, final)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun startingNotification(): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.prepared_audio_title_starting))
            .setContentText(getString(R.string.prepared_audio_progress_unknown))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    private fun updateNotification(version: PreparedAudioVersion, busy: Boolean) {
        val builder = baseBuilder()
            .setContentTitle(titleFor(version.status))
            .setContentText(progressText(version))
            .setOngoing(busy)
        val progress = progress(version)
        if (progress != null) builder.setProgress(progress.first, progress.second, false)
        else builder.setProgress(0, 0, true)
        if (busy) {
            builder.addAction(
                0,
                getString(R.string.prepared_audio_pause),
                pauseIntent()
            )
        }
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    private fun finalNotification(version: PreparedAudioVersion?): Notification {
        val status = version?.status
        val failed = status == PreparedAudioStatus.FAILED ||
            (version == null && graph.preparedAudio.error.value != null)
        val builder = baseBuilder()
            .setContentTitle(
                when {
                    status == PreparedAudioStatus.READY -> getString(R.string.prepared_audio_title_ready)
                    failed -> getString(R.string.prepared_audio_title_failed)
                    else -> getString(R.string.prepared_audio_title_paused)
                }
            )
            .setOngoing(false)
            .setAutoCancel(true)
        if (version != null) {
            builder.setContentText(progressText(version))
            progress(version)?.let { builder.setProgress(it.first, it.second, false) }
        } else {
            builder.setContentText(
                getString(
                    if (failed) R.string.prepared_audio_retry_hint
                    else R.string.prepared_audio_title_paused
                )
            )
        }
        return builder.build()
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.peekturn_monochrome)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (bookId >= 0L) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    bookId.toInt(),
                    ReaderActivity.intent(this, bookId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return builder
    }

    private fun pauseIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, PreparedAudioService::class.java).setAction(ACTION_PAUSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun titleFor(status: PreparedAudioStatus): String = when (status) {
        PreparedAudioStatus.INDEXING, PreparedAudioStatus.PREPARING ->
            getString(R.string.prepared_audio_title_preparing)
        PreparedAudioStatus.READY -> getString(R.string.prepared_audio_title_ready)
        PreparedAudioStatus.FAILED -> getString(R.string.prepared_audio_title_failed)
        PreparedAudioStatus.PAUSED -> getString(R.string.prepared_audio_title_paused)
    }

    private fun progressText(version: PreparedAudioVersion): String =
        getString(R.string.prepared_audio_progress, version.completed, version.total)

    private fun progress(version: PreparedAudioVersion): Pair<Int, Int>? {
        if (version.total <= 0) return null
        return version.total to version.completed.coerceIn(0, version.total)
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return try {
            // Core 1.16 ServiceCompat masks types to Android 14's set, dropping mediaProcessing.
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (failure: Exception) {
            graph.developerLog.recordNarration(
                "event=prepared_foreground_rejected api=${Build.VERSION.SDK_INT} " +
                    "type=$type kind=${failure.javaClass.simpleName}"
            )
            // A restricted background start cannot show preparation progress; report a fixed code.
            runCatching { graph.preparedAudio.reportStartDenied() }
            stopSelf()
            false
        }
    }

    private fun createChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.prepared_audio_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.prepared_audio_channel_description)
                setShowBadge(false)
            }
            notifications.createNotificationChannel(channel)
        }
    }

    /**
     * A foreground service does not keep the CPU awake. Progress renews this bounded lock at
     * most once per five minutes, not once per indexed sentence.
     */
    private fun acquireWakeLock() {
        runCatching {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "peekturn:prepared-audio")
                .apply {
                    setReferenceCounted(false)
                    wakeLock = this
                }
            val now = SystemClock.elapsedRealtime()
            if (lock.isHeld && now - wakeLockAcquiredAtMs < WAKE_LOCK_TIMEOUT_MS / 2L) return
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLockAcquiredAtMs = now
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    internal companion object {
        private const val CHANNEL_ID = "prepared_audio"
        private const val NOTIFICATION_ID = 0x5041
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
        private const val ACTION_PREPARE = "app.peekturn.reader.prepared.PREPARE"
        private const val ACTION_RESUME = "app.peekturn.reader.prepared.RESUME"
        private const val ACTION_PAUSE = "app.peekturn.reader.prepared.PAUSE"
        private const val EXTRA_BOOK = "book_id"
        private const val EXTRA_VERSION = "version_id"

        fun prepareIntent(context: Context, bookId: Long): Intent =
            Intent(context, PreparedAudioService::class.java)
                .setAction(ACTION_PREPARE)
                .putExtra(EXTRA_BOOK, bookId)

        fun resumeIntent(context: Context, versionId: String): Intent =
            Intent(context, PreparedAudioService::class.java)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_VERSION, versionId)
    }
}
