package app.peekturn.reader.video

data class VideoLink(val videoId: String, val startMs: Long? = null) {
    val canonicalUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

data class VideoMetadata(val videoId: String, val title: String, val author: String,
    val durationMs: Long, val thumbnailUrl: String?, val publishedAtMs: Long? = null,
    val publishedAtApproximate: Boolean = false)

enum class PlaylistSortOrder { ORIGINAL, REVERSED, OLDEST, NEWEST }

enum class VideoWatchState(internal val rank: Int) {
    UNWATCHED(0), IN_PROGRESS(1), COMPLETED(2)
}

/** Membership is stored in source order, independent of the chosen presentation. */
data class VideoPlaylist(val id: String, val title: String, val videoIds: List<Long>,
    val thumbnailUrl: String? = null, val sortOrder: PlaylistSortOrder = PlaylistSortOrder.ORIGINAL)

data class VideoItem(val id: Long, val videoId: String, val title: String, val author: String,
    val durationMs: Long, val thumbnailUrl: String?, val positionMs: Long = 0,
    val lastWatchedAt: Long = 0, val selectedTrackId: String? = null,
    val trackExplicit: Boolean = false,
    /** Null inherits the legacy app default; explicit choices belong to this video. */
    val audioOnly: Boolean? = null,
    val readMode: Boolean = false,
    /** Shown on the main shelf as well as in any playlists that contain it. */
    val isStandalone: Boolean = true,
    val aspectRatio: Float? = null,
    val publishedAtMs: Long? = null, val publishedAtApproximate: Boolean = false,
    val watchState: VideoWatchState = VideoWatchState.UNWATCHED)

/** Resolved URLs are session-only and must never be persisted or logged. */
data class CaptionTrack(val id: String, val language: String, val automatic: Boolean, val url: String,
    val translatedFrom: String? = null)

data class VideoPlaybackSource(val videoUrl: String, val videoMimeType: String,
    val audioUrl: String? = null, val audioMimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val videoCacheKey: String? = null, val audioCacheKey: String? = null)

/** Actual session-only rendition; choosing it never changes captions or their timestamps. */
data class VideoQuality(val id: String, val height: Int, val source: VideoPlaybackSource)

data class ResolvedVideo(val metadata: VideoMetadata, val playback: VideoPlaybackSource,
    val tracks: List<CaptionTrack>, val captionFailure: VideoFailureKind? = null,
    val qualities: List<VideoQuality> = emptyList(), val selectedQualityId: String? = null,
    val audioOnly: VideoPlaybackSource? = null)

data class CaptionWord(val text: String, val startMs: Long, val exact: Boolean = true)

data class CaptionCue(val id: Int, val startMs: Long, val endMs: Long, val text: String,
    val words: List<CaptionWord>? = null, val timingApproximate: Boolean = false)

data class CaptionDocument(val videoId: String, val trackId: String, val language: String,
    val automatic: Boolean, val sourceHash: String, val cues: List<CaptionCue>)

data class CaptionGroup(val firstCueId: Int, val lastCueId: Int)

enum class VideoFailureKind {
    InvalidLink, Network, Unavailable, Restricted, Unsupported, Captions, Provider, Storage,
    Verification, SignInRequired
}

class VideoFailure(val kind: VideoFailureKind, cause: Throwable? = null) : Exception(kind.name, cause)

data class VideoSessionData(val item: VideoItem, val resolved: ResolvedVideo,
    val captions: CaptionDocument?, val captionFailure: VideoFailureKind? = null,
    val watchFallbackFailure: VideoFailureKind? = null,
    val reusedOnlineSource: Boolean = false)
