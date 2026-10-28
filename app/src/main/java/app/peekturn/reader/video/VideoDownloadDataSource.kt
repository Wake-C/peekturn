package app.peekturn.reader.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

/** Bounded network requests behind one logical media stream, so cache offsets stay unchanged. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoDownloadDataSource private constructor(
    private val rangedFactory: OkHttpDataSource.Factory,
    private val fullFactory: OkHttpDataSource.Factory,
    private val chunkBytes: Long
) : DataSource {
    class Factory(
        client: OkHttpClient,
        headers: Map<String, String>,
        private val chunkBytes: Long = 2L * 1024 * 1024,
        requestTimeoutMs: Long = 60_000
    ) : DataSource.Factory {
        init { require(chunkBytes > 0); require(requestTimeoutMs > 0) }

        private val rangedFactory = OkHttpDataSource.Factory(client.newBuilder()
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 200 && chain.request().header("Range") != null) {
                    // Detect this before OkHttpDataSource skips a potentially large resume offset.
                    response.close()
                    throw RangeIgnoredException()
                }
                response
            }.build()).setDefaultRequestProperties(headers)
        // A server without ranges retains the original stream's idle timeouts, not a per-file deadline.
        private val fullFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)

        override fun createDataSource(): DataSource =
            VideoDownloadDataSource(rangedFactory, fullFactory, chunkBytes)
    }

    private val listeners = linkedSetOf<TransferListener>()
    private var upstream: OkHttpDataSource? = null
    private var original: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var total = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var ranged = false
    private var currentUri: Uri? = null
    private var lastHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        if (listeners.add(transferListener)) upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(original == null) { "Data source already open" }
        original = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        return safely {
            if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) openChunk() else openFull()
            remaining
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        check(original != null) { "Data source is not open" }
        return safely {
            checkInterrupted()
            if (remaining == 0L) return@safely C.RESULT_END_OF_INPUT
            if (ranged && chunkRemaining == 0L) openChunk()
            if (remaining == 0L) return@safely C.RESULT_END_OF_INPUT
            var requested = length.toLong()
            if (remaining != C.LENGTH_UNSET.toLong()) requested = minOf(requested, remaining)
            if (ranged) requested = minOf(requested, chunkRemaining)
            val read = checkNotNull(upstream).read(buffer, offset, requested.toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                if (remaining > 0 || ranged && chunkRemaining > 0) {
                    throw EOFException("Media response ended before its declared length")
                }
            } else {
                position += read
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
                if (ranged) chunkRemaining -= read
            }
            read
        }
    }

    private fun openChunk() {
        checkInterrupted()
        closeUpstream()
        val length = minOf(chunkBytes, Long.MAX_VALUE - position,
            remaining.takeIf { it != C.LENGTH_UNSET.toLong() } ?: Long.MAX_VALUE)
        if (length <= 0) throw IOException("Invalid media range")
        val request = checkNotNull(original).buildUpon().setPosition(position).setLength(length).build()
        try {
            val source = openSource(rangedFactory, request)
            if (source.responseCode == 416 &&
                header("Content-Range")?.trim()?.equals("bytes */$position", ignoreCase = true) == true) {
                if (total != C.LENGTH_UNSET.toLong() && total != position) {
                    throw EOFException("Media length changed")
                }
                remaining = 0
                return
            }
            if (source.responseCode != 206) {
                openFull()
                return
            }
            val range = responseRange()
            validateRange(range, length)
            if (range.total == null) {
                openFull()
                return
            }
            if (total != C.LENGTH_UNSET.toLong() && range.total != total) {
                throw IOException("Media length changed between requests")
            }
            total = range.total
            if (!ranged) {
                remaining = if (remaining == C.LENGTH_UNSET.toLong()) total - position
                    else minOf(remaining, total - position)
            }
            ranged = true
            chunkRemaining = range.end - range.start + 1
        } catch (failure: IOException) {
            if (generateSequence<Throwable>(failure) { it.cause }.any { it is RangeIgnoredException }) {
                openFull()
            } else throw failure
        }
    }

    private fun openFull() {
        checkInterrupted()
        closeUpstream()
        val initial = checkNotNull(original)
        val length = if (initial.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else remaining
        val request = initial.buildUpon().setPosition(position).setLength(length).build()
        val source = fullFactory.createDataSource()
        upstream = source
        listeners.forEach(source::addTransferListener)
        val resolved = source.open(request)
        rememberResponse(source)
        if (source.responseCode == 206) {
            val range = responseRange()
            validateRange(range, if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE - position else length)
            if (range.total == null || range.end != minOf(
                    range.total - 1,
                    if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else position + length - 1
                )) throw IOException("Full media range was not supplied")
            if (total != C.LENGTH_UNSET.toLong() && total != range.total) {
                throw IOException("Media length changed between requests")
            }
            remaining = range.end - position + 1
        } else {
            if (total != C.LENGTH_UNSET.toLong() && resolved != C.LENGTH_UNSET.toLong() &&
                resolved != remaining) throw IOException("Media length changed between requests")
            if (total == C.LENGTH_UNSET.toLong()) remaining = resolved
        }
        ranged = false
        chunkRemaining = 0
    }

    private fun openSource(factory: OkHttpDataSource.Factory, request: DataSpec): OkHttpDataSource =
        factory.createDataSource().also { source ->
            upstream = source
            listeners.forEach(source::addTransferListener)
            source.open(request)
            rememberResponse(source)
        }

    private fun rememberResponse(source: OkHttpDataSource) {
        currentUri = source.uri
        lastHeaders = source.responseHeaders
    }

    private fun validateRange(range: ResponseRange, requestedLength: Long) {
        if (range.start != position || range.end < range.start ||
            range.end - range.start >= requestedLength || range.total?.let { it <= range.end } == true) {
            throw IOException("Media response does not match the requested range")
        }
        header("Content-Length")?.toLongOrNull()?.let {
            if (it != range.end - range.start + 1) throw IOException("Inconsistent media range length")
        }
    }

    private fun responseRange(): ResponseRange {
        val match = RANGE.matchEntire(header("Content-Range").orEmpty().trim())
            ?: throw IOException("Missing or invalid media content range")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val totalText = match.groupValues[3]
        val size = totalText.toLongOrNull()
        if (start == null || end == null || totalText != "*" && size == null) {
            throw IOException("Invalid media content range")
        }
        return ResponseRange(start, end, size)
    }

    private fun header(name: String): String? = lastHeaders.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun getUri(): Uri? = upstream?.uri ?: currentUri
    override fun getResponseHeaders(): Map<String, List<String>> = lastHeaders

    private fun closeUpstream() {
        val source = upstream
        upstream = null
        source?.close()
    }

    override fun close() {
        try { closeUpstream() }
        finally {
            original = null
            currentUri = null
            lastHeaders = emptyMap()
            remaining = C.LENGTH_UNSET.toLong()
            total = C.LENGTH_UNSET.toLong()
            chunkRemaining = 0
            ranged = false
        }
    }

    private inline fun <T> safely(action: () -> T): T = try { action() }
    catch (failure: Exception) {
        try { close() } catch (closing: Exception) { failure.addSuppressed(closing) }
        throw failure
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Media download interrupted")
    }

    private data class ResponseRange(val start: Long, val end: Long, val total: Long?)
    private class RangeIgnoredException : ProtocolException("Media server ignored the range request")

    private companion object {
        val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)", RegexOption.IGNORE_CASE)
    }
}
