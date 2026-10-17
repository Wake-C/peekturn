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
 * [credit] describes the recording currently used for a word and stays until the next request or stop.
 */
data class SpeechState(
    val text: String = "",
    val playing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val credit: RecordingCredit? = null
)

internal const val ACCENT_NOT_GUARANTEED = "Using system English; requested accent is not guaranteed."
private const val MISSING_ENGLISH_DATA = "English voice data is missing. Open system speech settings to download it, then try again."
private const val NO_ENGLISH_VOICE = "No installed English voice can speak this. Open system speech settings to add one."
private const val RECORDING_UNAVAILABLE = "No recorded pronunciation is available; using system speech."
private const val LOOKUP_TIMEOUT_MS = 10_000L
private const val PREPARE_TIMEOUT_MS = 8_000L

/**
 * Main-thread serialized, latest-only pronunciation. A word prefers the requested accent's Commons
 * recording, installed English speech always remains the fallback, and nothing about a superseded
 * request may still reach the speaker. Call shutdown when its owner is destroyed.
 */
class PronunciationPlayer(
    context: Context,
    private val recordings: WordAudioSource = WordAudioClient()
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
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private data class Request(val text: String, val locale: Locale, val id: Long)

    private enum class Path { Speech, Recording }

    /** A single word: the requested accent's recording when available, otherwise installed English speech. */
    fun speakWord(text: String, accent: String) = onMain { begin(text, accent, recording = true) }

    /** A phrase, sentence or example: installed English system speech only. */
    fun speakExample(text: String, accent: String) = onMain { begin(text, accent, recording = false) }

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

    private fun begin(text: String, accent: String, recording: Boolean) {
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
        // Only one plain word may leave the device; anything else speaks locally.
        val term = if (recording) wordLookupTerm(text) else null
        if (term == null) requestSpeech(request, null) else lookupRecording(request, term, accent)
    }

    private fun lookupRecording(request: Request, term: String, accent: String) {
        path = Path.Recording
        lookup = scope.launch {
            val recording = try {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { recordings.lookup(term, accent) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isCurrent(request)) return@launch
            if (recording == null) {
                requestSpeech(request, RECORDING_UNAVAILABLE)
            } else {
                playRecording(recording, request)
            }
        }
    }

    /** Only the newest request may still reach the speaker; superseded ones are ignored entirely. */
    private fun isCurrent(request: Request) = !closed && request.id == generation && pending === request

    private fun playRecording(recording: RecordedWordAudio, request: Request) {
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
            player.setDataSource(RecordingDataSource(recording.audio))
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
                        mutableState.value = mutableState.value.copy(notice = null, credit = recording.credit)
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

    /** A recording that cannot play is never an error while installed English speech can speak instead. */
    private fun fallbackToSpeech(request: Request) {
        releaseMedia()
        requestSpeech(request, RECORDING_UNAVAILABLE)
    }

    /**
     * Only the player that is still the current recording may start, finish or fail it: a superseded,
     * released or replaced player must never reach the speaker or claim the recording's credit.
     */
    private fun mediaAction(player: MediaPlayer, request: Request, action: () -> Unit) {
        main.post {
            if (media === player && path == Path.Recording && isCurrent(request) && mutableState.value.playing) action()
        }
    }

    private fun requestSpeech(request: Request, notice: String?) {
        if (!isCurrent(request)) return
        path = Path.Speech
        speechNotice = notice
        if (ready) playLatest() else if (engine == null) initialize()
    }

    private fun initialize() {
        val initialization = ++engineGeneration
        try {
            engine = TextToSpeech(app) { status ->
                // Always post: construction must have assigned engine before handling init.
                main.post {
                    if (closed || initialization != engineGeneration) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        releaseEngine()
                        // A superseded sentence's engine must not fail the newer request, which may not
                        // need system speech at all.
                        if (path == Path.Speech && pending != null) {
                            fail("No working speech engine. Open system speech settings to install one.")
                        }
                    } else {
                        ready = true
                        engine?.setAudioAttributes(attributes)
                        engine?.setOnUtteranceProgressListener(listener)
                        playLatest()
                    }
                }
            }
        } catch (_: RuntimeException) {
            releaseEngine()
            fail("Speech could not initialize. Open system speech settings.")
        }
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
                    releaseEngine()
                    fail(MISSING_ENGLISH_DATA)
                    return
                }
                SpeechAvailability.Unsupported -> {
                    releaseEngine()
                    fail(NO_ENGLISH_VOICE)
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
        engine?.stop()
        releaseMedia()
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false, error = message, notice = null, credit = null)
    }

    private fun stopInternal() {
        generation++
        pending = null
        path = Path.Speech
        lookup?.cancel()
        lookup = null
        speechNotice = null
        engine?.stop()
        releaseMedia()
        abandonFocus()
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
 * Installed English voices, best first: offline before network-only, then the requested accent, then
 * quality, latency and a stable name. A country difference is a preference, never a blocker.
 */
internal fun englishVoices(voices: Set<Voice>, locale: Locale): List<Voice> =
    voices.filter {
        it.locale.language == locale.language && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
    }.sortedWith(
        compareBy<Voice>(
            { it.isNetworkConnectionRequired }, { it.locale.country != locale.country },
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
    val enumerated = try { voices().orEmpty() } catch (_: RuntimeException) { emptySet() }
    for (candidate in englishVoices(enumerated, locale)) {
        val selected = try { setVoice(candidate) == TextToSpeech.SUCCESS } catch (_: RuntimeException) { false }
        if (!selected) continue
        val actual = try { currentVoice() } catch (_: RuntimeException) { null }
        if (actual != null && actual.locale.language != locale.language) continue
        if (actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            return SpeechAvailability.MissingData
        }
        // A reported voice is authoritative, including an empty country: only an unreported one may
        // fall back to what the accepted voice promised.
        val country = actual?.locale?.country ?: candidate.locale.country
        return SpeechAvailability.Available(if (country == locale.country) null else ACCENT_NOT_GUARANTEED)
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
        if (actual != null && actual.locale.language != locale.language) continue
        if (actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            return SpeechAvailability.MissingData
        }
        val country = actual?.locale?.country
            ?: probe.country.takeIf { support >= TextToSpeech.LANG_COUNTRY_AVAILABLE }
        return SpeechAvailability.Available(if (country == locale.country) null else ACCENT_NOT_GUARANTEED)
    }
    return if (missingData) SpeechAvailability.MissingData else SpeechAvailability.Unsupported
}

internal fun otherEnglishCountry(locale: Locale): Locale = if (locale.country == "GB") Locale.US else Locale.UK

/** Bounded in-memory audio for MediaPlayer: no temporary file can leak, and nothing is written to disk. */
private class RecordingDataSource(private val audio: ByteArray) : MediaDataSource() {
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
