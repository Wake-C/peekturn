package app.peekturn.reader.video

import org.junit.Assert.*
import org.junit.Test

class VideoPreferencesTest {
    @Test fun `explicit video modes survive unrelated legacy default changes`() {
        val video = VideoItem(1, "u4LUix-BU0s", "Title", "Author", 60_000, null)
        assertTrue(preferredAudioOnly(video, true))
        assertFalse(preferredAudioOnly(video, false))
        assertTrue(preferredAudioOnly(video.copy(audioOnly = true), false))
        assertFalse(preferredAudioOnly(video.copy(audioOnly = false), true))
    }

    private fun qualities(vararg heights: Int) = heights.map {
        VideoQuality("${it}p", it, VideoPlaybackSource("https://example.com/$it", "video/mp4"))
    }
    @Test fun choosesRememberedHeightNotHighest() {
        assertEquals(720, preferredQuality(qualities(360, 720, 1080), 720)!!.height)
    }
    @Test fun missingPreferenceKeepsHdWhenRealHdExists() {
        assertEquals(1080, preferredQuality(qualities(360, 480, 1080), 720)!!.height)
        assertEquals(720, preferredQuality(qualities(360, 720, 1440), 1080)!!.height)
        assertEquals(480, preferredQuality(qualities(360, 480), 1080)!!.height)
        assertEquals(360, preferredQuality(qualities(360, 720), 360)!!.height)
        assertEquals(1440, preferredQuality(qualities(2160, 1440), 1080)!!.height)
    }
    @Test fun refreshingDownloaded360RestoresOnlineChoicesAndKeepsTheOfflineCopy() {
        val local = VideoQuality("360p", 360, VideoPlaybackSource("peekturn-offline://saved", "video/mp4"))
        val streams = qualities(360, 720, 1080)
        val restored = mergeVideoQualities(listOf(local), streams, local.id)
        assertEquals(listOf(1080, 720, 360), restored.map { it.height })
        assertEquals(local, restored.last())
        assertEquals(1080, preferredQuality(restored, 1080)!!.height)
        assertEquals(720, preferredQuality(restored, 720)!!.height)
    }

    @Test fun invalidPreferencesAndUnknownHeightAreSafe() {
        assertEquals(720, preferredQuality(qualities(360, 720, 1080, 2160), -1)!!.height)
        assertEquals(0, preferredQuality(qualities(0), 720)!!.height)
        assertNull(preferredQuality(emptyList(), 1080))
    }
}
