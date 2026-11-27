package app.peekturn.reader.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FullscreenPictureGesturesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pictureTapAndHorizontalSwipeDoNotAdjustLevelsAndVerticalSwipesKeepTheirStartingSide() {
        val starts = mutableListOf<PictureLevel>()
        val adjustments = mutableListOf<Pair<PictureLevel, Float>>()
        var taps = 0
        var ends = 0
        compose.setContent {
            Box(Modifier.size(220.dp, 320.dp).testTag("picture")
                .clickable { taps++ }
                .pointerInput(Unit) {
                    detectPictureLevelSwipes(16.dp.roundToPx(), 16.dp.roundToPx(),
                        onStart = { starts.add(it); 0.5f },
                        onChange = { level, value -> adjustments.add(level to value) },
                        onEnd = { ends++ })
                })
        }
        val picture = compose.onNodeWithTag("picture")
        picture.performTouchInput {
            click(center)
            swipe(Offset(width * .2f, height * .5f), Offset(width * .8f, height * .55f), 300)
        }
        compose.runOnIdle { assertEquals(1, taps); assertTrue(starts.isEmpty()) }
        picture.performTouchInput {
            down(Offset(width * .25f, height * .75f))
            moveTo(Offset(width * .25f, height * .6f))
            moveTo(Offset(width * .75f, height * .05f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(PictureLevel.Brightness), starts)
            assertTrue(adjustments.all { it.first == PictureLevel.Brightness })
            assertEquals(1f, adjustments.last().second, .001f)
        }
        picture.performTouchInput {
            swipe(Offset(width * .75f, height * .2f), Offset(width * .75f, height * .9f), 300)
        }
        compose.runOnIdle {
            assertEquals(listOf(PictureLevel.Brightness, PictureLevel.Volume), starts)
            assertEquals(PictureLevel.Volume to 0f, adjustments.last())
            assertEquals(2, ends)
            assertEquals(1, taps)
        }
        picture.performTouchInput { swipe(Offset(2f, height * .2f), Offset(2f, height * .8f), 300) }
        compose.runOnIdle { assertEquals(2, starts.size) }
    }
}
