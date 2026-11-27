package app.peekturn.reader.video

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIconButton
import app.peekturn.reader.ui.PeekturnIcons

internal fun VideoLink.watchUri(): Uri = canonicalUrl.toUri().buildUpon().apply {
    startMs?.takeIf { it >= 1000L }?.let { appendQueryParameter("t", "${it / 1000}s") }
}.build()

internal fun VideoItem.watchUri(startMs: Long = positionMs): Uri =
    VideoLink(videoId, startMs.takeUnless { watchState == VideoWatchState.COMPLETED }).watchUri()

/** Android opens the user's YouTube app or browser with its own sign-in session. */
internal fun openYouTubePage(context: Context, uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
        return true
    } catch (_: ActivityNotFoundException) {
        // The device has no available link handler.
    } catch (_: SecurityException) {
        // The installed handler is blocked for this user.
    }
    Toast.makeText(context, R.string.video_ui_no_youtube_handler, Toast.LENGTH_LONG).show()
    return false
}

@Composable
internal fun rememberOpenYouTubeVideo(): (VideoItem) -> Unit {
    val context = LocalContext.current
    return remember(context) { { video -> openYouTubePage(context, video.watchUri()) } }
}

@Composable
internal fun YouTubeSourceButton(item: VideoItem, onOpen: (VideoItem) -> Unit = rememberOpenYouTubeVideo()) {
    YouTubeSourceButton(item.title) { onOpen(item) }
}

@Composable
internal fun YouTubeSourceButton(title: String, onClick: () -> Unit) {
    PeekturnIconButton(
        icon = PeekturnIcons.ExternalLink,
        description = stringResource(R.string.video_ui_open_source, title),
        tooltipText = stringResource(R.string.video_ui_open_youtube),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    )
}
