package app.peekturn.reader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.BookReadingTime
import app.peekturn.reader.model.ModelConfig
import app.peekturn.reader.model.LlmProfile
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.model.ReadingDay
import app.peekturn.reader.model.ReadingStats
import app.peekturn.reader.model.SentenceSpeechProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PeekturnStoreTest {
    private lateinit var context: Context
    private lateinit var store: PeekturnStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
        store = PeekturnStore(context)
    }

    @Test fun latestCompletedLocatorWriteSurvivesStoreRecreationWithoutBecomingLastOpenedBook() = runBlocking {
        val book = addBook("Reading")
        val first = """{"href":"chapter-1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.25}}"""
        val latest = """{"href":"chapter-2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.75},"text":{"highlight":"same word, different occurrence"}}"""
        store.savePosition(book.id, first, 0.25)
        store.savePosition(book.id, latest, 0.75)

        val restored = PeekturnStore(context)
        restored.load()
        assertEquals(latest, restored.findBook(book.id)?.locatorJson)
        assertEquals(0.75, restored.books.value.single().progression, 0.0)
        assertNull(restored.lastBookId())
    }

    @Test fun openingAndSavingAnotherBookPreserveEachBooksOwnLocator() = runBlocking {
        val first = addBook("First")
        val second = addBook("Second")
        store.savePosition(first.id, "first locator", 0.2)
        store.markOpened(first.id)
        store.savePosition(second.id, "second locator", 0.8)
        store.markOpened(second.id)

        val restored = PeekturnStore(context)
        assertEquals(second.id, restored.lastBookId())
        assertEquals("first locator", restored.findBook(first.id)?.locatorJson)
        assertEquals("second locator", restored.findBook(second.id)?.locatorJson)
        restored.markOpened(Long.MAX_VALUE)
        assertEquals(second.id, restored.lastBookId())
    }

    @Test fun deletingLastOpenedBookClearsReopeningAndOnlyItsReadingTime() = runBlocking {
        val removed = addBook("Removed")
        val retained = addBook("Retained")
        store.addReadingTime(removed.id, "2026-10-14", 40)
        store.addReadingTime(removed.id, "2026-10-15", 60)
        store.addReadingTime(retained.id, "2026-10-15", 20)
        store.addReadingTime(retained.id, "2026-10-15", 10)
        store.markOpened(removed.id)
        store.deleteBook(removed.id)
        // A late lifecycle flush must not resurrect time for a removed book.
        store.addReadingTime(removed.id, "2026-10-15", 90)
        store.savePosition(removed.id, "late locator", 1.0)

        val restored = PeekturnStore(context)
        restored.load()
        assertNull(restored.lastBookId())
        assertNull(restored.findBook(removed.id))
        assertFalse(File(removed.filePath).exists())
        assertTrue(File(retained.filePath).exists())
        assertEquals(listOf(retained.id), restored.books.value.map { it.id })
        assertEquals(ReadingStats(listOf(ReadingDay("2026-10-15", 30)), listOf(BookReadingTime(retained.id, 30))), restored.stats.value)
    }

    @Test fun deletingAnotherBookKeepsLastOpenedBookAndItsPosition() = runBlocking {
        val retained = addBook("Retained")
        val removed = addBook("Removed")
        store.markOpened(retained.id)
        store.savePosition(retained.id, "retained locator", 0.4)
        store.deleteBook(removed.id)
        assertEquals(retained.id, store.lastBookId())
        assertEquals("retained locator", store.findBook(retained.id)?.locatorJson)
    }

    @Test fun clearingFailedLastBookPreservesLibraryPositionAndPreferences() = runBlocking {
        val book = addBook("Unreadable")
        val settings = AppSettings(quickExplain = true, reading = ReaderPreferences(scroll = true))
        store.updateSettings { settings }
        store.markOpened(book.id)
        store.savePosition(book.id, "saved locator", 0.6)
        store.clearLastBook()

        val restored = PeekturnStore(context)
        assertNull(restored.lastBookId())
        assertEquals("saved locator", restored.findBook(book.id)?.locatorJson)
        assertEquals(settings, restored.settings.value)
    }

    @Test fun preferencesAndModelConfigurationSurviveWithoutAnApiKey() = runBlocking {
        val settings = AppSettings(
            quickExplain = true, autoPronounce = true, accent = "UK",
            sentenceSpeechProvider = SentenceSpeechProvider.BAIDU,
            profiles = listOf(LlmProfile("default", ModelConfig(name = "Local provider", endpoint = "http://127.0.0.1:8080/v1", model = "reader-model", apiKey = "", temperature = 1.2))),
            reading = ReaderPreferences(fontSize = 130.0, fontFamily = "sans-serif", lineHeight = 1.8, pageMargins = 1.2, paragraphSpacing = 0.9, scroll = true, topPaddingDp = 42.0, bottomPaddingDp = 66.0),
            appTheme = "warm"
        )
        store.updateSettings { settings }
        assertEquals(settings, PeekturnStore(context).settings.value)
    }

    @Test fun immersiveAndIndependentTextDistancesSurviveRestartAndTypographyUpdates() = runBlocking {
        store.updateSettings { it.copy(reading = it.reading.copy(immersive = false, topPaddingDp = 48.0, bottomPaddingDp = 72.0)) }
        store.updateSettings { it.copy(reading = it.reading.copy(fontSize = 145.0)) }
        val restored = PeekturnStore(context)
        assertFalse(restored.settings.value.reading.immersive)
        assertEquals(48.0, restored.settings.value.reading.topPaddingDp, 0.0)
        assertEquals(72.0, restored.settings.value.reading.bottomPaddingDp, 0.0)
        assertEquals(145.0, restored.settings.value.reading.fontSize, 0.0)
        restored.updateSettings { it.copy(reading = it.reading.copy(immersive = true, bottomPaddingDp = 96.0)) }
        val again = PeekturnStore(context).settings.value.reading
        assertTrue(again.immersive)
        assertEquals(48.0, again.topPaddingDp, 0.0)
        assertEquals(96.0, again.bottomPaddingDp, 0.0)
    }

    @Test fun queuedDifferentFieldUpdatesPreserveBothPreferencesAfterRestart() = runBlocking {
        val updates = kotlinx.coroutines.channels.Channel<(AppSettings) -> AppSettings>(capacity = 2)
        updates.send { latest -> latest.copy(quickExplain = true) }
        updates.send { latest -> latest.copy(autoPronounce = true) }
        repeat(2) { store.updateSettings(updates.receive()) }
        updates.close()

        val restored = PeekturnStore(context).settings.value
        assertTrue(restored.quickExplain)
        assertTrue(restored.autoPronounce)
    }

    @Test fun unavailableProtectedKeyDoesNotDiscardPreferencesOrExposeCiphertextAsApiKey() {
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK","model":{"name":"My provider","model":"my-model","apiKey":""}}""")
            .putString("model_key_cipher", "AA==")
            .commit()
        val restored = PeekturnStore(context)
        assertTrue(restored.settings.value.quickExplain)
        assertEquals("UK", restored.settings.value.accent)
        assertEquals("my-model", restored.settings.value.model.model)
        assertEquals("", restored.settings.value.model.apiKey)
        assertNotNull(restored.error.value)
    }

    @Test fun malformedPreferenceFieldsKeepValidChoicesAndRestoreOnlyInvalidDefaults() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK","model":{"name":"My provider","endpoint":null,"temperature":"hot"},"reading":{"fontSize":140,"fontFamily":null},"autoPronounce":"wrong type"}""")
            .commit()

        val restored = PeekturnStore(context)
        assertTrue(restored.settings.value.quickExplain)
        assertFalse(restored.settings.value.autoPronounce)
        assertEquals("UK", restored.settings.value.accent)
        assertEquals(ModelConfig(name = "My provider"), restored.settings.value.model)
        assertEquals(0.3, restored.settings.value.model.temperature, 0.0)
        assertEquals(ReaderPreferences(fontSize = 140.0), restored.settings.value.reading)
        assertNotNull(restored.error.value)
    }

    @Test fun missingSentenceSpeechProviderRestoresMicrosoftWithoutDiscardingOtherChoices() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK"}""")
            .commit()

        val restored = PeekturnStore(context)
        assertEquals(SentenceSpeechProvider.MICROSOFT, restored.settings.value.sentenceSpeechProvider)
        assertTrue(restored.settings.value.quickExplain)
        assertEquals("UK", restored.settings.value.accent)
    }

    @Test fun unknownSentenceSpeechProviderIsRepairedToMicrosoft() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"sentenceSpeechProvider":"GOOGLE","quickExplain":true}""")
            .commit()

        val restored = PeekturnStore(context)
        assertEquals(SentenceSpeechProvider.MICROSOFT, restored.settings.value.sentenceSpeechProvider)
        assertTrue("The valid choice beside it survives", restored.settings.value.quickExplain)
        assertNotNull(restored.error.value)
        assertEquals(SentenceSpeechProvider.MICROSOFT, PeekturnStore(context).settings.value.sentenceSpeechProvider)
    }

    @Test fun incorrectlyTypedSentenceSpeechProviderIsRepairedToMicrosoft() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"sentenceSpeechProvider":7,"profiles":[{"id":"default","config":{"name":"Mine"}}]}""")
            .commit()

        val restored = PeekturnStore(context)
        assertEquals(SentenceSpeechProvider.MICROSOFT, restored.settings.value.sentenceSpeechProvider)
        assertNotNull(restored.error.value)
        val repaired = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
            .getString("settings", "").orEmpty()
        assertTrue("The repaired choice is persisted", repaired.contains("\"sentenceSpeechProvider\":\"MICROSOFT\""))
    }

    @Test fun obsoleteSystemSpeechIsIgnoredAndRemovedWithoutReportingInvalidPreferences() {
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK","systemSpeech":7}""")
            .commit()

        val restored = PeekturnStore(context)
        assertTrue(restored.settings.value.quickExplain)
        assertEquals("UK", restored.settings.value.accent)
        assertNull("An obsolete field is not a damaged preference", restored.error.value)
        val persisted = prefs.getString("settings", "").orEmpty()
        assertFalse("The obsolete selection cannot stay dormant in storage", persisted.contains("systemSpeech"))
        assertEquals(restored.settings.value, PeekturnStore(context).settings.value)
    }

    @Test fun storedSystemSpeechSelectionIsRemovedWhileOtherSettingsSurvive() {
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK","sentenceSpeechProvider":"ANDROID","profiles":[{"id":"default","config":{"name":"Mine","endpoint":"https://provider.example/v1","model":"reader"}}],"systemSpeech":{"enginePackage":"com.google.android.tts","voiceName":"en-us-x-sfg#female_1-local","languageTag":"en-US"}}""")
            .commit()

        val restored = PeekturnStore(context)
        assertTrue(restored.settings.value.quickExplain)
        assertEquals("UK", restored.settings.value.accent)
        assertEquals(SentenceSpeechProvider.ANDROID, restored.settings.value.sentenceSpeechProvider)
        assertEquals("Mine", restored.settings.value.model.name)
        assertNull(restored.error.value)
        val persisted = prefs.getString("settings", "").orEmpty()
        assertFalse(persisted.contains("systemSpeech"))
        assertFalse(persisted.contains("com.google.android.tts"))
        assertEquals(restored.settings.value, PeekturnStore(context).settings.value)
    }

    @Test fun obsoleteAndUnusableStoredProfileValuesAreRepairedWithoutLosingTheCredential() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"activeProfileId":"one","profiles":[{"id":"one","config":{"name":"Personal","model":"reader","thinking":true,"temperature":9.5}}]}""")
            .putString("profile_key_cipher:one", "protected:kept-secret")
            .commit()

        val restored = PeekturnStore(context, TestVault())
        assertEquals("one", restored.settings.value.activeProfileId)
        assertEquals(ModelConfig(name = "Personal", model = "reader", apiKey = "kept-secret", temperature = 0.3), restored.settings.value.model)
        assertNotNull(restored.error.value)
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        assertEquals("protected:kept-secret", prefs.getString("profile_key_cipher:one", null))
        val json = prefs.getString("settings", "")!!
        assertTrue("Repaired temperature must be persisted", json.contains("\"temperature\":0.3"))
        assertFalse(json.contains("thinking"))
        assertFalse(json.contains("9.5"))
        assertFalse(json.contains("kept-secret"))
        assertEquals("kept-secret", PeekturnStore(context, TestVault()).settings.value.model.apiKey)
    }

    @Test fun structurallyInvalidSavedModelFallsBackToUsableSettingsInsteadOfCrashingStartup() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"model":null}""").commit()

        val restored = PeekturnStore(context)
        assertEquals(AppSettings(), restored.settings.value)
        assertNotNull(restored.error.value)
    }

    @Test fun legacyConfigurationAndProtectedKeyMigrateOnceWithoutPlaintextInSettings() {
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        prefs.edit().putString("settings", """{"quickExplain":true,"model":{"name":"Personal","endpoint":"https://provider.example/v1","model":"reader","thinking":true}}""")
            .putString("model_key_cipher", "protected:legacy-secret").commit()
        val restored = PeekturnStore(context, TestVault())
        assertEquals("default", restored.settings.value.activeProfileId)
        assertEquals(ModelConfig("Personal", "https://provider.example/v1", "reader", "legacy-secret", 0.3), restored.settings.value.model)
        assertEquals(0.3, restored.settings.value.model.temperature, 0.0)
        assertFalse(prefs.contains("model_key_cipher"))
        assertFalse(prefs.getString("settings", "")!!.contains("legacy-secret"))
        assertFalse(prefs.getString("settings", "")!!.contains("apiKey"))
        assertFalse(prefs.getString("settings", "")!!.contains("thinking"))
        assertEquals(restored.settings.value, PeekturnStore(context, TestVault()).settings.value)
    }

    @Test fun switchingEditingAndDeletingProfilesPreserveIndividualProtectedKeys() = runBlocking {
        val secureStore = PeekturnStore(context, TestVault())
        val first = LlmProfile("first", ModelConfig("First", "https://first.example", "first-model", "first-secret", 0.7))
        val second = LlmProfile("second", ModelConfig("Second", "https://second.example", "second-model", "second-secret", 1.2))
        secureStore.updateSettings { it.copy(profiles = listOf(first, second), activeProfileId = first.id) }
        secureStore.updateSettings { it.copy(activeProfileId = second.id) }
        secureStore.updateSettings { latest -> latest.copy(profiles = latest.profiles.map {
            if (it.id == second.id) it.copy(config = it.config.copy(temperature = 1.9)) else it
        }) }
        val restored = PeekturnStore(context, TestVault())
        assertEquals(second.config.copy(temperature = 1.9), restored.settings.value.model)
        assertEquals(1.9, restored.settings.value.model.temperature, 0.0)
        assertEquals("second-secret", restored.settings.value.model.apiKey)
        restored.updateSettings { it.copy(activeProfileId = first.id) }
        assertEquals(first.config, restored.settings.value.model)
        assertEquals(0.7, restored.settings.value.model.temperature, 0.0)
        assertEquals("first-secret", restored.settings.value.model.apiKey)
        assertEquals(1.9, restored.settings.value.profiles.last().config.temperature, 0.0)
        assertFalse(context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).getString("settings", "")!!.contains("secret"))
        restored.updateSettings { it.copy(profiles = it.profiles.filterNot { profile -> profile.id == first.id }) }
        assertEquals(second.id, restored.settings.value.activeProfileId)
        assertFalse(context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).contains("profile_key_cipher:first"))
        restored.updateSettings { it.copy(profiles = emptyList()) }
        assertEquals(ModelConfig(), restored.settings.value.profiles.single().config)
        assertEquals(restored.settings.value.profiles.single().id, restored.settings.value.activeProfileId)
        assertEquals("", restored.settings.value.model.apiKey)
    }

    @Test fun queuedProfileAndPreferenceTransformsUseLatestSelectionWithoutLosingKeys() = runBlocking {
        val secureStore = PeekturnStore(context, TestVault())
        val first = LlmProfile("first", ModelConfig(apiKey = "first-secret"))
        val second = LlmProfile("second", ModelConfig(apiKey = "second-secret"))
        secureStore.updateSettings { it.copy(profiles = listOf(first, second), activeProfileId = first.id) }
        val updates = kotlinx.coroutines.channels.Channel<(AppSettings) -> AppSettings>(3)
        updates.send { it.copy(activeProfileId = second.id) }
        updates.send { latest -> latest.copy(profiles = latest.profiles.map {
            if (it.id == latest.activeProfileId) it.copy(config = it.config.copy(temperature = 1.2)) else it
        }) }
        updates.send { it.copy(quickExplain = true) }
        repeat(3) { secureStore.updateSettings(updates.receive()) }
        updates.close()
        val restored = PeekturnStore(context, TestVault()).settings.value
        assertEquals(second.id, restored.activeProfileId)
        assertEquals("second-secret", restored.model.apiKey)
        assertEquals(1.2, restored.model.temperature, 0.0)
        assertEquals(0.3, restored.profiles.first().config.temperature, 0.0)
        assertTrue(restored.quickExplain)
        val json = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).getString("settings", "")!!
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("apiKey"))
    }

    @Test fun malformedProfilesRetainValidFieldsAndChooseFirstRemainingProfile() {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().putString("settings",
            """{"quickExplain":true,"activeProfileId":"missing","profiles":[null,{"id":"one","config":{"name":"Personal","endpoint":null,"model":"reader","temperature":1.2}},{"id":"one","config":{"name":"Duplicate"}},{"id":"two","config":{"name":"Backup","temperature":8}}]}"""
        ).commit()
        val restored = PeekturnStore(context, TestVault())
        assertEquals(listOf("one", "two"), restored.settings.value.profiles.map { it.id })
        assertEquals("one", restored.settings.value.activeProfileId)
        assertEquals(ModelConfig(name = "Personal", model = "reader", temperature = 1.2), restored.settings.value.model)
        assertEquals("Backup", restored.settings.value.profiles.last().config.name)
        assertEquals(0.3, restored.settings.value.profiles.last().config.temperature, 0.0)
        assertTrue(restored.settings.value.quickExplain)
        assertNotNull(restored.error.value)
    }

    @Test fun temporarilyUnavailableKeysSurviveAppearanceAndProfileSelectionUpdates() = runBlocking {
        val first = LlmProfile("first", ModelConfig(apiKey = "first-secret"))
        val second = LlmProfile("second", ModelConfig(apiKey = "second-secret"))
        PeekturnStore(context, TestVault()).updateSettings {
            it.copy(profiles = listOf(first, second), activeProfileId = first.id)
        }
        val lockedStore = PeekturnStore(context, object : CredentialVault {
            override fun encrypt(value: String): String = error("Credential vault locked")
            override fun decrypt(value: String): String = error("Credential vault locked")
        })
        assertEquals("", lockedStore.settings.value.model.apiKey)
        assertNotNull(lockedStore.error.value)
        lockedStore.updateSettings { it.copy(appTheme = "warm") }
        lockedStore.updateSettings { it.copy(activeProfileId = second.id) }

        val unlocked = PeekturnStore(context, TestVault()).settings.value
        assertEquals("warm", unlocked.appTheme)
        assertEquals(second.id, unlocked.activeProfileId)
        assertEquals(listOf(first, second), unlocked.profiles)
        assertEquals("second-secret", unlocked.model.apiKey)
    }

    @Test fun deletingLastProfileWhileVaultLockedNeverRestoresItsCredential() = runBlocking {
        PeekturnStore(context, TestVault()).updateSettings {
            it.copy(profiles = listOf(LlmProfile("default", ModelConfig(apiKey = "deleted-secret"))))
        }
        val lockedStore = PeekturnStore(context, object : CredentialVault {
            override fun encrypt(value: String): String = error("Credential vault locked")
            override fun decrypt(value: String): String = error("Credential vault locked")
        })
        assertEquals("", lockedStore.settings.value.model.apiKey)
        lockedStore.updateSettings { latest ->
            latest.copy(profiles = latest.profiles.filterNot { it.id == latest.activeProfileId })
        }
        val restored = PeekturnStore(context, TestVault()).settings.value
        assertEquals(1, restored.profiles.size)
        assertEquals("", restored.model.apiKey)
        assertNotEquals("default", restored.activeProfileId)
        assertFalse(context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).contains("profile_key_cipher:default"))
    }

    @Test fun unusableDraftTemperatureIsStoredAsTheDefaultAndKeepsTheProtectedKey() = runBlocking {
        val secureStore = PeekturnStore(context, TestVault())
        secureStore.updateSettings { latest ->
            latest.copy(profiles = latest.profiles.map {
                it.copy(config = it.config.copy(apiKey = "draft-secret", temperature = Double.NaN))
            })
        }
        val restored = PeekturnStore(context, TestVault()).settings.value
        assertEquals(0.3, restored.model.temperature, 0.0)
        assertEquals("draft-secret", restored.model.apiKey)
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        assertEquals("protected:draft-secret", prefs.getString("profile_key_cipher:default", null))
        val json = prefs.getString("settings", "")!!
        assertFalse(json.contains("draft-secret"))
        assertFalse(json.contains("NaN"))
    }

    @Test fun malformedSettingsStillRecoverIndependentLegacyCredentialAcrossUpdates() = runBlocking {
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", "{malformed-json")
            .putString("model_key_cipher", "protected:legacy-secret").commit()
        val restored = PeekturnStore(context, TestVault())
        assertEquals("legacy-secret", restored.settings.value.model.apiKey)
        assertNotNull(restored.error.value)
        restored.updateSettings { it.copy(appTheme = "warm") }
        val restarted = PeekturnStore(context, TestVault()).settings.value
        assertEquals("warm", restarted.appTheme)
        assertEquals("legacy-secret", restarted.model.apiKey)
    }

    @Test fun retiringReasoningSettingDoesNotReportValidProfilesAsCorrupt() {
        val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
        prefs.edit().putString("settings",
            """{"activeProfileId":"saved","profiles":[{"id":"saved","config":{"name":"Personal","thinking":false}}]}"""
        ).putString("profile_key_cipher:saved", "protected:saved-secret").commit()
        val restored = PeekturnStore(context, TestVault())
        assertEquals("saved-secret", restored.settings.value.model.apiKey)
        assertEquals("Personal", restored.settings.value.model.name)
        assertNull(restored.error.value)
        assertFalse(prefs.getString("settings", "")!!.contains("thinking"))

        prefs.edit().putString("settings",
            """{"profiles":[{"id":"saved","config":{"temperature":9.5}}]}"""
        ).commit()
        assertNotNull(PeekturnStore(context, TestVault()).error.value)
    }

    private class TestVault : CredentialVault {
        override fun encrypt(value: String) = "protected:$value"
        override fun decrypt(value: String): String {
            require(value.startsWith("protected:"))
            return value.removePrefix("protected:")
        }
    }

    private suspend fun addBook(title: String) = store.addBook(
        title, "Author", File.createTempFile("book-", ".epub", context.filesDir).absolutePath, null
    )
}
