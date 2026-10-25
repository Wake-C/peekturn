package app.peekturn.reader.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private val captionWhitespace = Regex("[\\s\\p{Z}\\u0085]+")

private fun normalize(value: String) = value.trim().replace(captionWhitespace, " ")

/** Windows, model-boundary validation and the no-word-change rule stay local to the app. */
class CaptionGroupingTest {

    private fun cue(id: Int, start: Long, end: Long, text: String) = CaptionCue(id, start, end, text)

    private fun captionDocument(cues: List<CaptionCue>) =
        CaptionDocument("video", "en-manual", "en", false, "source-hash", cues)

    @Test fun windowsAreConsecutiveAndBoundedByCueCount() {
        val cues = (0 until 200).map { cue(it, it * 20_000L, it * 20_000L + 19_000L, "cue $it") }
        val windows = captionMergeWindows(cues)
        assertEquals(3, windows.size)
        assertTrue(windows.all { it.size <= CAPTION_MERGE_MAX_WINDOW_CUES })
        assertEquals(cues, windows.flatten())
    }

    @Test fun windowsStayWithinTheCharacterBudgetWithoutDroppingACue() {
        val text = "a".repeat(499)
        val cues = (0 until 40).map { cue(it, it * 1_000L, it * 1_000L + 900L, text) }
        val windows = captionMergeWindows(cues)
        assertTrue(windows.size > 1)
        assertTrue(windows.all { window ->
            window.sumOf { it.text.length } + (window.size - 1) <= CAPTION_MERGE_MAX_WINDOW_CHARACTERS
        })
        assertEquals(cues, windows.flatten())
    }

    @Test fun oneOversizedCueFormsItsOwnWindowAndStaysReadable() {
        val oversized = cue(0, 0, 9_000, "x".repeat(9_000))
        val short = cue(1, 10_000, 11_000, "short")
        assertEquals(listOf(listOf(oversized), listOf(short)), captionMergeWindows(listOf(oversized, short)))
    }

    @Test fun onlyTheModelBoundariesAreRead() {
        assertEquals(listOf(4, 9), parseCaptionEndIds("""{"endCueIds":[4,9]}"""))
        assertNull("non-integers are not boundaries", parseCaptionEndIds("""{"endCueIds":[1.5]}"""))
        assertNull("strings are not ids", parseCaptionEndIds("""{"endCueIds":["7"]}"""))
        assertNull(parseCaptionEndIds("""{"endCueIds":"4"}"""))
        assertNull(parseCaptionEndIds("""[4,9]"""))
        assertNull(parseCaptionEndIds("""{"boundaries":[4]}"""))
        assertNull(parseCaptionEndIds("not json"))
    }

    @Test fun unknownRepeatedDescendingAndMissingBoundariesAreRejected() {
        val window = listOf(cue(10, 0, 1_000, "a"), cue(11, 1_100, 2_000, "b"), cue(12, 2_100, 3_000, "c"))
        assertNull(captionGroupsForWindow(window, emptyList()))
        assertNull("an invented id is not a boundary", captionGroupsForWindow(window, listOf(13)))
        assertNull("a repeated boundary would duplicate cues", captionGroupsForWindow(window, listOf(10, 10)))
        assertNull("descending boundaries are rejected", captionGroupsForWindow(window, listOf(12, 11)))
        assertNull("a missing tail leaves cues out", captionGroupsForWindow(window, listOf(11)))
        assertNull("a repeated tail id repeats a cue", captionGroupsForWindow(window, listOf(11, 12, 12)))
        assertNull("a boundary for a cue outside the window is invented",
            captionGroupsForWindow(window, listOf(11, 12, 13)))
        assertEquals(
            listOf(CaptionGroup(10, 11), CaptionGroup(12, 12)),
            captionGroupsForWindow(window, listOf(11, 12))
        )
    }

    @Test fun overlongModelGroupsAreSplitAtCueBoundariesInsteadOfFailingTheWindow() {
        assertEquals("a source gap over one second splits the model group",
            listOf(CaptionGroup(0, 0), CaptionGroup(1, 1)),
            captionGroupsForWindow(listOf(cue(0, 0, 1_000, "hello"), cue(1, 2_001, 3_000, "world")), listOf(1)))
        assertEquals("a group over twelve seconds is split, not refused",
            listOf(CaptionGroup(0, 0), CaptionGroup(1, 1)),
            captionGroupsForWindow(listOf(cue(0, 0, 6_000, "hello"), cue(1, 6_100, 12_001, "world")), listOf(1)))
        assertEquals("a group over thirty words is split at the cue boundary",
            listOf(CaptionGroup(0, 0), CaptionGroup(1, 1)),
            captionGroupsForWindow(
                listOf(
                    cue(0, 0, 1_000, (1..16).joinToString(" ") { "word$it" }),
                    cue(1, 1_100, 2_000, (17..32).joinToString(" ") { "word$it" })
                ),
                listOf(1)
            ))

        assertEquals("the limits are inclusive",
            listOf(CaptionGroup(0, 1)),
            captionGroupsForWindow(listOf(cue(0, 0, 1_000, "hello"), cue(1, 2_000, 3_000, "world")), listOf(1)))
        assertEquals("a single oversized cue is kept alone",
            listOf(CaptionGroup(0, 0)),
            captionGroupsForWindow(
                listOf(cue(0, 0, 30_000, (1..40).joinToString(" ") { "word$it" })),
                listOf(0)
            ))
    }

