package app.peekturn.reader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReadingTrackerLifecycleTest {
    private lateinit var store: PeekturnStore
    private lateinit var scope: CoroutineScope
    private lateinit var tracker: ReadingTracker

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
        store = PeekturnStore(context)
        val book = store.addBook("Reading", "Author", File.createTempFile("tracker-", ".epub", context.filesDir).absolutePath, null)
        tracker = ReadingTracker(store, book.id, scope)
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun backgroundTimeAndDuplicateLifecycleCallbacksDoNotIncreaseReadingTime() = runBlocking {
        tracker.resume()
        advance(30_000)
        tracker.resume()
        tracker.pause()
        tracker.pause()
        tracker.flush()
        assertEquals(30L, totalSeconds())

        advance(300_000)
        tracker.interact()
        tracker.resume()
        advance(5_000)
        tracker.pause()
        tracker.flush()
        assertEquals(35L, totalSeconds())
    }

    @Test fun returningInteractionAfterIdleCountsOnlyTheActiveWindows() = runBlocking {
        tracker.resume()
        advance(300_000)
        tracker.interact()
        tracker.flush()
        assertEquals(120L, totalSeconds())

        advance(5_000)
        tracker.pause()
        tracker.flush()
        assertEquals(125L, totalSeconds())
    }

    @Test fun subsecondForegroundWindowsAccumulateWithoutCountingTheBackgroundGap() = runBlocking {
        tracker.resume()
        advance(600)
        tracker.pause()
        tracker.flush()
        assertEquals(0L, totalSeconds())

        advance(300_000)
        tracker.resume()
        advance(400)
        tracker.pause()
        tracker.flush()
        assertEquals(1L, totalSeconds())
    }

    private fun advance(milliseconds: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))
    private fun totalSeconds() = store.stats.value.days.sumOf { it.seconds }
}
