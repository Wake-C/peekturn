package app.margin.reader.speech

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Playing includes pending lookups and initialization, so the same speaker button can always stop it.
 * [credit] describes the online provider audio currently used for the request and stays until the next
 * request or stop.
 */
data class SpeechState(
    val text: String = "",
    val playing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val credit: AudioCredit? = null,
    /**
     * What the online routes and device speech answered when no speaker could be reached, for the
     * reader to copy and send back: one line per online route plus the device-speech report.
     */
    val diagnostic: String? = null
)

internal const val ACCENT_NOT_GUARANTEED = "Using system English; requested accent is not guaranteed."

/**
 * The online sentence routes in the order they are tried, each one a whole request in its own right:
 * the read-aloud service first, then the simple key-free GET when that service refuses, stalls or has
 * nothing, and installed device speech after both. A word uses the same two routes after its own
 * dictionary recording, so a word no dictionary voices still gets online audio before speech.
 */
internal val defaultSentenceSources: List<SentenceAudioSource> = listOf(BingSpeechClient(), BaiduSpeechClient())

/**
 * What a device-speech failure tells the reader. Each message names only what the app observed: an
 * engine that offered no English voice, one that says its own English data is missing, and one that
 * never started are different device facts, and none of them is proof that nothing is installed. Which
 * message belongs to which fact is decided in SpeechDiagnosis.kt, where the report is built from it.
 */
internal const val MISSING_ENGLISH_DATA = "Your speech engine reports its English voice data is missing. Open system speech settings to download it, then try again."
internal const val NO_ENGLISH_VOICE = "None of the device's speech engines offered an English voice. Check system speech settings."
internal const val NO_SPEECH_ENGINE = "The device's speech engine did not start. Open system speech settings to check it."
private const val NO_ONLINE_AUDIO = "No online audio is available for this text; using system speech."
/**
 * Every online route — the dictionary recording and each speech service — gets the same short bound,
 * so a phone on a hostile network spends at most a few seconds per route instead of ten. The clients'
 * own bounds are shorter still, so they can say where they stopped before this one cuts them off.
 */
private const val LOOKUP_TIMEOUT_MS = 4_000L
private const val PREPARE_TIMEOUT_MS = 8_000L
private const val INITIALIZE_TIMEOUT_MS = 8_000L

/**
 * Main-thread serialized, latest-only pronunciation. A word prefers its free dictionary recording, then
 * the key-free speech services, which also voice every phrase and example; installed device speech
 * always remains the fallback, and nothing about a superseded request may still reach the speaker.
 * Call shutdown when its owner is destroyed.
 */
