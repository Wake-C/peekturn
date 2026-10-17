package app.margin.reader.speech

import app.margin.reader.BuildConfig
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol

/**
 * The step of an online attempt that stopped it. A report that says only "no audio" cannot tell a
 * phone whose clock the service rejected from a phone whose operator dropped the upgrade, so each
 * attempt keeps the stage it reached and the status, if any, it was given.
 */
enum class RouteStage(val label: String) {
    Resolve("resolve"),
    Connect("connect"),
    Tls("tls"),
    Handshake("handshake"),
    Response("response"),
    FirstByte("first byte"),
    Answer("answer")
}

/**
 * One online route's answer for one request, in the compact form a reader can send back: the route,
 * the stage that failed, the HTTP status when there was one, and how long the attempt took. It never
 * carries the text that was asked to be spoken.
 */
data class RouteOutcome(
    val route: String,
    val stage: RouteStage,
    val elapsedMs: Long,
    val httpStatus: Int? = null,
    val timedOut: Boolean = false,
    val detail: String? = null,
    /** Server clock minus device clock, when the service's `Date` header was observed. */
    val clockSkewMs: Long? = null
) {
    /** The human-readable half of the line; the report adds the route and elapsed milliseconds. */
    fun outcome(): String {
        val base = when {
            timedOut -> "timeout at ${stage.label}"
            stage == RouteStage.Handshake && httpStatus != null -> "handshake rejected HTTP $httpStatus"
            stage == RouteStage.Handshake -> "handshake not completed"
            stage == RouteStage.Response && httpStatus != null -> "HTTP $httpStatus"
            detail != null -> detail
            stage == RouteStage.FirstByte -> "no audio bytes"
            else -> "${stage.label} failed"
        }
        val skew = clockSkewMs ?: return base
        return "$base (clock skew ${if (skew >= 0) "+" else ""}${skew}ms)"
    }
}

/** The one line a failure report starts with, in the order the routes were tried and nothing else. */
internal fun onlineFailureReport(outcomes: List<RouteOutcome>): String =
    "online: " + outcomes.joinToString("; ") { "${it.route}=${it.outcome()} ${it.elapsedMs}ms" }

/**
 * The clock and build identity a wrong device clock would otherwise hide. Both are fixed facts of the
 * phone, not of the text, so the report can carry them without widening what leaves the device.
 */
internal fun environmentReport(clock: Instant = Instant.now()): String =
    "device clock: $clock; app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

/** Milliseconds since a [System.nanoTime] reading, for bounding and reporting an attempt. */
internal fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

/** The HTTP `Date` value as epoch milliseconds, or null when the header is absent or unparseable. */
internal fun parseHttpDate(value: String): Long? = try {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}

/**
 * The stage a failed attempt reached, from what the platform reported. Exceptions that name a cause
 * keep it; anything else falls back to the stage the listener had already observed.
 */
internal fun routeStage(error: Throwable, responseSeen: Boolean): RouteStage? = when (error) {
    is UnknownHostException -> RouteStage.Resolve
    is SSLException -> RouteStage.Tls
    is ConnectException -> RouteStage.Connect
    is SocketTimeoutException -> if (responseSeen) RouteStage.FirstByte else RouteStage.Connect
    is InterruptedIOException -> if (responseSeen) RouteStage.FirstByte else RouteStage.Connect
    else -> if (responseSeen) RouteStage.FirstByte else null
}

/**
 * The transport milestones of one attempt, kept on the client the attempt builds so a timeout can say
 * where it stopped: name resolution, the socket, the TLS handshake, the HTTP/WebSocket handshake, the
 * response status and the first body byte are all distinct answers.
 */
internal class RouteStageTracker(private val webSocket: Boolean) : EventListener() {
    @Volatile
    var stage: RouteStage = RouteStage.Connect

    override fun dnsStart(call: Call, domainName: String) {
        stage = RouteStage.Resolve
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        stage = RouteStage.Connect
    }

    override fun secureConnectStart(call: Call) {
        stage = RouteStage.Tls
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        if (stage == RouteStage.Connect || stage == RouteStage.Tls) stage = RouteStage.Handshake
    }

    override fun responseHeadersStart(call: Call) {
        stage = if (webSocket) RouteStage.Handshake else RouteStage.Response
    }

    override fun responseBodyStart(call: Call) {
        stage = RouteStage.FirstByte
    }
}
