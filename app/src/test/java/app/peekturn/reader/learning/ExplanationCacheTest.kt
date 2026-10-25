package app.peekturn.reader.learning

import app.peekturn.reader.model.Explanation
import app.peekturn.reader.model.ExplanationExample
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.model.ModelConfig
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The persistent explanation cache: absolute 15-day expiry, bounded LRU, validation and privacy. */
class ExplanationCacheTest {
    private lateinit var directory: File
    private var now = 1_700_000_000_000L

    private val context = LookupContext(
        selection = "bank",
        before = "the river ",
        after = " was muddy",
        sourceTitle = "Book",
        sectionTitle = "Chapter",
        sourceId = "epub:7",
        sectionKey = "chapter-1.xhtml"
    )
    private val config = ModelConfig(
        name = "Local provider",
        endpoint = "https://example.test/v1",
        model = "reader-model",
        apiKey = "sk-secret",
        temperature = 0.3
    )
    private val answer = Explanation(
        term = "bank",
        partOfSpeech = "noun",
        ukIpa = "bæŋk",
        usIpa = "bæŋk",
        meaning = "The land alongside a river.",
        context = "The river's edge in this sentence.",
        examples = listOf(ExplanationExample("The bank was muddy.", ""))
    )

    @Before fun setUp() {
        directory = Files.createTempDirectory("peekturn-explanation-cache").toFile()
        now = 1_700_000_000_000L
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun cache(maxBytes: Long = EXPLANATION_CACHE_BYTES) = ExplanationCache(
        directory,
        maxBytes = maxBytes,
        io = Dispatchers.Unconfined,
        now = { now }
    )

    private fun request(
        context: LookupContext = this.context,
        profileId: String = "profile-a",
        config: ModelConfig = this.config,
        language: ExplanationLanguage = ExplanationLanguage.English
    ) = ExplanationRequest.of(context, profileId, config, language)

    @Test fun completeAnswerSurvivesANewCacheAndKeepsItsCompletionTime() = runTest {
        val first = cache()
        val stored = requireNotNull(first.put(request(), answer, first.epoch()))
        now += 5_000
        val reopened = cache()
        val hit = requireNotNull(reopened.get(request(), reopened.epoch()))
        assertEquals(answer, hit.answer)
        assertEquals(stored.completedAt, hit.completedAt)
    }


    @Test fun explanationsWithoutContextAreNeverPersistedOrReused() = runTest {
        val cache = cache()
        val unknown = LookupContext(
            selection = "bank",
            before = "",
            after = "",
            sourceTitle = "Book",
            sectionTitle = "Chapter",
            sourceId = "epub:7",
            sectionKey = "chapter-1.xhtml"
        )
        val request = request(context = unknown)
        assertFalse(request.hasContextEvidence)
        assertNull(cache.put(request, answer, cache.epoch()))
        assertNull(cache.get(request, cache.epoch()))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun expiryIsAbsoluteAtFifteenDaysAndRepeatedReadsDoNotExtendIt() = runTest {
        val cache = cache()
        val stored = requireNotNull(cache.put(request(), answer, cache.epoch()))
        val completedAt = stored.completedAt
        now = completedAt + EXPLANATION_CACHE_TTL_MILLIS - 1
        assertNotNull(cache.get(request(), cache.epoch()))
        now = completedAt + EXPLANATION_CACHE_TTL_MILLIS
        assertNull("the entry expires at the exact boundary", cache.get(request(), cache.epoch()))
        assertNull("a repeated read cannot extend it", cache.get(request(), cache.epoch()))
        assertTrue(cache.sizeBytes() == 0L)
    }

    @Test fun aFutureCompletionTimestampIsRejectedAfterAClockRollback() = runTest {
        val cache = cache()
        val stored = requireNotNull(cache.put(request(), answer, cache.epoch()))
        now = stored.completedAt - 1
        assertNull(cache.get(request(), cache.epoch()))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun sameTermDifferentContextSourceLanguageOrModelNeverSharesAKey() = runTest {
        val variants = listOf(
            request(),
            request(context = context.copy(before = "another ")),
            request(context = context.copy(after = " elsewhere")),
            request(context = context.copy(sourceId = "epub:8")),
            request(context = context.copy(sectionKey = "chapter-2.xhtml")),
            request(profileId = "profile-b"),
            request(config = config.copy(endpoint = "https://other.test/v1")),
            request(config = config.copy(model = "other-model")),
            request(config = config.copy(temperature = 1.1)),
            request(language = ExplanationLanguage.Chinese)
        )
        assertEquals(variants.size, variants.map { it.cacheKey }.toSet().size)
        assertTrue("identity must not include the API key", variants.all { "sk-secret" !in it.cacheKey })
    }

    @Test fun priorPromptGenerationCannotBeReusedAfterTheExplanationPolicyChanges() = runTest {
        val cache = cache()
        val request = request()
        assertNotNull(cache.put(request, answer, cache.epoch()))
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val legacy = JsonParser.parseString(file.readText()).asJsonObject.apply {
            addProperty("prompt", "explanation-prompt-v1")
        }
        file.writeText(legacy.toString())

        assertNull("An answer from the former prompt must not mask the new explanation policy", cache.get(request, cache.epoch()))
        assertFalse(file.exists())

        val fresh = answer.copy(meaning = "Land beside a river.")
        assertNotNull(cache.put(request, fresh, cache.epoch()))
        assertEquals(fresh, cache().get(request, cache.epoch())?.answer)
    }

    @Test fun previousChinesePromptGenerationCannotBeReused() = runTest {
        val cache = cache()
        val request = request(language = ExplanationLanguage.Chinese)
        val chinese = answer.copy(
            meaning = "河岸",
            context = "这里指河边的陆地。",
            examples = listOf(ExplanationExample("The bank was muddy.", "河岸很泥泞。"))
        )
        assertNotNull(cache.put(request, chinese, cache.epoch()))
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val legacy = JsonParser.parseString(file.readText()).asJsonObject.apply {
            addProperty("prompt", "explanation-prompt-v1")
        }
        file.writeText(legacy.toString())

        assertNull("A former Chinese prompt must not answer the current instructions", cache.get(request, cache.epoch()))
        assertFalse(file.exists())
    }

    @Test fun corruptOversizedAndWrongLanguageEntriesMissAndAreDeleted() = runTest {
        val cache = cache()
        assertNotNull(cache.put(request(), answer, cache.epoch()))
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        file.writeText("{\"format\":")
        assertNull(cache.get(request(), cache.epoch()))
        assertFalse(file.exists())

        assertNotNull(cache.put(request(), answer, cache.epoch()))
        val schemaFile = directory.listFiles()!!.single { it.name.endsWith(".json") }
        schemaFile.writeText(schemaFile.readText().replace("\"term\":", "\"wrongField\":"))
        assertNull("an unexpected answer field is corrupt", cache.get(request(), cache.epoch()))
        assertFalse(schemaFile.exists())

        assertNotNull(cache.put(request(), answer, cache.epoch()))
        directory.listFiles()!!.single { it.name.endsWith(".json") }
            .writeBytes(ByteArray((EXPLANATION_CACHE_MAX_ENTRY_BYTES + 1).toInt()))
        assertNull(cache.get(request(), cache.epoch()))
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".json") })

        val chinese = request(language = ExplanationLanguage.Chinese)
        val chineseAnswer = answer.copy(
            meaning = "河岸。",
            context = "指这条河的岸边。",
            examples = listOf(ExplanationExample("The bank was muddy.", "河岸很泥泞。"))
        )
        assertNotNull(cache.put(chinese, chineseAnswer, cache.epoch()))
        val chineseFile = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val mismatched = File(directory, "${request().cacheKey}.json")
        chineseFile.copyTo(mismatched, overwrite = true)
        assertNull("a persisted language must match the request", cache.get(request(), cache.epoch()))
        assertFalse(mismatched.exists())
    }

