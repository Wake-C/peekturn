package app.margin.reader.speech

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The public read-aloud service this app reads sentence audio from. It states no creator or licence. */
private const val BING_SOURCE = "Microsoft Read Aloud"
private const val BING_SOURCE_URL = "https://speech.platform.bing.com"
private const val BING_SYNTHESIS_PATH = "consumer/speech/synthesize/readaloud/edge/v1"

/**
 * The service stops voicing text at around two hundred characters, so anything longer is refused
 * before a request: a truncated answer is not the text the user asked to hear.
 */
internal const val MAX_SPEECH_TEXT_LENGTH = 190

/** The credit for every clip from the read-aloud service: source name and address only. */
private val BING_CREDIT = AudioCredit(BING_SOURCE, BING_SOURCE_URL)

/** The client the service answers: an Edge browser asks this way, which is no licence claim. */
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
private const val CONFIG_BODY =
    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

/** The voices this service has for the accents this app offers; nothing else is ever requested. */
private const val US_VOICE = "en-US-AriaNeural"
private const val UK_VOICE = "en-GB-SoniaNeural"

private const val PATH_HEADER = "Path:"
private const val PATH_AUDIO = "audio"
private const val PATH_TURN_END = "turn.end"
private const val PATH_SPEECH_CONFIG = "speech.config"
private const val PATH_SSML = "ssml"
private const val CONFIG_CONTENT_TYPE = "application/json; charset=utf-8"
private const val SSML_CONTENT_TYPE = "application/ssml+xml"
private const val NORMAL_CLOSURE = 1000

/**
 * The whole request is bounded: the service answers in well under a second, and a handshake that
 * stalls must hand the text to the next route rather than hold the speaker.
 */
private const val LOOKUP_BUDGET_MS = 6_000L

/** Speech for a phrase, sentence or example; null means no usable audio, so device speech speaks. */
fun interface SentenceAudioSource {
    suspend fun lookup(text: String, accent: String): AudioClip?
}

/**
 * The only text this app sends to either speech service: the text the user asked to hear, trimmed and
 * no longer than the service can voice in one answer. Never surrounding book context or any credential.
 */
internal fun speechLookupText(text: String): String? =
    text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_SPEECH_TEXT_LENGTH }

/**
 * Speech for arbitrary text from the public read-aloud service: one bounded, key-free WebSocket
 * request per text, cancelled with its coroutine. The handshake the service expects drifts — the token
 * generation changed and a plain SHA-256 token is now refused — so a rejected or refused handshake, a
 * frame that is not audio, an answer past the byte ceiling and a lookup past the time budget all mean
 * "no audio", and the next route speaks instead. This function returns null rather than throwing:
 * only cancellation, which is not a failure, is passed on.
 */
class BingSpeechClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        // The text must reach only the host chosen here: a redirect answer is "no audio", never a
        // reason to re-send it somewhere else. The GET routes refuse redirects for the same reason.
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: HttpUrl = "$BING_SOURCE_URL/$BING_SYNTHESIS_PATH".toHttpUrl(),
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val connectionId: () -> String = { UUID.randomUUID().toString().replace("-", "") }
) : SentenceAudioSource {

    private val http = client

    override suspend fun lookup(text: String, accent: String): AudioClip? {
        val spoken = speechLookupText(text) ?: return null
        // The accents this app offers are the accents this service voices, so the same mapping gates
        // the request and names its voice.
        val locale = speechLocale(accent) ?: return null
        val audio = withTimeoutOrNull(LOOKUP_BUDGET_MS) { speak(spoken, locale) } ?: return null
        return AudioClip(audio, BING_CREDIT)
    }

    /**
     * One handshake: the configuration message, then the SSML request, then every audio frame until
     * the service ends the turn. The socket is closed on every path — including cancellation, which
     * closes it as soon as the request that owns it goes away — and only audio reaches the caller.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun speak(text: String, locale: Locale): ByteArray? {
        val session = Session(configMessage(), ssmlMessage(text, locale))
        val connection = http.newWebSocket(handshake(now()), session)
        val job = currentCoroutineContext()[Job]!!
        val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) connection.cancel()
        }
        val audio = try {
            session.audio.await()
        } finally {
            cancellation.dispose()
            if (session.ended) connection.close(NORMAL_CLOSURE, null) else connection.cancel()
        }
        return audio?.takeIf(::mpegAudio)
    }

    private fun handshake(seconds: Double) = Request.Builder()
        .url(
            endpoint.newBuilder()
                .addQueryParameter("TrustedClientToken", BING_TRUSTED_CLIENT_TOKEN)
                .addQueryParameter("Sec-MS-GEC", bingSecMsGec(seconds))
                .addQueryParameter("Sec-MS-GEC-Version", BING_TOKEN_VERSION)
                .addQueryParameter("ConnectionId", connectionId())
                .build()
        )
        .header("User-Agent", USER_AGENT)
        .header("Origin", ORIGIN)
        .header("Pragma", "no-cache")
        .header("Cache-Control", "no-cache")
        .build()

    private fun configMessage() = frame(CONFIG_CONTENT_TYPE, null, PATH_SPEECH_CONFIG) + CONFIG_BODY + "\r\n"

    private fun ssmlMessage(text: String, locale: Locale) =
        frame(SSML_CONTENT_TYPE, connectionId(), PATH_SSML) + ssml(text, locale)

    /** A frame's headers, in the order the service sends them: a request id where it needs one, then its path. */
    private fun frame(contentType: String, requestId: String?, path: String) =
        "X-Timestamp:${Instant.ofEpochMilli((now() * 1000).toLong())}\r\n" +
            (requestId?.let { "X-RequestId:$it\r\n" } ?: "") +
            "Content-Type:$contentType\r\nPath:$path\r\n\r\n"

    /** The request the service expects: only the text asked to hear, in the voice for that accent. */
    private fun ssml(text: String, locale: Locale) =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${locale.toLanguageTag()}'>" +
            "<voice name='${if (normalizeCountry(locale.country) == "GB") UK_VOICE else US_VOICE}'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>${ssmlEscape(text)}</prosody></voice></speak>"

    /**
     * The service's answers for one handshake: the audio frames it sends between the turn's start and
     * its end, up to the ceiling this app is willing to hold in memory. Anything unexpected — a failed
     * handshake, a socket that closes early, more audio than a clip can be — completes with no audio.
     */
    private class Session(private val config: String, private val ssml: String) : WebSocketListener() {
        val audio = CompletableDeferred<ByteArray?>()
        private val collected = ByteArrayOutputStream()

        /** Whether the service ended the turn, which is what makes a clean close the polite goodbye. */
        var ended = false
            private set

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(config)
            webSocket.send(ssml)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (bingFramePath(text.toByteArray(Charsets.UTF_8)) != PATH_TURN_END) return
            ended = true
            audio.complete(collected.toByteArray().takeIf { it.isNotEmpty() })
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The frame's own size is checked before anything is copied out of it, so a single
            // oversized frame cannot spike memory past the ceiling this route promises.
            if (collected.size() + bytes.size > MAX_AUDIO_BYTES) {
                audio.complete(null)
                webSocket.cancel()
                return
            }
            val payload = bingAudioPayload(bytes.toByteArray()) ?: return
            if (collected.size() + payload.size > MAX_AUDIO_BYTES) {
                // An answer larger than any usable clip is refused, not buffered.
                audio.complete(null)
                webSocket.cancel()
                return
            }
            collected.write(payload)
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            audio.complete(null)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.cancel()
            audio.complete(null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            audio.complete(null)
        }
    }
}

/**
 * SSML is XML, so the text is escaped rather than allowed to close the element it is spoken in. The
 * text is element content, where quotes carry no meaning and need no entity.
 */
private fun ssmlEscape(text: String) =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** The `Path:` a frame declares; the service answers `turn.start`, `response`, `turn.end` and audio. */
internal fun bingFramePath(frame: ByteArray): String? {
    val text = String(frame, Charsets.UTF_8)
    val blank = text.indexOf("\r\n\r\n")
    for (line in text.substring(0, if (blank < 0) text.length else blank).split("\r\n")) {
        if (line.startsWith(PATH_HEADER)) return line.substring(PATH_HEADER.length).trim()
    }
    return null
}

/**
 * The audio a binary frame carries, or null when the frame is not audio. A binary frame opens with its
 * header length as two big-endian bytes, then exactly those header bytes — which already end in the
 * blank line that separates them from the payload — and then the MP3 bytes themselves, so a frame too
 * short to hold its own headers, or one that does not declare `Path:audio`, is ignored rather than
 * guessed at.
 */
internal fun bingAudioPayload(frame: ByteArray): ByteArray? {
    if (frame.size < 2) return null
    val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
    if (2 + headerLength > frame.size) return null
    if (bingFramePath(frame.copyOfRange(2, 2 + headerLength)) != PATH_AUDIO) return null
    return frame.copyOfRange(2 + headerLength, frame.size).takeIf { it.isNotEmpty() }
}
