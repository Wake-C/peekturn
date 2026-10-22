package app.peekturn.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sentence stays one cache/play unit even when the speech service can only voice a bounded chunk.
 * Splitting happens between words, is bounded, and refuses rather than silently truncating.
 */
class NarrationChunksTest {

    @Test fun aShortSentenceIsOneUnchangedChunk() {
        assertEquals(listOf("Today is a perfect day."), narrationChunks("Today is a perfect day."))
    }

    @Test fun aLongSentenceIsSplitBetweenWordsAndKeepsEveryWord() {
        val sentence = (1..80).joinToString(" ") { "word$it" } + "."
        val chunks = requireNotNull(narrationChunks(sentence))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= NARRATION_CHUNK_CHARS })
        assertEquals(sentence, chunks.joinToString(" "))
    }

    @Test fun aSingleUnbrokenRunIsSplitAtTheHardBoundWithoutLosingCharacters() {
        val sentence = "a".repeat(NARRATION_CHUNK_CHARS * 2 + 7)
        val chunks = requireNotNull(narrationChunks(sentence))
        assertEquals(sentence, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= NARRATION_CHUNK_CHARS })
    }

    @Test fun punctuationSurvivesAtTheEndOfTheLastChunk() {
        val sentence = "word ".repeat(70).trim() + "!"
        val chunks = requireNotNull(narrationChunks(sentence))
        assertTrue(chunks.joinToString(" ").endsWith("!"))
    }

    @Test fun aSentencePastTheHardCapIsRefusedInsteadOfTruncated() {
        val sentence = ("word ".repeat(NARRATION_MAX_CHUNKS * 40)).trim() + "."
        assertNull(narrationChunks(sentence))
    }
}
