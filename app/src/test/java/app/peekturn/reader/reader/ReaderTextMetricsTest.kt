package app.peekturn.reader.reader

import app.peekturn.reader.model.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class ReaderTextMetricsTest {
    @Test fun nativeBodyUsesTheReadiumRootAndSavedRelativeSpacing() {
        val metrics = readerTextMetrics(ReaderPreferences(), 393f)
        assertEquals(16f, metrics.fontSizeSp, 0.0001f)
        assertEquals(25.6f, metrics.lineHeightSp, 0.0001f)
        assertEquals(11.2f, metrics.paragraphGapSp, 0.0001f)
        assertEquals(20f, metrics.sidePaddingDp, 0.0001f)
        assertEquals(640f, metrics.maximumWidthSp, 0.0001f)
        assertEquals(16f, metrics.topPaddingDp, 0.0001f)
        assertEquals(24f, metrics.bottomPaddingDp, 0.0001f)
    }

    @Test fun everySizePresetUsesTheSameBookMultiplier() {
        val values = listOf(14.4f, 16f, 19.2f, 24f)
        values.forEachIndexed { index, expected ->
            val preference = ReaderTypographyPreset.FONT_SIZE.apply(ReaderPreferences(), index)
            assertEquals(expected, readerTextMetrics(preference, 320f).fontSizeSp, 0.0001f)
        }
    }

    @Test fun legacyCustomValuesAreNotRoundedToPresets() {
        val preferences = ReaderPreferences(fontSize = 113.7, lineHeight = 1.73, paragraphSpacing = 0.91,
            letterSpacing = 0.037, pageMargins = 1.27, topPaddingDp = 43.0, bottomPaddingDp = 67.0)
        val m = readerTextMetrics(preferences, 393f)
        assertEquals(18.192f, m.fontSizeSp, 0.0001f)
        assertEquals(m.fontSizeSp * 1.73f, m.lineHeightSp, 0.0001f)
        assertEquals(m.fontSizeSp * 0.91f, m.paragraphGapSp, 0.0001f)
        assertEquals(0.037f, m.letterSpacingEm, 0.0001f)
        assertEquals(25.4f, m.sidePaddingDp, 0.0001f)
        assertEquals(43f, m.topPaddingDp, 0.0001f)
        assertEquals(67f, m.bottomPaddingDp, 0.0001f)
    }

    @Test fun guttersTrackTheShippedCssBreakpointsAndMarginMultiplier() {
        val preferences = ReaderPreferences(pageMargins = 1.5)
        listOf(320f to 30f, 559f to 30f, 560f to 45f, 720f to 60f, 1200f to 75f).forEach { (width, expected) ->
            assertEquals(expected, readerTextMetrics(preferences, width).sidePaddingDp, 0f)
        }
    }

    @Test fun zeroSpacingAndOldFontAliasesStayValid() {
        val m = readerTextMetrics(ReaderPreferences(paragraphSpacing = 0.0, letterSpacing = 0.0), 320f)
        assertEquals(0f, m.paragraphGapSp, 0f)
        assertEquals(0f, m.letterSpacingEm, 0f)
        assertEquals("literata", readerFontFor("serif").id)
        assertEquals("source-sans-3", readerFontFor("sans-serif").id)
        ReaderFonts.all.filter { it.faces.isNotEmpty() }.forEach { font ->
            assertTrue(font.faces.any { !it.italic && 400 in it.weights })
        }
    }
}
