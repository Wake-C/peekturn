package app.peekturn.reader.speech

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R

/**
 * What the speech routes answered when they could not speak, in the reader's own words: shown only
 * with a failure, next to it, and copyable, because a reader who sends this back is the only way to
 * settle what their route actually reports.
 */
@Composable
fun SpeechReport(report: String) {
    val context = LocalContext.current
    var copied by remember(report) { mutableStateOf(false) }
    // Resolved in composition so the label follows the interface language.
    val clipLabel = stringResource(R.string.speech_report_clip_label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(clipLabel, report))
            copied = true
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (copied) R.string.speech_report_copied else R.string.speech_report_copy_details))
        }
    }
}