class PronunciationPlayer(
    context: Context,
    private val wordAudio: WordAudioSource = YoudaoWordAudioClient(),
    private val sentenceAudio: List<SentenceAudioSource> = defaultSentenceSources,
    private val engines: (Context) -> List<String> = ::installedSpeechEngines
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var generation = 0L
    private var engineGeneration = 0L
    private var pending: Request? = null
    private var path = Path.Speech
    private var lookup: Job? = null
    private var speechNotice: String? = null
    private var media: MediaPlayer? = null
    private var mediaPrepared = false
    private var focus: AudioFocusRequest? = null
    private var initTimeout: Runnable? = null
    private var candidates: List<String?> = emptyList()
    private var candidateIndex = 0
    private var currentCandidate: String? = null
    private var substitutionPass = false
    private val substitutable = LinkedHashSet<String?>()
    private var engineStarted = false
    private var missingEnglishData = false
    /** An engine accepted the text and then failed it, which is playback, not a missing voice. */
    private var utteranceAccepted = false

    /**
     * Whether the utterance in flight is the engine's own voice, which the app neither chose nor can
     * name. Such a voice is not a promise, so its failure hands the request to the next engine instead
     * of being reported as the end of the story.
     */
    private var substituting = false
    private val evidence = SpeechEvidence()

    /** What each online route answered for the current request, in the order they were tried. */
    private val onlineOutcomes = mutableListOf<RouteOutcome>()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private data class Request(val text: String, val locale: Locale, val id: Long)

    /** Where the current request is playing: online provider bytes through the decoder, or device speech. */
    private enum class Path { Speech, Audio }

    /** A single word: its dictionary recording, then the speech service, then installed device speech. */
    fun speakWord(text: String, accent: String) = onMain { begin(text, accent, word = true) }

    /** A phrase, sentence or example: the speech service, then installed device speech. */
    fun speakExample(text: String, accent: String) = onMain { begin(text, accent, word = false) }

    fun stop() = onMain { stopInternal() }

    fun shutdown() = onMain {
        if (!closed) {
            closed = true
            stopInternal()
            releaseEngine()
            scope.cancel()
        }
    }

    fun openSystemSettings() = onMain {
        stopInternal()
        releaseEngine()
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@onMain
            } catch (_: ActivityNotFoundException) {
                // OEMs expose different speech settings entry points.
            } catch (_: SecurityException) {
                // Try the public settings activity if an OEM speech activity is private.
            }
        }
        fail("System speech settings are unavailable on this device.")
    }

    private fun begin(text: String, accent: String, word: Boolean) {
        if (closed) return
        stopInternal()
        val locale = speechLocale(accent)
        if (locale == null) {
            fail("Choose US or UK pronunciation.")
            return
        }
        if (text.isBlank()) {
            fail("There is no text to pronounce.")
            return
        }
        val request = Request(text, locale, generation)
        pending = request
        path = Path.Speech
        utteranceAccepted = false
        mutableState.value = SpeechState(text, playing = true)
        // Only the text asked to be heard may leave the device, and only a single plain word may try
        // the dictionary's own recording before the speech service.
        lookupOnline(request, accent, if (word) wordLookupTerm(text) else null)
    }

    /**
     * The online routes for this request, in order: the dictionary's recording of a single word first
     * where the endpoint accepts one, then each speech service in turn, which voices arbitrary text. The
     * first route with audio plays it; a route that has nothing, fails or redirects only moves on, and
     * installed device speech speaks when none of them can.
     */
    private fun lookupOnline(request: Request, accent: String, term: String?) {
        path = Path.Audio
        lookup = scope.launch {
            val clip = (term?.let { word -> onlineClip(LOOKUP_TIMEOUT_MS) { wordAudio.lookup(word, accent) } })
                ?: sentenceClip(request.text, accent)
            if (!isCurrent(request)) return@launch
            if (clip == null) requestSpeech(request, NO_ONLINE_AUDIO) else playClip(clip, request)
        }
    }

    /**
     * Each online sentence route is tried in turn with its own bounded attempt, so one service being
     * unavailable costs the reader the time to find out and nothing else; the clip that plays is the one
     * the service that answered supplied, with its own credit and its own caveat. What a route answered
     * when it had no clip is kept for the failure report, never shown on success.
     */
    private suspend fun sentenceClip(text: String, accent: String): AudioClip? {
        for (source in sentenceAudio) {
            val clip = sentenceAttempt(source, text, accent)
            if (clip != null) return clip
        }
        return null
    }

    /** One bounded sentence route. Cancellation belongs to a newer request and is never reported. */
    private suspend fun sentenceAttempt(
        source: SentenceAudioSource,
        text: String,
        accent: String
    ): AudioClip? {
        val started = System.nanoTime()
        var outcome: RouteOutcome? = null
        var returned = false
        val clip = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                val found = source.lookup(text, accent) { outcome = it }
                returned = true
                found
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            returned = true
            null
        }
        if (clip != null) return clip
        onlineOutcomes += outcome ?: if (!returned) {
            // The route's own bound is shorter; this one only catches a source that never answered.
            RouteOutcome(source.route, RouteStage.Connect, LOOKUP_TIMEOUT_MS, timedOut = true)
        } else {
            RouteOutcome(
                source.route, RouteStage.Answer, elapsedMillis(started), detail = "no audio"
            )
        }
        return null
    }

    /** One route's attempt, bounded by its own timeout and cancelled with the coroutine that made it. */
    private suspend fun onlineClip(timeoutMs: Long, lookup: suspend () -> AudioClip?): AudioClip? =
        try {
            withTimeoutOrNull(timeoutMs) { lookup() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Only the newest request may still reach the speaker; superseded ones are ignored entirely. */
    private fun isCurrent(request: Request) = !closed && request.id == generation && pending === request

    private fun playClip(clip: AudioClip, request: Request) {
        if (!requestFocus(request)) return
        val player = try { MediaPlayer() } catch (_: RuntimeException) { null }
        if (player == null) {
            fallbackToSpeech(request)
            return
        }
        media = player
        mediaPrepared = false
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(ClipDataSource(clip.audio))
            player.setOnPreparedListener { prepared ->
                mediaAction(prepared, request) {
                    mediaPrepared = true
                    val started = try {
                        prepared.start()
                        true
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (started) {
                        mutableState.value = mutableState.value.copy(notice = clip.notice, credit = clip.credit)
                    } else {
                        fallbackToSpeech(request)
                    }
                }
            }
            player.setOnCompletionListener { finished -> mediaAction(finished, request) { finish() } }
            player.setOnErrorListener { failed, _, _ ->
                mediaAction(failed, request) { fallbackToSpeech(request) }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            fallbackToSpeech(request)
            return
        }
        main.postDelayed({
            if (!mediaPrepared && media === player && isCurrent(request)) fallbackToSpeech(request)
        }, PREPARE_TIMEOUT_MS)
    }

    /** Online audio that no route could supply is never an error while device speech can speak instead. */
    private fun fallbackToSpeech(request: Request) {
        releaseMedia()
        requestSpeech(request, NO_ONLINE_AUDIO)
    }

    /**
     * Only the player that is still the current audio may start, finish or fail it: a superseded,
     * released or replaced player must never reach the speaker or claim the clip's credit.
     */
    private fun mediaAction(player: MediaPlayer, request: Request, action: () -> Unit) {
        main.post {
            if (media === player && path == Path.Audio && isCurrent(request) && mutableState.value.playing) action()
        }
    }

    private fun requestSpeech(request: Request, notice: String?) {
        if (!isCurrent(request)) return
        path = Path.Speech
        speechNotice = notice
        if (ready) playLatest() else if (engine == null) initialize()
    }

    /**
     * Tries the engines in order: the user's default first, then every other engine that declares the
     * TTS service. Each one is asked for a real English voice, and only a failure of the engine being
     * tried moves on to the next candidate, so a broken or English-less default cannot hide an engine
     * that can speak. Once every engine has declined, the engines that actually started get one final
     * pass, in the same order, where the engine's own voice is asked to speak the text unchanged: the
     * reader's own engine choice may be the only English-capable voice on the device, but a real English
     * voice found on any engine was still worth more than a substituted one.
     */
    private fun initialize() {
        if (closed || engine != null) return
        if (candidates.isEmpty()) {
            candidates = listOf<String?>(null) + engines(app)
            candidateIndex = 0
        }
        if (candidateIndex >= candidates.size) {
            if (!substitutionPass && substitutable.isNotEmpty()) {
                substitutionPass = true
                candidates = substitutable.toList()
                candidateIndex = 0
            } else {
                reportEngineFailure()
                return
            }
        }
        val candidate = candidates[candidateIndex++]
        currentCandidate = candidate
        // The engine the reader chose is the one whose answers a report keeps, and a package name the
        // reader can send back is worth more than "the default engine".
        evidence.addEngine(candidate ?: defaultSpeechEngine(app) ?: "system default engine")
        val initialization = ++engineGeneration
        val onInit = TextToSpeech.OnInitListener { status ->
            // Always post: construction must have assigned engine before handling init.
            main.post { onEngineInitialized(initialization, status) }
        }
        try {
            engine = if (candidate == null) TextToSpeech(app, onInit) else TextToSpeech(app, onInit, candidate)
        } catch (_: RuntimeException) {
            // An engine that cannot even be constructed has told us nothing about its voices.
            engine = null
            initialize()
            return
        }
        scheduleInitTimeout(initialization)
    }

    /** An engine that never reports its initialization must not hold the request: the next one gets a turn. */
    private fun scheduleInitTimeout(initialization: Long) {
        cancelInitTimeout()
        val timeout = Runnable { onEngineTimeout(initialization) }
        initTimeout = timeout
        main.postDelayed(timeout, INITIALIZE_TIMEOUT_MS)
    }

    private fun onEngineTimeout(initialization: Long) {
        initTimeout = null
        if (closed || initialization != engineGeneration || engine == null) return
        releaseEngine()
        if (path == Path.Speech && pending != null) initialize() else forgetEngines()
    }

    private fun cancelInitTimeout() {
        initTimeout?.let { main.removeCallbacks(it) }
        initTimeout = null
    }

    private fun onEngineInitialized(initialization: Long, status: Int) {
        if (closed || initialization != engineGeneration) return
        cancelInitTimeout()
        if (status != TextToSpeech.SUCCESS) {
            releaseEngine()
            // A superseded sentence's engine must not fail the newer request, which may not need
            // system speech at all.
            if (path == Path.Speech && pending != null) initialize() else forgetEngines()
            return
        }
        // An engine that started is a different device fact from one that never answered, so a failure
        // says which of the two happened even when every engine is tried.
        engineStarted = true
        ready = true
        val speaking = engine ?: return
        speaking.setAudioAttributes(attributes)
        speaking.setOnUtteranceProgressListener(listenerFor(speaking))
        playLatest()
    }

    /**
     * The engine gets the request in a fixed order: the best installed English voice first, then the
     * standard language API for the requested accent, the other English country and plain English, and
     * only once every engine has declined does any engine's own voice get its single unlabelled-voice
     * attempt, which is decided in [initialize] and taken here. Every answer is recorded, so a request
     * that ends without a speaker can say what it was told.
     */
    private fun playLatest() {
        if (path != Path.Speech) return
        val request = pending ?: return
        val tts = engine ?: return
        // Only the final pass speaks with a voice the app did not choose; anything that throws before
        // that is not a substituted attempt, whatever the previous engine was asked for.
        substituting = false
        try {
            if (request.text.length > TextToSpeech.getMaxSpeechInputLength()) {
                fail("This text is too long for system speech. Select a shorter phrase.")
                return
            }
            if (substitutionPass) {
                // The final device attempt, and the only one that does not change the voice: no voice and
                // no language is selected, so the reader's own engine choice may be the only
                // English-capable voice on the device. Only text that is itself English reaches this pass.
                speak(request, tts, DEVICE_DEFAULT_VOICE_NOTICE, substituting = true)
                return
            }
            when (val availability = configureSpeech(
                request.locale, { tts.voices }, { tts.setVoice(it) },
                { tts.setLanguage(it) }, { tts.voice }, evidence::observe
            )) {
                is SpeechAvailability.Available ->
                    speak(request, tts, availability.notice, substituting = false)
                SpeechAvailability.MissingData, SpeechAvailability.Unsupported -> {
                    if (availability == SpeechAvailability.MissingData) missingEnglishData = true
                    // What the engine answered was about the voice that was asked for, not about the voice
                    // it already holds, so this engine is worth keeping for the final pass - but only for
                    // text that is itself English, and only after the other engines have had their chance
                    // at a real English voice.
                    if (substitutedDeviceVoiceNotice(availability, request.text) != null) {
                        substitutable += currentCandidate
                    }
                    releaseEngine()
                    initialize()
                }
            }
        } catch (_: RuntimeException) {
            releaseEngine()
            // An engine that throws instead of answering is the same fact as one that answers with a
            // failure: the substituted voice gives way, and a promised one is reported.
            if (substituting && pending != null) {
                initialize()
            } else {
                fail("Speech playback failed. Check system speech settings and try again.")
            }
        }
    }

    /**
     * Speaks [request] with whatever voice [tts] holds, labelling a voice the app neither chose nor can
     * name. A voice the engine offered and set is a real answer, so the request is settled here either
     * way; an engine's own voice is not a promise, so while it speaks the request stays open for the next
     * engine, which is what the failure paths rely on.
     */
    private fun speak(request: Request, tts: TextToSpeech, notice: String?, substituting: Boolean) {
        this.substituting = substituting
        if (!requestFocus(request)) return
        val notices = listOfNotNull(speechNotice, notice)
        val accepted = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id.toString()) ==
            TextToSpeech.SUCCESS
        if (accepted && substituting) utteranceAccepted = true
        if (!substituting) pending = null
        if (!accepted) {
            releaseEngine()
            if (substituting) initialize() else fail("Speech playback failed. Check system speech settings.")
            return
        }
        speechNotice = null
        mutableState.value = SpeechState(
            request.text, playing = true,
            notice = notices.joinToString(" ").takeIf { it.isNotEmpty() }
        )
    }

    /** No engine is left to try, so the request fails with what every engine answered, and says so. */
    private fun reportEngineFailure() {
        val failure = deviceSpeechFailure(engineStarted, missingEnglishData, utteranceAccepted)
        engineStarted = false
        missingEnglishData = false
        utteranceAccepted = false
        substituting = false
        forgetEngines()
        if (path != Path.Speech || pending == null) return
        fail(failure.message, evidence.report(failure))
    }

    private fun forgetEngines() {
        candidates = emptyList()
        candidateIndex = 0
        substitutable.clear()
        substitutionPass = false
    }

    /**
     * The listener belongs to one engine, and only the engine the player still owns may report progress
     * for the request. A released engine can still deliver a final callback, and on the final pass the
     * request is deliberately left open for the next engine, so its stale `onDone` must never finish the
     * request the failure just handed on.
     */
    private fun listenerFor(source: TextToSpeech) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = callback(utteranceId) {
            if (source === engine) mutableState.value = mutableState.value.copy(playing = true, error = null)
        }
        override fun onDone(utteranceId: String?) = callback(utteranceId) {
            if (source === engine) finish()
        }
        @Deprecated("Required by the Android listener contract")
        override fun onError(utteranceId: String?) = callback(utteranceId) {
            if (source === engine) utteranceFailed(null)
        }
        override fun onError(utteranceId: String?, errorCode: Int) = callback(utteranceId) {
            if (source === engine) utteranceFailed(errorCode)
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) = callback(utteranceId) {
            if (source === engine) finish()
        }
    }

    /**
     * An engine that accepted the request but could not speak it. The engine's own substituted voice was
     * never promised, so its failure hands the request to the next installed engine, and what the engine
     * said about its data still counts towards the classification that ends the queue; a voice the engine
     * offered and set is a real answer, so its failure is reported as one, in the engine's own words when
     * it said the voice is not installed yet.
     */
    private fun utteranceFailed(errorCode: Int?) {
        releaseEngine()
        if (substituting && pending != null) {
            if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) missingEnglishData = true
            initialize()
            return
        }
        if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
            // The engine said the voice is not installed, so that is what the reader is told, in the
            // same words the classification uses, so the message and the report cannot drift apart.
            fail(
                DeviceSpeechFailure.MissingData.message,
                evidence.report(DeviceSpeechFailure.MissingData)
            )
        } else {
            fail("Speech playback failed. Check system speech settings.")
        }
    }

    private fun callback(id: String?, action: () -> Unit) {
        main.post {
            if (!closed && id == generation.toString() && mutableState.value.playing) action()
        }
    }

    private fun finish() {
        pending = null
        substituting = false
        onlineOutcomes.clear()
        releaseMedia()
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false)
    }

    /**
     * Every failure that ends a request without a speaker says that the online routes failed too, so
     * the reader is never left believing their phone simply has no voice. The copyable report carries
     * one line per online attempt, the device clock and version, and the device-speech evidence.
     */
    private fun fail(message: String, diagnostic: String? = null) {
        val shown = noSpeakerMessage(message)
        val report = diagnosticReport(diagnostic)
        generation++
        pending = null
        path = Path.Speech
        lookup?.cancel()
        lookup = null
        speechNotice = null
        cancelInitTimeout()
        engine?.stop()
        releaseMedia()
        abandonFocus()
        forgetEngines()
        engineStarted = false
        missingEnglishData = false
        utteranceAccepted = false
        substituting = false
        onlineOutcomes.clear()
        mutableState.value = mutableState.value.copy(
            playing = false, error = shown, notice = null, credit = null, diagnostic = report
        )
    }

    /** The one sentence that names both halves of the failure, without implying the phone has no voice. */
    private fun noSpeakerMessage(message: String): String {
        if (onlineOutcomes.isEmpty()) return message
        val online = if (onlineOutcomes.map { it.route }.distinct().size > 1) {
            "Both online audio routes failed"
        } else {
            "Online audio failed"
        }
        return "$online, and ${message.replaceFirstChar { it.lowercase() }}"
    }

    /** The online line, the build and clock identity, then the device-speech report when there is one. */
    private fun diagnosticReport(deviceReport: String?): String? {
        if (onlineOutcomes.isEmpty() && deviceReport == null) return null
        return buildString {
            if (onlineOutcomes.isNotEmpty()) {
                append(onlineFailureReport(onlineOutcomes))
                append('\n')
            }
            append(environmentReport())
            if (deviceReport != null) {
                append('\n')
                append(deviceReport)
            }
        }
    }

    private fun stopInternal() {
        generation++
        pending = null
        path = Path.Speech
        lookup?.cancel()
        lookup = null
        speechNotice = null
        cancelInitTimeout()
        engine?.stop()
        // A half-initialized engine is dropped so it can never hold the next request.
        if (!ready) releaseEngine()
        releaseMedia()
        abandonFocus()
        // Capabilities are re-read on the next request, so a retry after system settings sees the change.
        forgetEngines()
        engineStarted = false
        missingEnglishData = false
        substituting = false
        evidence.clear()
        onlineOutcomes.clear()
        mutableState.value = mutableState.value.copy(
            playing = false, error = null, notice = null, credit = null, diagnostic = null
        )
    }

    private fun requestFocus(request: Request): Boolean {
        // The request's own playback keeps the focus it already holds; every new request and every stop
        // abandons it, so a replacement request would only leak the granted one.
        if (focus != null) return true
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && focusLossSilences(
                        playing = mutableState.value.playing, closed = closed,
                        requestId = request.id, generation = generation
                    )
                ) {
                    stopInternal()
                }
            }, main)
            .build()
        focus = focusRequest
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("Audio is currently unavailable. Try pronunciation again.")
            return false
        }
        return true
    }

    private fun releaseEngine() {
        cancelInitTimeout()
        engineGeneration++
        ready = false
        val previous = engine
        engine = null
        previous?.shutdown()
    }

    private fun releaseMedia() {
        mediaPrepared = false
        val previous = media
        media = null
        try {
            previous?.release()
        } catch (_: RuntimeException) {
            // A player that already failed is released twice at most.
        }
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}

