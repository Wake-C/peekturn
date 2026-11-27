package app.peekturn.reader.video

import org.junit.Assert.*
import org.junit.Test

class VideoProgressCheckpointTest {
    @Test fun loadingAndBufferingNeverQualifyAnObservedClock() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        assertNull(checkpoint.observe(1, 0, 0, false))
        assertNull(checkpoint.observe(1, 120_000, 60_000, false))
        assertNull(checkpoint.observe(1, 0, 120_000, false))
        assertNull(checkpoint.position(1))
    }

    @Test fun exactlyTenSecondsOfPlayingAreRequired() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        assertNull(checkpoint.observe(1, 120_000, 0, true))
        assertNull(checkpoint.observe(1, 129_999, 9_999, true))
        assertEquals(130_000L, checkpoint.observe(1, 130_000, 10_000, true))
        assertEquals(130_050L, checkpoint.observe(1, 130_050, 10_050, false))
    }

    @Test fun pauseAndBufferingTimeDoNotCountButPlaybackCanResume() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 120_000, 0, true)
        assertNull(checkpoint.observe(1, 125_000, 5_000, false))
        assertNull(checkpoint.observe(1, 125_000, 90_000, false))
        assertNull(checkpoint.observe(1, 125_000, 100_000, true))
        assertNull(checkpoint.observe(1, 129_999, 104_999, true))
        assertEquals(130_000L, checkpoint.observe(1, 130_000, 105_000, true))
    }

    @Test fun speedAndSeekJumpsDoNotPretendThatTimeWasSpentPlaying() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 120_000, 0, true)
        assertNull(checkpoint.observe(1, 130_000, 5_000, true)) // Double-speed media clock.
        assertEquals(140_000L, checkpoint.observe(1, 140_000, 10_000, true))
        checkpoint.seek()
        assertNull(checkpoint.position(1))
        assertNull(checkpoint.observe(1, 0, 10_000, false))
        assertNull(checkpoint.observe(1, 0, 60_000, true))
        assertNull(checkpoint.observe(1, 4_999, 64_999, true))
        assertEquals(10_000L, checkpoint.observe(1, 10_000, 70_000, true))
    }

    @Test fun preparingAnotherSourceCannotReplaceTheLastQualifiedPlaybackClock() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 120_000, 0, true)
        checkpoint.observe(1, 130_000, 10_000, false)
        assertEquals(130_000L, checkpoint.observe(1, 0, 11_000, false))
        assertEquals(130_000L, checkpoint.position(1))
        checkpoint.begin(null)
        assertNull(checkpoint.observe(1, 0, 12_000, false))
        checkpoint.begin(2)
        assertNull(checkpoint.observe(2, 0, 30_000, false))
        assertNull(checkpoint.position(1))
        assertNull(checkpoint.position(2))
    }

    @Test fun openingAndPausedSeeksNeverMarkAnEpisodeAsWatched() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 0, 0, false)
        checkpoint.seek()
        checkpoint.reposition(null)
        checkpoint.observe(1, 5_000, 20_000, false)
        assertEquals(VideoWatchState.UNWATCHED, checkpoint.watchState)

        // Even a playing seek directly to the end supplies a new clock, not watched media.
        checkpoint.observe(1, 0, 21_000, true)
        checkpoint.reposition(5_000)
        checkpoint.observe(1, 5_000, 21_000, false, endedWhilePlaying = true)
        assertEquals(VideoWatchState.UNWATCHED, checkpoint.watchState)
    }

    @Test fun shortEpisodesCanFinishBeforeResumeSavingQualifies() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 0, 0, true)
        assertEquals(VideoWatchState.UNWATCHED, checkpoint.watchState)
        checkpoint.observe(1, 350, 350, true)
        assertEquals(VideoWatchState.IN_PROGRESS, checkpoint.watchState)
        checkpoint.observe(1, 600, 600, false) // isPlaying may change before the end callback.
        checkpoint.observe(1, 600, 600, false, endedWhilePlaying = true)
        assertEquals(VideoWatchState.COMPLETED, checkpoint.watchState)
        assertNull(checkpoint.position(1))

        checkpoint.seek()
        checkpoint.observe(1, 0, 800, true)
        checkpoint.observe(1, 200, 1_000, true)
        assertEquals(VideoWatchState.COMPLETED, checkpoint.watchState)
    }

    @Test fun reachingTheEndRequiresPlaybackAfterTheLastReposition() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 120, 0, true)
        checkpoint.observe(1, 500, 380, false)
        checkpoint.reposition(null)
        checkpoint.observe(1, 9_000, 450, false, endedWhilePlaying = true)
        assertEquals(VideoWatchState.IN_PROGRESS, checkpoint.watchState)

        checkpoint.reposition(8_990)
        checkpoint.observe(1, 9_000, 480, false, endedWhilePlaying = true)
        assertEquals(VideoWatchState.COMPLETED, checkpoint.watchState)
    }

    @Test fun watchHistoryRestoresPerEpisodeWithoutReusingAnotherItemsClock() {
        val checkpoint = VideoProgressCheckpoint()
        checkpoint.begin(1)
        checkpoint.observe(1, 0, 0, true)
        checkpoint.begin(2)
        checkpoint.observe(1, 1_000, 1_000, false, endedWhilePlaying = true)
        checkpoint.observe(2, 1_000, 1_000, false, endedWhilePlaying = true)
        assertEquals(VideoWatchState.UNWATCHED, checkpoint.watchState)

        checkpoint.begin(1, VideoWatchState.COMPLETED)
        checkpoint.observe(1, 50, 1_050, true)
        checkpoint.observe(1, 500, 1_500, false)
        assertEquals(VideoWatchState.COMPLETED, checkpoint.watchState)
    }
}
