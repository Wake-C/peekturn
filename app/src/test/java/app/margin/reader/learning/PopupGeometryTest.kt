package app.margin.reader.learning

import org.junit.Assert.*
import org.junit.Test

class PopupGeometryTest {
    @Test fun growingAnswerStaysBesideSelectionWhenASideFitsControlsAndBody() {
        val selections = listOf(
            PopupSelectionBounds(90, 180, 180, 220),
            PopupSelectionBounds(90, 250, 180, 290),
            PopupSelectionBounds(90, 350, 180, 390)
        )
        selections.forEach { selection ->
            val availableAbove = selection.top - 24
            val availableBelow = 720 - selection.bottom - 24
            assertTrue("Fixture must leave room for controls and a useful scroll body", maxOf(availableAbove, availableBelow) >= 260)
            val partial = learningPopupGeometry(360, 720, 336, 120, selection, 12)
            val grown = learningPopupGeometry(360, 720, 336, 520, selection, 12)
            listOf(partial, grown).forEach { popup ->
                assertTrue(popup.x >= 12 && popup.x + popup.width <= 348)
                assertTrue(popup.y >= 12 && popup.y + popup.height <= 708)
                assertTrue("Answer must not cover the selected range: selection=$selection popup=$popup",
                    popup.y + popup.height <= selection.top - 12 || popup.y >= selection.bottom + 12)
            }
            assertEquals("Streaming growth must not move the popup across the selection",
                partial.y >= selection.bottom, grown.y >= selection.bottom)
            assertTrue("Keep controls and a useful scroll body visible", grown.height >= 260)
        }
    }

    @Test fun noSelectionAndShortWindowsRemainWithinViewport() {
        listOf(null, PopupSelectionBounds(12, 140, 80, 170)).forEach { selection ->
            val popup = learningPopupGeometry(320, 300, 420, 520, selection, 12)
            assertTrue(popup.x >= 12 && popup.x + popup.width <= 308)
            assertTrue(popup.y >= 12 && popup.y + popup.height <= 288)
        }
    }
}
