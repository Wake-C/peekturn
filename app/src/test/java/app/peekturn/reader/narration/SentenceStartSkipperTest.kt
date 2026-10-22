package app.peekturn.reader.narration

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
    @Test fun precedingBlocksRemainAvailableToPreviousBeforeTheSelectedParagraph() {
        val skipper = SentenceStartSkipper("The selected paragraph starts here.")
        val tokenizer = tokenizer(skipper)
        val precedingBlocks = (1..9).map { "Earlier paragraph $it." }

        val spokenBeforeTarget = precedingBlocks.flatMap { block ->
            tokenizer.tokenize(block).map { block.substring(it) }
        }
        val selectedBlock = "The selected paragraph starts here. Its next sentence follows."
        val spokenAtTarget = tokenizer.tokenize(selectedBlock).map { selectedBlock.substring(it) }

        assertEquals(precedingBlocks, spokenBeforeTarget)
        assertEquals(
            listOf("The selected paragraph starts here.", "Its next sentence follows."),
            spokenAtTarget
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

    @Test fun aMismatchingTargetDoesNotDropEarlierBlocks() {
        val skipper = SentenceStartSkipper("Not in this text.")
        val tokenizer = tokenizer(skipper)
        repeat(9) { index ->
            val earlierBlock = "Earlier paragraph ${index + 1}."
            assertEquals(
                listOf(earlierBlock),
                tokenizer.tokenize(earlierBlock).map { earlierBlock.substring(it) }
            )
        }
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
