package app.peekturn.reader.narration

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why narration could not produce the complete sentence it was asked for. */
internal sealed class NarrationFailure(message: String) : Exception(message) {
    class Unavailable : NarrationFailure("The read-aloud service returned no complete audio.")
    class TooLong : NarrationFailure("The sentence is too long to read aloud in one piece.")
    class InvalidAudio : NarrationFailure("The read-aloud answer was not complete supported audio.")
    class TooLarge : NarrationFailure("The sentence audio is larger than the cache allows.")
    class Storage : NarrationFailure("The sentence audio could not be stored.")
    class Device(val code: Int) : NarrationFailure("The system speech engine could not produce complete audio (code $code).")
}

internal data class NarrationAudio(
    val file: File,
    val fromCache: Boolean,
    val durationMs: Long = 0L,
    val configuration: String? = null,
    val diagnosticIdentity: String? = null
)

/** The complete cached audio for a sentence; providers own synthesis and its configuration identity. */
internal interface NarrationAudioResolver {
    suspend fun configuration(): String
    suspend fun audioFor(sentence: String): Result<NarrationAudio>
    fun cancelPending()
    fun close() { cancelPending() }
}

/** One bounded provider request for transport; a returned null means the whole sentence failed. */
internal fun interface NarrationChunkSource {
    suspend fun lookup(text: String, accent: String): ByteArray?
}

/**
 * Turns a complete sentence into one cached MP3. Requests for the same sentence share a single
 * synthesis, different sentences synthesize one at a time, and a long sentence is assembled from
 * bounded transport chunks without changing its cache key or its playback unit.
 *
 * The provider gate is shared by every playback session in the app, so an old session being torn
 * down cannot overlap a new session's request. A caller that missed the cache just before another
 * request finished finds the finished file under the same gate instead of asking the service twice.
 *
 * This is deliberately separate from [app.peekturn.reader.speech.PronunciationPlayer]: popup speech is
 * latest-only and memory-only, while narration is continuous, persistent and restartable.
 */
internal class NarrationSynthesizer(
    private val cache: SentenceAudioCache,
    private val source: NarrationChunkSource,
    private val scope: CoroutineScope,
    private val accent: String,
    private val voice: NarrationVoiceSpec,
    private val providerGate: Mutex = Mutex()
) : NarrationAudioResolver {
    private val flights = mutableMapOf<String, Deferred<Result<NarrationAudio>>>()
    private val configurationKey = sentenceAudioKey("", voice)

    override suspend fun configuration(): String = configurationKey

    override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
        val canonical = canonicalSentence(sentence)
        if (canonical.isBlank()) return Result.failure(NarrationFailure.Unavailable())
        val key = sentenceAudioKey(canonical, voice)

        cache.get(key)?.let { return Result.success(it) }

        val flight = synchronized(flights) {
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) { synthesize(canonical, key) }
                .also { started ->
                    flights[key] = started
                    started.invokeOnCompletion {
                        synchronized(flights) { if (flights[key] === started) flights.remove(key) }
                    }
                    started.start()
                }
        }
        return flight.await()
    }

    /** A stop cancels every queued or in-flight synthesis; the cache keeps only complete clips. */
    override fun cancelPending() {
        val pending = synchronized(flights) {
            flights.values.toList().also { flights.clear() }
        }
        pending.forEach { it.cancel() }
    }

    private suspend fun synthesize(canonical: String, key: String): Result<NarrationAudio> {
        val epoch = cache.epoch()
        val chunks = narrationChunks(canonical) ?: return Result.failure(NarrationFailure.TooLong())
        return try {
            providerGate.withLock {
                // A concurrent caller may have stored the same sentence while this one waited.
                cache.get(key)?.let { return@withLock Result.success(it) }

                val collected = ArrayList<ByteArray>(chunks.size)
                var total = 0L
                for (chunk in chunks) {
                    val audio = source.lookup(chunk, accent)
                        ?: return@withLock Result.failure(NarrationFailure.Unavailable())
                    total += audio.size
                    if (total > NARRATION_MAX_SENTENCE_AUDIO_BYTES) {
                        return@withLock Result.failure(NarrationFailure.TooLarge())
                    }
                    collected += audio
                }
                val joined = Mp3Audio.concat(collected)
                    ?: return@withLock Result.failure(NarrationFailure.InvalidAudio())
                val file = cache.put(key, joined, epoch)
                    ?: return@withLock Result.failure(NarrationFailure.Storage())
                Result.success(file)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(NarrationFailure.Unavailable())
        }
    }
}
