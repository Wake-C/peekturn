package app.margin.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Readium hands back the surrounding text it can reach, without separators at block boundaries, so
 * the window has to be shaped into readable language before it is sent and echoed.
 */
class LookupContextWindowTest {
    @Test fun `a heading glued to its paragraph is cut back to the sentence around the selection`() {
        val glued = "Chapter one. The opening pages describe how the bookChapter 1, passage 1. She was "
        assertEquals("She was", contextBeforeSelection(glued))
    }

    @Test fun `the following side stops at the end of its sentence`() {
        val raw = " to leave the familiar streets behind. A new city meant new people and new routines."
        assertEquals("to leave the familiar streets behind.", contextAfterSelection(raw))
    }

    @Test fun `text without a sentence edge is only collapsed`() {
        assertEquals("a quiet window of text", contextBeforeSelection("  a quiet\n window   of text "))
        assertEquals("a quiet window of text", contextAfterSelection("a quiet\twindow of text "))
    }

    @Test fun `a single trailing word does not collapse the window to nothing`() {
        // The last sentence holds only one word, so the fuller window is kept instead.
        assertEquals("Earlier sentence here. was", contextBeforeSelection("Earlier sentence here. was"))
    }

    @Test fun `decimals and initials are not sentence edges`() {
        assertEquals("measured at 3.5 units", contextAfterSelection("measured at 3.5 units"))
        assertEquals("the U.S. market", contextAfterSelection("the U.S. market"))
    }

    @Test fun `an empty window stays empty`() {
        assertEquals("", contextBeforeSelection(""))
        assertEquals("", contextAfterSelection("   "))
    }

    /** EPUBs put non-breaking and other Unicode spaces between blocks; they must not defeat shaping. */
    @Test fun `unicode spaces are separators like any other`() {
        assertEquals("She was", contextBeforeSelection("the book\u00a0Chapter 1, passage 1. She was\u2003"))
        assertEquals("to leave the familiar streets behind.", contextAfterSelection("\u00a0to leave the familiar streets behind. A new city"))
    }
}
