package app.margin.reader.speech

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The read-aloud contract: one bounded, key-free handshake that carries only the text asked to hear,
 * answers with the service's own bytes credited to it, and means "no audio" for everything else.
 */
class BingSpeechClientTest {
    private lateinit var server: MockWebServer
    private val received = CopyOnWriteArrayList<String>()

    /** The connection and request id the client is told to use, so the handshake can be asserted. */
    private val connectionId = "0123456789ABCDEF0123456789ABCDEF"

    /** The clock the token is taken from, so the handshake can be compared with a locked vector. */
    private val seconds = 1700000000.0

    /** Bytes shaped like the service's MPEG frames, so the client treats them as audio. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x64, 0xC4.toByte()) + ByteArray(60)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        received.clear()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = BingSpeechClient(
        OkHttpClient(), server.url("/consumer/speech/synthesize/readaloud/edge/v1"), { seconds }, { connectionId }
    )

    /** How the service frames one audio chunk: its header length, then the headers, then the MP3 bytes. */
    private fun audioFrame(payload: ByteArray = audio, path: String = "audio"): ByteString {
        val headers = "X-RequestId:$connectionId\r\nContent-Type:audio/mpeg\r\nPath:$path\r\n".toByteArray(Charsets.US_ASCII)
        val frame = ByteArray(2 + headers.size + payload.size)
        frame[0] = ((headers.size shr 8) and 0xFF).toByte()
        frame[1] = (headers.size and 0xFF).toByte()
        headers.copyInto(frame, 2)
        payload.copyInto(frame, 2 + headers.size)
        return frame.toByteString()
    }

    private fun turnEnd() =
        "X-RequestId:$connectionId\r\nContent-Type:application/json\r\nPath:turn.end\r\n\r\n"

