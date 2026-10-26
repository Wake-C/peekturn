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

    @Test fun lateMetadataNeverRewindsAnAttachedSession() {
        val ready = VideoPlaybackState(phase = VideoPhase.Ready, item = stored, positionMs = 190_000,
            durationMs = 610_000, isPlaying = true, playRequested = true)
        assertSame(ready, ready.withStoredMetadata(stored))
        assertEquals(190_000L, ready.withStoredMetadata(stored).positionMs)
    }
}
