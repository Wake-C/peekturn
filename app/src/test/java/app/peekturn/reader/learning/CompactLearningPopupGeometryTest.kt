package app.peekturn.reader.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class CompactLearningPopupGeometryTest {
    @Test fun landscapePopupLeavesVisibleSpaceAboveAndBelowTheAnswer() {
        val popup = compactLearningPopupGeometry(720, 360, 420, 420, 12, 24)
        assertEquals(420, popup.width)
        assertEquals(255, popup.height)
        assertEquals(150, popup.x)
        assertEquals(40, popup.y)
        assertTrue(popup.y > 12)
        assertTrue(popup.y + popup.height < 360 - 24 - 12)
    }

    @Test fun tabletPopupDoesNotGrowBeyondItsReadingColumnOrHeightCap() {
        val popup = compactLearningPopupGeometry(1280, 900, 420, 420, 12, 24)
        assertEquals(420, popup.width)
        assertEquals(420, popup.height)
        assertEquals(1280, 2 * popup.x + popup.width)
        assertEquals(popup.y - 12, 900 - 24 - 12 - popup.y - popup.height)
    }

    @Test fun narrowShortWindowStillFitsWithoutExtendingUnderNavigation() {
        val popup = compactLearningPopupGeometry(320, 240, 420, 420, 12, 32)
        assertEquals(296, popup.width)
        assertEquals(150, popup.height)
        assertTrue(popup.x >= 12)
        assertTrue(popup.x + popup.width <= 320 - 12)
        assertTrue(popup.y >= 12)
        assertTrue(popup.y + popup.height <= 240 - 32 - 12)
    }

    @Test fun orientationAndWindowSizesKeepTheSameBoundedCenteredPolicy() {
        for (width in listOf(280, 320, 720, 1280)) {
            for (height in listOf(180, 240, 320, 720, 900)) {
                for (bottom in listOf(0, 24, 48)) {
                    val popup = compactLearningPopupGeometry(width, height, 420, 420, 12, bottom)
                    val availableHeight = height - 24 - bottom
                    val expectedHeight = minOf(420, floor(availableHeight * 0.82).toInt())
                    assertEquals("$width x $height bottom=$bottom", expectedHeight, popup.height)
                    assertEquals((width - popup.width) / 2, popup.x)
                    assertEquals(12 + (availableHeight - popup.height) / 2, popup.y)
                    assertTrue(popup.y + popup.height <= height - bottom - 12)
                }
            }
        }
    }

    @Test fun pixelDensityDoesNotChangeTheDpSizePolicy() {
        val normal = compactLearningPopupGeometry(720, 360, 420, 420, 12, 24)
        val doubled = compactLearningPopupGeometry(1440, 720, 840, 840, 24, 48)
        assertEquals(normal.width * 2, doubled.width)
        assertEquals(normal.x * 2, doubled.x)
        assertTrue(kotlin.math.abs(normal.height * 2 - doubled.height) <= 1)
        assertTrue(kotlin.math.abs(normal.y * 2 - doubled.y) <= 1)
    }

    @Test fun transientEmptyWindowAndOversizedInsetsNeverProduceInvalidBounds() {
        for ((width, height) in listOf(0 to 0, 1 to 1, 320 to 1, 1 to 320)) {
            val popup = compactLearningPopupGeometry(width, height, 420, 420, 12, 80)
            assertTrue(popup.width >= 1)
            assertTrue(popup.height >= 1)
            assertTrue(popup.x >= 0)
            assertTrue(popup.y >= 0)
            assertTrue(popup.x + popup.width <= width.coerceAtLeast(1))
            assertTrue(popup.y + popup.height <= height.coerceAtLeast(1))
        }
    }
}
