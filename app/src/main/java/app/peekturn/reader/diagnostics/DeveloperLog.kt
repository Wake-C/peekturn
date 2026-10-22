package app.peekturn.reader.diagnostics

import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The persisted budget. Rotation retains the newest half plus one explicit truncation marker.
 * Default exports stay small enough for one-tap copying while retaining recent UTF-8 records.
 */
internal const val DEVELOPER_LOG_MAX_BYTES = 256L * 1024
internal const val DEVELOPER_LOG_EXPORT_MAX_BYTES = 16 * 1024

/** Always written as the first record after rotation, so truncation is visible in a copied log. */
internal const val DEVELOPER_LOG_TRUNCATION_MARKER = "[developer log truncated: older entries removed]"

private const val DEVELOPER_LOG_FILE = "developer-log.txt"
private const val DEVELOPER_LOG_DIRECTORY = "diagnostics"
private const val MIN_LOG_BYTES = 4L * 1024
private const val MAX_RECORD_BYTES = 8 * 1024
private const val MAX_SNAPSHOT_BYTES = 8 * 1024
private const val MAX_CODE_CHARS = 64
private const val MAX_CAUSE_DEPTH = 6
private const val MAX_FRAMES = 32
private const val MAX_SUPPRESSED = 4

/**
 * App-private, support-facing diagnostics. Callers pass only app-owned codes or typed speech
 * metadata: this module never accepts book text, prompts, model output or arbitrary user content.
 * Records are queued to one background writer, bounded on disk, and survive process restarts; a
 * fatal crash is written synchronously before the original uncaught-exception handler runs.
 */
class DeveloperLog internal constructor(
    private val directory: File,
    private val maxBytes: Long = DEVELOPER_LOG_MAX_BYTES,
    private val writer: Executor = newWriter(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val snapshot: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val file = File(directory, DEVELOPER_LOG_FILE)
    private val lock = Any()
    private val recordLimit = (maxBytes / 4).coerceIn(512L, MAX_RECORD_BYTES.toLong()).toInt()
    private val _updates = MutableStateFlow(0L)
    val updates = _updates.asStateFlow()


    init {
        require(maxBytes >= MIN_LOG_BYTES) { "Developer log budget is too small" }
    }

    /**
     * A speech capability or lifecycle report produced by the app itself. The report may be
     * multi-line, but it is normalized to one bounded record so one OEM string cannot bloat the file.
     */
    fun recordSystemSpeech(report: String) {
        val detail = normalizeText(report)
        if (detail.isBlank()) return
        enqueue(record("report", "system-speech", boundedUtf8(detail, recordLimit)))
    }
    /** Bounded narration lifecycle details; callers must pass only safe diagnostic summaries. */
    fun recordNarration(report: String) {
        val detail = normalizeText(report)
        if (detail.isBlank()) return
        enqueue(record("report", "narration", boundedUtf8(detail, recordLimit)))
    }


    /**
     * A failure in an app-owned feature. [feature] and [category] are fixed short codes. [cause] is
     * reduced to exception class names, declaring classes/methods and line numbers: messages,
     * file names, suppressed messages and cause messages never reach disk.
     */
    fun recordFailure(feature: String, category: String, cause: Throwable? = null) {
        val detail = buildString {
            append("category=")
            append(code(category))
            if (cause != null) {
                append(" | ")
                append(describeThrowable(cause))
            }
        }
        enqueue(record("failure", code(feature), detail))
    }

    /** A fixed app-owned lifecycle or capability event code, never free-form content. */
    fun recordEvent(feature: String, event: String) {
        enqueue(record("event", code(feature), code(event)))
    }

    /**
     * The current safe device/app/OS/speech-engine snapshot followed by the newest records that fit
     * the small export budget. Pending background writes are drained first.
     */
    suspend fun export(): String = withContext(io) {
        drain()
        val current = runCatching { snapshot() }.getOrDefault("Snapshot unavailable.")
        synchronized(lock) {
            val events = if (file.exists()) file.readLines(StandardCharsets.UTF_8) else emptyList()
            val header = buildString {
                appendLine("Peekturn developer log")
                appendLine("Exported: ${Instant.ofEpochMilli(clock())}")
                appendLine(boundedUtf8(current, MAX_SNAPSHOT_BYTES).trimEnd())
                appendLine("----")
            }
            recentExport(header, events)
        }
    }
    /** Removes persisted diagnostics after all writes already queued on the writer have completed. */
    suspend fun clear() = withContext(io) {
        val completed = CompletableDeferred<Unit>()
        writer.execute {
            try {
                synchronized(lock) {
                    if (file.exists() && !file.delete()) {
                        throw IOException("Could not clear developer log")
                    }
                }
                _updates.update { it + 1 }
                completed.complete(Unit)
            } catch (error: Throwable) {
                completed.completeExceptionally(error)
            }
        }
        completed.await()
    }


    /**
     * Installs the process handler once and preserves whatever handler was already present. Logging
     * is best-effort and cannot prevent the original handler from receiving the throwable.
     */
    fun installCrashHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(crashHandler(previous))
    }

    internal fun crashHandler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            runCatching { appendSafely(record("crash", "app", describeThrowable(error))) }
            if (previous != null) previous.uncaughtException(thread, error) else throw error
        }

    private fun enqueue(line: String) {
        try {
            writer.execute { appendSafely(line) }
            _updates.update { it + 1 }
        } catch (_: Throwable) {
            // Diagnostics must never break the feature or surface a second failure.
        }
    }

    private fun drain() {
        val barrier = CountDownLatch(1)
        try {
            writer.execute { barrier.countDown() }
        } catch (_: Throwable) {
            return
        }
        try {
            barrier.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun appendSafely(line: String) {
        try {
            synchronized(lock) {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
                val bytes = boundedUtf8(line, recordLimit).toByteArray(StandardCharsets.UTF_8)
                val current = if (file.isFile) file.length() else 0L
                if (current > 0 && current + bytes.size > maxBytes) rotateLocked()
                FileOutputStream(file, true).use { output ->
                    output.write(bytes)
                    output.write('\n'.code)
                }
                // A single record is bounded above; this second check covers an unexpected disk state.
                if (file.length() > maxBytes) rotateLocked()
            }
        } catch (_: Throwable) {
            // Best effort only: a read-only or full disk must not affect the app or crash handling.
        }
    }

    /** Keeps the newest records up to half the budget and marks the discarded older records. */
    private fun rotateLocked() {
        val existing = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        val markerBytes = DEVELOPER_LOG_TRUNCATION_MARKER.toByteArray(StandardCharsets.UTF_8).size + 1L
        val target = (maxBytes / 2).coerceAtLeast(markerBytes)
        val kept = ArrayDeque<String>()
        var used = markerBytes
        for (line in existing.asReversed()) {
            val size = line.toByteArray(StandardCharsets.UTF_8).size + 1L
            if (used + size > target) break
            kept.addFirst(line)
            used += size
        }
        val temporary = File(directory, "$DEVELOPER_LOG_FILE.tmp")
        FileOutputStream(temporary).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.write(DEVELOPER_LOG_TRUNCATION_MARKER)
            output.newLine()
            for (line in kept) {
                output.write(line)
                output.newLine()
            }
        }
        try {
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun record(kind: String, feature: String, detail: String): String =
        "${Instant.ofEpochMilli(clock())} | $kind | $feature | $detail"

    companion object {
        private val crashHandlerInstalled = AtomicBoolean(false)

        internal fun directory(noBackupFilesDir: File): File = File(noBackupFilesDir, DEVELOPER_LOG_DIRECTORY)
    }
}

private fun recentExport(header: String, records: List<String>): String {
    val maxBytes = DEVELOPER_LOG_EXPORT_MAX_BYTES
    val headerBytes = header.toByteArray(StandardCharsets.UTF_8).size
    val markerBytes = (DEVELOPER_LOG_TRUNCATION_MARKER + "\n").toByteArray(StandardCharsets.UTF_8).size
    val retained = ArrayDeque<String>()
    var used = headerBytes
    for (record in records.asReversed()) {
        val size = record.toByteArray(StandardCharsets.UTF_8).size + 1
        if (used + markerBytes + size > maxBytes) break
        retained.addFirst(record)
        used += size
    }
    val truncated = retained.size < records.size
    return buildString {
        append(header)
        if (records.isEmpty()) {
            appendLine("No recorded events.")
        } else {
            if (truncated) appendLine(DEVELOPER_LOG_TRUNCATION_MARKER)
            retained.forEach { appendLine(it) }
        }
    }
}

private fun newWriter(): Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "peekturn-developer-log").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}

