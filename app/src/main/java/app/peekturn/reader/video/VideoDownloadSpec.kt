package app.peekturn.reader.video

import android.net.Uri

/** Media3 persists this public identity and intent, never an expiring media URL. */
internal data class VideoDownloadSpec(val itemId: Long, val audioOnly: Boolean, val height: Int) {
    val id: String get() = "video-$itemId"
    val uri: Uri get() = Uri.parse("peekturn-download://$itemId/${if (audioOnly) "audio" else "video"}/$height")

    companion object {
        fun parse(uri: Uri): VideoDownloadSpec? {
            if (uri.scheme != "peekturn-download" || uri.query != null || uri.fragment != null) return null
            val id = uri.host?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val path = uri.pathSegments
            if (path.size != 2 || path[0] !in listOf("audio", "video")) return null
            val height = path[1].toIntOrNull()?.takeIf { it in 0..8640 } ?: return null
            return VideoDownloadSpec(id, path[0] == "audio", height)
        }
    }
}

internal enum class VideoDownloadPhase { Queued, Downloading, Complete, Failed, Removing }

internal data class VideoDownloadState(
    val phase: VideoDownloadPhase,
    val audioOnly: Boolean,
    val percent: Int? = null,
    val bytes: Long = 0,
    val captionsSaved: Boolean = false
) {
    val active: Boolean get() = phase in listOf(VideoDownloadPhase.Queued, VideoDownloadPhase.Downloading)
}
