package app.peekturn.reader.video

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.model.VideoReadingTime
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoTimeTrackerTest {
    private lateinit var store: PeekturnStore
    private lateinit var scope: CoroutineScope
    private lateinit var item: VideoItem
    private lateinit var tracker: VideoTimeTracker
    private var monotonic = 1_000_000L
    private var wall = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
        store = PeekturnStore(context)
        item = store.upsertVideo(VideoMetadata("video-one", "Video", "Channel", 600_000, null))
        tracker = VideoTimeTracker(store, item.id, scope,
            elapsed = { monotonic }, wallClock = { wall }, zone = ZoneId.of("UTC"))
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun playingCountsRealClockSecondsAndRepeatedTogglesAddNothing() = runBlocking {
        tracker.foreground(true)
        tracker.playing(true)
        advance(30_000)
        repeat(5) {
            tracker.playing(false)
            tracker.playing(true)
        }
        tracker.pause()
        tracker.flush()
        assertEquals(30L, totalSeconds())
    }

    @Test fun bufferingAndBackgroundWindowsAreNotLearningTime() = runBlocking {
        tracker.foreground(true)
        tracker.playing(true)
        advance(5_000)
        tracker.playing(false)
        advance(60_000)
        tracker.playing(true)
        advance(5_000)
        tracker.pause()
        tracker.flush()
        assertEquals(10L, totalSeconds())
    }

    @Test fun leavingTheForegroundStopsTheClockAtTheBoundary() = runBlocking {
        tracker.foreground(true)
        tracker.playing(true)
        advance(10_000)
        tracker.foreground(false)
        advance(600_000)
        tracker.foreground(true)
        tracker.playing(true)
        advance(2_000)
        tracker.pause()
        tracker.flush()
        assertEquals(12L, totalSeconds())
    }

    @Test fun explanationCountsOnlyInsideItsInteractionWindow() = runBlocking {
        tracker.foreground(true)
        tracker.explaining(true)
        advance(300_000)
        tracker.interact()
        tracker.flush()
        assertEquals(120L, totalSeconds())

        advance(10_000)
        tracker.pause()
        tracker.flush()
        assertEquals(130L, totalSeconds())
    }

    @Test fun playingAndExplainingInTheSameWindowCountOnce() = runBlocking {
        tracker.foreground(true)
        tracker.playing(true)
        tracker.explaining(true)
        advance(60_000)
        tracker.pause()
        tracker.flush()
        assertEquals(60L, totalSeconds())
    }

    @Test fun subsecondWindowsCarryTheirRemainderAcrossPauses() = runBlocking {
        repeat(3) {
            tracker.foreground(true)
            tracker.playing(true)
            advance(400)
            tracker.pause()
            tracker.flush()
        }
        tracker.flush()
        assertEquals(1L, totalSeconds())
    }

    @Test fun aWindowCrossingMidnightBelongsToBothLocalDays() = runBlocking {
        wall = Instant.parse("2026-10-14T23:59:30Z").toEpochMilli()
        tracker.foreground(true)
        tracker.playing(true)
        advance(90_000)
        tracker.pause()
        tracker.flush()

        assertEquals(listOf("2026-10-14" to 30L, "2026-10-15" to 60L),
            store.stats.value.days.map { it.date to it.seconds })
    }

    @Test fun videoTimeNeverBecomesBookTime() = runBlocking {
        tracker.foreground(true)
        tracker.playing(true)
        advance(10_000)
        tracker.pause()
        tracker.flush()

        assertTrue(store.stats.value.books.isEmpty())
        assertEquals(listOf(VideoReadingTime(item.id, 10)), store.stats.value.videos)
        assertEquals(10L, store.stats.value.days.single().seconds)
    }

    @Test fun aDeletedEntryStopsAccumulatingTime() = runBlocking {
        store.deleteVideo(item.id)
        tracker.foreground(true)
        tracker.playing(true)
        advance(10_000)
        tracker.pause()
        tracker.flush()

        assertTrue(store.stats.value.videos.isEmpty())
        assertTrue(store.stats.value.days.isEmpty())
    }

    @Test fun repeatedPauseIsIdempotent() = runBlocking {
        tracker.foreground(true)
        tracker.playing(true)
        advance(5_000)
        tracker.pause()
        tracker.pause()
        advance(60_000)
        tracker.flush()
        assertEquals(5L, totalSeconds())
    }

    private fun advance(milliseconds: Long) {
        monotonic += milliseconds
        wall += milliseconds
    }

    private fun totalSeconds(): Long =
        store.stats.value.videos.filter { it.itemId == item.id }.sumOf { it.seconds }
}