internal fun speechLocale(accent: String): Locale? = when (accent) {
    "US" -> Locale.US
    "UK" -> Locale.UK
    else -> null
}

/**
 * Audio focus loss may only silence the newest request that is still audible. System speech clears its
 * own pending request once the engine starts, so identity alone cannot decide this, and a superseded
 * or already finished request must never stop a newer one.
 */
internal fun focusLossSilences(playing: Boolean, closed: Boolean, requestId: Long, generation: Long): Boolean =
    !closed && playing && requestId == generation

/**
 * Other engines that declare the platform's TTS service action, so a broken or English-less default
 * engine is not the end of the story. Discovery mirrors how the platform itself finds engines: the
 * declared service action is the only requirement, because real engines such as eSpeak-NG export
 * their TTS service without the binding permission and would otherwise be invisible. Only enabled
 * services are listed, the engine the user chose stays with the default candidate, and nothing
 * OEM-specific is assumed. The default is read from settings rather than
 * TextToSpeech.getDefaultEngine(), which needs an engine context a first-ever lookup may not have.
 */
internal fun installedSpeechEngines(context: Context): List<String> {
    val default = defaultSpeechEngine(context)
    return try {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).addCategory(Intent.CATEGORY_DEFAULT)
        context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .filter { it != default }
            .distinct()
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }
}

