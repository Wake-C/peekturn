package app.peekturn.reader.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.peekturn.reader.R
import app.peekturn.reader.narration.NarrationState
import java.math.BigDecimal
import java.math.RoundingMode

internal val narrationSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

internal fun nextNarrationSpeed(current: Float): Float {
    val index = narrationSpeeds.indexOfFirst { it == current }
    return narrationSpeeds[(if (index < 0) 1 else index + 1) % narrationSpeeds.size]
}

internal fun narrationSpeedLabel(speed: Float): String =
    BigDecimal(speed.toDouble()).setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()

/**
 * The floating transport card above the reading menu. It is an overlay: its presence, error row
 * or cached label never resize the publication. Every control keeps an independent touch target
 * so the book around the card receives its original taps and swipes.
 */
@Composable
internal fun ReaderNarrationCard(
    state: NarrationState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNextParagraph: () -> Unit,
    onSpeed: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        when {
                            state.buffering -> R.string.reader_narration_status_preparing
                            state.playing -> R.string.reader_narration_status_playing
                            else -> R.string.reader_narration_status_paused
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                if (state.cached && state.error == null) {
                    Text(
                        stringResource(R.string.reader_narration_cached),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (state.active) {
                Text(
                    if (state.savedAudio) stringResource(R.string.reader_narration_saved_source) else stringResource(
                        R.string.reader_narration_reserve,
                        state.readyParagraphs,
                        (state.readyDurationMs / 1_000L).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            if (state.notice != null && state.error == null) {
                Text(
                    state.notice,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            if (state.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                        )
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.reader_narration_retry))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(42.dp)) {
                    Icon(
                        readerTransportPrevious,
                        contentDescription = stringResource(R.string.reader_narration_previous),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(42.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(42.dp)) {
                        Icon(
                            if (state.playing) readerTransportPause else readerTransportPlay,
                            contentDescription = stringResource(
                                if (state.playing) R.string.reader_narration_pause
                                else R.string.reader_narration_play
                            ),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(42.dp)) {
                    Icon(
                        readerTransportNext,
                        contentDescription = stringResource(R.string.reader_narration_next),
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onNextParagraph, modifier = Modifier.size(42.dp)) {
                    Icon(
                        readerTransportNextParagraph,
                        contentDescription = stringResource(R.string.reader_narration_next_paragraph),
                        modifier = Modifier.size(22.dp)
                    )
                }
                TextButton(onClick = onSpeed, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(
                        stringResource(
                            R.string.reader_narration_speed_value,
                            narrationSpeedLabel(state.speed)
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onStop, modifier = Modifier.size(42.dp)) {
                    Icon(
                        toolbarClose,
                        contentDescription = stringResource(R.string.reader_narration_stop),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
