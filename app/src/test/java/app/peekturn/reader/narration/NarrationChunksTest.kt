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

    @Test fun prefetchIsBoundedByTwoSentencesAndTheWordTarget() {
        val sentences = listOf(
            "The current sentence.",
            "A second sentence with several words in it.",
            "A third sentence that is also fairly long for a sentence.",
            "A fourth sentence."
        )
        assertEquals(
            listOf("A second sentence with several words in it."),
            nextNarrationSentences(sentences, currentIndex = 0, maxAhead = 2, wordTarget = 8)
        )
        assertEquals(
            listOf("A second sentence with several words in it.", "A third sentence that is also fairly long for a sentence."),
            nextNarrationSentences(sentences, currentIndex = 0, maxAhead = 2, wordTarget = 50)
        )
    }

    @Test fun theFirstFollowingSentenceIsAlwaysIncludedEvenWhenItIsLong() {
        val long = "word ".repeat(80).trim() + "."
        assertEquals(
            listOf(long),
            nextNarrationSentences(listOf("Current.", long, "After."), currentIndex = 0, maxAhead = 2, wordTarget = 50)
        )
    }

    @Test fun thereIsNothingToPrefetchAfterTheLastSentence() {
        assertEquals(emptyList<String>(), nextNarrationSentences(listOf("Only."), currentIndex = 0))
        assertEquals(emptyList<String>(), nextNarrationSentences(emptyList(), currentIndex = 0))
    }
}
