package app.margin.reader.narration

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narration synthesis is single-flight per sentence, one network synthesis at a time overall, and a
 * whole sentence is one cache unit even when the source is asked for bounded chunks.
 */
class NarrationSynthesizerTest {

    private val directory: File = Files.createTempDirectory("margin-narration-synth").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spec = NarrationVoiceSpec(
        engine = "microsoft-read-aloud",
        voice = "en-US-AriaNeural",
        prosody = "pitch=+0Hz;rate=+0%;volume=+0%",
        format = "mp3-24khz-48kbps-v1"
    )

    @After fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun frame(): ByteArray = ByteArray(417).also {
        it[0] = 0xFF.toByte()
        it[1] = 0xFB.toByte()
        it[2] = 0x90.toByte()
        it[3] = 0x64
    }

    private class CountingSource(
        private val delayMs: Long = 20,
        private val fail: Boolean = false
    ) : NarrationChunkSource {
        val calls = CopyOnWriteArrayList<String>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        override suspend fun lookup(text: String, accent: String): ByteArray? {
            calls += text
            active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, active.get()) }
            try {
                delay(delayMs)
                return if (fail) null else ByteArray(417).also {
                    it[0] = 0xFF.toByte()
                    it[1] = 0xFB.toByte()
                    it[2] = 0x90.toByte()
                    it[3] = 0x64
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun synthesizer(source: NarrationChunkSource) = NarrationSynthesizer(
        cache = SentenceAudioCache(directory, maxBytes = 4 * 1024 * 1024),
        source = source,
        scope = scope,
        accent = "US",
        voice = spec
    )

    @Test fun concurrentRequestsForTheSameSentenceShareOneSynthesis() = runBlocking {
        val source = CountingSource()
        val synth = synthesizer(source)
        val results = listOf(
            async { synth.audioFor("Today is a perfect day.") },
            async { synth.audioFor("Today   is a perfect day.") }
        ).awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, source.calls.size)
        assertTrue(results.all { it.getOrThrow().fromCache.not() })
    }


    @Test fun aClearDuringSynthesisCannotRepopulateTheCache() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = object : NarrationChunkSource {
            override suspend fun lookup(text: String, accent: String): ByteArray? {
                gate.await()
                return frame()
            }
        }
        val cache = SentenceAudioCache(directory, maxBytes = 4 * 1024 * 1024)
        val synthesizer = NarrationSynthesizer(cache, source, scope, "US", spec)
        val pending = async { synthesizer.audioFor("Today is a perfect day.") }

        delay(50)
        cache.clear()
        gate.complete(Unit)
        val result = pending.await()

        assertTrue(result.isFailure)
        assertEquals("a clear cannot be repopulated by an older synthesis", 0L, cache.sizeBytes())
    }

    @Test fun aSecondListenUsesTheCachedClip() = runBlocking {
        val source = CountingSource()
        val synth = synthesizer(source)
        assertTrue(synth.audioFor("Today is a perfect day.").isSuccess)
        val second = synth.audioFor("Today is a perfect day.").getOrThrow()

        assertTrue(second.fromCache)
        assertEquals(1, source.calls.size)
    }

    @Test fun differentSentencesNeverSynthesizeAtTheSameTime() = runBlocking {
        val source = CountingSource()
        val synth = synthesizer(source)
        val results = listOf(
            async { synth.audioFor("First sentence.") },
            async { synth.audioFor("Second sentence.") }
        ).awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals(2, source.calls.size)
        assertEquals("one synthesis request at a time", 1, source.maxActive.get())
    }

    @Test fun aLongSentenceIsSynthesizedInBoundedChunksButStoredAsOneUnit() = runBlocking {
        val source = CountingSource()
        val synth = synthesizer(source)
        val sentence = (1..80).joinToString(" ") { "word$it" } + "."

        val audio = synth.audioFor(sentence).getOrThrow()

        assertTrue(source.calls.size > 1)
        assertTrue(source.calls.all { it.length <= NARRATION_CHUNK_CHARS })
        assertTrue(audio.file.length() > 0)
        assertNotNull(SentenceAudioCache(directory, 4 * 1024 * 1024).get(
            sentenceAudioKey(canonicalSentence(sentence), spec)
        ))
        val second = synth.audioFor(sentence).getOrThrow()
        assertTrue(second.fromCache)
        assertTrue("one cache entry, not one per chunk", source.calls.size == source.calls.distinct().size)
    }


    @Test fun aStopThenClearLeavesNoLateCacheWrite() = runBlocking {
        val gate = Mutex()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : NarrationChunkSource {
            override suspend fun lookup(text: String, accent: String): ByteArray? {
                entered.complete(Unit)
                release.await()
                return frame()
            }
        }
        val cache = SentenceAudioCache(directory, maxBytes = 4 * 1024 * 1024)
        val synth = NarrationSynthesizer(cache, source, scope, "US", spec, gate)

        val request = async { runCatching { synth.audioFor("A sentence.") } }
        entered.await()
        synth.cancelPending()
        val clear = async { gate.withLock { cache.clear() } }
        release.complete(Unit)

        request.await()
        clear.await()
        assertEquals("a canceled in-flight synthesis cannot repopulate the cache", 0L, cache.sizeBytes())
    }

    @Test fun aChunkWithoutAudioFailsTheWholeSentenceAndWritesNothing() = runBlocking {
        val source = CountingSource(fail = true)
        val synth = synthesizer(source)
        val result = synth.audioFor("Today is a perfect day.")

        assertTrue(result.isFailure)
        assertEquals(0L, SentenceAudioCache(directory, 4 * 1024 * 1024).sizeBytes())
    }

    @Test fun aSentencePastTheHardCapFailsWithoutAskingTheService() = runBlocking {
        val source = CountingSource()
        val synth = synthesizer(source)
        val sentence = ("word ".repeat(NARRATION_MAX_CHUNKS * 40)).trim() + "."
        val result = synth.audioFor(sentence)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NarrationFailure.TooLong)
        assertEquals(0, source.calls.size)
    }
}
