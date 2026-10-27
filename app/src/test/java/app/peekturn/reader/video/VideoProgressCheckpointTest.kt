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
}
