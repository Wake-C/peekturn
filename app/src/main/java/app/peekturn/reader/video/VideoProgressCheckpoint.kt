package app.peekturn.reader.video

/**
 * Only observed playback can replace a durable resume point. Preparation, a displayed clock,
 * buffering and seek targets are not evidence of watching/listening. Uses elapsed wall time,
 * independent of speed or media-clock jumps; pause/buffering intervals never accumulate.
 */
internal class VideoProgressCheckpoint {
    private var videoId: Long? = null
    private var lastObservedAt: Long? = null
    private var wasPlaying = false
    private var playedMs = 0L
    private var acceptedPosition: Long? = null

    fun begin(id: Long?) {
        videoId = id
        lastObservedAt = null
        wasPlaying = false
        playedMs = 0L
        acceptedPosition = null
    }

    fun seek() = begin(videoId)

    fun observe(id: Long, positionMs: Long, elapsedMs: Long, playing: Boolean): Long? {
        if (id != videoId) return null
        val previous = lastObservedAt
        if (wasPlaying && previous != null) playedMs += (elapsedMs - previous).coerceAtLeast(0L)
        if (playedMs >= MIN_PLAYBACK_MS && (wasPlaying || playing)) {
            acceptedPosition = positionMs.coerceAtLeast(0L)
        }
        wasPlaying = playing
        lastObservedAt = elapsedMs
        return acceptedPosition
    }

    fun position(id: Long): Long? = acceptedPosition.takeIf { id == videoId }

    companion object {
        const val MIN_PLAYBACK_MS = 10_000L
    }
}
