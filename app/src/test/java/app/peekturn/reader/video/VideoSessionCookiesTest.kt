package app.peekturn.reader.video

import java.net.InetAddress
import java.net.Proxy
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class VideoSessionCookiesTest {
    @Test fun webVerificationOnlyImportsAnonymousChallengeCookies() {
        val jar = VideoSessionCookies(now = { 1_800_000_000_000L })
        assertFalse(jar.importVerificationCookies("VISITOR_INFO1_LIVE=ordinary; SID=secret", null))
        assertTrue(jar.loadForRequest("https://www.youtube.com/".toHttpUrl()).isEmpty())

        assertTrue(jar.importVerificationCookies(
            "SID=secret; SAPISID=secret2; VISITOR_INFO1_LIVE=visitor; GOOGLE_ABUSE_EXEMPTION=proof",
            "HSID=login; s_gl=challenge"
        ))
        val youtube = jar.loadForRequest("https://www.youtube.com/watch".toHttpUrl())
        val google = jar.loadForRequest("https://www.google.com/".toHttpUrl())
        assertEquals(setOf("VISITOR_INFO1_LIVE", "GOOGLE_ABUSE_EXEMPTION"), youtube.map { it.name }.toSet())
        assertEquals(listOf("s_gl"), google.map { it.name })
        assertFalse((youtube + google).any { it.name in setOf("SID", "SAPISID", "HSID") })
        assertTrue(jar.loadForRequest("https://evil-youtube.com/".toHttpUrl()).isEmpty())
    }

    @Test fun importedVerificationExpiresAndDoesNotEscapeTheSession() {
        var time = 1_800_000_000_000L
        val jar = VideoSessionCookies(now = { time })
        assertTrue(jar.importVerificationCookies("GOOGLE_ABUSE_EXEMPTION=proof", null))
        assertTrue(VideoSessionCookies().loadForRequest("https://www.youtube.com/".toHttpUrl()).isEmpty())
        time += 30L * 60 * 1000
        assertTrue(jar.loadForRequest("https://www.youtube.com/".toHttpUrl()).isEmpty())
    }

    @Test fun domainPathSecureAndHostOnlyRulesAreRespected() {
        val jar = VideoSessionCookies()
        val origin = "https://www.youtube.com/start".toHttpUrl()
        jar.saveFromResponse(origin, listOf(
            Cookie.parse(origin, "host=one; Path=/watch; Secure")!!,
            Cookie.parse(origin, "domain=two; Domain=youtube.com; Path=/; Secure")!!
        ))
        assertEquals(listOf("host", "domain"), jar.loadForRequest("https://www.youtube.com/watch".toHttpUrl()).map { it.name })
        assertEquals(listOf("domain"), jar.loadForRequest("https://m.youtube.com/watch".toHttpUrl()).map { it.name })
        assertEquals(listOf("domain"), jar.loadForRequest("https://www.youtube.com/elsewhere".toHttpUrl()).map { it.name })
        assertTrue(jar.loadForRequest("http://www.youtube.com/watch".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://www.google.com/watch".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://youtube.com.example.org/watch".toHttpUrl()).isEmpty())
    }

    @Test fun unsupportedDomainsAndSpoofedCookieDomainsAreNotStored() {
        val jar = VideoSessionCookies()
        val other = "https://example.org/".toHttpUrl()
        jar.saveFromResponse(other, listOf(Cookie.parse(other, "outside=value")!!))
        val youtube = "https://www.youtube.com/".toHttpUrl()
        jar.saveFromResponse(youtube, listOf(Cookie.Builder().name("wrong").value("value").domain("google.com").build()))
        assertTrue(jar.loadForRequest(other).isEmpty())
        assertTrue(jar.loadForRequest("https://google.com/".toHttpUrl()).isEmpty())
    }

    @Test fun expiryDeletionAndReplacementDoNotLeaveStaleValues() {
        var time = 1_800_000_000_000L
        val jar = VideoSessionCookies(now = { time })
        val origin = "https://www.youtube.com/".toHttpUrl()
        fun cookie(value: String, expires: Long) = Cookie.Builder().name("session").value(value)
            .hostOnlyDomain(origin.host).path("/").expiresAt(expires).build()
        jar.saveFromResponse(origin, listOf(cookie("old", time + 1000)))
        jar.saveFromResponse(origin, listOf(cookie("new", time + 2000)))
        assertEquals("new", jar.loadForRequest(origin).single().value)
        time += 2000
        assertTrue(jar.loadForRequest(origin).isEmpty())
        jar.saveFromResponse(origin, listOf(cookie("present", time + 1000)))
        jar.saveFromResponse(origin, listOf(cookie("deleted", time - 1)))
        assertTrue(jar.loadForRequest(origin).isEmpty())
    }

    @Test fun memoryAndLifetimeAreBoundedAndNewClientsStartEmpty() {
        var time = 1_800_000_000_000L
        val jar = VideoSessionCookies(now = { time }, capacity = 2)
        val origin = "https://www.youtube.com/".toHttpUrl()
        fun cookie(name: String, value: String = "value") = Cookie.Builder().name(name).value(value)
            .hostOnlyDomain(origin.host).path("/").build()
        jar.saveFromResponse(origin, listOf(cookie("one"), cookie("two"), cookie("three")))
        assertEquals(listOf("two", "three"), jar.loadForRequest(origin).map { it.name })
        jar.saveFromResponse(origin, listOf(cookie("oversized", "x".repeat(4097))))
        assertEquals(2, jar.loadForRequest(origin).size)
        assertTrue(VideoSessionCookies().loadForRequest(origin).isEmpty())
        time += 24L * 60 * 60 * 1000
        assertTrue(jar.loadForRequest(origin).isEmpty())
    }

    @Test fun sourceClientCompletesServerCookieRedirectInsteadOfLooping() {
        MockWebServer().use { server ->
            server.start()
            val target = server.url("/watch").newBuilder().host("www.youtube.com").build()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Cookie")?.contains("anonymous-session=accepted") == true) {
                        MockResponse().setBody("ready")
                    } else MockResponse().setResponseCode(302).setHeader("Location", target)
                        .setHeader("Set-Cookie", "anonymous-session=accepted; Path=/; HttpOnly")
            }
            val events = mutableListOf<String>()
            val client = defaultVideoHttpClient(events::add).newBuilder().proxy(Proxy.NO_PROXY)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                }).build()
            try {
                client.newCall(Request.Builder().url(target).build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("ready", it.body!!.string())
                }
                assertEquals(2, server.requestCount)
                assertNull(server.takeRequest().getHeader("Cookie"))
                assertEquals("anonymous-session=accepted", server.takeRequest().getHeader("Cookie"))
                assertFalse(events.joinToString().contains("anonymous-session"))
                assertFalse(events.joinToString().contains("accepted"))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
