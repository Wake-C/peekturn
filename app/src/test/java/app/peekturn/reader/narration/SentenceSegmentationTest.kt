package app.peekturn.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Narration must speak complete sentences: punctuation stays, decimals and common abbreviations do
 * not create fake edges, and a position in the middle of a phrase resolves to that sentence's start.
 * The tokenizer is Readium's platform ICU adapter, so this test runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SentenceSegmentationTest {

    private fun sentences(text: String) = sentenceRanges(text).map { text.substring(it) }

    @Test fun decimalsAreNotSentenceEdges() {
        assertEquals(
            listOf("The value is 3.5, not 3.6.", "Then it ends."),
            sentences("The value is 3.5, not 3.6. Then it ends.")
        )
    }

    @Test fun commonAbbreviationsAndInitialsStayInsideTheirSentence() {
        assertEquals(
            listOf("Dr. Smith arrived at 5 p.m.", "He waited for J. K. Rowling."),
            sentences("Dr. Smith arrived at 5 p.m. He waited for J. K. Rowling.")
        )
        assertEquals(
            listOf("The U.S. Army moved at 9 a.m.", "It was quiet."),
            sentences("The U.S. Army moved at 9 a.m. It was quiet.")
        )
        assertEquals(
            listOf("He lives in the U.S.", "Tomorrow he leaves."),
            sentences("He lives in the U.S. Tomorrow he leaves.")
        )
    }

    @Test fun ellipsisAndClosingQuotesStayWithTheSentence() {
        assertEquals(listOf("Wait...", "Then go."), sentences("Wait... Then go."))
        assertEquals(
            listOf("He said, \"Go now!\"", "She left."),
            sentences("He said, \"Go now!\" She left.")
        )
        assertEquals(listOf("Really?!", "Yes."), sentences("Really?! Yes."))
    }

    @Test fun punctuationIsPreserved() {
        val sentence = "A comma, a colon: and a semicolon; all stay."
        assertEquals(listOf(sentence), sentences(sentence))
        assertTrue(sentenceRanges(sentence).single().last == sentence.lastIndex)
    }

    @Test fun aPhraseMiddlePositionSnapsToTheContainingSentenceStart() {
        val text = "Today is a perfect day. Tomorrow is better."
        val perfect = text.indexOf("perfect")
        val range = sentenceContaining(text, perfect)
        assertEquals("Today is a perfect day.", text.substring(range))
        assertEquals(0, range.first)
    }

    @Test fun positionsBetweenSentencesChooseTheFollowingSentence() {
        val text = "One. Two. Three."
        assertEquals("Two.", text.substring(sentenceContaining(text, text.indexOf("Two") - 1)))
        assertEquals("Three.", text.substring(sentenceContaining(text, text.length)))
    }

    @Test fun canonicalSentenceCollapsesWhitespaceAndKeepsPunctuation() {
        assertEquals("A  sentence\nwith\tspaces.", "A  sentence\nwith\tspaces.".let { it })
        assertEquals("A sentence with spaces.", canonicalSentence("  A  sentence\nwith\tspaces.  "))
    }

    @Test fun blankTextHasNoSentences() {
        assertTrue(sentenceRanges("   \n\t ").isEmpty())
        assertTrue(sentenceRanges("").isEmpty())
    }


    @Test fun aStartSkipperDropsLeadingSentencesThenPassesEverything() {
        val text = "Yesterday was ordinary. Today is a perfect day. Tomorrow is better."
        val ranges = sentenceRanges(text)
        val skipper = SentenceStartSkipper("Today is a perfect day.")
        val first = skipper.filter(text, ranges)
        assertEquals(ranges.drop(1), first)
        // Readium builds more than one utterance window over the same start block.
        assertEquals(ranges.drop(1), skipper.filter(text, ranges))
        val next = skipper.filter("Tomorrow is better.", sentenceRanges("Tomorrow is better."))
        assertEquals(1, next.size)
    }

    @Test fun aStartSkipperTargetingTheFirstSentenceLeavesTheBlockWhole() {
        val text = "Today is a perfect day. Tomorrow is better."
        val ranges = sentenceRanges(text)
        val skipper = SentenceStartSkipper("Today is a perfect day.")
        assertEquals(ranges, skipper.filter(text, ranges))
    }

    @Test fun anAbsentStartTargetPreservesPrecedingContent() {
        val text = "One. Two."
        val ranges = sentenceRanges(text)
        val skipper = SentenceStartSkipper("Not on this page.")
        repeat(9) { assertEquals(ranges, skipper.filter(text, ranges)) }
    }

    @Test fun theReadiumTokenizerAdapterReturnsTheSameRanges() {
        val text = "Dr. Smith paid 3.5 dollars. He left."
        assertEquals(sentenceRanges(text), SentenceTextTokenizer().tokenize(text))
    }
}
