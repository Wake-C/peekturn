package app.peekturn.reader.video

import app.peekturn.reader.learning.ModelChatTransport
import app.peekturn.reader.model.ModelConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val mergeWhitespace = Regex("[\\s\\p{Z}\\u0085]+")

private fun mergeNormalize(value: String) = value.trim().replace(mergeWhitespace, " ")

class CaptionMergeClientTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    private fun config() = ModelConfig(
        endpoint = server.url("/v1").toString(), model = "merge-model", apiKey = "test-key", temperature = 0.2
    )

    private fun transport(http: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()) =
        ModelChatTransport(http)

    private fun sse(content: String): MockResponse {
        fun envelope(delta: JsonObject, finish: String? = null) = JsonObject().apply {
            add("choices", JsonArray().apply {
                add(JsonObject().apply { add("delta", delta); if (finish != null) addProperty("finish_reason", finish) })
            })
        }
        val body = "data: ${envelope(JsonObject().apply { addProperty("content", content) })}\n\n" +
            "data: ${envelope(JsonObject(), "stop")}\n\n" +
            "data: [DONE]\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }

    private fun captionDocument(vararg cues: CaptionCue) =
        CaptionDocument("video", "en-manual", "en", false, "source-hash", cues.toList())

    private fun cues(count: Int) = (0 until count).map {
        CaptionCue(it, it * 2_000L, it * 2_000L + 1_500L, "line $it")
    }

    @Test fun windowsAreRequestedOneAtATimeAndCoverTheWholeTrack() = runBlocking {
        val all = cues(90)
        val document = CaptionDocument("video", "en-manual", "en", false, "source-hash", all)
        server.enqueue(sse("""{"endCueIds":${(1 until 80 step 2).toList()}}"""))
        server.enqueue(sse("""{"endCueIds":${(81..89 step 2).toList()}}"""))

        val progress = mutableListOf<Pair<Int, Int>>()
        val groups = CaptionMergeClient(transport()).merge(config(), document) { done, total ->
            progress += done to total
        }

        assertEquals(90 to 90, progress.last())
        assertTrue(progress.all { (done, total) -> done in 0..total && total == 90 })
        assertTrue(progress.zipWithNext().all { (before, after) -> before.first <= after.first })
        assertEquals(2, server.requestCount)
        val sizes = mutableListOf<Int>()
        repeat(2) {
            val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            val payload = JsonParser.parseString(body.getAsJsonArray("messages")[1].asJsonObject["content"].asString).asJsonObject
            val window = payload.getAsJsonArray("cues")
            sizes += window.size()
            assertEquals(window[window.size() - 1].asJsonObject["id"].asInt, payload["lastCueId"].asInt)
        }
        assertEquals(listOf(80, 10), sizes)

        val merged = applyCaptionGroups(document, groups)
        assertEquals(45, merged.size)
        assertEquals(
            mergeNormalize(all.joinToString(" ") { it.text }),
            mergeNormalize(merged.joinToString(" ") { it.text })
        )
        // Each merged cue uses only source times.
        val byId = all.associateBy { it.id }
        merged.forEach { cue ->
            assertEquals(byId.getValue(cue.id).startMs, cue.startMs)
        }
    }

    @Test fun oversizedSingleCueIsKeptWithoutUploadingIt() = runBlocking {
        val original = CaptionCue(0, 0, 20_000, "x".repeat(9_000))
        val document = captionDocument(original)
        server.enqueue(MockResponse().setResponseCode(500))

        val groups = CaptionMergeClient(transport()).merge(config(), document)

        assertEquals(listOf(CaptionGroup(0, 0)), groups)
        assertEquals(listOf(original), applyCaptionGroups(document, groups))
        assertEquals(0, server.requestCount)
    }

    @Test fun providerAndInvalidBoundariesAreFailuresWithoutGroups() = runBlocking {
        val document = captionDocument(CaptionCue(0, 0, 1_000, "a"), CaptionCue(1, 1_100, 2_000, "b"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("private provider detail"))
        val provider = runCatching { CaptionMergeClient(transport()).merge(config(), document) }.exceptionOrNull()
        assertTrue(provider is VideoFailure)
        assertEquals(VideoFailureKind.Provider, (provider as VideoFailure).kind)
        assertFalse(provider.toString().contains("private provider detail"))

        val invalid = listOf(
            """{"endCueIds":[1.5]}""",
            """{"endCueIds":[9]}""",
            """{"endCueIds":[1,0]}""",
            """{"endCueIds":[0]}""",
            """not json"""
        )
        invalid.forEach { content ->
            server.enqueue(sse(content))
            val failure = runCatching { CaptionMergeClient(transport()).merge(config(), document) }.exceptionOrNull()
            assertTrue("$content must fail locally", failure is VideoFailure)
            assertEquals(VideoFailureKind.Provider, (failure as VideoFailure).kind)
        }
    }

    @Test fun overlongProviderGroupsAreSplitLocallyWithoutAnotherRequest() = runBlocking {
        val first = CaptionCue(0, 0, 1_000, "hello")
        val second = CaptionCue(1, 2_500, 3_500, "world")
        val document = captionDocument(first, second)
        server.enqueue(sse("""{"endCueIds":[1]}"""))

        val groups = CaptionMergeClient(transport()).merge(config(), document)

        assertEquals(listOf(CaptionGroup(0, 0), CaptionGroup(1, 1)), groups)
        val merged = applyCaptionGroups(document, groups)
        assertEquals(listOf("hello", "world"), merged.map { it.text })
        assertEquals(listOf(0L, 2_500L), merged.map { it.startMs })
        assertEquals(listOf(1_000L, 3_500L), merged.map { it.endMs })
        assertEquals("splitting is local, so no retry or fallback request is made", 1, server.requestCount)
    }

    @Test fun cancellationIsNotReportedAsAMergeFailure() = runBlocking {
        val document = captionDocument(CaptionCue(0, 0, 1_000, "a"), CaptionCue(1, 1_100, 2_000, "b"))
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = CaptionMergeClient(transport(http))
        var thrown: Throwable? = null
        val progress = java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)
        val job = launch(Dispatchers.Default) {
            try {
                client.merge(config(), document) { done, total -> progress.set(done to total) }
            } catch (failure: Throwable) {
                thrown = failure
                throw failure
            }
        }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        assertEquals("Waiting on the provider must already be shown as generation", 0 to 2, progress.get())
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertFalse("cancellation must not become a provider failure", thrown is VideoFailure)
        assertTrue(thrown == null || thrown is CancellationException)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }

    @Test fun identityTracksTheRequestSnapshotAndNeverTheKey() {
        val base = ModelConfig(endpoint = "https://api.test/v1", model = "model-a", apiKey = "sk-secret", temperature = 0.2)
        val identity = captionMergeIdentity("default", base)
        assertEquals(identity, captionMergeIdentity("default", base.copy(apiKey = "sk-other")))
        assertFalse(identity.contains("sk-secret"))
        val variants = listOf(
            identity,
            captionMergeIdentity("other", base),
            captionMergeIdentity("default", base.copy(endpoint = "https://api.other/v1")),
            captionMergeIdentity("default", base.copy(model = "model-b")),
            captionMergeIdentity("default", base.copy(temperature = 0.9))
        )
        assertEquals(variants.size, variants.toSet().size)
    }
}
