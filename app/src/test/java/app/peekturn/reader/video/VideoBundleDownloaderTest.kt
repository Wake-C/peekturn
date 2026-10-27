package app.peekturn.reader.video

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
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

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoBundleDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private data class Progress(val length: Long, val bytes: Long, val percent: Float)

    @Test fun ordinaryIoRetryKeepsTheResolvedSessionAndResumesItsSavedBytes() = withCache { cache ->
        MockWebServer().use { server ->
            val payload = payload(256 * 1024)
            val requests = CopyOnWriteArrayList<RecordedRequest>()
            val requestCount = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return response(request, payload).apply {
                        if (requestCount.getAndIncrement() == 0) {
                            setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        }
                    }
                }
            }
            server.start()
            var preparations = 0
            val failures = mutableListOf<Exception>()
            val events = mutableListOf<String>()
            val progress = mutableListOf<Progress>()
            val downloader = VideoBundleDownloader(cache, client, prepare = {
                preparations++
                // A source without revision metadata would receive a different session key
                // on each resolution. A transport retry must retain the original plan.
                listOf(VideoDownloadPart("session-$preparations",
                    server.url("/media?clen=${payload.size}&signature=private-token").toString(), "audio/mp4"))
            }, removeParts = {}, onEvent = events::add, onFailure = failures::add)

            assertThrows(IOException::class.java) {
                downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }
            }
            val saved = cache.getCachedBytes("session-1", 0, payload.size.toLong())
            assertTrue("The failed connection must retain a real partial download", saved in 1 until payload.size.toLong())
            assertTrue("The failed attempt must not claim completion", progress.none { it.percent >= 100f })
            assertTrue("An intermediate failure must be observable", failures.isNotEmpty())
            val requestsBeforeRetry = requests.size

            downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }

            assertEquals("Do not re-resolve ordinary transfer failures", 1, preparations)
            assertEquals(setOf("session-1"), cache.keys)
            assertTrue("Retry should request the uncached remainder", requests.drop(requestsBeforeRetry).any {
                it.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() == saved
            })
            assertArrayEquals(payload, readOffline(cache, "session-1"))
            assertEquals(payload.size.toLong(), progress.last().bytes)
            assertEquals(100f, progress.last().percent, 0f)
            assertTrue("Signed URLs and tokens must stay out of diagnostics", events.none {
                it.contains("private-token") || it.contains("http://") || it.contains("https://")
            })
        }
    }

    @Test fun aggregateProgressUsesPartByteSizesAndCompletesOnlyAfterAllParts() = withCache { cache ->
        MockWebServer().use { server ->
            val audio = payload(1024)
            val picture = payload(9 * 1024)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    response(request, if (request.requestUrl!!.encodedPath == "/audio") audio else picture)
            }
            server.start()
            val progress = mutableListOf<Progress>()
            val downloader = VideoBundleDownloader(cache, client, prepare = {
                listOf(
                    VideoDownloadPart("audio", server.url("/audio?clen=${audio.size}").toString(), "audio/mp4"),
                    VideoDownloadPart("video", server.url("/video?clen=${picture.size}").toString(), "video/mp4")
                )
            }, removeParts = {})

            downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }

            assertTrue("A 1 KiB audio part out of 10 KiB total is 10%, not 50%", progress.any {
                it.length == 10 * 1024L && it.bytes == 1024L && it.percent in 9.9f..10.1f
            })
            assertTrue("Only the final successful callback can report 100%", progress.dropLast(1).none { it.percent >= 100f })
            assertEquals(Progress(10 * 1024L, 10 * 1024L, 100f), progress.last())
            assertArrayEquals(audio, readOffline(cache, "audio"))
            assertArrayEquals(picture, readOffline(cache, "video"))
        }
    }

    @Test fun unknownPartLengthsStayIndeterminateUntilTheWholeTotalIsKnown() = withCache { cache ->
        MockWebServer().use { server ->
            val audio = payload(1024)
            val picture = payload(9 * 1024)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    response(request, if (request.requestUrl!!.encodedPath == "/audio") audio else picture)
            }
            server.start()
            val progress = mutableListOf<Progress>()
            val downloader = VideoBundleDownloader(cache, client, prepare = {
                listOf(
                    VideoDownloadPart("audio", server.url("/audio").toString(), "audio/mp4"),
                    VideoDownloadPart("video", server.url("/video").toString(), "video/mp4")
                )
            }, removeParts = {})

            downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }

            assertTrue("Unopened parts have unknown lengths; do not invent a total", progress.any {
                it.bytes == audio.size.toLong() && it.percent == C.PERCENTAGE_UNSET.toFloat()
            })
            assertTrue("Equal-part weighting must not report the small audio part as 50%", progress.none {
                it.bytes == audio.size.toLong() && it.percent == 50f
            })
            assertEquals(Progress(10 * 1024L, 10 * 1024L, 100f), progress.last())
        }
    }

    @Test fun rejectedSignedMediaRefreshesOnTheNextAttemptAndThenCompletes() = withCache { cache ->
        MockWebServer().use { server ->
            val payload = payload(4096)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.requestUrl!!.queryParameter("attempt") == "1") {
                        MockResponse().setResponseCode(403)
                    } else response(request, payload)
            }
            server.start()
            var preparations = 0
            val progress = mutableListOf<Progress>()
            val downloader = VideoBundleDownloader(cache, client, prepare = {
                preparations++
                listOf(VideoDownloadPart("media-stable", server.url(
                    "/media?clen=${payload.size}&attempt=$preparations").toString(), "audio/mp4"))
            }, removeParts = {})

            assertThrows(IOException::class.java) { downloader.download(null) }
            assertEquals("The first failure must return to Media3's retry policy", 1, preparations)
            downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }

            assertEquals(2, preparations)
            assertArrayEquals(payload, readOffline(cache, "media-stable"))
            assertEquals(100f, progress.last().percent, 0f)
        }
    }

    @Test fun repeatedSignedMediaRejectionHasABoundedSourceRefreshBudget() = withCache { cache ->
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
            }
            server.start()
            var preparations = 0
            val progress = mutableListOf<Progress>()
            val downloader = VideoBundleDownloader(cache, client, prepare = {
                preparations++
                listOf(VideoDownloadPart("session-$preparations", server.url(
                    "/media?clen=4096&attempt=$preparations").toString(), "audio/mp4"))
            }, removeParts = {})

            repeat(6) {
                assertThrows(Exception::class.java) {
                    downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }
                }
            }

            assertEquals("Initial resolution plus at most two refreshes", 3, preparations)
            assertTrue("Repeated rejection cannot become a completed download", progress.none { it.percent >= 100f })
            assertEquals(0L, cache.cacheSpace)
        }
    }

    @Test fun cancellationDuringPreparationStopsWithoutStartingMediaOrClaimingCompletion() = withCache { cache ->
        val entered = CountDownLatch(1)
        val progress = CopyOnWriteArrayList<Progress>()
        val downloader = VideoBundleDownloader(cache, client, prepare = {
            entered.countDown()
            awaitCancellation()
        }, removeParts = {})
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> {
                try {
                    downloader.download { length, bytes, percent -> progress += Progress(length, bytes, percent) }
                    null
                } catch (failure: Throwable) { failure }
            }
            assertTrue("Preparation must have started", entered.await(3, TimeUnit.SECONDS))
            downloader.cancel()
            assertNotNull("Cancellation must not return successful completion", result.get(3, TimeUnit.SECONDS))
            assertTrue(progress.none { it.percent >= 100f })
            assertEquals(0L, cache.cacheSpace)
        } finally {
            downloader.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun cancellationDuringTransferNeverReportsCompletion() = withCache { cache ->
        MockWebServer().use { server ->
            val payload = payload(256 * 1024)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = response(request, payload)
            }
            server.start()
            val progress = CopyOnWriteArrayList<Progress>()
            val downloader = VideoBundleDownloader(cache, client, prepare = {
                listOf(VideoDownloadPart("cancelled", server.url("/media?clen=${payload.size}").toString(), "audio/mp4"))
            }, removeParts = {})
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> {
                    try {
                        downloader.download { length, bytes, percent ->
                            progress += Progress(length, bytes, percent)
                            if (bytes > 0) downloader.cancel()
                        }
                        null
                    } catch (failure: Throwable) { failure }
                }

                assertNotNull("A cancelled transfer must not report success", result.get(5, TimeUnit.SECONDS))
                assertTrue(progress.any { it.bytes > 0 })
                assertTrue(progress.none { it.percent >= 100f })
                assertFalse(cache.isCached("cancelled", 0, payload.size.toLong()))
            } finally {
                downloader.cancel()
                executor.shutdownNow()
            }
        }
    }

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun response(request: RecordedRequest, payload: ByteArray): MockResponse {
        val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toLong() ?: 0L
        if (start >= payload.size) {
            return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${payload.size}")
        }
        val end = (range?.groupValues?.get(2)?.toLongOrNull() ?: (payload.size - 1).toLong())
            .coerceAtMost((payload.size - 1).toLong())
        return MockResponse()
            .setResponseCode(if (range == null) 200 else 206)
            .setHeader("Content-Type", "application/octet-stream")
            .setHeader("Accept-Ranges", "bytes")
            .setBody(Buffer().write(payload.copyOfRange(start.toInt(), end.toInt() + 1)))
            .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${payload.size}") }
    }

    private fun readOffline(cache: Cache, key: String): ByteArray {
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()
        val output = ByteArrayOutputStream()
        try {
            source.open(DataSpec.Builder().setUri("peekturn-offline://media/$key").setKey(key).build())
            val buffer = ByteArray(4096)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
        } finally { source.close() }
        assertEquals(output.size().toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(key)))
        return output.toByteArray()
    }

    private fun withCache(block: (Cache) -> Unit) {
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor(), database)
        try { block(cache) }
        finally { cache.release(); database.close() }
    }
}
