package app.peekturn.reader.video

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

// Shared disk budget across all videos and audio streams, not a per-video or RAM allocation.
internal const val VIDEO_MEDIA_CACHE_BYTES = 1024L * 1024 * 1024
internal const val VIDEO_BACK_BUFFER_MS = 30_000

@androidx.annotation.OptIn(UnstableApi::class)
internal fun videoLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder().setBackBuffer(VIDEO_BACK_BUFFER_MS, true).build()

/** Content identity only. Expiring URLs, signatures and access tokens must never become disk keys. */
internal fun mediaStreamCacheKey(videoId: String, formatId: Int, codec: String, trackId: String, url: String): String? {
    if (!YouTubeUrls.isVideoId(videoId) || formatId <= 0) return null
    val parsed = url.toHttpUrlOrNull() ?: return null
    val length = parsed.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val modified = parsed.queryParameter("lmt")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val identity = listOf("youtube-media-v1", videoId, formatId, codec, trackId, length, modified).joinToString("\n")
    return "media-" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** One bounded, process-owned Media3 fragment cache. This is not a downloader or offline library. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoMediaCache(
    context: Context,
    private val directory: File = File(context.cacheDir, "video-media"),
    private val maxBytes: Long = VIDEO_MEDIA_CACHE_BYTES,
    private val onFailure: (Throwable) -> Unit = {},
    private val onEvent: (String) -> Unit = {}
) : AutoCloseable {
    private val database = StandaloneDatabaseProvider(context.applicationContext)
    private var opened: SimpleCache? = null
    private var privateCache: Cache? = null

    @Synchronized private fun cache(): Cache {
        privateCache?.let { return it }
        val disk = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(maxBytes), database)
        opened = disk
        try { disk.checkInitialization() }
        catch (failure: Exception) { disk.release(); opened = null; throw failure }
        return object : Cache by disk {
            override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
                // CacheDataSource otherwise persists the redirect's full signed URL even with a
                // custom cache key. Keep only content metadata, never a session redirect address.
                mutations.remove(ContentMetadata.KEY_REDIRECTED_URI)
                disk.applyContentMetadataMutations(key, mutations)
            }
        }.also { privateCache = it }
    }

    fun factory(upstream: DataSource.Factory, cacheable: Boolean): DataSource.Factory = DataSource.Factory {
        val source = if (cacheable) {
            try {
                val contentCache = cache()
                CacheDataSource.Factory().setCache(contentCache).setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory { spec -> requireNotNull(spec.key) { "Missing media content identity" } }
                    .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(contentCache).setFragmentSize(2L * 1024 * 1024))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .setEventListener(object : CacheDataSource.EventListener {
                        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                            if (cachedBytesRead > 0) event("media-cache-read-bytes:$cachedBytesRead")
                        }
                        override fun onCacheIgnored(reason: Int) { event("media-cache-bypassed:$reason") }
                    }).createDataSource()
            } catch (failure: Exception) {
                try { onFailure(failure) } catch (_: Exception) { }
                event("media-cache-unavailable")
                upstream.createDataSource()
            }
        } else upstream.createDataSource()
        source.addTransferListener(object : TransferListener {
            private var bytes = 0L
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                if (isNetwork) { bytes = 0L; event("media-network-open") }
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) bytes += bytesTransferred
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                if (isNetwork) event("media-network-read-bytes:$bytes")
            }
        })
        source
    }

    private fun event(value: String) {
        try { onEvent(value) } catch (_: Exception) { /* Diagnostics never interrupt media reads. */ }
    }

    @Synchronized override fun close() {
        opened?.release()
        opened = null
        privateCache = null
        database.close()
    }
}
