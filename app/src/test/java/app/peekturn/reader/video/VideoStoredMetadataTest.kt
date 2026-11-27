package app.peekturn.reader.video

import org.junit.Assert.*
import org.junit.Test

class VideoStoredMetadataTest {
    private val stored = VideoItem(1, "u4LUix-BU0s", "Title", "Author", 600_000, null, positionMs = 183_000)

    @Test fun openingKeepsTheSavedClockBeforeThereIsAPlayerTimeline() {
        val state = VideoPlaybackState(phase = VideoPhase.Loading).withStoredMetadata(stored)
        assertEquals(stored, state.item)
        assertEquals(183_000L, state.positionMs)
        assertEquals(600_000L, state.durationMs)
        assertTrue(state.phase is VideoPhase.Loading)
    }

    @Test fun failureBeforeMediaPreparationCannotTurnSavedProgressIntoZero() {
        val failed = VideoPlaybackState(phase = VideoPhase.Loading).withStoredMetadata(stored)
            .copy(phase = VideoPhase.Failed(VideoFailureKind.Network))
        assertEquals(183_000L, failed.positionMs)
        assertFalse(failed.playRequested)
        assertFalse(failed.isPlaying)
    }

    @Test fun cachedTextRestoresAtSavedPositionOnlyWhileOpeningItsOwnVideoAndTrack() {
        val item = stored.copy(selectedTrackId = "en", readMode = true, audioOnly = true)
        val cue = CaptionCue(1, 182_000L, 184_000L, "Here again.")
        val doc = CaptionDocument(item.videoId, "en", "en", false, "hash", listOf(cue))
        val opening = VideoPlaybackState(phase = VideoPhase.Loading).withStoredMetadata(item)
        val preview = opening.withOpeningCaptions(doc, listOf(cue))
        assertTrue(preview.readMode && preview.audioOnly)
        assertEquals(VideoPhase.Loading, preview.phase)
        assertEquals(item.positionMs, preview.positionMs)
        assertEquals(listOf(cue), preview.displayedCues)
        assertFalse(preview.playRequested)
        assertSame(opening, opening.withOpeningCaptions(doc.copy(trackId = "other"), null))
        assertSame(opening, opening.withOpeningCaptions(doc.copy(videoId = "other"), null))
        val ready = preview.copy(phase = VideoPhase.Ready, positionMs = 185_000)
        assertSame(ready, ready.withOpeningCaptions(doc, null))
    }

    @Test fun openingUsesTheSavedPortraitGeometryBeforeThePlayerReportsItsSize() {
        val opening = VideoPlaybackState(phase = VideoPhase.Loading)
        val portraitRatio = 480f / 854f
        assertEquals(portraitRatio, opening.withStoredMetadata(stored.copy(aspectRatio = portraitRatio)).aspectRatio, 0f)
        assertEquals(16f / 9f, opening.withStoredMetadata(stored).aspectRatio, 0f)
    }

    @Test fun lateMetadataNeverRewindsAnAttachedSession() {
        val ready = VideoPlaybackState(phase = VideoPhase.Ready, item = stored, positionMs = 190_000,
            durationMs = 610_000, isPlaying = true, playRequested = true)
        assertSame(ready, ready.withStoredMetadata(stored))
        assertEquals(190_000L, ready.withStoredMetadata(stored).positionMs)
    }
}
