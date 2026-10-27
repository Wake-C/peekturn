package app.peekturn.reader.video

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoDownloadDataSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(10_123) { (it % 251).toByte() }
    private val key = "download-content-identity"
    private val chunkBytes = 1_024L

    private inner class Fixture : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        private val context = ApplicationProvider.getApplicationContext<Application>()
        private val database = StandaloneDatabaseProvider(context)
        private val disk = SimpleCache(temporary.newFolder(), NoOpCacheEvictor(), database)
        val cache = object : Cache by disk {
            override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
                mutations.remove(ContentMetadata.KEY_REDIRECTED_URI)
                disk.applyContentMetadataMutations(key, mutations)
            }
        }
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
        val url get() = server.url("/media?signature=private-test-token").toString()

        fun respond(block: (RecordedRequest) -> MockResponse) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return block(request)
                }
            }
        }

        fun upstream(timeoutMs: Long = 5_000) = VideoDownloadDataSource.Factory(
            client, mapOf("User-Agent" to "Peekturn transfer test"), chunkBytes, timeoutMs
        )

        fun downloader(url: String = this.url, timeoutMs: Long = 5_000): ProgressiveDownloader {
            val factory = CacheDataSource.Factory().setCache(cache)
                .setCacheKeyFactory { spec -> requireNotNull(spec.key) }
                .setUpstreamDataSourceFactory(upstream(timeoutMs))
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            return ProgressiveDownloader(
                MediaItem.Builder().setUri(url).setCustomCacheKey(key).build(), factory
            )
        }

        fun offline(): DataSource = CacheDataSource.Factory().setCache(cache)
            .setCacheKeyFactory { spec -> requireNotNull(spec.key) }
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
            .createDataSource()

        override fun close() {
            try { server.shutdown() }
            finally {
                disk.release()
                database.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun requestedRange(request: RecordedRequest): LongRange {
        val header = requireNotNull(request.getHeader("Range"))
        val match = requireNotNull(Regex("bytes=(\\d+)-(\\d*)").matchEntire(header))
        val first = match.groupValues[1].toLong()
        val last = match.groupValues[2].toLongOrNull() ?: (payload.size - 1).toLong()
        return first..minOf(last, (payload.size - 1).toLong())
    }

    private fun rangeResponse(request: RecordedRequest): MockResponse {
        val range = requestedRange(request)
        return MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes ${range.first}-${range.last}/${payload.size}")
            .setBody(Buffer().write(payload, range.first.toInt(), (range.last - range.first + 1).toInt()))
    }

    private fun wholeResponse() = MockResponse().setBody(Buffer().write(payload))

    private fun spec(url: String, position: Long = 0, length: Long = -1): DataSpec = DataSpec.Builder()
        .setUri(url).setKey(key).setPosition(position).setLength(length).build()

    private fun read(source: DataSource, spec: DataSpec): Pair<Long, ByteArray> {
        try {
            val length = source.open(spec)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(317)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            return length to output.toByteArray()
        } finally { source.close() }
    }

    @Test fun boundedRangesProduceACompleteOfflineFileWithTheOriginalLength() {
        Fixture().use { fixture ->
            fixture.respond(::rangeResponse)
            var downloadedBytes = 0L
            var percentage = -1f
            fixture.downloader().download { _, bytes, percent ->
                downloadedBytes = bytes
                percentage = percent
            }

            assertEquals(payload.size.toLong(), downloadedBytes)
            assertEquals(100f, percentage, 0f)
            assertEquals(payload.size.toLong(), fixture.cache.getCachedBytes(key, 0, payload.size.toLong()))
            assertEquals(payload.size.toLong(), fixture.cache.getContentMetadata(key)
                .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L))
            assertNull(fixture.cache.getContentMetadata(key)
                .get(ContentMetadata.KEY_REDIRECTED_URI, null as String?))

            val expectedRanges = (0 until payload.size step chunkBytes.toInt()).map { start ->
                "bytes=$start-${minOf(start + chunkBytes - 1, payload.size - 1L)}"
            }
            assertEquals(expectedRanges, fixture.requests.map { it.getHeader("Range") })
            assertTrue(fixture.requests.all { it.getHeader("User-Agent") == "Peekturn transfer test" })

            val requestCount = fixture.server.requestCount
            val offline = read(fixture.offline(), spec("peekturn-offline://audio"))
            assertEquals(payload.size.toLong(), offline.first)
            assertArrayEquals(payload, offline.second)
            assertEquals(requestCount, fixture.server.requestCount)
        }
    }

    @Test fun interruptedBodyKeepsSavedBytesAndResumesWithTheSameContentKey() {
        Fixture().use { fixture ->
            val failBody = AtomicBoolean(true)
            fixture.respond { request ->
                rangeResponse(request).apply {
                    if (failBody.get() && requestedRange(request).first >= chunkBytes) {
                        setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                }
            }
            assertThrows(IOException::class.java) { fixture.downloader().download(null) }
            val savedPrefix = fixture.cache.getCachedLength(key, 0, payload.size.toLong())
            assertTrue("The first complete range must survive the failed transfer", savedPrefix >= chunkBytes)
            assertTrue(savedPrefix < payload.size)
            val requestsBeforeRetry = fixture.requests.size

            failBody.set(false)
            fixture.downloader(fixture.url.replace("private-test-token", "renewed-test-token")).download(null)

            assertEquals(savedPrefix, requestedRange(fixture.requests[requestsBeforeRetry]).first)
            assertArrayEquals(payload, read(fixture.offline(), spec("peekturn-offline://audio")).second)
            assertEquals(payload.size.toLong(), fixture.cache.getCachedBytes(key, 0, payload.size.toLong()))
        }
    }

    @Test fun aServerThatNeverRespondsFailsWithinTheConfiguredRequestDeadline() {
        Fixture().use { fixture ->
            fixture.respond { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            val source = fixture.upstream(timeoutMs = 200).createDataSource()
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<Long> { source.open(spec(fixture.url)) }
            try {
                assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                // This deadline is shorter than the base client's ten-second read timeout.
                val failure = assertThrows(ExecutionException::class.java) {
                    future.get(3, TimeUnit.SECONDS)
                }
                assertTrue("A stalled request must fail as an I/O error", failure.cause is IOException)
            } finally {
                future.cancel(true)
                source.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun cancellationEndsADownloadThatIsWaitingForResponseHeaders() {
        Fixture().use { fixture ->
            fixture.respond { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            val downloader = fixture.downloader(timeoutMs = 60_000)
            val finished = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit {
                try { downloader.download(null) }
                finally { finished.countDown() }
            }
            try {
                assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                downloader.cancel()
                assertTrue("Cancellation must release the download worker", finished.await(2, TimeUnit.SECONDS))
                try { future.get(2, TimeUnit.SECONDS) }
                catch (failure: ExecutionException) {
                    assertTrue(failure.cause is InterruptedException || failure.cause is IOException)
                }
                assertEquals(0L, fixture.cache.getCachedBytes(key, 0, payload.size.toLong()))
            } finally {
                downloader.cancel()
                future.cancel(true)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun anIncorrectContentRangeOffsetCannotBecomeACompletedDownload() {
        Fixture().use { fixture ->
            fixture.respond { request ->
                val range = requestedRange(request)
                rangeResponse(request).setHeader("Content-Range",
                    "bytes ${range.first + 1}-${range.last + 1}/${payload.size}")
            }
            assertThrows(IOException::class.java) { fixture.downloader().download(null) }
            assertEquals(0L, fixture.cache.getCachedBytes(key, 0, payload.size.toLong()))
        }
    }

    @Test fun aChangedTotalBetweenRangesCannotSilentlyTruncateTheDownload() {
        Fixture().use { fixture ->
            fixture.respond { request ->
                val range = requestedRange(request)
                rangeResponse(request).apply {
                    if (range.first > 0) setHeader("Content-Range",
                        "bytes ${range.first}-${range.last}/${payload.size + 1}")
                }
            }
            assertThrows(IOException::class.java) { fixture.downloader().download(null) }
            val cached = fixture.cache.getCachedBytes(key, 0, payload.size.toLong())
            assertTrue(cached >= chunkBytes)
            assertTrue(cached < payload.size)
        }
    }

    @Test fun aServerIgnoringRangeStillProducesTheEntireOfflineFile() {
        Fixture().use { fixture ->
            fixture.respond { wholeResponse() }
            fixture.downloader().download(null)
            assertEquals(2, fixture.requests.size)
            assertEquals("bytes=0-1023", fixture.requests[0].getHeader("Range"))
            assertNull(fixture.requests[1].getHeader("Range"))
            assertArrayEquals(payload, read(fixture.offline(), spec("peekturn-offline://audio")).second)
        }
    }

    @Test fun ignoringRangePreservesTheOriginalNonzeroOffsetAndRequestedLength() {
        Fixture().use { fixture ->
            fixture.respond { wholeResponse() }
            val result = read(fixture.upstream().createDataSource(), spec(fixture.url, 1_307, 1_900))
            assertEquals(1_900L, result.first)
            assertArrayEquals(payload.copyOfRange(1_307, 3_207), result.second)
            assertEquals(listOf("bytes=1307-2330", "bytes=1307-3206"),
                fixture.requests.map { it.getHeader("Range") })
        }
    }

    @Test fun boundedRangeReadsPreserveTheOriginalNonzeroOffsetAndRequestedLength() {
        Fixture().use { fixture ->
            fixture.respond(::rangeResponse)
            val result = read(fixture.upstream().createDataSource(), spec(fixture.url, 1_307, 1_900))
            assertEquals(1_900L, result.first)
            assertArrayEquals(payload.copyOfRange(1_307, 3_207), result.second)
            assertEquals(listOf("bytes=1307-2330", "bytes=2331-3206"),
                fixture.requests.map { it.getHeader("Range") })
        }
    }
}
