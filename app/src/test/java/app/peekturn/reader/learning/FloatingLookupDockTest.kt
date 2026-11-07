package app.peekturn.reader.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLookupDockTest {
    @Test fun docksHalfTheIconAtNearestEdge() {
        assertEquals(-27, dockX(12, 400, 54))
        assertEquals(373, dockX(340, 400, 54))
    }

    @Test fun staysAtTheSameEdgeWhenAlreadyDocked() {
        assertEquals(-27, dockX(-27, 400, 54))
        assertEquals(373, dockX(373, 400, 54))
    }
}
