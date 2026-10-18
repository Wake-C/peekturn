package app.peekturn.reader.reader

import android.view.MotionEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.readium.r2.navigator.Selection

/**
 * One reader's selection ownership: the native toolbar lifetime, the physical pointer gesture and
 * every asynchronous native selection read.
 *
 * Android creates the text-selection toolbar while the finger is still on the screen and recreates
 * it during handle dragging, so toolbar creation is not a gesture boundary. Pointer ownership is
 * physical: [holdStart] and [holdEnd] follow the real touch stream, [cancelGesture] is the only
 * deliberate lifecycle reset, and clearing a selection or ending a session must never end a held
 * gesture. An automatic lookup additionally needs a release the app actually observed plus the
 * range a release read confirmed, so a native menu request mid-gesture can neither explain nor
 * interrupt. The identity already explained is remembered so an adjustment never reopens the old
 * highlight.
 */
internal class SelectionSession {
    /** Identity of one native selection. [usable] means it can be explained or looked up. */
    data class Identity(val highlight: String, val before: String, val after: String) {
        val usable: Boolean get() = highlight.isNotBlank()
    }

    var generation: Long = 0L
        private set

    /** True between ACTION_DOWN and ACTION_UP, or until the gesture stream is cancelled. */
    var pointerHeld: Boolean = false
        private set

    /**
     * True once this selection session saw the pointer leave the screen in an ACTION_UP. A native
     * menu request can arrive while the finger is still selecting - and on some input paths the
     * Activity never sees the pointer at all - so an automatic explanation requires a release that
     * was actually observed, never a menu request that merely looks like one. A cancelled gesture
     * stream is not a release, because the finger may still be down.
     */
    var hasObservedRelease: Boolean = false
        private set

    /** True while a native selection of this session exists, even when its toolbar was destroyed. */
    var hasNativeSelection: Boolean = false
        private set

    /** Selection already explained or opened in this session. */
    private var explained: Identity? = null

    /**
     * Selection the last released gesture ended with, or null when that gesture selected nothing.
     * Automatic explanation can only explain this identity, never an earlier state of the gesture.
     */
    var released: Identity? = null
        private set

    fun holdStart() {
        pointerHeld = true
        hasObservedRelease = false
    }

    fun holdEnd() {
        pointerHeld = false
        hasObservedRelease = true
    }

    /** Native toolbar created or recreated. Returns the token owning this session's native reads. */
    fun beginNativeMode(): Long {
        generation++
        hasNativeSelection = true
        return generation
    }

    fun isCurrent(token: Long): Boolean = token == generation

    /**
     * Quick Explain belongs to a released gesture and to the selection that gesture ended with. A
     * held pointer, a pointer the app never observed leaving the screen, an obsolete read, an empty
     * selection, a stale identity from earlier in the same gesture (the word it started on) or an
     * already explained selection can never start one.
     */
    fun mayAutoExplain(token: Long, enabled: Boolean, identity: Identity?): Boolean =
        enabled && isCurrent(token) && !pointerHeld && hasObservedRelease &&
            identity?.usable == true && identity == released && identity != explained

    /** Records what the released gesture ended with; an unusable release clears the previous one. */
    fun releasedSelection(token: Long, identity: Identity?) {
        if (isCurrent(token)) released = identity?.takeIf { it.usable }
    }

    /** Remembers the selection that already opened a lookup, however it was started. */
    fun lookupOpened(token: Long, identity: Identity?) {
        if (isCurrent(token) && identity?.usable == true) explained = identity
    }

    /**
     * The reader stopped owning input: the Activity paused, or Android cancelled the gesture stream
     * because a system gesture or another window took the touch. A cancel is not a release - the
     * finger may still be down - so it never counts as an observed one and cannot explain a range.
     * Only a real ACTION_UP does that; clearing a selection or ending a session never does.
     */
    fun cancelGesture() {
        pointerHeld = false
        hasObservedRelease = false
    }

    /**
     * Source input may only end the selection session once the pointer is up. While a finger is
     * still down, an empty native selection is just a selection that has not been composed yet, not
     * a cleared one.
     */
    fun mayClearForSourceInput(): Boolean = !pointerHeld

    /**
     * Ends the selection session: new reads and new explanations need a new gesture. The physical
     * pointer lifetime is separate, so a held gesture stays held.
     */
    fun clear() {
        generation++
        hasNativeSelection = false
        hasObservedRelease = false
        explained = null
        released = null
    }

    /**
     * A location change invalidates an open lookup, but it must not clear a native selection that a
     * held gesture is still extending: Readium's debounced native location can move while the
     * WebView settles under the drag, and clearing there ends multi-word selection mid-gesture.
     */
    fun mayClearForLocationChange(): Boolean = !(pointerHeld && hasNativeSelection)

