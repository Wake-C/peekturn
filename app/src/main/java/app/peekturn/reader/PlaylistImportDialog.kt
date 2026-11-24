package app.peekturn.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaylistImportDialog(
    state: PlaylistImport,
    updating: Boolean,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onSave: () -> Unit,
    onOpenVideo: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = when (state) {
                is PlaylistImport.Choose -> stringResource(R.string.playlist_choose)
                is PlaylistImport.Loading -> stringResource(R.string.playlist_loading)
                is PlaylistImport.Saving -> stringResource(R.string.playlist_saving)
                is PlaylistImport.Failed -> stringResource(R.string.playlist_import_title)
                is PlaylistImport.Preview -> state.playlist.title
            }
            Text(title,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        text = {
            when (state) {
                is PlaylistImport.Choose -> Text(stringResource(R.string.playlist_choose_hint))
                is PlaylistImport.Failed -> Text(stringResource(state.messageRes))
                is PlaylistImport.Preview -> {
                    val videos = state.playlist.videos
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(pluralStringResource(R.plurals.playlist_available, videos.size, videos.size))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            videos.take(3).forEach { video ->
                                Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                PlaylistImport.Saving, is PlaylistImport.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            when (state) {
                is PlaylistImport.Choose -> TextButton(onClick = { onOpenVideo(state.url) }) {
                    Text(stringResource(R.string.playlist_this_video))
                }
                is PlaylistImport.Preview -> TextButton(onClick = onSave) {
                    Text(stringResource(if (updating) R.string.playlist_update else R.string.playlist_add))
                }
                is PlaylistImport.Failed -> TextButton(onClick = { onLoad(state.id) }) {
                    Text(stringResource(R.string.video_ui_retry))
                }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is PlaylistImport.Choose -> TextButton(onClick = { onLoad(state.id) }) {
                    Text(stringResource(R.string.playlist_entire))
                }
                PlaylistImport.Saving -> Unit
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) }
            }
        }
    )
}
