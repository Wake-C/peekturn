package app.peekturn.reader.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoCaptionParserTest {
    private val videoId = "u4LUix-BU0s"
    private val manualTrack = CaptionTrack("vtt:en:manual", "en", false, "https://example.invalid/captions")
    private val automaticTrack = CaptionTrack("vtt:en:asr", "en", true, "https://example.invalid/captions")

    private fun parse(payload: String, track: CaptionTrack = manualTrack): CaptionDocument =
        parseVideoCaptions(videoId, track, payload)

    private fun assertCaptionFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected VideoFailure(Captions)")
        } catch (failure: VideoFailure) {
            assertEquals(VideoFailureKind.Captions, failure.kind)
        }
    }

    @Test
    fun `manual captions keep words punctuation and timing`() {
        val document = parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.500 align:start position:0%
            Hello, <b>world</b>! It's 5 &lt; 6.

            00:00:04.000 --> 00:00:06.000
            Second line
            continues here.
            """.trimIndent()
        )
        assertEquals(videoId, document.videoId)
        assertEquals(manualTrack.id, document.trackId)
        assertEquals("en", document.language)
        assertEquals(false, document.automatic)
        assertEquals(2, document.cues.size)
        assertEquals(CaptionCue(0, 1_000, 3_500, "Hello, world! It's 5 < 6."), document.cues[0])
        assertEquals(CaptionCue(1, 4_000, 6_000, "Second line\ncontinues here."), document.cues[1])
    }

    @Test
    fun `rolling automatic cues drop only the repeated display line`() {
        val payload = "WEBVTT\nKind: captions\nLanguage: en\n\n" +
            "00:00:00.640 --> 00:00:02.389 align:start position:0%\n \n" +
            "Amphetamines<00:00:01.520><c> can</c><00:00:02.000><c> drive</c>\n\n" +
            "00:00:02.389 --> 00:00:02.399 align:start position:0%\n" +
            "Amphetamines can drive\n \n\n" +
            "00:00:02.399 --> 00:00:04.390 align:start position:0%\n" +
            "Amphetamines can drive\n" +
            "brutal<00:00:02.800><c> work</c> shifts\n\n" +
            "00:00:04.390 --> 00:00:04.400 align:start position:0%\n" +
            "brutal work shifts\n \n"
        val document = parse(payload, automaticTrack)
        assertEquals(2, document.cues.size)
        assertEquals(CaptionCue(0, 640, 2_389, "Amphetamines can drive", listOf(
            CaptionWord("Amphetamines", 640), CaptionWord("can", 1_520), CaptionWord("drive", 2_000))), document.cues[0])
        assertEquals(CaptionCue(1, 2_399, 4_390, "brutal work shifts", listOf(
            CaptionWord("brutal", 2_399), CaptionWord("work", 2_800), CaptionWord("shifts", 2_800, false))), document.cues[1])
    }

    @Test
    fun `repeated speech away from the rolling window is kept`() {
        val document = parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            No. No. No.

            00:00:04.000 --> 00:00:06.000
            No. No. No.

            00:00:06.010 --> 00:00:08.000
            very very good
            """.trimIndent(),
            automaticTrack
        )
        assertEquals(3, document.cues.size)
        assertEquals("No. No. No.", document.cues[0].text)
        assertEquals("No. No. No.", document.cues[1].text)
        assertEquals("very very good", document.cues[2].text)
    }

    @Test
    fun `adjacent equal automatic lines are kept without redraw evidence`() {
        val document = parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:01.000
            No.

            00:00:01.000 --> 00:00:02.000
            No.
            """.trimIndent(),
            automaticTrack
        )
        assertEquals(2, document.cues.size)
        assertEquals("No.", document.cues[0].text)
        assertEquals("No.", document.cues[1].text)
        assertEquals(1_000L, document.cues[1].startMs)
    }

    @Test
    fun `adjacent word-timed repetitions are kept when they are not redraw cues`() {
        val document = parse(
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:01.000\n" +
                "No.<00:00:00.500><c> No.</c>\n\n" +
                "00:00:01.000 --> 00:00:02.000\n" +
                "No.<00:00:01.500><c> No.</c>\n",
            automaticTrack
        )
        assertEquals(2, document.cues.size)
        assertEquals("No. No.", document.cues[0].text)
        assertEquals("No. No.", document.cues[1].text)
    }

    @Test
    fun `a timed repeated first line is kept even beside a refresh marker`() {
        val document = parse(
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:02.000\nNo.\n\n" +
                "00:00:02.000 --> 00:00:02.010\nNo.\n\n" +
                "00:00:02.010 --> 00:00:04.000\n" +
                "No.<00:00:02.500>\nSo be it.\n",
            automaticTrack
        )
        assertEquals(2, document.cues.size)
        assertEquals("No.", document.cues[0].text)
        assertEquals("No.\nSo be it.", document.cues[1].text)
    }

    @Test
    fun `short timed repetition is speech rather than a redraw marker`() {
        val document = parse(
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:01.000\nNo.\n\n" +
                "00:00:01.000 --> 00:00:01.100\nNo.<00:00:01.050>\n\n" +
                "00:00:01.100 --> 00:00:02.000\nNo.\nAgain.\n",
            automaticTrack
        )
        assertEquals(listOf("No.", "No.", "No.\nAgain."), document.cues.map { it.text })
        assertEquals(1_000L, document.cues[1].startMs)
        assertEquals(1_100L, document.cues[1].endMs)
    }

    @Test
    fun `an ambiguous untimed first-line repeat without a marker is kept`() {
        val document = parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            Hello

            00:00:02.000 --> 00:00:04.000
            Hello
            World
            """.trimIndent(),
            automaticTrack
        )
        assertEquals(2, document.cues.size)
        assertEquals("Hello", document.cues[0].text)
        assertEquals("Hello\nWorld", document.cues[1].text)
    }

    @Test
    fun `a tiny duplicate and a multi-line carry-over are normalized`() {
        val document = parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            Hello

            00:00:02.000 --> 00:00:02.010
            Hello

            00:00:02.010 --> 00:00:04.000
            Hello
            World
            """.trimIndent(),
            automaticTrack
        )
        assertEquals(2, document.cues.size)
        assertEquals(CaptionCue(0, 0, 2_000, "Hello"), document.cues[0])
        assertEquals(CaptionCue(1, 2_010, 4_000, "World"), document.cues[1])
    }

    @Test
    fun `manual tracks keep a repeated contiguous line`() {
        val document = parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            Thank you.

            00:00:02.000 --> 00:00:04.000
            Thank you.
            """.trimIndent(),
            manualTrack
        )
        assertEquals(2, document.cues.size)
        assertEquals("Thank you.", document.cues[0].text)
        assertEquals("Thank you.", document.cues[1].text)
    }

    @Test
    fun `cues are ordered, overlap is allowed and ids are contiguous`() {
        val document = parse(
            """
            WEBVTT

            00:00:05.000 --> 00:00:08.000
            Later

            00:00:01.000 --> 00:00:06.000
            Earlier overlap
            """.trimIndent()
        )
        assertEquals(listOf(1_000L, 5_000L), document.cues.map { it.startMs })
        assertEquals(listOf(0, 1), document.cues.map { it.id })
        assertTrue(document.cues.all { it.endMs > it.startMs && it.startMs >= 0 && it.text.isNotBlank() })
        assertEquals(6_000L, document.cues[0].endMs)
    }

    @Test
    fun `a zero length cue is not emitted as invalid timing`() {
        val document = parse(
            """
            WEBVTT

            00:00:03.000 --> 00:00:03.000
            Instant
            """.trimIndent()
        )
        assertEquals(1, document.cues.size)
        assertEquals(3_000L, document.cues[0].startMs)
        assertTrue(document.cues[0].endMs > document.cues[0].startMs)
    }

    @Test
    fun `voice tags and entities become plain display text`() {
        val document = parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:01.000
            <v Roger>Tom &amp; Jerry&nbsp;forever</v>
            """.trimIndent()
        )
        assertEquals("Tom & Jerry forever", document.cues.single().text)
    }

    @Test
    fun `short and comma timestamp forms are accepted`() {
        val document = parse(
            """
            WEBVTT

            01:02.500 --> 01:03.000
            Short form

            1:00:00,250 --> 1:00:01,000
            Hours form
            """.trimIndent()
        )
        assertEquals(62_500L, document.cues[0].startMs)
        assertEquals(63_000L, document.cues[0].endMs)
        assertEquals(3_600_250L, document.cues[1].startMs)
        assertEquals(3_601_000L, document.cues[1].endMs)
    }

    @Test
    fun `source hash covers normalized text and timing`() {
        val first = parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            Hello there.
            """.trimIndent()
        )
        val rewritten = parse(
            "WEBVTT file\r\n\r\ntrack\r\n00:00:01.000 --> 00:00:02.000 position:0%\r\nHello   there.\r\n"
        )
        val changedText = parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            Hello there!
            """.trimIndent()
        )
        val changedTiming = parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.500
            Hello there.
            """.trimIndent()
        )
        assertEquals(first.sourceHash, rewritten.sourceHash)
        assertNotEquals(first.sourceHash, changedText.sourceHash)
        assertNotEquals(first.sourceHash, changedTiming.sourceHash)
        assertEquals(64, first.sourceHash.length)
        assertTrue(first.sourceHash.all { it in "0123456789abcdef" })
        assertEquals(first.sourceHash, parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            Hello there.
            """.trimIndent()
        ).sourceHash)
    }

    @Test
    fun `empty html and header-only payloads fail as caption failures`() {
        assertCaptionFailure { parse("") }
        assertCaptionFailure { parse("   ") }
        assertCaptionFailure { parse("{}") }
        assertCaptionFailure { parse("<!DOCTYPE html><html><body>Sign in to confirm you're not a bot</body></html>") }
        assertCaptionFailure { parse("WEBVTT\n\n") }
        assertCaptionFailure { parse("WEBVTT\n\n00:00:00.000 --> nope\nhello") }
        assertCaptionFailure { parse("WEBVTT\n\nnot a cue at all") }
    }

    @Test
    fun `too many cues fail instead of returning a partial document`() {
        val builder = StringBuilder("WEBVTT\n\n")
        for (index in 0..MAX_CAPTION_CUES) {
            builder.append("00:00:00.000 --> 00:00:01.000\n")
            builder.append("line ").append(index).append("\n\n")
        }
        assertCaptionFailure { parse(builder.toString()) }
    }
}
