package app.peekturn.reader.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YouTubeSourceCaptionsTest {
    private lateinit var server: MockWebServer
    private lateinit var source: YouTubeSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = YouTubeSource(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun track(url: String = server.url("/captions").toString()): CaptionTrack =
        CaptionTrack("vtt:en:manual", "en", false, url)

    private fun assertCaptionFailure(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected VideoFailure(Captions)")
        } catch (failure: VideoFailure) {
            assertEquals(VideoFailureKind.Captions, failure.kind)
        }
    }

    @Test
    fun `a successful response is parsed into the document`() {
        server.enqueue(
            MockResponse().setBody(
                "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nAmphetamines can drive\n"
            )
        )
        val document = runBlocking { source.captions("u4LUix-BU0s", track()) }
        assertEquals("u4LUix-BU0s", document.videoId)
        assertEquals("vtt:en:manual", document.trackId)
        assertEquals(1, document.cues.size)
        assertEquals("Amphetamines can drive", document.cues.single().text)
        assertEquals("/captions", server.takeRequest().path)
    }

    @Test
    fun `http errors empty bodies and html pages are caption failures`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertCaptionFailure { source.captions("u4LUix-BU0s", track()) }

        server.enqueue(MockResponse().setBody(""))
        assertCaptionFailure { source.captions("u4LUix-BU0s", track()) }

        server.enqueue(MockResponse().setBody("<!DOCTYPE html><html><body>Sign in</body></html>"))
        assertCaptionFailure { source.captions("u4LUix-BU0s", track()) }

        server.enqueue(MockResponse().setResponseCode(500).setBody("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nx"))
        assertCaptionFailure { source.captions("u4LUix-BU0s", track()) }
    }

    @Test
    fun `non remote track urls are refused without a request`() {
        assertCaptionFailure { source.captions("u4LUix-BU0s", track("file:///data/local/tmp/captions.vtt")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling a caption fetch aborts the blocked response read`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                return MockResponse()
                    .setBody("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nAmphetamines\n")
            }
        }
        try {
            runBlocking {
                // A long call timeout keeps this test about active cancellation, not a timeout.
                val slowSource = YouTubeSource(
                    OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
                )
                val job = launch(Dispatchers.IO) {
                    slowSource.captions("u4LUix-BU0s", track())
                }
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        } finally {
            // Let the blocked dispatcher finish so MockWebServer can shut down cleanly.
            release.countDown()
        }
    }

    @Test
    fun `invalid video ids are refused before any network work`() {
        try {
            runBlocking { source.captions("bad", track()) }
            fail("Expected VideoFailure(InvalidLink)")
        } catch (failure: VideoFailure) {
            assertEquals(VideoFailureKind.InvalidLink, failure.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `same-language manual tracks keep distinct stable identities`() {
        val first = CaptionStream(
            "en", false,
            "https://media.example/captions?lang=en&kind=transcript&fmt=vtt&expire=1&signature=AAA"
        )
        val second = CaptionStream(
            "en", false,
            "https://media.example/captions?lang=en&kind=transcript&fmt=vtt&expire=2&signature=BBB"
        )
        val source = CaptionSourceData(
            tracks = listOf(
                CaptionSourceTrack("en", false, ".en", "English", false),
                CaptionSourceTrack("en", false, ".en-orig", "English (Original)", false)
            ),
            translationLanguages = emptySet()
        )

        val tracks = mapCaptionTracks(listOf(first, second), source)

        assertEquals(listOf("vtt:.en", "vtt:.en-orig"), tracks.map { it.id })
        assertEquals(listOf(first.url, second.url), tracks.map { it.url })
        assertFalse(tracks.any { it.id.contains("AAA") || it.id.contains("BBB") })

        // A fresh signed URL is a new session, not a new track.
        val renewed = mapCaptionTracks(
            listOf(first.copy(url = first.url.replace("AAA", "CCC")), second.copy(url = second.url.replace("BBB", "DDD"))),
            source
        )
        assertEquals(tracks.map { it.id }, renewed.map { it.id })
    }

    @Test
    fun `without captured metadata identity uses only non-secret source fields`() {
        val stream = CaptionStream(
            "en", true,
            "https://media.example/captions?lang=en&kind=asr&signature=SECRET&expire=3"
        )

        val tracks = mapCaptionTracks(listOf(stream), null)

        assertEquals(1, tracks.size)
        assertEquals("vtt:en:asr", tracks.single().id)
        assertFalse(tracks.single().id.contains("SECRET"))
        assertNull(tracks.single().translatedFrom)
    }

    @Test
    fun `a name keeps two otherwise identical fallback tracks apart`() {
        val url = "https://media.example/captions?lang=en&kind=transcript&signature=SECRET"
        val streams = listOf(CaptionStream("en", false, url), CaptionStream("en", false, url))
        val source = CaptionSourceData(
            tracks = listOf(
                CaptionSourceTrack("en", false, null, "Doctor Smith", false),
                CaptionSourceTrack("en", false, null, "Community", false)
            ),
            translationLanguages = emptySet()
        )

        val tracks = mapCaptionTracks(streams, source)

        assertEquals(listOf("vtt:en:manual:doctor-smith", "vtt:en:manual:community"), tracks.map { it.id })
    }

    @Test
    fun `english translation exists only when the player map really supports it`() {
        val stream = CaptionStream(
            "fr", false,
            "https://media.example/timedtext?v=abc&lang=fr&kind=transcript&fmt=vtt&expire=9" +
                "&signature=SIGNED&sparams=ip"
        )
        val supported = CaptionSourceData(
            tracks = listOf(CaptionSourceTrack("fr", false, ".fr", "French", true)),
            translationLanguages = setOf("en")
        )

        val tracks = mapCaptionTracks(listOf(stream), supported)

        assertEquals(listOf("vtt:.fr:translated:en", "vtt:.fr"), tracks.map { it.id })
        val translated = tracks.first()
        assertEquals("en", translated.language)
        assertTrue(translated.automatic)
        assertEquals("fr", translated.translatedFrom)
        assertTrue(translated.url.startsWith(stream.url))
        assertTrue(translated.url.endsWith("&tlang=en"))
        for (field in listOf("v=abc", "lang=fr", "kind=transcript", "fmt=vtt", "expire=9",
            "signature=SIGNED", "sparams=ip")) {
            assertTrue(field, translated.url.contains(field))
        }

        val notTranslatable = supported.copy(
            tracks = listOf(CaptionSourceTrack("fr", false, ".fr", "French", false))
        )
        assertNull(mapCaptionTracks(listOf(stream), notTranslatable).firstOrNull { it.translatedFrom != null })

        val noEnglish = supported.copy(translationLanguages = setOf("de", "ja"))
        assertNull(mapCaptionTracks(listOf(stream), noEnglish).firstOrNull { it.translatedFrom != null })

        val english = CaptionStream("en", false, "https://media.example/timedtext?lang=en&signature=X")
        val englishSource = CaptionSourceData(
            tracks = listOf(CaptionSourceTrack("en", false, ".en", "English", true)),
            translationLanguages = setOf("en")
        )
        assertTrue(mapCaptionTracks(listOf(english), englishSource).none { it.translatedFrom != null })
    }

    @Test
    fun `a translation target is appended without rewriting the caption query`() {
        val url = "https://media.example/timedtext?v=abc&fmt=vtt&expire=9&signature=SIGNED"
        assertEquals("$url&tlang=en", withTranslationTarget(url, "en"))
        assertEquals(
            "https://media.example/timedtext?tlang=en",
            withTranslationTarget("https://media.example/timedtext", "en")
        )
        assertNull(withTranslationTarget("https://media.example/timedtext?v=abc&tlang=de", "en"))
        assertNull(withTranslationTarget("file:///data/local/tmp/captions.vtt", "en"))
        assertNull(withTranslationTarget(url, " "))
    }

    @Test
    fun `player caption metadata is parsed from the real response shape`() {
        val body = """
            {"captions":{"playerCaptionsTracklistRenderer":{
              "captionTracks":[
                {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=fr",
                 "name":{"runs":[{"text":"French"}]},
                 "vssId":".fr","languageCode":"fr","isTranslatable":true},
                {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr",
                 "name":{"simpleText":"English (auto)"},
                 "vssId":"a.en","languageCode":"en","isTranslatable":true}
              ],
              "translationLanguages":[
                {"languageCode":"en","languageName":{"simpleText":"English"}},
                {"languageCode":"de","languageName":{"simpleText":"German"}}
              ]
            }}}
        """.trimIndent()

        val parsed = parseCaptionSource(body)
        assertNotNull(parsed)
        val data = parsed!!
        assertEquals(2, data.tracks.size)
        assertEquals(".fr", data.tracks[0].vssId)
        assertEquals("French", data.tracks[0].name)
        assertTrue(data.tracks[0].translatable)
        assertTrue(data.tracks[1].automatic)
        assertEquals(setOf("en", "de"), data.translationLanguages)

        assertNull(parseCaptionSource("""{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[]}}}"""))
        assertNull(parseCaptionSource("not json"))
        assertNull(parseCaptionSource("""{"unrelated":true}"""))
    }
}
