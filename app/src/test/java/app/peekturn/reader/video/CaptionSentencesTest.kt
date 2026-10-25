package app.peekturn.reader.video

import org.junit.Assert.*
import org.junit.Test

class CaptionSentencesTest {
    private fun document(vararg cues: CaptionCue) = CaptionDocument("sampleVideo", "en", "en", false, "hash", cues.toList())

    @Test fun continuesAcrossBecauseWithoutHardWordOrDurationSplits() {
        val doc = document(
            CaptionCue(0, 0, 2_000, "I love you so much, because"),
            CaptionCue(1, 2_000, 4_000, "you are my heart, you are my destination."))
        val result = sentenceCaptions(doc)
        assertEquals(1, result.size)
        assertEquals("I love you so much, because you are my heart, you are my destination.", result.single().text)
        assertEquals(0L, result.single().startMs)
        assertEquals(4_000L, result.single().endMs)
    }

    @Test fun canSplitInsideSourceCueAndRetainsRealWordStarts() {
        val doc = document(CaptionCue(0, 0, 5_000, "Hello there. Next sentence.", listOf(
            CaptionWord("Hello", 0), CaptionWord("there.", 500),
            CaptionWord("Next", 2_000), CaptionWord("sentence.", 2_500))))
        val result = sentenceCaptions(doc)
        assertEquals(listOf("Hello there.", "Next sentence."), result.map { it.text })
        assertEquals(listOf(0L, 2_000L), result.map { it.startMs })
        assertEquals(2_000L, result.first().endMs)
        assertFalse(result.last().timingApproximate)
    }

    @Test fun missingWordTimesUsesHonestSourceAnchorNotEvenlySpacedGuesses() {
        val result = sentenceCaptions(document(CaptionCue(0, 500, 9_000, "Hello. Next sentence.")))
        assertEquals(2, result.size)
        assertEquals(500L, result.last().startMs)
        assertTrue(result.last().timingApproximate)
    }

    @Test fun handlesTitlesDecimalsAndSpeakerChanges() {
        val result = sentenceCaptions(document(
            CaptionCue(0, 0, 2_000, "Dr. Smith paid 3.14 dollars."),
            CaptionCue(1, 2_000, 3_000, ">> Thank you"),
            CaptionCue(2, 3_000, 4_000, ">> You are welcome.")))
        assertEquals(listOf("Dr. Smith paid 3.14 dollars.", ">> Thank you", ">> You are welcome."), result.map { it.text })
    }

    @Test fun longSentenceIsNotCutAtAnArbitraryBudget() {
        val left = "This is a deliberately long sentence with " + "many ".repeat(35) + "words because"
        val result = sentenceCaptions(document(CaptionCue(0, 0, 20_000, left),
            CaptionCue(1, 20_000, 40_000, "the speaker has not finished yet.")))
        assertEquals(1, result.size)
        assertTrue(result.single().text.endsWith("because the speaker has not finished yet."))
    }

    @Test fun speakerBoundariesWorkInsideOneSourceBlock() {
        val result = sentenceCaptions(document(CaptionCue(0, 0, 5_000, ">> Thank you >> You are welcome.")))
        assertEquals(listOf(">> Thank you", ">> You are welcome."), result.map { it.text })
    }

    @Test fun sentenceAfterFinalInitialIsNotAttachedToTheName() {
        val result = sentenceCaptions(document(CaptionCue(0, 0, 5_000, "My name is Coin K. And I will stay.")))
        assertEquals(listOf("My name is Coin K.", "And I will stay."), result.map { it.text })
    }

    @Test fun inlineCensorMarkerDoesNotBreakTheSurroundingSentence() {
        val result = sentenceCaptions(document(CaptionCue(0, 0, 5_000, ">> Mark, that's [ __ ] man. That's a new problem.")))
        assertEquals(listOf(">> Mark, that's [ __ ] man.", "That's a new problem."), result.map { it.text })
    }

    @Test fun unpunctuatedTrackKeepsItsTimelineInsteadOfBecomingOneHugeRow() {
        val doc = document(CaptionCue(0, 0, 5_000, "these words have no punctuation"),
            CaptionCue(1, 5_000, 10_000, "and neither do these"))
        assertEquals(doc.cues, sentenceCaptions(doc))
    }

    @Test fun quotedExclamationDoesNotDetachTheFollowingClause() {
        val result = sentenceCaptions(document(
            CaptionCue(0, 0, 2_000, "Dopamine says “yes, this matters!”"),
            CaptionCue(1, 2_000, 5_000, "and noradrenaline gives you the tools to make it happen.")))
        assertEquals(1, result.size)
        assertTrue(result.single().text.contains("matters!” and noradrenaline"))
    }

    @Test fun noWordPunctuationOrOrderChangesInAnyAlgorithm() {
        val doc = document(CaptionCue(0, 0, 8_000, "What? Yes! Mr. Smith says, ‘Hello.’ Then he leaves."))
        SentenceAlgorithm.entries.forEach {
            val result = sentenceCaptions(doc, it)
            assertEquals(normalizedCaptionText(doc.cues.single().text), result.joinToString(" ") { cue -> cue.text })
            assertEquals(result.indices.toList(), result.map { cue -> cue.id })
        }
    }

    @Test fun parserRetainsInlineWordTimesWithoutChangingText() {
        val track = CaptionTrack("en", "en", true, "https://example.com")
        val doc = parseVideoCaptions("sampleVideo", track, """WEBVTT

00:00:01.000 --> 00:00:05.000
Hello<00:00:02.000><c> there.</c><00:00:03.000><c> Next sentence.</c>
""")
        assertEquals("Hello there. Next sentence.", doc.cues.single().text)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 3_000L), doc.cues.single().words!!.map { it.startMs })
        val result = sentenceCaptions(doc)
        assertEquals(3_000L, result.last().startMs)
    }
}
