package app.margin.reader.reader

import org.junit.Assert.*
import org.junit.Test

class SelectionSessionTest {
    @Test fun `adjusting handles after Quick Explain does not automatically explain expanded phrase`() {
        val session = SelectionSession()
        val selectedWord = session.beginNativeMode()
        assertTrue(session.consumeQuick(selectedWord))
        session.lookupOpened()
        session.adjust()
        // Android destroys/recreates the native toolbar while handles expand the same selection.
        val expandedPhrase = session.beginNativeMode()
        assertFalse(session.consumeQuick(expandedPhrase))
        // Explicit Explain still owns a valid generation after adjustment.
        assertTrue(session.isCurrent(expandedPhrase))
        session.lookupOpened()
        assertFalse(session.consumeQuick(expandedPhrase))
        session.clear()
        val genuinelyNewSelection = session.beginNativeMode()
        assertTrue(session.consumeQuick(genuinelyNewSelection))
    }

    @Test fun `obsolete native selection reads cannot consume a new sessions Quick Explain`() {
        val session = SelectionSession()
        val obsolete = session.beginNativeMode()
        session.clear()
        val current = session.beginNativeMode()
        assertFalse(session.consumeQuick(obsolete))
        assertTrue(session.consumeQuick(current))
        assertFalse(session.consumeQuick(current))
    }

    @Test fun `explicit lookup also consumes automatic explanation for that selection session`() {
        val session = SelectionSession()
        session.beginNativeMode()
        session.lookupOpened()
        val recreatedMode = session.beginNativeMode()
        assertFalse(session.consumeQuick(recreatedMode))
    }

    @Test fun `padding gestures clear native selection without navigation including after Adjust removes the toolbar`() {
        val session = SelectionSession()
        val normal = session.beginNativeMode()
        assertEquals(ReaderPaddingAction.ClearSelection, session.paddingAction(normal, hasSelection = true))
        session.lookupOpened()
        session.adjust()
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
        assertTrue(session.consumeQuick(newerSelection))
    }

    @Test fun `new source gesture invalidates padding read even before native toolbar creation`() {
        val session = SelectionSession()
        val token = session.generation
        assertEquals(ReaderPaddingAction.Ignore, session.paddingAction(token, hasSelection = true, gestureInput = 1, currentInput = 2))
        assertEquals(ReaderPaddingAction.Ignore, session.paddingAction(token, hasSelection = false, gestureInput = 1, currentInput = 2))
        assertEquals(ReaderPaddingAction.Navigate, session.paddingAction(token, hasSelection = false, gestureInput = 2, currentInput = 2))
    }
}
