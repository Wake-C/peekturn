package app.peekturn.reader.reader

import app.peekturn.reader.model.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class ReaderTypographyPresetsTest {
    @Test fun fontSizeHasExactlySmallMediumLargeAndExtraLarge() {
        val preset = ReaderTypographyPreset.FONT_SIZE
        assertEquals(4, preset.count)
        assertEquals(listOf(90.0, 100.0, 120.0, 150.0), (0 until preset.count).map {
            preset.apply(ReaderPreferences(), it).fontSize
        })
        assertEquals(1, preset.selected(ReaderPreferences()))
        assertNull(preset.selected(ReaderPreferences(fontSize = 180.0)))
    }

    @Test fun everyPresetRoundTripsAndOnlyChangesItsOwnFields() {
        val original = ReaderPreferences(fontFamily = "literata", fontSize = 117.3, lineHeight = 1.73,
            pageMargins = 1.27, paragraphSpacing = 0.91, letterSpacing = 0.037,
            topPaddingDp = 43.0, bottomPaddingDp = 67.0, scroll = true, immersive = false)
        for (preset in ReaderTypographyPreset.entries) {
            for (index in 0 until preset.count) {
                val updated = preset.apply(original, index)
                assertEquals(index, preset.selected(updated))
                val restored = when (preset) {
                    ReaderTypographyPreset.FONT_SIZE -> updated.copy(fontSize = original.fontSize)
                    ReaderTypographyPreset.LINE_HEIGHT -> updated.copy(lineHeight = original.lineHeight)
                    ReaderTypographyPreset.SIDE_MARGINS -> updated.copy(pageMargins = original.pageMargins)
                    ReaderTypographyPreset.PARAGRAPH_SPACING -> updated.copy(paragraphSpacing = original.paragraphSpacing)
                    ReaderTypographyPreset.LETTER_SPACING -> updated.copy(letterSpacing = original.letterSpacing)
                    ReaderTypographyPreset.VERTICAL_PADDING -> updated.copy(topPaddingDp = original.topPaddingDp, bottomPaddingDp = original.bottomPaddingDp)
                }
                assertEquals(original, restored)
            }
        }
    }

    @Test fun inspectingCustomValuesDoesNotSnapThemToNearestPreset() {
        val custom = ReaderPreferences(fontSize = 117.3, lineHeight = 1.73, pageMargins = 1.27,
            paragraphSpacing = .91, letterSpacing = .037, topPaddingDp = 43.0, bottomPaddingDp = 67.0)
        ReaderTypographyPreset.entries.forEach { assertNull(it.selected(custom)) }
        assertEquals(117.3, custom.fontSize, 0.0)
        assertNull(ReaderTypographyPreset.VERTICAL_PADDING.selected(custom.copy(topPaddingDp = 16.0)))
    }

    @Test fun oldFloatRoundingNoiseIsNotShownAsACustomSetting() {
        assertEquals(1, ReaderTypographyPreset.LINE_HEIGHT.selected(ReaderPreferences(lineHeight = 1.6f.toDouble())))
        assertEquals(1, ReaderTypographyPreset.LETTER_SPACING.selected(ReaderPreferences(letterSpacing = .05f.toDouble())))
    }

    @Test fun verticalSpacingPreservesMinimumViewportInsets() {
        val field = ReaderTypographyPreset.VERTICAL_PADDING
        for (index in 0 until field.count) {
            val prefs = field.apply(ReaderPreferences(), index)
            assertTrue(prefs.topPaddingDp >= 16)
            assertTrue(prefs.bottomPaddingDp >= 24)
            assertEquals(prefs.topPaddingDp, readerTextInsets(prefs).topDp, 0.0)
            assertEquals(prefs.bottomPaddingDp, readerTextInsets(prefs).bottomDp, 0.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownPresetIndexIsNotClampedOrSilentlyApplied() {
        ReaderTypographyPreset.FONT_SIZE.apply(ReaderPreferences(), 4)
    }
}
