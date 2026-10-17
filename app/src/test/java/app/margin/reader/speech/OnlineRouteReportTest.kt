package app.margin.reader.speech

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The online report's contract: a failed attempt keeps the stage the platform named, the line carries
 * exactly route, outcome and elapsed milliseconds, and a rejected handshake carries the clock evidence
 * that separates a wrong clock from a blocked upgrade.
 */
class OnlineRouteReportTest {
    @Test fun aFailureKeepsTheStageThePlatformNamed() {
        assertEquals(RouteStage.Resolve, routeStage(UnknownHostException("no dns"), responseSeen = false))
        assertEquals(RouteStage.Connect, routeStage(ConnectException("refused"), responseSeen = false))
        assertEquals(RouteStage.Tls, routeStage(SSLHandshakeException("bad certificate"), responseSeen = false))
        assertEquals(RouteStage.Connect, routeStage(SocketTimeoutException("connect"), responseSeen = false))
        assertEquals(RouteStage.FirstByte, routeStage(SocketTimeoutException("read"), responseSeen = true))
        assertNull("An unnamed error before a response keeps the observed stage",
            routeStage(IOException("reset"), responseSeen = false))
    }

    /** A stall keeps the milestone the tracker saw, so a TLS or header wait is not called a connect. */
    @Test fun aTimeoutKeepsTheStageTheTrackerObserved() {
        assertEquals(RouteStage.Tls,
            routeStage(SocketTimeoutException("tls"), responseSeen = false, observed = RouteStage.Tls))
        assertEquals(RouteStage.Handshake,
            routeStage(SocketTimeoutException("upgrade"), responseSeen = false, observed = RouteStage.Handshake))
        assertEquals(RouteStage.Response,
            routeStage(InterruptedIOException("headers"), responseSeen = false, observed = RouteStage.Response))
        assertEquals(RouteStage.Connect,
            routeStage(SocketTimeoutException("nothing observed"), responseSeen = false))
    }

    /** A plain GET is waiting for its response after connecting, never for a WebSocket handshake. */
    @Test fun aPlainGetIsNotReportedAsAHandshake() {
        val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1/").build())
        val address = InetSocketAddress("127.0.0.1", 80)

        val get = RouteStageTracker(webSocket = false)
        get.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        assertEquals(RouteStage.Response, get.stage)

        val socket = RouteStageTracker(webSocket = true)
        socket.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        assertEquals(RouteStage.Handshake, socket.stage)
    }

    /** The lead counts the routes that actually ran, so a word's three are not called "both". */
    @Test fun theLeadNamesTheNumberOfRoutesThatActuallyRan() {
        val bing = RouteOutcome("bing", RouteStage.Answer, 1)
        val baidu = RouteOutcome("baidu", RouteStage.Answer, 2)
        val dictionary = RouteOutcome(DICTIONARY_ROUTE, RouteStage.Answer, 3)

        assertEquals("Online audio failed", onlineFailureLead(emptyList()))
        assertEquals("Online audio failed", onlineFailureLead(listOf(bing)))
        assertEquals("Both online audio routes failed", onlineFailureLead(listOf(bing, baidu)))
        assertEquals("All 3 online audio routes failed", onlineFailureLead(listOf(dictionary, bing, baidu)))
    }

    @Test fun theReportLineCarriesEachRouteItsOutcomeAndMilliseconds() {
        val line = onlineFailureReport(
            listOf(
                RouteOutcome("bing", RouteStage.Handshake, 812, httpStatus = 403),
                RouteOutcome("baidu", RouteStage.Connect, 4000, timedOut = true)
            )
        )
        assertEquals(
            "online: bing=handshake rejected HTTP 403 812ms; baidu=timeout at connect 4000ms",
            line
        )
    }

    @Test fun aRejectedHandshakeCarriesTheSkewItMeasured() {
        val behind = RouteOutcome("bing", RouteStage.Handshake, 700, httpStatus = 403, clockSkewMs = -600000)
        assertEquals("handshake rejected HTTP 403 (clock skew -600000ms)", behind.outcome())
        val ahead = RouteOutcome("bing", RouteStage.Handshake, 700, httpStatus = 403, clockSkewMs = 5231)
        assertEquals("handshake rejected HTTP 403 (clock skew +5231ms)", ahead.outcome())
    }

    @Test fun aHandshakeWithNoStatusSaysItNeverCompleted() {
        assertEquals("handshake not completed", RouteOutcome("bing", RouteStage.Handshake, 3000).outcome())
        assertEquals("timeout at handshake",
            RouteOutcome("bing", RouteStage.Handshake, 3700, timedOut = true).outcome())
    }

    @Test fun theEnvironmentLineNamesTheDeviceClockAndBuild() {
        val line = environmentReport(Instant.parse("2026-10-17T00:00:00Z"))
        assertTrue(line.startsWith("device clock: 2026-10-17T00:00:00Z; app: "))
        assertTrue("The version identity is part of the line", line.contains("("))
    }
}

/** OkHttp wraps the platform failure; the stage must come from the cause, not the wrapper. */
class RouteStageCauseTest {
    @org.junit.Test fun `a refused connection reads as a connection failure through its cause`() {
        val wrapped = java.io.IOException(
            "upgrade failed",
            java.net.ConnectException("Connection refused")
        )
        org.junit.Assert.assertEquals(
            RouteStage.Connect,
            routeStage(wrapped, responseSeen = false, observed = RouteStage.Handshake)
        )
    }

    @org.junit.Test fun `a stalled read after headers is a first-byte stall`() {
        val wrapped = java.io.IOException(
            "read",
            java.net.SocketTimeoutException("timeout")
        )
        org.junit.Assert.assertEquals(
            RouteStage.FirstByte,
            routeStage(wrapped, responseSeen = true)
        )
    }
}
