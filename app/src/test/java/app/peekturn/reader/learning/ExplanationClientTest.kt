package app.peekturn.reader.learning

import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.model.ModelConfig
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
    private val payload = """{"term":"hello","partOfSpeech":"interjection","ukIpa":"","usIpa":"","meaning":"A greeting.","context":"Used to greet someone.","synonyms":"hi","antonyms":"goodbye","examples":[{"text":"Hello, how are you?","translation":""}]}"""
    private val bilingualPayload = """{"term":"turn down","partOfSpeech":"phrasal verb","ukIpa":"/tɜːn daʊn/","usIpa":"/tɝːn daʊn/","meaning":"拒绝","context":"这里表示拒绝某个提议。","synonyms":"refuse, reject","antonyms":"accept","examples":[{"text":"She turned down the offer.","translation":"她拒绝了那个提议。"}]}"""
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
        val events = ExplanationClient().explain(config(), LookupContext("hello", before = (1..40).joinToString(" ") { "before$it" }), ExplanationLanguage.English).toList()
        assertTrue(events.first() is ExplanationEvent.Content)
        assertEquals("hello", (events.last() as ExplanationEvent.Complete).answer.term)
        assertEquals("hi", (events.last() as ExplanationEvent.Complete).answer.synonyms)
        assertEquals("goodbye", (events.last() as ExplanationEvent.Complete).answer.antonyms)
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

    @Test fun chineseRequestPreservesSourceDataAndTheStructuredExampleSchema() = runBlocking {
        server.enqueue(stream(chunk(bilingualPayload) + chunk(finish = "stop") + "data: [DONE]\n\n"))
        val lookup = LookupContext("bank", "the river ", " was muddy", "Book", "Chapter")
        ExplanationClient().explain(config(), lookup, ExplanationLanguage.Chinese).toList()

        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val messages = json.getAsJsonArray("messages")
        val system = messages[0].asJsonObject["content"].asString
        val user = messages[1].asJsonObject["content"].asString
        val passage = JsonParser.parseString(user).asJsonObject
        assertEquals(lookup.selection, passage["selection"].asString)

        val schema = JsonParser.parseString(system.substringAfter("Required JSON schema: ")).asJsonObject
        val required = schema.getAsJsonArray("required").map { it.asString }
        assertTrue(required.containsAll(listOf("synonyms", "antonyms")))
        assertEquals("string", schema.getAsJsonObject("properties").getAsJsonObject("synonyms")["type"].asString)
        assertEquals("string", schema.getAsJsonObject("properties").getAsJsonObject("antonyms")["type"].asString)
        val exampleItems = schema.getAsJsonObject("properties").getAsJsonObject("examples").getAsJsonObject("items")
        assertEquals("object", exampleItems["type"].asString)
        assertFalse(exampleItems["additionalProperties"].asBoolean)
        assertEquals(setOf("text", "translation"), exampleItems.getAsJsonObject("properties").keySet())
        assertEquals(setOf("text", "translation"), exampleItems.getAsJsonArray("required").map { it.asString }.toSet())
    }


    @Test fun eofAndTokenLimitNeverBecomeSuccessfulCompletion() = runBlocking {
        listOf(chunk(payload), chunk(payload) + chunk(finish = "length"), chunk(payload.dropLast(2)) + chunk(finish = "stop")).forEach { body ->
            server.enqueue(stream(body))
            val events = ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English).toList()
            assertTrue(events.last() is ExplanationEvent.Failure)
            assertFalse(events.any { it is ExplanationEvent.Complete })
        }
    }

    @Test fun malformedLaterFieldRetainsTheSameValidatedContentAcrossChunkBoundaries() = runBlocking {
        val validPrefix = """{"term":"hello","partOfSpeech":"interjection","ukIpa":"","usIpa":"","meaning":"A greeting.","""
        val malformedSuffix = """"context":42,"examples":[{"text":"Hello, how are you?","translation":""}]}"""
        val bodies = listOf(
            chunk(validPrefix + malformedSuffix),
            chunk(validPrefix) + chunk(malformedSuffix)
        )
        val results = bodies.map { body ->
            server.enqueue(stream(body))
            ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English).toList()
        }
        val retainedAnswers = results.map { events ->
            assertTrue("Malformed output must terminate with Failure", events.last() is ExplanationEvent.Failure)
            assertFalse("Malformed output must never complete", events.any { it is ExplanationEvent.Complete })
            val retained = events.filterIsInstance<ExplanationEvent.Content>().lastOrNull()?.answer
            assertNotNull("Validated fields must survive a malformed later field in the same chunk", retained)
            assertEquals("hello", retained?.term)
            assertEquals("A greeting.", retained?.meaning)
            assertEquals("", retained?.context)
            assertEquals(emptyList<app.peekturn.reader.model.ExplanationExample>(), retained?.examples)
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
            val events = ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English).toList()
            assertTrue(events.last() is ExplanationEvent.Failure)
            assertFalse(events.toString().contains("private server diagnostic"))
        }
    }

    @Test fun everyFailureKeepsAStableActionableCategory() = runBlocking {
        val cases = listOf(
            MockResponse().setResponseCode(401).setBody("private rejection") to ExplanationFailureCategory.Authentication,
            MockResponse().setResponseCode(403) to ExplanationFailureCategory.Authentication,
            MockResponse().setResponseCode(402).setBody("private balance detail") to ExplanationFailureCategory.Balance,
            MockResponse().setResponseCode(429) to ExplanationFailureCategory.Quota,
            MockResponse().setResponseCode(400).setBody("private parameter detail") to ExplanationFailureCategory.InvalidRequest,
            MockResponse().setResponseCode(422) to ExplanationFailureCategory.InvalidRequest,
            MockResponse().setResponseCode(500).setBody("private gateway detail") to ExplanationFailureCategory.ProviderRejected,
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}") to ExplanationFailureCategory.Protocol,
            stream("data: {\"error\":{\"message\":\"private\"}}\n\n") to ExplanationFailureCategory.ProviderRejected,
            stream("data: {}\n\n") to ExplanationFailureCategory.Protocol,
            stream("data: {\"choices\":[{\"delta\":{\"refusal\":\"private\"}}]}\n\n") to ExplanationFailureCategory.Refusal,
            stream(chunk("not JSON") + chunk(finish = "stop")) to ExplanationFailureCategory.MalformedResponse,
            stream(chunk("{}") + chunk(finish = "stop")) to ExplanationFailureCategory.MalformedResponse,
            stream("data: [\"private\"]\n\n") to ExplanationFailureCategory.MalformedResponse,
            stream(chunk(payload)) to ExplanationFailureCategory.IncompleteResponse,
            stream(chunk(payload) + chunk(finish = "length")) to ExplanationFailureCategory.IncompleteResponse,
            stream(chunk(payload.dropLast(2)) + chunk(finish = "stop")) to ExplanationFailureCategory.IncompleteResponse,
            stream("") to ExplanationFailureCategory.IncompleteResponse
        )
        cases.forEach { (response, expected) ->
            server.enqueue(response)
            val events = ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English).toList()
            val failure = events.filterIsInstance<ExplanationEvent.Failure>().singleOrNull()
            assertNotNull("Expected one failure for $expected", failure)
            assertEquals("Unexpected category: ${failure?.message}", expected, failure?.category)
            assertFalse("Provider text must not reach localized UI copy", events.toString().contains("private"))
        }
        val offline = ExplanationClient(ModelChatTransport(OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()))
            .explain(config().copy(endpoint = "http://127.0.0.1:1/v1"), LookupContext("hello"), ExplanationLanguage.English)
            .toList().filterIsInstance<ExplanationEvent.Failure>().single()
        assertEquals(ExplanationFailureCategory.Connectivity, offline.category)
        val requests = server.requestCount
        val configurationCases = listOf(
            config().copy(apiKey = ""),
            config().copy(model = ""),
            config().copy(temperature = 2.1),
            config().copy(endpoint = "not a url")
        )
        configurationCases.forEach { profile ->
            val failure = ExplanationClient().explain(profile, LookupContext("hello"), ExplanationLanguage.English)
                .toList().filterIsInstance<ExplanationEvent.Failure>().single()
            assertEquals(ExplanationFailureCategory.Configuration, failure.category)
        }
        assertEquals("Configuration failures never reach the provider", requests, server.requestCount)
    }

    @Test fun missingKeyDoesNotConnect() = runBlocking {
        val events = ExplanationClient().explain(config().copy(apiKey = ""), LookupContext("hello"), ExplanationLanguage.English).toList()
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
        val job = launch(Dispatchers.Default) { ExplanationClient(ModelChatTransport(http)).explain(config(), LookupContext("hello"), ExplanationLanguage.English).toList(events) }
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
            ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English).collect { event ->
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
        ExplanationClient().explain(profile, LookupContext("hello"), ExplanationLanguage.English).toList()
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
        val events = ExplanationClient().explain(config().copy(temperature = 0.7), LookupContext("hello"), ExplanationLanguage.English).toList()
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
            val events = ExplanationClient().explain(config().copy(temperature = value), LookupContext("hello"), ExplanationLanguage.English).toList()
            assertTrue("$value must fail before transport", events.single() is ExplanationEvent.Failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedEnvelopeNeverLeaksJsonOrReasoningInAnError() = runBlocking {
        server.enqueue(stream("data: [\"private reasoning and passage\"]\n\n"))
        val events = ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English).toList()
        assertTrue(events.single() is ExplanationEvent.Failure)
        assertFalse(events.toString().contains("private reasoning and passage"))
    }
}
