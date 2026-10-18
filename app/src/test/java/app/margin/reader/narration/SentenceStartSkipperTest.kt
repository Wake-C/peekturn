package app.margin.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Test

/** The start skipper moves Readium's first utterance to the visible sentence without losing later text. */
class SentenceStartSkipperTest {

    private fun tokenizer(skipper: SentenceStartSkipper) = SentenceTextTokenizer(null, skipper)

    @Test fun leadingSentencesAreDroppedUntilTheVisibleSentence() {
        val skipper = SentenceStartSkipper("Today is a perfect day.")
        val text = "Yesterday was ordinary. Today is a perfect day."
        val tokenizer = tokenizer(skipper)

        assertEquals(
            listOf("Today is a perfect day."),
            tokenizer.tokenize(text).map { text.substring(it) }
        )
        val next = " Tomorrow is better."
        assertEquals(
            listOf("Tomorrow is better."),
            tokenizer.tokenize(next).map { next.substring(it) }
        )
    }

    @Test fun aTargetAtTheStartKeepsEverySentence() {
        val skipper = SentenceStartSkipper("Today is a perfect day.")
        val text = "Today is a perfect day. Tomorrow is better."
        assertEquals(
            listOf("Today is a perfect day.", "Tomorrow is better."),
            tokenizer(skipper).tokenize(text).map { text.substring(it) }
        )
    }

    @Test fun aMismatchingTargetFallsBackInsteadOfSwallowingTheBlock() {
        val skipper = SentenceStartSkipper("Not in this text.")
        val text = "First sentence. Second sentence."
        val tokenizer = tokenizer(skipper)
        repeat(8) {
            assertEquals(emptyList<IntRange>(), tokenizer.tokenize(text))
        }
        assertEquals(
            listOf("First sentence.", "Second sentence."),
            tokenizer.tokenize(text).map { text.substring(it) }
        )
    }

    @Test fun resetRearmsTheSameSkipperForANewVisibleSentence() {
        val skipper = SentenceStartSkipper("Tomorrow is better.")
        val first = "Yesterday was ordinary. Today is a perfect day. Tomorrow is better."
        assertEquals(
            listOf("Tomorrow is better."),
            tokenizer(skipper).tokenize(first).map { first.substring(it) }
        )
        skipper.reset("Yesterday was ordinary.")
        assertEquals(
            listOf("Yesterday was ordinary.", "Today is a perfect day.", "Tomorrow is better."),
            tokenizer(skipper).tokenize(first).map { first.substring(it) }
        )
    }
}
