package app.margin.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Result rows show the publication's own words and boundaries, never a trimmed or re-spaced copy. */
class BookSearchRowTest {
    @Test fun `the displayed row keeps source boundary whitespace and a suffix glued to the match`() {
        val row = bookSearchRow("Chapter 1", "known as ", "seer", "s, and they were held in fear")
        assertEquals("known as seers, and they were held in fear", row.before + row.match + row.after)
    }

    @Test fun `context words stay separated and an untitled locator gets no label`() {
        val row = bookSearchRow(null, "the old man ", "bank", " beside the river")
        assertEquals("the old man bank beside the river", row.before + row.match + row.after)
        assertNull(row.chapter)
    }

    @Test fun `windowed context keeps its boundary space next to the match`() {
        val before = "The book says that a bank can mean a place beside a river and here the quiet river makes that meaning clear "
        val after = " and the boy kept reading about the desert and the omens of the caravan"
        val row = bookSearchRow("Chapter 1", before, "bank", after)
        assertTrue(row.before.startsWith("…"))
        assertFalse(row.before.contains("The book"))
        assertTrue(row.before.endsWith(" "))
        assertTrue(row.after.startsWith(" "))
        assertFalse(row.after.contains("caravan"))
        assertTrue(row.after.endsWith("…"))
        assertEquals("bank", row.match)
        assertFalse((row.before + row.match + row.after).contains("  "))
    }

    @Test fun `reflowed whitespace becomes single spaces without fusing words`() {
        val row = bookSearchRow("Chapter\n1,  passage 10", "they\n  were\tused ", "to", " omens.\n ")
        assertEquals("Chapter 1, passage 10", row.chapter)
        assertEquals("they were used to omens.", row.before + row.match + row.after)
    }

    @Test fun `absent context stays empty and an overlong match is bounded`() {
        val edges = bookSearchRow("Chapter 1", " \n ", "bank", " ")
        assertEquals("", edges.before)
        assertEquals("", edges.after)
        assertEquals("bank", edges.before + edges.match + edges.after)
        val long = bookSearchRow("Chapter 1", "", "q".repeat(400), "")
        assertEquals(201, long.match.length)
        assertTrue(long.match.endsWith("…"))
    }

    @Test fun `a sentence closing inside the budget ends the snippet before the next fragment`() {
        val row = bookSearchRow("Opening the book", "She was ",
            "reluctant", " to leave the familiar streets behind. A new city meant new people and new routines.")
        assertEquals(" to leave the familiar streets behind.", row.after)
        assertFalse(row.after.endsWith("…"))
    }

    @Test fun `a snippet cut inside a sentence still marks the dropped words`() {
        val row = bookSearchRow("Opening the book", "The old man ",
            "kept", "s, and they were held in fear of the long night that never ended")
        assertEquals("s, and they were held in fear of the long…", row.after)
    }

    @Test fun `the same chapter labels a run of results once`() {
        assertTrue(chapterRunStart(null, "Opening the book"))
        assertTrue(chapterRunStart("Opening the book", "A different street"))
        assertFalse(chapterRunStart("A different street", "A different street"))
        assertFalse(chapterRunStart("A different street", null))
        assertEquals("Opening the book", chapterLabel("  Opening\n the book "))
        assertNull(chapterLabel("  "))
    }
}
