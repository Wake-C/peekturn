package app.peekturn.reader.narration

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import app.peekturn.reader.diagnostics.diagnosticMetadata
import app.peekturn.reader.speech.DeviceSpeechFailure
import app.peekturn.reader.speech.SpeechAvailability
import app.peekturn.reader.speech.SpeechEvidence
import app.peekturn.reader.speech.SpeechObservation
import app.peekturn.reader.speech.configureSpeech
import app.peekturn.reader.speech.defaultSpeechEngine
import app.peekturn.reader.speech.deviceSpeechFailure
import app.peekturn.reader.speech.installedSpeechEngines
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/**
 * Android system speech for continuous narration.
 *
 * The adapter speaks through the raw platform [TextToSpeech] API instead of Readium's private
 * Android engine, so one preparation rule shared with popup pronunciation decides whether an
 * English voice is usable. An explicit installed English voice is preferred; when an engine
 * declines every voice but accepts English through `setLanguage`, narration still speaks with an
 * honest "voice language/accent cannot be confirmed" notice. Missing data, an unsupported
 * language, and an uninstalled voice remain explicit failures. Only the default engine is asked
 * first; another installed engine is tried only when the previous one declines during preparation.
 * Once preparation succeeds, a real synthesis error fails that utterance instead of silently
 * switching engines.
 *
 * Text goes straight to the platform engine: no sentence cache, no network request of our own, and
 * no provider fallback. Pause is cancellation, so the next play starts the same sentence again from
 * its beginning. Audio focus and the media-session player remain Readium's.
 */
@ExperimentalReadiumApi
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AndroidNarrationEngineProvider(
    context: Context,
    accent: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNotice: (Int?) -> Unit = {},
    private val onFailureReport: (String?) -> Unit = {}
) : TtsEngineProvider<
    NarrationSettings,
    NarrationPreferences,
    PreferencesEditor<NarrationPreferences>,
    NarrationError,
    NarrationVoice
    > {

    private val application = context.applicationContext

    /** The accent ranks English voices; it never becomes a network request of its own. */
    private val language = when (accent) {
        "UK" -> Language(Locale.UK)
        else -> Language(Locale.US)
    }

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: NarrationPreferences
    ): Try<TtsEngine<NarrationSettings, NarrationPreferences, NarrationError, NarrationVoice>, Error> {
        val speed = (initialPreferences.speed ?: 1f).toDouble()
        val engine = AndroidNarrationEngine(
            context = application,
            language = language,
            initialSpeed = speed,
            onDiagnostic = onDiagnostic,
            onNotice = onNotice,
            onFailureReport = onFailureReport
        )
        engine.submitPreferences(NarrationPreferences(speed = speed.toFloat()))
        return Try.success(engine)
    }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: NarrationPreferences
    ): PreferencesEditor<NarrationPreferences> = object : PreferencesEditor<NarrationPreferences> {
        override val preferences: NarrationPreferences = initialPreferences
        override fun clear() = Unit
    }

    override fun createEmptyPreferences(): NarrationPreferences = NarrationPreferences()

    override fun getPlaybackParameters(settings: NarrationSettings): PlaybackParameters =
        PlaybackParameters(settings.speed)

    override fun updatePlaybackParameters(
        previousPreferences: NarrationPreferences,
        playbackParameters: PlaybackParameters
    ): NarrationPreferences = previousPreferences.copy(speed = playbackParameters.speed)

    override fun mapEngineError(error: NarrationError): PlaybackException = PlaybackException(
        error.message,
        null,
        PlaybackException.ERROR_CODE_UNSPECIFIED
    )
}

/**
 * The narration-facing view of one prepared platform engine. It owns the same [TextToSpeech]
 * instance that [configureSpeech] inspected, waits for its real initialization before speaking,
 * and keeps only the latest sentence while that happens. Every callback is matched against the
 * current engine instance and the current utterance, so a stop, pause or close can never let an
 * old callback finish or resurrect a sentence.
 */
