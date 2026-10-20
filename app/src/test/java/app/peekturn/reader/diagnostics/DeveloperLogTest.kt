package app.peekturn.reader.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Persistence, bounding, privacy and crash delegation for the support-facing developer log. */
class DeveloperLogTest {
    private lateinit var directory: File
    private var now = 1_700_000_000_000L
    private val inline = Executor { it.run() }

    private val snapshot = {
        "App: app.peekturn.reader 1.1.0 (3)\n" +
            "Device: Test Manufacturer Test Model\n" +
            "Android: 15 (SDK 35, build TEST)\n" +
            "Default TTS engine: com.test.engine 2.0\n" +
            "Installed TTS engines: com.test.engine 2.0"
    }

    @Before fun setUp() {
        directory = Files.createTempDirectory("peekturn-developer-log").toFile()
        now = 1_700_000_000_000L
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun log(
        maxBytes: Long = DEVELOPER_LOG_MAX_BYTES,
        writer: Executor = inline,
        snapshot: () -> String = this.snapshot
    ) = DeveloperLog(
        directory = directory,
        maxBytes = maxBytes,
        writer = writer,
        io = Dispatchers.Unconfined,
        snapshot = snapshot,
        clock = { now++ }
    )

    private fun logFile() = File(directory, "developer-log.txt")

    @Test fun eventsSurviveANewLogInstance() = runTest {
        val first = log()
        first.recordEvent("app", "start")
        first.recordFailure("reader", "open", IllegalStateException("private detail"))
        first.recordSystemSpeech("engine=test; voice=en-US")

        val exported = log().export()
        assertTrue(exported.contains("App: app.peekturn.reader 1.1.0 (3)"))
        assertTrue(exported.contains("| app | start"))
        assertTrue(exported.contains("| reader |"))
        assertTrue(exported.contains("IllegalStateException"))
        assertTrue(exported.contains("engine=test; voice=en-US"))
        assertFalse(exported.contains("private detail"))
    }

    @Test fun failureExportKeepsTypesAndDeclaringMethodsButNoMessagesOrFileNames() = runTest {
        val root = IllegalStateException("root message secret").apply {
            stackTrace = arrayOf(
                StackTraceElement("app.peekturn.reader.SecretType", "hide", "/private/books/secret.kt", 42)
            )
        }
        val outer = RuntimeException("outer message secret", root).apply {
            stackTrace = arrayOf(StackTraceElement("app.peekturn.reader.Caller", "record", "Caller.kt", 7))
            addSuppressed(IllegalArgumentException("suppressed message secret"))
        }

        log().recordFailure("reader", "search", outer)
        val exported = log().export()

        assertTrue(exported.contains("exception=java.lang.RuntimeException"))
        assertTrue(exported.contains("cause=java.lang.IllegalStateException"))
        assertTrue(exported.contains("app.peekturn.reader.Caller.record:7"))
        assertTrue(exported.contains("app.peekturn.reader.SecretType.hide:42"))
        assertTrue(exported.contains("suppressed=java.lang.IllegalArgumentException"))
        assertFalse(exported.contains("outer message secret"))
        assertFalse(exported.contains("root message secret"))
        assertFalse(exported.contains("suppressed message secret"))
        assertFalse(exported.contains("secret.kt"))
        assertFalse(exported.contains("/private/books"))
    }

    @Test fun depthOfCausesIsBounded() = runTest {
        var error = RuntimeException("root message secret")
        repeat(30) { error = RuntimeException("cause message secret", error) }

        log().recordFailure("reader", "chapter", error)
        val exported = log().export()

        assertTrue(Regex("cause=").findAll(exported).count() <= 6)
        assertFalse(exported.contains("message secret"))
    }

    @Test fun rotationKeepsTheNewestRecordsWithinBudgetAndMarksTruncation() {
        val log = log(maxBytes = 4L * 1024)
        repeat(200) { index -> log.recordEvent("reader", "event-$index") }

        assertTrue(logFile().length() <= 4L * 1024)
        val exported = runBlocking { log.export() }
        assertTrue(exported.contains(DEVELOPER_LOG_TRUNCATION_MARKER))
        assertTrue(exported.contains("| event-199"))
        assertFalse(exported.contains("| event-0\n"))
        assertTrue(runBlocking { log().export() }.contains("| event-199"))
    }

    @Test fun anOversizedSpeechReportIsNormalizedBoundedAndFreeOfControls() {
        val log = log(maxBytes = 4L * 1024)
        log.recordSystemSpeech("engine=test\n" + "x".repeat(20_000) + "\u0000\u0007")

        assertTrue(logFile().length() <= 4L * 1024)
        val text = logFile().readText()
        assertTrue(text.contains("engine=test x"))
        assertTrue(text.contains("[truncated]"))
        assertFalse(text.contains('\u0000'))
        assertFalse(text.contains('\u0007'))
        assertFalse(text.contains("\nengine"))
    }

    @Test fun exportDrainsPendingBackgroundWrites() = runTest {
        val writer = Executors.newSingleThreadExecutor()
        try {
            val log = log(writer = writer)
            repeat(50) { index -> log.recordEvent("app", "event-$index") }
            val exported = log.export()
            assertTrue(exported.contains("| event-49"))
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun exportUsesAFreshSnapshotEveryTime() = runTest {
        var current = "Device: first"
        val log = log(snapshot = { current })
        assertTrue(log.export().contains("Device: first"))
        current = "Device: second"
        assertTrue(log.export().contains("Device: second"))
        assertFalse(log.export().contains("Device: first"))
    }

    @Test fun concurrentWritesAndExportsStayCompleteAndWellFormed() {
        val writer = Executors.newSingleThreadExecutor()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val log = log(writer = writer)
            val start = CountDownLatch(1)
            val tasks = (0 until 4).map { worker ->
                workers.submit {
                    start.await()
                    repeat(25) { index -> log.recordEvent("worker", "event-$worker-$index") }
                    repeat(3) {
                        val exported = runBlocking { log.export() }
                        assertTrue(exported.contains("Peekturn developer log"))
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val exported = runBlocking { log.export() }
            assertEquals(100, Regex("\\| event-").findAll(exported).count())
        } finally {
            workers.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun framesAreValidatedInsteadOfEchoingPayloadShapedFields() = runTest {
        val error = IllegalStateException("frame message secret").apply {
            stackTrace = arrayOf(
                StackTraceElement("/private/books/secret.epub", "do\nbad", "secret.epub", 9),
                StackTraceElement("com.example.Safe", "run", "Safe.kt", 12)
            )
        }

        log().recordFailure("reader", "open", error)
        val exported = log().export()

        assertTrue(exported.contains("com.example.Safe.run:12"))
        assertTrue(exported.contains(DIAGNOSTIC_INVALID_IDENTIFIER))
        assertFalse(exported.contains("private/books"))
        assertFalse(exported.contains("secret.epub"))
        assertFalse(exported.contains("do bad"))
        assertFalse(exported.contains("frame message secret"))
    }

    @Test fun unreadableExistingLogFailsExportInsteadOfClaimingNoEvents() = runTest {
        val log = log()
        assertTrue(log.export().contains("No recorded events."))
        assertTrue(File(directory, "developer-log.txt").mkdirs())

        var failed = false
        try {
            log.export()
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
    }

    @Test fun crashHandlerDelegatesEvenWhenTheLogCannotBeWritten() {
        val blocked = File(directory, "blocked")
        blocked.writeText("not a directory")
        val log = DeveloperLog(
            directory = blocked,
            writer = inline,
            io = Dispatchers.Unconfined,
            snapshot = snapshot,
            clock = { now++ }
        )
        var delegated: Throwable? = null
        val handler = log.crashHandler(Thread.UncaughtExceptionHandler { _, error -> delegated = error })
        val fatal = IllegalStateException("crash message secret")

        handler.uncaughtException(Thread.currentThread(), fatal)

        assertSame(fatal, delegated)
    }

    @Test fun crashIsWrittenSynchronouslyWithoutItsMessage() {
        val log = log()
        var delegated: Throwable? = null
        val handler = log.crashHandler(Thread.UncaughtExceptionHandler { _, error -> delegated = error })
        val fatal = IllegalStateException("crash message secret")

        handler.uncaughtException(Thread.currentThread(), fatal)

        assertSame(fatal, delegated)
        val exported = runBlocking { log.export() }
        assertTrue(exported.contains("| crash | app |"))
        assertTrue(exported.contains("exception=java.lang.IllegalStateException"))
        assertFalse(exported.contains("crash message secret"))
    }
}
