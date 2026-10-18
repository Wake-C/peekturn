package app.margin.reader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.margin.reader.model.ReaderPreferences
import app.margin.reader.reader.readerFontFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderTypographyPreferencesTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("margin", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("margin.db")
    }

    @Test fun `bundled font and letter spacing survive a restart`() = runBlocking {
        val store = MarginStore(context)
        store.updateSettings { latest ->
            latest.copy(reading = latest.reading.copy(fontFamily = "atkinson-hyperlegible", letterSpacing = 0.12))
        }

        val restored = MarginStore(context).settings.value.reading

        assertEquals("atkinson-hyperlegible", restored.fontFamily)
        assertEquals(0.12, restored.letterSpacing, 0.0001)
        assertEquals("Atkinson Hyperlegible", readerFontFor(restored.fontFamily).familyName)
    }

    @Test fun `unusable letter spacing is repaired without discarding the rest of typography`() {
        fun restore(reading: String): ReaderPreferences {
            context.getSharedPreferences("margin", Context.MODE_PRIVATE).edit()
                .putString("settings", """{"reading":$reading}""")
                .commit()
            return MarginStore(context).settings.value.reading
        }

        val high = restore("""{"fontFamily":"noto-serif","fontSize":150,"letterSpacing":2.5}""")
        assertEquals("noto-serif", high.fontFamily)
        assertEquals(150.0, high.fontSize, 0.0)
        assertEquals(1.0, high.letterSpacing, 0.0001)

        val negative = restore("""{"fontFamily":"source-serif-4","letterSpacing":-0.4}""")
        assertEquals("source-serif-4", negative.fontFamily)
        assertEquals(0.0, negative.letterSpacing, 0.0001)

        val notANumber = restore("""{"fontFamily":"source-sans-3","letterSpacing":"wide"}""")
        assertEquals("source-sans-3", notANumber.fontFamily)
        assertEquals(0.0, notANumber.letterSpacing, 0.0001)
    }

    @Test fun `a retired stored font keeps reading through the bundled default`() {
        context.getSharedPreferences("margin", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"reading":{"fontFamily":"retired-serif","fontSize":128}}""")
            .commit()

        val restored = MarginStore(context).settings.value.reading

        assertEquals(128.0, restored.fontSize, 0.0)
        assertEquals("retired-serif", restored.fontFamily)
        assertEquals("Literata", readerFontFor(restored.fontFamily).familyName)
    }
}
