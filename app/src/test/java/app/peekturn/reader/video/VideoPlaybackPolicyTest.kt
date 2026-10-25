package app.peekturn.reader.video

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VideoPlaybackPolicyTest {

    private fun cue(id: Int, start: Long, end: Long, text: String = "line $id") =
        CaptionCue(id, start, end, text)

    @Test
    fun `current cue follows player time and is empty outside source intervals`() {
        val cues = listOf(cue(0, 0, 1000), cue(1, 1000, 2000), cue(2, 2000, 3000))
        assertEquals(0, activeCueIndex(cues, 0)!!)
        assertEquals(0, activeCueIndex(cues, 999)!!)
        assertEquals(1, activeCueIndex(cues, 1000)!!)
        assertEquals(2, activeCueIndex(cues, 2999)!!)
        // A gap or the tail after the last end has no active line; source timing is not extended.
        assertNull(activeCueIndex(cues, 3000))
        assertNull(activeCueIndex(cues, 5000))
        assertNull(activeCueIndex(cues, -1))
        assertNull(activeCueIndex(emptyList(), 10))
        val gapped = listOf(cue(0, 0, 1000), cue(1, 2000, 3000))
        assertNull(activeCueIndex(gapped, 1500))
        assertEquals(0, navigationCueIndex(gapped, 1500)!!)

        val overlapping = listOf(cue(0, 0, 2000), cue(1, 1000, 3000))
        assertEquals(0, activeCueIndex(overlapping, 1500)!!)
        assertEquals(1, activeCueIndex(overlapping, 2500)!!)
    }

    @Test
    fun `previous and next stay in range through gaps and at both ends`() {
        val cues = listOf(cue(0, 0, 1000), cue(1, 1000, 2000), cue(2, 2000, 3000))
        assertNull(previousCueIndex(cues, 500))
        assertEquals(0, previousCueIndex(cues, 1500)!!)
        assertEquals(1, nextCueIndex(cues, 500)!!)
        assertEquals(2, nextCueIndex(cues, 1500)!!)
        assertNull(nextCueIndex(cues, 2500))
        assertNull(nextCueIndex(emptyList(), 0))

        val gapped = listOf(cue(0, 0, 1000), cue(1, 2000, 3000))
        assertEquals(0, previousCueIndex(gapped, 1500)!!)
        assertEquals(1, nextCueIndex(gapped, 1500)!!)
        // Before the first cue there is no previous target and the first cue is next.
        assertNull(previousCueIndex(gapped, -1))
        assertEquals(0, nextCueIndex(gapped, -1)!!)
        // Inside the first cue the next target is already the second one.
        assertNull(previousCueIndex(gapped, 500))
        assertEquals(1, nextCueIndex(gapped, 500)!!)
        assertEquals(1, previousCueIndex(gapped, 3200)!!)
        assertNull(nextCueIndex(gapped, 3200))
    }

    @Test
    fun `a shared start time wins and is clamped to the real duration`() {
        assertEquals(5000L, playbackStartMs(itemPositionMs = 1000, linkStartMs = 5000, durationMs = 10_000))
        assertEquals(1000L, playbackStartMs(itemPositionMs = 1000, linkStartMs = null, durationMs = 10_000))
        assertEquals(10_000L, playbackStartMs(itemPositionMs = 0, linkStartMs = 99_000, durationMs = 10_000))
        assertEquals(4000L, playbackStartMs(itemPositionMs = 4000, linkStartMs = null, durationMs = 0))
    }

    @Test
    fun `the real end is not replayed automatically`() {
        assertTrue(shouldAutoPlay(0, 10_000))
        assertTrue(shouldAutoPlay(8_999, 10_000))
        assertTrue(!shouldAutoPlay(9_000, 10_000))
        assertTrue(!shouldAutoPlay(10_000, 10_000))
        assertTrue(shouldAutoPlay(5_000, 0))
    }

    @Test
    fun `only an explicit saved track is restored and no English means no selection`() {
        val manual = CaptionTrack("en-manual", "en", automatic = false, url = "https://caption/1")
        val auto = CaptionTrack("en-auto", "en", automatic = true, url = "https://caption/2")
        val translated = CaptionTrack("en-fr", "en", automatic = true, url = "https://caption/4",
            translatedFrom = "fr")
        val another = CaptionTrack("de", "de", automatic = false, url = "https://caption/3")
        assertEquals(manual, selectInitialTrack(listOf(manual, auto), "en-manual"))
        assertEquals(manual, selectInitialTrack(listOf(manual, auto), "deleted"))
        assertEquals(manual, selectInitialTrack(listOf(manual, auto, translated), null))
        assertEquals(translated, selectInitialTrack(listOf(translated, auto, manual), "en-fr"))
        // A previous automatic foreign fallback is not intent, and is never substituted for English.
        assertEquals(another, selectInitialTrack(listOf(another), "de", explicit = true))
        assertNull(selectInitialTrack(listOf(another), "de", explicit = false))
        assertNull(selectInitialTrack(listOf(another), null))
        assertNull(selectInitialTrack(emptyList(), "x"))
    }

    @Test
    fun `translated English follows source English and localized names stay honest`() {
        val translated = CaptionTrack("en-fr", "en", automatic = true, url = "u1", translatedFrom = "fr")
        val automatic = CaptionTrack("en-auto", "en", automatic = true, url = "u2")
        val manual = CaptionTrack("en-manual", "en", automatic = false, url = "u3")
        assertEquals(manual, selectInitialTrack(listOf(translated, automatic, manual), null))
        assertEquals(automatic, selectInitialTrack(listOf(translated, automatic), null))
        assertEquals(translated, selectInitialTrack(listOf(translated), null))

        assertEquals("English", localizedLanguageName("en", Locale.ENGLISH))
        assertEquals("French", localizedLanguageName("fr", Locale.ENGLISH))
        assertTrue(isEnglishLanguage("en-US"))
        assertTrue(!isEnglishLanguage("fr"))
        assertNull(localizedLanguageName("", Locale.ENGLISH))
        assertNull(localizedLanguageName(null, Locale.ENGLISH))
        assertNull(localizedLanguageName("und", Locale.ENGLISH))
    }

    @Test
    fun `quality switch keeps clock speed and pause ownership`() {
        val first = quality("240", 240)
        val second = quality("720", 720)
        val qualities = listOf(first, second)
        val plan = qualitySwitch(
            qualities = qualities,
            selectedQualityId = first.id,
            targetQualityId = second.id,
            positionMs = 42_500L,
            playWhenReady = true,
            lookupOpen = false,
            speed = 1.25f
        )!!
        assertEquals(second, plan.quality)
        assertEquals(42_500L, plan.positionMs)
        assertTrue(plan.resumeOnReady)
        assertEquals(1.25f, plan.speed)

        val behindLookup = qualitySwitch(qualities, first.id, second.id, 500L, true, true, 1f)!!
        assertTrue(!behindLookup.resumeOnReady)

        assertNull(qualitySwitch(qualities, second.id, second.id, 0L, true, false, 1f))
        assertNull(qualitySwitch(qualities, first.id, "missing", 0L, true, false, 1f))

        // A failed source keeps its id visible; an explicit retry tap must prepare it again, paused.
        val forced = qualitySwitch(
            qualities, second.id, second.id, 900L, playWhenReady = false, lookupOpen = false,
            speed = 1f, force = true
        )!!
        assertEquals(second, forced.quality)
        assertEquals(900L, forced.positionMs)
        assertTrue(!forced.resumeOnReady)
    }

    @Test
    fun `played quality is only named while the source really offers it`() {
        val first = quality("240", 240)
        val second = quality("720", 720)
        assertEquals(second.id, initialQualityId(listOf(first, second), second.source, second.id))
        assertEquals(first.id, initialQualityId(listOf(first, second), first.source, "gone"))
        assertNull(initialQualityId(listOf(first, second), quality("1080", 1080).source, null))
        assertNull(initialQualityId(emptyList(), first.source, null))
    }

    private fun quality(id: String, height: Int) = VideoQuality(
        id = id,
        height = height,
        source = VideoPlaybackSource("https://media/$id", "video/mp4")
    )

    @Test
    fun `a partition with the raw cue count is never presented as merged`() {
        val raw = listOf(cue(0, 0, 1_000), cue(1, 1_000, 2_000))
        val document = CaptionDocument("v", "t", "en", automatic = false, sourceHash = "h", cues = raw)
        val unchanged = VideoPlaybackState(captions = document, mergedCues = raw)
        assertTrue(unchanged.mergedUnchanged)
        val changed = VideoPlaybackState(
            captions = document,
            mergedCues = listOf(CaptionCue(0, 0, 2_000, "line 0 line 1"))
        )
        assertTrue(!changed.mergedUnchanged)
        assertTrue(!VideoPlaybackState(captions = document).mergedUnchanged)
    }

    @Test
    fun `clock text never invents an hour`() {
        assertEquals("00:00", formatClock(-10))
        assertEquals("00:09", formatClock(9_999))
        assertEquals("01:04", formatClock(64_000))
        assertEquals("1:01:01", formatClock(3_661_000))
    }

    @Test
    fun `speed text is stable`() {
        assertEquals("0.5×", speedText(0.5f))
        assertEquals("0.75×", speedText(0.75f))
        assertEquals("1×", speedText(1f))
        assertEquals("2×", speedText(2f))
    }

    @Test
    fun `cache size uses real units`() {
        assertEquals("0 B", formatCacheBytes(0))
        assertEquals("512 B", formatCacheBytes(512))
        assertEquals("1.5 KB", formatCacheBytes(1536))
        assertEquals("2.0 MB", formatCacheBytes(2L * 1024 * 1024))
    }

    @Test
    fun `section keys distinguish sentence text and source times`() {
        val document = CaptionDocument("video", "track", "en", false, "hash",
            (0..5).map { cue(it, it * 1000L, it * 1000L + 900) })
        val merged = listOf(
            cue(0, 0, 2000),
            cue(2, 2000, 4000),
            cue(4, 4000, 6000)
        )
        val first = captionSectionKey(document, merged, 0)
        assertTrue(first.startsWith("hash:sentence-v1:"))
        assertEquals(first, captionSectionKey(document, merged, 0))
        assertNotEquals(first, captionSectionKey(document, merged, 1))
        assertNotEquals(first, captionSectionKey(document, listOf(merged[0].copy(text = "Different sentence.")), 0))
        assertNotEquals(first, captionSectionKey(document, listOf(merged[0].copy(startMs = 1)), 0))
    }

    @Test
    fun `lookup keeps the selected cue and one neighbour, capped at thirty words`() {
        val cues = listOf(
            cue(0, 0, 1000, "First line here."),
            cue(1, 1000, 2000, "The selected words continue now."),
            cue(2, 2000, 3000, "Next context line.")
        )
        val start = cues[1].text.indexOf("selected")
        val context = videoLookupContext(
            selection = "selected",
            cueIndex = 1,
            sourceStart = start,
            sourceEnd = start + "selected".length,
            cues = cues,
            sourceTitle = "The Drug To Master Reality",
            videoId = "u4LUix-BU0s",
            sectionKey = "hash:1-1"
        )
        assertEquals("selected", context.selection)
        assertEquals("First line here. The", context.before)
        assertEquals("words continue now. Next context line.", context.after)
        assertEquals("youtube:u4LUix-BU0s", context.sourceId)
        assertEquals("The Drug To Master Reality", context.sourceTitle)
        assertEquals("hash:1-1", context.sectionKey)
        assertEquals("", context.sectionTitle)

        val prefix = (1..40).joinToString(" ") { "w$it" }
        val longCues = listOf(cue(0, 0, 1000, "$prefix selected $prefix"))
        val longStart = longCues[0].text.indexOf("selected")
        val capped = videoLookupContext(
            "selected", 0, longStart, longStart + 8, longCues, "t", "v", "k"
        )
        assertEquals(30, capped.before.split(" ").size)
        assertEquals(30, capped.after.split(" ").size)
        assertEquals("w11", capped.before.split(" ").first())
        assertEquals("w30", capped.after.split(" ").last())
    }

    @Test
    fun `word and phrase selection stay inside real word boundaries`() {
        val text = "Hello, don't stop the music."
        assertEquals(CaptionSelection(7, 12, "don't"), wordSelectionAt(text, 8))
        assertEquals(CaptionSelection(0, 5, "Hello"), wordSelectionAt(text, 2))
        assertNull(wordSelectionAt(text, 5))
        assertNull(wordSelectionAt(text, 6))
        assertNull(wordSelectionAt("", 0))
        val phraseStart = text.indexOf("stop")
        val phraseEnd = text.indexOf("the")
        assertEquals("stop the", phraseSelection(text, phraseStart + 1, phraseEnd + 1)?.term)
        assertEquals("stop", phraseSelection(text, phraseStart, phraseStart)?.term)
    }

    @Test
    fun `unused space outside a word rectangle is not a word hit`() {
        val word = CaptionSelection(0, 5, "Hello")
        val glyphs = listOf(Rect(0f, 0f, 42f, 20f))
        assertEquals(word, wordHit(word, glyphs, Offset(10f, 10f)))
        assertEquals(word, wordHit(word, glyphs, Offset(41.9f, 19.9f)))
        assertNull(wordHit(word, glyphs, Offset(43f, 10f)))
        assertNull(wordHit(word, glyphs, Offset(10f, 21f)))
        assertNull(wordHit(null, emptyList(), Offset(10f, 10f)))
        assertNull(wordHit(word, emptyList(), Offset(10f, 10f)))
    }

    @Test
    fun `aspect ratio is the stream's, with a layout fallback before the first frame`() {
        assertEquals((16f / 9f).toDouble(), videoAspectRatio(0, 0).toDouble(), 0.0001)
        assertEquals((16f / 9f).toDouble(), videoAspectRatio(1920, 1080).toDouble(), 0.0001)
        assertEquals((9f / 16f).toDouble(), videoAspectRatio(1080, 1920).toDouble(), 0.0001)
    }
}
