package app.peekturn.reader.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoDownloadControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun existingMenuOffersDownloadWithoutDuplicatingTheOpenAction() {
        var downloads = 0
        var opens = 0
        val item = VideoItem(1, "u4LUix-BU0s", "A real video entry", "Channel", 60_000, null)
        compose.setContent {
            PeekturnTheme { VideoLibraryScreen(listOf(item), { opens++ }, {}, {}, onDownload = { downloads++ }) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.video_ui_video_options, item.title)).performClick()
        compose.onNodeWithText(text(R.string.video_download_action)).performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(0, opens) }
        compose.onNodeWithText(item.title).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun pendingFailedAndCompletedDownloadsExposeOnlyRelevantActions() {
        var state by mutableStateOf<VideoDownloadState?>(null)
        var download = 0
        var remove = 0
        var video = 0
        compose.setContent {
            PeekturnTheme {
                Box(Modifier.width(320.dp)) {
                    VideoDownloadControls(state, { download++ }, { remove++ }, { video++ })
                }
            }
        }
        compose.onNodeWithText(text(R.string.video_download_action)).performClick()
        compose.runOnIdle { state = VideoDownloadState(VideoDownloadPhase.Downloading, true, percent = 37) }
        compose.onNodeWithText(context.getString(R.string.video_download_percent, 37)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.video_download_cancel)).performClick()
        compose.runOnIdle { state = VideoDownloadState(VideoDownloadPhase.Failed, true) }
        compose.onNodeWithText(text(R.string.video_download_retry)).performClick()
        compose.runOnIdle { state = VideoDownloadState(VideoDownloadPhase.Complete, true) }
        compose.onNodeWithText(text(R.string.video_download_audio_saved)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.video_download_no_captions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.video_download_add_video)).performClick()
        compose.onNodeWithText(text(R.string.video_download_remove)).performClick()
        compose.runOnIdle { assertEquals(2, download); assertEquals(2, remove); assertEquals(1, video) }
    }
}
