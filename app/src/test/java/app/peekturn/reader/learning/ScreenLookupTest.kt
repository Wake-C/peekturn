package app.peekturn.reader.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenLookupTest {
    @Test fun releasePointChoosesTheSecondOccurrenceNotTheFirst() {
        val line = ScreenLine("The bank is near the bank.", ScreenBox(10, 30, 290, 55),
            listOf(ScreenWord("bank", ScreenBox(48, 30, 87, 55), 4, 8),
                ScreenWord("bank", ScreenBox(225, 30, 264, 55), 21, 25)), 0)
        val hit = screenLookup(listOf(line), 242f, 42f, 8f)!!
        assertEquals("bank", hit.selection)
        assertEquals("The bank is near the", hit.before)
        assertEquals(".", hit.after)
    }

    @Test fun nearbyLinesStayInTheSameColumnAndBlock() {
        val lines = listOf(
            ScreenLine("The river was wide", ScreenBox(10, 12, 195, 32), emptyList(), 0),
            ScreenLine("A bank stood beside it", ScreenBox(10, 34, 195, 54),
                listOf(ScreenWord("bank", ScreenBox(28, 34, 67, 54), 2, 6)), 0),
            ScreenLine("I walked past", ScreenBox(230, 55, 390, 75), emptyList(), 0),
            ScreenLine("More text", ScreenBox(10, 78, 195, 98), emptyList(), 1))
        val hit = screenLookup(lines, 40f, 42f, 8f)!!
        assertEquals("The river was wide A", hit.before)
        assertEquals("stood beside it", hit.after)
    }

    @Test fun pointOutsideWordsDoesNotGuessFromRepeatedText() {
        val word = ScreenWord("bank", ScreenBox(10, 10, 45, 30), 0, 4)
        val line = ScreenLine("bank", ScreenBox(10, 10, 45, 30), listOf(word), 0)
        assertNull(screenLookup(listOf(line), 170f, 200f, 10f))
        assertEquals("bank", screenLookup(listOf(line), 50f, 20f, 6f)?.selection)
    }
}
