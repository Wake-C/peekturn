package app.peekturn.reader.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class VideoPlaylistShelfTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun playlistAndContinueOpenThePlayerAndOnlyStandaloneVideosHaveTheirOwnRows() {
        val first = VideoItem(1, "aaaaaaaaaaa", "Zulu", "Channel", 1000, null, isStandalone = true)
        val second = VideoItem(2, "bbbbbbbbbbb", "Alpha", "Channel", 1000, null,
            lastWatchedAt = 100, isStandalone = false)
        val playlist = VideoPlaylist("PLtest", "English lessons", listOf(first.id, second.id))
        var videos by mutableStateOf(listOf(first, second.copy(lastWatchedAt = 0)))
        var playlists by mutableStateOf(listOf(playlist))
        val openedPlaylists = mutableListOf<Pair<String, Long>>()
        var openedVideo = 0L
        compose.setContent {
            PeekturnTheme {
                VideoLibraryScreen(videos, { openedVideo = it.id }, {}, { error("Deleted a video") },
                    playlists = playlists, onOpenPlaylist = { group, episode -> openedPlaylists += group.id to episode.id },
                    onRemovePlaylist = { id -> playlists = playlists.filter { it.id != id } })
            }
        }
        compose.onNodeWithText(second.title).assertDoesNotExist()
        compose.onNodeWithText(playlist.title).performClick()
        compose.runOnIdle {
            assertEquals(listOf(playlist.id to first.id), openedPlaylists)
            videos = listOf(first, second)
        }
        val entries = compose.onAllNodesWithText(playlist.title).assertCountEquals(2)
        entries[0].performClick()
        entries[1].performClick()
        compose.runOnIdle {
            assertEquals(listOf(playlist.id to first.id, playlist.id to second.id, playlist.id to second.id), openedPlaylists)
        }
        compose.onNodeWithText(first.title).performClick()
        compose.runOnIdle { assertEquals(first.id, openedVideo) }
        compose.onNodeWithContentDescription(context.getString(R.string.video_ui_video_options, first.title)).performClick()
        compose.onNodeWithText(context.getString(R.string.video_ui_remove)).performClick()
        compose.onNodeWithText(context.getString(R.string.playlist_library_remove_single, first.title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_ui_keep)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.playlist_library_options, playlist.title)).performClick()
        compose.onNodeWithText(context.getString(R.string.playlist_library_remove)).performClick()
        compose.onNodeWithText(context.getString(R.string.playlist_library_remove_message, playlist.title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_ui_remove)).performClick()
        compose.onNodeWithText(playlist.title).assertDoesNotExist()
        compose.onNodeWithText(second.title).assertDoesNotExist()
        compose.onNodeWithText(first.title).assertIsDisplayed()
    }

    @Test fun continuePreservesTheLastEntryWhenSeveralEntriesShareAnEpisode() {
        val video = VideoItem(1, "aaaaaaaaaaa", "Shared lesson", "Channel", 1000, null, lastWatchedAt = 100)
        val first = VideoPlaylist("PLone", "A playlist", listOf(video.id))
        val second = VideoPlaylist("PLtwo", "Z playlist", listOf(video.id))
        var lastPlaylistId by mutableStateOf<String?>(second.id)
        var openedPlaylist: String? = null
        var openedVideo = 0L
        compose.setContent {
            PeekturnTheme {
                VideoLibraryScreen(listOf(video), { openedVideo = it.id }, {}, {}, playlists = listOf(first, second),
                    lastPlaylistId = lastPlaylistId, onOpenPlaylist = { playlist, _ -> openedPlaylist = playlist.id })
            }
        }
        compose.onAllNodesWithText(second.title).assertCountEquals(2)[0].performClick()
        compose.runOnIdle {
            assertEquals(second.id, openedPlaylist)
            lastPlaylistId = null
        }
        compose.onAllNodesWithText(video.title).assertCountEquals(2)[0].performClick()
        compose.runOnIdle { assertEquals(video.id, openedVideo) }
    }
}
