package app.peekturn.reader.video

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException

class YouTubeSourceSelectionTest {
    @Test fun `audio only uses a real original audio stream with no video partner`() {
        val source = audioOnlySource(listOf(
            AudioChoice("https://media.example/dub", "audio/mp4", 192_000),
            AudioChoice("https://media.example/original", "audio/mp4", 96_000, original = true)))!!
        assertEquals("https://media.example/original", source.videoUrl)
        assertEquals("audio/mp4", source.videoMimeType)
        assertNull(source.audioUrl)
        assertNull(audioOnlySource(emptyList()))
    }

    @Test
    fun `a muxed 360 and a split 1080 are both offered and 1080 is the default`() {
        val muxed360 = VideoChoice("https://media.example/muxed360", "video/mp4", 360, avc = true)
        val split1080 = VideoChoice("https://media.example/split1080", "video/mp4", 1080, avc = true)
        val split720 = VideoChoice("https://media.example/split720", "video/mp4", 720, avc = true)
        val audio = AudioChoice("https://media.example/audio", "audio/mp4", bitrate = 128_000)
        val renditions = playbackRenditions(
            muxed = listOf(muxed360),
            videoOnly = listOf(split1080, split720),
            audio = listOf(audio)
        )

        assertEquals(listOf("1080p", "720p", "360p"), renditions.map { it.id })
        assertEquals(listOf(1080, 720, 360), renditions.map { it.height })
        val selected = defaultRendition(renditions)
        assertEquals("1080p", selected?.id)
        assertEquals(split1080, selected?.video)
        assertEquals(audio, selected?.audio)
        val muxedRendition = renditions.single { it.height == 360 }
        assertEquals(muxed360, muxedRendition.video)
        assertNull(muxedRendition.audio)
    }

    @Test
    fun `without 1080 the default is 720 and smaller muxed streams remain selectable`() {
        val renditions = playbackRenditions(
            muxed = listOf(
                VideoChoice("https://media.example/720", "video/mp4", 720, avc = true),
                VideoChoice("https://media.example/360", "video/mp4", 360, avc = true)
            ),
            videoOnly = listOf(VideoChoice("https://media.example/split480", "video/mp4", 480, avc = true)),
            audio = listOf(AudioChoice("https://media.example/audio", "audio/mp4"))
        )

        assertEquals("720p", defaultRendition(renditions)?.id)
        assertEquals("https://media.example/720", defaultRendition(renditions)?.video?.url)
        assertEquals(listOf("720p", "480p", "360p"), renditions.map { it.id })
        assertNull(renditions.single { it.id == "720p" }.audio)
    }

    @Test
    fun `a split rendition pairs a real m4a track and never appears without one`() {
        val split = VideoChoice("https://media.example/1080only", "video/mp4", 1080, avc = true)
        assertTrue(
            playbackRenditions(
                muxed = emptyList(),
                videoOnly = listOf(split),
                audio = emptyList()
            ).isEmpty()
        )
        assertTrue(playbackRenditions(emptyList(), listOf(split), emptyList()).isEmpty())
        assertNull(defaultRendition(emptyList()))

        val audio = AudioChoice("https://media.example/audio", "audio/mp4")
        val partnered = playbackRenditions(emptyList(), listOf(split), listOf(audio)).single()
        assertEquals(audio, partnered.audio)
        assertEquals("1080p", partnered.id)
    }

    @Test
    fun `the same height prefers a muxed stream to avoid a second connection`() {
        val muxed = VideoChoice("https://media.example/muxed720", "video/mp4", 720, avc = false)
        val renditions = playbackRenditions(
            muxed = listOf(muxed),
            videoOnly = listOf(VideoChoice("https://media.example/split720", "video/mp4", 720, avc = true)),
            audio = listOf(AudioChoice("https://media.example/audio", "audio/mp4"))
        )

        assertEquals(1, renditions.size)
        assertEquals(muxed, renditions.single().video)
        assertNull(renditions.single().audio)
    }

    @Test
    fun `an avc variant wins its height over an unsupported codec`() {
        val renditions = playbackRenditions(
            muxed = emptyList(),
            videoOnly = listOf(
                VideoChoice("https://media.example/1080av1", "video/mp4", 1080, avc = false),
                VideoChoice("https://media.example/1080avc", "video/mp4", 1080, avc = true)
            ),
            audio = listOf(AudioChoice("https://media.example/audio", "audio/mp4"))
        )

        assertEquals("https://media.example/1080avc", renditions.single().video.url)
    }

