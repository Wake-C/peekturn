package app.margin.reader.speech

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

/** The speech-service contract: one bounded, key-free request for the text asked for, or no audio. */
class TranslateSpeechClientTest {
    private lateinit var server: MockWebServer

    /** Bytes that look like an MPEG frame, so the client treats them as audio rather than as a bad answer. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(60)

    /** The service answers a request it will not voice with an error page, not with audio. */
    private val failureBody = "<html><body>400 Bad Request</body></html>"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = TranslateSpeechClient(OkHttpClient(), server.url("/"))

    private fun serve(body: ByteArray = audio, contentType: String = "audio/mpeg") {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type", contentType))
    }

    /** Text the requested text is built from: the exact selection, trimmed, never book context. */
    @Test fun onlyTextTheServiceCanVoiceIsSent() = runBlocking {
        assertEquals("hello", speechLookupText("  hello  "))
        assertEquals("a longer sentence.", speechLookupText("a longer sentence."))
        assertNull(speechLookupText("   "))
        assertNull(speechLookupText(""))
        assertNull("The service truncates near two hundred characters", speechLookupText("a".repeat(191)))
        assertNotNull(speechLookupText("a".repeat(190)))

        assertNull(client().lookup("", "US"))
        assertNull(client().lookup("   ", "UK"))
        assertNull(client().lookup("a".repeat(191), "US"))
        assertNull("Accents the app does not offer are never requested", client().lookup("hello", "AU"))
        assertNull(client().lookup("hello", ""))
        assertEquals(0, server.requestCount)
    }

    /** The endpoint answer is used as it came, and the credit names the service that supplied it. */
    @Test fun lookupAsksForExactlyTheEncodedTextAndItsLanguage() = runBlocking {
        serve()
        val spoken = "\u201cDon\u2019t wait \u2014 it\u2019s a well-known page.\u201d"
        val clip = client().lookup(spoken, "US")
        assertNotNull(clip)
        assertTrue("The served audio must be returned unchanged", clip!!.audio.contentEquals(audio))
        assertEquals(AudioCredit("Google Translate", "https://translate.google.com"), clip.credit)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/translate_tts", url.encodedPath)
        assertEquals("UTF-8", url.queryParameter("ie"))
        assertEquals("tw-ob", url.queryParameter("client"))
        assertEquals("en-US", url.queryParameter("tl"))
        assertEquals("Only the text asked to be heard is sent", spoken, url.queryParameter("q"))
        // Exactly these four parameters travel: no book context, titles or credentials can ride along.
        assertEquals(4, url.querySize)
    }

    @Test fun eachAccentRequestsItsOwnLanguage() = runBlocking {
        serve()
        client().lookup("hello", "UK")
        assertEquals("en-GB", server.takeRequest().requestUrl!!.queryParameter("tl"))
        serve()
        client().lookup("hello", "US")
        assertEquals("en-US", server.takeRequest().requestUrl!!.queryParameter("tl"))
    }

    /** A single word the dictionary could not voice is spoken here too: arbitrary text is what it takes. */
    @Test fun aSingleWordIsVoicedToo() = runBlocking {
        serve()
        assertNotNull(client().lookup("hello", "UK"))
        assertEquals("hello", server.takeRequest().requestUrl!!.queryParameter("q"))
    }

    @Test fun aProviderFailureOrANonAudioAnswerMeansNoAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(failureBody).setHeader("Content-Type", "text/html"))
        assertNull(client().lookup("hello", "US"))

        serve(failureBody.toByteArray(), "text/html")
        assertNull(client().lookup("hello", "US"))

        serve(ByteArray(0), "audio/mpeg")
        assertNull("An empty answer is not audio", client().lookup("hello", "UK"))
    }

    @Test fun oversizedAnswersAreRefusedInsteadOfBuffered() = runBlocking {
        serve(ByteArray(2 * 1024 * 1024 + 1))
        assertNull("A body past the audio limit must not be kept", client().lookup("hello", "UK"))
    }

    @Test fun aStalledProviderAnswerIsBoundedAndCancellable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertNull(withTimeoutOrNull(1_000) { client().lookup("hello", "UK") })
    }

    /** A redirect must never carry the requested text to a host the app did not choose to send it to. */
    @Test fun aRedirectIsNeverFollowedToAnotherHost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.test/copied.mp3"))
        assertNull("A redirect is not this service's audio", client().lookup("hello", "UK"))
        assertEquals("The text reaches only the service's own address", 1, server.requestCount)
    }
}
