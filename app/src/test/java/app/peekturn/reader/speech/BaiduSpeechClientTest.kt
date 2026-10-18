package app.peekturn.reader.speech

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fallback contract: the same bounded, key-free request shapes as the other routes, one English
 * voice disclosed as such, and "no audio" for everything else.
 */
class BaiduSpeechClientTest {
    private lateinit var server: MockWebServer

    /** Bytes shaped like MPEG frames, so the client treats them as audio rather than as a bad answer. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(60)

    /** The endpoint answers a request it will not voice with an error page, not with audio. */
    private val failureBody = "<html><body>400 Bad Request</body></html>"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = BaiduSpeechClient(OkHttpClient(), server.url("/"))

    private fun serve(body: ByteArray = audio, contentType: String = "audio/mpeg") {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type", contentType))
    }

    /** Only the text the app is willing to send ever reaches the fallback, and only for its two accents. */
    @Test fun nothingIsRequestedForTextTheServiceShouldNotSee() = runBlocking {
        assertNull(client().lookup("", "US"))
        assertNull(client().lookup("   ", "UK"))
        assertNull(client().lookup("a".repeat(MAX_SPEECH_TEXT_LENGTH + 1), "US"))
        assertNull("Accents the app does not offer are never requested", client().lookup("hello", "AU"))
        assertEquals(0, server.requestCount)
    }

    /** One GET, carrying the exact text asked to hear and nothing else, then the service is credited. */
    @Test fun oneRequestCarriesTheEncodedTextAndTheClipCarriesTheServiceAndItsCaveat() = runBlocking {
        serve()
        val spoken = "\u201cDon\u2019t wait \u2014 it\u2019s a well-known page.\u201d"
        val clip = client().lookup(spoken, "US")

        assertNotNull(clip)
        assertTrue("The served audio must be returned unchanged", clip!!.audio.contentEquals(audio))
        assertEquals(AudioCredit("Baidu Translate", "https://fanyi.baidu.com"), clip.credit)
        assertEquals("The fallback has one English voice and says so", BAIDU_ACCENT_NOTICE, clip.noticeRes)

        assertEquals(1, server.requestCount)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/gettts", url.encodedPath)
        assertEquals("en", url.queryParameter("lan"))
        assertEquals("3", url.queryParameter("spd"))
        assertEquals("web", url.queryParameter("source"))
        assertEquals("Only the text asked to be heard is sent", spoken, url.queryParameter("text"))
        // Exactly these four parameters travel: no book context, titles or credentials can ride along.
        assertEquals(4, url.querySize)
    }

    /** The fallback cannot honour the accent, so both accepted accents take the same route honestly. */
    @Test fun bothAccentsAreVoicedByTheSameRequest() = runBlocking {
        serve()
        assertNotNull(client().lookup("hello", "UK"))
        assertEquals("hello", server.takeRequest().requestUrl!!.queryParameter("text"))
        serve()
        assertNotNull(client().lookup("hello", "US"))
        assertEquals("hello", server.takeRequest().requestUrl!!.queryParameter("text"))
    }

    @Test fun aProviderFailureOrANonAudioAnswerMeansNoAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(failureBody).setHeader("Content-Type", "text/html"))
        assertNull(client().lookup("hello", "US"))

        serve(failureBody.toByteArray(), "text/html")
        assertNull(client().lookup("hello", "US"))

        serve(ByteArray(0), "audio/mpeg")
        assertNull("An empty answer is not audio", client().lookup("hello", "UK"))
    }

    /** The route's failure is reported with the status or stage that produced it, never the text. */
    @Test fun everyFailedAnswerIsReportedWithItsStage() = runBlocking {
        val outcomes = mutableListOf<RouteOutcome>()

        server.enqueue(MockResponse().setResponseCode(400).setBody(failureBody))
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals(RouteStage.Response, outcomes.single().stage)
        assertEquals(400, outcomes.single().httpStatus)
        assertEquals("HTTP 400", outcomes.single().outcome())

        outcomes.clear()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.test/x.mp3"))
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals("HTTP 302", outcomes.single().outcome())

        outcomes.clear()
        serve(ByteArray((MAX_AUDIO_BYTES + 1).toInt()))
        assertNull(client().lookup("hello", "UK") { outcomes += it })
        assertEquals(RouteStage.Answer, outcomes.single().stage)
        assertEquals("oversized answer", outcomes.single().outcome())

        outcomes.clear()
        serve(failureBody.toByteArray(), "text/html")
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals("answer not audio", outcomes.single().outcome())

        outcomes.clear()
        serve(ByteArray(0), "audio/mpeg")
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals(RouteStage.FirstByte, outcomes.single().stage)
        assertEquals("no audio bytes", outcomes.single().outcome())
    }

    /** A cancellation belongs to whichever newer request caused it, so it never becomes a report. */
    @Test fun aCancelledRouteIsNotReportedAsAFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val outcomes = mutableListOf<RouteOutcome>()
        assertNull(withTimeoutOrNull(1_000) {
            client().lookup("hello", "UK") { outcomes += it }
        })
        assertTrue("A superseded attempt is not this request's failure", outcomes.isEmpty())
    }

    @Test fun oversizedAnswersAreRefusedInsteadOfBuffered() = runBlocking {
        serve(ByteArray((MAX_AUDIO_BYTES + 1).toInt()))
        assertNull("A body past the audio limit must not be kept", client().lookup("hello", "UK"))
    }

    @Test fun aStalledProviderAnswerIsBoundedAndCancellable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertNull(withTimeoutOrNull(1_000) { client().lookup("hello", "UK") })
    }

    /** A redirect must never carry the requested text to a host the app did not choose to send it to. */
    @Test fun aRedirectIsNeverFollowedToAnotherHost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.test/copied.mp3"))
        assertNull("A redirect is not this service's audio", client().lookup("hello", "US"))
        assertEquals("The text reaches only the service's own address", 1, server.requestCount)
    }
}