    @Test
    fun `an original audio track is preferred over a higher bitrate dub`() {
        val original = AudioChoice("https://media.example/original", "audio/mp4", 96_000, original = true, cacheKey = "original-audio")
        val dubbed = AudioChoice("https://media.example/dub", "audio/mp4", 192_000, original = false)
        val renditions = playbackRenditions(
            muxed = emptyList(),
            videoOnly = listOf(VideoChoice("https://media.example/1080", "video/mp4", 1080, avc = true)),
            audio = listOf(dubbed, original)
        )

        assertEquals(original, renditions.single().audio)
        assertEquals("original-audio", audioOnlySource(listOf(dubbed, original))!!.videoCacheKey)
    }

    @Test
    fun `unknown height is a real fallback and never becomes an invented quality`() {
        val audio = AudioChoice("https://media.example/audio", "audio/mp4")
        val unknown = playbackRenditions(
            muxed = emptyList(),
            videoOnly = listOf(VideoChoice("https://media.example/unknown", "video/mp4", 0)),
            audio = listOf(audio)
        )
        assertEquals("auto", unknown.single().id)
        assertEquals(0, unknown.single().height)
        assertEquals("https://media.example/unknown", defaultRendition(unknown)?.video?.url)
    }

    @Test
    fun `renditions above 1080 stay selectable but never become the default`() {
        val audio = AudioChoice("https://media.example/audio", "audio/mp4")
        val renditions = playbackRenditions(
            muxed = emptyList(),
            videoOnly = listOf(
                VideoChoice("https://media.example/2160", "video/mp4", 2160, avc = true),
                VideoChoice("https://media.example/1440", "video/mp4", 1440, avc = true),
                VideoChoice("https://media.example/1080", "video/mp4", 1080, avc = true)
            ),
            audio = listOf(audio)
        )

        assertEquals(listOf("2160p", "1440p", "1080p"), renditions.map { it.id })
        assertEquals("1080p", defaultRendition(renditions)?.id)

        val onlyTall = playbackRenditions(
            muxed = emptyList(),
            videoOnly = listOf(
                VideoChoice("https://media.example/2160", "video/mp4", 2160, avc = true),
                VideoChoice("https://media.example/1440", "video/mp4", 1440, avc = true)
            ),
            audio = listOf(audio)
        )
        assertEquals(
            "the shortest real option is used rather than a fake 1080p",
            "1440p",
            defaultRendition(onlyTall)?.id
        )
    }

    @Test
    fun `extractor failures map to distinguishable categories`() {
        assertEquals(VideoFailureKind.Network, videoFailureFor(IOException("offline")).kind)
        assertEquals(
            VideoFailureKind.Verification,
            videoFailureFor(ReCaptchaException("challenge", "https://example.invalid")).kind
        )
        assertEquals(
            VideoFailureKind.Verification,
            videoFailureFor(SignInConfirmNotBotException("bot check")).kind
        )
        assertEquals(VideoFailureKind.Restricted, videoFailureFor(PrivateContentException("private")).kind)
        assertEquals(VideoFailureKind.Restricted, videoFailureFor(AgeRestrictedContentException("age")).kind)
        assertEquals(
            VideoFailureKind.Restricted,
            videoFailureFor(GeographicRestrictionException("region")).kind
        )
        assertEquals(VideoFailureKind.Restricted, videoFailureFor(PaidContentException("paid")).kind)
        assertEquals(
            VideoFailureKind.Restricted,
            videoFailureFor(YoutubeMusicPremiumContentException()).kind
        )
        assertEquals(
            VideoFailureKind.Restricted,
            videoFailureFor(UnsupportedContentInCountryException("country")).kind
        )
        assertEquals(
            VideoFailureKind.Unavailable,
            videoFailureFor(AccountTerminatedException("gone")).kind
        )
        assertEquals(
            VideoFailureKind.Unavailable,
            videoFailureFor(ContentNotAvailableException("missing")).kind
        )
        assertEquals(
            VideoFailureKind.Unsupported,
            videoFailureFor(ContentNotSupportedException("live")).kind
        )
    }

    @Test
    fun `media headers follow the client marker without touching the url`() {
        val plain = playbackHeaders("https://media.example/videoplayback?c=ANDROID&itag=18")
        assertTrue(plain.getValue("User-Agent").startsWith("Mozilla/5.0"))
        assertEquals("identity", plain["Accept-Encoding"])
        assertNull(plain["Origin"])

        val web = playbackHeaders("https://media.example/videoplayback?c=WEB&itag=18")
        assertEquals("https://www.youtube.com", web["Origin"])
        assertEquals("https://www.youtube.com", web["Referer"])
        assertEquals("empty", web["Sec-Fetch-Dest"])
    }

    @Test
    fun `only trusted remote urls are fetched`() {
        assertTrue(isRemoteUrl("https://media.example/captions?fmt=vtt"))
        assertTrue(isRemoteUrl("http://media.example/captions"))
        assertFalse(isRemoteUrl("file:///data/local/tmp/captions.vtt"))
        assertFalse(isRemoteUrl("javascript:alert(1)"))
        assertFalse(isRemoteUrl(""))
    }
}
