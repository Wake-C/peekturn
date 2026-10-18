package app.peekturn.reader.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId

/** Elapsed-time accounting is independent of page count and survives lifecycle flushes. */
class ReadingTracker(private val store: PeekturnStore, private val bookId: Long, private val scope: CoroutineScope) {
    private var foreground = false
    private var accountedAt = 0L
    private var lastInteraction = 0L
    private var remainderMs = 0L
    private var heartbeat: Job? = null
    private var pendingWrite: Job? = null

    fun resume() {
        if (foreground) return
        foreground = true
        accountedAt = SystemClock.elapsedRealtime()
        lastInteraction = accountedAt
        heartbeat = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) { delay(60_000); settle() }
        }
    }
    fun interact() {
        if (!foreground) return
        settle()
        lastInteraction = SystemClock.elapsedRealtime()
    }
    fun pause() {
        if (!foreground) return
        settle()
        foreground = false
        heartbeat?.cancel(); heartbeat = null
    }
    suspend fun flush() { pendingWrite?.join() }

    private fun settle() {
        if (!foreground) return
        val now = SystemClock.elapsedRealtime()
        val delta = activeReadingMillis(accountedAt, lastInteraction, now)
        accountedAt = now
        val total = delta + remainderMs
        val seconds = total / 1000
        remainderMs = total % 1000
        if (seconds <= 0) return
        val endMillis = System.currentTimeMillis() - (now - minOf(now, lastInteraction + IDLE_MS)).coerceAtLeast(0)
        val chunks = splitReadingDays(endMillis - seconds * 1000, seconds, ZoneId.systemDefault())
        val previous = pendingWrite
        pendingWrite = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                chunks.forEach { (date, count) -> store.addReadingTime(bookId, date, count) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Log.w("PeekturnReadingTime", "Reading time write failed: ${e.javaClass.simpleName}") }
        }
    }
    companion object { const val IDLE_MS = 120_000L }
}

internal fun activeReadingMillis(accountedAt: Long, lastInteraction: Long, now: Long): Long =
    (minOf(now, lastInteraction + ReadingTracker.IDLE_MS) - accountedAt).coerceAtLeast(0)

internal fun splitReadingDays(startMillis: Long, seconds: Long, zone: ZoneId): List<Pair<String, Long>> {
    var start = startMillis
    var remaining = seconds
    return buildList {
        while (remaining > 0) {
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val count = minOf(remaining, ((nextMidnight - start) / 1000).coerceAtLeast(1))
            add(date.toString() to count)
            start += count * 1000
            remaining -= count
        }
    }
}
