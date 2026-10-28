package app.peekturn.reader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.ModelConfig
import app.peekturn.reader.model.LlmProfile
import app.peekturn.reader.model.ReaderPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ThemePreferencesTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
    }

    @Test fun newSettingsDefaultToWhiteAndGroupedVideoSentences() {
        val defaults = AppSettings()
        val stored = PeekturnStore(context).settings.value
        assertEquals("light", defaults.appTheme)
        assertTrue(defaults.videoMergeCaptions)
        assertEquals("light", stored.appTheme)
        assertTrue(stored.videoMergeCaptions)
    }

    @Test fun explicitDarkAndUngroupedChoicesSurviveReloadAndUnrelatedEdits() = runBlocking {
        val store = PeekturnStore(context)
        store.updateSettings { it.copy(appTheme = "dark", videoMergeCaptions = false) }
        val restored = PeekturnStore(context)
        assertEquals("dark", restored.settings.value.appTheme)
        assertFalse(restored.settings.value.videoMergeCaptions)

        restored.updateSettings { it.copy(quickExplain = true) }
        val reloaded = PeekturnStore(context).settings.value
        assertEquals("dark", reloaded.appTheme)
        assertFalse(reloaded.videoMergeCaptions)
        assertTrue(reloaded.quickExplain)
    }

    @Test fun invalidAppearanceUsesWhiteWithoutResettingOtherChoices() = runBlocking {
        val store = PeekturnStore(context)
        store.updateSettings { it.copy(appTheme = "unknown", videoMergeCaptions = false) }
        val restored = PeekturnStore(context).settings.value
        assertEquals("light", restored.appTheme)
        assertFalse(restored.videoMergeCaptions)
    }

    @Test fun oneAppAppearancePersistsWithoutReplacingReadingOrModelPreferences() = runBlocking {
        val initial = AppSettings(
            quickExplain = true, autoPronounce = true, accent = "UK",
            profiles = listOf(LlmProfile("default", ModelConfig(name = "My provider", endpoint = "https://example.org/v1", model = "my-model", temperature = 1.2))),
            reading = ReaderPreferences(fontSize = 135.0, lineHeight = 1.9, pageMargins = 1.2, scroll = true, topPaddingDp = 40.0)
        )
        val store = PeekturnStore(context)
        store.updateSettings { initial }
        store.updateSettings { latest -> latest.copy(appTheme = "light") }
        val restored = PeekturnStore(context)
        assertEquals(initial.copy(appTheme = "light"), restored.settings.value)

        restored.updateSettings { latest -> latest.copy(appTheme = "warm") }
        assertEquals(initial.copy(appTheme = "warm"), PeekturnStore(context).settings.value)
    }

    @Test fun midnightAndSageSurviveAReload() = runBlocking {
        val store = PeekturnStore(context)
        listOf("oled", "sage").forEach { theme ->
            store.updateSettings { latest -> latest.copy(appTheme = theme) }
            assertEquals(theme, PeekturnStore(context).settings.value.appTheme)
        }
    }

    @Test fun languageChoiceDefaultsToSystemPersistsAndRejectsUnknownValues() = runBlocking {
        assertEquals("system", PeekturnStore(context).settings.value.uiLanguage)
        val store = PeekturnStore(context)
        store.updateSettings { latest -> latest.copy(uiLanguage = "zh") }
        assertEquals("zh", PeekturnStore(context).settings.value.uiLanguage)
        store.updateSettings { latest -> latest.copy(uiLanguage = "fr") }
        assertEquals("system", PeekturnStore(context).settings.value.uiLanguage)
    }

    @Test fun existingSettingsWithoutAppearanceOrGroupingUseDefaultsAndIgnoreObsoleteReaderPaper() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().putString(
            "settings",
            """{"quickExplain":true,"accent":"UK","model":{"name":"Existing provider","model":"existing-model"},"reading":{"theme":"light","fontSize":125}}"""
        ).commit()
        val restored = PeekturnStore(context).settings.value
        assertEquals("light", restored.appTheme)
        assertTrue(restored.videoMergeCaptions)
        assertEquals(ReaderPreferences(fontSize = 125.0), restored.reading)
        assertEquals(ModelConfig(name = "Existing provider", model = "existing-model"), restored.model)
        assertEquals(true, restored.quickExplain)
        assertEquals("UK", restored.accent)
    }

    @Test fun legacyBarsMigrateToOneImmersiveChoiceWithoutChangingTypography() {
        listOf(true to true, true to false, false to true, false to false).forEach { (status, navigation) ->
            context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().putString(
                "settings",
                """{"reading":{"fontSize":145,"hideStatusBar":$status,"hideNavigationBar":$navigation}}"""
            ).commit()
            val reading = PeekturnStore(context).settings.value.reading
            assertEquals(status && navigation, reading.immersive)
            assertEquals(145.0, reading.fontSize, 0.0)
            assertEquals(16.0, reading.topPaddingDp, 0.0)
            assertEquals(24.0, reading.bottomPaddingDp, 0.0)
        }
    }

}
