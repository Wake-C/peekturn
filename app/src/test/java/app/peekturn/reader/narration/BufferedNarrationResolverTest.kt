package app.peekturn.reader.narration

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer is the seam between Readium's one-sentence-at-a-time requests and a provider that
 * renders whole sentences in finite time. These tests use virtual coroutine time and a fake
 * resolver that exposes how many sentences it is actually rendering at once.
 */
class BufferedNarrationResolverTest {

    private val directory: File = Files.createTempDirectory("peekturn-narration-buffer").toFile()

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    /**
     * A fake provider with real concurrency accounting. Rendering takes [renderMs] of virtual time
     * and produces a clip of [clipDurationMs]; complete clips are keyed by configuration, like the
     * persistent disk cache. Cache hits take no time and never count as a render.
     */
    private inner class FakeNarrationSource(
        var renderMs: Long = 4_500,
        var clipDurationMs: Long = 4_500
    ) : NarrationAudioResolver {
        var configurationValue = "voice-a"
        var configurationDelayMs = 0L
        var configurationFailure: NarrationFailure? = null

        val calls = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<String>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val failing = ConcurrentHashMap.newKeySet<String>()
        val renderTimes = ConcurrentHashMap<String, Long>()
        var serial = false
        private val renderGate = Mutex()

        private val cache = ConcurrentHashMap.newKeySet<String>()
        private val files = ConcurrentHashMap<String, File>()

        fun preload(vararg sentences: String) {
            sentences.forEach { cache.add("$configurationValue|$it") }
        }

        fun evict(vararg sentences: String) {
            sentences.forEach { cache.remove("$configurationValue|$it") }
        }

        fun resetPeak() {
            maxActive.set(0)
        }

        fun renderCount(sentence: String): Int = calls.count { it == sentence }

        fun completedCount(sentence: String): Int = completed.count { it == sentence }

        override suspend fun configuration(): String {
            configurationFailure?.let { throw it }
            // A real resolver binds once and then reads the cached profile cheaply.
            val initialBindDelay = configurationDelayMs
            configurationDelayMs = 0L
            if (initialBindDelay > 0L) delay(initialBindDelay)
            return configurationValue
        }

        override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
            configurationFailure?.let { return Result.failure(it) }
            val key = "$configurationValue|$sentence"
            val file = fileFor(sentence)
            if (key in cache) {
                return Result.success(
                    NarrationAudio(file = file, fromCache = true, durationMs = clipDurationMs)
                )
            }
            calls += sentence
            val running = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, running) }
            try {
                val duration = renderTimes[sentence] ?: renderMs
                return if (serial) {
                    renderGate.withLock { renderOne(sentence, key, file, duration) }
                } else {
                    renderOne(sentence, key, file, duration)
                }
            } finally {
                active.decrementAndGet()
            }
        }

        private suspend fun renderOne(
            sentence: String,
            key: String,
            file: File,
            duration: Long
        ): Result<NarrationAudio> {
            delay(duration)
            if (sentence in failing) {
                return Result.failure(NarrationFailure.Unavailable())
            }
            completed += sentence
            cache += key
            return Result.success(
                NarrationAudio(file = file, fromCache = false, durationMs = clipDurationMs)
            )
        }

        private fun fileFor(sentence: String): File = files.computeIfAbsent(sentence) {
            File.createTempFile("buffer-", ".wav", directory)
        }

        override fun cancelPending() = Unit

        override fun close() = cancelPending()
    }

    private fun blocks(vararg sizes: Int): List<List<String>> =
        sizes.mapIndexed { paragraph, size ->
            (0 until size).map { sentence -> "p${paragraph + 1}s${sentence + 1}." }
        }

    private fun followingFrom(
        current: String,
        paragraphs: List<List<String>>
    ): (String) -> Flow<NarrationParagraph> = { from ->
        flow {
            if (from == current) {
                paragraphs.forEach { emit(NarrationParagraph(it)) }
            }
        }
    }


    @Test
    fun `cold startup waits for two complete paragraphs and fifteen seconds of reserve`() = runTest {
        val current = "current."
        val paragraphs = blocks(1, 1)
        val source = FakeNarrationSource(renderMs = 5_000, clipDurationMs = 5_000)
        source.serial = true
        source.renderTimes[current] = 5_000
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
        )

        val audio = buffer.audioFor(current).getOrThrow()

        assertNotNull(audio.file)
        assertEquals("cold startup must wait for current + two complete paragraphs", 15_000L, currentTime)
        assertEquals(1, source.completedCount("p1s1."))
        assertEquals(1, source.completedCount("p2s1."))
    }


    @Test
    fun `steady mode waits for eight fully ready real paragraphs`() = runTest {
        val current = "current."
        val paragraphs = blocks(1, 1, 1, 1, 1, 1, 1)
        val source = FakeNarrationSource(renderMs = 1, clipDurationMs = 10_000)
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        buffer.audioFor(current).getOrThrow()
        advanceTimeBy(200)
        runCurrent()

        paragraphs.flatten().forEach { sentence ->
            assertEquals("paragraph sentence was not actually prepared: $sentence", 1, source.completedCount(sentence))
        }
        assertTrue("seven ready paragraphs must remain in burst", states.last().burst)
    }

    @Test
    fun `cold startup exits when one giant paragraph exhausts reserve bounds`() = runTest {
        val current = "current."
        val giant = (1..400).map { sentence -> "giant$sentence." }
        val source = FakeNarrationSource(renderMs = 1, clipDurationMs = 3_000)
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = { from ->
                flow {
                    if (from == current) emit(NarrationParagraph(giant))
                }
            },
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        val demand = async { buffer.audioFor(current) }
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue("cold startup remained blocked at the bounded reserve limit", demand.isCompleted)
        assertTrue(demand.await().isSuccess)
        assertEquals("a partial giant block cannot count as a ready paragraph", 0, states.last().readyParagraphs)
        assertTrue("only already-running clips may exceed the duration target", states.last().readyDurationMs <= 606_000)
    }

    @Test
    fun `configuration change at active intent restarts cold reserve for new identity`() = runTest {
        val current = "current."
        val paragraphs = blocks(1, 1, 1)
        val source = FakeNarrationSource(renderMs = 20, clipDurationMs = 10_000)
        source.serial = true
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        val first = buffer.audioFor(current).getOrThrow()
        assertEquals("voice-a", first.configuration)
        assertTrue("initial cold reserve was not built", states.last().readyParagraphs >= 2)

        source.configurationValue = "voice-b"
        val resumed = buffer.audioFor(paragraphs.first().single()).getOrThrow()

        assertEquals("voice-b", resumed.configuration)
        assertTrue("configuration switch skipped cold reserve", states.last().readyParagraphs >= 2)
        assertTrue("new identity reserve was shorter than the cold minimum", states.last().readyDurationMs >= 15_000)
    }
    @Test
    fun `slow configuration remains buffered until the current clip is ready`() = runTest {
        val current = "current."
        val paragraphs = blocks(1)
        val source = FakeNarrationSource(renderMs = 5_000, clipDurationMs = 5_000)
        source.configurationDelayMs = 5_000
        source.serial = true
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        val pending = async { buffer.audioFor(current) }
        advanceTimeBy(1_000)

        assertTrue(
            "slow TTS binding was not reported as buffering: ${states.lastOrNull()}",
            states.lastOrNull()?.buffering == true
        )

        val audio = pending.await().getOrThrow()
        assertNotNull(audio.file)
    }

    @Test
    fun `steady reserve uses one producer and keeps filling until the speculative ceiling`() = runTest {
        val current = "current."
        val paragraphs = blocks(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)
        val source = FakeNarrationSource()
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        var readyAtSteady = -1L
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { state ->
                states += state
                if (!state.burst && readyAtSteady < 0L) {
                    // Eight complete paragraphs and the adaptive duration establish steady mode;
                    // then the single producer keeps filling toward the 10-minute ceiling.
                    readyAtSteady = state.readyDurationMs
                    source.resetPeak()
                }
            }
        )

        buffer.audioFor(current).getOrThrow()
        advanceTimeBy(200_000)
        runCurrent()

        assertTrue(
            "burst never reached steady; last=${states.lastOrNull()} " +
                "calls=${source.calls.size} completed=${source.completed.size} " +
                "maxActive=${source.maxActive.get()}",
            readyAtSteady >= 0L
        )
        assertTrue(
            "steady producer stopped at the burst-exit target; ready=$readyAtSteady then ${states.last().readyDurationMs}",
            states.last().readyDurationMs > readyAtSteady
        )
        assertTrue("ready ${states.last().readyDurationMs} did not grow past startup", states.last().readyDurationMs > 60_000)
        assertFalse("reserve should be steady after it exceeded the burst target", states.last().burst)
        assertTrue("steady fill used ${source.maxActive.get()} producers", source.maxActive.get() <= 1)

        // Two ready sentences are consumed without any render or per-sentence wait.
        source.resetPeak()
        val flat = paragraphs.flatten()
        val frozen = currentTime
        buffer.audioFor(flat[0]).getOrThrow()
        buffer.audioFor(flat[1]).getOrThrow()
        assertEquals(frozen, currentTime)
        advanceTimeBy(200_000)
        runCurrent()
        assertTrue("steady state used ${source.maxActive.get()} producers", source.maxActive.get() <= 1)

        // Consume the rest. The contiguous reserve falls under the low-water mark, which is the
        // only honest reason to run two producers again.
        source.resetPeak()
        flat.drop(2).forEach { sentence -> buffer.audioFor(sentence).getOrThrow() }
        advanceTimeBy(200_000)
        runCurrent()
        val steadyAt = states.indexOfFirst { !it.burst }
        assertTrue("burst never became steady", steadyAt >= 0)
        assertTrue("low reserve did not resume burst", states.drop(steadyAt + 1).any { it.burst })
        assertTrue(source.maxActive.get() <= 2)
    }

    @Test
    fun `ready duration is playback time at the current speed`() = runTest {
        val current = "current."
        val paragraphs = blocks(2)
        val source = FakeNarrationSource()
        source.preload(current, *paragraphs.flatten().toTypedArray())
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        var speed = 1f
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { speed },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        buffer.audioFor(current).getOrThrow()
        advanceUntilIdle()
        assertEquals(3L * 4_500L, states.last().readyDurationMs)

        speed = 2f
        buffer.audioFor(paragraphs[0][0]).getOrThrow()
        advanceUntilIdle()
        // The consumed current clip is gone; the two remaining 4.5s clips play in 4.5s at 2x.
        assertEquals(4_500L, states.last().readyDurationMs)
        assertFalse(states.last().buffering)
    }

    @Test
    fun `steady requires eight complete paragraphs even when one paragraph covers the time reserve`() = runTest {
        val current = "current."
        val oneParagraph = FakeNarrationSource()
        val oneStates = CopyOnWriteArrayList<NarrationBufferState>()
        val onlyOne = BufferedNarrationResolver(
            source = oneParagraph,
            scope = backgroundScope,
            following = followingFrom(current, blocks(20)),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { oneStates += it }
        )
        onlyOne.audioFor(current).getOrThrow()
        advanceTimeBy(200_000)
        runCurrent()
        assertTrue(oneStates.last().readyDurationMs >= 60_000)
        assertTrue("one paragraph was treated as steady", oneStates.last().burst)

        val eightParagraphs = FakeNarrationSource()
        val eightStates = CopyOnWriteArrayList<NarrationBufferState>()
        val withEight = BufferedNarrationResolver(
            source = eightParagraphs,
            scope = backgroundScope,
            following = followingFrom(current, blocks(2, 2, 2, 2, 2, 2, 2, 2)),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { eightStates += it }
        )
        withEight.audioFor(current).getOrThrow()
        advanceTimeBy(200_000)
        runCurrent()
        assertTrue(eightStates.last().readyDurationMs >= 60_000)
        assertEquals(8, eightStates.last().readyParagraphs)
        assertFalse("eight complete paragraphs should reach steady", eightStates.last().burst)
    }


    @Test
    fun `end of book starts without waiting for a nonexistent next sentence`() = runTest {
        val current = "last sentence."
        val source = FakeNarrationSource()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = { emptyFlow() },
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        buffer.audioFor(current).getOrThrow()

        assertEquals(4_500L, currentTime)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `cancel drops lookahead but keeps complete cache`() = runTest {
        val current = "complete."
        val source = FakeNarrationSource()
        source.preload(current)
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, blocks(4)),
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        val pending = async { buffer.audioFor("lookahead.") }
        advanceTimeBy(1_000)
        buffer.cancelPending()
        advanceUntilIdle()

        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertTrue("lookahead completed after cancel", source.completed.isEmpty())

        // The complete clip is still a cache hit in a new session.
        val resumed = buffer.audioFor(current).getOrThrow()
        assertTrue(resumed.fromCache)
    }

    @Test
    fun `pause retains completed buffered audio and resumes without cold synthesis`() = runTest {
        val current = "current."
        val paragraphs = blocks(1, 1)
        val source = FakeNarrationSource(renderMs = 1, clipDurationMs = 10_000)
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        buffer.audioFor(current).getOrThrow()
        val completedNext = paragraphs.first().single()
        assertEquals(1, source.completedCount(completedNext))
        source.evict(completedNext)
        source.failing += completedNext
        val pausedAt = currentTime

        buffer.cancelPending()
        val resumed = buffer.audioFor(completedNext)

        assertTrue("pause discarded completed in-memory reserve", resumed.isSuccess)
        assertTrue(resumed.getOrThrow().fromCache)
        assertEquals("resume should use completed audio without another render", 1, source.renderCount(completedNext))
        assertEquals("resume should not wait for a cold rerender", pausedAt, currentTime)
    }

    @Test
    fun `pause at low reserve resumes warm cursor without rebuilding cold runway`() = runTest {
        val current = "current."
        val paragraphs = blocks(1, 1, 1)
        val source = FakeNarrationSource(renderMs = 5_000, clipDurationMs = 10_000)
        source.serial = true
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        buffer.audioFor(current).getOrThrow()
        val first = paragraphs[0][0]
        val currentAfterResume = paragraphs[1][0]
        buffer.audioFor(first).getOrThrow()
        buffer.audioFor(currentAfterResume).getOrThrow()
        val pausedAt = currentTime

        buffer.cancelPending()
        val resumed = buffer.audioFor(currentAfterResume).getOrThrow()

        assertTrue(resumed.fromCache)
        assertEquals("warm current audio should not be synthesized again", 1, source.renderCount(currentAfterResume))
        assertEquals("low-water resume unnecessarily waited for another cold runway", pausedAt, currentTime)
    }

    @Test
    fun `configuration and intent changes reject the previous ready reserve`() = runTest {
        val current = "current."
        val paragraphs = blocks(4, 4, 4, 4)
        val source = FakeNarrationSource()
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        var generation = 0L
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { generation },
            onState = { states += it }
        )
        buffer.audioFor(current).getOrThrow()
        advanceTimeBy(200_000)
        runCurrent()
        assertTrue(states.last().readyDurationMs >= 60_000)

        val flatsentences = paragraphs.flatten()
        val configRenders = source.renderCount(flatsentences[0])
        source.configurationValue = "voice-b"
        val afterConfig = buffer.audioFor(flatsentences[0]).getOrThrow()
        assertFalse(afterConfig.fromCache)
        assertTrue(
            "old-identity clip was not re-rendered",
            source.renderCount(flatsentences[0]) > configRenders
        )
        assertTrue("old reserve leaked into the new configuration", states.last().readyDurationMs < 60_000)

        generation = 1L
        buffer.audioFor(flatsentences[1]).getOrThrow()
        assertTrue("old reserve leaked across navigation", states.last().readyDurationMs < 60_000)
        advanceTimeBy(200_000)
        runCurrent()
    }

    @Test
    fun `a slow service reports buffering and never fakes a ready reserve`() = runTest {
        val current = "current."
        val paragraphs = blocks(50)
        val source = FakeNarrationSource(renderMs = 10_000, clipDurationMs = 2_000)
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        val pending = async { buffer.audioFor(current) }
        advanceTimeBy(1_000)
        assertTrue("initial wait was not reported as buffering", states.last().buffering)
        assertEquals(0L, states.last().readyDurationMs)

        val audio = pending.await().getOrThrow()
        assertNotNull(audio.file)
        assertTrue("audio was returned before it was rendered", currentTime >= 10_000)
        assertTrue("slow service was allowed to claim a ready reserve", states.last().readyDurationMs < 60_000)
        assertTrue("slow service should stay in burst", states.last().burst)

        // This sentence is beyond the real cold reserve. A new demand must wait for synthesis,
        // even when an earlier part of this giant paragraph is already playable.
        val late = async { buffer.audioFor(paragraphs.flatten()[40]) }
        advanceTimeBy(100)
        assertTrue("an underrunning demand did not report buffering", states.last().buffering)
        assertTrue(states.last().readyDurationMs < 60_000)
        assertTrue(late.await().isSuccess)
    }

    @Test
    fun `lookahead failure does not fail current audio and a real demand failure is reported`() = runTest {
        val current = "current."
        val paragraphs = listOf(listOf("next.", "distant."))
        val source = FakeNarrationSource()
        source.failing += "next."
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        val first = buffer.audioFor(current)
        assertTrue(first.isSuccess)
        assertEquals("a failed lookahead delayed startup", 4_500L, currentTime)
        assertEquals(0, source.completedCount("next."))

        val demandedFailure = buffer.audioFor("next.")
        assertTrue("an actual demand failure must be reported", demandedFailure.isFailure)

        val unrelated = buffer.audioFor("distant.")
        assertTrue("a failed lookahead failed unrelated audio", unrelated.isSuccess)
    }

    @Test
    fun `normal sentence transitions keep the same following flow`() = runTest {
        val current = "current."
        val paragraphs = blocks(4)
        val source = FakeNarrationSource()
        var followingCalls = 0
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = { from ->
                followingCalls++
                flow {
                    if (from == current) {
                        paragraphs.forEach { emit(NarrationParagraph(it)) }
                    }
                }
            },
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        buffer.audioFor(current).getOrThrow()
        val flat = paragraphs.flatten()
        buffer.audioFor(flat[0]).getOrThrow()
        buffer.audioFor(flat[1]).getOrThrow()
        advanceUntilIdle()

        assertEquals(1, followingCalls)
        assertEquals(1, source.renderCount(flat[0]))
        assertEquals(1, source.renderCount(flat[1]))
    }

    @Test
    fun `voice change during a demand re-resolves under the new identity`() = runTest {
        val current = "current."
        val paragraphs = blocks(2)
        val source = FakeNarrationSource()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        val pending = async { buffer.audioFor(current) }
        advanceTimeBy(1_000)
        source.configurationValue = "voice-b"
        val result = pending.await()

        assertTrue("a voice change hung or failed the waiting demand", result.isSuccess)
        assertFalse(result.getOrThrow().fromCache)
        assertEquals("old-identity render was not retried", 2, source.renderCount(current))
    }

    @Test
    fun `cached lookup re-resolves when the voice changes during startup`() = runTest {
        val current = "current."
        val paragraphs = blocks(2)
        val source = FakeNarrationSource()
        source.preload(current)
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, paragraphs),
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        val pending = async { buffer.audioFor(current) }
        advanceTimeBy(1_000)
        source.configurationValue = "voice-b"
        val result = pending.await()

        assertTrue(result.isSuccess)
        assertEquals("a clip cached under the old voice was admitted", "voice-b", result.getOrThrow().configuration)
        assertEquals("only the new identity may render", 1, source.renderCount(current))
    }

    @Test
    fun `engine initialization failure during cold reserve never returns prior engine audio`() = runTest {
        val current = "current."
        val source = FakeNarrationSource(renderMs = 5_000, clipDurationMs = 5_000)
        source.serial = true
        source.preload(current)
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = followingFrom(current, blocks(1, 1, 1)),
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )
        val pending = async { buffer.audioFor(current) }
        advanceTimeBy(1_000)
        source.configurationFailure = NarrationFailure.Unavailable()

        assertTrue("previous engine audio escaped after current initialization failed", pending.await().isFailure)
        assertFalse("a failed request left the UI preparing forever", states.last().buffering)
    }

    @Test
    fun `giant paragraph reaches bounded steady reserve without inventing paragraph credit`() = runTest {
        val current = "current."
        val giant = (1..200).map { sentence -> "giant$sentence." }
        val source = FakeNarrationSource(renderMs = 500, clipDurationMs = 6_000)
        val states = CopyOnWriteArrayList<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = { from ->
                flow {
                    if (from == current) emit(NarrationParagraph(giant))
                }
            },
            playbackSpeed = { 1f },
            intentGeneration = { 0L },
            onState = { states += it }
        )

        buffer.audioFor(current).getOrThrow()
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(states.last().readyDurationMs >= 300_000)
        assertFalse("a full duration-bound reserve should no longer use burst producers", states.last().burst)
        assertEquals(0, states.last().readyParagraphs)
        assertTrue("queued speculative work ran beyond the ceiling", source.calls.distinct().size <= 102)
        assertTrue("only already-running clips may cross the ceiling", states.last().readyDurationMs <= 612_000)
        val renderedAtBound = source.calls.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("a full reserve kept requesting unused audio", renderedAtBound, source.calls.size)
    }

    @Test
    fun `concurrent demands for one sentence share a single render`() = runTest {
        val current = "current."
        val source = FakeNarrationSource()
        val buffer = BufferedNarrationResolver(
            source = source,
            scope = backgroundScope,
            following = { emptyFlow() },
            playbackSpeed = { 1f },
            intentGeneration = { 0L }
        )

        val first = async { buffer.audioFor(current) }
        val second = async { buffer.audioFor(current) }
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, source.renderCount(current))
    }
}
