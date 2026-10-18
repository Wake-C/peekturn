package app.peekturn.reader.webimport

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Reassembles the base64 slices the page pushes over the bridge. It is deliberately strict: a
 * missing, duplicated or reordered slice means the archive would be corrupt, so the result is
 * refused rather than written and imported.
 */
internal class EpubChunkAssembler(private val maxBytes: Int = MAX_EPUB_BYTES) {
    private val bytes = ByteArrayOutputStream()
    private var nextIndex = 0
    private var damaged = false

    fun push(index: Int, chunk: String): Boolean {
        if (damaged) return false
        if (index != nextIndex) return damage()
        val decoded = runCatching { Base64.getDecoder().decode(chunk) }.getOrNull() ?: return damage()
        if (bytes.size() + decoded.size > maxBytes) return damage()
        bytes.write(decoded)
        nextIndex++
        return true
    }

    /** Starts a new transfer; a retry after a failure must not inherit the old indices. */
    fun reset() {
        bytes.reset()
        nextIndex = 0
        damaged = false
    }

    fun finish(): ByteArray? {
        if (damaged || nextIndex == 0 || bytes.size() == 0) return null
        return bytes.toByteArray()
    }

    private fun damage(): Boolean {
        damaged = true
        return false
    }

    companion object {
        internal const val MAX_EPUB_BYTES = 64 * 1024 * 1024
    }
}
