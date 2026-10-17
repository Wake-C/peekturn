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
    val credit: AudioCredit? = null
)

internal const val ACCENT_NOT_GUARANTEED = "Using system English; requested accent is not guaranteed."
private const val MISSING_ENGLISH_DATA = "English voice data is missing. Open system speech settings to download it, then try again."
private const val NO_ENGLISH_VOICE = "English speech is unavailable from the installed engines. Check system speech settings."
private const val NO_SPEECH_ENGINE = "No working speech engine. Open system speech settings to install one."
private const val NO_ONLINE_AUDIO = "No online audio is available for this text; using system speech."
private const val LOOKUP_TIMEOUT_MS = 10_000L
private const val PREPARE_TIMEOUT_MS = 8_000L
private const val INITIALIZE_TIMEOUT_MS = 8_000L

/**
 * Main-thread serialized, latest-only pronunciation. A word prefers its free dictionary audio, then
 * the key-free speech service, which also voices every phrase and example; installed device speech
 * always remains the fallback, and nothing about a superseded request may still reach the speaker.
 * Call shutdown when its owner is destroyed.
 */
class PronunciationPlayer(
    context: Context,
    private val wordAudio: WordAudioSource = YoudaoWordAudioClient(),
    private val sentenceAudio: SentenceAudioSource = TranslateSpeechClient(),
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
    private var trouble: EngineTrouble? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private data class Request(val text: String, val locale: Locale, val id: Long)

    /** Where the current request is playing: online provider bytes through the decoder, or device speech. */
    private enum class Path { Speech, Audio }

    /** What stopped an engine, worst last: the final error names the most actionable of these. */
    private enum class EngineTrouble { Init, Unsupported, MissingData }

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
        mutableState.value = SpeechState(text, playing = true)
        // Only the text asked to be heard may leave the device, and only a single plain word may try
        // the dictionary's own recording before the speech service.
        lookupOnline(request, accent, if (word) wordLookupTerm(text) else null)
    }

    /**
     * The online routes for this request, in order: the dictionary's recording of a single word first
     * where the endpoint accepts one, then the speech service, which voices arbitrary text. The first
     * route with audio plays it; a route that has nothing, fails or redirects only moves on, and
     * installed device speech speaks when none of them can.
     */
    private fun lookupOnline(request: Request, accent: String, term: String?) {
        path = Path.Audio
        lookup = scope.launch {
            val clip = (term?.let { word -> onlineClip(LOOKUP_TIMEOUT_MS) { wordAudio.lookup(word, accent) } })
                ?: onlineClip(LOOKUP_TIMEOUT_MS) { sentenceAudio.lookup(request.text, accent) }
            if (!isCurrent(request)) return@launch
            if (clip == null) requestSpeech(request, NO_ONLINE_AUDIO) else playClip(clip, request)
        }
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
                        mutableState.value = mutableState.value.copy(notice = null, credit = clip.credit)
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
     * TTS service. A failure of the engine being tried only moves on to the next candidate, so a broken
     * or English-less default cannot hide an engine that can speak.
     */
    private fun initialize() {
        if (closed || engine != null) return
        if (candidates.isEmpty()) {
            candidates = listOf<String?>(null) + engines(app)
            candidateIndex = 0
        }
        if (candidateIndex >= candidates.size) {
            reportEngineFailure()
            return
        }
        val candidate = candidates[candidateIndex++]
        val initialization = ++engineGeneration
        val onInit = TextToSpeech.OnInitListener { status ->
            // Always post: construction must have assigned engine before handling init.
            main.post { onEngineInitialized(initialization, status) }
        }
        try {
            engine = if (candidate == null) TextToSpeech(app, onInit) else TextToSpeech(app, onInit, candidate)
        } catch (_: RuntimeException) {
            engine = null
            noteTrouble(EngineTrouble.Init)
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
        noteTrouble(EngineTrouble.Init)
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
            noteTrouble(EngineTrouble.Init)
            // A superseded sentence's engine must not fail the newer request, which may not need
            // system speech at all.
            if (path == Path.Speech && pending != null) initialize() else forgetEngines()
            return
        }
        ready = true
        engine?.setAudioAttributes(attributes)
        engine?.setOnUtteranceProgressListener(listener)
        playLatest()
    }

    private fun playLatest() {
        if (path != Path.Speech) return
        val request = pending ?: return
        val tts = engine ?: return
        try {
            if (request.text.length > TextToSpeech.getMaxSpeechInputLength()) {
                fail("This text is too long for system speech. Select a shorter phrase.")
                return
            }
            when (val availability = configureSpeech(
                request.locale, { tts.voices }, { tts.setVoice(it) },
                { tts.setLanguage(it) }, { tts.voice }
            )) {
                is SpeechAvailability.Available -> {
                    if (!requestFocus(request)) return
                    val notices = listOfNotNull(speechNotice, availability.notice)
                    speechNotice = null
                    pending = null
                    mutableState.value = SpeechState(
                        request.text, playing = true,
                        notice = notices.joinToString(" ").takeIf { it.isNotEmpty() }
                    )
                }
                SpeechAvailability.MissingData -> {
                    noteTrouble(EngineTrouble.MissingData)
                    releaseEngine()
                    initialize()
                    return
                }
                SpeechAvailability.Unsupported -> {
                    noteTrouble(EngineTrouble.Unsupported)
                    releaseEngine()
                    initialize()
                    return
                }
            }
            if (tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id.toString()) != TextToSpeech.SUCCESS) {
                releaseEngine()
                fail("Speech playback failed. Check system speech settings.")
            }
        } catch (_: RuntimeException) {
            releaseEngine()
            fail("Speech playback failed. Check system speech settings and try again.")
        }
    }

    /** No engine is left to try, so the request fails with the most actionable cause found. */
    private fun reportEngineFailure() {
        val found = trouble
        trouble = null
        forgetEngines()
        if (path != Path.Speech || pending == null) return
        fail(when (found) {
            EngineTrouble.MissingData -> MISSING_ENGLISH_DATA
            EngineTrouble.Unsupported -> NO_ENGLISH_VOICE
            EngineTrouble.Init, null -> NO_SPEECH_ENGINE
        })
    }

    private fun noteTrouble(found: EngineTrouble) {
        val current = trouble
        if (current == null || found.ordinal > current.ordinal) trouble = found
    }

    private fun forgetEngines() {
        candidates = emptyList()
        candidateIndex = 0
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = callback(utteranceId) {
            mutableState.value = mutableState.value.copy(playing = true, error = null)
        }
        override fun onDone(utteranceId: String?) = callback(utteranceId) { finish() }
        @Deprecated("Required by the Android listener contract")
        override fun onError(utteranceId: String?) = callback(utteranceId) {
            releaseEngine()
            fail("Speech playback failed. Check system speech settings.")
        }
        override fun onError(utteranceId: String?, errorCode: Int) = callback(utteranceId) {
            releaseEngine()
            fail(if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
                "This voice is not installed. Open system speech settings to download it."
            } else {
                "Speech playback failed. Check system speech settings."
            })
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) = callback(utteranceId) { finish() }
    }

    private fun callback(id: String?, action: () -> Unit) {
        main.post {
            if (!closed && id == generation.toString() && mutableState.value.playing) action()
        }
    }

    private fun finish() {
        pending = null
        releaseMedia()
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false)
    }

    private fun fail(message: String) {
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
        trouble = null
        mutableState.value = mutableState.value.copy(playing = false, error = message, notice = null, credit = null)
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
        trouble = null
        mutableState.value = mutableState.value.copy(playing = false, error = null, notice = null, credit = null)
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
    val default = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    } catch (_: Exception) {
        null
    }
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
 */
internal inline fun configureSpeech(
    locale: Locale,
    voices: () -> Set<Voice>?,
    setVoice: (Voice) -> Int,
    setLanguage: (Locale) -> Int,
    currentVoice: () -> Voice?
): SpeechAvailability {
    val language = normalizeLanguage(locale.language)
    val country = normalizeCountry(locale.country)
    val enumerated = try { voices().orEmpty() } catch (_: RuntimeException) { emptySet() }
    for (candidate in englishVoices(enumerated, locale)) {
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
