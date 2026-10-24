package app.peekturn.reader.narration

import app.peekturn.reader.model.SentenceSpeechProvider
import java.security.MessageDigest

/**
 * The synthesis configuration a cached clip belongs to; none of this is the reading cursor. The
 * four fields stay separate in the hash, so an engine version, voice, prosody or container change
 * each produces a different key (for system speech, [format] names the Wave sample format).
 */
internal data class NarrationVoiceSpec(
    val engine: String,
    val voice: String,
    val prosody: String,
    val format: String
)

/** The US and UK voices the Microsoft route narrates with, plus its fixed format and prosody. */
internal fun narrationVoice(accent: String): NarrationVoiceSpec? = when (accent) {
    "US" -> NarrationVoiceSpec(
        engine = "microsoft-read-aloud",
        voice = "en-US-AriaNeural",
        prosody = "pitch=+0Hz;rate=+0%;volume=+0%",
        format = "mp3-24khz-48kbps-v1"
    )
    "UK" -> NarrationVoiceSpec(
        engine = "microsoft-read-aloud",
        voice = "en-GB-SoniaNeural",
        prosody = "pitch=+0Hz;rate=+0%;volume=+0%",
        format = "mp3-24khz-48kbps-v1"
    )
    else -> null
}

/**
 * The synthesis configuration for a selected online narration provider. Baidu has one English voice,
 * so the accent does not reach it and does not enter its cache key; its engine, voice, prosody and
 * format still differ from Microsoft's, so neither route can ever read the other's cached clip.
 * Android speech is rendered locally by the installed system engine, whose cache profile is built
 * from the bound engine's reported identity by that renderer, so this online route has none.
 */
internal fun narrationVoice(
    provider: SentenceSpeechProvider,
    accent: String
): NarrationVoiceSpec? = when (provider) {
    SentenceSpeechProvider.MICROSOFT -> narrationVoice(accent)
    SentenceSpeechProvider.BAIDU -> NarrationVoiceSpec(
        engine = "baidu-translate",
        voice = "en",
        prosody = "spd=3",
        format = "mp3-translate-v1"
    )
    SentenceSpeechProvider.ANDROID -> null
}

/**
 * A stable, opaque cache name for one complete sentence and one synthesis configuration. The text
 * is normalized for whitespace but its punctuation and case stay significant. No cursor or book
 * position is part of the key, because the same sentence must play again after a restart.
 */
internal fun sentenceAudioKey(sentence: String, voice: NarrationVoiceSpec): String {
    val canonical = canonicalSentence(sentence)
    val material = buildString {
        append("peekturn-sentence-audio\u0000")
        append(voice.engine).append('\u0000')
        append(voice.voice).append('\u0000')
        append(voice.prosody).append('\u0000')
        append(voice.format).append('\u0000')
        append(canonical)
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}

private const val HEX = "0123456789abcdef"
