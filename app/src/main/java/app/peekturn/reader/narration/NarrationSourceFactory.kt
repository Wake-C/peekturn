package app.peekturn.reader.narration

import app.peekturn.reader.model.SentenceSpeechProvider
import app.peekturn.reader.speech.BaiduSpeechClient
import app.peekturn.reader.speech.BingSpeechClient
import app.peekturn.reader.speech.SentenceAudioSource

/**
 * The one place both live narration and saved-audio preparation learn which online transport and
 * which human-readable voice a provider choice means. System speech has no online transport; its
 * saved-audio label comes from the engine's own reported voice.
 */
internal fun onlineNarrationSource(provider: SentenceSpeechProvider): SentenceAudioSource? =
    when (provider) {
        SentenceSpeechProvider.MICROSOFT -> BingSpeechClient()
        SentenceSpeechProvider.BAIDU -> BaiduSpeechClient()
        SentenceSpeechProvider.ANDROID -> null
    }

/** A short, real voice name for the selected provider and accent; null when none can narrate. */
internal fun narrationVoiceLabel(provider: SentenceSpeechProvider, accent: String): String? =
    when (provider) {
        SentenceSpeechProvider.MICROSOFT -> when (accent) {
            "US" -> "Microsoft Aria (en-US)"
            "UK" -> "Microsoft Sonia (en-GB)"
            else -> null
        }
        SentenceSpeechProvider.BAIDU -> "Baidu Translate (en)"
        SentenceSpeechProvider.ANDROID -> null
    }
