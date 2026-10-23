package app.peekturn.reader.narration

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.peekturn.reader.diagnostics.diagnosticMetadata
import app.peekturn.reader.speech.SpeechConfigurationKey
import app.peekturn.reader.speech.boundSpeechEngine
import app.peekturn.reader.speech.systemSpeechConfigurationKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** A typed reason a system speech render could not produce a complete word file. */
internal sealed class SystemSpeechError(message: String, val code: Int) : Exception(message) {
    class Unavailable(
        detail: String = "The system speech engine is unavailable.",
        code: Int = TextToSpeech.ERROR
    ) : SystemSpeechError(detail, code)

    class Initialization(
        val status: Int,
        detail: String = "The system speech engine did not initialize (status $status)."
    ) : SystemSpeechError(detail, status)

    class Rejected(
        val status: Int,
        detail: String = "The system speech engine refused the request (status $status)."
    ) : SystemSpeechError(detail, status)

    class Synthesis(
        val status: Int,
        detail: String = "The system speech engine failed the request (error $status)."
    ) : SystemSpeechError(detail, status)

    class Incomplete(
        detail: String = "The system speech engine did not write a complete WAV file."
    ) : SystemSpeechError(detail, TextToSpeech.ERROR_SYNTHESIS)

    class TooLong(
        val limit: Int,
        detail: String = "The sentence is longer than the system speech engine accepts."
    ) : SystemSpeechError(detail, TextToSpeech.ERROR_INVALID_REQUEST)

    class InvalidRequest(
        detail: String = "The system speech request was empty."
    ) : SystemSpeechError(detail, TextToSpeech.ERROR_INVALID_REQUEST)

    class ConfigurationChanged(
        detail: String = "The system speech configuration changed during rendering."
    ) : SystemSpeechError(detail, TextToSpeech.ERROR_INVALID_REQUEST)

    class Timeout(
        val timeoutMs: Long,
        val stage: String
    ) : SystemSpeechError(
        "System speech $stage did not answer within ${timeoutMs}ms.",
        TextToSpeech.ERROR
    )

    class Storage(
        detail: String = "The rendered system speech file could not be written."
    ) : SystemSpeechError(detail, TextToSpeech.ERROR_OUTPUT)
}

/** The platform TTS surface this source needs: system-chosen engine, its own default voice. */
internal interface SystemSpeechConnection {
    /** Best-effort diagnostics only; unreadable metadata never gates synthesis. */
    val voice: Voice?

    /** Best-effort bound package; Android may hide it even from a working connection. */
    val enginePackage: String?

    fun setAudioAttributes(attributes: AudioAttributes)
    fun setSpeechRate(rate: Float): Int
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?)
    fun synthesizeToFile(text: String, params: Bundle?, file: File, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

internal fun interface SystemSpeechConnectionFactory {
    fun create(context: Context, engine: String?, onInit: (Int) -> Unit): SystemSpeechConnection
}

/** The production narrowing point; tests supply the same tiny surface. */
internal object AndroidSystemSpeechConnectionFactory : SystemSpeechConnectionFactory {
    override fun create(
        context: Context,
        engine: String?,
        onInit: (Int) -> Unit
    ): SystemSpeechConnection = AndroidSystemSpeechConnection(context, engine, onInit)
}

private class AndroidSystemSpeechConnection(
    context: Context,
    engine: String?,
    onInit: (Int) -> Unit
) : SystemSpeechConnection {
    private val textToSpeech = if (engine == null) {
        TextToSpeech(context, onInit)
    } else {
        TextToSpeech(context, onInit, engine)
    }

    override val voice: Voice?
        get() = runCatching { textToSpeech.voice }.getOrNull()

    override val enginePackage: String?
        get() = boundSpeechEngine(textToSpeech)

    override fun setAudioAttributes(attributes: AudioAttributes) {
        runCatching { textToSpeech.setAudioAttributes(attributes) }
    }

    override fun setSpeechRate(rate: Float): Int = textToSpeech.setSpeechRate(rate)

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
        textToSpeech.setOnUtteranceProgressListener(listener)
    }

    override fun synthesizeToFile(
        text: String,
        params: Bundle?,
        file: File,
        utteranceId: String
    ): Int = textToSpeech.synthesizeToFile(text, params, file, utteranceId)

    override fun stop(): Int = textToSpeech.stop()

    override fun shutdown() {
        textToSpeech.shutdown()
    }
}