/**
 * Class names and declaring class/method/line only. StackTraceElement.fileName is deliberately not
 * retained: it can carry a source path or URL. Class and method fields are validated Java-shaped
 * identifiers and replaced by an explicit marker when they are not; cause and suppressed traversal
 * is bounded and cycle-safe, and no throwable message is ever read.
 */
internal fun describeThrowable(throwable: Throwable): String = buildString {
    val seen = IdentityHashMap<Throwable, Boolean>()
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH && seen.put(current, true) == null) {
        append(if (depth == 0) "exception=" else " | cause=")
        append(diagnosticIdentifier(current.javaClass.name))
        val frames = current.stackTrace
        if (frames.isNotEmpty()) {
            append(" frames=")
            frames.take(MAX_FRAMES).forEachIndexed { index, frame ->
                if (index > 0) append(';')
                append(diagnosticIdentifier(frame.className)).append('.')
                    .append(diagnosticIdentifier(frame.methodName)).append(':').append(frame.lineNumber)
            }
        }
        current = current.cause
        depth++
    }
    val suppressed = throwable.suppressed
    if (suppressed.isNotEmpty()) {
        append(" | suppressed=")
        suppressed.take(MAX_SUPPRESSED).forEachIndexed { index, item ->
            if (index > 0) append(',')
            append(diagnosticIdentifier(item.javaClass.name))
        }
    }
}

/** Fixed app-owned codes only: anything outside the small ASCII vocabulary becomes an underscore. */
internal fun code(value: String): String {
    val safe = buildString {
        for (character in value.trim()) {
            append(
                when {
                    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' -> character
                    character == '.' || character == '_' || character == '-' || character == ':' -> character
                    else -> '_'
                }
            )
        }
    }
    return boundedUtf8(safe.ifBlank { "unknown" }, MAX_CODE_CHARS)
}