    /**
     * The service as a test double: it upgrades the handshake, records what the client sends, answers
     * the SSML request with [frames] and ends the turn, and reports when the socket goes away.
     */
    private fun serve(
        frames: List<ByteString> = listOf(audioFrame()),
        endTurn: Boolean = true,
        closed: CountDownLatch? = null
    ) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (!endTurn || !text.contains("Path:ssml")) return
                    for (frame in frames) webSocket.send(frame)
                    webSocket.send(turnEnd())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed?.countDown()
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, failure: Throwable, response: okhttp3.Response?) {
                    closed?.countDown()
                }
            })
        )
    }

    /** Only the text the app is willing to send ever reaches the service, and only for its two accents. */
    @Test fun onlyTextTheServiceCanVoiceIsSentAndNothingIsRequestedWithoutIt() = runBlocking {
        assertEquals("hello", speechLookupText("  hello  "))
        assertEquals("A little further, one page at a time.", speechLookupText("A little further, one page at a time."))
        assertNull(speechLookupText("   "))
        assertNull(speechLookupText(""))
        assertNull("The service truncates near two hundred characters", speechLookupText("a".repeat(MAX_SPEECH_TEXT_LENGTH + 1)))
        assertNotNull(speechLookupText("a".repeat(MAX_SPEECH_TEXT_LENGTH)))

        assertNull(client().lookup("", "US"))
        assertNull(client().lookup("   ", "UK"))
        assertNull(client().lookup("a".repeat(MAX_SPEECH_TEXT_LENGTH + 1), "US"))
        assertNull("Accents the app does not offer are never requested", client().lookup("hello", "AU"))
        assertNull(client().lookup("hello", ""))
        assertEquals("Nothing may be requested for text the service never sees", 0, server.requestCount)
    }

    /** The handshake the service expects: its token, its version, a fresh connection id, browser headers. */
    @Test fun theHandshakeCarriesTheGeneratedTokenAndTheHeadersTheServiceExpects() = runBlocking {
        serve()
        assertNotNull(client().lookup("hello", "US"))

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/consumer/speech/synthesize/readaloud/edge/v1", url.encodedPath)
        assertEquals(BING_TRUSTED_CLIENT_TOKEN, url.queryParameter("TrustedClientToken"))
        assertEquals("The token comes from the clock the client was given",
            bingSecMsGec(seconds), url.queryParameter("Sec-MS-GEC"))
        assertEquals(BING_TOKEN_VERSION, url.queryParameter("Sec-MS-GEC-Version"))
        assertEquals(connectionId, url.queryParameter("ConnectionId"))
        assertEquals(4, url.querySize)
        assertTrue("The service answers the browser it ships", request.getHeader("User-Agent")!!.contains("Edg/"))
        assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", request.getHeader("Origin"))
        assertEquals("no-cache", request.getHeader("Pragma"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))
    }

    /** The two frames the client sends: the configuration, then the SSML request for the exact text. */
    @Test fun theConfigurationAndSsmlRequestsCarryTheTextAndTheVoiceForTheAccent() = runBlocking {
        serve()
        val spoken = "\u201cDon\u2019t wait & see <this>\u201d"
        assertNotNull(client().lookup(spoken, "US"))

        assertEquals(2, received.size)
        val configuration = received[0]
        assertTrue(configuration.contains("Path:speech.config"))
        assertTrue("The client asks for the MP3 output the decoder can play",
            configuration.contains("audio-24khz-48kbitrate-mono-mp3"))
        val ssml = received[1]
        assertTrue(ssml.contains("Path:ssml"))
        assertTrue(ssml.contains("xml:lang='en-US'"))
        assertTrue("The accent the reader chose names the voice", ssml.contains("name='en-US-AriaNeural'"))
        assertTrue("The text is escaped rather than allowed to close the element",
            ssml.contains("\u201cDon\u2019t wait &amp; see &lt;this&gt;\u201d"))
        assertTrue("Only the text asked to hear is in the request body",
            ssml.substringAfter("\r\n\r\n").contains(spoken.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")))
    }

    @Test fun eachAccentAsksForItsOwnVoiceAndLanguage() = runBlocking {
        serve()
        assertNotNull(client().lookup("hello", "UK"))
        assertTrue(received[1].contains("xml:lang='en-GB'"))
        assertTrue(received[1].contains("name='en-GB-SoniaNeural'"))
        received.clear()
        serve()
        assertNotNull(client().lookup("hello", "US"))
        assertTrue(received[1].contains("xml:lang='en-US'"))
        assertTrue(received[1].contains("name='en-US-AriaNeural'"))
    }

    /** Every audio frame until the turn ends is the answer, and the service that sent it is credited. */
    @Test fun everyAudioFrameUntilTheTurnEndsIsCollectedAndCredited() = runBlocking {
        val second = audioFrame(ByteArray(40) { (it + 7).toByte() })
        serve(listOf(audioFrame(), second))
        val clip = client().lookup("hello", "US")

        assertNotNull(clip)
        assertTrue("The served audio is returned as it came",
            clip!!.audio.contentEquals(audio + ByteArray(40) { (it + 7).toByte() }))
        assertEquals(AudioCredit("Microsoft Read Aloud", "https://speech.platform.bing.com"), clip.credit)
        assertNull("A service that honours the accent carries no caveat", clip.noticeRes)
    }

    /** A turn that yields no audio, a frame that is not audio and a payload that is not MPEG mean nothing. */
    @Test fun anAnswerWithoutUsableAudioMeansNoAudio() = runBlocking {
        serve(frames = emptyList())
        assertNull("A turn that ends without audio is not audio", client().lookup("hello", "UK"))

        serve(frames = listOf(audioFrame("not audio at all".toByteArray(), path = "response")))
        assertNull("A binary frame that does not declare audio is ignored", client().lookup("hello", "UK"))

        serve(frames = listOf(audioFrame(ByteArray(0))))
        assertNull("A frame with no payload is not audio", client().lookup("hello", "UK"))

        serve(frames = listOf(audioFrame("<html>400 Bad Request</html>".toByteArray())))
        assertNull("Bytes that are not MPEG are not handed to the decoder", client().lookup("hello", "UK"))
    }

    @Test fun oversizedAnswersAreRefusedInsteadOfBuffered() = runBlocking {
        serve(frames = listOf(audioFrame(audio + ByteArray((MAX_AUDIO_BYTES + 1).toInt()))))
        assertNull("Audio past the limit must not be kept", client().lookup("hello", "UK"))
    }

    /** A handshake the service refuses — the token generation drifted, say — is no audio, not an error. */
    @Test fun aRefusedHandshakeMeansNoAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(client().lookup("hello", "US"))
        assertEquals(1, server.requestCount)
    }

    /** A refusal is reported with its route, HTTP status and elapsed time, and never the text. */
    @Test fun aRefusedHandshakeIsReportedWithItsHttpStatusAndNotTheText() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val outcomes = mutableListOf<RouteOutcome>()
        val spoken = "a private sentence"
        assertNull(client().lookup(spoken, "US") { outcomes += it })

        val outcome = outcomes.single()
        assertEquals("bing", outcome.route)
        assertEquals(RouteStage.Handshake, outcome.stage)
        assertEquals(403, outcome.httpStatus)
        assertNull("Without a Date header there is no clock evidence", outcome.clockSkewMs)
        assertEquals("handshake rejected HTTP 403", outcome.outcome())
        assertFalse("No selection text may reach the report", outcome.outcome().contains(spoken))
        assertTrue(outcome.elapsedMs >= 0)
    }

    /**
     * The token is derived from the device clock, so a 401/403 carrying the server's `Date` is retried
     * once with the clock corrected by the observed skew; the text and the host do not change.
     */
    @Test fun aRejectedHandshakeWithTheServerDateIsRetriedOnceWithTheCorrectedClock() = runBlocking {
        val deviceClock = 1_700_000_600.0
        val serverDate = "Tue, 14 Nov 2023 22:13:20 GMT"
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Date", serverDate))
        serve()
        val client = BingSpeechClient(
            OkHttpClient(), server.url("/consumer/speech/synthesize/readaloud/edge/v1"),
            { deviceClock }, { connectionId }
        )

        assertNotNull(client.lookup("hello", "US"))
        assertEquals("One retry, and no more", 2, server.requestCount)
        val first = server.takeRequest().requestUrl!!.queryParameter("Sec-MS-GEC")
        val second = server.takeRequest().requestUrl!!.queryParameter("Sec-MS-GEC")
        assertEquals("The first token uses the device's own clock", bingSecMsGec(deviceClock), first)
        assertEquals(
            "The retry uses the clock the server's Date proves",
            bingSecMsGec(1_700_000_000.0), second
        )
    }

    /** A retry that is refused too reports the status it got and the skew that was measured. */
    @Test fun aSecondRejectionReportsTheStatusAndTheObservedSkew() = runBlocking {
        val deviceClock = 1_700_000_600.0
        val serverDate = "Tue, 14 Nov 2023 22:13:20 GMT"
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Date", serverDate))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Date", serverDate))
        val outcomes = mutableListOf<RouteOutcome>()
        val client = BingSpeechClient(
            OkHttpClient(), server.url("/consumer/speech/synthesize/readaloud/edge/v1"),
            { deviceClock }, { connectionId }
        )

        assertNull(client.lookup("hello", "US") { outcomes += it })
        assertEquals(2, server.requestCount)
        val outcome = outcomes.single()
        assertEquals(403, outcome.httpStatus)
        assertEquals("Device is 10 minutes ahead of the server", -600_000L, outcome.clockSkewMs)
        assertEquals(
            "handshake rejected HTTP 403 (clock skew -600000ms)", outcome.outcome()
        )
    }

    /** An upgrade that is dropped without an HTTP answer is reported as a handshake never completed. */
    @Test fun anUpgradeThatNeverCompletesIsReportedAsSuch() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val outcomes = mutableListOf<RouteOutcome>()
        assertNull(client().lookup("hello", "US") { outcomes += it })

        val outcome = outcomes.single()
        assertEquals(RouteStage.Handshake, outcome.stage)
        assertNull(outcome.httpStatus)
        assertFalse(outcome.timedOut)
        assertEquals("handshake not completed", outcome.outcome())
    }

    /** A completed turn that carried nothing usable names the stage instead of saying only "no audio". */
    @Test fun everyUnusableAnswerIsReportedWithItsStage() = runBlocking {
        val outcomes = mutableListOf<RouteOutcome>()

        serve(frames = emptyList())
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals(RouteStage.FirstByte, outcomes.single().stage)
        assertEquals("no audio bytes", outcomes.single().outcome())

        outcomes.clear()
        serve(frames = listOf(audioFrame("<html>not audio</html>".toByteArray())))
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals("answer not audio", outcomes.single().outcome())

        outcomes.clear()
        serve(frames = listOf(audioFrame(audio + ByteArray((MAX_AUDIO_BYTES + 1).toInt()))))
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals("oversized answer", outcomes.single().outcome())

        outcomes.clear()
        serve(frames = listOf(audioFrame("not audio at all".toByteArray(), path = "response")))
        assertNull(client().lookup("hello", "US") { outcomes += it })
        assertEquals("A turn with no audio at all is the first-byte stage",
            RouteStage.FirstByte, outcomes.single().stage)
    }

    /** A handshake that is never answered must not hold the speaker, and its socket must be closed. */
    @Test fun aStalledHandshakeIsBoundedAndClosesTheSocket() = runBlocking {
        val closed = CountDownLatch(1)
        serve(endTurn = false, closed = closed)
        assertNull(withTimeoutOrNull(1_000) { client().lookup("hello", "US") })
        assertTrue("The socket is closed when the request that owns it goes away",
            closed.await(2, TimeUnit.SECONDS))
    }

    /** The frames the service sends are read exactly as it frames them. */
    @Test fun theServiceFramingIsReadAsTheServiceSendsIt() {
        val frame = audioFrame().toByteArray()
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        assertTrue("The audio payload is the bytes after the headers", bingAudioPayload(frame)!!.contentEquals(audio))
        assertEquals("audio", bingFramePath(frame.copyOfRange(2, 2 + headerLength)))
        assertEquals("turn.end", bingFramePath(turnEnd().toByteArray()))
        assertEquals("speech.config", bingFramePath(
            "X-RequestId:$connectionId\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{}"
                .toByteArray()
        ))
        assertNull("A frame too short to hold its own headers is not audio", bingAudioPayload(byteArrayOf(0, 40)))
        assertNull("A frame with no header length is not audio", bingAudioPayload(ByteArray(1)))
        assertNull("A frame whose path is not audio is not audio",
            bingAudioPayload(audioFrame(path = "response").toByteArray()))
    }
}
