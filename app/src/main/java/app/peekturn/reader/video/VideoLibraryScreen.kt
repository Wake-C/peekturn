package app.peekturn.reader.video

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A's compact video shelf: one honest continue row, the whole library and one import action. No
 * demo rows are ever created here; every title and position comes from the stored video entry.
 */
@Composable
internal fun VideoLibraryScreen(
    items: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    onImport: () -> Unit,
    onDelete: (VideoItem) -> Unit,
    downloads: Map<Long, VideoDownloadState> = emptyMap(),
    onDownload: (VideoItem) -> Unit = {},
    onRemoveDownload: (VideoItem) -> Unit = {},
    onDownloadVideo: (VideoItem) -> Unit = {}
) {
    val ordered = remember(items) {
        items.sortedWith(compareByDescending<VideoItem> { it.lastWatchedAt }.thenBy { it.title.lowercase() })
    }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val focus = ordered.firstOrNull { it.lastWatchedAt > 0L }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 48.dp)
    ) {
        if (ordered.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.video_ui_empty_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.video_ui_empty_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VideoImportButton(onImport)
                }
            }
        } else {
            if (focus != null) {
                item(key = "continue-${focus.id}") {
                    ContinueRow(focus, onOpen)
                    Spacer(Modifier.height(20.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.video_ui_all_videos),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.video_ui_video_count, ordered.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    VideoImportButton(onImport)
                }
            }
            items(ordered, key = { it.id }) { item ->
                VideoRow(item, onOpen = { onOpen(item) }, onRemove = { deletingId = item.id },
                    download = downloads[item.id], onDownload = { onDownload(item) },
                    onRemoveDownload = { onRemoveDownload(item) }, onDownloadVideo = { onDownloadVideo(item) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    ordered.firstOrNull { it.id == deletingId }?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.video_ui_remove_video_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.video_ui_remove_video_message, item.title),
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deletingId = null; onDelete(item) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.video_ui_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.video_ui_keep))
                }
            }
        )
    }
}

@Composable
private fun VideoImportButton(onImport: () -> Unit) {
    IconButton(onClick = onImport, modifier = Modifier.size(48.dp)) {
        Icon(PeekturnIcons.Add, contentDescription = stringResource(R.string.video_ui_import_video),
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ContinueRow(item: VideoItem, onOpen: (VideoItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.video_ui_open_video, item.title)) {
                onOpen(item)
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoThumbnail(
            url = item.thumbnailUrl,
            durationMs = item.durationMs,
            modifier = Modifier.width(112.dp).aspectRatio(16f / 9f)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 25.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.author.ifBlank { stringResource(R.string.video_ui_unknown_channel) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ProgressLine(item)
        }
    }
}

@Composable
private fun VideoRow(item: VideoItem, onOpen: () -> Unit, onRemove: () -> Unit,
    download: VideoDownloadState?, onDownload: () -> Unit, onRemoveDownload: () -> Unit, onDownloadVideo: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.video_ui_open_video, item.title)) {
                    onOpen()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumbnail(
                url = item.thumbnailUrl,
                durationMs = item.durationMs,
                modifier = Modifier.width(96.dp).aspectRatio(16f / 9f)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.author.ifBlank { stringResource(R.string.video_ui_unknown_channel) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    positionText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (download != null) Text(videoDownloadLabel(download), style = MaterialTheme.typography.bodySmall,
                    color = if (download.phase == VideoDownloadPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        VideoOptions(item, onRemove, download, onDownload, onRemoveDownload, onDownloadVideo)
    }
}

@Composable
private fun ProgressLine(item: VideoItem) {
    val fraction = if (item.durationMs > 0L) {
        (item.positionMs.toDouble() / item.durationMs).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    Column {
        Box(
            Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                Modifier.fillMaxWidth(fraction).height(2.dp).background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            positionText(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun positionText(item: VideoItem): String {
    if (item.positionMs <= 0L) return stringResource(R.string.video_ui_not_started)
    if (item.durationMs <= 0L) return formatClock(item.positionMs)
    return stringResource(
        R.string.video_ui_watched_position,
        formatClock(item.positionMs),
        formatClock(item.durationMs)
    )
}

@Composable
private fun VideoOptions(item: VideoItem, onRemove: () -> Unit,
    download: VideoDownloadState?, onDownload: () -> Unit, onRemoveDownload: () -> Unit, onDownloadVideo: () -> Unit) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(
                PeekturnIcons.More,
                contentDescription = stringResource(R.string.video_ui_video_options, item.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (download == null || download.phase == VideoDownloadPhase.Failed) DropdownMenuItem(
                text = { Text(stringResource(if (download == null) R.string.video_download_action else R.string.video_download_retry)) },
                onClick = { expanded = false; onDownload() })
            if (download?.phase == VideoDownloadPhase.Complete && download.audioOnly) DropdownMenuItem(
                text = { Text(stringResource(R.string.video_download_add_video)) },
                onClick = { expanded = false; onDownloadVideo() })
            if (download != null && download.phase != VideoDownloadPhase.Removing) DropdownMenuItem(
                text = { Text(stringResource(if (download.active) R.string.video_download_cancel else R.string.video_download_remove)) },
                onClick = { expanded = false; onRemoveDownload() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_ui_remove)) },
                onClick = { expanded = false; onRemove() }
            )
        }
    }
}

@Composable
private fun VideoThumbnail(url: String?, durationMs: Long, modifier: Modifier) {
    val fallback = stringResource(R.string.video_ui_thumbnail)
    val cached = remember(url) { url?.let { thumbnailCache.get(it) } }
    val image by produceState<ImageBitmap?>(initialValue = cached, key1 = url) {
        if (value != null || url == null) return@produceState
        value = withContext(Dispatchers.IO) { loadThumbnail(url) }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = fallback,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    PeekturnIcons.Play,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (durationMs > 0L) {
            Text(
                formatClock(durationMs),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** In-memory only: thumbnails are never written to disk and are not video media. */
private val thumbnailCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private val thumbnailClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

private fun loadThumbnail(url: String): ImageBitmap? {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    return try {
        val request = Request.Builder().url(url).build()
        thumbnailClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.byteStream().use { BitmapFactory.decodeStream(it) } ?: return null
            val bitmap = bytes.asImageBitmap()
            thumbnailCache.put(url, bitmap)
            bitmap
        }
    } catch (_: Exception) {
        null
    }
}
