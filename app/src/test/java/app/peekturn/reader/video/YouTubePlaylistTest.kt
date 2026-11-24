package app.peekturn.reader.video

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class YouTubePlaylistTest {
    private fun item(id: String, title: String = id, type: StreamType = StreamType.VIDEO_STREAM) =
        StreamInfoItem(0, "https://www.youtube.com/watch?v=$id", title, type).apply {
            uploaderName = "Channel"
            duration = 13
        }

    @Test fun `reads each page in order and skips unavailable and duplicate members`() {
        val first = item("aaaaaaaaaaa", "First")
        val last = item("bbbbbbbbbbb", "Last")
        val upcoming = item("ccccccccccc").apply { contentAvailability = ContentAvailability.UPCOMING }
        val live = item("ddddddddddd", type = StreamType.LIVE_STREAM)
        val continuation = Page("https://example.org/next")
        val firstPage = InfoItemsPage(listOf(first, upcoming, live), continuation, emptyList())
        val result = playlistVideos(firstPage) { cursor ->
            assertEquals(continuation, cursor)
            InfoItemsPage(listOf(item("aaaaaaaaaaa", "Repeat"), last), null, emptyList())
        }
        assertEquals(listOf(
            VideoMetadata("aaaaaaaaaaa", "First", "Channel", 13000L, null),
            VideoMetadata("bbbbbbbbbbb", "Last", "Channel", 13000L, null)
        ), result)
    }

    @Test fun `continuation failure does not yield a partial playlist`() {
        val page = InfoItemsPage(listOf(item("aaaaaaaaaaa")), Page("https://example.org/next"), emptyList())
        assertThrows(IOException::class.java) { playlistVideos(page) { throw IOException("offline") } }
    }

    @Test fun `reads a public playlist from YouTube`() = runBlocking {
        val id = System.getenv("PEEKTURN_PLAYLIST_PROBE_ID")
        assumeTrue(!id.isNullOrBlank())
        val playlist = YouTubeSource().playlist(checkNotNull(id))
        check(playlist.id == id && playlist.title.isNotBlank())
        check(playlist.videos.size >= 2 && playlist.videos.all { it.title.isNotBlank() })
        check(playlist.videos.distinctBy { it.videoId } == playlist.videos)
        println("Public playlist returned ${playlist.videos.size} videos")
    }
}
