@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.peekturn.reader.reader

import app.peekturn.reader.model.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderPreferencesTest {
    @Test fun `stored percentage becomes Readium multiplier without mutating persistence`() {
        val preferences = ReaderPreferences(fontSize = 180.0)
        assertEquals(1.8, preferences.toEpub("dark").fontSize!!, 0.0001)
        assertEquals(180.0, preferences.fontSize, 0.0)
        assertEquals(1.0, ReaderPreferences().toEpub("light").fontSize!!, 0.0001)
    }

    @Test fun `all four font presets reach Readium without changing the selected family`() {
        val initial = ReaderPreferences(fontFamily = "literata")
        val field = ReaderTypographyPreset.FONT_SIZE
        val multipliers = (0 until field.count).map { index ->
            val preferences = field.apply(initial, index)
            val epub = preferences.toEpub("warm")
            assertEquals("Literata", epub.fontFamily!!.name)
            epub.fontSize!!
        }
        assertEquals(listOf(0.9, 1.0, 1.2, 1.5), multipliers)
    }

    @Test fun `top and bottom text distances are independent and leave passive labels clear`() {
        assertEquals(ReaderTextInsets(16.0, 24.0), readerTextInsets(ReaderPreferences()))
        val custom = ReaderPreferences(topPaddingDp = 80.0, bottomPaddingDp = 100.0)
        assertEquals(ReaderTextInsets(80.0, 100.0), readerTextInsets(custom))
        assertEquals(ReaderTextInsets(16.0, 100.0), readerTextInsets(custom.copy(topPaddingDp = 16.0)))
        assertEquals(ReaderTextInsets(16.0, 24.0),
            readerTextInsets(ReaderPreferences(topPaddingDp = 0.0, bottomPaddingDp = -10.0)))
    }

    @Test fun `chapter pages use actual callback page numbers and scroll never invents pages`() {
        assertEquals("3 / 12", chapterPositionLabel(false, 3, 12, 0.4))
        assertEquals("Chapter 40%", chapterPositionLabel(true, 3, 12, 0.4))
        assertEquals("", chapterPositionLabel(false, 0, 0, null))
        assertEquals("Chapter", chapterPositionLabel(true, 0, 0, null))
    }

    @Test fun `book page counter counts the text before the chapter plus the exact page`() {
        // 1,000 characters per page, 100 pages of book: the first page of the first chapter is 1.
        assertEquals(1 to 100, bookPageCounter(charsBefore = 0, page = 1, totalChars = 100_000, charsPerPage = 1_000.0))
        // Text before this chapter is converted at the same density, then the in-chapter page is added.
        assertEquals(53 to 100, bookPageCounter(charsBefore = 50_000, page = 3, totalChars = 100_000, charsPerPage = 1_000.0))
        // The total can never fall behind a page the reader is actually on.
        assertEquals(103 to 103, bookPageCounter(charsBefore = 99_500, page = 3, totalChars = 100_000, charsPerPage = 1_000.0))
        // Unusable measurements produce no counter rather than a fabricated page.
        assertNull(bookPageCounter(charsBefore = 0, page = 0, totalChars = 100_000, charsPerPage = 1_000.0))
        assertNull(bookPageCounter(charsBefore = 0, page = 1, totalChars = 0, charsPerPage = 1_000.0))
        assertNull(bookPageCounter(charsBefore = 0, page = 1, totalChars = 100_000, charsPerPage = 0.0))
    }

    @Test fun `text characters skip markup, scripts and whitespace`() {
        val html = "<html><head><style>p { color: red }</style></head><body><p>Hello  world</p><!-- note --></body></html>"
        assertEquals(10, textCharacters(html.toByteArray()))
    }

    @Test fun `bundled font ids and legacy generic choices reach Readium as real families`() {
        assertEquals("Literata", ReaderPreferences(fontFamily = "serif").toEpub("dark").fontFamily!!.name)
        assertEquals("Source Sans 3", ReaderPreferences(fontFamily = "sans-serif").toEpub("dark").fontFamily!!.name)
        assertEquals("Source Serif 4", ReaderPreferences(fontFamily = "source-serif-4").toEpub("dark").fontFamily!!.name)
        assertEquals("Noto Serif", ReaderPreferences(fontFamily = "noto-serif").toEpub("dark").fontFamily!!.name)
        assertEquals("Atkinson Hyperlegible", ReaderPreferences(fontFamily = "atkinson-hyperlegible").toEpub("dark").fontFamily!!.name)
        assertEquals("Source Sans 3", ReaderPreferences(fontFamily = "source-sans-3").toEpub("dark").fontFamily!!.name)
    }


    @Test fun `layout signature tracks every typography input and collapses identical legacy fonts`() {
        val base = ReaderPreferences(fontFamily = "literata")
        val signature = readerLayoutSignature(base, width = 800, height = 1200)
        assertEquals(signature, readerLayoutSignature(base.copy(), 800, 1200))
        // The pre-bundled "serif" value and its replacement draw the same page, so they must not
        // create parallel page-density entries.
        assertEquals(signature, readerLayoutSignature(base.copy(fontFamily = "serif"), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(letterSpacing = 0.1), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(fontFamily = "noto-serif"), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(fontSize = 140.0), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(lineHeight = 1.9), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(paragraphSpacing = 1.2), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(pageMargins = 1.4), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(topPaddingDp = 40.0), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base.copy(bottomPaddingDp = 60.0), 800, 1200))
        assertNotEquals(signature, readerLayoutSignature(base, 801, 1200))
        assertNotEquals(signature, readerLayoutSignature(base, 800, 1201))
    }
}
