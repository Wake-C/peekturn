package app.peekturn.reader.video

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** URLs belong to this in-memory attempt; only opaque keys enter the download cache. */
internal data class VideoDownloadPart(val key: String, val url: String, val mime: String)

/** Media3 owns retries and the service. This adapter preserves a bundle across those retries. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoBundleDownloader(
    private val cache: Cache,
    private val client: OkHttpClient,
    private val prepare: suspend () -> List<VideoDownloadPart>,
    private val removeParts: () -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val onFailure: (Exception) -> Unit = {}
) : Downloader {
    @Volatile private var cancelled = false
    @Volatile private var resolving: Job? = null
    @Volatile private var current: ProgressiveDownloader? = null
    private var parts: List<VideoDownloadPart>? = null
    private var refreshes = 0
    private var attempts = 0

    override fun download(listener: Downloader.ProgressListener?) {
        checkCancelled()
        event("attempt:${++attempts}")
        var progress: BundleProgress? = null
        try {
            val plan = parts ?: resolve().also { parts = it }
            checkCancelled()
            val bundleProgress = BundleProgress(cache, plan, listener)
            progress = bundleProgress
            bundleProgress.publish()
            var lastProgressAt = System.nanoTime()
            plan.forEachIndexed { index, part ->
                checkCancelled()
                event("part:${index + 1}:of:${plan.size}")
                val factory = CacheDataSource.Factory().setCache(cache)
                    .setCacheKeyFactory { spec -> requireNotNull(spec.key) }
                    .setUpstreamDataSourceFactory(VideoDownloadDataSource.Factory(client, playbackHeaders(part.url)))
                    .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
                val media = MediaItem.Builder().setUri(part.url).setMimeType(part.mime)
                    .setCustomCacheKey(part.key).build()
                val downloader = ProgressiveDownloader(media, factory)
                current = downloader
                checkCancelled()
                downloader.download { length, bytes, _ ->
                    bundleProgress.update(index, length, bytes)
                    val now = System.nanoTime()
                    if (now - lastProgressAt >= TimeUnit.SECONDS.toNanos(10)) {
                        event("bytes:${bundleProgress.bytes}:total:${bundleProgress.length}")
                        lastProgressAt = now
                    }
                }
                checkCancelled()
                // A successful read is not enough if a truncated body or cache close lost bytes.
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(part.key))
                if (length <= 0 || !cache.isCached(part.key, 0, length)) {
                    throw IOException("Downloaded media is incomplete")
                }
                bundleProgress.update(index, length, length)
            }
            checkCancelled()
            listener?.onProgress(bundleProgress.length, bundleProgress.bytes, 100f)
            event("complete:bytes:${bundleProgress.bytes}")
        } catch (cancelled: CancellationException) {
            throw InterruptedException()
        } catch (failure: IOException) {
            checkCancelled()
            // Ordinary connection failures keep the same plan/key and resume cached bytes.
            // Expired URLs need a new extraction, bounded because a new identity can reset
            // Media3's consecutive-error counter. Never merge unverified content identities.
            if (failure is HttpDataSource.InvalidResponseCodeException &&
                failure.responseCode in setOf(401, 403, 410) && parts != null && refreshes < 2) {
                parts = null
                refreshes++
                event("source-refresh:$refreshes")
            }
            progress?.refreshFromCache()
            progress?.publish()
            event("retry:bytes:${progress?.bytes ?: 0}")
            try { onFailure(failure) } catch (_: Exception) { }
            throw failure
        } finally {
            current = null
        }
    }

    private fun resolve(): List<VideoDownloadPart> {
        event("resolving")
        val job = Job()
        resolving = job
        try {
            if (cancelled) job.cancel()
            return runBlocking(job) {
                val plan = prepare()
                ensureActive()
                if (plan.isEmpty()) throw IOException("No media to download")
                require(plan.map { it.key }.distinct().size == plan.size)
                plan
            }
        } catch (failure: VideoFailure) {
            if (failure.kind == VideoFailureKind.Network) throw IOException("Source connection failed", failure)
            throw failure
        } finally {
            resolving = null
            job.cancel()
        }
    }

    override fun cancel() {
        cancelled = true
        resolving?.cancel()
        current?.cancel()
    }

    override fun remove() = removeParts()

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun event(value: String) {
        try { onEvent(value) } catch (_: Exception) { }
    }

    private class BundleProgress(
        private val cache: Cache,
        private val parts: List<VideoDownloadPart>,
        private val listener: Downloader.ProgressListener?
    ) {
        private val lengths = LongArray(parts.size) { index ->
            ContentMetadata.getContentLength(cache.getContentMetadata(parts[index].key)).takeIf { it > 0 }
                ?: parts[index].url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
                ?: C.LENGTH_UNSET.toLong()
        }
        private val cached = LongArray(parts.size)
        val bytes: Long get() = cached.sum()
        val length: Long get() = if (lengths.all { it > 0 }) lengths.sum() else C.LENGTH_UNSET.toLong()

        init { refreshFromCache() }

        fun refreshFromCache() {
            parts.forEachIndexed { index, part ->
                ContentMetadata.getContentLength(cache.getContentMetadata(part.key)).takeIf { it > 0 }
                    ?.let { lengths[index] = it }
                cached[index] = cache.getCachedBytes(part.key, 0, lengths[index])
            }
        }

        fun update(index: Int, length: Long, bytes: Long) {
            if (length > 0) lengths[index] = length
            cached[index] = bytes.coerceAtLeast(0)
            publish()
        }

        fun publish() {
            val total = length
            val percent = if (total > 0) (bytes.toDouble() * 100 / total).coerceIn(0.0, 99.99).toFloat()
                else C.PERCENTAGE_UNSET.toFloat()
            listener?.onProgress(total, bytes, percent)
        }
    }
}