    @Test fun splittingKeepsTheModelBoundariesInsideTheSafeBudget() {
        // Two model groups; only the second crosses a pause, so only it is split further.
        val window = listOf(
            cue(4, 0, 900, "keep"), cue(5, 1_000, 1_900, "together"),
            cue(6, 4_000, 4_900, "split"), cue(7, 6_500, 7_400, "here")
        )
        assertEquals(
            listOf(CaptionGroup(4, 5), CaptionGroup(6, 6), CaptionGroup(7, 7)),
            captionGroupsForWindow(window, listOf(5, 7))
        )
        val split = listOf(cue(0, 0, 1_000, "one"), cue(1, 2_500, 3_500, "two"), cue(2, 3_600, 4_500, "three"))
        val groups = checkNotNull(captionGroupsForWindow(split, listOf(2))) {
            "an over-long model group must still become a valid partition"
        }
        assertEquals(listOf(CaptionGroup(0, 0), CaptionGroup(1, 2)), groups)
        val merged = applyCaptionGroups(captionDocument(split), groups)
        assertEquals("one", merged[0].text)
        assertEquals("two three", merged[1].text)
        assertEquals(listOf(0L, 2_500L), merged.map { it.startMs })
        assertEquals(listOf(1_000L, 4_500L), merged.map { it.endMs })
    }

    @Test fun modelBoundaryCeilingsRespectWordsDurationsPausesAndOversizedSingletons() {
        val wordHeavy = listOf(
            cue(73, 0, 4_500, (1..17).joinToString(" ") { "word$it" }),
            cue(74, 4_500, 9_680, (18..34).joinToString(" ") { "word$it" })
        )
        assertEquals(0, captionGroupEndLimit(wordHeavy, 0))
        assertEquals(1, captionGroupEndLimit(wordHeavy, 1))
        val timed = listOf(
            cue(0, 0, 6_000, "one"), cue(1, 6_000, 12_000, "two"),
            cue(2, 12_000, 12_001, "three"), cue(3, 13_002, 14_000, "four")
        )
        assertEquals(1, captionGroupEndLimit(timed, 0))
        assertEquals(2, captionGroupEndLimit(timed, 1))
        assertEquals(2, captionGroupEndLimit(timed, 2))
        val oversized = listOf(cue(0, 0, 20_000, "one"), cue(1, 20_000, 21_000, "two"))
        assertEquals(0, captionGroupEndLimit(oversized, 0))
    }

    @Test fun mergedCuesKeepEveryOriginalWordAndUseOnlySourceTimes() {
        val document = captionDocument(listOf(
            cue(0, 0, 1_200, "the drug "),
            cue(1, 1_200, 2_400, "to master"),
            cue(2, 2_400, 3_600, "reality"),
            cue(3, 3_600, 4_800, " now")
        ))
        val merged = applyCaptionGroups(document,
            listOf(CaptionGroup(0, 1), CaptionGroup(2, 2), CaptionGroup(3, 3)))

        assertEquals(listOf(0, 2, 3), merged.map { it.id })
        assertEquals(listOf(0L, 2_400L, 3_600L), merged.map { it.startMs })
        assertEquals(listOf(2_400L, 3_600L, 4_800L), merged.map { it.endMs })
        assertEquals("the drug to master", merged[0].text)
        assertEquals("reality", merged[1].text)
        assertEquals("now", merged[2].text)
        assertEquals(
            normalize(document.cues.joinToString(" ") { it.text }),
            normalize(merged.joinToString(" ") { it.text })
        )
    }

    @Test fun mergedEndIsTheLatestTimeItCoversNotAFabricatedOne() {
        val document = captionDocument(listOf(cue(0, 0, 5_000, "overlapping"), cue(1, 1_000, 2_000, "source")))
        val merged = applyCaptionGroups(document, listOf(CaptionGroup(0, 1)))
        assertEquals(1, merged.size)
        assertEquals(0, merged[0].startMs)
        assertEquals(5_000, merged[0].endMs)
        assertEquals("overlapping source", merged[0].text)
    }

    @Test fun cachedPartitionsMustStillRespectPauseAndDurationLimits() {
        val pause = captionDocument(listOf(
            cue(0, 0, 1_000, "hello"), cue(1, 2_001, 3_000, "world")))
        val duration = captionDocument(listOf(
            cue(0, 0, 6_000, "hello"), cue(1, 6_100, 12_001, "world")))
        for (document in listOf(pause, duration)) {
            assertThrows(IllegalArgumentException::class.java) {
                applyCaptionGroups(document, listOf(CaptionGroup(0, 1)))
            }
        }
    }

    @Test fun groupsMustPartitionTheTrackExactlyOnce() {
        val document = captionDocument(listOf(cue(0, 0, 1_000, "a"), cue(1, 1_000, 2_000, "b")))
        assertThrows(IllegalArgumentException::class.java) {
            applyCaptionGroups(document, listOf(CaptionGroup(1, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            applyCaptionGroups(document, listOf(CaptionGroup(0, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            applyCaptionGroups(document, listOf(CaptionGroup(0, 1), CaptionGroup(1, 1)))
        }
        assertEquals(emptyList<CaptionCue>(), applyCaptionGroups(captionDocument(emptyList()), emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            applyCaptionGroups(captionDocument(emptyList()), listOf(CaptionGroup(0, 0)))
        }
    }
}
