package app.peekturn.reader.reader

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class SelectionSessionTest {
    private val word = SelectionSession.Identity("reluctant", "was ", " to leave")
    private val phrase = SelectionSession.Identity("reluctant to leave", "was ", " the house")

    @Test fun `holding and dragging a first selection explains nothing while the finger is down`() {
        val session = SelectionSession()
        session.beginNativeMode()
        session.holdStart()
        val gesture = session.generation
        // Android shows the toolbar as soon as the first word is selected, mid-gesture.
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
        // The words dragged through afterwards must not start a lookup either.
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
        session.holdEnd()
        // Release owns the final native range, after the native selection settled on it.
        session.releasedSelection(gesture, phrase)
        assertTrue(session.mayAutoExplain(gesture, enabled = true, phrase))
        session.lookupOpened(gesture, phrase)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
        // The word the gesture started on is not the released range, so it can never be explained.
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
    }

    @Test fun `handle dragging inside one held gesture never explains and never reopens the old highlight`() {
        val session = SelectionSession()
        val gesture = session.beginNativeMode()
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(gesture, word)
        assertTrue(session.mayAutoExplain(gesture, enabled = true, word))
        session.lookupOpened(gesture, word)
        // The user taps Adjust (the popup closes, the session stands), then drags the end handle.
        session.holdStart()
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
        session.holdEnd()
        // Releasing without a real change keeps the old highlight closed.
        session.releasedSelection(gesture, word)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
        // A genuinely revised released selection may explain once.
        session.releasedSelection(gesture, phrase)
        assertTrue(session.mayAutoExplain(gesture, enabled = true, phrase))
        session.lookupOpened(gesture, phrase)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
    }

    @Test fun `Quick Explain disabled never explains automatically but still owns the released range`() {
        val session = SelectionSession()
        val gesture = session.beginNativeMode()
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(gesture, phrase)
        assertFalse(session.mayAutoExplain(gesture, enabled = false, phrase))
        assertEquals(phrase, session.released)
        // The explicit action uses the released range and consumes repetition for that selection.
        session.lookupOpened(gesture, phrase)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
    }

    @Test fun `a release without a usable selection explains nothing and clears the previous capture`() {
        val session = SelectionSession()
        val previous = session.beginNativeMode()
        session.releasedSelection(previous, phrase)
        assertEquals(phrase, session.released)
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(previous, null)
        assertNull(session.released)
        assertFalse(session.mayAutoExplain(previous, enabled = true, phrase))
        assertFalse(session.mayAutoExplain(previous, enabled = true, text()))
    }

    @Test fun `an obsolete native read cannot explain or overwrite the newer session`() {
        val session = SelectionSession()
        val obsolete = session.beginNativeMode()
        session.clear()
        val current = session.beginNativeMode()
        session.releasedSelection(obsolete, word)
        session.lookupOpened(obsolete, word)
        assertNull(session.released)
        assertFalse(session.mayAutoExplain(obsolete, enabled = true, word))
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(current, phrase)
        assertTrue(session.mayAutoExplain(current, enabled = true, phrase))
    }

    @Test fun `a read started before the next gesture cannot explain after that gesture started`() {
        val session = SelectionSession()
        val released = session.beginNativeMode()
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(released, word)
        // The user presses down again before the asynchronous released read resolved.
        session.holdStart()
        assertFalse(session.mayAutoExplain(released, enabled = true, word))
        session.holdEnd()
        assertTrue(session.mayAutoExplain(released, enabled = true, word))
    }

    @Test fun `an explicit lookup keeps working after Adjust and a new selection re-arms automatic explanation`() {
        val session = SelectionSession()
        val first = session.beginNativeMode()
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(first, word)
        session.lookupOpened(first, word)
        // Adjust keeps the native selection and the same session.
        assertTrue(session.hasNativeSelection)
        assertTrue(session.isCurrent(first))
        session.lookupOpened(first, word)
        assertFalse(session.mayAutoExplain(first, enabled = true, word))
        session.clear()
        val second = session.beginNativeMode()
        session.holdStart()
        session.holdEnd()
        session.releasedSelection(second, phrase)
        assertTrue(session.mayAutoExplain(second, enabled = true, phrase))
    }

    @Test fun `a location change during a held selection gesture does not clear the native selection`() {
        val session = SelectionSession()
        val gesture = session.beginNativeMode()
        // Not held: a moved location may dismiss the session's selection as before.
        assertTrue(session.mayClearForLocationChange())
        session.holdStart()
        // The gesture is still selecting: Readium's debounced location must not clear it.
        assertFalse(session.mayClearForLocationChange())
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
        session.holdEnd()
        session.releasedSelection(gesture, word)
        assertTrue(session.mayClearForLocationChange())
        assertTrue(session.mayAutoExplain(gesture, enabled = true, word))
    }

    @Test fun `a probe after pointer down cannot end the gesture before the long press selects`() {
        val session = SelectionSession()
        // Activity sequence on this device: ACTION_DOWN -> holdStart -> source-input probe -> nothing
        // is selected yet -> the probe tries to clear -> the native toolbar appears while still held.
        session.holdStart()
        assertFalse(session.mayClearForSourceInput())
        val gesture = session.beginNativeMode()
        // The toolbar of the held gesture must never look like a release.
        assertTrue(session.pointerHeld)
        assertFalse(session.hasObservedRelease)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
        // Release owns the final range.
        session.holdEnd()
        session.releasedSelection(gesture, phrase)
        assertTrue(session.mayAutoExplain(gesture, enabled = true, phrase))
        assertFalse(session.mayAutoExplain(gesture, enabled = true, word))
    }

    @Test fun `ending a selection session does not end the physical gesture`() {
        val session = SelectionSession()
        session.holdStart()
        val gesture = session.beginNativeMode()
        // A UI action while the finger is elsewhere must not make a held gesture look released.
        session.clear()
        assertTrue(session.pointerHeld)
        assertFalse(session.hasObservedRelease)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
        session.holdEnd()
        assertFalse(session.pointerHeld)
        assertTrue(session.hasObservedRelease)
    }

    @Test fun `a native toolbar request before any observed release never explains`() {
        val session = SelectionSession()
        val gesture = session.beginNativeMode()
        // No pointer events were observed for this gesture (a synthetic or service-driven selection).
        session.releasedSelection(gesture, phrase)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
        // Once the app observes the pointer leaving, that same released range may explain.
        session.holdStart()
        session.holdEnd()
        assertTrue(session.mayAutoExplain(gesture, enabled = true, phrase))
    }

    @Test fun `only the reader lifecycle cancels a held gesture`() {
        val session = SelectionSession()
        session.holdStart()
        val gesture = session.beginNativeMode()
        // A selection clear or a session end must not touch the physical pointer.
        session.clear()
        assertTrue(session.pointerHeld)
        // The deliberate cancellation path is the only other way a gesture ends.
        session.cancelGesture()
        assertFalse(session.pointerHeld)
        assertFalse(session.hasObservedRelease)
        assertFalse(session.mayAutoExplain(gesture, enabled = true, phrase))
    }

    @Test fun `agreeing reads confirm a released range even when an earlier read differed`() {
        val reads = ReleasedSelectionReads(budget = 3)
        assertNull(reads.accept(word))
        assertNull(reads.accept(phrase))
        // A, B, B: the last two agreeing reads are the final native range, so this is not unconfirmed.
        assertEquals(ReleasedSelectionReads.Outcome.Confirmed, reads.accept(phrase))
    }

    @Test fun `a native range that never repeats is unconfirmed when the budget ends`() {
        val reads = ReleasedSelectionReads(budget = 3)
        assertNull(reads.accept(word))
        assertNull(reads.accept(phrase))
        assertEquals(ReleasedSelectionReads.Outcome.Unconfirmed, reads.accept(text()))
    }

    @Test fun `two identical reads confirm and a read without a selection ends the sequence`() {
        val reads = ReleasedSelectionReads(budget = 3)
        assertNull(reads.accept(word))
        assertEquals(ReleasedSelectionReads.Outcome.Confirmed, reads.accept(word))
        assertEquals(ReleasedSelectionReads.Outcome.Empty, ReleasedSelectionReads(budget = 3).accept(null))
    }

    @Test fun `padding gestures clear native selection without navigation including after Adjust removes the toolbar`() {
        val session = SelectionSession()
        val normal = session.beginNativeMode()
        assertEquals(ReaderPaddingAction.ClearSelection, session.paddingAction(normal, hasSelection = true))
        session.lookupOpened(normal, word)
        assertEquals(ReaderPaddingAction.ClearSelection, session.paddingAction(session.generation, hasSelection = true))
        session.clear()
        assertEquals(ReaderPaddingAction.Navigate, session.paddingAction(session.generation, hasSelection = false))
    }

    @Test fun `late padding selection read cannot clear or navigate a newer selection`() {
        val session = SelectionSession()
        val gesture = session.generation
        val newerSelection = session.beginNativeMode()
        assertEquals(ReaderPaddingAction.Ignore, session.paddingAction(gesture, hasSelection = false))
        assertEquals(ReaderPaddingAction.Ignore, session.paddingAction(gesture, hasSelection = true))
        assertTrue(session.isCurrent(newerSelection))
    }

    @Test fun `new source gesture invalidates padding read even before native toolbar creation`() {
        val session = SelectionSession()
        val token = session.generation
        assertEquals(ReaderPaddingAction.Ignore, session.paddingAction(token, hasSelection = true, gestureInput = 1, currentInput = 2))
        assertEquals(ReaderPaddingAction.Ignore, session.paddingAction(token, hasSelection = false, gestureInput = 1, currentInput = 2))
        assertEquals(ReaderPaddingAction.Navigate, session.paddingAction(token, hasSelection = false, gestureInput = 2, currentInput = 2))
    }

    /**
     * Android reports a cancellation as well as a release, and a cancellation can arrive while the
     * finger is still down (a system gesture or another window taking the touch). Only the release
     * may explain a range, so a cancelled stream leaves the session without an observed release.
     */
    @Test fun `only a real release makes a range eligible for automatic explanation`() {
        assertEquals(GestureEnd.Release, gestureEnd(MotionEvent.ACTION_UP))
        assertEquals(GestureEnd.Cancelled, gestureEnd(MotionEvent.ACTION_CANCEL))
        assertNull(gestureEnd(MotionEvent.ACTION_MOVE))

        val session = SelectionSession()
        val token = session.beginNativeMode()
        val frozen = SelectionSession.Identity("relucta", "was ", "nt to leave")

        session.holdStart()
        session.releasedSelection(token, frozen)
        assertEquals(GestureEnd.Cancelled, gestureEnd(MotionEvent.ACTION_CANCEL))
        session.cancelGesture()
        assertFalse(session.pointerHeld)
        assertFalse("A cancelled stream must not explain a mid-gesture range", session.mayAutoExplain(token, true, frozen))

        session.holdStart()
        session.releasedSelection(token, frozen)
        session.holdEnd()
        assertTrue("A release the reader saw makes the same range eligible", session.mayAutoExplain(token, true, frozen))
    }

    /** A whitespace-only native selection is never explainable. */
    private fun text() = SelectionSession.Identity("   ", "", "")
}
