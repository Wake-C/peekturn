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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The provider contract: one bounded, key-free request for one plain word, or no audio at all. */
class WordAudioProviderTest {
    private lateinit var server: MockWebServer

    /** Bytes that look like an MPEG frame, so the client treats them as audio rather than as a bad answer. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(60)

    /** The body the provider really returns when it cannot voice a lookup. */
    private val failureBody =
        """{"timestamp":1792149333868,"status":500,"error":"Internal Server Error","msg":"returned null audio","path":"/dictvoice"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = YoudaoWordAudioClient(OkHttpClient(), server.url("/"))

    private fun serve(body: ByteArray = audio, contentType: String = "audio/mpeg") {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type", contentType))
    }

    @Test fun onlyPlainSingleWordsMayBeLookedUp() {
        assertEquals("hello", wordLookupTerm("hello"))
        assertEquals("Hello", wordLookupTerm("  \u201cHello,\u201d  "))
        assertEquals("don't", wordLookupTerm("don\u2019t"))
        assertEquals("well-known", wordLookupTerm("well-known"))
        assertNull(wordLookupTerm(""))
        assertNull(wordLookupTerm("   "))
        assertNull(wordLookupTerm("two words"))
        assertNull(wordLookupTerm("A sentence with book context."))
        assertNull(wordLookupTerm("hello2"))
        assertNull(wordLookupTerm("a".repeat(49)))
        assertNull(wordLookupTerm("café"))
    }

    /** The accent parameter the open-source word trainers use: 1 is UK, 2 is US, nothing else. */
    @Test fun accentsMapToTheProviderContract() {
        assertEquals("2", voiceType("US"))
        assertEquals("1", voiceType("UK"))
        assertNull(voiceType("AU"))
        assertNull(voiceType(""))
    }

    /** A JSON error body, a truncated answer or stray bytes must never reach the decoder as audio. */
    @Test fun onlyRealMpegFramesCountAsAudio() {
        assertTrue(mpegAudio(byteArrayOf(0x49, 0x44, 0x33, 0x04)))
        assertTrue(mpegAudio(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())))
        assertTrue(mpegAudio(audio))
        assertFalse(mpegAudio(byteArrayOf(0xFF.toByte(), 0x00.toByte(), 0x00.toByte())))
        assertFalse(mpegAudio(failureBody.toByteArray()))
        assertFalse(mpegAudio("<html>error</html>".toByteArray()))
        assertFalse(mpegAudio(ByteArray(0)))
        assertFalse(mpegAudio(byteArrayOf(0xFF.toByte())))
    }

    @Test fun lookupAsksForExactlyTheEncodedWordAndItsAccent() = runBlocking {
        serve()
        val clip = client().lookup("don\u2019t", "UK")
        assertNotNull(clip)
        assertTrue("The served audio must be returned unchanged", clip!!.audio.contentEquals(audio))
        assertEquals(AudioCredit("Youdao Dictionary", "https://dict.youdao.com"), clip.credit)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest().requestUrl!!
        assertEquals("/dictvoice", request.encodedPath)
        assertEquals("don't", request.queryParameter("audio"))
        assertEquals("1", request.queryParameter("type"))
        assertEquals(2, request.querySize)
    }

    @Test fun eachAccentRequestsItsOwnType() = runBlocking {
        serve()
        client().lookup("hello", "UK")
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("type"))
        serve()
        client().lookup("hello", "US")
        assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("type"))
    }

    @Test fun aProviderFailureOrANonAudioAnswerMeansNoAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(failureBody).setHeader("Content-Type", "application/json"))
        assertNull(client().lookup("hello", "UK"))

        serve(failureBody.toByteArray(), "application/json")
        assertNull(client().lookup("hello", "UK"))

        serve("<html>gateway error</html>".toByteArray(), "text/html")
        assertNull(client().lookup("hello", "US"))

        serve(ByteArray(0), "audio/mpeg")
        assertNull("An empty answer is not audio", client().lookup("hello", "US"))
    }

    @Test fun oversizedAnswersAreRefusedInsteadOfBuffered() = runBlocking {
        serve(ByteArray(2 * 1024 * 1024 + 1))
        assertNull("A body past the audio limit must not be kept", client().lookup("hello", "UK"))
    }

    @Test fun aStalledProviderAnswerIsBoundedAndCancellable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertNull(withTimeoutOrNull(1_000) { client().lookup("hello", "UK") })
    }

    /** A redirect must never carry the requested word to a host the app did not choose to send it to. */
    @Test fun aRedirectIsNeverFollowedToAnotherHost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.test/copied.mp3"))
        assertNull("A redirect is not this provider's audio", client().lookup("hello", "UK"))
        assertEquals("The word reaches only the provider's own address", 1, server.requestCount)
    }

    @Test fun phrasesAndUnsupportedAccentsNeverReachTheNetwork() = runBlocking {
        assertNull(client().lookup("A little further, one page at a time.", "US"))
        assertNull(client().lookup("", "UK"))
        assertNull(client().lookup("hello", "AU"))
        assertEquals(0, server.requestCount)
    }
}
