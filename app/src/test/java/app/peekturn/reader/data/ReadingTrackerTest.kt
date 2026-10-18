package app.peekturn.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReadingTrackerTest {
    @Test fun idleTimeNeverAccumulatesPastTwoMinutes() {
        assertEquals(120_000, activeReadingMillis(0, 0, 300_000))
        assertEquals(0, activeReadingMillis(120_000, 0, 300_000))
        assertEquals(5_000, activeReadingMillis(300_000, 300_000, 305_000))
    }
    @Test fun backwardClockDoesNotCreateNegativeTime() {
        assertEquals(0, activeReadingMillis(5000, 4000, 3000))
    }
    @Test fun sessionsCrossingMidnightBelongToBothDates() {
        val start = Instant.parse("2026-10-14T23:59:30Z").toEpochMilli()
        assertEquals(listOf("2026-10-14" to 30L, "2026-10-15" to 60L), splitReadingDays(start, 90, ZoneId.of("UTC")))
    }
    @Test fun localDayBoundaryIsNotUtcMidnight() {
        val start = Instant.parse("2026-10-14T15:59:50Z").toEpochMilli()
        assertEquals(listOf("2026-10-14" to 10L, "2026-10-15" to 20L), splitReadingDays(start, 30, ZoneId.of("Asia/Shanghai")))
    }
}
