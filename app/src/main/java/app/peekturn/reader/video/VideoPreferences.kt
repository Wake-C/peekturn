package app.peekturn.reader.video

import app.peekturn.reader.model.DEFAULT_VIDEO_QUALITY_HEIGHT

/** A video keeps its explicit mode even when another video or the legacy default changes. */
internal fun preferredAudioOnly(item: VideoItem, legacyDefault: Boolean): Boolean = item.audioOnly ?: legacyDefault

/** Unavailable renditions do not rewrite the user's target height. */
internal fun preferredQuality(qualities: List<VideoQuality>, height: Int): VideoQuality? {
    val selected = preferredVideoHeight(qualities.map { it.height }, height)
    return qualities.firstOrNull { it.height == selected } ?: qualities.firstOrNull()
}

/** Prefer HD for an HD target, even when the next real HD stream is above the target. */
internal fun preferredVideoHeight(heights: List<Int>, preferred: Int): Int? {
    val target = preferred.takeIf { it > 0 } ?: DEFAULT_VIDEO_QUALITY_HEIGHT
    val known = heights.filter { it > 0 }
    val candidates = if (target >= 720) known.filter { it >= 720 }.ifEmpty { known } else known
    return candidates.filter { it <= target }.maxOrNull() ?: candidates.minOrNull()
}

/** Keep a complete local copy for its height; online alternatives have fresh session URLs. */
internal fun mergeVideoQualities(current: List<VideoQuality>, fresh: List<VideoQuality>, playingId: String?): List<VideoQuality> {
    val downloaded = current.filter { it.source.isDownloaded }
    val playing = current.filter { it.id == playingId }
    return (downloaded + fresh + playing).distinctBy { it.id }.sortedByDescending { it.height }
}

internal val VideoPlaybackSource.isDownloaded: Boolean get() = videoUrl.startsWith("peekturn-offline://")