    /**
     * Padding-band input shares native selection ownership. A read started before a newer gesture
     * or session can neither clear nor navigate.
     */
    fun paddingAction(
        token: Long,
        hasSelection: Boolean,
        gestureInput: Long = 0L,
        currentInput: Long = 0L
    ): ReaderPaddingAction = when {
        !isCurrent(token) || gestureInput != currentInput -> ReaderPaddingAction.Ignore
        hasSelection -> ReaderPaddingAction.ClearSelection
        else -> ReaderPaddingAction.Navigate
    }
}

internal enum class ReaderPaddingAction { Ignore, ClearSelection, Navigate }

/**
 * Outcome of one bounded read of the live native selection. Losing the read (it timed out, or the
 * reader stopped being current) is a different answer from a readable state that simply has nothing
 * selected, and neither is ever replaced by a remembered range.
 */
internal sealed interface NativeSelectionRead {
    val traceName: String

    /** Repeated reads agreed on this range: the one automatic explanation accepts. */
    data class Confirmed(val selection: Selection) : NativeSelectionRead {
        override val traceName = "confirmed"
    }

    /** The last read only; the native state was still moving, so it is not release-proof. */
    data class Unconfirmed(val selection: Selection) : NativeSelectionRead {
        override val traceName = "unconfirmed"
    }

    data object Empty : NativeSelectionRead {
        override val traceName = "empty"
    }

    data object Unavailable : NativeSelectionRead {
        override val traceName = "unavailable"
    }
}

/** The identity of one native selection, or null when it holds no usable text. */
internal fun Selection.identity(): SelectionSession.Identity? {
    val text = locator.text
    val highlight = text.highlight.orEmpty()
    if (highlight.isBlank()) return null
    return SelectionSession.Identity(highlight, text.before.orEmpty(), text.after.orEmpty())
}

/**
 * Reads the live native selection under a bounded budget: repeated reads that agree confirm a range,
 * a range that keeps moving is unconfirmed once the budget ends, an answer without a selection means
 * nothing is selected, and a read that times out or stopped being current cannot be answered at all.
 * The navigator stays outside so the distinction between these answers is testable.
 */
internal suspend fun readLiveSelection(
    budget: Int,
    timeoutMs: Long,
    settleMs: Long,
    isCurrent: () -> Boolean,
    read: suspend () -> Selection?,
    wait: suspend (Long) -> Unit
): NativeSelectionRead {
    val reads = ReleasedSelectionReads(budget)
    repeat(budget) { attempt ->
        if (!isCurrent()) return NativeSelectionRead.Unavailable
        val selection = try {
            withTimeout(timeoutMs) { read() }
        } catch (_: TimeoutCancellationException) {
            return NativeSelectionRead.Unavailable
        }
        if (!isCurrent()) return NativeSelectionRead.Unavailable
        if (selection == null) return NativeSelectionRead.Empty
        // Agreement is decided right after each read, so the last two agreeing reads confirm the
        // range even when a different value was reported earlier in the sequence.
        when (reads.accept(selection.identity())) {
            ReleasedSelectionReads.Outcome.Confirmed -> return NativeSelectionRead.Confirmed(selection)
            ReleasedSelectionReads.Outcome.Empty -> return NativeSelectionRead.Empty
            ReleasedSelectionReads.Outcome.Unconfirmed -> return NativeSelectionRead.Unconfirmed(selection)
            null -> Unit
        }
        if (attempt < budget - 1) wait(settleMs)
    }
    return NativeSelectionRead.Empty
}

/**
 * How the reader's gesture stream ended. Android reports both a real release and a cancellation:
 * only a release means the finger left the screen, because a cancelled stream (a system gesture or
 * another window taking the touch) can arrive while the finger is still down. An automatic
 * explanation may only be built from [Release].
 */
internal enum class GestureEnd { Release, Cancelled }

internal fun gestureEnd(actionMasked: Int): GestureEnd? = when (actionMasked) {
    MotionEvent.ACTION_UP -> GestureEnd.Release
    MotionEvent.ACTION_CANCEL -> GestureEnd.Cancelled
    else -> null
}



/**
 * The read rule of one released selection, kept pure so it is testable without a navigator. Reads
 * are consumed one at a time: two consecutive reads with the same identity confirm the range, a read
 * reporting nothing selected ends it, and the budget bounds how long a still-moving native range is
 * followed before it is reported as unconfirmed. Agreement is checked right after every read, so a
 * sequence like A, B, B confirms B.
 */
internal class ReleasedSelectionReads(private val budget: Int) {
    enum class Outcome { Confirmed, Unconfirmed, Empty }

    private var previous: SelectionSession.Identity? = null
    private var reads = 0

    /** Consumes one read; null means the caller must keep reading within its budget. */
    fun accept(identity: SelectionSession.Identity?): Outcome? {
        reads++
        if (identity == null) return Outcome.Empty
        if (identity == previous) return Outcome.Confirmed
        previous = identity
        return if (reads >= budget) Outcome.Unconfirmed else null
    }
}
