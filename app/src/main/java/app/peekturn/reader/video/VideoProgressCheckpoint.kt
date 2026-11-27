package app.peekturn.reader.video

/**
 * Only observed playback can replace a durable resume point. Preparation, a displayed clock,
 * buffering and seek targets are not evidence of watching/listening. Uses elapsed wall time,
 * independent of speed or media-clock jumps; pause/buffering intervals never accumulate.
 * Watch history follows actual media-clock advancement, so even a short episode can complete
 * without qualifying for a resume point.
 */
internal class VideoProgressCheckpoint {
    private var videoId: Long? = null
    private var lastObservedAt: Long? = null
    private var wasPlaying = false
    private var playedMs = 0L
    private var acceptedPosition: Long? = null

    var watchState = VideoWatchState.UNWATCHED
        private set
    private var playingPositionMs: Long? = null
    private var playedSinceReposition = false

    fun begin(id: Long?, savedWatchState: VideoWatchState = VideoWatchState.UNWATCHED) {
        videoId = id
        lastObservedAt = null
        wasPlaying = false
        playedMs = 0L
        acceptedPosition = null
        watchState = savedWatchState
        reposition(null)
    }

    fun seek() = begin(videoId, watchState)

    // A seek (including a lock-screen seek) or a source swap starts a new stretch of media.
    fun reposition(playingFromMs: Long?) {
        playingPositionMs = playingFromMs
        playedSinceReposition = false
    }

    fun observe(id: Long, positionMs: Long, elapsedMs: Long, playing: Boolean, endedWhilePlaying: Boolean = false): Long? {
        if (id != videoId) return null
        val from = playingPositionMs
        if (from != null && positionMs > from) {
            playedSinceReposition = true
            if (watchState == VideoWatchState.UNWATCHED) watchState = VideoWatchState.IN_PROGRESS
        }
        playingPositionMs = positionMs.takeIf { playing }
        if (endedWhilePlaying && playedSinceReposition) watchState = VideoWatchState.COMPLETED

        val previous = lastObservedAt
        if (wasPlaying && previous != null) playedMs += (elapsedMs - previous).coerceAtLeast(0L)
        if (playedMs >= MIN_PLAYBACK_MS && (wasPlaying || playing)) {
            acceptedPosition = positionMs.coerceAtLeast(0L)
        }
        wasPlaying = playing
        lastObservedAt = elapsedMs
        return acceptedPosition
    }

    fun watchWriteFailed(id: Long, attempted: VideoWatchState, previous: VideoWatchState) {
        if (videoId == id && watchState == attempted) watchState = previous
    }

    fun position(id: Long): Long? = acceptedPosition.takeIf { id == videoId }

    companion object {
        const val MIN_PLAYBACK_MS = 10_000L
    }
}
