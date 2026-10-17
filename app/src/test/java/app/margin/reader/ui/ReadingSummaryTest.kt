package app.margin.reader.ui

import app.margin.reader.model.ReadingDay
import app.margin.reader.model.ReadingStats
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSummaryTest {
    @Test fun mondayStartsANewWeekAndDuplicateDaysAggregate() {
        val today = LocalDate.of(2026, 10, 12)
        val summary = readingSummary(ReadingStats(days = listOf(
            ReadingDay("2026-10-11", 180), ReadingDay("2026-10-12", 60), ReadingDay("2026-10-12", 30)
        )), today, 7)
        assertEquals(90L, summary.today)
        assertEquals(90L, summary.week)
        assertEquals(270L, summary.total)
        assertEquals(7, summary.daily.size)
        assertEquals(today.minusDays(6), summary.daily.first().first)
        assertEquals(today to 90L, summary.daily.last())
    }

    @Test fun thirtyDayRangeIncludesZeroDaysAndCrossesYearBoundary() {
        val today = LocalDate.of(2027, 1, 2)
        val summary = readingSummary(ReadingStats(days = listOf(
            ReadingDay("2026-12-28", 60), ReadingDay("2027-01-01", 120), ReadingDay("2026-12-03", 240)
        )), today, 30)
        assertEquals(0L, summary.today)
        assertEquals(180L, summary.week)
        assertEquals(420L, summary.total)
        assertEquals(30, summary.daily.size)
        assertEquals(LocalDate.of(2026, 12, 4), summary.daily.first().first)
        assertEquals(180L, summary.daily.sumOf { it.second })
    }

    @Test fun invalidFutureAndNegativeRecordsDoNotInventReading() {
        val today = LocalDate.of(2026, 10, 15)
        val summary = readingSummary(ReadingStats(days = listOf(
            ReadingDay("not-a-date", 500), ReadingDay("2026-10-16", 100), ReadingDay("2026-10-15", -20)
        )), today, 7)
        assertEquals(0L, summary.total)
        assertEquals(List(7) { 0L }, summary.daily.map { it.second })
    }

    @Test fun durationsNeverRoundSubminuteReadingUpToAMinute() {
        assertEquals(DurationParts(DurationUnit.Zero), readingDuration(0))
        assertEquals(DurationParts(DurationUnit.Seconds, 45), readingDuration(45))
        assertEquals(DurationParts(DurationUnit.Minutes, 1), readingDuration(60))
        assertEquals(DurationParts(DurationUnit.HoursMinutes, 1, 1), readingDuration(3660))
    }

    @Test fun metricDurationsGlueEachUnitToItsFigure() {
        assertEquals(DurationParts(DurationUnit.Zero), compactDuration(0))
        assertEquals(DurationParts(DurationUnit.Seconds, 45), compactDuration(45))
        assertEquals(DurationParts(DurationUnit.Minutes, 51), compactDuration(3060))
        assertEquals(DurationParts(DurationUnit.Hours, 1), compactDuration(3600))
        assertEquals(DurationParts(DurationUnit.HoursMinutes, 1, 26), compactDuration(5160))
        assertEquals(DurationParts(DurationUnit.HoursMinutes, 1, 26), compactDuration(5160 + 59))
    }
}
