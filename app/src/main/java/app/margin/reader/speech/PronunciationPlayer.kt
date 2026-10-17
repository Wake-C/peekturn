package app.margin.reader.speech

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Playing includes pending initialization, so the same speaker button can always stop it. */
data class SpeechState(val text: String = "", val playing: Boolean = false, val error: String? = null)

/** Main-thread serialized, latest-only system speech. Call shutdown when its owner is destroyed. */
class PronunciationPlayer(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutableState.asStateFlow()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var generation = 0L
    private var pending: Request? = null
    private var focus: AudioFocusRequest? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private data class Request(val text: String, val locale: Locale, val id: Long)

    fun speak(text: String, accent: String) = onMain {
        if (closed) return@onMain
        stopInternal()
        val locale = speechLocale(accent)
        if (locale == null) {
            fail("Choose US or UK pronunciation.")
            return@onMain
        }
        if (text.isBlank()) {
            fail("There is no text to pronounce.")
            return@onMain
        }
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            fail("This text is too long for system speech. Select a shorter phrase.")
            return@onMain
        }
        pending = Request(text, locale, generation)
        mutableState.value = SpeechState(text, playing = true)
        if (ready) playLatest() else if (engine == null) initialize()
    }

    fun stop() = onMain { stopInternal() }

    fun shutdown() = onMain {
        if (!closed) {
            closed = true
            stopInternal()
            engine?.shutdown()
            engine = null
            ready = false
        }
    }

    fun openSystemSettings() = onMain {
        stopInternal()
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

    private fun initialize() {
        try {
            engine = TextToSpeech(app) { status ->
                // Always post: construction must have assigned engine before handling init.
                main.post {
                    if (closed) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        engine?.shutdown()
                        engine = null
                        ready = false
                        fail("No working speech engine. Open system speech settings to install one.")
                    } else {
                        ready = true
                        engine?.setAudioAttributes(attributes)
                        engine?.setOnUtteranceProgressListener(listener)
                        playLatest()
                    }
                }
            }
        } catch (_: RuntimeException) {
            engine?.shutdown()
            engine = null
            fail("Speech could not initialize. Open system speech settings.")
        }
    }

    private fun playLatest() {
        val request = pending ?: return
        val tts = engine ?: return
        try {
            val voice = chooseVoice(tts.voices.orEmpty(), request.locale)
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                fail("No installed ${if (request.locale.country == "GB") "UK" else "US"} English voice. Open system speech settings to install one.")
                return
            }
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    if (change < 0 && generation == request.id) stopInternal()
                }, main)
                .build()
            focus = focusRequest
            if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail("Audio is currently unavailable. Try pronunciation again.")
                return
            }
            pending = null
            if (tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id.toString()) != TextToSpeech.SUCCESS) {
                fail("Speech playback failed. Check system speech settings.")
            }
        } catch (_: RuntimeException) {
            fail("Speech playback failed. Check system speech settings.")
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = callback(utteranceId) {
            mutableState.value = mutableState.value.copy(playing = true, error = null)
        }
        override fun onDone(utteranceId: String?) = callback(utteranceId) { finish() }
        @Deprecated("Required by the Android listener contract")
        override fun onError(utteranceId: String?) = callback(utteranceId) {
            fail("Speech playback failed. Check system speech settings.")
        }
        override fun onError(utteranceId: String?, errorCode: Int) = callback(utteranceId) {
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
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false)
    }

    private fun fail(message: String) {
        generation++
        pending = null
        engine?.stop()
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false, error = message)
    }

    private fun stopInternal() {
        generation++
        pending = null
        engine?.stop()
        abandonFocus()
        mutableState.value = mutableState.value.copy(playing = false, error = null)
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

internal fun chooseVoice(voices: Set<Voice>, locale: Locale): Voice? {
    var best: Voice? = null
    for (voice in voices) {
        if (voice.locale.language != locale.language || voice.locale.country != locale.country ||
            voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) continue
        val previous = best
        if (previous == null || betterVoice(voice, previous)) best = voice
    }
    return best
}

private fun betterVoice(candidate: Voice, current: Voice): Boolean = when {
    candidate.isNetworkConnectionRequired != current.isNetworkConnectionRequired -> !candidate.isNetworkConnectionRequired
    candidate.quality != current.quality -> candidate.quality > current.quality
    candidate.latency != current.latency -> candidate.latency < current.latency
    else -> candidate.name < current.name
}
