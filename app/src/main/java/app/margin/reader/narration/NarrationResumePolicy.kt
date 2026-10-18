package app.margin.reader.narration

/**
 * Per-session resume policy shared by [NarrationPlayback] and the TTS engine.
 *
 * Readium pauses narration by cancelling the current speak request, which calls [TtsEngine.stop]
 * and loses the MediaPlayer. The engine therefore keeps one in-clip checkpoint (sentence text and
 * paused milliseconds) across that stop. [checkpointGeneration] scopes the checkpoint to the
 * current reading intent: any navigation, retry, stop or end increments it, so a checkpoint saved
 * for an old intent can never be replayed into a new one. A plain speed change does not touch it.
 *
 * Lookup ownership is deliberately narrower than the checkpoint. Manual pause/play clears lookup
 * ownership but keeps the checkpoint, because a person resuming narration must still continue the
 * same sentence at the same offset. Only a lookup-scoped pause claimed through
 * [beginLookupPause] can be resumed by [claimLookupResume].
 */
internal class NarrationResumePolicy {
    private var generation = 0L
    private var lookupPause = false

    /** The generation an in-flight clip belongs to when it starts. */
    fun checkpointGeneration(): Long = generation

    /** navigation, retry, stop, completion, a new book: no paused clip may cross this boundary. */
    fun invalidateCheckpoint() {
        generation++
        lookupPause = false
    }

    /** A manual pause or play takes lookup ownership, but must not discard the audio offset. */
    fun clearLookupPause() {
        lookupPause = false
    }

    /**
     * Records lookup ownership only when narration was actually active and playing. A reader that
     * was already paused stays paused and does not become a lookup-owned pause.
     */
    fun beginLookupPause(active: Boolean, playing: Boolean): Boolean {
        if (!active || !playing || lookupPause) return false
        lookupPause = true
        return true
    }

    /** Consumes lookup ownership exactly once; navigation or a manual control clears it first. */
    fun claimLookupResume(): Boolean {
        if (!lookupPause) return false
        lookupPause = false
        return true
    }
}
