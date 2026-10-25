package app.peekturn.reader.video

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val payload = ByteArray(4096) { (it % 251).toByte() }
    private fun response() = MockResponse().setBody(Buffer().write(payload))
    private fun upstream() = OkHttpDataSource.Factory(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    private fun spec(url: String, key: String, length: Long = 1024) = DataSpec.Builder()
        .setUri(url).setKey(key).setLength(length).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
    private fun read(factory: DataSource.Factory, dataSpec: DataSpec): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(dataSpec)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
            return bytes.toByteArray()
        } finally { source.close() }
    }

    @Test fun defaultDiskBudgetIsOneGiBSharedByTheApp() {
        assertEquals(1_073_741_824L, VIDEO_MEDIA_CACHE_BYTES)
    }

    @Test fun recentlyPlayedSamplesAreRetainedWithoutIncreasingForwardPreloadToMinutes() {
        assertEquals(0L, DefaultLoadControl().getBackBufferDurationUs(PlayerId.UNSET))
        assertEquals(30_000_000L, videoLoadControl().getBackBufferDurationUs(PlayerId.UNSET))
        assertEquals(50_000, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS)
    }

    @Test fun aRepeatedPartialReadUsesDiskWhileUncachedPlaybackHitsTheNetworkAgain() {
        for (cacheable in listOf(false, true)) {
            val server = MockWebServer(); server.start()
            try {
                server.enqueue(response())
                server.enqueue(if (cacheable) MockResponse().setResponseCode(500) else response())
                val url = server.url("/media?clen=4096&lmt=1&signature=first").toString()
                val key = mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", url)!!
                val events = mutableListOf<String>()
                VideoMediaCache(context, temporary.newFolder(),
                    onFailure = { throw AssertionError("Cache initialization failed", it) }, onEvent = { events += it }).use { cache ->
                    val factory = cache.factory(upstream(), cacheable)
                    assertArrayEquals(payload.copyOf(1024), read(factory, spec(url, key)))
                    assertArrayEquals(payload.copyOf(1024), read(factory, spec(url.replace("first", "renewed"), key)))
                    assertEquals(if (cacheable) 1 else 2, server.requestCount)
                    if (cacheable) assertTrue(events.any { it.startsWith("media-cache-read-bytes:") })
                    assertTrue(events.none { it.contains("signature") || it.contains("http") })
                }
            } finally { server.shutdown() }
        }
    }

    @Test fun cachedRangesSurviveReopeningAndNoRedirectOrSignedUrlIsPersisted() {
        val server = MockWebServer(); server.start()
        val directory = temporary.newFolder()
        val key = "media-opaque-content-identity"
        try {
            val redirected = server.url("/redirected?signature=private-token").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected))
            server.enqueue(response())
            val url = server.url("/start?signature=private-token").toString()
            VideoMediaCache(context, directory).use { cache ->
                read(cache.factory(upstream(), true), spec(url, key))
            }
            val database = StandaloneDatabaseProvider(context)
            val disk = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(VIDEO_MEDIA_CACHE_BYTES), database)
            try {
                disk.checkInitialization()
                assertEquals(setOf(key), disk.keys)
                assertNull(disk.getContentMetadata(key).get(ContentMetadata.KEY_REDIRECTED_URI, null as String?))
                assertEquals(1024L, disk.getCachedBytes(key, 0, 1024))
            } finally { disk.release(); database.close() }
            server.enqueue(MockResponse().setResponseCode(500))
            VideoMediaCache(context, directory).use { reopened ->
                assertArrayEquals(payload.copyOf(1024), read(reopened.factory(upstream(), true), spec(url, key)))
            }
            assertEquals(2, server.requestCount) // Initial redirect and body only.
        } finally { server.shutdown() }
    }

    @Test fun contentIdentityIgnoresTokensButChangesWithEncodingLanguageOrRevision() {
        val url = "https://media.example/videoplayback?clen=4096&lmt=123&signature=one"
        val key = mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", url)!!
        assertEquals(key, mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", url.replace("one", "two")))
        assertNotEquals(key, mediaStreamCacheKey("RPwn7muocAg", 140, "mp4a", "en", url))
        assertNotEquals(key, mediaStreamCacheKey("u4LUix-BU0s", 141, "mp4a", "en", url))
        assertNotEquals(key, mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "ja", url))
        assertNotEquals(key, mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", url.replace("123", "124")))
        assertNull(mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", "https://media.example/unknown"))
        assertFalse(key.contains("u4LUix") || key.contains("signature"))
    }

    @Test fun cacheInitializationOrDiagnosticFailureDoesNotPreventStreaming() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(response())
            val notADirectory = temporary.newFile()
            VideoMediaCache(context, notADirectory, onEvent = { throw IllegalStateException("test writer") }).use { cache ->
                assertArrayEquals(payload.copyOf(1024), read(cache.factory(upstream(), true), spec(server.url("/media").toString(), "safe-key")))
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun leastRecentFragmentsAreEvictedWithinTheByteBudget() {
        val server = MockWebServer(); server.start()
        val directory = temporary.newFolder()
        try {
            repeat(3) { server.enqueue(response()) }
            VideoMediaCache(context, directory, maxBytes = 2048).use { cache ->
                repeat(3) { index ->
                    read(cache.factory(upstream(), true), spec(server.url("/media-$index").toString(), "fragment-$index"))
                    Thread.sleep(5)
                }
            }
            val database = StandaloneDatabaseProvider(context)
            val disk = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(2048), database)
            try {
                disk.checkInitialization()
                assertTrue(disk.cacheSpace <= 2048)
                assertEquals(0L, disk.getCachedBytes("fragment-0", 0, 1024))
                assertEquals(1024L, disk.getCachedBytes("fragment-2", 0, 1024))
            } finally { disk.release(); database.close() }
        } finally { server.shutdown() }
    }
}
