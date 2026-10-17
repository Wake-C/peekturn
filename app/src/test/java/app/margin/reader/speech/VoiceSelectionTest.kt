package app.margin.reader.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoiceSelectionTest {
    private fun voice(
        name: String,
        locale: Locale = Locale.US,
        network: Boolean = false,
        quality: Int = Voice.QUALITY_NORMAL,
        features: Set<String> = emptySet()
    ) = Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, features)

    @Test fun offlineVoiceIsPreferredOverHigherQualityNetworkVoice() {
        val offline = voice("offline", quality = Voice.QUALITY_LOW)
        val network = voice("network", network = true, quality = Voice.QUALITY_VERY_HIGH)
        assertEquals(offline, chooseVoice(setOf(network, offline), Locale.US))
    }

    @Test fun otherAccentIsNeverAnImplicitFallback() {
        assertNull(chooseVoice(setOf(voice("us")), Locale.UK))
        assertNull(chooseVoice(setOf(voice("english", locale = Locale.ENGLISH)), Locale.US))
        assertEquals(Locale.US, speechLocale("US"))
        assertEquals(Locale.UK, speechLocale("UK"))
        assertNull(speechLocale("AU"))
    }

    @Test fun advertisedButUninstalledVoiceIsNotPlayable() {
        val missing = voice("missing", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        assertNull(chooseVoice(setOf(missing), Locale.US))
    }

    @Test fun matchingNetworkVoiceIsUsableWhenNoOfflineVoiceIsInstalled() {
        val network = voice("network", network = true)
        assertEquals(network, chooseVoice(setOf(network), Locale.US))
    }

    @Test fun stableNameBreaksEquivalentVoiceTies() {
        val first = voice("a")
        val second = voice("b")
        assertEquals(first, chooseVoice(setOf(second, first), Locale.US))
        assertEquals(first, chooseVoice(setOf(first, second), Locale.US))
    }
}
