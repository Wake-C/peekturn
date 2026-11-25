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
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import com.google.gson.Gson
import java.io.File
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
    private fun read(factory: DataSource.Factory, dataSpec: DataSpec, expectedLength: Long? = null): ByteArray {
        val source = factory.createDataSource()
        try {
            val length = source.open(dataSpec)
            if (expectedLength != null) assertEquals(expectedLength, length)
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

    @Test fun progressivePlaybackKeepsReadingWhenTheServerSlowsOpenEndedRanges() {
        val media = ByteArray(2 * 1024 * 1024 + 65_536) { (it % 251).toByte() }
        val seek = 31_007
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = requireNotNull(request.getHeader("Range")).removePrefix("bytes=")
                val start = range.substringBefore('-').toInt()
                val requestedEnd = range.substringAfter('-').toLongOrNull()
                val end = minOf(requestedEnd ?: media.lastIndex.toLong(), media.lastIndex.toLong()).toInt()
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${media.size}")
                    .setBody(Buffer().write(media, start, end - start + 1))
                    .apply { if (requestedEnd == null) throttleBody(16_384, 3, TimeUnit.SECONDS) }
            }
        }
        server.start()
        val deadlines = mutableListOf<Long>()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { deadlines += call.timeout().timeoutNanos() }
            }).build()
        try {
            val spec = DataSpec.Builder().setUri(server.url("/media").toString()).setKey("opaque-media-key")
                .setPosition(seek.toLong())
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
                .build()
            VideoMediaCache(context, temporary.newFolder()).use { cache ->
                val playback = cache.progressiveFactory(client, emptyMap(), cacheable = true)
                assertArrayEquals(media.copyOfRange(seek, media.size), read(playback, spec))
                val networkRequests = server.requestCount
                assertTrue("The cold seek must span multiple network requests", networkRequests > 1)
                assertTrue("Playback may pause between reads", deadlines.all { it == 0L })
                val replay = spec.buildUpon().setPosition((seek + 113).toLong()).setLength(1024).build()
                assertArrayEquals(media.copyOfRange(seek + 113, seek + 113 + 1024), read(playback, replay))
                assertEquals(networkRequests, server.requestCount)
            }
        } finally { server.shutdown() }
    }

    @Test fun aColdSeekCanReadAPartialResponseWithoutAFileLength() {
        val seek = 113
        val suffix = payload.copyOfRange(seek, payload.size)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Buffer().write(suffix)
                val response = MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $seek-${payload.lastIndex}/*")
                return if (request.path == "/chunked") response.setChunkedBody(body, 127)
                    else response.setBody(body)
            }
        }
        server.start()
        try {
            VideoMediaCache(context, temporary.newFolder()).use { cache ->
                val factory = cache.progressiveFactory(OkHttpClient(), emptyMap(), cacheable = true)
                fun readSeek(path: String, requested: Long, expectedLength: Long) {
                    val spec = DataSpec.Builder().setUri(server.url(path).toString()).setKey("$path-$requested")
                        .setPosition(seek.toLong()).setLength(requested)
                        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
                        .build()
                    assertArrayEquals(suffix, read(factory, spec, expectedLength))
                }
                readSeek("/fixed", -1, suffix.size.toLong())
                readSeek("/chunked", -1, -1)
                // Media3 reports the requested length here and returns EOF at the actual last byte.
                readSeek("/chunked", 8192, 8192)
            }
        } finally { server.shutdown() }
    }

    @Test fun progressiveReadPastEofReturnsTheActualTailWithOrWithoutRangeSupport() {
        val seek = 113
        val suffix = payload.copyOfRange(seek, payload.size)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/whole") return response()
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $seek-${payload.lastIndex}/${payload.size}")
                    .setBody(Buffer().write(suffix))
            }
        }
        server.start()
        try {
            VideoMediaCache(context, temporary.newFolder()).use { cache ->
                for (cacheable in listOf(false, true)) {
                    val factory = cache.progressiveFactory(OkHttpClient(), emptyMap(), cacheable)
                    for (path in listOf("/whole", "/range")) {
                        val spec = spec(server.url(path).toString(), "$path-$cacheable", 8192)
                            .buildUpon().setPosition(seek.toLong()).build()
                        assertArrayEquals(suffix, read(factory, spec, expectedLength = 8192))
                    }
                }
            }
        } finally { server.shutdown() }
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

    @Test fun streamsWithoutRevisionMetadataStillReplayFromCacheWithinTheirResolvedSession() {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(response()) }
            val url = server.url("/media?signature=temporary").toString()
            val sessionKey = mediaStreamCacheKey("u4LUix-BU0s", 18, "avc", "", url)
            VideoMediaCache(context, temporary.newFolder()).use { cache ->
                val factory = cache.factory(upstream(), true)
                assertArrayEquals(payload.copyOf(1024), read(factory, spec(url, sessionKey)))
                assertArrayEquals(payload.copyOf(1024), read(factory, spec(url, sessionKey)))
                assertEquals(1, server.requestCount)
                val nextSessionKey = mediaStreamCacheKey("u4LUix-BU0s", 18, "avc", "", url)
                assertNotEquals(sessionKey, nextSessionKey)
                assertArrayEquals(payload.copyOf(1024), read(factory, spec(url, nextSessionKey)))
                assertEquals(2, server.requestCount)
            }
        } finally { server.shutdown() }
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
        val session = mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", "https://media.example/unknown")
        assertTrue(session.startsWith("session-"))
        assertNotEquals(session, mediaStreamCacheKey("u4LUix-BU0s", 140, "mp4a", "en", "https://media.example/unknown"))
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

    /** Opt-in real VOD check with the same unknown-length flags as ProgressiveMediaPeriod. */
    @Test fun liveMuxedStreamCachesARepeatedPrefixWithoutPersistingItsSignedUrl() = runBlocking {
        val id = System.getenv("PEEKTURN_MEDIA_PROBE_ID")
        val output = System.getenv("PEEKTURN_MEDIA_PROBE_DIR")
        assumeTrue(!id.isNullOrBlank() && !output.isNullOrBlank())
        val resolved = YouTubeSource().resolve(id!!)
        val source = resolved.qualities.first { it.height == 360 && it.source.audioUrl == null }.source
        val key = source.videoCacheKey!!
        val events = mutableListOf<String>()
        val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val directory = File(output!!, "muxed-session-cache")
        VideoMediaCache(context, directory, onEvent = { events += it }).use { cache ->
            val factory = cache.progressiveFactory(client, source.headers, cacheable = true)
            fun prefix(): Pair<ByteArray, Long> {
                val data = factory.createDataSource()
                try {
                    val length = data.open(DataSpec.Builder().setUri(source.videoUrl).setKey(key)
                        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN).build())
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (result.size() < 16384) {
                        val count = data.read(buffer, 0, minOf(buffer.size, 16384 - result.size()))
                        if (count < 0) break
                        result.write(buffer, 0, count)
                    }
                    return result.toByteArray() to length
                } finally { data.close() }
            }
            val first = prefix()
            val opens = events.count { it == "media-network-open" }
            val second = prefix()
            File(output, "muxed-live-check.json").writeText(Gson().toJson(mapOf(
                "height" to 360, "cacheScope" to if (key.startsWith("session-")) "session" else "content",
                "declaredLength" to first.second, "prefixBytes" to first.first.size,
                "firstNetworkOpens" to opens, "totalNetworkOpens" to events.count { it == "media-network-open" },
                "cacheReadObserved" to events.any { it.startsWith("media-cache-read-bytes:") }
            )))
            assertEquals(16384, first.first.size)
            assertArrayEquals(first.first, second.first)
            assertEquals(opens, events.count { it == "media-network-open" })
            assertTrue(events.any { it.startsWith("media-cache-read-bytes:") })
        }
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
