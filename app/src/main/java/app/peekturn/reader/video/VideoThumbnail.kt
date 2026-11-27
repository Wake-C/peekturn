package app.peekturn.reader.video

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
import androidx.compose.ui.platform.LocalContext
import app.peekturn.reader.PeekturnApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Public artwork only. Older entries can still use the standard YouTube thumbnail for their id. */
internal fun videoThumbnailUrls(videoId: String?, vararg artwork: String?): List<String> = buildList {
    for (url in artwork) {
        if (!url.isNullOrBlank() && url !in this) add(url)
    }
    if (videoId != null && YouTubeUrls.isVideoId(videoId)) {
        val fallback = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        if (fallback !in this) add(fallback)
    }
}

@Composable
internal fun VideoThumbnail(urls: List<String>, durationMs: Long, modifier: Modifier) {
    val fallback = stringResource(R.string.video_ui_thumbnail)
    val image = rememberVideoThumbnail(urls)
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = fallback,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    PeekturnIcons.Play,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (durationMs > 0L) {
            Text(
                formatClock(durationMs),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun rememberVideoThumbnail(urls: List<String>): ImageBitmap? = key(urls) {
    val loader = (LocalContext.current.applicationContext as PeekturnApp).graph.videoThumbnails
    val cached = remember(loader) { loader.peek(urls) }
    val image by produceState<ImageBitmap?>(initialValue = cached, key1 = loader) {
        value = loader.load(urls) ?: cached
    }
    image
}

/** The HTTP cache holds only public artwork and follows the image server's cache directives. */
internal class VideoThumbnails(directory: File) : Closeable {
    private val bitmaps = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val client = OkHttpClient.Builder()
        .cache(Cache(directory, 16L * 1024 * 1024))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun close() { client.cache?.close() }

    fun peek(urls: List<String>): ImageBitmap? = urls.firstNotNullOfOrNull(bitmaps::get)

    suspend fun load(urls: List<String>): ImageBitmap? {
        for (url in urls) {
            currentCoroutineContext().ensureActive()
            bitmaps.get(url)?.let { return it }
            val address = url.toHttpUrlOrNull() ?: continue
            val request = Request.Builder().url(address).build()
            val image = try {
                fetch(request)
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                try { fetch(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()) }
                catch (_: IOException) { null }
            }
            if (image != null) {
                bitmaps.put(url, image)
                return image
            }
        }
        return null
    }

    private suspend fun fetch(request: Request): ImageBitmap? = suspendCancellableCoroutine { result ->
        val call = client.newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val image = runCatching {
                    response.use {
                        if (!it.isSuccessful) null
                        else it.body?.byteStream()?.use(BitmapFactory::decodeStream)?.asImageBitmap()
                    }
                }
                result.resumeWith(image)
            }
        })
    }
}