/**
 * Renders one complete sentence to a WAV file through Android's current preferred engine.
 *
 * Exactly one reusable [TextToSpeech] connection follows the system engine and the engine's own
 * default voice: this class has no setVoice/setLanguage path at all. The app-wide [gate] admits at
 * most two file renders across every narration session; the platform service serializes them.
 *
 * A changed [systemSpeechConfigurationKey] (system preference or a foreground settings refresh)
 * rebinds the connection but does not enter the audio identity: an unchanged public engine, version,
 * voice and prosody keeps the same cache. A provider-side model change that leaves every public
 * identifier identical is not observable through the platform API; only an explicit app cache clear
 * addresses that case, never a foreground-return invalidation. Callbacks are matched to the exact
 * connection and utterance, so a late onDone/onError cannot finish a cancelled request, and a
 * configuration is verified again before a rendered file may be published. When the engine hides
 * its bound identity or voice, a connection nonce makes that audio reusable only inside the
 * connection that produced it. A disconnected service is reinitialized once per system
 * key/failure chain.
 */
internal class SystemSpeechRenderer(
    context: Context,
    private val gate: Semaphore,
    private val onDiagnostic: (String) -> Unit = {},
    private val platformFactory: SystemSpeechConnectionFactory = AndroidSystemSpeechConnectionFactory,
    private val initializationTimeoutMs: Long = 15_000L,
    private val synthesisTimeoutMs: Long = 180_000L,
    private val maxSpeechInputLength: Int = TextToSpeech.getMaxSpeechInputLength()
) {
    private val application = context.applicationContext
    private val lock = Any()
    private val lifecycle = Mutex()
    private val preparation = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actives = mutableMapOf<String, Active>()
    private var binding: Binding? = null
    private var serviceRecoveryKey: SpeechConfigurationKey? = null

    /** Diagnostics only: the last key this process bound, for rebind evidence. */
    private var lastBoundKey: SpeechConfigurationKey? = null

    @Volatile
    private var closed = false

    /**
     * The cache profile of the current system configuration. Reuses the live connection and its
     * already-observed profile without another engine metadata call; binds only when the system
     * key changed or no connection exists. A failed initialization is reported honestly.
     */
    suspend fun configuration(): NarrationVoiceSpec =
        withContext(Dispatchers.IO) { configurationOnWorker() }

    private suspend fun configurationOnWorker(): NarrationVoiceSpec = lifecycle.withLock {
        if (closed) throw SystemSpeechError.Unavailable("Narration speech is closed.")
        for (attempt in 0..1) {
            val latest = systemSpeechConfigurationKey(application)
            val current = synchronized(lock) { binding }
            if (current != null && current.key == latest) {
                current.profile?.let { return@withLock it }
                try {
                    return@withLock awaitReady(current)
                } catch (changed: SystemSpeechError.ConfigurationChanged) {
                    if (attempt == 1) throw changed
                    continue
                }
            }
            val recoveryKey = synchronized(lock) { serviceRecoveryKey }
            if (recoveryKey != null && recoveryKey != latest) {
                synchronized(lock) {
                    if (serviceRecoveryKey == recoveryKey) serviceRecoveryKey = null
                }
            }
            val rebindReason = rebindReasonFor(latest, attempt)
            resetFor(latest, rebindReason)
            try {
                val created = createBindingLocked(latest, rebindReason)
                val profile = awaitReady(created)
                return@withLock profile
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: SystemSpeechError.ConfigurationChanged) {
                if (attempt == 1) throw changed
            } catch (error: SystemSpeechError) {
                if (closed) throw error
                // A failed or timed-out connection must not fail a request for the engine the
                // user has since chosen. Only a genuine system-key change is retried; the same
                // key remains an honest failure.
                val current = systemSpeechConfigurationKey(application)
                if (current != latest) {
                    if (attempt == 1) throw SystemSpeechError.ConfigurationChanged()
                    continue
                }
                throw error
            }
        }
        throw SystemSpeechError.ConfigurationChanged()
    }

    /**
     * Renders [sentence] once, up to the engine's own input bound, and publishes [target] only
     * after onDone and after re-checking that [expected] is still the current profile.
     */
    suspend fun render(sentence: String, target: File, expected: NarrationVoiceSpec) {
        if (closed) throw SystemSpeechError.Unavailable("Narration speech is closed.")
        if (sentence.isBlank()) throw SystemSpeechError.InvalidRequest()
        val limit = maxSpeechInputLength.coerceAtLeast(1)
        if (sentence.length > limit) throw SystemSpeechError.TooLong(limit)
        if (configuration() != expected) throw SystemSpeechError.ConfigurationChanged()

        gate.withPermit {
            if (closed) throw SystemSpeechError.Unavailable("Narration speech is closed.")
            if (configuration() != expected) throw SystemSpeechError.ConfigurationChanged()
            val latest = systemSpeechConfigurationKey(application)
            val connection = synchronized(lock) {
                binding?.takeIf { !it.isClosed && it.key == latest }
            } ?: throw SystemSpeechError.ConfigurationChanged()
            try {
                synthesize(connection, sentence, target, expected)
                noteRenderSucceeded(connection)
            } catch (error: SystemSpeechError) {
                if (!error.isRecoverableServiceFailure() || closed) throw error
                if (!claimServiceRecovery()) throw error
                diagnostic(
                    "Android narration TTS: engine=" + engineLabel(connection) +
                        " connection=" + connection.nonce.take(8) +
                        " service failure (code " + error.code +
                        "); reinitializing once for the same system choice"
                )
                val recovered = rebindAfterServiceFailure(expected)
                synthesize(recovered, sentence, target, expected)
                noteRenderSucceeded(recovered)
            }
        }
    }

    /** One recovery per system key/failure chain, carried across nonce-only re-resolution. */
    private fun claimServiceRecovery(): Boolean {
        val latest = systemSpeechConfigurationKey(application)
        return synchronized(lock) {
            if (serviceRecoveryKey == latest) {
                false
            } else {
                serviceRecoveryKey = latest
                true
            }
        }
    }

    private fun noteRenderSucceeded(connection: Binding) {
        synchronized(lock) {
            if (binding === connection && !connection.isClosed) {
                serviceRecoveryKey = null
            }
        }
    }

    /**
     * One bounded recovery for a disconnected platform service. The same system choice is bound
     * again; a changed choice or a fresh identity mismatch is refused instead of publishing audio.
     */
    private suspend fun rebindAfterServiceFailure(expected: NarrationVoiceSpec): Binding =
        lifecycle.withLock {
            if (closed) throw SystemSpeechError.Unavailable("Narration speech is closed.")
            val latest = systemSpeechConfigurationKey(application)
            resetFor(latest, "service_recovery")
            val created = createBindingLocked(latest, "service_recovery")
            val profile = awaitReady(created)
            if (profile != expected) throw SystemSpeechError.ConfigurationChanged()
            synchronized(lock) { binding } ?: throw SystemSpeechError.Unavailable()
        }

    /** Stops every queued or inflight render; their late callbacks are ignored, files removed. */
    fun cancelPending() {
        val pending = synchronized(lock) {
            val copy = actives.values.toList()
            actives.clear()
            copy
        }
        pending.forEach { active ->
            logActiveTerminal(active, "cancel_pending", "cancelled")
            runCatching { active.binding.speech?.stop() }
            active.continuation.cancel(
                CancellationException("System speech rendering was cancelled.")
            )
        }
    }

    /** Releases the one connection; every waiter is woken with a typed close failure. */
    fun close() {
        val state: Pair<Binding?, Int>? = synchronized(lock) {
            if (closed) return@synchronized null
            closed = true
            val old = binding
            binding = null
            old?.isClosed = true
            old to actives.size
        }
        preparation.cancel()
        failActives(SystemSpeechError.Unavailable("Narration speech is closed."), "closed")
        state?.let { (previous, activeCount) ->
            if (previous != null) {
                logConnectionRelease(previous, "closed", activeCount, null)
                release(previous, SystemSpeechError.Unavailable("Narration speech is closed."))
            }
        }
    }

    private fun failActives(failure: SystemSpeechError, reason: String) {
        val cause = when (failure) {
            is SystemSpeechError.ConfigurationChanged -> "configuration_changed"
            else -> "closed"
        }
        val pending = synchronized(lock) {
            val copy = actives.values.toList()
            actives.clear()
            copy
        }
        pending.forEach { active ->
            // The map is already cleared here: record the terminal state before the callback
            // path can lose it, so a request never disappears without a fixed reason.
            logActiveTerminal(active, reason, cause)
            runCatching { active.binding.speech?.stop() }
            if (active.continuation.isActive) {
                active.continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    private suspend fun synthesize(
        connection: Binding,
        sentence: String,
        target: File,
        expected: NarrationVoiceSpec
    ) {
        val parent = target.parentFile ?: application.cacheDir
        parent.mkdirs()
        val part = File(parent, ".${target.name}.${UUID.randomUUID()}.wav")
        try {
            awaitFileSynthesis(connection, sentence, part, expected)
            val complete = part.isFile &&
                part.length() > 0L &&
                WaveAudio.inspect(part) != null
            if (!complete) throw SystemSpeechError.Incomplete()
            // The renderer checks again after onDone: a superseded voice cannot publish here.
            if (configuration() != expected) throw SystemSpeechError.ConfigurationChanged()
            publish(part, target)
        } finally {
            part.delete()
        }
    }

    private suspend fun awaitFileSynthesis(
        connection: Binding,
        sentence: String,
        part: File,
        expected: NarrationVoiceSpec
    ) {
        val utteranceId = UUID.randomUUID().toString()
        val queuedAtMs = SystemClock.elapsedRealtime()
        var pendingWatch: Job? = null
        try {
            withTimeout(synthesisTimeoutMs.coerceAtLeast(1)) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val active = Active(connection, continuation, sentence.length, utteranceId)
                    synchronized(lock) {
                        if (closed) throw SystemSpeechError.Unavailable("Narration speech is closed.")
                        if (connection.isClosed || connection.profile != expected) {
                            throw SystemSpeechError.ConfigurationChanged()
                        }
                        actives[utteranceId] = active
                    }
                    diagnostic(
                        "event=synthesis_queued request=${utteranceId.take(8)} " +
                            "connection=${connection.nonce.take(8)} chars=${sentence.length} " +
                            "profile=${sentenceAudioKey("", expected).take(12)} engine=${engineLabel(connection)}"
                    )
                    pendingWatch = startPendingWatch(connection, utteranceId, active)
                    continuation.invokeOnCancellation {
                        pendingWatch?.cancel()
                        val owned = synchronized(lock) {
                            if (actives[utteranceId] === active) {
                                actives.remove(utteranceId)
                                !connection.isClosed && binding === connection
                            } else {
                                false
                            }
                        }
                        // A late cancellation after onDone no longer owns the utterance; stopping
                        // then would flush a newer request queued on the same connection.
                        if (owned) {
                            val now = SystemClock.elapsedRealtime()
                            diagnostic(
                                "event=synthesis_cancelled request=${utteranceId.take(8)} " +
                                    "connection=${connection.nonce.take(8)} reason=continuation_cancelled " +
                                    "stopFlush=true elapsedMs=${now - active.enqueuedAtMs} " +
                                    "queueMs=${if (active.startedAtMs > 0L) active.startedAtMs - active.enqueuedAtMs else now - active.enqueuedAtMs} " +
                                    "serviceMs=${if (active.startedAtMs > 0L) now - active.startedAtMs else -1L}"
                            )
                            runCatching { connection.speech?.stop() }
                        }
                    }
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val accepted = try {
                        connection.speech?.synthesizeToFile(sentence, null, part, utteranceId)
                            ?: TextToSpeech.ERROR
                    } catch (_: RuntimeException) {
                        TextToSpeech.ERROR
                    }
                    if (accepted != TextToSpeech.SUCCESS) {
                        val removed = synchronized(lock) { actives.remove(utteranceId) }
                        diagnostic(
                            "event=synthesis_rejected request=${utteranceId.take(8)} " +
                                "connection=${connection.nonce.take(8)} code=$accepted " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - active.enqueuedAtMs} " +
                                "engine=${engineLabel(connection)}"
                        )
                        if (removed != null && continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(SystemSpeechError.Rejected(accepted))
                            )
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            diagnostic(
                "event=synthesis_timeout request=${utteranceId.take(8)} " +
                    "connection=${connection.nonce.take(8)} timeoutMs=$synthesisTimeoutMs " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - queuedAtMs}"
            )
            throw SystemSpeechError.Timeout(synthesisTimeoutMs, "synthesis")
        } finally {
            pendingWatch?.cancel()
        }
    }

    /**
     * One bounded, read-only diagnostic: reports a request still registered after
     * [PENDING_WARNING_MS] with no terminal callback. It fires at most once per request, reads the
     * active map under the state lock, never cancels/releases/retries or touches synthesis, and is
     * cancelled when the request completes.
     */
    private fun startPendingWatch(
        connection: Binding,
        utteranceId: String,
        active: Active
    ): Job? = preparation.launch {
        delay(PENDING_WARNING_MS)
        val stillPending = synchronized(lock) { actives[utteranceId] === active }
        if (!stillPending) return@launch
        val now = SystemClock.elapsedRealtime()
        val started = active.startedAtMs
        diagnostic(
            "event=synthesis_pending request=${utteranceId.take(8)} " +
                "connection=${connection.nonce.take(8)} elapsedMs=${now - active.enqueuedAtMs} " +
                "queueMs=${if (started > 0L) started - active.enqueuedAtMs else now - active.enqueuedAtMs} " +
                "started=${started > 0L} chars=${active.characters} engine=${configuredEngineLabel(connection)}"
        )
    }

    private fun publish(part: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) throw SystemSpeechError.Storage()
        if (part.renameTo(target)) return
        try {
            part.copyTo(target, overwrite = true)
            part.delete()
        } catch (_: Exception) {
            target.delete()
            throw SystemSpeechError.Storage()
        }
    }

    private fun listenerFor(connection: Binding) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val id = utteranceId ?: return
            val active = synchronized(lock) {
                if (connection.isClosed || binding !== connection) null else actives[id]
            } ?: return
            val now = SystemClock.elapsedRealtime()
            active.startedAtMs = now
            diagnostic(
                "event=synthesis_started request=${id.take(8)} connection=${connection.nonce.take(8)} " +
                    "queueMs=${now - active.enqueuedAtMs} " +
                    "chars=${active.characters} engine=${engineLabel(connection)}"
            )
        }

        override fun onDone(utteranceId: String?) {
            val active = takeActive(connection, utteranceId ?: return) ?: return
            val now = SystemClock.elapsedRealtime()
            diagnostic(
                "event=synthesis_done request=${utteranceId.take(8)} connection=${connection.nonce.take(8)} " +
                    "totalMs=${now - active.enqueuedAtMs} " +
                    "serviceMs=${if (active.startedAtMs > 0L) now - active.startedAtMs else -1L} " +
                    "chars=${active.characters} engine=${engineLabel(connection)}"
            )
            if (active.continuation.isActive) {
                active.continuation.resumeWith(Result.success(Unit))
            }
        }

        @Deprecated("Required by the Android listener contract")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val active = takeActive(connection, utteranceId ?: return) ?: return
            diagnostic(
                "event=synthesis_error request=${utteranceId.take(8)} connection=${connection.nonce.take(8)} " +
                    "code=$errorCode " +
                    "totalMs=${SystemClock.elapsedRealtime() - active.enqueuedAtMs} engine=${engineLabel(connection)}"
            )
            if (active.continuation.isActive) {
                active.continuation.resumeWith(
                    Result.failure(SystemSpeechError.Synthesis(errorCode))
                )
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            val active = takeActive(connection, utteranceId ?: return) ?: return
            diagnostic(
                "event=synthesis_stopped request=${utteranceId.take(8)} connection=${connection.nonce.take(8)} " +
                    "interrupted=$interrupted " +
                    "totalMs=${SystemClock.elapsedRealtime() - active.enqueuedAtMs}"
            )
            if (active.continuation.isActive) {
                active.continuation.resumeWith(
                    Result.failure(
                        SystemSpeechError.Synthesis(
                            TextToSpeech.ERROR,
                            "The system speech engine stopped the request."
                        )
                    )
                )
            }
        }
    }

    private fun takeActive(connection: Binding, utteranceId: String): Active? =
        synchronized(lock) {
            if (connection.isClosed || binding !== connection) return null
            actives.remove(utteranceId)
        }

    private suspend fun createBindingLocked(
        key: SpeechConfigurationKey,
        reason: String
    ): Binding {
        val slot = InitSlot()
        val speech = try {
            platformFactory.create(application, key.enginePackage) { status ->
                val ready = synchronized(lock) {
                    val target = slot.binding
                    if (target == null) {
                        slot.status = status
                        null
                    } else {
                        target
                    }
                }
                if (ready != null) completeInit(ready, status)
            }
        } catch (_: Exception) {
            diagnostic(
                "Android narration TTS: engine=" +
                    diagnosticMetadata(key.enginePackage ?: "system default") +
                    " could not be created (reason=$reason revision=${key.revision})"
            )
            throw SystemSpeechError.Unavailable("The system speech engine could not be created.")
        }

        val created = Binding(key)
        var previousKey: SpeechConfigurationKey? = null
        var activeCount = 0
        val early = synchronized(lock) {
            created.speech = speech
            slot.binding = created
            binding = created
            previousKey = lastBoundKey
            lastBoundKey = key
            activeCount = actives.size
            slot.status
        }
        logConnectionBind(created, reason, previousKey, activeCount)
        if (early != null) completeInit(created, early)
        return created
    }

    private fun completeInit(connection: Binding, status: Int) {
        synchronized(lock) {
            if (closed || connection.isClosed || binding !== connection ||
                connection.ready.isCompleted || connection.preparing) return
            connection.preparing = true
        }
        // Android dispatches initialization on its callback thread, commonly the main thread.
        // A provider's metadata Binder call may wait behind inference; neither that thread nor
        // our state lock may be held while it runs, so pause/close/timeouts remain responsive.
        preparation.launch {
            val result = runCatching {
                if (status != TextToSpeech.SUCCESS) {
                    diagnostic(
                        "Android narration TTS: initialization failed status=$status engine=" +
                            diagnosticMetadata(connection.key.enginePackage ?: "system default")
                    )
                    throw SystemSpeechError.Initialization(status)
                }
                val speech = connection.speech
                    ?: throw SystemSpeechError.Initialization(status, "The system speech engine disappeared.")
                try {
                    speech.setAudioAttributes(SPEECH_ATTRIBUTES)
                    speech.setSpeechRate(FIXED_SPEECH_RATE)
                    speech.setOnUtteranceProgressListener(listenerFor(connection))
                } catch (_: Exception) {
                    throw SystemSpeechError.Initialization(
                        status,
                        "The system speech engine could not be configured."
                    )
                }
                runCatching { profileFor(connection) }.getOrElse { unidentifiedProfile(connection) }
            }
            synchronized(lock) {
                if (closed || connection.isClosed || binding !== connection || connection.ready.isCompleted) {
                    return@launch
                }
                result.fold(
                    onSuccess = { profile ->
                        connection.profile = profile
                        connection.ready.complete(Unit)
                    },
                    onFailure = { connection.ready.completeExceptionally(it) }
                )
            }
        }
    }

    private suspend fun awaitReady(connection: Binding): NarrationVoiceSpec {
        try {
            withTimeout(initializationTimeoutMs.coerceAtLeast(1)) {
                connection.ready.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            val failure = SystemSpeechError.Timeout(initializationTimeoutMs, "initialization")
            invalidate(connection, failure)
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            invalidate(connection, error)
            throw error
        }
        if (connection.isClosed) throw SystemSpeechError.ConfigurationChanged()
        if (connection.key != systemSpeechConfigurationKey(application)) {
            val failure = SystemSpeechError.ConfigurationChanged()
            invalidate(connection, failure)
            throw failure
        }
        return connection.profile ?: throw SystemSpeechError.Unavailable()
    }

    /**
     * A reason only when this branch proves it. A revision delta alone never names an Activity
     * cause: the foreground refresh and an unchanged-provider settings refresh look identical here.
     */
    private fun rebindReasonFor(latest: SpeechConfigurationKey, attempt: Int): String {
        val (previous, previousBound) = synchronized(lock) { binding to lastBoundKey }
        return when {
            previous == null && previousBound == null -> "initial"
            previous == null -> "no_live_binding"
            previous.key.enginePackage != latest.enginePackage -> "engine_changed"
            previous.key.revision != latest.revision -> "config_revision_changed"
            attempt > 0 -> "same_key_retry"
            else -> "same_key_rebind"
        }
    }

    private fun resetFor(newKey: SpeechConfigurationKey, reason: String) {
        val (previous, activeCount) = synchronized(lock) {
            val old = binding
            binding = null
            old?.isClosed = true
            old to actives.size
        }
        // A configuration change is not caller cancellation: every wait gets a terminal
        // ConfigurationChanged it can reconcile, never a bare CancellationException.
        failActives(SystemSpeechError.ConfigurationChanged(), reason)
        previous?.let {
            logConnectionRelease(it, reason, activeCount, newKey)
            release(it, SystemSpeechError.ConfigurationChanged())
        }
    }

    private fun invalidate(connection: Binding, cause: Throwable) {
        val activeCount = synchronized(lock) {
            connection.isClosed = true
            if (binding === connection) binding = null
            actives.size
        }
        logConnectionInvalidated(connection, cause, activeCount)
        release(connection, cause)
    }

    private fun release(connection: Binding, cause: Throwable) {
        synchronized(lock) {
            connection.isClosed = true
            if (binding === connection) binding = null
            connection.ready.completeExceptionally(cause)
        }
        runCatching { connection.speech?.setOnUtteranceProgressListener(null) }
        runCatching { connection.speech?.shutdown() }
    }

    private fun profileFor(connection: Binding): NarrationVoiceSpec {
        val speech = connection.speech
        val bound = speech?.let { runCatching { it.enginePackage }.getOrNull() }
        val version = bound?.let(::engineVersion)
        val reported = speech?.let { runCatching { it.voice }.getOrNull() }
        val voiceName = reported?.name?.takeIf { it.isNotBlank() }
        if (bound == null || version == null || reported == null || voiceName == null) {
            // A requested package is not evidence of the engine that actually bound. Without the
            // bound identity and its voice, reuse is scoped to this exact connection.
            diagnostic(
                "Android narration TTS: bound engine identity unavailable " +
                    "(requested=" +
                    diagnosticMetadata(connection.key.enginePackage ?: "system default") +
                    "); audio reuse is scoped to this connection"
            )
            return unidentifiedProfile(connection)
        }
        val engine = "android-tts:$bound@$version"
        val features = reported.features.orEmpty().sorted()
        val voice = encodeIdentity(
            voiceName,
            reported.locale.toLanguageTag(),
            encodeIdentityList(features),
            reported.quality.toString(),
            reported.latency.toString(),
            reported.isNetworkConnectionRequired.toString()
        )
        diagnostic(
            "Android narration TTS: engine=" + engineLabel(connection) +
                " voice=" + diagnosticMetadata(voiceName) +
                " locale=" + diagnosticMetadata(reported.locale.toLanguageTag()) +
                " (system default; no voice selection)"
        )
        val engineName = runCatching {
            val info = application.packageManager.getApplicationInfo(bound, 0)
            application.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(bound)
        return NarrationVoiceSpec(
            engine = engine,
            voice = voice,
            prosody = "pitch=${systemPitch()};rate=1.0",
            format = WAVE_FORMAT,
            displayLabel = "$engineName · $voiceName"
        )
    }

    private fun unidentifiedProfile(connection: Binding): NarrationVoiceSpec = NarrationVoiceSpec(
        engine = "android-tts:unidentified:${connection.nonce}",
        voice = "engine-default-voice:unidentified:${connection.nonce}",
        prosody = "pitch=${systemPitch()};rate=1.0",
        format = WAVE_FORMAT
    )

    /** The actual system preference applied by Android's TTS settings; never a literal default. */
    private fun systemPitch(): String = runCatching {
        Settings.Secure.getString(
            application.contentResolver,
            Settings.Secure.TTS_DEFAULT_PITCH
        )
    }.getOrNull()
        ?.trim()
        ?.take(16)
        ?.takeIf { candidate -> candidate.isNotEmpty() && candidate.none { it.isISOControl() } }
        ?: "default"

    private fun engineVersion(packageName: String): String? = runCatching {
        val info = application.packageManager.getPackageInfo(packageName, 0)
        val name = info.versionName?.takeIf { it.isNotBlank() } ?: "version-unreported"
        @Suppress("DEPRECATION")
        val code = info.versionCode.toLong()
        "$name:$code:${info.lastUpdateTime}"
    }.getOrNull()

    /**
     * Identity components are opaque: length prefixes keep arbitrary voice names, locales and
     * feature values injective instead of assuming none of them contains the separator.
     */
    private fun encodeIdentity(vararg parts: String): String =
        parts.joinToString("|") { "${it.length}:$it" }

    private fun encodeIdentityList(values: List<String>): String = buildString {
        append(values.size).append(':')
        for (value in values) append(value.length).append(':').append(value)
    }

    private fun engineLabel(connection: Binding): String = diagnosticMetadata(
        connection.speech?.enginePackage
            ?: connection.key.enginePackage
            ?: "unidentified"
    )

    /**
     * Release-safe engine label. It reports only the engine package the configuration key asked
     * for, never the actually bound identity (which needs a Binder query and may be hidden).
     */
    private fun configuredEngineLabel(connection: Binding): String = diagnosticMetadata(
        connection.key.enginePackage ?: "unidentified"
    )

    private fun logConnectionBind(
        connection: Binding,
        reason: String,
        previousKey: SpeechConfigurationKey?,
        activeCount: Int
    ) {
        diagnostic(
            "event=connection_bind connection=${connection.nonce.take(8)} " +
                "revision=${connection.key.revision} " +
                "previousRevision=${previousKey?.revision ?: -1L} " +
                "engineChanged=${previousKey != null && previousKey.enginePackage != connection.key.enginePackage} " +
                "initial=${previousKey == null} reason=$reason activeCount=$activeCount " +
                "engine=${configuredEngineLabel(connection)}"
        )
    }

    private fun logConnectionRelease(
        connection: Binding,
        reason: String,
        activeCount: Int,
        nextKey: SpeechConfigurationKey?
    ) {
        diagnostic(
            "event=connection_release connection=${connection.nonce.take(8)} " +
                "revision=${connection.key.revision} " +
                "nextRevision=${nextKey?.revision ?: -1L} " +
                "engineChanged=${nextKey != null && nextKey.enginePackage != connection.key.enginePackage} " +
                "reason=$reason activeCount=$activeCount engine=${configuredEngineLabel(connection)}"
        )
    }

    private fun logConnectionInvalidated(connection: Binding, cause: Throwable, activeCount: Int) {
        val reason = when (cause) {
            is SystemSpeechError.Timeout -> "initialization_timeout"
            is SystemSpeechError.ConfigurationChanged -> "configuration_changed"
            else -> "initialization_failure"
        }
        diagnostic(
            "event=connection_invalidated connection=${connection.nonce.take(8)} " +
                "revision=${connection.key.revision} reason=$reason " +
                "activeCount=$activeCount engine=${configuredEngineLabel(connection)}"
        )
    }

    /**
     * One terminal record for a queued request whose wait ended without an engine callback, or
     * whose cancellation/rebind path cleared the active map before the callback could land.
     */
    private fun logActiveTerminal(active: Active, reason: String, cause: String) {
        val now = SystemClock.elapsedRealtime()
        val started = active.startedAtMs
        diagnostic(
            "event=synthesis_terminal request=${active.requestId.take(8)} " +
                "connection=${active.binding.nonce.take(8)} reason=$reason cause=$cause " +
                "elapsedMs=${now - active.enqueuedAtMs} " +
                "queueMs=${if (started > 0L) started - active.enqueuedAtMs else now - active.enqueuedAtMs} " +
                "serviceMs=${if (started > 0L) now - started else -1L} " +
                "chars=${active.characters} engine=${configuredEngineLabel(active.binding)}"
        )
    }

    private fun diagnostic(report: String) {
        runCatching { onDiagnostic(report) }
    }

    private class Binding(val key: SpeechConfigurationKey) {
        /** Connection-scoped identity for engines/voices that cannot be named safely. */
        val nonce: String = newNonce()

        @Volatile
        var speech: SystemSpeechConnection? = null

        @Volatile
        var isClosed: Boolean = false

        @Volatile
        var profile: NarrationVoiceSpec? = null
        var preparing: Boolean = false

        val ready = CompletableDeferred<Unit>()
    }

    private class InitSlot {
        var status: Int? = null
        var binding: Binding? = null
    }

    private class Active(
        val binding: Binding,
        val continuation: CancellableContinuation<Unit>,
        val characters: Int,
        val requestId: String,
        val enqueuedAtMs: Long = SystemClock.elapsedRealtime()
    ) {
        @Volatile var startedAtMs: Long = 0L
    }

    private companion object {
        const val FIXED_SPEECH_RATE = 1.0f
        const val WAVE_FORMAT = "wav-pcm-v1"

        /** One bounded diagnostic per request; never a recurring heartbeat. */
        const val PENDING_WARNING_MS = 15_000L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun newNonce(): String = UUID.randomUUID().toString()
    }
}

private fun SystemSpeechError.isRecoverableServiceFailure(): Boolean = when (this) {
    is SystemSpeechError.Rejected ->
        status == TextToSpeech.ERROR || status == TextToSpeech.ERROR_SERVICE
    is SystemSpeechError.Synthesis ->
        status == TextToSpeech.ERROR || status == TextToSpeech.ERROR_SERVICE
    else -> false
}