    @Test fun leastRecentlyUsedEntriesMakeRoomInOneBatchAtTheDiskBound() = runTest {
        // Measure one encoded entry in an unbounded probe so the bound is exact, not guessed.
        val probeDirectory = Files.createTempDirectory("peekturn-explanation-probe").toFile()
        try {
            val probe = ExplanationCache(probeDirectory, io = Dispatchers.Unconfined, now = { now })
            assertNotNull(probe.put(request(), answer, probe.epoch()))
            val entryBytes = probeDirectory.listFiles()!!.single { it.name.endsWith(".json") }.length()
            val maxBytes = entryBytes * 4
            val capped = ExplanationCache(directory, maxBytes = maxBytes, io = Dispatchers.Unconfined, now = { now })
            val first = request(context = context.copy(selection = "alpha"))
            val second = request(context = context.copy(selection = "beta"))
            val third = request(context = context.copy(selection = "gamma"))
            val fourth = request(context = context.copy(selection = "delta"))
            val fifth = request(context = context.copy(selection = "epsilon"))
            val entries = listOf(first, second, third, fourth, fifth)
            now += 1_000; assertNotNull(capped.put(first, answer, capped.epoch()))
            now += 1_000; assertNotNull(capped.put(second, answer, capped.epoch()))
            now += 1_000; assertNotNull(capped.put(third, answer, capped.epoch()))
            now += 1_000; assertNotNull(capped.get(first, capped.epoch()))
            now += 1_000; assertNotNull(capped.get(third, capped.epoch()))
            now += 1_000; assertNotNull(capped.put(fourth, answer, capped.epoch()))
            now += 1_000; assertNotNull(capped.put(fifth, answer, capped.epoch()))

            // The over-quota put trims to 95% in one batch, so the two oldest entries leave.
            assertNull(capped.get(second, capped.epoch()))
            assertNull(capped.get(first, capped.epoch()))
            assertNotNull(capped.get(third, capped.epoch()))
            assertNotNull(capped.get(fourth, capped.epoch()))
            assertNotNull(capped.get(fifth, capped.epoch()))
            assertTrue(capped.sizeBytes() <= maxBytes)
            assertEquals(entries.size - 2L, directory.listFiles()!!.count { it.name.endsWith(".json") }.toLong())
        } finally {
            probeDirectory.deleteRecursively()
        }
    }

