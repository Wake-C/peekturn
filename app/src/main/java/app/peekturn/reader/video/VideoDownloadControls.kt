package app.peekturn.reader.video

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R

@Composable
internal fun videoDownloadLabel(state: VideoDownloadState): String = when (state.phase) {
    VideoDownloadPhase.Complete -> stringResource(if (state.audioOnly) R.string.video_download_audio_saved else R.string.video_download_video_saved)
    VideoDownloadPhase.Failed -> stringResource(R.string.video_download_failed)
    VideoDownloadPhase.Removing -> stringResource(R.string.video_download_removing)
    VideoDownloadPhase.Queued -> stringResource(R.string.video_download_queued)
    VideoDownloadPhase.Downloading -> state.percent?.let { stringResource(R.string.video_download_percent, it) }
        ?: stringResource(R.string.video_download_downloading)
}

@Composable
internal fun VideoDownloadControls(
    state: VideoDownloadState?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadVideo: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.video_download_title), style = MaterialTheme.typography.titleMedium)
        if (state != null) {
            Text(videoDownloadLabel(state), style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == VideoDownloadPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.phase == VideoDownloadPhase.Complete && !state.captionsSaved) {
                Text(stringResource(R.string.video_download_no_captions), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                state == null -> TextButton(onClick = onDownload) { Text(stringResource(R.string.video_download_action)) }
                state.active -> TextButton(onClick = onRemove) { Text(stringResource(R.string.video_download_cancel)) }
                state.phase == VideoDownloadPhase.Failed -> {
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.video_download_retry)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.video_download_remove)) }
                }
                state.phase == VideoDownloadPhase.Complete -> {
                    if (state.audioOnly) TextButton(onClick = onDownloadVideo) { Text(stringResource(R.string.video_download_add_video)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.video_download_remove)) }
                }
            }
        }
        if (state == null) Text(stringResource(R.string.video_download_detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
