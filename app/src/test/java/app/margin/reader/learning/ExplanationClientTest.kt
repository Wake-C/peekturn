package app.margin.reader.learning

import app.margin.reader.model.LookupContext
import app.margin.reader.model.ModelConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExplanationClientTest {
    private lateinit var server: MockWebServer
    private val payload = """{"term":"hello","partOfSpeech":"interjection","ukIpa":"","usIpa":"","meaning":"A greeting.","context":"Used to greet someone.","examples":["Hello, how are you?"]}"""
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun config() = ModelConfig(endpoint = server.url("/v1").toString(), model = "configured-model", apiKey = "test-key")
    private fun chunk(content: String? = null, finish: String? = null, reasoning: String? = null): String {
        val delta = JsonObject().apply { content?.let { addProperty("content", it) }; reasoning?.let { addProperty("reasoning_content", it) } }
        val choice = JsonObject().apply { add("delta", delta); if (finish != null) addProperty("finish_reason", finish) }
        return "data: {\"choices\":[$choice]}\n\n"
    }
    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test fun streamsValidatedFieldsAndUsesConfiguredTransport() = runBlocking {
        val split = payload.indexOf("\"context\"")
        server.enqueue(stream(chunk(reasoning = "private thoughts") + chunk(payload.take(split)) + chunk(payload.drop(split)) + chunk(finish = "stop") + "data: [DONE]\n\n"))
        val events = ExplanationClient().explain(config(), LookupContext("hello", before = (1..40).joinToString(" ") { "before$it" })).toList()
        assertTrue(events.first() is ExplanationEvent.Content)
        assertEquals("hello", (events.last() as ExplanationEvent.Complete).answer.term)
        assertFalse(events.toString().contains("private thoughts"))
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("configured-model", json["model"].asString)
        assertEquals("json_object", json.getAsJsonObject("response_format")["type"].asString)
        val context = JsonParser.parseString(json.getAsJsonArray("messages")[1].asJsonObject["content"].asString).asJsonObject
        assertEquals("before11", context["before"].asString.substringBefore(' '))
    }

    @Test fun eofAndTokenLimitNeverBecomeSuccessfulCompletion() = runBlocking {
        listOf(chunk(payload), chunk(payload) + chunk(finish = "length"), chunk(payload.dropLast(2)) + chunk(finish = "stop")).forEach { body ->
            server.enqueue(stream(body))
            val events = ExplanationClient().explain(config(), LookupContext("hello")).toList()
            assertTrue(events.last() is ExplanationEvent.Failure)
            assertFalse(events.any { it is ExplanationEvent.Complete })
        }
    }

    @Test fun malformedLaterFieldRetainsTheSameValidatedContentAcrossChunkBoundaries() = runBlocking {
        val validPrefix = """{"term":"hello","partOfSpeech":"interjection","ukIpa":"","usIpa":"","meaning":"A greeting.","""
        val malformedSuffix = """"context":42,"examples":["Hello, how are you?"]}"""
        val bodies = listOf(
            chunk(validPrefix + malformedSuffix),
            chunk(validPrefix) + chunk(malformedSuffix)
        )
        val results = bodies.map { body ->
            server.enqueue(stream(body))
            ExplanationClient().explain(config(), LookupContext("hello")).toList()
        }
        val retainedAnswers = results.map { events ->
            assertTrue("Malformed output must terminate with Failure", events.last() is ExplanationEvent.Failure)
            assertFalse("Malformed output must never complete", events.any { it is ExplanationEvent.Complete })
            val retained = events.filterIsInstance<ExplanationEvent.Content>().lastOrNull()?.answer
            assertNotNull("Validated fields must survive a malformed later field in the same chunk", retained)
            assertEquals("hello", retained?.term)
            assertEquals("A greeting.", retained?.meaning)
            assertEquals("", retained?.context)
            assertEquals(emptyList<String>(), retained?.examples)
            retained
        }
        assertEquals(retainedAnswers[0], retainedAnswers[1])
    }

    @Test fun refusalMalformedAndHttpErrorAreExplicitFailures() = runBlocking {
        val responses = listOf(
            stream("data: {\"choices\":[{\"delta\":{\"refusal\":\"No\"}}]}\n\n"),
            stream(chunk("not JSON") + chunk(finish = "stop")),
            MockResponse().setResponseCode(400).setBody("private server diagnostic"),
            stream(chunk("{}") + chunk(finish = "stop"))
        )
        responses.forEach { response ->
            server.enqueue(response)
            val events = ExplanationClient().explain(config(), LookupContext("hello")).toList()
            assertTrue(events.last() is ExplanationEvent.Failure)
            assertFalse(events.toString().contains("private server diagnostic"))
        }
    }

    @Test fun missingKeyDoesNotConnect() = runBlocking {
        val events = ExplanationClient().explain(config().copy(apiKey = ""), LookupContext("hello")).toList()
        assertTrue(events.single() is ExplanationEvent.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test fun connectionTestRequiresRealStructuredCompletion() = runBlocking {
        server.enqueue(stream(chunk(payload) + chunk(finish = "stop") + "data: [DONE]\n\n"))
        assertTrue(ExplanationClient().testConnection(config()).isSuccess)
        server.enqueue(stream(chunk(payload)))
        assertTrue(ExplanationClient().testConnection(config()).isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationClosesAnActiveSocketWithoutFailureEvent() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val events = mutableListOf<ExplanationEvent>()
        val job = launch(Dispatchers.Default) { ExplanationClient(http).explain(config(), LookupContext("hello")).toList(events) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(events.isEmpty())
        assertEquals(0, http.dispatcher.runningCallsCount())
    }

    @Test fun cancellationInterruptsBodyReadAfterUsefulPartialContent() = runBlocking {
        server.enqueue(stream(chunk(payload)).setHeader("Content-Length", "100000"))
        val contentArrived = CompletableDeferred<Unit>()
        val events = mutableListOf<ExplanationEvent>()
        val job = launch(Dispatchers.Default) {
            ExplanationClient().explain(config(), LookupContext("hello")).collect { event ->
                events += event
                if (event is ExplanationEvent.Content) contentArrived.complete(Unit)
            }
        }
        withTimeout(5_000) { contentArrived.await() }
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(events.any { it is ExplanationEvent.Content })
        assertFalse(events.any { it is ExplanationEvent.Complete || it is ExplanationEvent.Failure })
    }

    @Test fun chosenNumericTemperatureIsSentForExplanationAndConnectionTest() = runBlocking {
        val profile = config().copy(temperature = 1.2)
        server.enqueue(stream(chunk(payload) + chunk(finish = "stop") + "data: [DONE]\n\n"))
        assertTrue(ExplanationClient().testConnection(profile).isSuccess)
        server.enqueue(stream(chunk(payload) + chunk(finish = "stop") + "data: [DONE]\n\n"))
        ExplanationClient().explain(profile, LookupContext("hello")).toList()
        repeat(2) { index ->
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            val sent = json.get("temperature")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            assertNotNull("Request $index must carry the profile temperature", sent)
            assertTrue("Temperature must be a JSON number, not a string", sent?.isNumber == true)
            assertEquals(1.2, sent?.asDouble ?: -1.0, 0.0)
            assertFalse(json.has("thinking"))
            assertFalse(json.has("reasoning_effort"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun providerRejectionStaysVisibleExactlyOnceWithoutRetryingOrRewritingTheModel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("temperature 0.7 is unsupported"))
        val events = ExplanationClient().explain(config().copy(temperature = 0.7), LookupContext("hello")).toList()
        val failure = events.single()
        assertTrue(failure is ExplanationEvent.Failure)
        assertTrue((failure as ExplanationEvent.Failure).message.contains("temperature", true))
        assertFalse(events.toString().contains("is unsupported"))
        assertEquals(1, server.requestCount)
        val request = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("configured-model", request.get("model").asString)
        assertEquals(0.7, request.get("temperature")?.takeIf { it.isJsonPrimitive }?.asDouble ?: -1.0, 0.0)
    }

    @Test fun unusableTemperatureNeverReachesTheProvider() = runBlocking {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 2.1).forEach { value ->
            val events = ExplanationClient().explain(config().copy(temperature = value), LookupContext("hello")).toList()
            assertTrue("$value must fail before transport", events.single() is ExplanationEvent.Failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedEnvelopeNeverLeaksJsonOrReasoningInAnError() = runBlocking {
        server.enqueue(stream("data: [\"private reasoning and passage\"]\n\n"))
        val events = ExplanationClient().explain(config(), LookupContext("hello")).toList()
        assertTrue(events.single() is ExplanationEvent.Failure)
        assertFalse(events.toString().contains("private reasoning and passage"))
    }
}
