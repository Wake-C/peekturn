package app.peekturn.reader.learning

import app.peekturn.reader.model.Explanation
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.model.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One Activity's lookups. Call on the main thread; close when the reader exits. */
class LearningSession internal constructor(
    private val source: ExplanationSource = ExplanationClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val capacity: Int = 32,
    private val cache: ExplanationCache? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class State(
        val answer: Explanation = Explanation(),
        val loading: Boolean = false,
        val complete: Boolean = false,
        val failure: ExplanationFailureCategory? = null
    )

    private data class MemoryEntry(val answer: Explanation, val completedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val completed = LinkedHashMap<ExplanationRequest, MemoryEntry>(capacity, .75f, true)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var active: ExplanationRequest? = null
    private var request: Job? = null
    private var generation = 0L
    private var seenEpoch = cache?.epoch() ?: 0L
    private var closed = false

    init { require(capacity > 0) }

    fun open(
        context: LookupContext,
        profileId: String,
        config: ModelConfig,
        language: ExplanationLanguage = ExplanationLanguage.English
    ) {
        if (closed) return
        val key = ExplanationRequest.of(context, profileId, config, language)
        // A clear or an expired memory entry must not be masked by the active-key short circuit.
        if (active == key && (cache == null || cache.epoch() == seenEpoch)) {
            val memory = completed[key] ?: return
            val age = now() - memory.completedAt
            if (age >= 0L && age < EXPLANATION_CACHE_TTL_MILLIS) return
        }
        start(key, config, bypassCache = false)
    }

    /** An explicit retry is one fresh request using the caller's latest configuration. */
    fun retry(
        context: LookupContext,
        profileId: String,
        config: ModelConfig,
        language: ExplanationLanguage = ExplanationLanguage.English
    ) {
        if (!closed) start(ExplanationRequest.of(context, profileId, config, language), config, bypassCache = true)
    }

    /** Dismissing cancels pending work, but completed answers live until their absolute expiry. */
    fun dismiss(context: LookupContext) {
        val activeContext = active?.context ?: return
        if (activeContext != context && clipContext(activeContext) != clipContext(context)) return
        generation++
        request?.cancel()
        request = null
        active = null
        mutableState.value = State()
    }

    fun close() {
        closed = true
        generation++
        scope.cancel()
        request = null
        active = null
        completed.clear()
        mutableState.value = State()
    }

    private fun start(key: ExplanationRequest, config: ModelConfig, bypassCache: Boolean) {
        val token = ++generation
        request?.cancel()
        request = null
        active = key
        val reusable = key.hasContextEvidence
        if (!reusable) completed.remove(key)
        // A clear from Settings invalidates this session's completed answers as well as disk.
        val epoch = cache?.epoch() ?: 0L
        if (epoch != seenEpoch) {
            completed.clear()
            seenEpoch = epoch
        }
        if (bypassCache) completed.remove(key)
        if (!bypassCache && reusable) {
            val memory = completed[key]
            if (memory != null) {
                val age = now() - memory.completedAt
                if (age < 0L || age >= EXPLANATION_CACHE_TTL_MILLIS) {
                    completed.remove(key)
                } else {
                    mutableState.value = State(answer = memory.answer, complete = true)
                    return
                }
            }
        }
        mutableState.value = State(loading = true)
        request = scope.launch {
            if (cache != null && reusable) {
                if (bypassCache) {
                    cacheOrNull { cache.remove(key) }
                } else {
                    val cached = cacheOrNull { cache.get(key, epoch) }
                    if (generation != token || closed) return@launch
                    if (cached != null && cache.epoch() == epoch) {
                        completed[key] = MemoryEntry(cached.answer, cached.completedAt)
                        trimCompleted()
                        mutableState.value = State(answer = cached.answer, complete = true)
                        return@launch
                    }
                }
            }
            try {
                source.explain(config, key.context, key.language).collect { event ->
                    if (generation != token || closed) return@collect
                    when (event) {
                        is ExplanationEvent.Content -> mutableState.value = State(answer = event.answer, loading = true)
                        is ExplanationEvent.Complete -> {
                            // Copy the list at the seam so a client cannot later mutate a cached answer.
                            val answer = event.answer.copy(examples = event.answer.examples.toList())
                            val completedAt = now()
                            if (reusable) {
                                val stored = if (cache == null) {
                                    MemoryEntry(answer, completedAt)
                                } else {
                                    cacheOrNull { cache.put(key, answer, epoch) }
                                        ?.let { MemoryEntry(it.answer, it.completedAt) }
                                }
                                when {
                                    stored != null -> {
                                        completed[key] = stored
                                        trimCompleted()
                                    }
                                    // A disk failure is not a clear: this session still owns the validated
                                    // answer for its original completion time.
                                    cache == null || cache.epoch() == epoch -> {
                                        completed[key] = MemoryEntry(answer, completedAt)
                                        trimCompleted()
                                    }
                                    // A clear won the race with this request; do not resurrect its answer.
                                    else -> completed.remove(key)
                                }
                            }
                            mutableState.value = State(answer = answer, complete = true)
                        }
                        is ExplanationEvent.Failure -> {
                            completed.remove(key)
                            mutableState.value = mutableState.value.copy(loading = false, complete = false, failure = event.category)
                        }
                    }
                }
                if (generation == token && mutableState.value.loading) {
                    mutableState.value = mutableState.value.copy(loading = false, failure = ExplanationFailureCategory.IncompleteResponse)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == token && !closed) {
                    completed.remove(key)
                    mutableState.value = mutableState.value.copy(loading = false, complete = false,
                        failure = ExplanationFailureCategory.Unknown)
                }
            }
        }
    }

    private fun trimCompleted() {
        while (completed.size > capacity) completed.remove(completed.keys.first())
    }

    /** Cache storage never breaks a lookup; cancellation still propagates. */
    private suspend fun <T> cacheOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
