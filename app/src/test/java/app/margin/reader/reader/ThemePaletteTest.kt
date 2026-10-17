@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.margin.reader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.margin.reader.model.ReaderPreferences
import app.margin.reader.ui.marginColorScheme
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.robolectric.RobolectricTestRunner

/** The schemes are a contract: any theme added here must stay readable and reach the reader page. */
@RunWith(RobolectricTestRunner::class)
class ThemePaletteTest {
    private val themes = listOf("dark", "light", "warm", "oled", "sage")

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test fun `body text clears 4_5 to 1 on background and surface in every theme`() {
        themes.forEach { theme ->
            val scheme = marginColorScheme(theme)
            assertTrue("$theme onBackground", contrast(scheme.onBackground, scheme.background) >= 4.5)
            assertTrue("$theme onSurface", contrast(scheme.onSurface, scheme.surface) >= 4.5)
        }
    }

    @Test fun `muted text still carries meaning in every theme`() {
        themes.forEach { theme ->
            val scheme = marginColorScheme(theme)
            assertTrue("$theme variant on surface", contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5)
            assertTrue("$theme variant on variant", contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5)
        }
    }

    @Test fun `whole themes keep accent, container and error roles`() {
        themes.forEach { theme ->
            val scheme = marginColorScheme(theme)
            assertTrue("$theme primary container", contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5)
            assertTrue("$theme secondary container", contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5)
            assertTrue("$theme error", contrast(scheme.error, scheme.background) >= 4.5)
        }
    }

    @Test fun `midnight is a true black canvas with stepping surfaces`() {
        val midnight = marginColorScheme("oled")
        assertEquals(Color(0xFF000000), midnight.background)
        assertTrue(midnight.surface.luminance() > 0f)
        assertTrue(midnight.surfaceContainerHigh.luminance() > midnight.surfaceContainer.luminance())
        assertTrue(midnight.surfaceContainer.luminance() > midnight.background.luminance())
    }

    @Test fun `reader paper and ink use the scheme instead of a per-theme list`() {
        themes.forEach { theme ->
            val scheme = marginColorScheme(theme)
            assertEquals(scheme.background, readerPaper(theme))
            val epub = ReaderPreferences().toEpub(theme)
            assertEquals(ReadiumColor(scheme.background.toArgb()), epub.backgroundColor)
            assertEquals(ReadiumColor(scheme.onBackground.toArgb()), epub.textColor)
        }
    }
}
