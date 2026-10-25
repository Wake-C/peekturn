package app.peekturn.reader.video

/** A video keeps its explicit mode even when another video or the legacy default changes. */
internal fun preferredAudioOnly(item: VideoItem, legacyDefault: Boolean): Boolean = item.audioOnly ?: legacyDefault

/** Unavailable renditions do not rewrite the user's target height. */
internal fun preferredQuality(qualities: List<VideoQuality>, height: Int): VideoQuality? {
    val known = qualities.filter { it.height > 0 }
    val target = height.takeIf { it > 0 } ?: 1080
    return known.filter { it.height <= target }.maxByOrNull { it.height }
        ?: known.minByOrNull { it.height }
        ?: qualities.firstOrNull()
}
