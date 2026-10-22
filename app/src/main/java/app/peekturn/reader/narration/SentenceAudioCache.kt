package app.peekturn.reader.narration

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

/** The container a cached clip is stored in. The extension is the only format-specific disk name. */
internal enum class NarrationAudioFormat(val extension: String) {
    Mp3("mp3"),
    Wave("wav")
}

/**
 * Persistent, app-private sentence audio. A name is the opaque synthesis key; no book text reaches
 * a filename or a log. Writes are validated complete streams and land with an atomic rename, so a
 * killed process can leave a temporary file but never a partial playable clip. MP3 and WAV clips
 * share one byte budget and one least-recently-used order.
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
     * Total bytes of every clip in both formats, initialized once from the directory and then
     * maintained under [mutex]. A cache hit or a normal write below the quota never enumerates.
     */
    private var usageBytes: Long? = null

    init {
        directory.mkdirs()
    }

    /** The cache-wide clear generation; a synthesis begun before a clear cannot write afterward. */
    fun epoch(): Long = generation.get()

    /**
     * A complete, previously synthesized clip with its real duration, refreshed as most recently
     * used. A damaged clip is removed instead of being decoded as a shorter sentence.
     */
    suspend fun get(
        key: String,
        format: NarrationAudioFormat = NarrationAudioFormat.Mp3
    ): NarrationAudio? = withContext(Dispatchers.IO) {
        if (!sentenceAudioKeyPattern.matches(key)) return@withContext null
        mutex.withLock {
            ensureAccounting()
            val file = clip(key, format)
            if (!file.isFile) return@withLock null
            val length = file.length()
            if (length <= 0L) {
                discard(file)
                return@withLock null
            }
            if (length > NARRATION_MAX_SENTENCE_AUDIO_BYTES) {
                discard(file)
                return@withLock null
            }
            // The write was validated, but a private file can still be damaged by the device.
            val durationMs = when (format) {
                NarrationAudioFormat.Mp3 ->
                    runCatching { Mp3Audio.durationMs(file.readBytes()) }.getOrNull()
                NarrationAudioFormat.Wave -> WaveAudio.durationMs(file)
            }
            if (durationMs == null) {
                discard(file)
                return@withLock null
            }
            file.setLastModified(System.currentTimeMillis())
            NarrationAudio(file = file, fromCache = true, durationMs = durationMs)
        }
    }

    /** Writes MP3 [audio] atomically when it is a complete frame sequence; null keeps nothing. */
    suspend fun put(
        key: String,
        audio: ByteArray,
        expectedEpoch: Long = generation.get()
    ): NarrationAudio? = withContext(Dispatchers.IO) {
        if (!sentenceAudioKeyPattern.matches(key)) return@withContext null
        if (
            audio.isEmpty() ||
            audio.size.toLong() > NARRATION_MAX_SENTENCE_AUDIO_BYTES ||
            audio.size.toLong() > maxBytes
        ) {
            return@withContext null
        }
        val durationMs = Mp3Audio.durationMs(audio) ?: return@withContext null
        mutex.withLock {
            if (expectedEpoch != generation.get()) return@withLock null
            ensureAccounting()
            directory.mkdirs()
            val target = clip(key, NarrationAudioFormat.Mp3)
            val previousLength = if (target.isFile) target.length() else 0L
            val temporary = File(directory, "${target.name}.tmp")
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
            // A clear that incremented the epoch while this write held the lock must win.
            if (expectedEpoch != generation.get()) {
                discard(target)
                return@withLock null
            }
            NarrationAudio(file = target, fromCache = false, durationMs = durationMs)
        }
    }

    /**
     * Validates an already rendered WAV and moves [source] into the cache atomically. The file is
     * streamed for validation, never copied into one byte array. Null means nothing was kept; a
     * source that was not moved remains the caller's to delete.
     */
    suspend fun putWave(
        key: String,
        source: File,
        expectedEpoch: Long = generation.get()
    ): NarrationAudio? = withContext(Dispatchers.IO) {
        if (!sentenceAudioKeyPattern.matches(key)) return@withContext null
        if (!source.isFile) return@withContext null
        val sourceLength = source.length()
        if (
            sourceLength <= 0L ||
            sourceLength > NARRATION_MAX_SENTENCE_AUDIO_BYTES ||
            sourceLength > maxBytes
        ) {
            return@withContext null
        }
        val durationMs = WaveAudio.durationMs(source) ?: return@withContext null
        mutex.withLock {
            if (expectedEpoch != generation.get()) return@withLock null
            ensureAccounting()
            directory.mkdirs()
            val target = clip(key, NarrationAudioFormat.Wave)
            val previousLength = if (target.isFile) target.length() else 0L
            if (!moveValidated(source, target)) return@withLock null
            // The caller owned the source and could have written it mid-validation; a length that
            // no longer matches the validated stream must not be published.
            val storedLength = target.length()
            if (storedLength != sourceLength) {
                discard(target)
                return@withLock null
            }
            changeUsage(storedLength - previousLength)
            target.setLastModified(System.currentTimeMillis())
            evictIfNeeded(protected = target)
            // A clear that incremented the epoch while this move held the lock must win.
            if (expectedEpoch != generation.get()) {
                discard(target)
                return@withLock null
            }
            NarrationAudio(file = target, fromCache = false, durationMs = durationMs)
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

    private fun clip(key: String, format: NarrationAudioFormat) =
        File(directory, "$key.${format.extension}")

    private fun clips(): List<File> =
        directory.listFiles { file -> file.isFile && clipNamePattern.matches(file.name) }
            ?.toList()
            .orEmpty()

    /** Removes a damaged file and forces the next accounting pass to recount what is left. */
    private fun discard(file: File) {
        file.delete()
        usageBytes = null
    }

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
     * bound. Both formats share this order. Only a write that crossed the bound enumerates the
     * directory, and it trims to 95% so the next several sentence writes do not scan again.
     * [protected] is never evicted: the clip just written must remain readable even when it is the
     * only entry and the bound is tight.
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

    /**
     * Moves the validated source to its final name without ever exposing a partial target. A
     * same-filesystem move is one atomic rename; anything else is streamed to a temporary inside
     * the cache directory and then atomically renamed.
     */
    private fun moveValidated(source: File, target: File): Boolean {
        if (source.absoluteFile == target.absoluteFile) return true
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            return true
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall through to a same-directory temporary file.
        } catch (_: Exception) {
            return false
        }
        val temporary = File(directory, "${target.name}.tmp")
        return try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            moveAtomically(temporary, target)
            source.delete()
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
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

private val clipNamePattern = Regex("[0-9a-f]{64}\\.(mp3|wav)")
