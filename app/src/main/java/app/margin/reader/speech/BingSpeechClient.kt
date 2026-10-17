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
 * The whole route is bounded: the service answers in well under a second, and a handshake that
 * stalls must hand the text to the next route rather than hold the speaker. The bound is deliberately
 * under the player's four-second route budget so this client can report where it stopped.
 */
private const val LOOKUP_BUDGET_MS = 3_700L

/** A retry that cannot get at least this much of the budget is reported instead of attempted. */
private const val RETRY_MIN_MS = 250L

private const val NANOS_PER_MILLI = 1_000_000L

/** What a route says when it reached the service but the answer was unusable, never the spoken text. */
private const val OVERSIZED_ANSWER = "oversized answer"
private const val NO_AUDIO_BYTES = "no audio bytes"
private const val ANSWER_NOT_AUDIO = "answer not audio"
private const val CONNECTION_CLOSED = "connection closed before audio"

/** Speech for a phrase, sentence or example; null means no usable audio, so device speech speaks. */
interface SentenceAudioSource {
    /** The short, stable name this route is reported under: "bing" or "baidu". */
    val route: String

    /**
     * One bounded attempt. A failure calls [failed] with the stage it reached before returning null;
     * cancellation, which is a newer request rather than a failure, is never reported.
     */
    suspend fun lookup(text: String, accent: String, failed: (RouteOutcome) -> Unit = {}): AudioClip?
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

    override val route: String = "bing"

    private val http = client

    /**
     * The route's whole budget covers the handshake and the first audio frame. The token is derived from
     * the device clock, so a 401/403 that carries the service's own `Date` is retried once with the
     * clock corrected by the observed skew; the same text goes to the same host, and only the handshake
     * is repeated. A timeout or a rejection without a `Date` is reported rather than retried.
     */
    override suspend fun lookup(
        text: String,
        accent: String,
        failed: (RouteOutcome) -> Unit
    ): AudioClip? {
        val started = System.nanoTime()
        val spoken = speechLookupText(text) ?: return null
        // The accents this app offers are the accents this service voices, so the same mapping gates
        // the request and names its voice.
        val locale = speechLocale(accent) ?: return null
        val deadline = started + LOOKUP_BUDGET_MS * NANOS_PER_MILLI
        var skew: Long? = null
        var attempts = 0
        while (true) {
            val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (remaining <= 0) {
                failed(
                    RouteOutcome(
                        route, RouteStage.Handshake, elapsedMillis(started),
                        timedOut = true, clockSkewMs = skew
                    )
                )
                return null
            }
            val clock = now() + (skew ?: 0L) / 1000.0
            val answer = attempt(spoken, locale, clock, remaining)
            val audio = answer.audio
            if (audio != null) return AudioClip(audio, BING_CREDIT)
            attempts++
            val rejected = answer.httpStatus == 401 || answer.httpStatus == 403
            val retry = attempts == 1 && rejected && answer.clockSkewMs != null &&
                (deadline - System.nanoTime()) / NANOS_PER_MILLI >= RETRY_MIN_MS
            if (!retry) {
                failed(answer.outcome(route, elapsedMillis(started), skew ?: answer.clockSkewMs))
                return null
            }
            skew = answer.clockSkewMs
        }
    }

