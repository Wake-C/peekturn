package app.peekturn.reader.video

import android.view.KeyEvent
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.reader.NarrationVolumeKeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoReadVolumeKeysTest {
    private val cues = listOf(
        CaptionCue(0, 0, 1_000, "Before."),
        CaptionCue(1, 1_000, 2_000, "Now."),
        CaptionCue(2, 2_000, 3_000, "After.")
    )
    private val reading = VideoPlaybackState(
        phase = VideoPhase.Ready,
        readMode = true,
        audioOnly = true,
        positionMs = 1_500,
        captions = CaptionDocument("fixture", "en", "en", false, "fixture", cues)
    )

    private fun VideoReadVolumeKeys.press(
        state: VideoPlaybackState? = reading,
        key: Int = KeyEvent.KEYCODE_VOLUME_DOWN,
        event: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0,
        foreground: Boolean = true
    ) = action(foreground, state, key, event, repeat)

    @Test fun `read mode uses the same up previous and down next mapping as books`() {
        val keys = VideoReadVolumeKeys()
        assertEquals(NarrationVolumeKeyAction.PREVIOUS, keys.press(key = KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(NarrationVolumeKeyAction.NEXT, keys.press())
        assertNull(keys.press(key = KeyEvent.KEYCODE_BACK))
        assertNull(keys.press(key = KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test fun `watch audio only watch and unfocused activity leave volume with Android`() {
        for (state in listOf(reading.copy(readMode = false), reading.copy(readMode = false, audioOnly = false))) {
            assertNull(VideoReadVolumeKeys().press(state))
        }
        assertNull(VideoReadVolumeKeys().press(foreground = false))
    }

    @Test fun `read navigation remains available while paused or playing`() {
        for (playing in listOf(false, true)) {
            val state = reading.copy(isPlaying = playing, playRequested = playing)
            assertEquals(NarrationVolumeKeyAction.NEXT, VideoReadVolumeKeys().press(state))
        }
    }

    @Test fun `unavailable media and transitions do not capture keys`() {
        val unavailable = listOf(null, reading.copy(phase = VideoPhase.Idle),
            reading.copy(phase = VideoPhase.Loading), reading.copy(phase = VideoPhase.Failed(VideoFailureKind.Network)),
            reading.copy(captions = null), reading.copy(modeSaving = true), reading.copy(qualityChanging = true))
        for (state in unavailable) assertNull(VideoReadVolumeKeys().press(state))
    }

    @Test fun `lookup keeps volume available for word pronunciation without moving video`() {
        val lookup = VideoLookupRequest(
            context = LookupContext(selection = "Now", sourceTitle = "fixture"),
            cueId = 1,
            selection = CaptionSelection(start = 0, end = 3, term = "Now"),
            startMs = 1_000
        )
        assertNull(VideoReadVolumeKeys().press(reading.copy(lookup = lookup)))
    }

    @Test fun `held repeats and key up are consumed without a second navigation`() {
        val keys = VideoReadVolumeKeys()
        assertEquals(NarrationVolumeKeyAction.NEXT, keys.press())
        assertEquals(NarrationVolumeKeyAction.CONSUME, keys.press(repeat = 1))
        assertEquals(NarrationVolumeKeyAction.CONSUME, keys.press(repeat = 4))
        assertEquals(NarrationVolumeKeyAction.CONSUME, keys.press(event = KeyEvent.ACTION_UP))
        assertEquals(NarrationVolumeKeyAction.NEXT, keys.press())
    }

    @Test fun `a captured key still consumes its release after read mode or focus changes`() {
        val keys = VideoReadVolumeKeys()
        assertEquals(NarrationVolumeKeyAction.NEXT, keys.press())
        assertEquals(NarrationVolumeKeyAction.CONSUME,
            keys.press(state = reading.copy(readMode = false), repeat = 1, foreground = false))
        assertEquals(NarrationVolumeKeyAction.CONSUME,
            keys.press(state = reading.copy(readMode = false), event = KeyEvent.ACTION_UP, foreground = false))
        assertNull(keys.press(state = reading.copy(readMode = false)))
    }

    @Test fun `enabling read mode cannot take over a volume key already held elsewhere`() {
        val keys = VideoReadVolumeKeys()
        assertNull(keys.press(reading.copy(readMode = false)))
        assertNull(keys.press(repeat = 1))
        assertNull(keys.press(event = KeyEvent.ACTION_UP))
        assertEquals(NarrationVolumeKeyAction.NEXT, keys.press())
        keys.clear()
        assertNull(keys.press(event = KeyEvent.ACTION_UP))
    }

    @Test fun `grouped captions remain navigable and boundaries do not fall through to volume`() {
        val grouped = reading.copy(captions = null, mergedCues = cues.take(1), mergeEnabled = true)
        assertEquals(NarrationVolumeKeyAction.PREVIOUS,
            VideoReadVolumeKeys().press(grouped, key = KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(NarrationVolumeKeyAction.NEXT, VideoReadVolumeKeys().press(grouped))
    }
}
