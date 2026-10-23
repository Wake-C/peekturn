package app.peekturn.reader.narration

import android.content.Context
import androidx.core.content.ContextCompat
import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.model.SentenceSpeechProvider
import app.peekturn.reader.reader.EpubEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Owns the app's single saved-audio preparation job. It snapshots the current provider choice and
 * actual voice, opens an independent publication, indexes every sentence with Readium's own
 * tokenizer, and renders one complete clip per sentence into [PreparedAudioStore]. Nothing here
 * writes a reading position or reading stats.
 *
 * Playback and pronunciation have priority. Starting preparation is refused while either is alive,
 * and [pause] cancels and joins the active job (closing its resolver and renderer) before returning,
 * so a player or the system TTS engine never competes with preparation. Preparation itself never
 * stops the user's playback; it simply declines to start.
 *
 * A changed provider, accent, voice or book fingerprint is a fixed `voice_changed` failure with all
 * completed clips retained. Provider and content failures are reported by code and counts only;
 * book text and private paths never enter a diagnostic or a stored error.
 */
internal class PreparedAudioManager(
    context: Context,
    private val store: PreparedAudioStore,
    private val books: PeekturnStore,
    private val engine: EpubEngine,
    private val cache: SentenceAudioCache,
    private val providerGate: Mutex,
    private val systemSynthesisGate: Semaphore,
    private val onDiagnostic: (String) -> Unit = {}
) {
    private val application = context.applicationContext
    private val runGuard = Mutex()

    val versions: StateFlow<List<PreparedAudioVersion>> = store.versions

    private val busyMutable = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyMutable.asStateFlow()

    private val errorMutable = MutableStateFlow<String?>(null)

    /** The last fixed failure code; UI maps it to localized text. */
    val error: StateFlow<String?> = errorMutable.asStateFlow()

    private val activeVersionMutable = MutableStateFlow<String?>(null)
    internal val activeVersionId: StateFlow<String?> = activeVersionMutable.asStateFlow()

    /** The last version this manager touched, for the service's terminal notification. */
    @Volatile internal var lastFinishedVersionId: String? = null

    @Volatile private var activeJob: Job? = null
    private val activeJobLock = Any()
    private val bookMutex = Mutex()
    private val deletingBooks = mutableSetOf<Long>()
    @Volatile private var startingVersionId: String? = null
    @Volatile private var startingBookId: Long? = null
    @Volatile private var pausedBeforeStart = false
    @Volatile private var narrationActive = false
    @Volatile private var speechActive = false

    fun prepare(bookId: Long) {
        pausedBeforeStart = false
        if (!canStart()) return
        startService(PreparedAudioService.prepareIntent(application, bookId))
    }

    fun resume(versionId: String) {
        pausedBeforeStart = false
        if (!canStart()) return
        startService(PreparedAudioService.resumeIntent(application, versionId))
    }

    suspend fun delete(versionId: String): Boolean {
        clearError()
        if (activeVersionMutable.value == versionId) pause()
        val deleted = try {
            store.delete(versionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish("storage")
            return false
        }
        if (!deleted) publish("in_use")
        return deleted
    }

    /**
     * Removes the book and its saved audio under one admission guard. Cancels and joins preparation
     * first; a saved-playback lease or storage failure leaves the library book in place.
     */
    suspend fun deleteBook(bookId: Long): Boolean {
        bookMutex.withLock {
            synchronized(deletingBooks) { deletingBooks += bookId }
        }
        return try {
            if (startingBookId == bookId || startingVersionIsBook(bookId)) pause()
            bookMutex.withLock {
                store.refresh()
                var clean = true
                for (version in store.versions.value.filter { it.bookId == bookId }) {
                    if (!store.delete(version.id)) clean = false
                }
                if (!clean || store.versions.value.any { it.bookId == bookId }) {
                    false
                } else {
                    books.deleteBook(bookId)
                    true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            synchronized(deletingBooks) { deletingBooks -= bookId }
        }
    }

    private fun startingVersionIsBook(bookId: Long): Boolean {
        val versionId = startingVersionId ?: return false
        return store.versions.value.firstOrNull { it.id == versionId }?.bookId == bookId
    }

    /**
     * Cancels the active job and waits for it to release its resolver, renderer and publication.
     * Safe to call from playback or pronunciation arbitration; priority flags are not touched by it.
     */
    suspend fun pause() {
        pausedBeforeStart = true
        val job = synchronized(activeJobLock) { activeJob } ?: return
        if (job == currentCoroutineContext()[Job]) return
        // Priority owners may call this from a job they are about to cancel. Teardown must still
        // run to completion, and no teardown failure may propagate into playback or pronunciation.
        withContext(NonCancellable) {
            runCatching { job.cancelAndJoin() }
        }
    }

    /** Blocks new preparation while any narration session is alive, including paused playback. */
    fun setNarrationActive(active: Boolean) {
        narrationActive = active
    }

    /** Blocks new preparation while popup pronunciation owns the speech engine. */
    fun setSpeechActive(active: Boolean) {
        speechActive = active
    }

    fun clearError() {
        errorMutable.value = null
    }

    /**
     * The service's single entry point. It is a no-op that publishes a fixed code when another
     * generation is active or a priority owner is alive.
     */
    internal suspend fun run(bookId: Long?, versionId: String?) {
        val self = currentCoroutineContext()[Job]
        // Registered before the priority check so a pause issued concurrently with a start joins
        // this job instead of returning while preparation is still beginning.
        synchronized(activeJobLock) { activeJob = self }
        try {
            if (pausedBeforeStart) {
                pausedBeforeStart = false
                return
            }
            if (!priorityAllowsStart()) return
            if (!runGuard.tryLock()) {
                publish("busy")
                return
            }
            val workJob = Job(self)
            val workScope = CoroutineScope(SupervisorJob(workJob) + Dispatchers.Default)
            var version: PreparedAudioVersion? = null
            var opened: OpenedSource? = null
            try {
                busyMutable.value = true
                errorMutable.value = null
                val run = begin(bookId, versionId, workScope)
                val started = run.version
                val source = run.source
                version = started
                activeVersionMutable.value = started.id
                opened = source
                if (run.alreadyReady) {
                    diagnostic("event=prepared_existing_ready version=${started.id.take(8)} total=${started.total}")
                    return
                }
                // A version with at least one clip has already completed its index pass: clips are
                // only written after finishIndex. Re-index only when no clip proves completeness.
                if (started.completed > 0) {
                    diagnostic(
                        "event=prepared_index_retained version=${started.id.take(8)} " +
                            "completed=${started.completed} total=${started.total}"
                    )
                } else {
                    index(started, run.file)
                }
                store.setStatus(started.id, PreparedAudioStatus.PREPARING)
                synthesize(started, source)
                ensureBookPersisted(started.bookId)
                store.complete(started.id)
                val finished = store.versions.value.firstOrNull { it.id == started.id }
                diagnostic(
                    "event=prepared_complete version=${started.id.take(8)} " +
                        "completed=${finished?.completed ?: 0} total=${finished?.total ?: started.total}"
                )
            } catch (cancelled: CancellationException) {
                (version?.id ?: startingVersionId)?.let { pauseAfterCancellation(it) }
                throw cancelled
            } catch (failure: GenerationFailure) {
                fail(version?.id ?: startingVersionId, failure.code)
            } catch (failure: PreparedAudioException) {
                fail(version?.id ?: startingVersionId, safeStoreCode(failure.code))
            } catch (_: Exception) {
                fail(version?.id ?: startingVersionId, "provider_error")
            } finally {
                lastFinishedVersionId = version?.id ?: startingVersionId
                startingVersionId = null
                startingBookId = null
                opened?.resolver?.let { runCatching { it.close() } }
                withContext(NonCancellable) {
                    workJob.cancel()
                    runCatching { workJob.join() }
                }
                activeVersionMutable.value = null
                busyMutable.value = false
                runGuard.unlock()
            }
        } finally {
            synchronized(activeJobLock) { if (activeJob === self) activeJob = null }
        }
    }

    /** The service could not enter the foreground; surface the fixed code instead of failing mute. */
    internal fun reportStartDenied() {
        publish("start_denied")
    }

    private fun canStart(): Boolean {
        if (!priorityAllowsStart()) return false
        if (busyMutable.value) {
            publish("busy")
            return false
        }
        return true
    }

    private fun priorityAllowsStart(): Boolean {
        if (narrationActive) {
            publish("narration_active")
            return false
        }
        if (speechActive) {
            publish("speech_active")
            return false
        }
        return true
    }

    private fun startService(intent: android.content.Intent) {
        try {
            ContextCompat.startForegroundService(application, intent)
        } catch (_: Exception) {
            publish("start_denied")
        }
    }

    private suspend fun begin(
        bookId: Long?,
        versionId: String?,
        workScope: CoroutineScope
    ): ActiveRun = if (versionId == null) {
        beginNew(bookId ?: throw GenerationFailure("book_missing"), workScope)
    } else {
        beginResume(versionId, workScope)
    }

    private suspend fun beginNew(bookId: Long, workScope: CoroutineScope): ActiveRun {
        val (book, file) = bookFile(bookId)
        startingBookId = book.id
        if (bookBeingDeleted(book.id)) throw GenerationFailure("book_missing")
        val settings = books.settings.value
        val provider = settings.sentenceSpeechProvider
        val accent = settings.accent
        val source = openSource(provider, accent, workScope)
        return try {
            store.refresh()
            val fingerprint = PreparedAudioStore.fingerprint(file)
            val sameProfile = store.versions.value.filter {
                it.bookId == book.id &&
                    it.bookFingerprint == fingerprint &&
                    it.provider == provider &&
                    it.accent == accent &&
                    it.configuration == source.configuration
            }
            val ready = sameProfile.firstOrNull { it.status == PreparedAudioStatus.READY }
            if (ready != null) {
                diagnostic("event=prepared_existing_ready total=${ready.total}")
                return ActiveRun(ready, file, source, alreadyReady = true)
            }
            val reusable = sameProfile.firstOrNull {
                it.status == PreparedAudioStatus.PAUSED ||
                    it.status == PreparedAudioStatus.FAILED ||
                    it.status == PreparedAudioStatus.INDEXING ||
                    it.status == PreparedAudioStatus.PREPARING
            }
            val version = if (reusable != null) {
                startingVersionId = reusable.id
                diagnostic(
                    "event=prepared_reuse version=${reusable.id.take(8)} " +
                        "completed=${reusable.completed} total=${reusable.total}"
                )
                reusable
            } else {
                bookMutex.withLock {
                    if (bookBeingDeleted(book.id)) throw GenerationFailure("book_missing")
                    if (books.findBook(book.id) == null) throw GenerationFailure("book_missing")
                    // Register ownership before cancellation can discard the committed version.
                    withContext(NonCancellable) {
                        store.create(
                            bookId = book.id,
                            bookFingerprint = fingerprint,
                            provider = provider,
                            accent = accent,
                            configuration = source.configuration,
                            voiceLabel = source.voiceLabel
                        ).also {
                            startingVersionId = it.id
                            diagnostic("event=prepared_create version=${it.id.take(8)} provider=$provider total=0")
                        }
                    }
                }
            }
            ActiveRun(version, file, source)
        } catch (failure: Throwable) {
            source.resolver.close()
            throw failure
        }
    }

    private suspend fun beginResume(versionId: String, workScope: CoroutineScope): ActiveRun {
        store.refresh()
        val version = store.versions.value.firstOrNull { it.id == versionId }
            ?: throw GenerationFailure("not_found")
        if (version.status == PreparedAudioStatus.READY) {
            throw GenerationFailure("already_ready")
        }
        startingVersionId = version.id
        startingBookId = version.bookId
        if (bookBeingDeleted(version.bookId)) throw GenerationFailure("book_missing")
        val (_, file) = bookFile(version.bookId)
        val fingerprint = PreparedAudioStore.fingerprint(file)
        if (fingerprint != version.bookFingerprint) throw GenerationFailure("book_changed")
        val settings = books.settings.value
        if (settings.sentenceSpeechProvider != version.provider || settings.accent != version.accent) {
            throw GenerationFailure("voice_changed")
        }
        val source = openSource(version.provider, version.accent, workScope)
        if (source.configuration != version.configuration) {
            source.resolver.close()
            throw GenerationFailure("voice_changed")
        }
        diagnostic(
            "event=prepared_resume version=${version.id.take(8)} " +
                "completed=${version.completed} total=${version.total}"
        )
        return ActiveRun(version, file, source)
    }

    private suspend fun bookFile(bookId: Long): Pair<app.peekturn.reader.model.Book, File> {
        val book = books.findBook(bookId) ?: throw GenerationFailure("book_missing")
        val file = File(book.filePath)
        if (!file.isFile) throw GenerationFailure("book_missing")
        return book to file
    }

    private suspend fun openSource(
        provider: SentenceSpeechProvider,
        accent: String,
        workScope: CoroutineScope
    ): OpenedSource = when (provider) {
        SentenceSpeechProvider.ANDROID -> {
            val resolver = SystemNarrationSynthesizer(
                context = application,
                cache = cache,
                scope = workScope,
                gate = systemSynthesisGate,
                onDiagnostic = ::diagnostic
            )
            try {
                val profile = resolver.profileSnapshot()
                // A connection nonce cannot identify a resumable book/voice version.
                // Live narration still permits that connection-scoped fallback.
                if (!profile.stableIdentity) throw GenerationFailure("voice_unidentified")
                OpenedSource(
                    resolver = resolver,
                    configuration = profile.configuration,
                    voiceLabel = profile.voiceLabel
                )
            } catch (failure: Throwable) {
                resolver.close()
                throw failure
            }
        }
        SentenceSpeechProvider.MICROSOFT, SentenceSpeechProvider.BAIDU -> {
            val spec = narrationVoice(provider, accent)
                ?: throw GenerationFailure("voice_unsupported")
            val online = onlineNarrationSource(provider)
                ?: throw GenerationFailure("voice_unsupported")
            val label = narrationVoiceLabel(provider, accent)
                ?: throw GenerationFailure("voice_unsupported")
            val resolver = NarrationSynthesizer(
                cache = cache,
                source = NarrationChunkSource { text, spokenAccent ->
                    online.lookup(text, spokenAccent)?.audio
                },
                scope = workScope,
                accent = accent,
                voice = spec,
                providerGate = providerGate
            )
            OpenedSource(resolver, resolver.configuration(), label)
        }
    }

    /**
     * Indexes every sentence before the first provider request. Resume re-runs the idempotent pass
     * so a run interrupted during indexing can never be mistaken for a complete book.
     */
    private suspend fun index(version: PreparedAudioVersion, file: File) {
        try {
            store.setStatus(version.id, PreparedAudioStatus.INDEXING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PreparedAudioException) {
            throw GenerationFailure(safeStoreCode(failure.code))
        }
        ensureBookPersisted(version.bookId)
        val publication = try {
            engine.open(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GenerationFailure("content_unavailable")
        }
        try {
            val content = NarrationContent(publication, onDiagnostic = ::diagnostic)
            var index = 0
            try {
                content.allSentences().collect { sentence ->
                    currentCoroutineContext().ensureActive()
                    ensureBookExists(version.bookId)
                    try {
                        store.addSentence(
                            version.id,
                            PreparedAudioSentence(
                                index = index,
                                text = sentence.text,
                                locatorJson = sentence.locator.toJSON().toString()
                            )
                        )
                    } catch (failure: PreparedAudioException) {
                        throw GenerationFailure(safeStoreCode(failure.code))
                    }
                    index++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: NarrationBookContentError) {
                throw GenerationFailure(failure.code)
            }
            if (index == 0) throw GenerationFailure("empty_book")
            try {
                store.finishIndex(version.id)
            } catch (failure: PreparedAudioException) {
                throw GenerationFailure(safeStoreCode(failure.code))
            }
            diagnostic("event=prepared_indexed version=${version.id.take(8)} total=$index")
        } finally {
            runCatching { publication.close() }
        }
    }

    private suspend fun synthesize(version: PreparedAudioVersion, source: OpenedSource) {
        var afterIndex = -1
        while (true) {
            val page = try {
                store.sentences(version.id, afterIndex, SENTENCE_PAGE)
            } catch (failure: PreparedAudioException) {
                throw GenerationFailure(safeStoreCode(failure.code))
            }
            if (page.isEmpty()) break
            for (sentence in page) {
                currentCoroutineContext().ensureActive()
                ensureBookPersisted(version.bookId)
                if (store.contains(version.id, sentence.text)) continue
                checkFrozenConfiguration(version, source)
                val audio = try {
                    withTimeout(CLIP_TIMEOUT_MS) {
                        source.resolver.audioFor(sentence.text).getOrThrow()
                    }
                } catch (timeout: TimeoutCancellationException) {
                    throw GenerationFailure("timeout")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: NarrationFailure) {
                    throw GenerationFailure(failureCode(failure))
                }
                checkFrozenConfiguration(version, source)
                val pinned = if (audio.configuration == null) {
                    audio.copy(configuration = version.configuration)
                } else {
                    audio
                }
                try {
                    store.saveClip(version.id, sentence.text, pinned)
                } catch (failure: PreparedAudioException) {
                    val code = safeStoreCode(failure.code)
                    throw GenerationFailure(
                        if (code == "configuration_mismatch") "voice_changed" else code
                    )
                }
            }
            afterIndex = page.last().index
        }
    }

    /**
     * The provider/accent setting and the source's own reported configuration must still be the
     * exact frozen snapshot before and after every clip.
     */
    private suspend fun checkFrozenConfiguration(version: PreparedAudioVersion, source: OpenedSource) {
        val settings = books.settings.value
        if (settings.sentenceSpeechProvider != version.provider || settings.accent != version.accent) {
            throw GenerationFailure("voice_changed")
        }
        val current = try {
            source.resolver.configuration()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NarrationFailure) {
            throw GenerationFailure(failureCode(failure))
        } catch (_: Exception) {
            throw GenerationFailure("provider_error")
        }
        if (current != version.configuration) throw GenerationFailure("voice_changed")
    }

    private suspend fun pauseAfterCancellation(versionId: String) {
        withContext(NonCancellable) {
            runCatching { store.setStatus(versionId, PreparedAudioStatus.PAUSED) }
                .onFailure { runCatching { store.setStatus(versionId, PreparedAudioStatus.FAILED, "interrupted") } }
            diagnostic("event=prepared_paused version=${versionId.take(8)}")
        }
    }

    private suspend fun fail(versionId: String?, code: String) {
        if (versionId != null) {
            withContext(NonCancellable) {
                runCatching { store.setStatus(versionId, PreparedAudioStatus.FAILED, code) }
            }
        }
        publish(code)
    }

    private fun publish(code: String) {
        errorMutable.value = code
        diagnostic("event=prepared_failed code=$code")
    }

    private fun bookBeingDeleted(bookId: Long): Boolean =
        synchronized(deletingBooks) { bookId in deletingBooks }

    /**
     * Library deletion must never leave pinned clips for a book that no longer exists. An empty
     * in-memory library is not evidence of deletion: a recreated process loads it asynchronously.
     */
    private fun ensureBookExists(bookId: Long) {
        val snapshot = books.books.value
        if (snapshot.isNotEmpty() && snapshot.none { it.id == bookId }) {
            throw GenerationFailure("book_missing")
        }
    }

    /** Persistence must consult the database, not only a possibly stale library snapshot. */
    private suspend fun ensureBookPersisted(bookId: Long) {
        if (books.findBook(bookId) == null) throw GenerationFailure("book_missing")
    }

    private fun diagnostic(report: String) {
        runCatching { onDiagnostic(report) }
    }

    private fun failureCode(failure: NarrationFailure): String = when (failure) {
        is NarrationFailure.TooLong -> "provider_length"
        is NarrationFailure.InvalidAudio -> "provider_audio"
        is NarrationFailure.TooLarge, is NarrationFailure.Storage -> "storage"
        is NarrationFailure.Device, is NarrationFailure.Unavailable -> "provider_error"
    }

    /** Store codes are fixed identifiers; anything unexpected is reported as storage. */
    private fun safeStoreCode(code: String): String = when (code) {
        "not_found", "invalid_version", "ready_immutable", "ready_requires_complete",
        "index_conflict", "empty_index", "invalid_sentence", "configuration_mismatch",
        "unknown_sentence", "invalid_audio", "too_large", "storage",
        "incomplete", "indexing", "not_ready", "fingerprint_mismatch", "missing_source" -> code
        else -> "storage"
    }

    private data class ActiveRun(
        val version: PreparedAudioVersion,
        val file: File,
        val source: OpenedSource,
        val alreadyReady: Boolean = false
    )

    private class OpenedSource(
        val resolver: NarrationAudioResolver,
        val configuration: String,
        val voiceLabel: String
    )

    private class GenerationFailure(val code: String) : Exception(code)

    private companion object {
        /** One provider request that never returns must fail the version, not hold the book. */
        const val CLIP_TIMEOUT_MS = 5L * 60L * 1000L

        /** A page keeps a huge book's sentence rows out of memory while resuming. */
        const val SENTENCE_PAGE = 64
    }
}
