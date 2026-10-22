package app.peekturn.reader.speech

import android.speech.tts.TextToSpeech
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SystemSpeechEvidenceTest {
    @Test fun unsupportedAccentNamesAreRejectedAndSupportedOnesMap() {
        assertEquals(Locale.US, speechLocale("US"))
        assertEquals(Locale.UK, speechLocale("UK"))
        assertNull(speechLocale("AU"))
        assertNull(speechLocale(""))
    }

    @Test fun engineSuppliedVoiceFieldsAreRedactedWithoutLosingLanguageOrAccent() {
        val evidence = SpeechEvidence()
        evidence.addEngine("com.example.engine")
        evidence.observe(SpeechObservation.DefaultVoiceReported(SpeechVoiceFact(
            name = "voice\nforged: https://evil.example/voice?token=S3CRETVALUE",
            locale = Locale.US,
            features = setOf("embeddedTts", "/Users/alice/private", "token=S3CRETVALUE")
        )))
        val facts = evidence.facts()
        assertTrue(facts.contains("language=en"))
        assertTrue(facts.contains("country=US"))
        assertTrue(facts.contains("tag=en-US"))
        assertFalse(facts.contains("evil.example"))
        assertFalse(facts.contains("/Users/alice"))
        assertFalse(facts.contains("S3CRETVALUE"))
        assertEquals("Provider metadata cannot forge a log record", 2, facts.lines().size)
    }

    @Test fun aFreshDefaultVoiceReplacesThePreviousSnapshot() {
        val evidence = SpeechEvidence()
        evidence.addEngine("system.engine")
        evidence.observe(SpeechObservation.DefaultVoiceReported(SpeechVoiceFact("first", Locale.US)))
        evidence.observe(SpeechObservation.DefaultVoiceReported(SpeechVoiceFact("second", Locale.UK)))
        val facts = evidence.facts()
        assertTrue(facts.contains("second"))
        assertFalse(facts.contains("first"))
    }

    @Test fun unavailableVoiceMetadataDoesNotEraseTheEnginesRealAnswers() {
        val evidence = SpeechEvidence()
        evidence.addEngine("system.engine", "2.0")
        evidence.observe(SpeechObservation.InitAnswered(TextToSpeech.SUCCESS))
        evidence.observe(SpeechObservation.DefaultVoiceReported(null))
        evidence.observe(SpeechObservation.SpeakAnswered(TextToSpeech.SUCCESS))
        evidence.recordUtterance("onError", TextToSpeech.ERROR_SYNTHESIS)
        val report = evidence.report(DeviceSpeechFailure.PlaybackFailed)
        assertTrue(report.contains("system.engine"))
        assertTrue(report.contains("2.0"))
        assertTrue(report.contains(TextToSpeech.ERROR_SYNTHESIS.toString()))
        assertNotEquals(report, SpeechEvidence().report(DeviceSpeechFailure.NotStarted))
    }
}
