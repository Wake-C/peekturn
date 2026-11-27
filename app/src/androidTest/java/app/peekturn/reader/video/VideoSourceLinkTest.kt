package app.peekturn.reader.video

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.model.ReadingStats
import app.peekturn.reader.model.VideoReadingTime
import app.peekturn.reader.ui.PeekturnTheme
import app.peekturn.reader.ui.StatisticsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoSourceLinkTest {
    @get:Rule val compose = createComposeRule()

    private val standalone = VideoItem(1, "jNQXAC9IVRw", "A visit to the zoo", "Channel", 19_000, null,
        positionMs = 18_000, lastWatchedAt = 10, watchState = VideoWatchState.COMPLETED)
    private val first = VideoItem(2, "u4LUix-BU0s", "First episode", "Channel", 180_000, null, isStandalone = false)
    private val latest = first.copy(id = 3, videoId = "SZhUit28l9o", title = "Second episode",
        positionMs = 65_800, lastWatchedAt = 20, watchState = VideoWatchState.IN_PROGRESS)
    private val playlist = VideoPlaylist("PL0123456789", "Two episodes", listOf(first.id, latest.id))

    @Test fun libraryLinksOpenTheStandaloneVideoPlaylistAndResumeEpisodeIndependently() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            PeekturnTheme {
                VideoLibraryScreen(listOf(standalone, first, latest), onOpen = { opened += it.id },
                    onImport = {}, onDelete = {}, playlists = listOf(playlist),
                    onOpenPlaylist = { _, episode -> opened += episode.id })
            }
        }

        tapSource(latest.title, "https://www.youtube.com/watch?v=SZhUit28l9o&t=65s")
        tapSource(playlist.title, "https://www.youtube.com/playlist?list=PL0123456789")
        tapSource(standalone.title, "https://www.youtube.com/watch?v=jNQXAC9IVRw")
        compose.onNodeWithText(standalone.title).performClick()
        compose.runOnIdle { assertEquals(listOf(standalone.id), opened) }
    }

    @Test fun anEpisodeSourceOpensThatVideoWithoutSelectingItOrClosingTheSheet() {
        val selections = mutableListOf<Long>()
        var dismissed = false
        compose.setContent {
            PeekturnTheme {
                EpisodeSheet(playlist, listOf(first, latest), selectedId = latest.id,
                    onSort = { true }, onRefreshDates = { true }, onDismiss = { dismissed = true }) { selections += it }
            }
        }

        tapSource(first.title, "https://www.youtube.com/watch?v=u4LUix-BU0s")
        compose.onNodeWithText(latest.title).assertIsSelected()
        compose.onNodeWithText(first.title).performClick()
        compose.runOnIdle {
            assertEquals(listOf(first.id), selections)
            assertFalse(dismissed)
        }
    }

    @Test fun recentlyWatchedVideoDistinguishesOpeningTheSourceFromPlayingLocally() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            PeekturnTheme {
                StatisticsScreen(ReadingStats(videos = listOf(VideoReadingTime(latest.id, 65))),
                    books = emptyList(), videos = listOf(latest), onOpen = {}, onOpenVideo = { opened += it.id })
            }
        }

        tapSource(latest.title, "https://www.youtube.com/watch?v=SZhUit28l9o&t=65s")
        compose.onNodeWithText(latest.title).performClick()
        compose.runOnIdle { assertEquals(listOf(latest.id), opened) }
    }

    private fun tapSource(title: String, expectedUrl: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = instrumentation.targetContext.getString(R.string.video_ui_open_source, title)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(label))
        val intents = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                intents += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithContentDescription(label).assertHasClickAction().assertIsDisplayed()
                .performTouchInput { click() }
            compose.runOnIdle {
                val intent = intents.single()
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
                assertNull(intent.component)
                assertEquals(expectedUrl, intent.dataString)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
