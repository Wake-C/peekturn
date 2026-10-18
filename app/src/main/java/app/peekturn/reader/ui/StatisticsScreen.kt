package app.peekturn.reader.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R
import app.peekturn.reader.model.Book
import app.peekturn.reader.model.ReadingStats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal data class ReadingSummary(val today: Long, val week: Long, val total: Long, val daily: List<Pair<LocalDate, Long>>)

/** Local calendar dates, including zero days, with an explicit clock for deterministic callers. */
internal fun readingSummary(stats: ReadingStats, today: LocalDate, period: Int): ReadingSummary {
    val days = mutableMapOf<LocalDate, Long>()
    stats.days.forEach { record ->
        val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
        if (date != null && !date.isAfter(today)) days[date] = (days[date] ?: 0L) + record.seconds.coerceAtLeast(0L)
    }
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return ReadingSummary(
        today = days[today] ?: 0L,
        week = days.filterKeys { !it.isBefore(monday) }.values.sum(),
        total = days.values.sum(),
        daily = (period - 1 downTo 0).map { offset -> today.minusDays(offset.toLong()).let { it to (days[it] ?: 0L) } }
    )
}

internal enum class DurationUnit { Zero, Seconds, Minutes, Hours, HoursMinutes }

internal data class DurationParts(val unit: DurationUnit, val first: Long = 0, val second: Long = 0)

internal fun readingDuration(seconds: Long): DurationParts {
    val value = seconds.coerceAtLeast(0)
    return when {
        value == 0L -> DurationParts(DurationUnit.Zero)
        value < 60 -> DurationParts(DurationUnit.Seconds, value)
        value < 3600 -> DurationParts(DurationUnit.Minutes, value / 60)
        else -> DurationParts(DurationUnit.HoursMinutes, value / 3600, value % 3600 / 60)
    }
}

/**
 * The same durations for the metric columns, where each unit is glued to its numeral ("1h 26m"). Three
 * columns on a phone offer about a hundred dp each, and the spoken "1 hr 26 min" needs about 122dp, so
 * there it can only wrap and orphan its last unit. Prose lines and accessibility labels keep the spoken
 * form; every metric column shows the same compact notation at the same size.
 */
internal fun compactDuration(seconds: Long): DurationParts {
    val value = seconds.coerceAtLeast(0)
    val minutes = value % 3600 / 60
    return when {
        value == 0L -> DurationParts(DurationUnit.Zero)
        value < 60 -> DurationParts(DurationUnit.Seconds, value)
        value < 3600 -> DurationParts(DurationUnit.Minutes, value / 60)
        minutes == 0L -> DurationParts(DurationUnit.Hours, value / 3600)
        else -> DurationParts(DurationUnit.HoursMinutes, value / 3600, minutes)
    }
}

@Composable
private fun readingDurationText(seconds: Long): String {
    val parts = readingDuration(seconds)
    return when (parts.unit) {
        DurationUnit.Zero -> stringResource(R.string.shell_duration_zero)
        DurationUnit.Seconds -> stringResource(R.string.shell_duration_seconds, parts.first)
        DurationUnit.Minutes -> stringResource(R.string.shell_duration_minutes, parts.first)
        DurationUnit.Hours -> stringResource(R.string.shell_duration_hours, parts.first)
        DurationUnit.HoursMinutes -> stringResource(R.string.shell_duration_hours_minutes, parts.first, parts.second)
    }
}

@Composable
private fun compactDurationText(seconds: Long): String {
    val parts = compactDuration(seconds)
    return when (parts.unit) {
        DurationUnit.Zero -> stringResource(R.string.shell_compact_duration_zero)
        DurationUnit.Seconds -> stringResource(R.string.shell_compact_duration_seconds, parts.first)
        DurationUnit.Minutes -> stringResource(R.string.shell_compact_duration_minutes, parts.first)
        DurationUnit.Hours -> stringResource(R.string.shell_compact_duration_hours, parts.first)
        DurationUnit.HoursMinutes -> stringResource(R.string.shell_compact_duration_hours_minutes, parts.first, parts.second)
    }
}