@ExperimentalReadiumApi
internal class AndroidNarrationEngine(
    private val context: Context,
    private val language: Language,
    initialSpeed: Double,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNotice: (Int?) -> Unit = {},
    private val onFailureReport: (String?) -> Unit = {},
    private val platformFactory: PlatformSpeechFactory = AndroidPlatformSpeechFactory,
    private val alternativeEngines: () -> List<String> = { installedSpeechEngines(context) },
    private val initializationTimeoutMs: Long = SPEECH_INITIALIZATION_TIMEOUT_MS,
    private val startTimeoutMs: Long = SPEECH_START_TIMEOUT_MS,
    private val maxSpeechInputLength: Int = TextToSpeech.getMaxSpeechInputLength()
) : TtsEngine<NarrationSettings, NarrationPreferences, NarrationError, NarrationVoice> {

    private val main = Handler(Looper.getMainLooper())
    private val mutableSettings = MutableStateFlow(
        NarrationSettings(
            language = language,
            overrideContentLanguage = true,
            speed = initialSpeed.coerceIn(SPEECH_MIN_SPEED, SPEECH_MAX_SPEED).toFloat()
        )
    )
    override val settings: StateFlow<NarrationSettings> = mutableSettings.asStateFlow()

    /** English is the session's language. The prepared engine may report a substituted accent. */
    override val voices: Set<NarrationVoice> = setOf(NarrationVoice(language))

    @Volatile
    private var closed = false
    private var listener: TtsEngine.Listener<NarrationError>? = null

    private var phase = Phase.Idle
    private var candidates: List<String?> = emptyList()
    private var candidateIndex = 0
    private var generation = 0L
    private var initializing: PlatformSpeech? = null
    private var prepared: PlatformSpeech? = null
    private var timeout: Runnable? = null
    private var startTimeout: Runnable? = null
    private var candidateLabel = "system default engine"
    private var pending: Pending? = null
    private var active: Active? = null
    private var speed = mutableSettings.value.speed.toDouble()
    private var engineStarted = false
    private var missingData = false
    private var recoveryUsed = false
    private val evidence = SpeechEvidence()

    private enum class Phase { Idle, Preparing, Ready, Unavailable }

    private data class Pending(val requestId: TtsEngine.RequestId, val text: String)

    private data class Active(
        val requestId: TtsEngine.RequestId,
        val utteranceId: String,
        val text: String
    )

    override fun setListener(listener: TtsEngine.Listener<NarrationError>?) {
        this.listener = listener
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        dispatch {
            if (closed) return@dispatch
            // Fail explicitly at whichever bound is tighter: the platform input limit or the
            // narration sentence bound. The platform would otherwise silently truncate the text.
            if (text.length > maxSpeechInputLength || narrationChunks(text) == null) {
                listener?.onError(requestId, NarrationError.TooLong())
                diagnostic("Android narration utterance refused: longer than the platform or narration bound")
                return@dispatch
            }
            pending = Pending(requestId, text)
            recoveryUsed = false
            when (phase) {
                Phase.Ready -> {
                    val next = pending ?: return@dispatch
                    pending = null
                    speakNow(next)
                }
                Phase.Unavailable -> startPreparation()
                Phase.Preparing -> Unit
                Phase.Idle -> startPreparation()
            }
        }
    }

    override fun stop() {
        dispatch {
            cancelStartTimeout()
            val pendingId = pending?.requestId
            pending = null
            val previous = active
            active = null
            prepared?.let { speech -> runCatching { speech.stop() } }
            when {
                previous != null -> {
                    listener?.onInterrupted(previous.requestId)
                    diagnostic("Android narration utterance: stop requested")
                }
                pendingId != null -> {
                    listener?.onFlushed(pendingId)
                    diagnostic("Android narration utterance: flushed before start")
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        dispatch {
            pending = null
            active = null
            cancelTimeout()
            cancelStartTimeout()
            initializing?.let(::release)
            prepared?.let(::release)
            initializing = null
            prepared = null
            phase = Phase.Idle
        }
    }

    override fun submitPreferences(preferences: NarrationPreferences) {
        dispatch {
            val updated = (preferences.speed?.toDouble() ?: speed)
                .coerceIn(SPEECH_MIN_SPEED, SPEECH_MAX_SPEED)
            speed = updated
            mutableSettings.value = NarrationSettings(
                language = language,
                overrideContentLanguage = true,
                speed = updated.toFloat()
            )
            prepared?.let { speech ->
                runCatching { speech.setSpeechRate(updated.toFloat()) }
            }
        }
    }

    private fun startPreparation(clearEvidence: Boolean = true) {
        if (closed) return
        phase = Phase.Preparing
        candidates = buildList {
            add(null)
            alternativeEngines().forEach { if (!it.isNullOrBlank()) add(it) }
        }
        candidateIndex = 0
        engineStarted = false
        missingData = false
        if (clearEvidence) evidence.clear()
        prepareNext()
    }

    private fun prepareNext() {
        if (closed) return
        if (candidateIndex >= candidates.size) {
            failPreparation()
            return
        }
        val candidate = candidates[candidateIndex++]
        val packageName = candidate ?: defaultSpeechEngine(context)
        val label = packageName ?: "system default engine"
        candidateLabel = label
        val version = engineVersion(packageName)
        if (packageName != null) evidence.addEngine(label, version)
        val safeLabel = diagnosticMetadata(label)
        diagnostic(
            buildString {
                append("Android narration TTS preparing: engine=").append(safeLabel)
                version?.let { append(" version=").append(diagnosticMetadata(it)) }
                append(if (candidate == null) " (user default)" else " (installed alternative)")
            }
        )
        val started = ++generation
        val speech = try {
            platformFactory.create(context, candidate) { status ->
                // Always post: construction must have assigned the instance before init is handled.
                main.post { onInitialized(started, status) }
            }
        } catch (_: Exception) {
            diagnostic("Android narration TTS initialization: engine=$safeLabel constructor failed")
            prepareNext()
            return
        }
        initializing = speech
        scheduleTimeout(started)
    }

    private fun onInitialized(started: Long, status: Int) {
        if (closed || started != generation) return
        cancelTimeout()
        val speech = initializing ?: return
        initializing = null
        val actualEngine = speech.enginePackage ?: candidateLabel
        evidence.addEngine(actualEngine, engineVersion(actualEngine))
        evidence.observe(SpeechObservation.InitAnswered(status))
        if (status != TextToSpeech.SUCCESS) {
            diagnostic(
                "Android narration TTS initialization: engine=${engineLabel(speech)} failed status=$status"
            )
            release(speech)
            prepareNext()
            return
        }
        engineStarted = true
        configure(speech)
    }

    private fun configure(speech: PlatformSpeech) {
        val availability = try {
            configureSpeech(
                locale = language.locale,
                voices = { speech.voices },
                setVoice = { candidate -> speech.setVoice(candidate) },
                setLanguage = { probe -> speech.setLanguage(probe) },
                currentVoice = { speech.voice },
                observed = evidence::observe
            )
        } catch (_: RuntimeException) {
            diagnostic("Android narration TTS preparation: engine=${engineLabel(speech)} threw")
            release(speech)
            prepareNext()
            return
        }
        when (availability) {
            is SpeechAvailability.Available -> {
                prepared = speech
                phase = Phase.Ready
                speech.setAudioAttributes(SPEECH_ATTRIBUTES)
                speech.setOnUtteranceProgressListener(listenerFor(speech))
                runCatching { speech.setSpeechRate(speed.toFloat()) }
                onNotice(availability.noticeRes)
                onFailureReport(null)
                diagnostic(
                    evidence.facts() +
                        "\nresult: prepared for narration; notice=" + noticeName(availability.noticeRes) +
                        "; speed=" + speed
                )
                val next = pending
                pending = null
                if (next != null) speakNow(next)
            }
            SpeechAvailability.MissingData, SpeechAvailability.Unsupported -> {
                if (availability == SpeechAvailability.MissingData) missingData = true
                diagnostic(
                    evidence.facts() +
                        "\nresult: " + if (availability == SpeechAvailability.MissingData) {
                            "declined, English voice data is missing"
                        } else {
                            "declined, no English voice or language was accepted"
                        }
                )
                release(speech)
                prepareNext()
            }
        }
    }

    private fun speakNow(request: Pending) {
        if (closed) return
        val speech = prepared ?: return
        cancelStartTimeout()
        val previous = active
        if (previous != null) {
            active = null
            runCatching { speech.stop() }
            listener?.onInterrupted(previous.requestId)
        }
        val utteranceId = request.requestId.value
        active = Active(request.requestId, utteranceId, request.text)
        scheduleStartTimeout(utteranceId)
        val accepted = try {
            speech.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (_: RuntimeException) {
            TextToSpeech.ERROR
        }
        if (accepted != TextToSpeech.SUCCESS) {
            cancelStartTimeout()
            evidence.observe(SpeechObservation.SpeakAnswered(accepted))
            active = null
            diagnostic("Android narration utterance: speak error code=$accepted (${errorName(accepted)})")
            // A dead platform service commonly answers speak() with ERROR. Reinitialize the same
            // configured engines once instead of leaving narration permanently unusable after a
            // pause or a background trip. A second refusal is a real failure and is reported.
            if (recoverFromService(request, "speak returned $accepted")) return
            onFailureReport(
                evidence.facts() +
                    "\nresult: the speech engine refused the utterance (code $accepted)."
            )
            listener?.onError(
                request.requestId,
                NarrationError.Device(AndroidSpeechFailure(accepted))
            )
            return
        }
        evidence.observe(SpeechObservation.SpeakAnswered(accepted))
        diagnostic("Android narration utterance: speak accepted")
    }

    /**
     * Releases a disconnected engine and prepares the configured engines again for exactly the
     * same queued sentence. This is one bounded recovery per utterance, decided only by the
     * platform's own answer; it is not a periodic retry and never changes the chosen provider.
     */
    private fun recoverFromService(request: Pending, reason: String): Boolean {
        if (recoveryUsed || closed) return false
        recoveryUsed = true
        cancelStartTimeout()
        diagnostic(
            "Android narration TTS: engine service unavailable ($reason); reinitializing once for the same sentence"
        )
        prepared?.let(::release)
        prepared = null
        active = null
        pending = Pending(request.requestId, request.text)
        phase = Phase.Idle
        startPreparation(clearEvidence = false)
        return true
    }

    private fun onInitTimeout(started: Long) {
        if (closed || started != generation) return
        timeout = null
        val speech = initializing ?: return
        initializing = null
        diagnostic(
            "Android narration TTS initialization: engine=${engineLabel(speech)} timed out after " +
                "${initializationTimeoutMs}ms"
        )
        release(speech)
        prepareNext()
    }

    private fun scheduleTimeout(started: Long) {
        cancelTimeout()
        val runnable = Runnable { dispatch { onInitTimeout(started) } }
        timeout = runnable
        main.postDelayed(runnable, initializationTimeoutMs.coerceAtLeast(1))
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    /**
     * Some disconnected engines accept speak() but never deliver onStart. One bounded watchdog
     * turns that silence into the same evidence-based reconnect as a service error, then a real
     * error; it never becomes a periodic retry.
     */
    private fun scheduleStartTimeout(utteranceId: String) {
        cancelStartTimeout()
        val runnable = Runnable { dispatch { onStartTimedOut(utteranceId) } }
        startTimeout = runnable
        main.postDelayed(runnable, startTimeoutMs.coerceAtLeast(1))
    }

    private fun cancelStartTimeout() {
        startTimeout?.let { main.removeCallbacks(it) }
        startTimeout = null
    }

    private fun onStartTimedOut(utteranceId: String) {
        if (closed) return
        startTimeout = null
        val current = active ?: return
        if (current.utteranceId != utteranceId) return
        active = null
        evidence.recordUtterance("onStart timeout")
        diagnostic(
            "Android narration utterance: no start callback within ${startTimeoutMs}ms"
        )
        if (recoverFromService(Pending(current.requestId, current.text), "no onStart within bound")) {
            return
        }
        val error = AndroidSpeechFailure(
            TextToSpeech.ERROR,
            "The engine accepted the sentence but never started it."
        )
        onFailureReport(
            evidence.facts() +
                "\nresult: the speech engine never started the accepted utterance."
        )
        listener?.onError(current.requestId, NarrationError.Device(error))
    }

    private fun failPreparation() {
        cancelStartTimeout()
        phase = Phase.Unavailable
        initializing = null
        prepared = null
        val failure = deviceSpeechFailure(engineStarted, missingData)
        val report = evidence.report(failure)
        val next = pending
        pending = null
        diagnostic(report)
        if (next != null) {
            onFailureReport(report)
            listener?.onError(
                next.requestId,
                NarrationError.Device(
                    AndroidSpeechFailure(
                        if (failure == DeviceSpeechFailure.MissingData) {
                            TextToSpeech.ERROR_NOT_INSTALLED_YET
                        } else {
                            TextToSpeech.ERROR
                        },
                        failure.verdict
                    )
                )
            )
        }
    }

    private fun listenerFor(source: PlatformSpeech) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            dispatch {
                val current = active ?: return@dispatch
                if (source !== prepared || utteranceId != current.utteranceId) return@dispatch
                cancelStartTimeout()
                evidence.recordUtterance("onStart")
                diagnostic("Android narration utterance: start")
                listener?.onStart(current.requestId)
            }
        }

        override fun onDone(utteranceId: String?) {
            dispatch {
                val current = active ?: return@dispatch
                if (source !== prepared || utteranceId != current.utteranceId) return@dispatch
                active = null
                cancelStartTimeout()
                evidence.recordUtterance("onDone")
                diagnostic("Android narration utterance: done")
                listener?.onDone(current.requestId)
            }
        }

        @Deprecated("Required by the Android listener contract")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) {
            dispatch {
                val current = active ?: return@dispatch
                if (source !== prepared || utteranceId != current.utteranceId) return@dispatch
                active = null
                cancelStartTimeout()
                evidence.recordUtterance("onError", errorCode)
                diagnostic("Android narration utterance: error code=$errorCode (${errorName(errorCode)})")
                if (errorCode == TextToSpeech.ERROR_SERVICE) {
                    // Readium's own Android engine treats a disconnected service as recoverable. Keep
                    // the same request pending and prepare the configured engines again once; every
                    // real synthesis/playback error below still fails the utterance.
                    if (recoverFromService(
                            Pending(current.requestId, current.text),
                            "onError code $errorCode"
                        )
                    ) {
                        return@dispatch
                    }
                }
                if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) missingData = true
                onFailureReport(
                    evidence.report(
                        deviceSpeechFailure(
                            engineStarted = true,
                            missingEnglishData = missingData,
                            utteranceAccepted = true
                        )
                    )
                )
                listener?.onError(
                    current.requestId,
                    NarrationError.Device(AndroidSpeechFailure(errorCode))
                )
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            dispatch {
                val current = active ?: return@dispatch
                if (source !== prepared || utteranceId != current.utteranceId) return@dispatch
                active = null
                cancelStartTimeout()
                evidence.recordUtterance(if (interrupted) "onStop interrupted" else "onStop flushed")
                diagnostic("Android narration utterance: stop interrupted=$interrupted")
                if (interrupted) listener?.onInterrupted(current.requestId)
                else listener?.onFlushed(current.requestId)
            }
        }

        override fun onRangeStart(
            utteranceId: String?,
            start: Int,
            end: Int,
            frame: Int
        ) {
            dispatch {
                val current = active ?: return@dispatch
                if (source !== prepared || utteranceId != current.utteranceId) return@dispatch
                listener?.onRange(current.requestId, start until end)
            }
        }
    }

    private fun release(speech: PlatformSpeech) {
        if (initializing === speech) initializing = null
        if (prepared === speech) prepared = null
        runCatching { speech.setOnUtteranceProgressListener(null) }
        runCatching { speech.shutdown() }
    }

    private fun engineLabel(speech: PlatformSpeech): String =
        diagnosticMetadata(speech.enginePackage ?: "system default engine")

    private fun engineVersion(packageName: String?): String? {
        if (packageName == null) return null
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    private fun noticeName(@StringRes noticeRes: Int?): String = noticeRes?.let {
        runCatching { context.resources.getResourceEntryName(it) }.getOrNull() ?: it.toString()
    } ?: "none"

    private fun diagnostic(report: String) {
        runCatching { onDiagnostic(report) }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val SPEECH_MIN_SPEED = 0.5
        const val SPEECH_MAX_SPEED = 2.0
        const val SPEECH_INITIALIZATION_TIMEOUT_MS = 8_000L
        const val SPEECH_START_TIMEOUT_MS = 10_000L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** The platform TTS narrowing point: the real engine and tests implement the same tiny surface. */
internal interface PlatformSpeech {
    val voices: Set<Voice>?
    val voice: Voice?
    val enginePackage: String?

    fun setVoice(voice: Voice): Int
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float): Int
    fun setAudioAttributes(attributes: AudioAttributes)
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?)
    fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

internal fun interface PlatformSpeechFactory {
    fun create(context: Context, engine: String?, onInit: (Int) -> Unit): PlatformSpeech
}

/** Constructs the one engine instance that is then both inspected and asked to speak. */
internal object AndroidPlatformSpeechFactory : PlatformSpeechFactory {
    override fun create(context: Context, engine: String?, onInit: (Int) -> Unit): PlatformSpeech =
        AndroidPlatformSpeech(context, engine, onInit)
}

private class AndroidPlatformSpeech(
    private val context: Context,
    engine: String?,
    onInit: (Int) -> Unit
) : PlatformSpeech {
    private val textToSpeech = if (engine == null) {
        TextToSpeech(context, onInit)
    } else {
        TextToSpeech(context, onInit, engine)
    }

    /** A requested alternative is authoritative; the platform resolves the default after init. */
    private val requestedEngine: String? = engine

    override val voices: Set<Voice>?
        get() = runCatching { textToSpeech.voices }.getOrNull()

    override val voice: Voice?
        get() = runCatching { textToSpeech.voice }.getOrNull()

    override val enginePackage: String?
        get() = requestedEngine
            ?: runCatching { textToSpeech.defaultEngine }.getOrNull()
            ?: defaultSpeechEngine(context)

    override fun setVoice(voice: Voice): Int = textToSpeech.setVoice(voice)

    override fun setLanguage(locale: Locale): Int = textToSpeech.setLanguage(locale)

    override fun setSpeechRate(rate: Float): Int = textToSpeech.setSpeechRate(rate)

    override fun setAudioAttributes(attributes: AudioAttributes) {
        runCatching { textToSpeech.setAudioAttributes(attributes) }
    }

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
        textToSpeech.setOnUtteranceProgressListener(listener)
    }

    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int =
        textToSpeech.speak(text, queueMode, params, utteranceId)

    override fun stop(): Int = textToSpeech.stop()

    override fun shutdown() {
        textToSpeech.shutdown()
    }
}

/** A numeric platform failure stays numeric so no real error can read like success. */
internal class AndroidSpeechFailure(
    val code: Int,
    detail: String = "The device speech engine failed."
) : Error {
    override val message: String = "$detail (code $code)"
    override val cause: Error? = null
}

private fun errorName(code: Int): String = when (code) {
    TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS"
    TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE"
    TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT"
    TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK"
    TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
    TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST"
    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
    else -> "ERROR"
}
