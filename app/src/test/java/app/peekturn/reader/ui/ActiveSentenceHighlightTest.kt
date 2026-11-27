package app.peekturn.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.peekturn.reader.reader.NARRATION_HIGHLIGHT_ALPHA
import app.peekturn.reader.reader.NARRATION_HIGHLIGHT_TINT
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActiveSentenceHighlightTest {
    private val themes = listOf("light", "warm", "sage", "dark", "oled")

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test fun `active sentence uses a visible translucent accent in every theme`() {
        themes.forEach { theme ->
            val colors = peekturnColorScheme(theme)
            val fill = colors.activeSentenceBackground
            assertEquals(theme, colors.primaryContainer, fill.copy(alpha = 1f))
            val rendered = fill.compositeOver(colors.background)
            val activeContrast = contrast(rendered, colors.background)
            assertTrue(theme, activeContrast > 1.0 && activeContrast < contrast(colors.primaryContainer, colors.background))
        }
    }

    @Test fun `spoken text and current timestamp stay readable`() {
        themes.forEach { theme ->
            val colors = peekturnColorScheme(theme)
            val rendered = colors.activeSentenceBackground.compositeOver(colors.background)
            assertTrue("$theme reading", contrast(colors.onBackground, rendered) >= 4.5)
            assertTrue("$theme transcript", contrast(colors.onSurface, rendered) >= 4.5)
        }
    }

    @Test fun `word selection retains its separate accent fill`() {
        themes.forEach { theme ->
            val colors = peekturnColorScheme(theme)
            assertNotEquals(theme, colors.primaryContainer, colors.activeSentenceBackground.compositeOver(colors.background))
        }
    }

    @Test fun `stronger book narration highlight preserves text contrast in every theme`() {
        themes.forEach { theme ->
            val colors = peekturnColorScheme(theme)
            val highlighted = Color(NARRATION_HIGHLIGHT_TINT)
                .copy(alpha = NARRATION_HIGHLIGHT_ALPHA.toFloat()).compositeOver(colors.background)
            assertTrue(theme, contrast(colors.onBackground, highlighted) >= 4.5)
        }
    }
}
