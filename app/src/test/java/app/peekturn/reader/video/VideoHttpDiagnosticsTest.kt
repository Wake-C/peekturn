package app.peekturn.reader.video

import java.net.InetAddress
import java.net.ProtocolException
import java.net.Proxy
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class VideoHttpDiagnosticsTest {
    @Test fun redirectLoopIsVisibleWithoutExportingRequestOrResponseSecrets() {
        MockWebServer().use { server ->
            server.start()
            val events = mutableListOf<String>()
            val youtube = server.url("/private-watch?token=private-query").newBuilder().host("www.youtube.com").build()
            val consent = youtube.newBuilder().host("consent.youtube.com").encodedPath("/private-consent").build()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", if (request.getHeader("Host")!!.startsWith("consent.")) youtube else consent)
                    .setHeader("Set-Cookie", "secret-response-cookie=private-cookie")
                    .setBody("private-response-body")
            }
            val client = defaultVideoHttpClient(events::add).newBuilder()
                .proxy(Proxy.NO_PROXY)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                })
                .build()
            try {
                val failure = runCatching {
                    client.newCall(Request.Builder().url(youtube)
                        .header("Cookie", "private-request-cookie")
                        .header("Authorization", "Bearer private-key").build()).execute().close()
                }.exceptionOrNull()
                assertTrue(failure is ProtocolException)
                assertEquals(21, server.requestCount)
                assertEquals(21, events.count { it.contains(":s302:") })
                assertTrue(events.any { it.contains(":youtube:to:youtube_consent") })
                assertTrue(events.any { it.contains(":youtube_consent:to:youtube") })
                assertTrue(events.last().endsWith(":follow_up_limit"))
                val exported = events.joinToString("\n")
                for (secret in listOf("private-", "youtube.com", "http://", "Cookie", "Authorization")) {
                    assertFalse("Sensitive data escaped into diagnostics: $secret", exported.contains(secret))
                }
                assertTrue(events.all { it.length <= 64 })
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun healthyResponsesRemainReadableEvenWhenTheDiagnosticWriterFails() {
        MockWebServer().use { server ->
            server.start()
            for (writerFails in listOf(false, true)) {
                server.enqueue(MockResponse().setBody("private-response-body"))
                val events = mutableListOf<String>()
                val client = defaultVideoHttpClient {
                    if (writerFails) error("private-writer-detail")
                    events += it
                }.newBuilder().proxy(Proxy.NO_PROXY).build()
                try {
                    client.newCall(Request.Builder().url(server.url("/private-path?key=private-key")).build())
                        .execute().use { response ->
                            assertEquals(200, response.code)
                            assertEquals("private-response-body", response.body!!.string())
                        }
                    if (!writerFails) {
                        assertTrue(events.any { it.contains(":s200:other:to:none") })
                        assertTrue(events.last().endsWith(":end:h0"))
                        assertFalse(events.joinToString().contains("private-"))
                    }
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdownNow()
                }
            }
        }
    }
}
