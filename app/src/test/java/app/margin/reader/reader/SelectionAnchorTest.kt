package app.margin.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionAnchorTest {
    @Test fun `window anchor preserves full selected word below native top padding`() {
        // DOM native-pixel bounds are y=308..372, page root padding is 48, and
        // navigator starts at window y=100. Readium 3.1 reports top=356,bottom=372.
        val anchor = readium31SelectionAnchor(40f, 356f, 120f, 372f, 8, 100, 48)
        assertEquals(48f, anchor.left, 0f)
        assertEquals(456f, anchor.top, 0f)
        assertEquals(128f, anchor.right, 0f)
        assertEquals(520f, anchor.bottom, 0f)
    }
}
