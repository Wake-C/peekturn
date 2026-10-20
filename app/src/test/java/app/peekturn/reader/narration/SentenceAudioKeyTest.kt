package app.peekturn.reader.narration

import app.peekturn.reader.model.SentenceSpeechProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A cache key names the exact audio unit, not the cursor, and never contains the sentence itself. */
class SentenceAudioKeyTest {

    private val us = NarrationVoiceSpec(
        engine = "microsoft-read-aloud",
        voice = "en-US-AriaNeural",
        prosody = "pitch=+0Hz;rate=+0%;volume=+0%",
        format = "mp3-24khz-48kbps-v1"
    )

    @Test fun whitespaceNormalizationReusesTheSameAudio() {
        assertEquals(
            sentenceAudioKey("  Today   is a perfect day. ", us),
            sentenceAudioKey("Today is a perfect day.", us)
        )
    }

    @Test fun engineVoiceProsodyAndFormatArePartOfTheKey() {
        val base = sentenceAudioKey("One sentence.", us)
        assertNotEquals(base, sentenceAudioKey("One sentence.", us.copy(engine = "other")))
        assertNotEquals(base, sentenceAudioKey("One sentence.", us.copy(voice = "en-GB-SoniaNeural")))
        assertNotEquals(base, sentenceAudioKey("One sentence.", us.copy(prosody = "rate=+10%")))
        assertNotEquals(base, sentenceAudioKey("One sentence.", us.copy(format = "mp3-v2")))
    }

    @Test fun differentSentencesNeverCollideInPractice() {
        assertNotEquals(
            sentenceAudioKey("Today is a perfect day.", us),
            sentenceAudioKey("Today is a perfect day!", us)
        )
    }

    @Test fun eachOnlineProviderOwnsADistinctCacheConfiguration() {
        val microsoft = checkNotNull(narrationVoice(SentenceSpeechProvider.MICROSOFT, "US"))
        val baidu = checkNotNull(narrationVoice(SentenceSpeechProvider.BAIDU, "US"))

        assertNotEquals(microsoft, baidu)
        assertEquals(
            "Baidu has one English voice, so the accent cannot change its cache entry",
            baidu,
            narrationVoice(SentenceSpeechProvider.BAIDU, "UK")
        )
        assertNotEquals(
            sentenceAudioKey("Today is a perfect day.", microsoft),
            sentenceAudioKey("Today is a perfect day.", baidu)
        )
        assertNull("Android system speech has no cached synthesis configuration", narrationVoice(
            SentenceSpeechProvider.ANDROID,
            "US"
        ))
    }

    @Test fun theKeyIsOpaque() {
        val key = sentenceAudioKey("Today is a perfect day.", us)
        assertEquals(64, key.length)
        assert(key.matches(Regex("[0-9a-f]{64}")))
    }
}
