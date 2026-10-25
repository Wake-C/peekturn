package app.peekturn.reader.learning

import org.junit.Assert.*
import org.junit.Test

class PopupGeometryTest {
    @Test fun streamingAnswerGrowsUpwardWithoutMovingItsBottomEdge() {
        val loading = learningPopupGeometry(360, 720, 440, 190, 12, 24)
        val completed = learningPopupGeometry(360, 720, 440, 590, 12, 24)
        assertEquals(loading.y + loading.height, completed.y + completed.height)
        assertEquals(720 - 24 - 12, completed.y + completed.height)
        assertTrue(completed.y < loading.y)
        assertEquals(590, completed.height)
    }

    @Test fun shortWindowsKeepTheWholeAnswerInsideTheSafeArea() {
        for ((width, height) in listOf(320 to 300, 720 to 320, 360 to 720)) {
            val popup = learningPopupGeometry(width, height, 640, 900, 12, 32)
            assertTrue(popup.x >= 12)
            assertTrue(popup.x + popup.width <= width - 12)
            assertTrue(popup.y >= 12)
            assertEquals(height - 32 - 12, popup.y + popup.height)
        }
    }

    @Test fun wideWindowsCenterTheBoundedReadingColumn() {
        val popup = learningPopupGeometry(1200, 900, 600, 700, 16, 30)
        assertEquals(600, popup.width)
        assertEquals(1200, 2 * popup.x + popup.width)
        assertEquals(900 - 30 - 16, popup.y + popup.height)
    }

    @Test fun centeredAnswerIgnoresTheContentEdgeAndStaysInsideTheSafeArea() {
        // A maximum-height answer fills the available area, so it sits flush at the top margin.
        val full = centeredLearningPopupGeometry(1200, 900, 480, 900, 12, 24)
        assertEquals(480, full.width)
        assertEquals(1200, 2 * full.x + full.width)
        assertEquals(12, full.y)
        assertEquals(900 - 24 - 12, full.y + full.height)

        // A shorter answer is centered with equal gaps above and below inside the same area.
        val short = centeredLearningPopupGeometry(1200, 900, 480, 400, 12, 24)
        assertEquals(400, short.height)
        assertEquals(short.y - 12, 900 - 24 - 12 - (short.y + short.height))
    }

    @Test fun centeredAnswerFitsANarrowShortWindowWithTheSameMargins() {
        for ((width, height) in listOf(320 to 300, 720 to 320)) {
            val popup = centeredLearningPopupGeometry(width, height, 480, height, 12, 32)
            assertTrue(popup.x >= 12)
            assertTrue(popup.x + popup.width <= width - 12)
            assertTrue(popup.y >= 12)
            assertTrue(popup.y + popup.height <= height - 32 - 12)
            assertEquals((width - popup.width) / 2, popup.x)
            assertEquals(12 + (height - 32 - 24 - popup.height) / 2, popup.y)
        }
    }
}
