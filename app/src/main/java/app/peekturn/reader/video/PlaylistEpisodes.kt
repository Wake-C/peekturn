package app.peekturn.reader.video

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Keep the source episode numbers. Equal and unknown dates retain their original relative order. */
internal fun orderedEpisodes(episodes: List<VideoItem>, order: PlaylistSortOrder): List<IndexedValue<VideoItem>> {
    val original = episodes.withIndex().toList()
    return when (order) {
        PlaylistSortOrder.ORIGINAL -> original
        PlaylistSortOrder.REVERSED -> original.asReversed()
        PlaylistSortOrder.OLDEST, PlaylistSortOrder.NEWEST -> {
            val (dated, undated) = original.partition { it.value.publishedAtMs != null }
            val sorted = if (order == PlaylistSortOrder.OLDEST) dated.sortedBy { it.value.publishedAtMs }
                else dated.sortedByDescending { it.value.publishedAtMs }
            sorted + undated
        }
    }
}

@get:StringRes
private val PlaylistSortOrder.label: Int get() = when (this) {
    PlaylistSortOrder.ORIGINAL -> R.string.playlist_order_original
    PlaylistSortOrder.REVERSED -> R.string.playlist_order_reversed
    PlaylistSortOrder.OLDEST -> R.string.playlist_order_oldest
    PlaylistSortOrder.NEWEST -> R.string.playlist_order_newest
}

@get:StringRes
private val VideoWatchState.label: Int get() = when (this) {
    VideoWatchState.UNWATCHED -> R.string.playlist_unwatched
    VideoWatchState.IN_PROGRESS -> R.string.playlist_in_progress
    VideoWatchState.COMPLETED -> R.string.playlist_watched
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeSheet(
    playlist: VideoPlaylist,
    episodes: List<VideoItem>,
    selectedId: Long?,
    onSort: suspend (PlaylistSortOrder) -> Boolean,
    onRefreshDates: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    onOpenSource: (VideoItem) -> Unit = rememberOpenYouTubeVideo(),
    onSelect: (Long) -> Unit
) {
    val order = playlist.sortOrder
    val datedOrder = order == PlaylistSortOrder.OLDEST || order == PlaylistSortOrder.NEWEST
    val ordered = remember(episodes, order) { orderedEpisodes(episodes, order) }
    var changedOrder by rememberSaveable(playlist.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var choosingOrder by remember { mutableStateOf(false) }
    var savingOrder by remember { mutableStateOf(false) }
    var orderSaveFailed by remember { mutableStateOf(false) }
    var refreshing by remember(playlist.id) { mutableStateOf(false) }
    var failed by remember(playlist.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    // One listing refresh serves both artwork and dates when opening a pre-upgrade playlist.
    val legacyAtOpen = remember(playlist.id) {
        playlist.thumbnailUrl == null && episodes.isNotEmpty() && episodes.all { it.publishedAtMs == null }
    }
    LaunchedEffect(playlist.id, legacyAtOpen || datedOrder, retry) {
        failed = false
        if (legacyAtOpen || datedOrder && episodes.any { it.publishedAtMs == null || it.publishedAtApproximate }) {
            refreshing = true
            try { failed = !onRefreshDates(playlist.id) }
            finally { refreshing = false }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneOffset.UTC) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(playlist.title, Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.headlineSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.playlist_library_episodes, episodes.size, episodes.size),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                TextButton(onClick = { choosingOrder = true }, enabled = !savingOrder) {
                    Text(stringResource(order.label))
                    Icon(PeekturnIcons.ChevronDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = choosingOrder, onDismissRequest = { choosingOrder = false }) {
                    PlaylistSortOrder.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(stringResource(choice.label)) },
                            trailingIcon = if (choice == order) ({ Icon(PeekturnIcons.Check, null, Modifier.size(16.dp)) }) else null,
                            onClick = {
                                choosingOrder = false
                                if (choice != order) {
                                    scope.launch {
                                        savingOrder = true
                                        try {
                                            changedOrder = true
                                            orderSaveFailed = !onSort(choice)
                                        } finally { savingOrder = false }
                                    }
                                }
                            })
                    }
                }
            }
        }
        if (orderSaveFailed) Text(stringResource(R.string.playlist_sort_save_failed),
            Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        if (datedOrder) {
            val notice = when {
                refreshing -> R.string.playlist_loading_dates
                failed -> R.string.playlist_date_refresh_failed
                episodes.all { it.publishedAtMs == null } -> R.string.playlist_no_dates
                episodes.any { it.publishedAtMs == null } -> R.string.playlist_undated_last
                else -> null
            }
            if (notice != null) Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(notice), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.video_ui_retry)) }
            }
        }
        val ids = remember(ordered) { ordered.map { it.value.id } }
        key(playlist.id, order, ids) {
            val start = if (changedOrder) 0 else ordered.indexOfFirst { it.value.id == selectedId }.coerceAtLeast(0)
            LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = start),
                contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
                items(ordered, key = { it.value.id }) { (index, episode) ->
                    EpisodeRow(index + 1, episode, episode.id == selectedId, date, onOpenSource) { onSelect(episode.id) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(number: Int, episode: VideoItem, selected: Boolean, date: DateTimeFormatter,
    onOpenSource: (VideoItem) -> Unit, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = if (episode.watchState == VideoWatchState.IN_PROGRESS && episode.positionMs > 0)
        stringResource(R.string.playlist_progress_position, formatClock(episode.positionMs)) else stringResource(episode.watchState.label)
    val progress = stringResource(R.string.playlist_library_episode, number, status)
    val metadata = episode.publishedAtMs?.let { millis ->
        val instant = Instant.ofEpochMilli(millis)
        val published = if (episode.publishedAtApproximate) stringResource(R.string.playlist_approx_year,
            instant.atOffset(ZoneOffset.UTC).year.toString()) else date.format(instant)
        stringResource(R.string.playlist_episode_date_detail, progress, published)
    } ?: progress
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) colors.primaryContainer else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 64.dp).padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(videoThumbnailUrls(episode.videoId, episode.thumbnailUrl), episode.durationMs,
                Modifier.width(80.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (selected) colors.primary else colors.onSurface)
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (selected) Icon(PeekturnIcons.Play, stringResource(R.string.playlist_current_episode),
                Modifier.size(16.dp), tint = colors.primary)
        }
        YouTubeSourceButton(episode, onOpenSource)
    }
}
