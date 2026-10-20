package app.peekturn.reader.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The compatibility contract for domestic Android engines: an explicit installed English voice is
 * preferred, an accepted English language is honored even when the engine's reported voice is stale
 * or names another language, and the reader is told when that metadata could not be confirmed.
 *
 * These cases use only the pre-existing [configureSpeech]/[SpeechAvailability] surface and no new
 * resource id, so the same file can run against the snapshot taken before the policy changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SystemSpeechCompatibilityTest {
    private fun voice(
        name: String,
        locale: Locale = Locale.US,
        network: Boolean = false,
        quality: Int = Voice.QUALITY_NORMAL,
        features: Set<String> = emptySet()
    ) = Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, features)

    private fun SpeechAvailability.assertAvailableWithNotice() {
        assertTrue("English must reach synthesis instead of failing: $this", this is SpeechAvailability.Available)
        assertNotNull(
            "Unconfirmed voice metadata must be disclosed",
            (this as SpeechAvailability.Available).noticeRes
        )
    }

    /** A domestic engine answers US English but keeps reporting its Chinese default voice. */
    @Test fun acceptedUsLanguageWithAChineseCurrentVoiceStillPlaysWithNotice() {
        val result = configureSpeech(
            Locale.US,
            { emptySet() },
            { error("No non-English voice may be selected") },
            { probe ->
                if (probe == Locale.US) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            },
            { voice("oem-default", locale = Locale.CHINESE) }
        )

        result.assertAvailableWithNotice()
    }

    /** A positive language answer without any current voice metadata still gets a compatibility try. */
    @Test fun acceptedLanguageWithoutCurrentVoiceMetadataStillPlaysWithNotice() {
        val result = configureSpeech(
            Locale.US,
            { emptySet() },
            { error("No voice was enumerated") },
            { probe ->
                if (probe == Locale.US) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            },
            { null }
        )

        result.assertAvailableWithNotice()
    }

    /** No accepted English language means no play and no non-English voice selected behind the reader. */
    @Test fun noAcceptedEnglishLanguageStaysUnsupportedAndNeverSelectsAnotherVoice() {
        val selected = mutableListOf<Voice>()
        val chinese = voice("oem-chinese", locale = Locale.CHINESE)
        val result = configureSpeech(
            Locale.US,
            { setOf(chinese) },
            { selected += it; TextToSpeech.SUCCESS },
            { TextToSpeech.LANG_NOT_SUPPORTED },
            { chinese }
        )

        assertEquals(SpeechAvailability.Unsupported, result)
        assertTrue("A non-English voice is never explicitly selected", selected.isEmpty())
    }

    /** Missing data stays a missing-data answer, whichever path reported it. */
    @Test fun missingDataAndNotInstalledVoicesRemainMissingData() {
        assertEquals(SpeechAvailability.MissingData, configureSpeech(
            Locale.US,
            { emptySet() },
            { TextToSpeech.ERROR },
            { TextToSpeech.LANG_MISSING_DATA },
            { null }
        ))
        assertEquals(SpeechAvailability.MissingData, configureSpeech(
            Locale.US,
            { emptySet() },
            { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE },
            { voice("en-us-download", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) }
        ))
    }

    /** A UK request whose engine refuses UK but accepts US still reaches synthesis for a word. */
    @Test fun ukUnsupportedThenUsAcceptedStillPlaysWithNotice() {
        val probes = mutableListOf<Locale>()
        val result = configureSpeech(
            Locale.UK,
            { emptySet() },
            { error("No voice was enumerated") },
            { probe ->
                probes += probe
                if (probe == Locale.US) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            },
            { voice("stale-default", locale = Locale.CHINESE) }
        )

        assertEquals("The requested accent is probed before the other English country", listOf(Locale.UK, Locale.US), probes)
        result.assertAvailableWithNotice()
    }
}