    @Test fun clearRemovesEntriesAndAdvancesTheGenerationGate() = runTest {
        val cache = cache()
        val before = cache.epoch()
        assertNotNull(cache.put(request(), answer, cache.epoch()))
        cache.clear()
        assertTrue(cache.epoch() > before)
        assertNull(cache.get(request(), before))
        assertNull(cache.get(request(), cache.epoch()))
        assertEquals(0L, cache.sizeBytes())
        assertNull("an old generation cannot write after clear", cache.put(request(), answer, before))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun selectedContextSourceTitlesAndSecretsStayOutOfNamesAndStoredBytes() = runTest {
        val secretContext = "PRIVATE_CONTEXT_SENTINEL"
        val privateContext = context.copy(
            before = "$secretContext before the river ",
            sourceTitle = "Private Source Title",
            sectionTitle = "Private Section Title"
        )
        val cache = cache()
        assertNotNull(cache.put(request(context = privateContext), answer, cache.epoch()))
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertFalse(file.name.contains("bank"))
        assertFalse(file.name.contains(secretContext))
        val stored = file.readText()
        assertFalse(stored.contains(secretContext))
        assertFalse(stored.contains("Private Source Title"))
        assertFalse(stored.contains("Private Section Title"))
        assertFalse(stored.contains("sk-secret"))
    }

    @Test fun invalidOrIncompleteAnswersAreNeverWritten() = runTest {
        val cache = cache()
        assertNull(cache.put(request(), answer.copy(examples = emptyList()), cache.epoch()))
        assertNull(cache.put(request(), answer.copy(meaning = " "), cache.epoch()))
        assertEquals(0L, cache.sizeBytes())
    }
}
