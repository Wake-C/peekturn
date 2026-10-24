package app.peekturn.reader.narration

import android.content.Context
import android.os.SystemClock
import app.peekturn.reader.diagnostics.diagnosticMetadata
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore

/**
 * Persistent file narration for Android's system engine. Every complete render becomes one WAV in
 * the shared sentence cache under the engine's current reported voice identity, so a repeat or a
 * restart reads the file instead of rebinding and synthesizing again.
 *
 * Requests for the same sentence and configuration share one flight. A genuine configuration change
 * (system engine or reported voice) is reconciled inside the waiting request: a superseded render
 * is retried under the new profile instead of being returned as stale audio or as a user-visible
 * device error. Caller cancellation and real engine failures still propagate unchanged.
 */
internal class SystemNarrationSynthesizer(
    context: Context,
    private val cache: SentenceAudioCache,
    private val scope: CoroutineScope,
    gate: Semaphore,
    private val onDiagnostic: (String) -> Unit = {},
    private val renderer: SystemSpeechRenderer = SystemSpeechRenderer(
        context = context,
        gate = gate,
        onDiagnostic = onDiagnostic
    )
) : NarrationAudioResolver {

    private val application = context.applicationContext
    private val tempDirectory = File(application.cacheDir, "narration-system-speech")
    private val flights = mutableMapOf<String, Deferred<Result<NarrationAudio>>>()
    private data class Profile(val voice: NarrationVoiceSpec, val identity: String, val description: String)
    @Volatile private var preparedProfile: Profile? = null

    @Volatile
    private var closed = false

    override suspend fun configuration(): String = try {
        profile().identity
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SystemSpeechError) {
        throw error.asNarrationFailure()
    } catch (_: Exception) {
        throw NarrationFailure.Unavailable()
    }

    override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
        if (closed) return Result.failure(NarrationFailure.Unavailable())
        val canonical = canonicalSentence(sentence)
        if (canonical.isBlank()) return Result.failure(NarrationFailure.Unavailable())

        var reconciliations = 0
        var previousIdentity: String? = null
        var retryReason = "initial"
        while (true) {
            if (closed) return Result.failure(NarrationFailure.Unavailable())
            val prepared = try {
                profile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SystemSpeechError) {
                return Result.failure(error.asNarrationFailure())
            } catch (_: Exception) {
                return Result.failure(NarrationFailure.Unavailable())
            }
            val voice = prepared.voice
            previousIdentity?.let { previous ->
                val changed = previous != prepared.identity
                diagnostic(
                    "event=reconcile attempt=$reconciliations reason=$retryReason " +
                        "result=${if (changed) "profile_changed" else "same_profile_retry"} " +
                        "oldProfile=${previous.take(12)} newProfile=${prepared.identity.take(12)} " +
                        "engine=${diagnosticMetadata(voice.engine)} chars=${canonical.length}"
                )
            }
            previousIdentity = prepared.identity

            val key = sentenceAudioKey(canonical, voice)
            val cached = cache.get(key, NarrationAudioFormat.Wave)
            if (cached != null) {
                diagnostic("event=cache_hit key=${key.take(12)} chars=${canonical.length} audioMs=${cached.durationMs}")
                val current = try {
                    profileStillCurrent(voice)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: SystemSpeechError) {
                    return Result.failure(error.asNarrationFailure())
                } catch (_: Exception) {
                    return Result.failure(NarrationFailure.Unavailable())
                }
                if (current) return Result.success(cached.withProfile(prepared))
                retryReason = "cache_stale"
            } else {
                diagnostic("event=cache_miss key=${key.take(12)} chars=${canonical.length}")
                var reused = false
                val flight = synchronized(flights) {
                    val existing = flights[key]
                    if (existing != null) {
                        reused = true
                        existing
                    } else {
                        scope.async(start = CoroutineStart.LAZY) {
                            synthesize(canonical, key, voice)
                        }.also { started ->
                            flights[key] = started
                            started.invokeOnCompletion {
                                synchronized(flights) {
                                    if (flights[key] === started) flights.remove(key)
                                }
                            }
                            started.start()
                        }
                    }
                }
                // Reuse vs a fresh flight: the requesting wait is a shared consumer, never a
                // second engine submission. Only the existing cache key and counts are reported.
                diagnostic(
                    "event=${if (reused) "flight_reuse" else "flight_new"} key=${key.take(12)} " +
                        "chars=${canonical.length} profile=${prepared.identity.take(12)} " +
                        "engine=${diagnosticMetadata(voice.engine)}"
                )
                val result = try {
                    flight.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SystemSpeechError.ConfigurationChanged) {
                    null
                } catch (error: SystemSpeechError) {
                    return Result.failure(error.asNarrationFailure())
                } catch (_: Exception) {
                    return Result.failure(NarrationFailure.Unavailable())
                }
                if (result != null) {
                    if (!result.isSuccess) return result
                    val current = try {
                        profileStillCurrent(voice)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: SystemSpeechError) {
                        return Result.failure(error.asNarrationFailure())
                    } catch (_: Exception) {
                        return Result.failure(NarrationFailure.Unavailable())
                    }
                    if (current) return result.map { it.withProfile(prepared) }
                    retryReason = "flight_stale"
                } else {
                    retryReason = "flight_config_changed"
                }
            }

            reconciliations++
            if (reconciliations >= MAX_PROFILE_RECONCILIATIONS) {
                diagnostic(
                    "event=reconcile_limit key=${key.take(12)} chars=${canonical.length} " +
                        "attempts=$reconciliations engine=${diagnosticMetadata(voice.engine)}"
                )
                return Result.failure(NarrationFailure.Unavailable())
            }
        }
    }

    /** A stop, seek or explicit cancellation cancels in-flight renders; complete files remain. */
    override fun cancelPending() {
        cancelFlights("cancel_pending")
        renderer.cancelPending()
    }

    /** Releases the renderer's TTS connection and every in-flight file synthesis. */
    override fun close() {
        if (closed) return
        closed = true
        cancelFlights("closed")
        renderer.close()
    }

    private suspend fun profileStillCurrent(voice: NarrationVoiceSpec): Boolean = try {
        renderer.configuration() == voice
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SystemSpeechError.ConfigurationChanged) {
        false
    } catch (error: SystemSpeechError) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun synthesize(
        canonical: String,
        key: String,
        voice: NarrationVoiceSpec
    ): Result<NarrationAudio> {
        val startedAt = SystemClock.elapsedRealtime()
        val temporary = File(tempDirectory, "$key-${UUID.randomUUID()}.wav")
        try {
            tempDirectory.mkdirs()
            val epoch = cache.epoch()
            diagnostic("event=render_begin key=${key.take(12)} chars=${canonical.length}")
            renderer.render(canonical, temporary, voice)

            // The renderer re-checked after onDone; this guard also covers a change while the
            // finished file is being handed to the cache.
            if (!profileStillCurrent(voice)) throw SystemSpeechError.ConfigurationChanged()

            val stored = cache.putWave(key, temporary, expectedEpoch = epoch)
                ?: return Result.failure(NarrationFailure.Storage())

            if (!profileStillCurrent(voice)) throw SystemSpeechError.ConfigurationChanged()
            diagnostic(
                "event=cache_store key=${key.take(12)} audioMs=${stored.durationMs} " +
                    "renderTotalMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return Result.success(stored)
        } catch (cancelled: CancellationException) {
            diagnostic("event=render_cancelled key=${key.take(12)}")
            throw cancelled
        } catch (changed: SystemSpeechError.ConfigurationChanged) {
            throw changed
        } catch (error: SystemSpeechError) {
            diagnostic("event=render_failed key=${key.take(12)} kind=${error.javaClass.simpleName} code=${error.code}")
            return Result.failure(error.asNarrationFailure())
        } catch (_: Exception) {
            diagnostic("event=render_failed key=${key.take(12)} kind=unavailable")
            return Result.failure(NarrationFailure.Unavailable())
        } finally {
            temporary.delete()
        }
    }

    private fun cancelFlights(reason: String) {
        val pending = synchronized(flights) {
            val copy = flights.values.toList()
            flights.clear()
            copy
        }
        if (pending.isNotEmpty()) {
            diagnostic("event=flights_cancelled reason=$reason count=${pending.size}")
        }
        pending.forEach { it.cancel() }
    }

    private suspend fun profile(): Profile {
        val voice = renderer.configuration()
        preparedProfile?.takeIf { it.voice == voice }?.let { return it }
        return synchronized(flights) {
            preparedProfile?.takeIf { it.voice == voice }
                ?: Profile(
                    voice,
                    sentenceAudioKey("", voice),
                    "engine=${diagnosticMetadata(voice.engine)} voice=${diagnosticMetadata(voice.voice).take(200)}"
                ).also {
                    preparedProfile = it
                    diagnostic(
                        "event=configuration profile=${it.identity.take(12)} " +
                            "engine=${diagnosticMetadata(voice.engine)} format=${voice.format}"
                    )
                }
        }
    }

    private fun NarrationAudio.withProfile(profile: Profile): NarrationAudio = copy(
        configuration = profile.identity,
        diagnosticIdentity = profile.description
    )

    private fun diagnostic(report: String) {
        runCatching { onDiagnostic(report) }
    }

    private companion object {
        /**
         * A revision change is finite in practice; this bound only stops a pathological engine
         * that keeps changing identity during one request. It never turns a genuine change into a
         * user-visible Device error.
         */
        const val MAX_PROFILE_RECONCILIATIONS = 4
    }
}

private fun SystemSpeechError.asNarrationFailure(): NarrationFailure = when (this) {
    is SystemSpeechError.TooLong -> NarrationFailure.TooLong()
    is SystemSpeechError.Incomplete -> NarrationFailure.InvalidAudio()
    is SystemSpeechError.Storage -> NarrationFailure.Storage()
    is SystemSpeechError.ConfigurationChanged -> NarrationFailure.Unavailable()
    else -> NarrationFailure.Device(code)
}
