package app.margin.reader.narration

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The most sentence audio the app keeps on disk; the least recently used clips make room. */
internal const val NARRATION_CACHE_BYTES = 4L * 1024 * 1024 * 1024

/** One sentence's audio cannot exceed this, so a malformed segment list cannot fill the cache. */
internal const val NARRATION_MAX_SENTENCE_AUDIO_BYTES = 16L * 1024 * 1024

internal val sentenceAudioKeyPattern = Regex("[0-9a-f]{64}")

/**
 * Persistent, app-private sentence audio. A name is the opaque synthesis key; no book text reaches
 * a filename or a log. Writes are validated complete MP3 frame sequences and land with an atomic
 * rename, so a killed process can leave a temporary file but never a partial playable clip.
 *
 * This store belongs to continuous narration only. Popup word and example pronunciation keeps its
 * existing memory-only lifetime and never reads or writes here.
 */
internal class SentenceAudioCache(
    private val directory: File,
    private val maxBytes: Long = NARRATION_CACHE_BYTES
) {
    private val mutex = Mutex()
    private val generation = AtomicLong(0L)

    /**
     * Total bytes of `.mp3` clips, initialized once from the directory and then maintained under
     * [mutex]. A cache hit or a normal write below the quota never enumerates the directory.
     */
    private var usageBytes: Long? = null

    init {
        directory.mkdirs()
    }

    /** The cache-wide clear generation; a synthesis begun before a clear cannot write afterward. */
    fun epoch(): Long = generation.get()

    /** A complete, previously synthesized clip, refreshed as most recently used. */
    suspend fun get(key: String): File? = withContext(Dispatchers.IO) {
        if (!sentenceAudioKeyPattern.matches(key)) return@withContext null
        mutex.withLock {
            ensureAccounting()
            val file = clip(key)
            if (!file.isFile) return@withLock null
            val length = file.length()
            if (length <= 0L) return@withLock null
            if (length > NARRATION_MAX_SENTENCE_AUDIO_BYTES) {
                file.delete()
                usageBytes = null
                return@withLock null
            }
            // The write was validated, but a private file can still be damaged by the device. A
            // damaged clip is removed rather than decoded as a shorter sentence.
            val valid = runCatching { Mp3Audio.complete(file.readBytes()) }.getOrDefault(false)
            if (!valid) {
                file.delete()
                // The file's counted size may no longer match its current size, so recompute once
                // on the next operation rather than subtracting a stale length.
                usageBytes = null
                return@withLock null
            }
            file.setLastModified(System.currentTimeMillis())
            file
        }
    }

    /** Writes [audio] atomically when it is a complete frame sequence; null means nothing was kept. */
    suspend fun put(
        key: String,
        audio: ByteArray,
        expectedEpoch: Long = generation.get()
    ): File? = withContext(Dispatchers.IO) {
        if (!sentenceAudioKeyPattern.matches(key)) return@withContext null
        if (
            audio.isEmpty() ||
            audio.size.toLong() > NARRATION_MAX_SENTENCE_AUDIO_BYTES ||
            audio.size.toLong() > maxBytes ||
            !Mp3Audio.complete(audio)
        ) {
            return@withContext null
        }
        mutex.withLock {
            if (expectedEpoch != generation.get()) return@withLock null
            ensureAccounting()
            directory.mkdirs()
            val target = clip(key)
            val previousLength = if (target.isFile) target.length() else 0L
            val temporary = File(directory, "$key.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(audio)
                    output.flush()
                    output.fd.sync()
                }
                moveAtomically(temporary, target)
            } catch (_: Exception) {
                temporary.delete()
                return@withLock null
            }
            changeUsage(audio.size.toLong() - previousLength)
            target.setLastModified(System.currentTimeMillis())
            evictIfNeeded(protected = target)
            target
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        mutex.withLock { ensureAccounting() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        generation.incrementAndGet()
        mutex.withLock {
            clips().forEach { it.delete() }
            directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
            // A failed delete is rare, but the reported usage must match what remains on disk.
            usageBytes = clips().sumOf { it.length() }
        }
    }

    private fun clip(key: String) = File(directory, "$key.mp3")

    private fun clips(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".mp3") }?.toList().orEmpty()

    /** First use scans the directory once; every later call is O(1). Caller holds [mutex]. */
    private fun ensureAccounting(): Long {
        usageBytes?.let { return it }
        return clips().sumOf { it.length() }.also { usageBytes = it }
    }

    private fun changeUsage(delta: Long) {
        val current = usageBytes ?: 0L
        usageBytes = when {
            delta > 0L && current > Long.MAX_VALUE - delta -> Long.MAX_VALUE
            else -> (current + delta).coerceAtLeast(0L)
        }
    }

    /**
     * Oldest access first, with the name breaking timestamp ties, until the cache is within its
     * bound. Only a write that crossed the bound enumerates the directory, and it trims to 95% so
     * the next several sentence writes do not scan again. [protected] is never evicted: the clip
     * just written must remain readable even when it is the only entry and the bound is tight.
     */
    private fun evictIfNeeded(protected: File) {
        var total = usageBytes ?: return
        if (total <= maxBytes) return
        val headroom = (maxBytes / 20).coerceAtLeast(1L)
        val target = (maxBytes - headroom).coerceAtLeast(0L)
        val clips = clips().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        for (clip in clips) {
            if (total <= target) break
            if (clip == protected) continue
            val length = clip.length()
            if (clip.delete()) total -= length
        }
        usageBytes = total
    }

    private fun moveAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Names the app-private directory without exposing it to backup or device transfer. */
        const val DIRECTORY_NAME = "sentence-audio"

        fun directory(noBackupFiles: File): File = File(noBackupFiles, DIRECTORY_NAME)
    }
}
