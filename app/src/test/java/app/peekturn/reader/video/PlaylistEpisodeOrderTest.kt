package app.peekturn.reader.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistEpisodeOrderTest {
    @Test fun newestAndOldestUsePublicationTimeAndKeepStableTiesAndUnknowns() {
        fun episode(id: Long, date: Long?) = VideoItem(id, "video-$id", "Episode $id", "", 0, null,
            lastWatchedAt = 10L - id, publishedAtMs = date)
        val episodes = listOf(episode(1, null), episode(2, 300), episode(3, 100), episode(4, null), episode(5, 100))
        fun ids(order: PlaylistSortOrder) = orderedEpisodes(episodes, order).map { it.value.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(PlaylistSortOrder.ORIGINAL))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ids(PlaylistSortOrder.REVERSED))
        assertEquals(listOf(3L, 5L, 2L, 1L, 4L), ids(PlaylistSortOrder.OLDEST))
        assertEquals(listOf(2L, 3L, 5L, 1L, 4L), ids(PlaylistSortOrder.NEWEST))
        assertEquals(listOf(2, 4, 1, 0, 3), orderedEpisodes(episodes, PlaylistSortOrder.OLDEST).map { it.index })
    }
}
