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
}
