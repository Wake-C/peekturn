package app.peekturn.reader.speech

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
import app.peekturn.reader.R
import app.peekturn.reader.diagnostics.diagnosticMetadata
import app.peekturn.reader.model.SentenceSpeechProvider
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

/**
 * The two key-free sentence routes in their legacy order: read-aloud first, then Baidu. A chosen
 * provider selects only its own source; the word route still tries both before device speech.
 */
internal val defaultSentenceSources: List<SentenceAudioSource> = listOf(BingSpeechClient(), BaiduSpeechClient())

/**
 * What a device-speech failure tells the reader lives in SpeechDiagnosis.kt, where the fact the app
 * observed and the report's verdict are classified together. Only the human sentence is localized;
 * the report below it keeps its technical English vocabulary.
 */
private val NO_ONLINE_AUDIO = R.string.speech_notice_no_online_audio
private val TOO_LONG_NOTICE = R.string.speech_notice_too_long_online
/**
 * Every online route — the dictionary recording and each speech service — gets the same short bound,
 * so a phone on a hostile network spends at most a few seconds per route instead of ten. The speech
 * services' own bounds are shorter still, so they can say where they stopped before this one cuts
 * them off; the dictionary has no shorter bound of its own, so this one is what bounds it.
 */
private const val LOOKUP_TIMEOUT_MS = 4_000L
private const val PREPARE_TIMEOUT_MS = 8_000L
private const val INITIALIZE_TIMEOUT_MS = 8_000L
/** The stable route names the two sentence clients report, and the only names a chosen provider matches. */
private const val MICROSOFT_ROUTE = "bing"
private const val BAIDU_ROUTE = "baidu"
/** The failure log keeps one bounded line per attempted route, not an unbounded route history. */
private const val FAILURE_ROUTE_LIMIT = 5
private const val FAILURE_ROUTE_NAME_LIMIT = 32
private const val FAILURE_OUTCOME_LIMIT = 96

/**
 * Main-thread serialized, latest-only pronunciation. A selected word keeps its free dictionary
 * recording first, then the key-free speech services and finally device speech. A phrase, example or
 * preview follows exactly the provider chosen in Settings, with no cross-route fallback: Microsoft and
 * Baidu either answer or fail, and Android means the platform's preferred engine with the voice that
 * engine already holds. The app neither picks nor substitutes an engine or voice: it binds whatever
 * the system currently resolves, reads the bound identity only as diagnostic evidence, and reports
 * the engine's own synthesis answers. Nothing about a superseded request may still reach the speaker.
 * Call shutdown when its owner is destroyed.
 */
