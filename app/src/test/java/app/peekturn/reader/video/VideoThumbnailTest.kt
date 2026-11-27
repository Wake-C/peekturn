package app.peekturn.reader.video

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoThumbnailTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun officialArtworkWinsOverAWarmMemberImageAndCanBeLoadedFromDisk() = runBlocking {
        val directory = temporary.newFolder()
        MockWebServer().use { server ->
            val official = server.url("/playlist.png").toString()
            val member = server.url("/member.png").toString()
            val candidates = videoThumbnailUrls(null, official, member)
            server.enqueue(image(24)); server.enqueue(image(48))
            VideoThumbnails(directory).use { loader ->
                assertEquals(24, loader.load(listOf(member))?.width)
                assertEquals(24, loader.peek(candidates)?.width)
                assertEquals(48, loader.load(candidates)?.width)
                assertEquals(48, loader.peek(candidates)?.width)
            }
            VideoThumbnails(directory).use { reopened -> assertEquals(48, reopened.load(candidates)?.width) }
            assertEquals(2, server.requestCount)
            assertEquals("/member.png", server.takeRequest().path)
            assertEquals("/playlist.png", server.takeRequest().path)
        }
    }

    @Test fun aMissingCoverUsesTheMemberAndOldEntriesGetTheirOwnPublicThumbnail() = runBlocking {
        val id = "aaaaaaaaaaa"
        val default = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        assertEquals(listOf(default), videoThumbnailUrls(id, null, "", default))
        assertTrue(videoThumbnailUrls("bad/id", null).isEmpty())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(image(24))
            VideoThumbnails(temporary.newFolder()).use { loader ->
                val urls = videoThumbnailUrls(id, server.url("/missing").toString(), server.url("/episode").toString())
                assertEquals(24, loader.load(urls)?.width)
            }
            assertEquals(2, server.requestCount)
        }
    }

    private fun image(width: Int): MockResponse {
        val bitmap = Bitmap.createBitmap(width, 12, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        bitmap.recycle()
        return MockResponse().setHeader("Cache-Control", "public, max-age=3600")
            .setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
    }
}