@Composable
fun StatisticsScreen(stats: ReadingStats, books: List<Book>, onOpen: (Book) -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(7) }
    val today by produceState(LocalDate.now()) {
        while (true) { value = LocalDate.now(); delay(30_000) }
    }
    val summary = remember(stats, today, period) { readingSummary(stats, today, period) }
    val totals = remember(stats.books) { stats.books.groupBy { it.bookId }.mapValues { (_, records) -> records.sumOf { it.seconds.coerceAtLeast(0L) } } }
    val recent = remember(books, totals) { books.filter { (totals[it.id] ?: 0L) > 0 }.sortedByDescending { it.lastReadAt } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PeekturnScreenHeader(stringResource(R.string.shell_statistics_title)) }
        item {
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                val horizontal = maxWidth / LocalDensity.current.fontScale >= 300.dp
                if (horizontal) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TimeMetric(R.string.shell_metric_today, summary.today, Modifier.weight(1f), compact = true)
                        TimeMetric(R.string.shell_metric_week, summary.week, Modifier.weight(1f), compact = true,
                            accessibleLabel = R.string.shell_metric_week_description)
                        TimeMetric(R.string.shell_metric_total, summary.total, Modifier.weight(1f), compact = true,
                            accessibleLabel = R.string.shell_metric_total_description)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TimeMetric(R.string.shell_metric_today, summary.today)
                        TimeMetric(R.string.shell_metric_week, summary.week, accessibleLabel = R.string.shell_metric_week_description)
                        TimeMetric(R.string.shell_metric_total, summary.total, accessibleLabel = R.string.shell_metric_total_description)
                    }
                }
            }
            HorizontalDivider()
        }
        item {
            Text(stringResource(R.string.shell_reading_time), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { days ->
                    FilterChip(selected = period == days, onClick = { period = days }, label = { Text(stringResource(R.string.shell_days, days)) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text(stringResource(R.string.shell_reading_time_last_days,
                    readingDurationText(summary.daily.sumOf { it.second }), period),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item { ReadingChart(summary.daily) }
        if (summary.total == 0L) item {
            Text(stringResource(R.string.shell_no_reading_time), style = MaterialTheme.typography.titleMedium)
        }
        if (recent.isNotEmpty()) item { Text(stringResource(R.string.shell_recent_books), style = MaterialTheme.typography.titleLarge) }
        items(recent, key = { it.id }) { book ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.shell_read_book, book.title)) { onOpen(book) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.width(56.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Text(book.author.ifBlank { stringResource(R.string.shell_unknown_author) }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(readingDurationText(totals[book.id] ?: 0L), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TimeMetric(@StringRes label: Int, seconds: Long, modifier: Modifier = Modifier, compact: Boolean = false,
    @StringRes accessibleLabel: Int = label) {
    val duration = readingDurationText(seconds)
    val description = stringResource(R.string.shell_metric_description, stringResource(accessibleLabel), duration)
    Column(modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        // The figure and its unit are one inseparable mark: no wrap, no orphaned "min" on a second line.
        Text(compactDurationText(seconds), maxLines = 1, softWrap = false,
            style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
                .copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun ReadingChart(days: List<Pair<LocalDate, Long>>) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    // Bars carry the app's smallest rectangle corner, the same one the search field is drawn with, so a
    // data mark belongs to the same family as the controls around it.
    val barCorner = MaterialTheme.shapes.extraSmall.topStart
    val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val datePattern = stringResource(R.string.shell_chart_date_pattern)
    val dateFormat = remember(datePattern) { DateTimeFormatter.ofPattern(datePattern) }
    val separator = stringResource(R.string.shell_list_separator)
    val dayDescriptions = ArrayList<String>(days.size)
    for ((day, seconds) in days) {
        dayDescriptions += stringResource(R.string.shell_chart_day, day.format(dateFormat), readingDurationText(seconds))
    }
    val description = stringResource(R.string.shell_chart_description, dayDescriptions.joinToString(separator))
    Column {
        Text(stringResource(R.string.shell_peak_day, readingDurationText(days.maxOfOrNull { it.second } ?: 0L)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(168.dp).padding(vertical = 12.dp).semantics { contentDescription = description }) {
            for (line in 0..2) {
                val y = size.height * line / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val slot = size.width / days.size.coerceAtLeast(1)
            val barWidth = slot * .55f
            val corner = CornerRadius(barCorner.toPx(Size.Zero, this))
            days.forEachIndexed { index, (_, seconds) ->
                if (seconds > 0L) {
                    val height = size.height * (seconds.toDouble() / max).toFloat()
                    drawRoundRect(accent, topLeft = Offset(slot * index + (slot - barWidth) / 2f, size.height - height),
                        size = Size(barWidth, height), cornerRadius = corner)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().first.format(dateFormat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(days.last().first.format(dateFormat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
