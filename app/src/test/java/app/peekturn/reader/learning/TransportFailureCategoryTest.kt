package app.peekturn.reader.learning

import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.model.ModelConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the provider-status and stream-protocol split apart from answer decoding: a billing failure,
 * rejected request parameters, a provider outage and a non-streaming response must never collapse
 * into one "check JSON mode" message. Categories are asserted by their stable name, which is also
 * the developer-facing failure code, so the check does not depend on enum declaration order.
 */
class TransportFailureCategoryTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    private fun config() = ModelConfig(
        endpoint = server.url("/v1").toString(), model = "test-model", apiKey = "test-key"
    )

    private fun failureFor(response: MockResponse): ExplanationEvent.Failure = runBlocking {
        server.enqueue(response)
        ExplanationClient().explain(config(), LookupContext("hello"), ExplanationLanguage.English)
            .toList().filterIsInstance<ExplanationEvent.Failure>().single()
    }

    @Test fun httpStatusAndStreamProtocolMapToDistinctFailures() {
        val cases = listOf(
            MockResponse().setResponseCode(402).setBody("private insufficient balance") to "Balance",
            MockResponse().setResponseCode(400).setBody("private parameter detail") to "InvalidRequest",
            MockResponse().setResponseCode(422) to "InvalidRequest",
            MockResponse().setResponseCode(500).setBody("private gateway detail") to "ProviderRejected",
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}") to "Protocol"
        )
        val messages = mutableMapOf<String, String>()
        for ((response, expected) in cases) {
            val failure = failureFor(response)
            assertEquals(expected, failure.category.name)
            assertFalse("Provider text must not reach the user copy", failure.message.contains("private"))
            messages[expected] = failure.message
        }
        assertFalse("A billing status is not a JSON mode problem", messages.getValue("Balance").contains("JSON"))
        assertFalse("A provider outage is not a JSON mode problem", messages.getValue("ProviderRejected").contains("JSON"))
        assertTrue("Rejected parameters keep the compatibility hint",
            messages.getValue("InvalidRequest").contains("parameters"))
    }
}
