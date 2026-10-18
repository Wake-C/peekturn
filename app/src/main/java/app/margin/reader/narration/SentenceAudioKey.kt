package app.margin.reader.narration

import java.security.MessageDigest

/** The synthesis configuration a cached clip belongs to; none of this is the reading cursor. */
internal data class NarrationVoiceSpec(
    val engine: String,
    val voice: String,
    val prosody: String,
    val format: String
)

/** The US and UK voices this app narrates with, plus the fixed synthesis format and prosody. */
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
 * A stable, opaque cache name for one complete sentence and one synthesis configuration. The text
 * is normalized for whitespace but its punctuation and case stay significant. No cursor or book
 * position is part of the key, because the same sentence must play again after a restart.
 */
internal fun sentenceAudioKey(sentence: String, voice: NarrationVoiceSpec): String {
    val canonical = canonicalSentence(sentence)
    val material = buildString {
        append("margin-sentence-audio\u0000")
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
