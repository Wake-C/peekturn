package app.peekturn.reader.narration

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One real Readium paragraph block; no invented layout or chapter flattening. */
internal data class NarrationParagraph(
    val sentences: List<String>,
    val continuesCurrentParagraph: Boolean = false
)

/**
 * What the buffer can honestly promise right now.
 *
 * [readyDurationMs] is contiguous ready playback time from the current sentence, in real playback
 * time for the current speed (actual clip duration divided by [BufferedNarrationResolver]'s speed).
 * [readyParagraphs] counts fully ready real following paragraphs; [pendingSynthesis] counts active
 * provider requests. [burst] says whether the policy currently allows two synthesis producers.
 */
internal data class NarrationBufferState(
    val buffering: Boolean = false,
    val readyDurationMs: Long = 0L,
    val burst: Boolean = false,
    val readyParagraphs: Int = 0,
    val targetParagraphs: Int = 8,
    val pendingSynthesis: Int = 0
)

/**
 * Wraps one sentence resolver with demand-first lookahead.
 *
 * - Cold startup waits for the current clip, two complete real following paragraphs, and at least
 *   [COLD_START_MIN_DURATION_MS] of contiguous playable time. EOF or failed lookahead ends the wait
 *   without inventing the missing reserve.
 * - Rolling reserve targets [TARGET_LOOKAHEAD_PARAGRAPHS] complete real paragraphs and an adaptive
 *   playable duration, bounded by [MAX_HIGH_WATER_MS] and [MAX_LOOKAHEAD_SENTENCES] sentences.
 * - At most two synthesis requests exist in burst and one in steady state. Low water restores burst.
 * - Intent/configuration changes invalidate stale work; completed clips survive pause/cancel at the
 *   same reading intent and close releases the session.
 *
 * A source slower than playback is not made gapless: the current demand still waits, the state
 * stays honestly buffering, and the high-water mark grows to reflect the observed rate.
 */
