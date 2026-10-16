package app.margin.reader.learning

import org.junit.Assert.*
import org.junit.Test

class PopupGeometryTest {
    @Test fun shortSidesYieldUsefulBoundedOverlayInsteadOfNeedlessScrolling() {
        val selection = PopupSelectionBounds(90, 350, 180, 390)
        val popup = learningPopupGeometry(360, 720, 336, 590, selection, 12, 420)
        assertEquals(590, popup.height)
        assertEquals(65, popup.y)
        assertTrue(popup.y < selection.top && popup.y + popup.height > selection.bottom)
    }

    @Test fun generousSideKeepsAnswerBesideSelection() {
        val selection = PopupSelectionBounds(90, 90, 180, 130)
        val popup = learningPopupGeometry(360, 900, 336, 700, selection, 12, 420)
        assertEquals(700, popup.height)
        assertTrue(popup.y >= selection.bottom + 12)
    }

    @Test fun loadingUsesNaturalHeightRatherThanAnEmptyFixedPanel() {
        val selection = PopupSelectionBounds(90, 350, 180, 390)
        val popup = learningPopupGeometry(360, 720, 336, 190, selection, 12, 420)
        assertEquals(190, popup.height)
        assertEquals((720 - 190) / 2, popup.y)
    }

    @Test fun noSelectionAndShortWindowsRemainWithinViewport() {
        listOf(null, PopupSelectionBounds(12, 140, 80, 170)).forEach { selection ->
            val popup = learningPopupGeometry(320, 300, 420, 520, selection, 12)
            assertTrue(popup.x >= 12 && popup.x + popup.width <= 308)
            assertTrue(popup.y >= 12 && popup.y + popup.height <= 288)
        }
    }
}
