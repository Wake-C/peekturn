@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.peekturn.reader.reader

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.peekturn.reader.R
import app.peekturn.reader.narration.PreparedAudioStatus
import app.peekturn.reader.narration.PreparedAudioVersion

private enum class ListenPane { LIVE, SAVED }

/** The reader's other sheets lift on the same 24dp top radius over the reading surface. */
private val readerListenSheetShape =
    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * Listen splits one book between live narration and audio prepared ahead of time. Live stays the
 * default because it needs no waiting on a fast voice; the saved pane only lists real versions,
 * with their own voice, sentence counts, size and status. The sheet owns no player: it calls
 * parent callbacks and closes itself before saved playback so the native transport stays visible.
 */
@Composable
internal fun ReaderListenSheet(
    versions: List<PreparedAudioVersion>,
    busy: Boolean,
    narrationActive: Boolean,
    error: String?,
    onLive: () -> Unit,
    onPrepare: () -> Unit,
    onResume: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pane by remember { mutableStateOf(ListenPane.LIVE) }
    var deleting by remember { mutableStateOf<PreparedAudioVersion?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = readerListenSheetShape,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.reader_listen_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        toolbarClose,
                        contentDescription = stringResource(R.string.reader_listen_close),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                ListenTabLabel(
                    stringResource(R.string.reader_listen_tab_live),
                    pane == ListenPane.LIVE,
                    Modifier.weight(1f)
                ) { pane = ListenPane.LIVE }
                ListenTabLabel(
                    stringResource(R.string.reader_listen_tab_saved),
                    pane == ListenPane.SAVED,
                    Modifier.weight(1f)
                ) { pane = ListenPane.SAVED }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val errorText = if (pane == ListenPane.SAVED && error != null) errorMessage(error) else null
            if (errorText != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            when (pane) {
                ListenPane.LIVE -> LivePane(
                    busy = busy,
                    narrationActive = narrationActive,
                    onLive = onLive,
                    onPrepare = { pane = ListenPane.SAVED; onPrepare() }
                )
                ListenPane.SAVED -> SavedPane(
                    versions = versions,
                    busy = busy,
                    narrationActive = narrationActive,
                    onPrepare = onPrepare,
                    onResume = onResume,
                    onPlay = { id ->
                        onDismiss()
                        onPlay(id)
                    },
                    onPause = onPause,
                    onDeleteRequest = { deleting = it }
                )
            }
        }
    }

    deleting?.let { version ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.reader_listen_delete_title)) },
            text = {
                Text(stringResource(R.string.reader_listen_delete_message, version.voiceLabel))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(version.id)
                }) {
                    Text(stringResource(R.string.reader_listen_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.reader_listen_cancel))
                }
            }
        )
    }
}

/**
 * Live narration needs no prepared files. The quiet card states which voice speed benefits from
 * whole-book preparation, so the user can choose without leaving the sheet.
 */
@Composable
private fun LivePane(
    busy: Boolean,
    narrationActive: Boolean,
    onLive: () -> Unit,
    onPrepare: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.reader_listen_live_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.reader_listen_live_slow),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.reader_listen_live_fast),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (narrationActive) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.reader_listen_live_active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.reader_listen_live_busy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onLive,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.reader_listen_start))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPrepare,
            enabled = !busy && !narrationActive,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.reader_listen_prepare))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.reader_listen_prepare_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One real version per card: voice, status, sentence counts, size, then the actions it supports. */