internal class BufferedNarrationResolver(
    private val source: NarrationAudioResolver,
    private val scope: CoroutineScope,
    private val following: (String) -> Flow<NarrationParagraph>,
    private val playbackSpeed: () -> Float,
    private val intentGeneration: () -> Long,
    private val onDiagnostic: (String) -> Unit = {},
    private val onState: (NarrationBufferState) -> Unit = {}
) : NarrationAudioResolver {

    private val lock = Any()
    private val publishLock = Any()

    /** Wakes startup and lookahead backpressure without polling. */
    private val progress = MutableStateFlow(0L)

    /** Audio requests still waiting for a resolved clip; ready-map answers never increment it. */
    private val pendingDemands = AtomicInteger(0)

    /** Opaque demand identity: correlative only, never derived from book text. */
    private val demandCounter = AtomicLong(0L)

    /** Reports built while [lock] is held and emitted only after it is released. */
    private val diagnosticReports = ArrayDeque<String>()

    @Volatile
    private var published = NarrationBufferState()

    private var session: Session? = null
    private var sessionCounter = 0L

    /** Sentences from the current demand forward; indices are absolute within the session. */
    private val slots = ArrayDeque<Slot>()

    /** Queued but not yet active render requests. */
    private val tasks = ArrayDeque<Work>()

    /** At most one source flight per sentence; demand promotes a queued or running prefetch. */
    private val flights = HashMap<String, Flight>()
    private val failed = HashSet<String>()

    /** Complete audio for the current session, keyed by sentence text (a cache unit). */
    private val ready = HashMap<String, NarrationAudio>()

    private val active = LinkedHashMap<Job, Work>()
    private var knownConfiguration: String? = null
    private var observedRenderRatio = 1.0
    private var highWaterMs = BASE_HIGH_WATER_MS
    private var burst = true
    private var paused = false

    // The first demand stays cold; completed playback or a user replay may reuse a ready clip.
    private var cachedReplayPreferred = false

    private class Slot(
        val index: Long,
        val sentence: String,
        val paragraphStart: Long,
        val paragraphEnd: Long,
        val initialParagraph: Boolean = false
    )

    private class Work(
        val sentence: String,
        val sequenceIndex: Long,
        var demand: Boolean,
        val configuration: String
    ) {
        /** Set only by this buffer's own intent/reset/preemption, never by a source supersession. */
        var canceledByPolicy = false

        /** Diagnostic age anchor; zero until the work is actually launched. */
        var startedAtNs = 0L
    }

    private class Flight(val sentence: String) {
        val result = CompletableDeferred<Result<NarrationAudio>>()
        var demand = false
    }

    /** Internal retry signal: the completed answer belongs to a previous provider identity. */
    private object Superseded : Exception("Narration audio belongs to an older configuration")

    private class DemandPlan(
        val owner: Session,
        val flight: Flight?,
        val ready: NarrationAudio?,
        val startup: Boolean,
        val preferCachedReplay: Boolean
    )

    private class Session(
        val id: Long,
        val generation: Long,
        var configuration: String
    ) {
        var cursor = 0L
        var nextIndex = 0L
        var firstDemand = true
        var startupInProgress = false
        var followingComplete = false
        var followingFailed = false
        var targetDiagnosticEmitted = false
        var followingJob: Job? = null
    }

    override suspend fun configuration(): String = source.configuration()

    /** Navigation/pause opts this playback session into immediate, identity-checked cache replay. */
    fun preferCachedReplay() {
        synchronized(lock) { cachedReplayPreferred = true }
    }

    override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
        if (sentence.isBlank()) return Result.failure(NarrationFailure.Unavailable())
        val demandId = demandCounter.incrementAndGet()
        val demandStartedAtNs = System.nanoTime()
        pendingDemands.incrementAndGet()
        publish()
        var succeeded = false
        var outcome = "failure"
        var requestedGeneration = -1L
        try {
            requestedGeneration = intentGeneration()
            val result = audioForInternal(sentence, demandId, demandStartedAtNs)
            succeeded = result.isSuccess
            if (succeeded) preferCachedReplay()
            outcome = if (succeeded) "ready" else "failure"
            return result
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (failure: NarrationFailure) {
            return Result.failure(failure)
        } catch (_: Exception) {
            return Result.failure(NarrationFailure.Unavailable())
        } finally {
            val remaining = pendingDemands.decrementAndGet()
            synchronized(lock) {
                if (remaining == 0 && !succeeded) {
                    session?.let {
                        it.startupInProgress = false
                        it.firstDemand = true
                    }
                }
                val activeSession = session
                queueDiagnosticLocked(
                    "event=buffer_demand_release demand=$demandId " +
                        "bufferSession=${activeSession?.id ?: 0} " +
                        "generation=${activeSession?.generation ?: -1} " +
                        "requestedGeneration=$requestedGeneration result=$outcome " +
                        "elapsedMs=${elapsedMs(demandStartedAtNs)} pendingDemands=$remaining " +
                        "paused=$paused"
                )
            }
            publish()
            flushDiagnostics()
        }
    }


    private suspend fun audioForInternal(
        sentence: String,
        demandId: Long,
        demandStartedAtNs: Long
    ): Result<NarrationAudio> {
        var retries = 0

        while (true) {
            val generation = intentGeneration()
            val configuration = try {
                source.configuration()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                return Result.failure(NarrationFailure.Unavailable())
            } catch (failure: NarrationFailure) {
                return Result.failure(failure)
            } catch (_: Exception) {
                return Result.failure(NarrationFailure.Unavailable())
            }

            val plan = synchronized(lock) {
                paused = false
                val owner = ensureSessionLocked(generation, configuration)
                locateOrRestartLocked(owner, sentence)

                val startup = owner.firstDemand
                if (startup) {
                    // Claim the startup reserve before launching work so a concurrent request
                    // cannot also block on cold-start preparation.
                    owner.firstDemand = false
                    owner.startupInProgress = true
                }

                val prepared = ready[sentence]
                val alreadyReady = if (prepared != null && prepared.file.isFile) {
                    prepared
                } else {
                    // The persistent cache may have evicted a clip during a long session; never
                    // hand playback a path that no longer exists.
                    if (prepared != null) ready.remove(sentence)
                    null
                }
                var flight: Flight? = null
                if (alreadyReady == null) {
                    val shared = flights.getOrPut(sentence) { Flight(sentence) }
                    shared.demand = true
                    flight = shared
                    failed.remove(sentence)
                    active.entries.firstOrNull { it.value.sentence == sentence }?.value?.demand = true
                    val queued = tasks.firstOrNull { it.sentence == sentence }
                    if (queued != null) {
                        queued.demand = true
                    } else if (active.values.none { it.sentence == sentence }) {
                        tasks.addLast(
                            Work(
                                sentence = sentence,
                                sequenceIndex = owner.cursor,
                                demand = true,
                                configuration = owner.configuration
                            )
                        )
                    }
                    preemptSpeculativeLocked(sentence)
                }
                // Consuming ready clips lowers the reserve just as finishing a clip raises it;
                // the policy and speculative queue are re-evaluated on every real request.
                refreshModeLocked(owner)
                scheduleSpeculativeLocked(owner)
                val nextPlan = DemandPlan(owner, flight, alreadyReady, startup, cachedReplayPreferred)
                queueDiagnosticLocked(
                    demandDiagnosticLocked(
                        event = "buffer_demand_start",
                        demandId = demandId,
                        owner = owner,
                        startup = startup,
                        extra = "source=${if (alreadyReady != null) "ready" else "render"} " +
                            "fromCache=${alreadyReady?.fromCache ?: false}"
                    )
                )
                nextPlan
            }
            flushDiagnostics()

            val owner = plan.owner
            val readyAudio = plan.ready
            if (readyAudio != null) {
                publish()
                pump()
                ensureCurrent(owner)
                val sessionConfiguration = synchronized(lock) { owner.configuration }
                if (sessionConfiguration != configuration) {
                    if (retries++ < MAX_CONFIG_RETRIES) continue
                    endStartup(owner)
                    publish()
                    return Result.failure(NarrationFailure.Unavailable())
                }
                val beforeCompanion = latestConfiguration()
                if (!isCurrentConfiguration(owner, beforeCompanion)) {
                    if (retries++ < MAX_CONFIG_RETRIES && reconfigure(owner, beforeCompanion)) continue
                    endStartup(owner)
                    publish()
                    return Result.failure(NarrationFailure.Unavailable())
                }
                synchronized(lock) {
                    queueDiagnosticLocked(
                        demandDiagnosticLocked(
                            event = "buffer_demand_clip_ready",
                            demandId = demandId,
                            owner = owner,
                            startup = plan.startup,
                            extra = "source=ready fromCache=${readyAudio.fromCache} " +
                                "waitMs=${elapsedMs(demandStartedAtNs)} " +
                                "gate=${if (!plan.startup) "none" else if (plan.preferCachedReplay) "cached-replay" else "cold-start"}"
                        )
                    )
                }
                flushDiagnostics()
                if (plan.startup) {
                    if (plan.preferCachedReplay) {
                        reportCachedReplay(owner, demandId, "ready")
                    } else {
                        val startupDecision = awaitColdStartReserve(owner, demandId, demandStartedAtNs)
                        reportStartupDegraded(owner, startupDecision, demandId)
                    }
                    ensureCurrent(owner)
                    val afterSessionConfiguration = synchronized(lock) { owner.configuration }
                    if (afterSessionConfiguration != configuration) {
                        if (retries++ < MAX_CONFIG_RETRIES) continue
                        endStartup(owner)
                        publish()
                        return Result.failure(NarrationFailure.Unavailable())
                    }
                    val afterCompanion = latestConfiguration()
                    if (!isCurrentConfiguration(owner, afterCompanion)) {
                        if (retries++ < MAX_CONFIG_RETRIES && reconfigure(owner, afterCompanion)) continue
                        endStartup(owner)
                        publish()
                        return Result.failure(NarrationFailure.Unavailable())
                    }
                    endStartup(owner)
                    publish()
                }
                return Result.success(readyAudio.copy(fromCache = true))
            }

            val flight = requireNotNull(plan.flight)
            pump()

            val result = try {
                flight.result.await()
            } catch (cancelled: CancellationException) {
                endStartup(owner)
                publish()
                throw cancelled
            }
            ensureCurrent(owner)

            val sessionConfiguration = synchronized(lock) { owner.configuration }
            if (sessionConfiguration != configuration || result.exceptionOrNull() === Superseded) {
                if (retries++ < MAX_CONFIG_RETRIES) continue
                endStartup(owner)
                publish()
                return Result.failure(NarrationFailure.Unavailable())
            }

            // A typed failure plus a changed provider identity means the answer is for the old
            // voice/model. Re-resolve the current sentence under the latest identity instead of
            // handing Readium stale audio or letting a source cancellation strand the caller.
            val latest = latestConfiguration()
            if (!isCurrentConfiguration(owner, latest)) {
                if (retries++ < MAX_CONFIG_RETRIES && reconfigure(owner, latest)) continue
                endStartup(owner)
                publish()
                return Result.failure(NarrationFailure.Unavailable())
            }

            if (result.isSuccess) {
                val audio = result.getOrNull()
                synchronized(lock) {
                    queueDiagnosticLocked(
                        demandDiagnosticLocked(
                            event = "buffer_demand_clip_ready",
                            demandId = demandId,
                            owner = owner,
                            startup = plan.startup,
                            extra = "source=render fromCache=${audio?.fromCache ?: false} " +
                                "waitMs=${elapsedMs(demandStartedAtNs)} " +
                                "gate=${if (!plan.startup) "none" else if (plan.preferCachedReplay && audio?.fromCache == true) "cached-replay" else "cold-start"}"
                        )
                    )
                }
                flushDiagnostics()
            }
            if (plan.startup && result.isSuccess) {
                if (plan.preferCachedReplay && result.getOrNull()?.fromCache == true) {
                    reportCachedReplay(owner, demandId, "disk")
                } else {
                    val startupDecision = awaitColdStartReserve(owner, demandId, demandStartedAtNs)
                    reportStartupDegraded(owner, startupDecision, demandId)
                }
                ensureCurrent(owner)
                val afterSessionConfiguration = synchronized(lock) { owner.configuration }
                if (afterSessionConfiguration != configuration) {
                    if (retries++ < MAX_CONFIG_RETRIES) continue
                    endStartup(owner)
                    publish()
                    return Result.failure(NarrationFailure.Unavailable())
                }
                val afterCompanion = latestConfiguration()
                if (!isCurrentConfiguration(owner, afterCompanion)) {
                    if (retries++ < MAX_CONFIG_RETRIES && reconfigure(owner, afterCompanion)) continue
                    endStartup(owner)
                    publish()
                    return Result.failure(NarrationFailure.Unavailable())
                }
                endStartup(owner)
            } else if (plan.startup) {
                endStartup(owner)
            }
            publish()
            return result
        }
    }

    private fun reportCachedReplay(owner: Session, demandId: Long, source: String) {
        emitDiagnostic(
            "event=buffer_cached_replay demand=$demandId bufferSession=${owner.id} " +
                "generation=${owner.generation} source=$source gate=bypassed"
        )
    }

    private suspend fun latestConfiguration(): String = try {
        source.configuration()
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        throw NarrationFailure.Unavailable()
    }

    private fun isCurrentConfiguration(owner: Session, configuration: String): Boolean =
        synchronized(lock) { session === owner && owner.configuration == configuration }

    private fun reconfigure(owner: Session, configuration: String): Boolean =
        synchronized(lock) { reconfigureLocked(owner, configuration) }

    private fun ensureCurrent(owner: Session) {
        if (synchronized(lock) { session !== owner }) {
            throw CancellationException("Narration buffer session changed")
        }
    }

    /**
     * The provider now has a different identity. Ready clips and queued work from the old
     * identity are unusable, but the reading sequence and cursor stay. Active workers are left to
     * finish and are rejected by their post-guard; rerunning them here would duplicate a caller.
     */
    private fun reconfigureLocked(owner: Session, configuration: String): Boolean {
        if (session !== owner) return false
        if (owner.configuration == configuration) return true
        val readyDropped = ready.size
        val failedDropped = failed.size
        val queuedDropped = tasks.size
        val activeKept = active.size
        owner.configuration = configuration
        knownConfiguration = configuration
        ready.clear()
        failed.clear()
        tasks.clear()
        observedRenderRatio = 1.0
        highWaterMs = BASE_HIGH_WATER_MS
        burst = true
        owner.targetDiagnosticEmitted = false
        owner.firstDemand = true
        queueDiagnosticLocked(
            "event=buffer_reconfigure reason=provider-identity bufferSession=${owner.id} " +
                "generation=${owner.generation} seq=${owner.cursor} readyDropped=$readyDropped " +
                "failedDropped=$failedDropped queuedDropped=$queuedDropped activeKept=$activeKept"
        )
        scheduleSpeculativeLocked(owner)
        pumpLocked()
        bumpProgressLocked()
        return true
    }

    override fun cancelPending() {
        synchronized(lock) {
            paused = true
            cancelWorkLocked("cancel-pending")
            session?.let { owner ->
                owner.firstDemand = owner.firstDemand || owner.startupInProgress
                owner.startupInProgress = false
            }
        }
        source.cancelPending()
        publish()
        flushDiagnostics()
    }

    override fun close() {
        synchronized(lock) {
            paused = true
            cancelWorkLocked("close")
            session?.followingJob?.cancel()
            session = null
            slots.clear()
            ready.clear()
            failed.clear()
            burst = true
        }
        source.close()
        publish()
        flushDiagnostics()
    }

    private fun ensureSessionLocked(generation: Long, configuration: String): Session {
        val existing = session
        if (existing != null && existing.generation == generation) {
            // A provider identity change keeps the reading sequence and any waiting demand; old
            // results are rejected by their post-guard and re-resolved under the new identity.
            if (existing.configuration != configuration) reconfigureLocked(existing, configuration)
            return existing
        }
        val previousConfiguration = knownConfiguration
        if (previousConfiguration != null && previousConfiguration != configuration) {
            // A different voice/model changes render speed; old measurements do not describe it.
            observedRenderRatio = 1.0
            highWaterMs = BASE_HIGH_WATER_MS
        }
        knownConfiguration = configuration
        val slotsDropped = slots.size
        val readyDropped = ready.size
        val failedDropped = failed.size
        val configurationChanged = previousConfiguration != null &&
            previousConfiguration != configuration
        if (existing != null) {
            cancelWorkLocked("intent-reset")
            existing.followingJob?.cancel()
            // An explicit-intent change makes all in-flight source work obsolete.
            source.cancelPending()
        }
        slots.clear()
        ready.clear()
        failed.clear()
        burst = true
        val created = Session(++sessionCounter, generation, configuration).also { session = it }
        queueDiagnosticLocked(
            "event=buffer_session_reset reason=${if (existing == null) "first-intent" else "intent-reset"} " +
                "bufferSession=${created.id} generation=$generation " +
                "previousSession=${existing?.id ?: 0} previousGeneration=${existing?.generation ?: -1} " +
                "slotsDropped=$slotsDropped readyDropped=$readyDropped failedDropped=$failedDropped " +
                "configurationChanged=$configurationChanged"
        )
        return created
    }

    /**
     * Finds the demanded sentence in the already streamed reading order. Normal sentence
     * transitions only advance the cursor. A sentence outside the known order (resume, retry after
     * content failure, or a navigation the caller did not cancel) starts a fresh following flow.
     */
    private fun locateOrRestartLocked(owner: Session, sentence: String) {
        val match = slots.firstOrNull { it.index >= owner.cursor && it.sentence == sentence }
        if (match == null) {
            restartSequenceLocked(owner, sentence)
            return
        }
        if (match.index != owner.cursor) {
            owner.cursor = match.index
            pruneLocked(owner)
        }
    }

    private fun restartSequenceLocked(owner: Session, sentence: String) {
        val previousSeq = owner.cursor
        val slotsDropped = slots.size
        val readyDropped = ready.size
        val failedDropped = failed.size
        cancelWorkLocked("sequence-restart")
        slots.clear()
        ready.clear()
        failed.clear()
        owner.cursor = 0L
        owner.nextIndex = 0L
        owner.followingComplete = false
        owner.followingFailed = false
        owner.firstDemand = true
        owner.targetDiagnosticEmitted = false
        owner.startupInProgress = false
        slots.addLast(
            Slot(
                index = owner.nextIndex++,
                sentence = sentence,
                paragraphStart = 0L,
                paragraphEnd = 1L,
                initialParagraph = true
            )
        )
        queueDiagnosticLocked(
            "event=buffer_sequence_restart reason=sentence-not-in-sequence " +
                "bufferSession=${owner.id} generation=${owner.generation} seq=${owner.cursor} " +
                "previousSeq=$previousSeq slotsDropped=$slotsDropped readyDropped=$readyDropped " +
                "failedDropped=$failedDropped slots=${slots.size} mode=${if (burst) "burst" else "steady"}"
        )
        startFollowingLocked(owner, sentence)
    }

    private fun startFollowingLocked(owner: Session, sentence: String) {
        owner.followingJob?.cancel()
        owner.followingJob = scope.launch {
            try {
                following(sentence).collect { paragraph -> onParagraph(owner, paragraph) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (session === owner) owner.followingFailed = true
                }
            } finally {
                synchronized(lock) {
                    if (session === owner) {
                        owner.followingComplete = true
                        refreshModeLocked(owner)
                        pumpLocked()
                    }
                    bumpProgressLocked()
                }
                reportTargetUnavailable(owner)
            }
        }
    }

    private suspend fun onParagraph(owner: Session, paragraph: NarrationParagraph) {
        val sentences = paragraph.sentences
        if (sentences.isEmpty()) return
        var index = 0
        var paragraphStart = -1L
        var paragraphEnd = -1L
        while (index < sentences.size) {
            val windowFull = synchronized(lock) {
                if (session !== owner) return
                if (intentGeneration() != owner.generation) {
                    owner.followingJob?.cancel()
                    return
                }
                if (paragraphStart < 0L) {
                    paragraphStart = owner.nextIndex
                    paragraphEnd = paragraphStart + sentences.size
                }
                var appended = 0
                // A valid EPUB block may be arbitrarily long. Keep the real sentence list from
                // Readium (upstream memory) but hold at most one bounded window in our queue.
                while (index < sentences.size && slots.size < MAX_LOOKAHEAD_SENTENCES) {
                    slots.addLast(
                        Slot(
                            index = owner.nextIndex++,
                            sentence = sentences[index],
                            paragraphStart = paragraphStart,
                            paragraphEnd = paragraphEnd,
                            initialParagraph = paragraph.continuesCurrentParagraph
                        )
                    )
                    index++
                    appended++
                }
                if (appended > 0) {
                    scheduleSpeculativeLocked(owner)
                    refreshModeLocked(owner)
                    pumpLocked()
                }
                slots.size >= MAX_LOOKAHEAD_SENTENCES && index < sentences.size
            }
            reportTargetUnavailable(owner)
            if (windowFull) awaitLookaheadRoom(owner)
        }
    }

    /** Stops collecting the real paragraph stream once the bounded sentence window is full. */
    private suspend fun awaitLookaheadRoom(owner: Session) {
        while (true) {
            val seen = progress.value
            val count = synchronized(lock) {
                if (session !== owner) return
                slots.size
            }
            if (count < MAX_LOOKAHEAD_SENTENCES) return
            progress.first { it != seen }
        }
    }

    private fun preemptSpeculativeLocked(sentence: String) {
        val limit = if (burst) 2 else 1
        if (active.size < limit) return
        if (active.values.any { it.sentence == sentence }) return
        // Never cancel a render another demand is waiting for. All speculative work is
        // expendable, and the underlying resolver's cancelPending is the only way to stop the
        // provider work it already owns.
        if (flights.any { (text, flight) -> flight.demand && text != sentence }) return
        if (active.values.any { it.demand }) return
        val producers = producerSummaryLocked()
        queueDiagnosticLocked(
            "event=buffer_work_cancel reason=demand-preempt canceledActive=${active.size} " +
                "canceledQueued=0 canceledFlights=0 sourceCancel=true limit=$limit " +
                "bufferSession=${session?.id ?: 0} generation=${session?.generation ?: -1} " +
                "seq=${session?.cursor ?: -1} $producers"
        )
        source.cancelPending()
        active.entries.forEach { (job, work) ->
            work.canceledByPolicy = true
            job.cancel()
        }
    }

    private fun scheduleSpeculativeLocked(owner: Session) {
        if (session !== owner || paused) return
        // The burst-exit target is not the speculative ceiling. Once steady, the single producer
        // keeps filling toward the 10-minute ceiling or the bounded sentence window. A huge real
        // paragraph cannot raise that ceiling or count as multiple ready paragraphs.
        val readyMs = readyDurationLocked(owner)
        if (readyMs >= MAX_HIGH_WATER_MS) return
        if (tasks.size >= MAX_QUEUED_TASKS) return
        for (slot in slots) {
            if (slot.index < owner.cursor) continue
            val sentence = slot.sentence
            if (ready.containsKey(sentence)) continue
            // Do not synthesize past a failed sentence: the contiguous reserve cannot advance
            // through it, and the failure is not hidden by ready-looking later work.
            if (failed.contains(sentence)) break
            if (flights.containsKey(sentence)) continue
            if (tasks.any { it.sentence == sentence }) continue
            tasks.addLast(
                Work(sentence, slot.index, demand = false, configuration = owner.configuration)
            )
            if (tasks.size >= MAX_QUEUED_TASKS) break
        }
    }

    private fun refreshModeLocked(owner: Session) {
        if (session !== owner) return
        val readyMs = readyDurationLocked(owner)
        val paragraphs = readyParagraphsLocked(owner)
        val healthy = readyMs >= MAX_HIGH_WATER_MS ||
            (readyMs >= highWaterMs && paragraphs >= TARGET_LOOKAHEAD_PARAGRAPHS)
        if (burst) {
            if (healthy) burst = false
        } else if (readyMs < highWaterMs / LOW_WATER_DIVISOR) {
            burst = true
        }
        val currentReady = slots.firstOrNull { it.index == owner.cursor }
            ?.let { ready.containsKey(it.sentence) } ?: false
        if (!currentReady && pendingDemands.get() > 0) burst = true
    }

    private fun readyDurationLocked(owner: Session): Long {
        var clipMs = 0L
        for (slot in slots) {
            if (slot.index < owner.cursor) continue
            val audio = ready[slot.sentence] ?: break
            clipMs += audio.durationMs
        }
        return (clipMs / safeSpeed()).toLong()
    }

    /** Number of real paragraphs fully inside the contiguous ready reserve, past the cursor block. */
    private fun readyParagraphsLocked(owner: Session): Int {
        var count = 0
        for (slot in slots) {
            if (slot.index < owner.cursor) continue
            if (!ready.containsKey(slot.sentence)) break
            if (slot.initialParagraph) continue
            if (slot.index == slot.paragraphEnd - 1 && slot.paragraphStart > owner.cursor) count++
        }
        return count
    }

    private fun safeSpeed(): Double {
        val speed = playbackSpeed().toDouble()
        return if (!speed.isFinite() || speed <= 0.0) 1.0 else speed.coerceIn(0.25, 4.0)
    }

    private fun pump() = synchronized(lock) { pumpLocked() }

    private fun pumpLocked() {
        val owner = session ?: return
        if (paused || intentGeneration() != owner.generation) return
        while (active.size < if (burst) 2 else 1) {
            // A queued speculative request is not permission to keep growing a full reserve.
            // An already-running clip may finish beyond the time target; no new one starts.
            if (readyDurationLocked(owner) >= MAX_HIGH_WATER_MS && tasks.none { it.demand }) break
            val work = nextWorkLocked() ?: break
            work.startedAtNs = System.nanoTime()
            val flight = flights.getOrPut(work.sentence) { Flight(work.sentence) }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runWork(owner, work, flight)
            }
            active[job] = work
            job.start()
        }
    }

    private fun nextWorkLocked(): Work? {
        while (tasks.isNotEmpty()) {
            val next = tasks.minWithOrNull(
                compareBy<Work>({ if (it.demand) 0 else 1 }, { it.sequenceIndex })
            ) ?: return null
            tasks.remove(next)
            if (ready.containsKey(next.sentence)) continue
            return next
        }
        return null
    }

    private suspend fun runWork(owner: Session, work: Work, flight: Flight) {
        val startedAt = System.nanoTime()
        var result: Result<NarrationAudio>? = null
        var configurationNow: String? = null
        try {
            result = try {
                source.audioFor(work.sentence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure as? NarrationFailure ?: NarrationFailure.Unavailable())
            }
            configurationNow = try {
                source.configuration()
            } catch (cancelled: CancellationException) {
                result = null
                throw cancelled
            } catch (failure: Exception) {
                result = Result.failure(failure as? NarrationFailure ?: NarrationFailure.Unavailable())
                null
            }
        } finally {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val job = currentCoroutineContext()[Job]
            val completed = result
            val outcome = synchronized(lock) {
                active.remove(job)
                if (flights[work.sentence] === flight) flights.remove(work.sentence)

                // The provider changed identity while this render was active. Update the session
                // now so the queue and the waiting demand move to the latest identity together.
                val sourceChanged = completed != null &&
                    configurationNow != null &&
                    session === owner &&
                    owner.configuration != configurationNow
                if (sourceChanged) reconfigureLocked(owner, configurationNow!!)

                var outcomeValue: Result<NarrationAudio>? = completed
                val sameSession = !paused &&
                    session === owner &&
                    intentGeneration() == owner.generation
                val sameConfiguration = work.configuration == owner.configuration &&
                    (configurationNow == null || configurationNow == work.configuration)

                if (completed == null) {
                    // A cancellation from our own policy is the caller's cancellation. A
                    // cancellation from the provider while our caller is still current is a
                    // supersession: report a retry signal so the demand can re-resolve instead
                    // of leaking CancellationException to Readium.
                    outcomeValue = when {
                        work.canceledByPolicy -> null
                        !sameSession -> null
                        work.configuration != owner.configuration -> Result.failure<NarrationAudio>(Superseded)
                        else -> {
                            if (sameConfiguration) failed.add(work.sentence)
                            Result.failure(NarrationFailure.Unavailable())
                        }
                    }
                } else if (!sameSession) {
                    outcomeValue = Result.failure(NarrationFailure.Unavailable())
                } else if (!sameConfiguration) {
                    // Discard old-identity work without marking it failed: the demand's retry
                    // must be free to render under the new identity.
                    outcomeValue = Result.failure<NarrationAudio>(Superseded)
                } else {
                    val accepted = completed.map { audio ->
                        if (audio.configuration == work.configuration) audio
                        else audio.copy(configuration = work.configuration)
                    }
                    outcomeValue = accepted
                    accepted.onSuccess { audio ->
                        ready[work.sentence] = audio
                        failed.remove(work.sentence)
                        observeRenderLocked(audio, elapsedMs)
                    }
                    accepted.onFailure {
                        // A demand clears this on its explicit retry; prefetch never hot-loops.
                        failed.add(work.sentence)
                    }
                }

                if (!sourceChanged) {
                    scheduleSpeculativeLocked(owner)
                    refreshModeLocked(owner)
                    pumpLocked()
                }
                outcomeValue
            }
            publish()
            flushDiagnostics()
            synchronized(lock) { bumpProgressLocked() }
            if (outcome != null) {
                flight.result.complete(outcome)
            } else {
                flight.result.cancel(CancellationException("Narration buffer work cancelled"))
            }
            reportTargetUnavailable(owner)
        }
    }

    private fun observeRenderLocked(audio: NarrationAudio, elapsedMs: Long) {
        if (audio.fromCache || audio.durationMs <= 0L || elapsedMs <= 0L) return
        val playbackMs = audio.durationMs / safeSpeed()
        if (playbackMs <= 0.0) return
        val observed = elapsedMs / playbackMs
        observedRenderRatio = maxOf(1.0, observedRenderRatio * 0.7 + observed * 0.3)
        highWaterMs = (BASE_HIGH_WATER_MS * observedRenderRatio)
            .toLong()
            .coerceIn(BASE_HIGH_WATER_MS, MAX_HIGH_WATER_MS)
    }

    /** Waits for the cold runway or returns the exact honest terminal reason. */
    private suspend fun awaitColdStartReserve(
        owner: Session,
        demandId: Long,
        startedAtNs: Long
    ): StartupDecision {
        var waiting = false
        var blocked: String? = null
        while (true) {
            val seen = progress.value
            var report: String? = null
            val decision = synchronized(lock) {
                if (session !== owner) {
                    report = coldGateDiagnosticLocked(
                        event = "buffer_cold_gate_exit",
                        owner = owner,
                        demandId = demandId,
                        reason = "session-changed",
                        startedAtNs = startedAtNs,
                        blocked = blocked
                    )
                    return@synchronized StartupDecision.INVALIDATED
                }
                val candidate = when {
                    readyDurationLocked(owner) >= COLD_START_MIN_DURATION_MS &&
                        readyParagraphsLocked(owner) >= COLD_START_MIN_PARAGRAPHS ->
                        StartupDecision.READY
                    owner.followingFailed ||
                        slots.any { it.index >= owner.cursor && failed.contains(it.sentence) } ->
                        StartupDecision.LOOKAHEAD_FAILED
                    owner.followingComplete && (
                        slots.all { it.index < owner.cursor || ready.containsKey(it.sentence) } ||
                            (availableParagraphsLocked(owner) < COLD_START_MIN_PARAGRAPHS &&
                                readyDurationLocked(owner) >= COLD_START_MIN_DURATION_MS)
                        ) -> StartupDecision.EOF
                    readyDurationLocked(owner) >= MAX_HIGH_WATER_MS ->
                        StartupDecision.DURATION_BOUND
                    slots.size >= MAX_LOOKAHEAD_SENTENCES &&
                        readyDurationLocked(owner) >= COLD_START_MIN_DURATION_MS &&
                        owner.followingJob?.isActive == true ->
                        StartupDecision.WINDOW_BOUND
                    else -> {
                        scheduleSpeculativeLocked(owner)
                        pumpLocked()
                        StartupDecision.WAIT
                    }
                }
                if (candidate != StartupDecision.WAIT) {
                    report = coldGateDiagnosticLocked(
                        event = "buffer_cold_gate_exit",
                        owner = owner,
                        demandId = demandId,
                        reason = gateReason(candidate),
                        startedAtNs = startedAtNs,
                        blocked = blocked
                    )
                } else {
                    val nextBlocked = coldGateBlockedLocked(owner)
                    if (!waiting) {
                        waiting = true
                        blocked = nextBlocked
                        report = coldGateDiagnosticLocked(
                            event = "buffer_cold_gate_enter",
                            owner = owner,
                            demandId = demandId,
                            reason = nextBlocked,
                            startedAtNs = startedAtNs,
                            blocked = nextBlocked
                        )
                    } else if (nextBlocked != blocked) {
                        blocked = nextBlocked
                        report = coldGateDiagnosticLocked(
                            event = "buffer_cold_gate_stage",
                            owner = owner,
                            demandId = demandId,
                            reason = nextBlocked,
                            startedAtNs = startedAtNs,
                            blocked = nextBlocked
                        )
                    }
                }
                candidate
            }
            report?.let(::emitDiagnostic)
            flushDiagnostics()
            if (decision != StartupDecision.WAIT) return decision
            progress.first { it != seen }
        }
    }

    private fun gateReason(decision: StartupDecision): String = when (decision) {
        StartupDecision.READY -> "ready"
        StartupDecision.INVALIDATED -> "session-changed"
        else -> decision.diagnosticReason ?: decision.name.lowercase()
    }

    /**
     * Diagnostic-only description of the next thing the cold gate needs. Sequence indexes and
     * producer counts are safe; no sentence text is ever included.
     */
    private fun coldGateBlockedLocked(owner: Session): String {
        val current = slots.firstOrNull { it.index == owner.cursor }
            ?: return "no-current-slot"
        if (!ready.containsKey(current.sentence)) {
            val producing = active.values.any { it.sentence == current.sentence } ||
                tasks.any { it.sentence == current.sentence } ||
                flights.containsKey(current.sentence)
            return if (producing) "current-rendering" else "current-no-producer"
        }
        val next = slots.firstOrNull {
            it.index > owner.cursor && !ready.containsKey(it.sentence)
        } ?: return when {
            owner.followingFailed -> "lookahead-failed"
            owner.followingComplete -> "reserve-eof"
            else -> "awaiting-paragraph"
        }
        val kind = if (readyParagraphsLocked(owner) < COLD_START_MIN_PARAGRAPHS) {
            "paragraphs"
        } else {
            "duration"
        }
        val producing = active.values.any { it.sentence == next.sentence } ||
            tasks.any { it.sentence == next.sentence } ||
            flights.containsKey(next.sentence)
        return "reserve-$kind-${if (producing) "rendering" else "no-producer"}"
    }

    private fun availableParagraphsLocked(owner: Session): Int {
        var count = 0
        var previousEnd = -1L
        for (slot in slots) {
            if (slot.index < owner.cursor || slot.initialParagraph || slot.paragraphStart <= owner.cursor) continue
            if (slot.paragraphEnd != previousEnd) {
                count++
                previousEnd = slot.paragraphEnd
            }
        }
        return count
    }

    private fun reportStartupDegraded(owner: Session, decision: StartupDecision, demandId: Long) {
        val reason = decision.diagnosticReason ?: return
        val diagnostic = synchronized(lock) {
            if (session !== owner || owner.targetDiagnosticEmitted) return
            owner.targetDiagnosticEmitted = true
            "buffer state=degraded wait=$reason bufferSession=${owner.id} " +
                "generation=${owner.generation} demand=$demandId seq=${owner.cursor} " +
                "requiredMs=$COLD_START_MIN_DURATION_MS requiredParagraphs=$COLD_START_MIN_PARAGRAPHS " +
                "readyDurationMs=${readyDurationLocked(owner)} " +
                "reserveIncludesCurrent=true " +
                "readyParagraphs=${readyParagraphsLocked(owner)} " +
                "targetParagraphs=$TARGET_LOOKAHEAD_PARAGRAPHS pendingSynthesis=${active.size} " +
                "mode=${if (burst) "burst" else "steady"}"
        }
        runCatching { onDiagnostic(diagnostic) }
    }

    private fun reportTargetUnavailable(owner: Session) {
        val diagnostic = synchronized(lock) {
            if (session !== owner || owner.targetDiagnosticEmitted) return
            val readyMs = readyDurationLocked(owner)
            val paragraphs = readyParagraphsLocked(owner)
            if (readyMs >= highWaterMs && paragraphs >= TARGET_LOOKAHEAD_PARAGRAPHS) {
                return@synchronized null
            }
            val reason = when {
                owner.followingFailed ||
                    slots.any { it.index >= owner.cursor && failed.contains(it.sentence) } ->
                    "lookahead-failed"
                slots.size >= MAX_LOOKAHEAD_SENTENCES &&
                    readyMs >= COLD_START_MIN_DURATION_MS &&
                    owner.followingJob?.isActive == true -> "window-bound"
                owner.followingComplete && active.isEmpty() && tasks.isEmpty() -> "eof"
                else -> return@synchronized null
            }
            owner.targetDiagnosticEmitted = true
            "buffer state=target-unavailable wait=$reason bufferSession=${owner.id} " +
                "generation=${owner.generation} seq=${owner.cursor} readyDurationMs=$readyMs " +
                "reserveIncludesCurrent=true readyParagraphs=$paragraphs " +
                "targetParagraphs=$TARGET_LOOKAHEAD_PARAGRAPHS " +
                "pendingSynthesis=${active.size} mode=${if (burst) "burst" else "steady"}"
        } ?: return
        runCatching { onDiagnostic(diagnostic) }
    }

    private fun endStartup(owner: Session) {
        synchronized(lock) { if (session === owner) owner.startupInProgress = false }
    }

    private fun elapsedMs(startedAtNs: Long): Long =
        (System.nanoTime() - startedAtNs) / 1_000_000L

    private fun queueDiagnosticLocked(report: String) {
        if (diagnosticReports.size == MAX_PENDING_DIAGNOSTICS) diagnosticReports.removeFirst()
        diagnosticReports.addLast(report)
    }

    private fun flushDiagnostics() {
        val reports = synchronized(lock) {
            if (diagnosticReports.isEmpty()) return
            val copy = diagnosticReports.toList()
            diagnosticReports.clear()
            copy
        }
        reports.forEach(::emitDiagnostic)
    }

    private fun emitDiagnostic(report: String) {
        runCatching { onDiagnostic(report) }
    }

    private fun followingStateLocked(owner: Session): String = when {
        owner.followingFailed -> "failed"
        owner.followingComplete -> "complete"
        owner.followingJob?.isActive == true -> "collecting"
        else -> "idle"
    }

    /**
     * Where the reserve producers are, as sequence indexes plus launch age. `d` marks the demand's
     * own render; `s` marks speculative lookahead. No sentence text or path is exposed.
     */
    private fun producerSummaryLocked(): String {
        val now = System.nanoTime()
        val activeRefs = active.values.take(MAX_DIAGNOSTIC_REFS).joinToString(",") { work ->
            val ageMs = if (work.startedAtNs == 0L) {
                0L
            } else {
                (now - work.startedAtNs) / 1_000_000L
            }
            "${work.sequenceIndex}${if (work.demand) "d" else "s"}@${ageMs}ms"
        }.ifEmpty { "-" }
        val queuedRefs = tasks.take(MAX_DIAGNOSTIC_REFS)
            .joinToString(",") { it.sequenceIndex.toString() }
            .ifEmpty { "-" }
        return "active=${active.size} activeRefs=$activeRefs queued=${tasks.size} " +
            "queuedRefs=$queuedRefs flights=${flights.size}"
    }

    private fun demandDiagnosticLocked(
        event: String,
        demandId: Long,
        owner: Session,
        startup: Boolean,
        extra: String
    ): String {
        val readyMs = readyDurationLocked(owner)
        val paragraphs = readyParagraphsLocked(owner)
        val currentClip = slots.firstOrNull { it.index == owner.cursor }
            ?.let { ready[it.sentence] }
        val currentClipMs = currentClip?.durationMs ?: 0L
        val currentMs = (currentClipMs / safeSpeed()).toLong()
        return "event=$event demand=$demandId bufferSession=${owner.id} " +
            "generation=${owner.generation} seq=${owner.cursor} startup=$startup " +
            "currentClipMs=$currentClipMs currentMs=$currentMs " +
            "reserveMs=$readyMs reserveIncludesCurrent=true " +
            "futureReserveMs=${(readyMs - currentMs).coerceAtLeast(0L)} " +
            "reserveParagraphs=$paragraphs slots=${slots.size} readyClips=${ready.size} " +
            "${producerSummaryLocked()} following=${followingStateLocked(owner)} " +
            "mode=${if (burst) "burst" else "steady"} paused=$paused $extra"
    }

    private fun coldGateDiagnosticLocked(
        event: String,
        owner: Session,
        demandId: Long,
        reason: String,
        startedAtNs: Long,
        blocked: String?
    ): String {
        val currentClip = slots.firstOrNull { it.index == owner.cursor }
            ?.let { ready[it.sentence] }
        val currentClipMs = currentClip?.durationMs ?: 0L
        val currentMs = (currentClipMs / safeSpeed()).toLong()
        val readyMs = readyDurationLocked(owner)
        return "event=$event demand=$demandId bufferSession=${owner.id} " +
            "generation=${owner.generation} seq=${owner.cursor} reason=$reason " +
            "requiredMs=$COLD_START_MIN_DURATION_MS " +
            "requiredParagraphs=$COLD_START_MIN_PARAGRAPHS " +
            "currentCached=${currentClip?.fromCache == true} " +
            "currentClipMs=$currentClipMs currentMs=$currentMs reserveMs=$readyMs " +
            "reserveIncludesCurrent=true " +
            "futureReserveMs=${(readyMs - currentMs).coerceAtLeast(0L)} " +
            "reserveParagraphs=${readyParagraphsLocked(owner)} " +
            "blocked=${blocked ?: "-"} waitMs=${elapsedMs(startedAtNs)} " +
            "slots=${slots.size} readyClips=${ready.size} ${producerSummaryLocked()} " +
            "following=${followingStateLocked(owner)} " +
            "mode=${if (burst) "burst" else "steady"} paused=$paused"
    }

    private fun cancelWorkLocked(reason: String) {
        val queuedCount = tasks.size
        val activeCount = active.size
        val pending = flights.values.toList()
        if (queuedCount + activeCount + pending.size > 0) {
            val owner = session
            queueDiagnosticLocked(
                "event=buffer_work_cancel reason=$reason canceledActive=$activeCount " +
                    "canceledQueued=$queuedCount canceledFlights=${pending.size} " +
                    "bufferSession=${owner?.id ?: 0} generation=${owner?.generation ?: -1} " +
                    "seq=${owner?.cursor ?: -1} pendingDemands=${pendingDemands.get()} " +
                    "paused=$paused ${producerSummaryLocked()}"
            )
        }
        tasks.clear()
        val jobs = active.entries.map { (job, work) ->
            work.canceledByPolicy = true
            job
        }
        active.clear()
        jobs.forEach { it.cancel() }
        flights.clear()
        pending.forEach { it.result.cancel() }
        bumpProgressLocked()
    }

    private fun pruneLocked(owner: Session) {
        while (slots.isNotEmpty() && slots.first().index < owner.cursor) {
            slots.removeFirst()
        }
        val live = slots.mapTo(HashSet<String>()) { it.sentence }
        ready.keys.retainAll(live)
        failed.retainAll(live)
        val queuedDropped = tasks.count { it.sequenceIndex < owner.cursor }
        tasks.removeAll { it.sequenceIndex < owner.cursor }
        val staleFlights = flights.filterKeys { it !in live }
        if (staleFlights.isNotEmpty()) {
            staleFlights.keys.forEach { flights.remove(it) }
            staleFlights.values.forEach { it.result.cancel() }
        }
        // Work for sentences already behind playback is not lookahead anymore. Keep work whose
        // text is still scheduled later (a repeated sentence shares the same audio flight).
        val staleActive = active.entries
            .filter {
                it.value.sequenceIndex < owner.cursor &&
                    !it.value.demand &&
                    it.value.sentence !in live
            }
            .map { (job, work) ->
                work.canceledByPolicy = true
                job.cancel()
                work
            }
        if (queuedDropped > 0 || staleFlights.isNotEmpty() || staleActive.isNotEmpty()) {
            queueDiagnosticLocked(
                "event=buffer_work_cancel reason=cursor-prune canceledActive=${staleActive.size} " +
                    "canceledQueued=$queuedDropped canceledFlights=${staleFlights.size} " +
                    "bufferSession=${owner.id} generation=${owner.generation} seq=${owner.cursor} " +
                    "pendingDemands=${pendingDemands.get()} paused=$paused " +
                    producerSummaryLocked()
            )
        }
        bumpProgressLocked()
    }

    private fun bumpProgressLocked() {
        progress.value = progress.value + 1L
    }

    private fun computeStateLocked(): NarrationBufferState {
        val owner = session
        val readyMs = if (owner != null) readyDurationLocked(owner) else 0L
        val paragraphs = if (owner != null) readyParagraphsLocked(owner) else 0
        val buffering = pendingDemands.get() > 0 || owner?.startupInProgress == true
        return NarrationBufferState(
            buffering = buffering,
            readyDurationMs = readyMs,
            burst = burst,
            readyParagraphs = paragraphs,
            targetParagraphs = TARGET_LOOKAHEAD_PARAGRAPHS,
            pendingSynthesis = active.size
        )
    }

    private fun publish() {
        synchronized(publishLock) {
            val next = synchronized(lock) { computeStateLocked() }
            if (next == published) return
            published = next
            runCatching { onState(next) }
        }
    }

    private enum class StartupDecision(val diagnosticReason: String?) {
        READY(null),
        WAIT(null),
        LOOKAHEAD_FAILED("lookahead-failed"),
        EOF("eof"),
        DURATION_BOUND("duration-bound"),
        WINDOW_BOUND("window-bound"),
        INVALIDATED(null)
    }

    private companion object {
        const val MAX_CONFIG_RETRIES = 2
        const val COLD_START_MIN_DURATION_MS = 15_000L
        const val COLD_START_MIN_PARAGRAPHS = 2
        const val TARGET_LOOKAHEAD_PARAGRAPHS = 8
        const val BASE_HIGH_WATER_MS = 60_000L
        const val MAX_HIGH_WATER_MS = 600_000L
        const val LOW_WATER_DIVISOR = 4L
        const val MAX_QUEUED_TASKS = 6
        const val MAX_LOOKAHEAD_SENTENCES = 256
        const val MAX_DIAGNOSTIC_REFS = 6
        const val MAX_PENDING_DIAGNOSTICS = 64
    }
}
