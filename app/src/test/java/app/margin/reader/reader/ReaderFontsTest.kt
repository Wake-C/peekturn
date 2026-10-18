package app.margin.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontsTest {
    @Test fun `explicit bundled and legacy choices remain available`() {
        assertEquals("Literata", readerFontFor("serif").familyName)
        assertEquals("Source Sans 3", readerFontFor("sans-serif").familyName)
        assertEquals("Literata", readerFontFor("literata").familyName)
        assertEquals("Source Serif 4", readerFontFor("source-serif-4").familyName)
        assertEquals("Noto Serif", readerFontFor("noto-serif").familyName)
        assertEquals("Atkinson Hyperlegible", readerFontFor("atkinson-hyperlegible").familyName)
        assertEquals("Source Sans 3", readerFontFor("source-sans-3").familyName)
    }

    @Test fun `system choice delegates to platform without registering bundled faces`() {
        val font = readerFontFor("system")
        assertEquals("system-ui, sans-serif", font.cssStack)
        assertEquals(emptyList<ReaderFontFace>(), font.faces)
    }
}
