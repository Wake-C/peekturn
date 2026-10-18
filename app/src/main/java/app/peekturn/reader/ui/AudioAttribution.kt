package app.peekturn.reader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp
import app.peekturn.reader.speech.AudioCredit

/** Shared inline credit for the audio actually playing: its provider and nothing else. */
@Composable
internal fun AudioAttribution(credit: AudioCredit) {
    val context = LocalContext.current
    val links = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        fun link(label: String, url: String) {
            val unbrokenLabel = label.replace(' ', '\u00A0')
            if (url.isBlank()) { append(unbrokenLabel); return }
            withLink(LinkAnnotation.Clickable(url, links) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    Log.w("PeekturnSpeech", "No browser is available for audio credits")
                    Toast.makeText(context, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Log.w("PeekturnSpeech", "Audio credits could not be opened")
                    Toast.makeText(context, "This link could not be opened.", Toast.LENGTH_SHORT).show()
                }
            }) { append(unbrokenLabel) }
        }
        append("Audio: ")
        link(credit.sourceName, credit.sourceUrl)
    }
    Text(text, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
