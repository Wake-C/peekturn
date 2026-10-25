package app.peekturn.reader.video

import android.os.SystemClock
import android.util.Log
import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.data.ReadingTracker
import app.peekturn.reader.data.splitReadingDays
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Learning time for one video, measured from the real monotonic clock. Media progress is never the
 * clock: a 2x playback of a minute is one real minute and a seek adds nothing. Actual playback
 * counts while the Activity or playback service is active; an explanation requires the Activity.
 * Buffering is reported as not playing and therefore does not accumulate.
 *
 * The controller must report observed state, not UI state: [foreground] from the Activity
 * lifecycle or an actively playing media service, [playing] from the real player callback,
 * [explaining] from the popup, [interact] on
 * genuine user interaction within the popup, and [pause] when the user leaves or playback is lost.
 * Seconds carry across pauses as milliseconds, and a window that crosses midnight is split by the
 * same local-day rule as reading.
 */
class VideoTimeTracker(
    private val store: PeekturnStore,
    private val itemId: Long,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private var foreground = false
    private var playing = false
    private var explaining = false
    private var accountedAt = 0L
    private var lastInteraction = 0L
    private var remainderMs = 0L
    private var heartbeat: Job? = null
    private var pendingWrite: Job? = null

    fun foreground(value: Boolean) {
        if (value == foreground) return
        if (value) {
            foreground = true
            accountedAt = elapsed()
            lastInteraction = accountedAt
            heartbeat = scope.launch(Dispatchers.Main.immediate) {
                while (isActive) {
                    delay(60_000)
                    settle()
                }
            }
        } else {
            settle()
            foreground = false
            // Neither a visible Activity nor active background playback owns this window now.
            playing = false
            explaining = false
            heartbeat?.cancel()
            heartbeat = null
        }
    }

    fun playing(value: Boolean) {
        if (value == playing) return
        settle()
        playing = value
    }

    fun explaining(value: Boolean) {
        if (value == explaining) return
        settle()
        explaining = value
        if (value) lastInteraction = elapsed()
    }

    fun interact() {
        if (!foreground) return
        settle()
        lastInteraction = elapsed()
    }

    /** A final stop: settles the open window and forgets transient playback state. */
    fun pause() {
        settle()
        foreground = false
        playing = false
        explaining = false
        heartbeat?.cancel()
        heartbeat = null
    }

    suspend fun flush() {
        pendingWrite?.join()
    }

    private fun settle() {
        if (!foreground) return
        val now = elapsed()
        val delta = videoActiveMillis(accountedAt, lastInteraction, now, playing, explaining)
        accountedAt = now
        val total = delta + remainderMs
        val seconds = total / 1000
        remainderMs = total % 1000
        if (seconds <= 0) return
        // The counted window ends where the clock stopped counting, so an idle explanation tail is
        // attributed to the right wall-clock instant and therefore to the right local day.
        val countedEnd = if (playing) now else minOf(now, lastInteraction + ReadingTracker.IDLE_MS)
        val endMillis = wallClock() - (now - countedEnd).coerceAtLeast(0L)
        val chunks = splitReadingDays(endMillis - seconds * 1000, seconds, zone)
        val previous = pendingWrite
        pendingWrite = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                for ((date, count) in chunks) store.addVideoTime(itemId, date, count)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("PeekturnVideoTime", "Video time write failed: ${e.javaClass.simpleName}")
            }
        }
    }
}

/**
 * The active milliseconds of one observed window. Playing counts continuously at real time; an
 * explanation alone counts only up to the same idle limit reading uses; the two never sum, because
 * one wall-clock window can only be learning time once.
 */
internal fun videoActiveMillis(
    accountedAt: Long,
    lastInteraction: Long,
    now: Long,
    playing: Boolean,
    explaining: Boolean
): Long {
    if (now <= accountedAt) return 0
    if (playing) return now - accountedAt
    if (!explaining) return 0
    return (minOf(now, lastInteraction + ReadingTracker.IDLE_MS) - accountedAt).coerceAtLeast(0)
}