@Composable
private fun SavedPane(
    versions: List<PreparedAudioVersion>,
    busy: Boolean,
    narrationActive: Boolean,
    onPrepare: () -> Unit,
    onResume: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: () -> Unit,
    onDeleteRequest: (PreparedAudioVersion) -> Unit
) {
    if (versions.isEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.reader_listen_saved_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
            )
            OutlinedButton(
                onClick = onPrepare,
                enabled = !busy && !narrationActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.reader_listen_prepare))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reader_listen_prepare_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        versions.forEachIndexed { index, version ->
            SavedVersionCard(
                version = version,
                busy = busy,
                narrationActive = narrationActive,
                onResume = { onResume(version.id) },
                onPlay = { onPlay(version.id) },
                onPause = onPause,
                onDeleteRequest = { onDeleteRequest(version) }
            )
            if (index < versions.lastIndex) Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onPrepare,
            enabled = !busy && !narrationActive,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.reader_listen_prepare_current_voice))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.reader_listen_prepare_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SavedVersionCard(
    version: PreparedAudioVersion,
    busy: Boolean,
    narrationActive: Boolean,
    onResume: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(version.voiceLabel, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                statusLabel(version.status),
                style = MaterialTheme.typography.bodySmall,
                color = if (version.status == PreparedAudioStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            versionMeta(version)?.let { meta ->
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (version.status == PreparedAudioStatus.FAILED) {
                errorMessage(version.error)?.let { reason ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (version.status) {
                    PreparedAudioStatus.READY -> {
                        TextButton(
                            onClick = onDeleteRequest,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.reader_listen_delete))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onPlay,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.reader_listen_play))
                        }
                    }
                    PreparedAudioStatus.PAUSED,
                    PreparedAudioStatus.FAILED -> {
                        TextButton(
                            onClick = onDeleteRequest,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.reader_listen_delete))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onResume,
                            enabled = !busy && !narrationActive,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.reader_listen_resume))
                        }
                    }
                    PreparedAudioStatus.INDEXING,
                    PreparedAudioStatus.PREPARING -> {
                        TextButton(
                            onClick = onPause,
                            enabled = busy,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.reader_listen_pause))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: PreparedAudioStatus): String = stringResource(
    when (status) {
        PreparedAudioStatus.INDEXING -> R.string.reader_listen_status_indexing
        PreparedAudioStatus.PREPARING -> R.string.reader_listen_status_preparing
        PreparedAudioStatus.PAUSED -> R.string.reader_listen_status_paused
        PreparedAudioStatus.READY -> R.string.reader_listen_status_ready
        PreparedAudioStatus.FAILED -> R.string.reader_listen_status_failed
    }
)

/** Fixed machine codes from the store and manager become plain reasons; the code never reaches UI. */
@Composable
private fun errorMessage(error: String?): String? = when (error) {
    null, "already_ready" -> null
    "busy" -> stringResource(R.string.reader_listen_error_busy)
    "narration_active" -> stringResource(R.string.reader_listen_error_narration_active)
    "speech_active" -> stringResource(R.string.reader_listen_error_speech_active)
    "in_use" -> stringResource(R.string.reader_listen_error_in_use)
    "book_missing" -> stringResource(R.string.reader_listen_error_book_missing)
    "book_changed", "index_conflict", "fingerprint_mismatch" ->
        stringResource(R.string.reader_listen_error_book_changed)
    "invalid_version", "invalid_sentence", "not_ready", "ready_immutable" ->
        stringResource(R.string.reader_listen_error_invalid)
    "voice_unsupported" -> stringResource(R.string.reader_listen_error_voice_unsupported)
    "voice_unidentified" -> stringResource(R.string.reader_listen_error_voice_unidentified)
    "voice_changed", "voice_mismatch" -> stringResource(R.string.reader_listen_error_voice)
    "provider_error" -> stringResource(R.string.reader_listen_error_provider)
    "provider_length" -> stringResource(R.string.reader_listen_error_provider_length)
    "provider_audio" -> stringResource(R.string.reader_listen_error_provider_audio)
    "content_unavailable", "content_read_failed", "empty_book", "missing_source" ->
        stringResource(R.string.reader_listen_error_content)
    "storage" -> stringResource(R.string.reader_listen_error_storage)
    "timeout" -> stringResource(R.string.reader_listen_error_timeout)
    "incomplete" -> stringResource(R.string.reader_listen_error_incomplete)
    "interrupted" -> stringResource(R.string.reader_listen_error_interrupted)
    "not_found" -> stringResource(R.string.reader_listen_error_not_found)
    "start_denied" -> stringResource(R.string.reader_listen_error_start_denied)
    else -> stringResource(R.string.reader_listen_error_other)
}

@Composable
private fun versionMeta(version: PreparedAudioVersion): String? {
    val context = LocalContext.current
    val sentences = if (version.total > 0) {
        stringResource(
            R.string.reader_listen_progress,
            version.completed.coerceAtMost(version.total),
            version.total
        )
    } else {
        null
    }
    val size = if (version.bytes > 0) {
        Formatter.formatShortFileSize(context, version.bytes)
    } else {
        null
    }
    return when {
        sentences != null && size != null -> stringResource(
            R.string.reader_listen_meta,
            version.completed.coerceAtMost(version.total),
            version.total,
            size
        )
        sentences != null -> sentences
        else -> size
    }
}

/**
 * Same two-pane indicator as the lookup card: a 13sp label and a short 2dp underline, so this
 * sheet reads as another panel of the reader rather than a new surface style.
 */
@Composable
private fun ListenTabLabel(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth(.56f).height(2.dp).background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(2.dp)
                )
            )
        }
    }
}
