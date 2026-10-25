package app.peekturn.reader.ui

import app.peekturn.reader.model.ReadingStats
import app.peekturn.reader.model.VideoReadingTime
import app.peekturn.reader.video.VideoItem
import org.junit.Assert.*
import org.junit.Test

class StatisticsVideoTest {
    @Test fun videoSecondsAreSummedPerItemAndIgnoreNegativeRecords() {
        val stats = ReadingStats(videos = listOf(
            VideoReadingTime(1, 40), VideoReadingTime(1, 20), VideoReadingTime(2, -5), VideoReadingTime(3, 0)
        ))

        assertEquals(mapOf(1L to 60L, 2L to 0L, 3L to 0L), videoSeconds(stats))
    }

    @Test fun onlyVideosWithLearningTimeAreListedMostRecentFirstWithADeterministicTieBreak() {
        val videos = listOf(
            VideoItem(1, "a", "First", "Channel", 60_000, null, lastWatchedAt = 100),
            VideoItem(2, "b", "Second", "Channel", 60_000, null, lastWatchedAt = 300),
            VideoItem(3, "c", "Third", "Channel", 60_000, null, lastWatchedAt = 300),
            VideoItem(4, "d", "Never watched", "Channel", 60_000, null, lastWatchedAt = 900)
        )
        val totals = mapOf(1L to 60L, 2L to 30L, 3L to 10L, 4L to 0L)

        assertEquals(listOf(3L, 2L, 1L), recentlyWatchedVideos(videos, totals).map { it.id })
    }

}
