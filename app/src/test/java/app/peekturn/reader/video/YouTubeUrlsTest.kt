package app.peekturn.reader.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlsTest {
    private val id = "u4LUix-BU0s"

    @Test
    fun `supported link forms share one identity`() {
        val expected = VideoLink(id)
        assertEquals(expected, YouTubeUrls.parse("https://www.youtube.com/watch?v=$id"))
        assertEquals(expected, YouTubeUrls.parse("https://youtube.com/watch?v=$id"))
        assertEquals(expected, YouTubeUrls.parse("https://m.youtube.com/watch?v=$id"))
        assertEquals(expected, YouTubeUrls.parse("http://youtube.com/watch?v=$id"))
        assertEquals(expected, YouTubeUrls.parse("https://youtu.be/$id"))
        assertEquals(expected, YouTubeUrls.parse("https://www.youtube.com/shorts/$id"))
        assertEquals(expected, YouTubeUrls.parse("https://www.youtube.com/embed/$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", expected.canonicalUrl)
    }

    @Test
    fun `tracking parameters and playlist context do not change identity`() {
        val expected = VideoLink(id)
        assertEquals(expected, YouTubeUrls.parse("https://youtu.be/$id?is=BhXx5x4PxA_D40U4"))
        assertEquals(expected, YouTubeUrls.parse("https://youtu.be/$id?si=abc123"))
        assertEquals(
            expected,
            YouTubeUrls.parse("https://www.youtube.com/watch?v=$id&list=PL123456789&index=2&pp=xyz")
        )
        assertEquals(expected, YouTubeUrls.parse("https://www.youtube.com/watch?feature=share&v=$id"))
    }

    @Test
    fun `explicit start times accept seconds and clock notation`() {
        assertEquals(90_000L, YouTubeUrls.parse("https://youtu.be/$id?t=90")?.startMs)
        assertEquals(90_000L, YouTubeUrls.parse("https://youtu.be/$id?t=90s")?.startMs)
        assertEquals(90_000L, YouTubeUrls.parse("https://youtu.be/$id?t=1m30s")?.startMs)
        assertEquals(3_723_000L, YouTubeUrls.parse("https://youtu.be/$id?t=1h2m3s")?.startMs)
        assertEquals(3_723_000L, YouTubeUrls.parse("https://youtu.be/$id?t=1H2M3S")?.startMs)
        assertEquals(0L, YouTubeUrls.parse("https://youtu.be/$id?t=0")?.startMs)
        assertEquals(42_000L, YouTubeUrls.parse("https://www.youtube.com/watch?v=$id&start=42")?.startMs)
        assertEquals(60_000L, YouTubeUrls.parse("https://youtu.be/$id?t=1m")?.startMs)
        assertEquals(60_000L, YouTubeUrls.parse("https://youtu.be/$id?start=1m&t=1m")?.startMs)
        assertNull(YouTubeUrls.parse("https://youtu.be/$id?t=")?.startMs)
        assertNull(YouTubeUrls.parse("https://youtu.be/$id#t=90")?.startMs)
    }

    @Test
    fun `invalid times reject the whole link`() {
        val invalidTimes = listOf(
            "t=-1", "t=1.5", "t=abc", "t=1h2m3s4", "t=1h2", "t=--1",
            "t=999999999999999999999", "t=9223372036854776s"
        )
        for (suffix in invalidTimes) {
            assertNull(suffix, YouTubeUrls.parse("https://youtu.be/$id?$suffix"))
        }
    }

    @Test
    fun `foreign hosts and spoofed domains are refused`() {
        val refused = listOf(
            "https://example.com/watch?v=$id",
            "https://youtube.com.evil.example/watch?v=$id",
            "https://notyoutube.com/watch?v=$id",
            "https://evil-youtube.com/watch?v=$id",
            "https://youtube.co/watch?v=$id",
            "https://music.youtube.com/watch?v=$id",
            "https://user:pass@youtube.com/watch?v=$id",
            "https://youtube.com@evil.example/watch?v=$id"
        )
        for (text in refused) {
            assertNull(text, YouTubeUrls.parse(text))
        }
    }

    @Test
    fun `non video paths and malformed ids are refused`() {
        val refused = listOf(
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?v=${id}X",
            "https://www.youtube.com/watch?v=u4LUix.BU0s",
            "https://www.youtube.com/playlist?list=PL123456789",
            "https://www.youtube.com/@Kurzgesagt",
            "https://www.youtube.com/c/Kurzgesagt",
            "https://www.youtube.com/live/$id",
            "https://youtu.be/",
            "https://youtu.be/$id/extra",
            "ftp://youtu.be/$id",
            "javascript:alert(1)",
            "file:///data/local/tmp/video.mp4",
            "youtube.com/watch?v=short"
        )
        for (text in refused) {
            assertNull(text, YouTubeUrls.parse(text))
        }
    }

    @Test
    fun `shared text yields the first link`() {
        assertNull(
            YouTubeUrls.parse("The Drug To Master Reality https://example.com/article https://youtu.be/$id")
        )
        assertEquals(
            VideoLink(id),
            YouTubeUrls.parse("Worth reading https://youtu.be/$id then https://example.com")
        )
        assertEquals(
            VideoLink(id, 10_000L),
            YouTubeUrls.parse("https://youtu.be/$id?t=10&v=other")
        )
        assertEquals(
            VideoLink(id),
            YouTubeUrls.parse("The Drug To Master Reality https://youtu.be/$id?is=BhXx5x4PxA_D40U4 is worth watching")
        )
        assertEquals(
            VideoLink(id, 90_000L),
            YouTubeUrls.parse("看这个 https://youtu.be/$id?t=1m30s，然后告诉我")
        )
        assertEquals(VideoLink(id), YouTubeUrls.parse("The video (https://youtu.be/$id) is here"))
        assertEquals(VideoLink(id), YouTubeUrls.parse("https://youtu.be/$id."))
        assertEquals(VideoLink(id), YouTubeUrls.parse("https://youtu.be/$id，"))
        assertEquals(VideoLink(id), YouTubeUrls.parse("youtu.be/$id"))
        assertEquals(VideoLink(id), YouTubeUrls.parse("www.youtube.com/watch?v=$id"))
        assertEquals(VideoLink(id), YouTubeUrls.parse("m.youtube.com/watch?v=$id"))
        assertNull(YouTubeUrls.parse("notyoutube.com/watch?v=$id"))
        assertNull(YouTubeUrls.parse("i like this video"))
    }

    @Test
    fun `youtube detection routes invalid video links to the video path`() {
        assertTrue(YouTubeUrls.isYouTubeUrl("https://www.youtube.com/watch?v=$id"))
        assertTrue(YouTubeUrls.isYouTubeUrl("http://youtu.be/$id"))
        assertTrue(YouTubeUrls.isYouTubeUrl("https://youtu.be/"))
        assertTrue(YouTubeUrls.isYouTubeUrl("https://youtube.com/watch?v=short"))
        assertTrue(YouTubeUrls.isYouTubeUrl("https://m.youtube.com/feed/subscriptions"))
        assertTrue(YouTubeUrls.isYouTubeUrl("https://youtube.com/@Kurzgesagt"))
        assertTrue(YouTubeUrls.isYouTubeUrl("https://music.youtube.com/watch?v=$id"))
        assertNull(YouTubeUrls.parse("https://music.youtube.com/watch?v=$id"))
        assertTrue(YouTubeUrls.isYouTubeUrl("youtu.be/$id"))
        assertFalse(YouTubeUrls.isYouTubeUrl("https://youtube.com.evil.example/watch?v=$id"))
        assertFalse(YouTubeUrls.isYouTubeUrl("https://example.com/watch?v=$id"))
        assertFalse(YouTubeUrls.isYouTubeUrl("https://notyoutube.com/watch?v=$id"))
        assertFalse(YouTubeUrls.isYouTubeUrl("https://user:pass@youtube.com/watch?v=$id"))
        assertFalse(YouTubeUrls.isYouTubeUrl("just some text"))
    }

    @Test
    fun `video ids are exactly eleven ascii url characters`() {
        assertTrue(YouTubeUrls.isVideoId("u4LUix-BU0s"))
        assertTrue(YouTubeUrls.isVideoId("___________"))
        assertTrue(YouTubeUrls.isVideoId("-----------"))
        assertFalse(YouTubeUrls.isVideoId("u4LUix-BU0sX"))
        assertFalse(YouTubeUrls.isVideoId("u4LUix-BU0"))
        assertFalse(YouTubeUrls.isVideoId("u4LUix-BU0."))
        assertFalse(YouTubeUrls.isVideoId("u4LUix-BU0é"))
        assertFalse(YouTubeUrls.isVideoId(""))
        assertFalse(YouTubeUrls.isVideoId("u4LUix BU0s"))
    }
}