/** The engine the reader chose in system settings, or null when the system has no preference yet. */
internal fun defaultSpeechEngine(context: Context): String? = try {
    Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
} catch (_: Exception) {
    null
}

/**
 * Engines may report ISO 639-2/T languages ("eng") and ISO 3166-1 alpha-3 countries ("USA", "GBR"),
 * which name the same voice as the ISO 639-1 / alpha-2 identifiers this app requests. Normalizing
 * before every comparison keeps English and the requested accent recognizable instead of unsupported.
 */
internal fun normalizeLanguage(language: String): String = when (language.lowercase()) {
    "eng" -> "en"
    else -> language.lowercase()
}

internal fun normalizeCountry(country: String): String = when (country.uppercase()) {
    "USA" -> "US"
    "GBR", "UK" -> "GB"
    else -> country.uppercase()
}

/**
 * Installed English voices, best first: offline before network-only, then the requested accent, then
 * quality, latency and a stable name. A country difference is a preference, never a blocker.
 */
internal fun englishVoices(voices: Set<Voice>, locale: Locale): List<Voice> =
    voices.filter {
        normalizeLanguage(it.locale.language) == normalizeLanguage(locale.language) &&
            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
    }.sortedWith(
        compareBy<Voice>(
            { it.isNetworkConnectionRequired },
            { normalizeCountry(it.locale.country) != normalizeCountry(locale.country) },
            { -it.quality }, { it.latency }, { it.name }
        )
    )

