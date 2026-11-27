package app.peekturn.reader.video

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.localization.DateWrapper
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

    @Test fun `relative dates share a sorting bucket and missing dates stay unknown`() {
        val first = item("aaaaaaaaaaa").apply {
            uploadDate = DateWrapper(Instant.parse("2024-02-18T06:34:54Z"), true)
            textualUploadDate = "2 years ago"
        }
        val sameDay = item("bbbbbbbbbbb").apply {
            uploadDate = DateWrapper(Instant.parse("2024-02-18T06:40:00Z"), false)
            textualUploadDate = "2 hours ago"
        }
        val exactInstant = Instant.parse("2025-01-07T15:45:00Z")
        val exact = item("ccccccccccc").apply { uploadDate = DateWrapper(exactInstant) }
        val missing = item("ddddddddddd")
        val videos = playlistVideos(InfoItemsPage(listOf(first, sameDay, exact, missing), null, emptyList())) {
            error("Unexpected continuation")
        }
        val day = Instant.parse("2024-02-18T00:00:00Z").toEpochMilli()
        assertEquals(listOf(day, day, exactInstant.toEpochMilli(), null), videos.map { it.publishedAtMs })
        assertEquals(listOf(true, true, false, false), videos.map { it.publishedAtApproximate })
    }

    @Test fun `Atom published dates refine only listed members and never use modification time`() {
        val original = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").map {
            VideoMetadata(it, it, "Channel", 10_000, null, 1_000, true)
        }
        val playlist = YouTubePlaylist("PLtest", "Course", original, "https://img.example/cover")
        val actual = playlist.withPublishedDates("""<feed xmlns="http://www.w3.org/2005/Atom"
            xmlns:yt="http://www.youtube.com/xml/schemas/2015">
            <entry><yt:videoId>bbbbbbbbbbb</yt:videoId><updated>2026-10-02T14:00:00Z</updated></entry>
            <entry><yt:videoId>ccccccccccc</yt:videoId><published>bad</published></entry>
            <entry><yt:videoId>other</yt:videoId><published>2026-09-30T12:30:00Z</published></entry>
            <entry><yt:videoId>aaaaaaaaaaa</yt:videoId><published>2025-07-04T09:00:00Z</published>
                <updated>2026-10-02T14:00:00Z</updated></entry></feed>""")
        val date = Instant.parse("2025-07-04T09:00:00Z").toEpochMilli()
        assertEquals(playlist.copy(videos = listOf(original[0].copy(publishedAtMs = date,
            publishedAtApproximate = false), original[1], original[2])), actual)
        assertEquals(playlist, playlist.withPublishedDates("<html><body>Unavailable</body></html>"))
    }

    @Test fun `continuation failure does not yield a partial playlist`() {
        val page = InfoItemsPage(listOf(item("aaaaaaaaaaa")), Page("https://example.org/next"), emptyList())
        assertThrows(IOException::class.java) { playlistVideos(page) { throw IOException("offline") } }
    }

    @Test fun `reads a public playlist from YouTube`() = runBlocking {
        val id = System.getenv("PEEKTURN_PLAYLIST_PROBE_ID")
        assumeTrue(!id.isNullOrBlank())
        val enrich = System.getenv("PEEKTURN_PLAYLIST_PROBE_FEED") == "true"
        val playlist = YouTubeSource().playlist(checkNotNull(id), enrichDates = enrich)
        check(playlist.id == id && playlist.title.isNotBlank())
        check(playlist.videos.size >= 2 && playlist.videos.all { it.title.isNotBlank() })
        check(playlist.videos.distinctBy { it.videoId } == playlist.videos)
        println("Public playlist videos=${playlist.videos.size}, cover=${playlist.thumbnailUrl != null}, " +
            "episodeImages=${playlist.videos.count { it.thumbnailUrl != null }}, " +
            "exactDates=${playlist.videos.count { it.publishedAtMs != null && !it.publishedAtApproximate }}, " +
            "approximateDates=${playlist.videos.count { it.publishedAtApproximate }}")
    }
}
