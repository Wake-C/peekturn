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
}
