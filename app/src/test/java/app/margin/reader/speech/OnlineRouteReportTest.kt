package app.margin.reader.speech

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
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
