@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.margin.reader.reader

import app.margin.reader.model.ReaderPreferences
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
}