class PronunciationPlayer(
    context: Context,
    private val wordAudio: WordAudioSource = YoudaoWordAudioClient(),
    private val sentenceAudio: List<SentenceAudioSource> = defaultSentenceSources,
    /**
     * Device-speech evidence for the reader's own diagnostics log, not for the screen: preparation
     * outcomes, the engine's answers and terminal or playback failures. It never receives the text
     * being spoken, an endpoint or a credential, and a missing owner simply has no log to write.
     */
    private val onDiagnostic: (String) -> Unit = {}
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
    /**
     * The system speech configuration the held engine was bound for: the platform's preferred engine
     * and the shared revision. A changed default or a refresh changes this key, and the engine is
     * released before the next request can use it.
     */
    private var boundKey: SpeechConfigurationKey? = null
    /**
     * The engine the player currently holds and may reuse after a stop. Evidence is cleared per
     * request, so a ready engine has to be identified to the new request's evidence explicitly.
     */
    private var currentEngineName: String = "system default engine"
    private var currentEngineVersion: String? = null
    private var engineStarted = false
    private var missingEnglishData = false
    /** An engine accepted the text and then failed it, which is playback, not a missing voice. */
    private var utteranceAccepted = false
    private val evidence = SpeechEvidence()

    /** What each online route answered for the current request, in the order they were tried. */
    private val onlineOutcomes = mutableListOf<RouteOutcome>()

    /**
     * Whether this request ever got a clip. A failure after that is a playback failure, not proof
     * that the online routes had nothing, so the message must not lead with a missing route.
     */
    private var clipObtained = false

    /** Why no online route was asked at all, when the text is past what the services will voice. */
    private var onlineUnavailable: Int? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private data class Request(
        val text: String,
        val id: Long,
        /** Set for a phrase-like request: exactly this source, never another route. */
        val provider: SentenceSpeechProvider? = null
    )

    /** Where the current request is playing: online provider bytes through the decoder, or device speech. */
    private enum class Path { Speech, Audio }

    /** A single word: its dictionary recording, then the speech services, then installed device speech. */
    fun speakWord(text: String, accent: String) = onMain { begin(text, accent, word = true, provider = null) }

    /**
     * A phrase, sentence or example: exactly the chosen provider. Microsoft and Baidu never hand the
     * text to the other service or to system speech, and Android never contacts an online service.
     */
    fun speakExample(text: String, accent: String, provider: SentenceSpeechProvider) =
        onMain { begin(text, accent, word = false, provider = provider) }

    /**
     * A selection pronounced as a selection: one plain word keeps the dictionary-first route, while
     * anything longer is phrase-like and follows the chosen provider.
     */
    fun speakSelection(text: String, accent: String, provider: SentenceSpeechProvider) = onMain {
        begin(text, accent, word = wordLookupTerm(text) != null, provider = provider)
    }

    fun stop() = onMain { stopInternal() }

    /**
     * The reader has dealt with a failure, so its message and report are no longer the answer to what
     * they just saw. Only a deliberate dismissal clears them; a stop keeps them.
     */
    fun dismissFailure() = onMain {
        mutableState.value = mutableState.value.copy(error = null, diagnostic = null)
    }

    fun shutdown() = onMain {
        if (!closed) {
            closed = true
            stopInternal()
            releaseEngine()
            scope.cancel()
        }
    }

    /**
     * The device's preferred engine, its version or the reader's system speech settings may have
     * changed outside the app. Stop the audible request, drop the cached engine so the next request
     * binds against current reality, and advance the shared configuration revision so every holder
     * of a key notices that the configuration is stale.
     */
    fun refreshSystemSpeech() = onMain {
        if (closed) return@onMain
        stopInternal()
        releaseEngine()
        refreshSpeechConfiguration()
    }

    fun openSystemSettings() = onMain {
        stopInternal()
        releaseEngine()
        // The reader is about to change the system's engine or voice outside the app, so holders of
        // the shared key (narration included) must not keep using a configuration read before that.
        refreshSpeechConfiguration()
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
        fail(app.getString(R.string.speech_failure_settings_unavailable))
    }

    private fun begin(text: String, accent: String, word: Boolean, provider: SentenceSpeechProvider?) {
        if (closed) return
        stopInternal()
        val locale = speechLocale(accent)
        if (locale == null) {
            fail(app.getString(R.string.speech_failure_choose_accent))
            return
        }
        if (text.isBlank()) {
            fail(app.getString(R.string.speech_failure_no_text))
            return
        }
        // A word keeps its dictionary-first chain; only non-word speech is bound to the chosen source.
        val strictProvider = provider.takeUnless { word }
        val request = Request(text, generation, strictProvider)
        pending = request
        path = Path.Speech
        utteranceAccepted = false
        mutableState.value = SpeechState(text, playing = true)
        if (strictProvider == SentenceSpeechProvider.ANDROID) {
            // The explicitly chosen device route never makes an online request.
            requestSpeech(request, null)
            return
        }
        // Only the text asked to be heard may leave the device, and only a single plain word may try
        // the dictionary's own recording before the speech service.
        lookupOnline(request, accent, if (word) wordLookupTerm(text) else null)
    }

    /**
     * The online routes for this request. A selected word tries the dictionary's recording first, then
     * each speech service in turn. A phrase-like request asks only the chosen provider. A text no
     * service will voice is declined before any request is made, because a route that was never
     * attempted must not be reported as one that failed. With no provider chosen, installed device
     * speech speaks when every online route has nothing; a chosen provider fails instead of silently
     * handing the text to another voice.
     */
    private fun lookupOnline(request: Request, accent: String, term: String?) {
        path = Path.Audio
        // The clients' own contract decides what can be asked, so a text they would decline is
        // declined here, before any route is recorded as having failed.
        if (speechLookupText(request.text) == null) {
            onlineUnavailable = R.string.speech_failure_too_long_online
            if (request.provider != null) {
                fail(app.getString(onlineUnavailable!!), leadWithRoutes = false)
            } else {
                requestSpeech(request, app.getString(TOO_LONG_NOTICE, app.getString(onlineUnavailable!!)))
            }
            return
        }
        lookup = scope.launch {
            val clip = (term?.let { word ->
                onlineClip(DICTIONARY_ROUTE, LOOKUP_TIMEOUT_MS) { wordAudio.lookup(word, accent) }
            }) ?: sentenceClip(request.text, accent, request.provider)
            if (!isCurrent(request)) return@launch
            if (clip == null) {
                if (request.provider != null) {
                    fail(providerFailureMessage(request.provider), leadWithRoutes = false)
                } else {
                    requestSpeech(request, app.getString(NO_ONLINE_AUDIO))
                }
            } else {
                clipObtained = true
                playClip(clip, request)
            }
        }
    }

    /**
     * Each online sentence route is tried in turn with its own bounded attempt, so one service being
     * unavailable costs the reader the time to find out and nothing else; the clip that plays is the one
     * the service that answered supplied, with its own credit and its own caveat. A chosen provider
     * narrows this to that one service. What a route answered when it had no clip is kept for the
     * failure report, never shown on success.
     */
    private suspend fun sentenceClip(
        text: String,
        accent: String,
        provider: SentenceSpeechProvider?
    ): AudioClip? {
        if (provider != null) {
            val source = sentenceSource(provider) ?: return null
            return sentenceAttempt(source, text, accent)
        }
        for (source in sentenceAudio) {
            val clip = sentenceAttempt(source, text, accent)
            if (clip != null) return clip
        }
        return null
    }

    /** The one source a chosen provider means, matched by the stable route name each client reports. */
    private fun sentenceSource(provider: SentenceSpeechProvider): SentenceAudioSource? = when (provider) {
        SentenceSpeechProvider.MICROSOFT -> sentenceAudio.firstOrNull { it.route == MICROSOFT_ROUTE }
        SentenceSpeechProvider.BAIDU -> sentenceAudio.firstOrNull { it.route == BAIDU_ROUTE }
        SentenceSpeechProvider.ANDROID -> null
    }

    private fun providerFailureMessage(provider: SentenceSpeechProvider): String = app.getString(
        when (provider) {
            SentenceSpeechProvider.MICROSOFT -> R.string.speech_failure_microsoft_unavailable
            SentenceSpeechProvider.BAIDU -> R.string.speech_failure_baidu_unavailable
            SentenceSpeechProvider.ANDROID -> R.string.speech_failure_audio_unavailable
        }
    )

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
                source.route, RouteStage.Answer, elapsedMillis(started), detail = NO_AUDIO
            )
        }
        return null
    }

    /**
     * One route's attempt, bounded by the player's per-route budget and cancelled with the coroutine
     * that made it. A route that ran and returned nothing is recorded, because it ran; one that
     * overran the budget is recorded as a timeout at the only stage this side observed, the wait.
     */
    private suspend fun onlineClip(
        route: String,
        timeoutMs: Long,
        lookup: suspend () -> AudioClip?
    ): AudioClip? {
        val started = System.nanoTime()
        var returned = false
        val clip = try {
            withTimeoutOrNull(timeoutMs) {
                val found = lookup()
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
        onlineOutcomes += if (!returned) {
            RouteOutcome(route, RouteStage.Connect, timeoutMs, timedOut = true)
        } else {
            RouteOutcome(route, RouteStage.Answer, elapsedMillis(started), detail = NO_AUDIO)
        }
        return null
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
                        mutableState.value = mutableState.value.copy(notice = clip.noticeRes?.let(app::getString), credit = clip.credit)
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

    /**
     * Online audio that no route could supply is never an error while device speech can speak instead.
     * A chosen provider has no such fallback: a clip that was found but cannot play is reported rather
     * than voiced by a different source.
     */
    private fun fallbackToSpeech(request: Request) {
        releaseMedia()
        if (request.provider != null) {
            fail(app.getString(R.string.speech_failure_online_playback), leadWithRoutes = false)
        } else {
            requestSpeech(request, app.getString(NO_ONLINE_AUDIO))
        }
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
        // The system's preferred engine or the shared revision may have changed since the engine was
        // bound. The cached engine is then not evidence about the current configuration, so it is
        // released before anything is asked of it.
        val key = systemSpeechConfigurationKey(app)
        if (engine != null && key != boundKey) releaseEngine()
        if (ready) {
            // stopInternal keeps a ready engine but clears the evidence, so the retained engine has to
            // be identified again before this request's answers can be attributed to it.
            evidence.addEngine(currentEngineName, currentEngineVersion)
            playLatest()
        } else if (engine == null) {
            initialize()
        }
    }

    /**
     * Binds the platform's preferred engine, or the platform default when the system has no explicit
     * preference. No installed alternative is ever a candidate: the engine the system resolves is the
     * only one tried, and its own voice and language are left untouched.
     */
    private fun initialize() {
        if (closed || engine != null) return
        val key = systemSpeechConfigurationKey(app)
        boundKey = key
        val enginePackage = key.enginePackage?.takeIf { it.isNotBlank() }
        // A package name and version the reader can send back are worth more than "the default engine".
        currentEngineName = enginePackage ?: "system default engine"
        currentEngineVersion = enginePackage?.let { speechEngineVersion(app, it) }
        evidence.addEngine(currentEngineName, currentEngineVersion)
        val initialization = ++engineGeneration
        val onInit = TextToSpeech.OnInitListener { status ->
            // Always post: construction must have assigned engine before handling init.
            main.post { onEngineInitialized(initialization, status) }
        }
        try {
            engine = if (enginePackage == null) TextToSpeech(app, onInit) else TextToSpeech(app, onInit, enginePackage)
        } catch (_: RuntimeException) {
            // An engine that cannot even be constructed has told us nothing about its voices.
            engine = null
            reportEngineFailure()
            return
        }
        scheduleInitTimeout(initialization)
    }

    /** An engine that never reports its initialization cannot speak, and no other engine is tried. */
    private fun scheduleInitTimeout(initialization: Long) {
        cancelInitTimeout()
        val timeout = Runnable { onEngineTimeout(initialization) }
        initTimeout = timeout
        main.postDelayed(timeout, INITIALIZE_TIMEOUT_MS)
    }

    private fun onEngineTimeout(initialization: Long) {
        initTimeout = null
        if (closed || initialization != engineGeneration || engine == null) return
        evidence.recordUtterance("init timeout")
        val changed = systemSpeechConfigurationKey(app) != boundKey
        releaseEngine()
        if (path == Path.Speech && pending != null) {
            if (changed) {
                engineStarted = false
                missingEnglishData = false
                utteranceAccepted = false
                pending?.let { requestSpeech(it, speechNotice) }
            } else {
                reportEngineFailure()
            }
        }
    }

    private fun cancelInitTimeout() {
        initTimeout?.let { main.removeCallbacks(it) }
        initTimeout = null
    }

    private fun onEngineInitialized(initialization: Long, status: Int) {
        if (closed || initialization != engineGeneration) return
        cancelInitTimeout()
        evidence.observe(SpeechObservation.InitAnswered(status))
        if (systemSpeechConfigurationKey(app) != boundKey) {
            // The system default or the shared revision changed while this engine initialized. This
            // engine and its callbacks describe the old configuration, so both are dropped and the
            // newest request is bound again against current reality.
            evidence.recordUtterance("system speech configuration changed during initialization")
            engineStarted = false
            missingEnglishData = false
            utteranceAccepted = false
            releaseEngine()
            if (path == Path.Speech) pending?.let { requestSpeech(it, speechNotice) }
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            releaseEngine()
            // A superseded sentence's engine must not fail the newer request, which may not need
            // system speech at all.
            if (path == Path.Speech && pending != null) reportEngineFailure()
            return
        }
        // An engine that started is a different device fact from one that never answered, so a failure
        // says which of the two happened.
        engineStarted = true
        val speaking = engine ?: return
        // The bound identity is diagnostic only: reflection may be unavailable, and the platform may
        // legitimately resolve a different package. Neither is a reason to refuse system speech.
        boundSpeechEngine(speaking)?.let { bound ->
            currentEngineName = bound
            currentEngineVersion = speechEngineVersion(app, bound)
            evidence.addEngine(currentEngineName, currentEngineVersion)
        }
        ready = true
        speaking.setAudioAttributes(attributes)
        speaking.setOnUtteranceProgressListener(listenerFor(speaking))
        playLatest()
    }

    /**
     * Asks the bound engine, with the voice it already holds, to speak the newest request. The engine
     * owns its voice, so the current voice is read as evidence only; no voice or language is selected,
     * and metadata such as a not-installed flag never refuses the request. The synthesis callbacks
     * decide whether the text was spoken.
     */
    private fun playLatest() {
        if (path != Path.Speech) return
        val request = pending ?: return
        val tts = engine ?: return
        if (systemSpeechConfigurationKey(app) != boundKey) {
            // The system may change underneath a prepared engine; rebind rather than speak through a
            // configuration the reader no longer has.
            engineStarted = false
            missingEnglishData = false
            utteranceAccepted = false
            releaseEngine()
            if (isCurrent(request)) requestSpeech(request, speechNotice)
            return
        }
        try {
            if (request.text.length > TextToSpeech.getMaxSpeechInputLength()) {
                fail(app.getString(R.string.speech_failure_too_long_for_system))
                return
            }
            evidence.observe(
                SpeechObservation.DefaultVoiceReported(
                    runCatching { tts.voice }.getOrNull()?.let(::speechVoiceFact)
                )
            )
            // Success is logged too: the engine's own voice metadata is a device fact a later bug
            // report needs, even when the app never chose that voice.
            emitDiagnostic("prepared")
            speak(request, tts, app.getString(R.string.speech_notice_device_default_voice))
        } catch (_: RuntimeException) {
            // The throwable's own message is never logged; it may echo engine internals or text.
            evidence.recordUtterance("preparation threw")
            releaseEngine()
            reportEngineFailure()
        }
    }

    /**
     * Speaks [request] with the voice the bound engine currently holds. The app never selected that
     * voice, so there is no alternative voice or engine to hand a failure to: the engine's answer is
     * the request's answer either way.
     */
    private fun speak(request: Request, tts: TextToSpeech, notice: String?) {
        if (!requestFocus(request)) return
        val notices = listOfNotNull(speechNotice, notice)
        val returned = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id.toString())
        evidence.observe(SpeechObservation.SpeakAnswered(returned))
        val accepted = returned == TextToSpeech.SUCCESS
        // An engine that accepted the text has answered about its ability to speak it; a later failure
        // is playback either way.
        if (accepted) utteranceAccepted = true
        pending = null
        if (!accepted) {
            releaseEngine()
            fail(app.getString(R.string.speech_failure_playback_settings))
            return
        }
        speechNotice = null
        mutableState.value = SpeechState(
            request.text, playing = true,
            notice = notices.joinToString(" ").takeIf { it.isNotEmpty() }
        )
    }

    /** The bound engine's failure ends the request with what the engine itself answered. */
    private fun reportEngineFailure() {
        val failure = deviceSpeechFailure(engineStarted, missingEnglishData, utteranceAccepted)
        engineStarted = false
        missingEnglishData = false
        utteranceAccepted = false
        if (path != Path.Speech || pending == null) return
        fail(app.getString(failure.message), evidence.report(failure))
    }

    /**
     * The listener belongs to one engine, and only the engine the player still owns may report progress
     * for the request. A released engine can still deliver a final callback, so its stale `onDone` must
     * never finish a newer request.
     */
    private fun listenerFor(source: TextToSpeech) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = callback(utteranceId) {
            if (source === engine) {
                evidence.recordUtterance("onStart")
                mutableState.value = mutableState.value.copy(playing = true, error = null)
            }
        }
        override fun onDone(utteranceId: String?) = callback(utteranceId) {
            if (source === engine) {
                evidence.recordUtterance("onDone")
                emitDiagnostic("completed")
                finish()
            }
        }
        @Deprecated("Required by the Android listener contract")
        override fun onError(utteranceId: String?) = callback(utteranceId) {
            if (source === engine) {
                evidence.recordUtterance("onError")
                utteranceFailed(null)
            }
        }
        override fun onError(utteranceId: String?, errorCode: Int) = callback(utteranceId) {
            if (source === engine) {
                evidence.recordUtterance("onError", errorCode)
                utteranceFailed(errorCode)
            }
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) = callback(utteranceId) {
            if (source === engine) {
                evidence.recordUtterance(if (interrupted) "onStop interrupted" else "onStop finished")
                emitDiagnostic("stopped")
                finish()
            }
        }
    }

    /**
     * An engine that accepted the request but could not speak it. There is no other engine or voice to
     * hand the request to, so the failure is reported with the verdict and evidence the engine produced;
     * an engine that says its voice data is not installed yet is reported as missing data.
     */
    private fun utteranceFailed(errorCode: Int?) {
        releaseEngine()
        if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) missingEnglishData = true
        val failure = deviceSpeechFailure(engineStarted, missingEnglishData, utteranceAccepted)
        fail(app.getString(failure.message), evidence.report(failure))
    }

    private fun callback(id: String?, action: () -> Unit) {
        main.post {
            if (!closed && id == generation.toString() && mutableState.value.playing) action()
        }
    }

    /**
     * Hands the bounded, text-free device facts to the owner's diagnostics log. Preparation, success
     * and failure are all emitted, so a working engine whose voice metadata conflicts with its own
     * accepted language is visible as system capability, not only as an app failure. The spoken text,
     * endpoint URLs and credentials are never part of the evidence.
     */
    private fun emitDiagnostic(stage: String) {
        if (!evidence.hasFacts()) return
        emitToLog("system speech $stage; " + evidence.facts())
    }

    /**
     * A failure the app itself observed, independent of device evidence: device facts need an engine
     * to have answered, while a strict Microsoft or Baidu failure has none. The log therefore always
     * gets an app-owned category and the routes that ran, with each engine-supplied field sanitized
     * by the shared diagnostics rule. No spoken text, URL, provider body or credential is included.
     */
    private fun emitFailureDiagnostic() {
        val category = when {
            clipObtained -> "online-playback"
            onlineUnavailable != null -> "text-too-long"
            onlineOutcomes.isNotEmpty() -> "online"
            pending?.provider != null && pending?.provider != SentenceSpeechProvider.ANDROID ->
                "online-provider"
            evidence.hasFacts() -> "device-speech"
            else -> "request"
        }
        emitToLog(
            buildString {
                append("pronunciation failed [").append(category).append(']')
                if (onlineOutcomes.isNotEmpty()) {
                    append("; online: ")
                    append(
                        onlineOutcomes.take(FAILURE_ROUTE_LIMIT).joinToString("; ") { outcome ->
                            diagnosticMetadata(outcome.route, FAILURE_ROUTE_NAME_LIMIT) +
                                "=" + diagnosticMetadata(outcome.outcome(), FAILURE_OUTCOME_LIMIT) +
                                " " + outcome.elapsedMs + "ms"
                        }
                    )
                }
            }
        )
    }

    private fun emitToLog(line: String) {
        try {
            onDiagnostic(line)
        } catch (_: RuntimeException) {
            // Diagnostics are an accessory: a broken log must never change what the reader hears.
        }
    }

    private fun finish() {
        pending = null
        clipObtained = false
        onlineUnavailable = null
        onlineOutcomes.clear()
        releaseMedia()
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false)
    }

    /**
     * Every failure that ends a request without a speaker says what the online routes actually did,
     * so the reader is never left believing their phone simply has no voice, nor told that a route
     * failed when it supplied the clip. The copyable report carries one line per online route that
     * failed, the device clock and version, and the device-speech evidence.
     */
    private fun fail(message: String, diagnostic: String? = null, leadWithRoutes: Boolean = true) {
        emitFailureDiagnostic()
        emitDiagnostic("failed")
        val shown = if (leadWithRoutes) noSpeakerMessage(message) else message
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
        engineStarted = false
        missingEnglishData = false
        utteranceAccepted = false
        clipObtained = false
        onlineUnavailable = null
        onlineOutcomes.clear()
        mutableState.value = mutableState.value.copy(
            playing = false, error = shown, notice = null, credit = null, diagnostic = report
        )
    }

    /** What the online routes did, then the device's own answer, each from its own classification. */
    private fun noSpeakerMessage(message: String): String {
        val online = when {
            clipObtained -> app.getString(R.string.speech_failure_online_playback)
            onlineUnavailable != null -> app.getString(onlineUnavailable!!)
            onlineOutcomes.isNotEmpty() -> onlineFailureLead(onlineOutcomes)
            else -> null
        }
        return if (online == null) message else app.getString(
            R.string.speech_failure_online_and_device, online, message.replaceFirstChar { it.lowercase() }
        )
    }

    /**
     * How many routes actually ran, so a word's dictionary recording plus both services are not
     * described as "both". A route that was never attempted is absent from [onlineOutcomes].
     */
    private fun onlineFailureLead(outcomes: List<RouteOutcome>): String {
        val attempts = outcomes.map { it.route }.distinct().size
        return when (attempts) {
            0, 1 -> app.getString(R.string.speech_failure_online_failed)
            2 -> app.getString(R.string.speech_failure_both_online_routes)
            else -> app.getString(R.string.speech_failure_all_online_routes, attempts)
        }
    }

    /**
     * The report is deliberately not localized: route names, stage words, HTTP statuses, the device
     * clock and the app version are the support vocabulary the reader copies back, and translating
     * them would make the same attempt hard to match with the code path that produced it. Only the
     * human sentence above it ([SpeechState.error]) follows the reader's language.
     */
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
        engineStarted = false
        missingEnglishData = false
        utteranceAccepted = false
        clipObtained = false
        onlineUnavailable = null
        evidence.clear()
        onlineOutcomes.clear()
        // A failure's message and report are what the reader just saw; acting on them (speech
        // settings, a pause) must not erase them. A new request and a deliberate dismissal do.
        mutableState.value = mutableState.value.copy(
            playing = false, notice = null, credit = null
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
            fail(app.getString(R.string.speech_failure_audio_unavailable), leadWithRoutes = request.provider == null)
            return false
        }
        return true
    }

    private fun releaseEngine() {
        cancelInitTimeout()
        engineGeneration++
        ready = false
        boundKey = null
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

/**
 * Audio focus loss may only silence the newest request that is still audible. System speech clears its
 * own pending request once the engine starts, so identity alone cannot decide this, and a superseded
 * or already finished request must never stop a newer one.
 */
internal fun focusLossSilences(playing: Boolean, closed: Boolean, requestId: Long, generation: Long): Boolean =
    !closed && playing && requestId == generation

/** The engine the system currently prefers, or null when the platform has no preference yet. */
internal fun defaultSpeechEngine(context: Context): String? = try {
    Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
} catch (_: Exception) {
    null
}

/**
 * The installed engine's version name, when the package manager will say. A report that names the
 * engine's version can be matched to the build that produced the answer; a package manager that
 * refuses is not a speech failure and simply leaves the version out.
 */
@Suppress("DEPRECATION")
internal fun speechEngineVersion(context: Context, packageName: String): String? = try {
    context.packageManager.getPackageInfo(packageName, 0).versionName
} catch (_: Exception) {
    null
}

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