internal sealed interface SpeechAvailability {
    data class Available(val notice: String? = null) : SpeechAvailability
    data object MissingData : SpeechAvailability
    data object Unsupported : SpeechAvailability
}

/**
 * Prepares installed English speech for [locale]. Enumeration is a preference, not proof: when the
 * engine lists nothing or rejects a voice, the language API is asked for the requested accent, the
 * other English country and generic English before giving up. A non-English voice is never accepted.
 * Every answer is handed to [observed], because what an engine said is the only honest thing a later
 * failure can report about it.
 */
internal inline fun configureSpeech(
    locale: Locale,
    voices: () -> Set<Voice>?,
    setVoice: (Voice) -> Int,
    setLanguage: (Locale) -> Int,
    currentVoice: () -> Voice?,
    observed: (SpeechObservation) -> Unit = {}
): SpeechAvailability {
    val language = normalizeLanguage(locale.language)
    val country = normalizeCountry(locale.country)
    val enumerated = try { voices() } catch (_: RuntimeException) { null }
    observed(SpeechObservation.VoicesListed(enumerated?.size))
    for (candidate in englishVoices(enumerated.orEmpty(), locale)) {
        val selected = try { setVoice(candidate) == TextToSpeech.SUCCESS } catch (_: RuntimeException) { false }
        if (!selected) continue
        val actual = try { currentVoice() } catch (_: RuntimeException) { null }
        if (actual != null && normalizeLanguage(actual.locale.language) != language) continue
        if (actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            return SpeechAvailability.MissingData
        }
        // A reported voice is authoritative, including an empty country: only an unreported one may
        // fall back to what the accepted voice promised.
        val spokenCountry = actual?.locale?.country?.let(::normalizeCountry)
            ?: normalizeCountry(candidate.locale.country)
        return SpeechAvailability.Available(if (spokenCountry == country) null else ACCENT_NOT_GUARANTEED)
    }
    var missingData = false
    for (probe in listOf(locale, otherEnglishCountry(locale), Locale.ENGLISH)) {
        val support = try { setLanguage(probe) } catch (_: RuntimeException) { TextToSpeech.LANG_NOT_SUPPORTED }
        observed(SpeechObservation.LanguageProbed(probe, support))
        if (support == TextToSpeech.LANG_MISSING_DATA) {
            missingData = true
            continue
        }
        if (support !in TextToSpeech.LANG_AVAILABLE..TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) continue
        val actual = try { currentVoice() } catch (_: RuntimeException) { null }
        if (actual != null && normalizeLanguage(actual.locale.language) != language) continue
        if (actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            return SpeechAvailability.MissingData
        }
        val spokenCountry = actual?.locale?.country?.let(::normalizeCountry)
            ?: probe.country.takeIf { support >= TextToSpeech.LANG_COUNTRY_AVAILABLE }?.let(::normalizeCountry)
        return SpeechAvailability.Available(if (spokenCountry == country) null else ACCENT_NOT_GUARANTEED)
    }
    return if (missingData) SpeechAvailability.MissingData else SpeechAvailability.Unsupported
}

internal fun otherEnglishCountry(locale: Locale): Locale =
    if (normalizeCountry(locale.country) == "GB") Locale.US else Locale.UK

/** Bounded in-memory audio for MediaPlayer: no temporary file can leak, and nothing is written to disk. */
private class ClipDataSource(private val audio: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= audio.size) return -1
        val count = minOf(size.toLong(), audio.size - position).toInt()
        if (count <= 0) return -1
        System.arraycopy(audio, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = audio.size.toLong()

    override fun close() = Unit
}
