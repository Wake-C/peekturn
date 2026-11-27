package app.peekturn.reader.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class PlaylistEpisodesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sortingReordersRowsButKeepsTheEpisodeNumberSelectionAndWatchState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val older = VideoItem(30, "first", "Older lesson", "", 30_000, null, publishedAtMs = 1000L)
        val undated = older.copy(id = 10, videoId = "unknown", title = "Undated lesson", publishedAtMs = null)
        val newer = older.copy(id = 70, videoId = "second", title = "Newer lesson",
            publishedAtMs = 2000L, watchState = VideoWatchState.COMPLETED)
        val episodes = listOf(older, undated, newer)
        var playlist by mutableStateOf(VideoPlaylist("course", "Lessons", episodes.map { it.id }))
        var opened: Long? = null
        compose.setContent {
            PeekturnTheme {
                EpisodeSheet(playlist, episodes, selectedId = older.id,
                    onSort = { order -> playlist = playlist.copy(sortOrder = order); true },
                    onRefreshDates = { true }, onDismiss = {}) { opened = it }
            }
        }
        compose.onNodeWithText(context.getString(R.string.playlist_order_original)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(context.getString(R.string.playlist_order_newest)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(older.title).assertIsSelected()
        fun top(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top(newer.title) < top(older.title))
        assertTrue(top(older.title) < top(undated.title))
        val status = context.getString(R.string.playlist_library_episode, 3, context.getString(R.string.playlist_watched))
        compose.onNodeWithText(status, substring = true).assertExists()
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithText(newer.title).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(newer.id, opened) }
    }
}
