package app.margin.reader.learning

import app.margin.reader.model.Explanation
import app.margin.reader.model.LookupContext
import app.margin.reader.model.ModelConfig
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
class LearningSession(
    private val source: ExplanationSource = ExplanationClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val capacity: Int = 32
) {
    data class State(
        val answer: Explanation = Explanation(),
        val loading: Boolean = false,
        val complete: Boolean = false,
        val error: String? = null
    )

    private data class Key(val context: LookupContext, val profileId: String, val config: ModelConfig)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val completed = LinkedHashMap<Key, Explanation>(capacity, .75f, true)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var active: Key? = null
    private var request: Job? = null
    private var generation = 0L
    private var closed = false

    init { require(capacity > 0) }

    fun open(context: LookupContext, profileId: String, config: ModelConfig) {
        val key = Key(context, profileId, config)
        if (closed || active == key) return
        start(key, bypassCache = false)
    }

    /** An explicit retry is one fresh request using the caller's latest configuration. */
    fun retry(context: LookupContext, profileId: String, config: ModelConfig) {
        if (!closed) start(Key(context, profileId, config), bypassCache = true)
    }

    /** Dismissing cancels pending work, but completed answers live until this reader exits. */
    fun dismiss(context: LookupContext) {
        if (active?.context != context) return
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

    private fun start(key: Key, bypassCache: Boolean) {
        val token = ++generation
        request?.cancel()
        request = null
        active = key
        if (bypassCache) completed.remove(key)
        val cached = completed[key]
        if (cached != null) {
            mutableState.value = State(answer = cached, complete = true)
            return
        }
        mutableState.value = State(loading = true)
        request = scope.launch {
            try {
                source.explain(key.config, key.context).collect { event ->
                    if (generation != token || closed) return@collect
                    when (event) {
                        is ExplanationEvent.Content -> mutableState.value = State(answer = event.answer, loading = true)
                        is ExplanationEvent.Complete -> {
                            // Copy the list at the seam so a client cannot later mutate a cached answer.
                            val answer = event.answer.copy(examples = event.answer.examples.toList())
                            completed[key] = answer
                            while (completed.size > capacity) completed.remove(completed.keys.first())
                            mutableState.value = State(answer = answer, complete = true)
                        }
                        is ExplanationEvent.Failure -> {
                            completed.remove(key)
                            mutableState.value = mutableState.value.copy(loading = false, complete = false, error = event.message)
                        }
                    }
                }
                if (generation == token && mutableState.value.loading) {
                    mutableState.value = mutableState.value.copy(loading = false, error = "The explanation ended before completion. Try again.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == token && !closed) {
                    completed.remove(key)
                    mutableState.value = mutableState.value.copy(loading = false, complete = false,
                        error = "The explanation could not be loaded. Try again.")
                }
            }
        }
    }
}
