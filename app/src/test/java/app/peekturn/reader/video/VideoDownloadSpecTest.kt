package app.peekturn.reader.video

import android.app.Application
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoDownloadSpecTest {
    @Test fun durableRequestsContainOnlyIdentityModeAndHeight() {
        for (audio in listOf(true, false)) {
            val spec = VideoDownloadSpec(17, audio, 720)
            assertEquals(spec, VideoDownloadSpec.parse(spec.uri))
            assertEquals("video-17", spec.id)
            assertFalse(spec.uri.toString().contains("http"))
            assertNull(spec.uri.query)
        }
    }

    @Test fun remoteUrlsAndMalformedRequestsCannotBecomeDownloadJobs() {
        listOf("https://example.org/media?signature=secret", "peekturn-download://0/audio/720",
            "peekturn-download://17/audio/-1", "peekturn-download://17/anything/720",
            "peekturn-download://17/audio/720?url=secret", "peekturn-download://17/audio/720#secret",
            "peekturn-download://17/audio/720/extra").forEach { assertNull(VideoDownloadSpec.parse(Uri.parse(it))) }
    }

    @Test fun onlyQueuedAndDownloadingStatesOfferCancellation() {
        VideoDownloadPhase.entries.forEach {
            assertEquals(it == VideoDownloadPhase.Queued || it == VideoDownloadPhase.Downloading,
                VideoDownloadState(it, true).active)
        }
    }
}
