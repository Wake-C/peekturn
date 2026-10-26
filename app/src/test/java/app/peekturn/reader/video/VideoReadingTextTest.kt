package app.peekturn.reader.video

import org.junit.Assert.*
import org.junit.Test

class VideoReadingTextTest {
    @Test fun softSubtitleLinesFlowWithoutChangingWordsOrPunctuation() {
        val text = VideoReadingText.from("  The rabbit\n took\r\n a\twatch.\u00a0“Why?”  ")
        assertEquals("The rabbit took a watch. “Why?”", text.text)
    }

    @Test fun wordAndPhraseLookupOffsetsStillAddressTheOriginalCue() {
        val source = "  She\n  turned\t down the offer."
        val flowed = VideoReadingText.from(source)
        val start = flowed.text.indexOf("turned")
        val end = flowed.text.indexOf(" the")
        val display = CaptionSelection(start, end, "turned down")
        val original = flowed.toSource(display)!!
        assertEquals("turned\t down", source.substring(original.start, original.end))
        assertEquals("turned down", original.term)
        assertEquals(display, flowed.toDisplay(original))
    }

    @Test fun everyWordHasAnExactSourceSpanIncludingUnicode() {
        val source = "\n🙂\tAlice’s\n rabbit\u2003waited.\n"
        val flowed = VideoReadingText.from(source)
        Regex("\\S+").findAll(flowed.text).forEach { match ->
            val selection = CaptionSelection(match.range.first, match.range.last + 1, match.value)
            val original = flowed.toSource(selection)!!
            assertEquals(match.value, source.substring(original.start, original.end))
            assertEquals(selection, flowed.toDisplay(original))
        }
    }

    @Test fun invalidOrEmptySelectionsDoNotReachLookup() {
        val text = VideoReadingText.from("\n \t")
        assertEquals("", text.text)
        assertNull(text.toSource(CaptionSelection(0, 1, "x")))
        assertNull(text.toDisplay(CaptionSelection(0, 1, " ")))
        val nonempty = VideoReadingText.from(" word ")
        assertNull(nonempty.toSource(CaptionSelection(-1, 1, "x")))
        assertNull(nonempty.toSource(CaptionSelection(0, 8, "x")))
    }
}
