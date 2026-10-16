package app.margin.reader.speech

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WordAudioSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var clientCertificates: HandshakeCertificates
    private val audio = ByteArray(64) { it.toByte() }

    /**
     * The live Commons Action API answer for `File:En-uk-hello.ogg`, `File:En-us-hello.ogg` and a
     * missing `File:En-uk-reluctant.ogg`, trimmed to the fields this client reads.
     */
    private val payload = """
        {"batchcomplete":true,"query":{"pages":[
        {"title":"File:En-uk-reluctant.ogg","missing":true},
        {"title":"File:En-us-hello.ogg","pageid":268421,"imageinfo":[{"url":"https://upload.wikimedia.org/wikipedia/commons/5/52/En-us-hello.ogg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=original","descriptionurl":"https://commons.wikimedia.org/wiki/File:En-us-hello.ogg","extmetadata":{"Artist":{"value":"<a href=\"//commons.wikimedia.org/wiki/User:Dvortygirl\" title=\"User:Dvortygirl\">Dvortygirl</a>"},"Credit":{"value":"<span class=\"int-own-work\" lang=\"en\">Own work</span>"},"LicenseShortName":{"value":"Public domain"},"UsageTerms":{"value":"Public domain"}}}]},
        {"title":"File:En-uk-hello.ogg","pageid":9021983,"imageinfo":[{"url":"https://upload.wikimedia.org/wikipedia/commons/4/48/En-uk-hello.ogg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=original","descriptionurl":"https://commons.wikimedia.org/wiki/File:En-uk-hello.ogg","extmetadata":{"Artist":{"value":"Association Shtooka, Judith Franck"},"Credit":{"value":"<a href=\"//commons.wikimedia.org/wiki/Commons:Shtooka\" title=\"Commons:Shtooka\">Shtooka Project</a>"},"LicenseShortName":{"value":"CC BY 3.0 us"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by/3.0/us/deed.en"},"UsageTerms":{"value":"Creative Commons Attribution 3.0 us"}}}]}
        ]}}
    """.trimIndent()

    @Before fun setUp() {
        // The source only ever speaks HTTPS, so the fixture serves HTTPS and trusts only its own certificate.
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(mediaHost: String = server.url("/").host) = WordAudioClient(
        OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build(),
        server.url("/"),
        mediaHost
    )

    /** The same answer with its media address on this test server, so the audio fetch stays local. */
    private fun serverPayload() = payload.replace("https://upload.wikimedia.org", server.url("/").toString().trimEnd('/'))

    /** Serves the metadata answer and the audio bytes its media address points at. */
    private fun serve(media: ByteArray = audio, metadata: String = serverPayload()) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/w/api.php") -> MockResponse().setBody(metadata)
                else -> MockResponse().setBody(Buffer().write(media))
            }
        }
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

    @Test fun recordingTitleNamesTheRequestedRegionOnly() {
        assertEquals("File:En-us-hello.ogg", recordingTitle("hello", "US"))
        assertEquals("File:En-uk-hello.ogg", recordingTitle("hello", "UK"))
        assertNull(recordingTitle("hello", "AU"))
    }

    @Test fun requestedRegionFileKeepsItsCreatorAndLicence() {
        val recording = commonsRecording(payload, "File:En-uk-hello.ogg")
        assertNotNull(recording)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/4/48/En-uk-hello.ogg", recording!!.mediaUrl)
        assertEquals("https://commons.wikimedia.org/wiki/File:En-uk-hello.ogg", recording.sourceUrl)
        assertEquals("Association Shtooka, Judith Franck", recording.creator)
        assertEquals("CC BY 3.0 us", recording.licenseName)
        assertEquals("https://creativecommons.org/licenses/by/3.0/us/deed.en", recording.licenseUrl)
        val credit = recordingCredit(recording)
        assertEquals(RecordingCredit(
            "Association Shtooka, Judith Franck",
            "Wikimedia Commons",
            "https://commons.wikimedia.org/wiki/File:En-uk-hello.ogg",
            "CC BY 3.0 us",
            "https://creativecommons.org/licenses/by/3.0/us/deed.en"
        ), credit)
    }

    @Test fun publicDomainFileIsCreditedWithoutAnInventedLicenceUrl() {
        val recording = commonsRecording(payload, "File:En-us-hello.ogg")
        assertNotNull(recording)
        assertEquals("Dvortygirl", recording!!.creator)
        assertEquals("Public domain", recording.licenseName)
        assertNull(recording.licenseUrl)
        val credit = recordingCredit(recording)
        assertNotNull(credit)
        assertEquals("Dvortygirl", credit!!.creator)
        assertEquals("Public domain", credit.licenseName)
        assertEquals("", credit.licenseUrl)
        assertEquals("Wikimedia Commons", credit.sourceName)
    }

    @Test fun missingFileNeverSubstitutesTheOtherAccent() {
        assertNull(commonsRecording(payload, "File:En-uk-reluctant.ogg"))
        assertNull(commonsRecording(payload, "File:En-au-hello.ogg"))
        assertNull(commonsRecording(payload, "File:En-uk-other-word.ogg"))
    }

    /** MediaWiki folds only the first letter of a page name: every other letter stays exact. */
    @Test fun pageTitlesMatchExactlyExceptTheFirstLetter() {
        val folded = payload.replace("\"title\":\"File:En-uk-hello.ogg\"", "\"title\":\"file:En-uk-hello.ogg\"")
        assertNotNull(commonsRecording(folded, "File:En-uk-hello.ogg"))
        assertNull("A capitalised word is a different title", commonsRecording(payload, "File:En-uk-Hello.ogg"))
    }

    @Test fun mediaAddressesOutsideTheConfiguredCdnAreRejected() {
        val foreign = """
            {"query":{"pages":[{"title":"File:En-us-hello.ogg","pageid":1,"imageinfo":[{"url":"https://example.test/hello.ogg",
            "descriptionurl":"https://commons.wikimedia.org/wiki/File:En-us-hello.ogg","extmetadata":{"Artist":{"value":"Dvortygirl"}}}]}]}}
        """.trimIndent()
        assertNull(commonsRecording(foreign, "File:En-us-hello.ogg"))

        val insecure = foreign.replace("https://example.test/hello.ogg", "http://upload.wikimedia.org/wikipedia/commons/5/52/En-us-hello.ogg")
        assertNull(commonsRecording(insecure, "File:En-us-hello.ogg"))

        val elsewhere = foreign.replace("https://example.test/hello.ogg", "https://upload.wikimedia.org/wikipedia/commons/../secret/hello.ogg")
        assertNull(commonsRecording(elsewhere, "File:En-us-hello.ogg"))

        assertNotNull("The production CDN address is still accepted", commonsRecording(payload, "File:En-us-hello.ogg", "upload.wikimedia.org"))
        assertNull("Another host never counts as the CDN", commonsRecording(payload, "File:En-us-hello.ogg", "cdn.example.test"))
    }

    @Test fun licensedRecordingsWithoutANamedCreatorAreNotPlayed() {
        val licensed = CommonsRecording(
            "https://upload.wikimedia.org/wikipedia/commons/1/1/En-us-word.ogg",
            "https://commons.wikimedia.org/wiki/File:En-us-word.ogg",
            creator = null,
            licenseName = "CC BY 3.0 us",
            licenseUrl = "https://creativecommons.org/licenses/by/3.0/us/deed.en"
        )
        assertNull(recordingCredit(licensed))
        assertNotNull(recordingCredit(licensed.copy(licenseName = "Public domain", licenseUrl = null)))
        assertNotNull(recordingCredit(licensed.copy(creator = "Dvortygirl")))
        assertNull(recordingCredit(licensed.copy(creator = null, licenseName = null, licenseUrl = null)))
        assertNull(recordingCredit(licensed.copy(sourceUrl = " ")))
    }

    @Test fun onlyFreeLicencesMayBePlayed() {
        val base = CommonsRecording(
            "https://upload.wikimedia.org/wikipedia/commons/1/1/En-us-word.ogg",
            "https://commons.wikimedia.org/wiki/File:En-us-word.ogg",
            creator = "Someone",
            licenseName = "All rights reserved",
            licenseUrl = null
        )
        assertNull("A reserved file is not playback authorization", recordingCredit(base))
        assertNull(recordingCredit(base.copy(licenseName = "CC BY-NC 4.0", licenseUrl = "https://creativecommons.org/licenses/by-nc/4.0/")))
        assertNull(recordingCredit(base.copy(licenseName = "CC BY-ND 3.0", licenseUrl = null)))
        assertNull(recordingCredit(base.copy(licenseName = "GFDL", licenseUrl = null)))
        assertNotNull(recordingCredit(base.copy(licenseName = "CC BY-SA 4.0", licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/")))
        assertNotNull(recordingCredit(base.copy(licenseName = "CC0 1.0 Universal", licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/", creator = null)))
        assertNotNull(recordingCredit(base.copy(licenseName = "Public domain", licenseUrl = null, creator = null)))
    }

    @Test fun attributionLinksMustBeHttpsAndOnCommons() {
        val base = CommonsRecording(
            "https://upload.wikimedia.org/wikipedia/commons/1/1/En-us-word.ogg",
            "https://commons.wikimedia.org/wiki/File:En-us-word.ogg",
            creator = "Someone",
            licenseName = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/"
        )
        assertNull(recordingCredit(base.copy(sourceUrl = "http://commons.wikimedia.org/wiki/File:En-us-word.ogg")))
        assertNull(recordingCredit(base.copy(sourceUrl = "https://example.test/wiki/File:En-us-word.ogg")))
        val dropped = recordingCredit(base.copy(licenseUrl = "http://creativecommons.org/licenses/by/4.0/"))
        assertNotNull(dropped)
        assertEquals("", dropped!!.licenseUrl)
    }

    /** Commons metadata without Artist lists only project boilerplate, which names no creator. */
    private fun creditOnlyPayload(license: String, licenseUrl: String) = """
        {"query":{"pages":[{"title":"File:En-us-word.ogg","pageid":7,"imageinfo":[
        {"url":"https://upload.wikimedia.org/wikipedia/commons/1/1/En-us-word.ogg",
        "descriptionurl":"https://commons.wikimedia.org/wiki/File:En-us-word.ogg",
        "extmetadata":{"Credit":{"value":"<span class=\"int-own-work\" lang=\"en\">Own work</span>"},
        "LicenseShortName":{"value":"$license"}${if (licenseUrl.isEmpty()) "" else ",\"LicenseUrl\":{\"value\":\"$licenseUrl\"}"}}}]}]}}
    """.trimIndent()

    @Test fun boilerplateCreditTextNeverCountsAsACreator() {
        val licensed = commonsRecording(
            creditOnlyPayload("CC BY 3.0 us", "https://creativecommons.org/licenses/by/3.0/us/deed.en"),
            "File:En-us-word.ogg"
        )
        assertNotNull(licensed)
        assertNull("Own work is not a creator name", licensed!!.creator)
        assertNull("A BY file without a named artist must not play", recordingCredit(licensed))

        val publicDomain = commonsRecording(creditOnlyPayload("Public domain", ""), "File:En-us-word.ogg")
        assertNotNull(publicDomain)
        assertNull(publicDomain!!.creator)
        val credit = recordingCredit(publicDomain)
        assertNotNull("A public-domain file needs no named creator", credit)
        assertNull(credit!!.creator)
        assertEquals("", credit.licenseUrl)
    }

    @Test fun lookupFetchesTheStatedAddressAndKeepsItsCredit() = runBlocking {
        serve()
        val recording = client().lookup("hello", "UK")
        assertNotNull(recording)
        assertTrue("The served audio must be returned unchanged", recording!!.audio.contentEquals(audio))
        assertEquals("Association Shtooka, Judith Franck", recording.credit.creator)
        assertEquals(2, server.requestCount)
        val metadata = server.takeRequest().requestUrl!!
        assertEquals("File:En-uk-hello.ogg", metadata.queryParameter("titles"))
        assertEquals("2", metadata.queryParameter("formatversion"))
        assertEquals("url|extmetadata", metadata.queryParameter("iiprop"))
        assertTrue("The audio comes from the address Commons stated", server.takeRequest().path.orEmpty().endsWith("En-uk-hello.ogg"))
    }

    @Test fun mediaRedirectsAreNeverFollowed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/w/api.php")) MockResponse().setBody(serverPayload())
                else MockResponse().setResponseCode(302).setHeader("Location", "https://example.test/stolen.ogg")
        }
        assertNull(client().lookup("hello", "UK"))
    }

    @Test fun oversizedAnswersAreRefusedInsteadOfBuffered() = runBlocking {
        serve(media = ByteArray(2 * 1024 * 1024 + 1))
        assertNull("A body past the audio limit must not be kept", client().lookup("hello", "UK"))

        serve(metadata = " ".repeat(512 * 1024 + 1))
        assertNull("A body past the metadata limit must not be kept", client().lookup("hello", "UK"))
    }

    @Test fun aStalledCommonsAnswerIsBoundedAndCancellable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertNull(withTimeoutOrNull(1_000) { client().lookup("hello", "UK") })
    }

    @Test fun phrasesNeverReachTheNetwork() = runBlocking {
        assertNull(client().lookup("A little further, one page at a time.", "US"))
        assertNull(client().lookup("", "UK"))
        assertEquals(0, server.requestCount)
    }

    @Test fun unknownAccentNeverLooksAnythingUp() = runBlocking {
        assertNull(client().lookup("hello", "AU"))
        assertEquals(0, server.requestCount)
    }

    @Test fun failuresAndUnknownWordsFallBackToNoRecording() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(client().lookup("hello", "UK"))

        server.enqueue(MockResponse().setBody(payload))
        assertNull(client().lookup("reluctant", "UK"))

        server.enqueue(MockResponse().setBody("not json"))
        assertNull(client().lookup("hello", "US"))

        server.enqueue(MockResponse().setBody(""))
        assertNull(client().lookup("hello", "US"))
        assertTrue(server.requestCount >= 4)
    }
}