    /**
     * One handshake: the configuration message, then the SSML request, then every audio frame until
     * the service ends the turn. The socket is closed on every path — including cancellation, which
     * closes it as soon as the request that owns it goes away — and only audio reaches the caller.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun attempt(
        text: String,
        locale: Locale,
        clockSeconds: Double,
        budgetMs: Long
    ): Answer {
        val tracker = RouteStageTracker(webSocket = true)
        val session = Session(
            configMessage(), ssmlMessage(text, locale), tracker, (clockSeconds * 1000).toLong()
        )
        val connection = http.newBuilder().eventListener(tracker).build()
            .newWebSocket(handshake(clockSeconds), session)
        val job = currentCoroutineContext()[Job]!!
        val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) connection.cancel()
        }
        val finished = try {
            withTimeoutOrNull(budgetMs) { session.done.await() }
        } finally {
            cancellation.dispose()
            if (session.ended) connection.close(NORMAL_CLOSURE, null) else connection.cancel()
        }
        if (finished == null) {
            return Answer(null, tracker.stage, timedOut = true, clockSkewMs = session.clockSkewMs)
        }
        val audio = session.collected.toByteArray()
        return when {
            session.oversized -> Answer(null, RouteStage.Answer, detail = OVERSIZED_ANSWER)
            session.httpStatus != null -> Answer(
                null, RouteStage.Handshake,
                httpStatus = session.httpStatus, clockSkewMs = session.clockSkewMs
            )
            !session.ended -> Answer(
                null, if (session.opened) tracker.stage else RouteStage.Handshake,
                detail = CONNECTION_CLOSED
            )
            audio.isEmpty() -> Answer(null, RouteStage.FirstByte, detail = NO_AUDIO_BYTES)
            !mpegAudio(audio) -> Answer(null, RouteStage.Answer, detail = ANSWER_NOT_AUDIO)
            else -> Answer(audio, RouteStage.FirstByte)
        }
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
     * One attempt's answer. [audio] is present only when the turn delivered usable MPEG; otherwise the
     * stage, status and clock evidence are what a failure report can say.
     */
    private data class Answer(
        val audio: ByteArray?,
        val stage: RouteStage,
        val httpStatus: Int? = null,
        val timedOut: Boolean = false,
        val detail: String? = null,
        val clockSkewMs: Long? = null
    ) {
        fun outcome(route: String, elapsedMs: Long, skewMs: Long?): RouteOutcome =
            RouteOutcome(route, stage, elapsedMs, httpStatus, timedOut, detail, skewMs)
    }

    /**
     * The service's answers for one handshake: the audio frames it sends between the turn's start and
     * its end, up to the ceiling this app is willing to hold in memory. Anything unexpected — a failed
     * handshake, a socket that closes early, more audio than a clip can be — completes with the
     * evidence a failure report needs, including the service's own `Date` when it rejected the upgrade.
     */
    private class Session(
        private val config: String,
        private val ssml: String,
        private val tracker: RouteStageTracker,
        private val deviceNowMs: Long
    ) : WebSocketListener() {
        val done = CompletableDeferred<Unit>()
        val collected = ByteArrayOutputStream()

        /** Whether the service ended the turn, which is what makes a clean close the polite goodbye. */
        var ended = false
        var opened = false
        var oversized = false
        var httpStatus: Int? = null
        var clockSkewMs: Long? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // The upgrade is done; what remains is the first audio frame, not the handshake.
            opened = true
            tracker.stage = RouteStage.FirstByte
            webSocket.send(config)
            webSocket.send(ssml)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (bingFramePath(text.toByteArray(Charsets.UTF_8)) != PATH_TURN_END) return
            ended = true
            done.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The frame's own size is checked before anything is copied out of it, so a single
            // oversized frame cannot spike memory past the ceiling this route promises.
            if (collected.size() + bytes.size > MAX_AUDIO_BYTES) {
                oversized = true
                done.complete(Unit)
                webSocket.cancel()
                return
            }
            val payload = bingAudioPayload(bytes.toByteArray()) ?: return
            if (collected.size() + payload.size > MAX_AUDIO_BYTES) {
                // An answer larger than any usable clip is refused, not buffered.
                oversized = true
                done.complete(Unit)
                webSocket.cancel()
                return
            }
            collected.write(payload)
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            if (response != null) {
                // The upgrade was refused with a real HTTP answer: keep its status and the clock
                // evidence in its Date header, which is the only way to see a wrong device clock.
                httpStatus = response.code
                clockSkewMs = response.header("Date")
                    ?.let(::parseHttpDate)
                    ?.minus(deviceNowMs)
                tracker.stage = RouteStage.Handshake
            } else {
                val seen = tracker.stage == RouteStage.FirstByte
                // A cause the platform names keeps its stage; anything else that ended the upgrade
                // before a first byte means the handshake never completed, never that the text was
                // the problem.
                tracker.stage = routeStage(failure, responseSeen = seen) ?: when (tracker.stage) {
                    RouteStage.Resolve, RouteStage.Tls -> tracker.stage
                    else -> RouteStage.Handshake
                }
            }
            done.complete(Unit)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.cancel()
            done.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            done.complete(Unit)
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
