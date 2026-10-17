package app.margin.reader.learning

import app.margin.reader.model.Explanation
import app.margin.reader.model.LookupContext
import app.margin.reader.model.ModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LearningSessionTest {
    private val context = LookupContext("bank", "the river ", " was muddy", "Book", "Chapter")
    private val config = ModelConfig(apiKey = "secret")
    private val answer = Explanation(meaning = "The land beside a river.", context = "The river's edge.")

    private class ControlledSource : ExplanationSource {
        data class Call(val config: ModelConfig, val context: LookupContext, val events: Channel<ExplanationEvent> = Channel(Channel.UNLIMITED))
        val calls = mutableListOf<Call>()
        override fun explain(config: ModelConfig, context: LookupContext): Flow<ExplanationEvent> = flow {
            val call = Call(config, context).also(calls::add)
            for (event in call.events) emit(event)
        }
    }

    @Test fun repeatedOpenAndDictionaryRoundTripKeepOneInFlightRequestAndCompletedAnswer() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        session.open(context, "first", config)
        runCurrent()
        source.calls.single().events.send(ExplanationEvent.Content(answer))
        runCurrent()
        // Dictionary is a presentation choice. It never closes or replaces the session lookup.
        session.open(context, "first", config)
        assertEquals(answer, session.state.value.answer)
        assertEquals(1, source.calls.size)
        source.calls.single().events.send(ExplanationEvent.Complete(answer))
        runCurrent()
        session.dismiss(context)
        session.open(context, "first", config)
        runCurrent()
        assertEquals(1, source.calls.size)
        assertTrue(session.state.value.complete)
        assertEquals(answer, session.state.value.answer)
        session.close()
    }

    @Test fun exactContextAndProfileConfigurationEachOwnTheirResult() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        val variants = listOf(context, context.copy(selection = "Bank"), context.copy(before = "another "),
            context.copy(after = " elsewhere"), context.copy(bookTitle = "Other"), context.copy(chapterTitle = "Next"))
        variants.forEach { lookup ->
            session.open(lookup, "first", config); runCurrent()
            source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        }
        session.open(context, "second", config); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        session.open(context, "second", config.copy(model = "new-model")); runCurrent()
        assertEquals(variants.size + 2, source.calls.size)
        session.open(context, "second", config.copy(temperature = 1.2)); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        session.open(context, "second", config.copy(temperature = 0.7)); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        // Temperature belongs to the profile identity: each value owns its own cached answer.
        session.open(context, "second", config.copy(temperature = 1.2)); runCurrent()
        assertEquals(variants.size + 4, source.calls.size)
        session.close()
    }

    @Test fun failuresDoNotRetryAutomaticallyOrEnterCompletedCacheAndRetryUsesLatestConfig() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        session.open(context, "first", config); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Failure("Quota reached")); runCurrent()
        assertEquals("Quota reached", session.state.value.error)
        assertEquals(1, source.calls.size)
        session.dismiss(context)
        session.open(context, "first", config); runCurrent()
        assertEquals(2, source.calls.size)
        source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        val latest = config.copy(apiKey = "replacement")
        session.retry(context, "second", latest); runCurrent()
        assertEquals(3, source.calls.size)
        assertEquals(latest, source.calls.last().config)
        source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        session.retry(context, "second", latest); runCurrent()
        assertEquals("Explicit retry bypasses even the matching completed cache", 4, source.calls.size)
        session.close()
    }

    @Test fun obsoleteNonCooperativeCallbacksCannotReplaceCurrentLookupAndExitCancels() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = 0
        val source = ExplanationSource { _, lookup -> flow {
            calls++
            try {
                if (lookup == context) withContext(NonCancellable) { release.await() }
                emit(ExplanationEvent.Complete(answer.copy(meaning = lookup.selection)))
            } finally { cancelled++ }
        } }
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        session.open(context, "first", config); runCurrent()
        session.open(context.copy(selection = "shore"), "first", config); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals("shore", session.state.value.answer.meaning)
        session.close()
        session.open(context, "first", config); runCurrent()
        assertEquals(2, calls)
        assertEquals(2, cancelled)
        assertFalse(session.state.value.loading)
    }

    @Test fun readerExitCancelsOutstandingProviderCollection() = runTest {
        var providerCancelled = false
        val source = ExplanationSource { _, _ -> flow<ExplanationEvent> {
            try { kotlinx.coroutines.awaitCancellation() }
            finally { providerCancelled = true }
        } }
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        session.open(context, "first", config); runCurrent()
        assertTrue(session.state.value.loading)
        session.close(); runCurrent()
        assertTrue(providerCancelled)
        assertFalse(session.state.value.loading)
    }

    @Test fun completedCacheIsBoundedAndDismissCancelsPendingLookup() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), capacity = 2)
        listOf("one", "two", "three").forEach { word ->
            session.open(context.copy(selection = word), "first", config); runCurrent()
            source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        }
        session.open(context.copy(selection = "one"), "first", config); runCurrent()
        assertEquals(4, source.calls.size)
        session.dismiss(context.copy(selection = "one"))
        source.calls.last().events.send(ExplanationEvent.Complete(answer)); runCurrent()
        assertFalse(session.state.value.complete)
        assertFalse(session.state.value.loading)
        session.close()
    }
}
