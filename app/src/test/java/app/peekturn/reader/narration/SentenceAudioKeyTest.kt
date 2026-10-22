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

    @Test fun theExistingOnlineCacheNameIsStable() {
        // Pinned material: persisted MP3 clips from before system narration must still be found.
        assertEquals(
            "778f81a3f7949319f6e5125811206515da0b80456d20c55ffdc1daac1d316445",
            sentenceAudioKey("Today is a perfect day.", us)
        )
    }

    @Test fun anAndroidWaveProfileSeparatesEngineVersionVoiceProsodyAndFormat() {
        val wave = NarrationVoiceSpec(
            engine = "com.google.android.tts/1.0",
            voice = "en-us-x-iol-local",
            prosody = "pitch=1.0;lang=en-US",
            format = "wav-pcm16-24000hz-mono"
        )
        val base = sentenceAudioKey("One sentence.", wave)
        assertNotEquals(base, sentenceAudioKey("One sentence.", wave.copy(engine = "com.google.android.tts/2.0")))
        assertNotEquals(base, sentenceAudioKey("One sentence.", wave.copy(voice = "en-gb-x-gbb-local")))
        assertNotEquals(base, sentenceAudioKey("One sentence.", wave.copy(prosody = "pitch=1.2;lang=en-US")))
        assertNotEquals(base, sentenceAudioKey("One sentence.", wave.copy(format = "wav-pcm16-48000hz-mono")))
        assertNotEquals(
            "a system wave never collides with an online MP3",
            base,
            sentenceAudioKey("One sentence.", us)
        )
    }

    @Test fun theKeyIsOpaque() {
        val key = sentenceAudioKey("Today is a perfect day.", us)
        assertEquals(64, key.length)
        assert(key.matches(Regex("[0-9a-f]{64}")))
    }
}
