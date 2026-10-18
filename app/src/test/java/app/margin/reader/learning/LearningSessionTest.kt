package app.margin.reader.learning

import app.margin.reader.model.Explanation
import app.margin.reader.model.ExplanationExample
import app.margin.reader.model.LookupContext
import app.margin.reader.model.ModelConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LearningSessionTest {
    private val context = LookupContext("bank", "the river ", " was muddy", "Book", "Chapter")
    private val config = ModelConfig(apiKey = "secret")
    private val answer = Explanation(meaning = "The land beside a river.", context = "The river's edge.")
    private val completeAnswer = Explanation(
        term = "bank",
        partOfSpeech = "noun",
        ukIpa = "bæŋk",
        usIpa = "bæŋk",
        meaning = "The land beside a river.",
        context = "The river's edge in this sentence.",
        examples = listOf(ExplanationExample("The bank was muddy.", ""))
    )
    private lateinit var cacheDirectory: File
    private var now = 1_700_000_000_000L

    @Before fun setUpPersistentCache() {
        cacheDirectory = Files.createTempDirectory("margin-session-cache").toFile()
        now = 1_700_000_000_000L
    }

    @After fun tearDownPersistentCache() {
        cacheDirectory.deleteRecursively()
    }

    private fun TestScope.persistentCache() = ExplanationCache(
        cacheDirectory,
        io = StandardTestDispatcher(testScheduler),
        now = { now }
    )

    private class ControlledSource : ExplanationSource {
        data class Call(
            val config: ModelConfig,
            val context: LookupContext,
            val language: ExplanationLanguage,
            val events: Channel<ExplanationEvent> = Channel(Channel.UNLIMITED)
        )
        val calls = mutableListOf<Call>()
        override fun explain(config: ModelConfig, context: LookupContext, language: ExplanationLanguage): Flow<ExplanationEvent> = flow {
            val call = Call(config, context, language).also(calls::add)
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

    @Test fun completedChineseAnswerIsNotReusedWhenTheActivitySwitchesToEnglish() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        val chinese = answer.copy(meaning = "中文回答", context = "中文语境")
        session.open(context, "first", config, ExplanationLanguage.Chinese)
        runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(chinese))
        runCurrent()
        session.open(context, "first", config, ExplanationLanguage.English)
        runCurrent()
        assertEquals("A different learning language must not reuse the Chinese answer", 2, source.calls.size)
        assertEquals(ExplanationLanguage.English, source.calls.last().language)
        source.calls.last().events.send(ExplanationEvent.Complete(answer))
        runCurrent()
        session.open(context, "first", config, ExplanationLanguage.Chinese)
        runCurrent()
        assertEquals(2, source.calls.size)
        assertTrue(session.state.value.complete)
        assertEquals(chinese, session.state.value.answer)
        session.close()
    }

    @Test fun failuresDoNotRetryAutomaticallyOrEnterCompletedCacheAndRetryUsesLatestConfig() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        session.open(context, "first", config); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Failure("Quota reached", ExplanationFailureCategory.Quota)); runCurrent()
        assertEquals(ExplanationFailureCategory.Quota, session.state.value.failure)
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
        val source = ExplanationSource { _, lookup, _ -> flow {
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
        val source = ExplanationSource { _, _, _ -> flow<ExplanationEvent> {
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

    @Test fun everyProviderFailureCategorySurvivesToTheSessionState() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler))
        val categories = listOf(
            ExplanationFailureCategory.Configuration,
            ExplanationFailureCategory.Authentication,
            ExplanationFailureCategory.Quota,
            ExplanationFailureCategory.Connectivity,
            ExplanationFailureCategory.MalformedResponse,
            ExplanationFailureCategory.IncompleteResponse,
            ExplanationFailureCategory.Refusal,
            ExplanationFailureCategory.ProviderRejected,
            ExplanationFailureCategory.Unknown
        )
        categories.forEachIndexed { index, category ->
            session.open(context.copy(selection = "word$index"), "first", config); runCurrent()
            source.calls.last().events.send(ExplanationEvent.Failure("provider-private ${category.name}", category)); runCurrent()
            assertEquals(category, session.state.value.failure)
            assertEquals("Provider text must never reach the UI state", Explanation(), session.state.value.answer)
            session.dismiss(context.copy(selection = "word$index"))
        }
        session.close()
    }

    @Test fun earlyEndAndThrownSourceUseIncompleteAndUnknown() = runTest {
        val earlyEnd = ExplanationSource { _, _, _ -> flow { emit(ExplanationEvent.Content(answer)) } }
        val endedSession = LearningSession(earlyEnd, StandardTestDispatcher(testScheduler))
        endedSession.open(context, "first", config); runCurrent()
        assertEquals(ExplanationFailureCategory.IncompleteResponse, endedSession.state.value.failure)
        endedSession.close()

        val thrown = ExplanationSource { _, _, _ -> flow<ExplanationEvent> { throw IllegalStateException("private") } }
        val thrownSession = LearningSession(thrown, StandardTestDispatcher(testScheduler))
        thrownSession.open(context, "first", config); runCurrent()
        assertEquals(ExplanationFailureCategory.Unknown, thrownSession.state.value.failure)
        thrownSession.close()
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

    @Test fun aPersistedCompleteAnswerIsReusedByANewSessionWithoutAProviderCall() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val first = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        first.open(context, "first", config); runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer))
        advanceUntilIdle()
        first.close()

        val second = LearningSession(source, StandardTestDispatcher(testScheduler), cache = persistentCache(), now = { now })
        second.open(context, "first", config); advanceUntilIdle()
        assertEquals(1, source.calls.size)
        assertTrue(second.state.value.complete)
        assertEquals(completeAnswer, second.state.value.answer)
        second.close()
    }

    @Test fun sameTermWithDifferentContextBookChapterLanguageOrProviderUsesItsOwnEntry() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val winner = LearningSession(source, dispatcher, cache = cache, now = { now })
        winner.open(context, "first", config); runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer))
        advanceUntilIdle()

        val variants = listOf(
            context.copy(before = "another "),
            context.copy(after = " elsewhere"),
            context.copy(bookId = 99L),
            context.copy(chapterKey = "chapter-2.xhtml")
        )
        variants.forEach { lookup ->
            winner.open(lookup, "first", config); runCurrent()
            assertEquals(1 + variants.indexOf(lookup) + 1, source.calls.size)
            source.calls.last().events.send(ExplanationEvent.Complete(completeAnswer)); advanceUntilIdle()
        }
        winner.open(context, "first", config, ExplanationLanguage.Chinese); runCurrent()
        assertEquals(1 + variants.size + 1, source.calls.size)
        source.calls.last().events.send(ExplanationEvent.Complete(
            completeAnswer.copy(meaning = "河岸。", context = "指这条河的岸边。",
                examples = listOf(ExplanationExample("The bank was muddy.", "河岸很泥泞。")))
        )); advanceUntilIdle()
        winner.open(context, "first", config.copy(model = "other-model")); runCurrent()
        assertEquals(1 + variants.size + 2, source.calls.size)
        winner.close()
    }

    @Test fun retryInvalidatesThePersistedAnswerAndReplacesItOnlyOnSuccess() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = LearningSession(source, dispatcher, cache = cache, now = { now })
        first.open(context, "first", config); runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer)); advanceUntilIdle()
        first.close()

        val second = LearningSession(source, dispatcher, cache = cache, now = { now })
        second.open(context, "first", config); advanceUntilIdle()
        assertEquals("a persisted hit makes no provider call", 1, source.calls.size)
        second.retry(context, "first", config); advanceUntilIdle()
        assertEquals(2, source.calls.size)
        assertNull("retry removes the old entry before asking again",
            cache.get(ExplanationRequest.of(context, "first", config, ExplanationLanguage.English), cache.epoch()))
        val replacement = completeAnswer.copy(meaning = "A fresh answer.")
        source.calls.last().events.send(ExplanationEvent.Complete(replacement)); advanceUntilIdle()
        second.close()

        val third = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        third.open(context, "first", config); advanceUntilIdle()
        assertEquals(2, source.calls.size)
        assertEquals(replacement, third.state.value.answer)
        third.close()
    }

    @Test fun partialAndFailedAnswersAreNeverPersisted() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        session.open(context, "first", config); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Content(completeAnswer))
        source.calls.last().events.send(ExplanationEvent.Failure("Quota", ExplanationFailureCategory.Quota))
        advanceUntilIdle()
        assertEquals(0L, cache.sizeBytes())
        session.dismiss(context)
        session.open(context, "first", config); runCurrent()
        assertEquals("a failure is not a cache entry", 2, source.calls.size)
        session.close()
    }


    @Test fun explanationsWithoutContextAreNotReusedAcrossOccurrences() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        val unknown = LookupContext("bank", bookId = 7L, chapterKey = "chapter-1.xhtml")

        session.open(unknown, "first", config); runCurrent()
        source.calls.last().events.send(ExplanationEvent.Complete(completeAnswer)); runCurrent()
        assertEquals(0L, cache.sizeBytes())

        session.dismiss(unknown)
        session.open(unknown, "first", config); runCurrent()
        assertEquals("no word-only reuse across occurrences", 2, source.calls.size)
        assertEquals(0L, cache.sizeBytes())
        session.close()
    }

    @Test fun clearDuringAnInflightRequestCannotRepopulateTheCache() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        session.open(context, "first", config); runCurrent()
        assertEquals(1, source.calls.size)
        cache.clear()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer)); advanceUntilIdle()
        assertEquals("the older request generation cannot write after clear", 0L, cache.sizeBytes())
        session.dismiss(context)
        session.open(context, "first", config); runCurrent()
        assertEquals("the completed answer is not resurrected from memory", 2, source.calls.size)
        session.close()
    }

    @Test fun clearingTheCacheInvalidatesCompletedReaderMemory() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        session.open(context, "first", config); runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer)); advanceUntilIdle()
        assertTrue(cache.sizeBytes() > 0L)

        cache.clear()
        session.dismiss(context)
        session.open(context, "first", config); runCurrent()
        assertEquals("clear also invalidates the session's completed answer", 2, source.calls.size)
        session.close()
    }

    @Test fun clearWhileTheSameKeyIsActiveIsNotMaskedByTheShortCircuit() = runTest {
        val source = ControlledSource()
        val cache = persistentCache()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), cache = cache, now = { now })
        session.open(context, "first", config); runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer)); advanceUntilIdle()
        cache.clear()
        // No dismiss: the popup still shows the same lookup, and reopening must not return its answer.
        session.open(context, "first", config); runCurrent()
        assertEquals(2, source.calls.size)
        assertEquals(Explanation(), session.state.value.answer)
        assertTrue(session.state.value.loading)
        session.close()
    }

    @Test fun aMemoryOnlyEntryExpiresAtTheAbsoluteBoundary() = runTest {
        val source = ControlledSource()
        val session = LearningSession(source, StandardTestDispatcher(testScheduler), now = { now })
        session.open(context, "first", config); runCurrent()
        source.calls.single().events.send(ExplanationEvent.Complete(completeAnswer)); runCurrent()
        val completedAt = now

        now = completedAt + EXPLANATION_CACHE_TTL_MILLIS - 1
        session.dismiss(context); session.open(context, "first", config); runCurrent()
        assertEquals("still valid one millisecond before the boundary", 1, source.calls.size)

        now = completedAt + EXPLANATION_CACHE_TTL_MILLIS
        session.open(context, "first", config); runCurrent()
        assertEquals("expired exactly at fifteen days, even while active", 2, source.calls.size)
        session.close()
    }
}
