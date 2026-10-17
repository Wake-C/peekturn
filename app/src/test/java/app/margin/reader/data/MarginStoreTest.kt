package app.margin.reader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.margin.reader.model.AppSettings
import app.margin.reader.model.BookReadingTime
import app.margin.reader.model.ModelConfig
import app.margin.reader.model.ReaderPreferences
import app.margin.reader.model.ReadingDay
import app.margin.reader.model.ReadingStats
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
class MarginStoreTest {
    private lateinit var context: Context
    private lateinit var store: MarginStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("margin", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("margin.db")
        store = MarginStore(context)
    }

    @Test fun latestCompletedLocatorWriteSurvivesStoreRecreationWithoutBecomingLastOpenedBook() = runBlocking {
        val book = addBook("Reading")
        val first = """{"href":"chapter-1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.25}}"""
        val latest = """{"href":"chapter-2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.75},"text":{"highlight":"same word, different occurrence"}}"""
        store.savePosition(book.id, first, 0.25)
        store.savePosition(book.id, latest, 0.75)

        val restored = MarginStore(context)
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

        val restored = MarginStore(context)
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

        val restored = MarginStore(context)
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

        val restored = MarginStore(context)
        assertNull(restored.lastBookId())
        assertEquals("saved locator", restored.findBook(book.id)?.locatorJson)
        assertEquals(settings, restored.settings.value)
    }

    @Test fun preferencesAndModelConfigurationSurviveWithoutAnApiKey() = runBlocking {
        val settings = AppSettings(
            quickExplain = true, autoPronounce = true, accent = "UK",
            model = ModelConfig(name = "Local provider", endpoint = "http://127.0.0.1:8080/v1", model = "reader-model", apiKey = "", thinking = true),
            reading = ReaderPreferences(fontSize = 130.0, fontFamily = "sans-serif", lineHeight = 1.8, pageMargins = 1.2, paragraphSpacing = 0.9, scroll = true, theme = "warm")
        )
        store.updateSettings { settings }
        assertEquals(settings, MarginStore(context).settings.value)
    }

    @Test fun queuedDifferentFieldUpdatesPreserveBothPreferencesAfterRestart() = runBlocking {
        val updates = kotlinx.coroutines.channels.Channel<(AppSettings) -> AppSettings>(capacity = 2)
        updates.send { latest -> latest.copy(quickExplain = true) }
        updates.send { latest -> latest.copy(autoPronounce = true) }
        repeat(2) { store.updateSettings(updates.receive()) }
        updates.close()

        val restored = MarginStore(context).settings.value
        assertTrue(restored.quickExplain)
        assertTrue(restored.autoPronounce)
    }

    @Test fun unavailableProtectedKeyDoesNotDiscardPreferencesOrExposeCiphertextAsApiKey() {
        val prefs = context.getSharedPreferences("margin", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK","model":{"name":"My provider","model":"my-model","apiKey":""}}""")
            .putString("model_key_cipher", "AA==")
            .commit()
        val restored = MarginStore(context)
        assertTrue(restored.settings.value.quickExplain)
        assertEquals("UK", restored.settings.value.accent)
        assertEquals("my-model", restored.settings.value.model.model)
        assertEquals("", restored.settings.value.model.apiKey)
        assertNotNull(restored.error.value)
    }

    @Test fun malformedPreferenceFieldsKeepValidChoicesAndRestoreOnlyInvalidDefaults() {
        context.getSharedPreferences("margin", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK","model":{"name":"My provider","endpoint":null,"thinking":true},"reading":{"fontSize":140,"fontFamily":null},"autoPronounce":"wrong type"}""")
            .commit()

        val restored = MarginStore(context)
        assertTrue(restored.settings.value.quickExplain)
        assertFalse(restored.settings.value.autoPronounce)
        assertEquals("UK", restored.settings.value.accent)
        assertEquals(ModelConfig(name = "My provider", thinking = true), restored.settings.value.model)
        assertEquals(ReaderPreferences(fontSize = 140.0), restored.settings.value.reading)
        assertNotNull(restored.error.value)
    }

    @Test fun structurallyInvalidSavedModelFallsBackToUsableSettingsInsteadOfCrashingStartup() {
        context.getSharedPreferences("margin", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"model":null}""").commit()

        val restored = MarginStore(context)
        assertEquals(AppSettings(), restored.settings.value)
        assertNotNull(restored.error.value)
    }

    private suspend fun addBook(title: String) = store.addBook(
        title, "Author", File.createTempFile("book-", ".epub", context.filesDir).absolutePath, null
    )
}
