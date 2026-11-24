package app.peekturn.reader.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FullscreenCaptionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captionsSitNearTheSafeBottomAndClearTheVisibleFooter() {
        val cue = CaptionCue(1, 0, 5_000, "A short sentence.")
        val state = VideoPlaybackState(positionMs = 500, captions = CaptionDocument(
            "video", "en", "en", false, "hash", listOf(cue)))
        var controls by mutableStateOf(false)
        var density = 1f
        var safeBottom = 0
        compose.setContent {
            density = LocalDensity.current.density
            safeBottom = WindowInsets.safeDrawing.getBottom(LocalDensity.current)
            PeekturnTheme {
                Box(Modifier.height(320.dp).testTag("frame")) {
                    FullscreenCaptions(state.copy(toolsVisible = controls)) { _, _ -> }
                }
            }
        }
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        fun bottomGapDp(): Float {
            val text = compose.onNodeWithText(cue.text).fetchSemanticsNode().boundsInRoot
            return (frame.bottom - text.bottom - safeBottom) / density
        }
        val viewingGap = bottomGapDp()
        assertTrue("Viewing margin: $viewingGap dp", viewingGap in 22f..36f)
        compose.runOnIdle { controls = true }
        val controlsGap = bottomGapDp()
        assertTrue("Footer clearance: $controlsGap dp", controlsGap in 64f..70f)
        compose.runOnIdle { controls = false }
        assertEquals(viewingGap, bottomGapDp(), 1f)
    }

    @Test fun portraitCaptionsStayOnTheFittedPictureAndWordsAreTappable() {
        val cue = CaptionCue(3, 2000, 6000, "A word in context.")
        val state = VideoPlaybackState(positionMs = 3500, toolsVisible = false, captions = CaptionDocument(
            "video", "en", "en", false, "hash", listOf(cue)))
        var aspect by mutableStateOf(9f / 16f)
        var selected: CaptionSelection? = null
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            PeekturnTheme {
                Box(Modifier.size(300.dp, 560.dp).testTag("portrait-frame")) {
                    FullscreenCaptions(state.copy(aspectRatio = aspect)) { _, word -> selected = word }
                }
            }
        }
        val frame = compose.onNodeWithTag("portrait-frame").fetchSemanticsNode().boundsInRoot
        val sentence = compose.onNodeWithText(cue.text, useUnmergedTree = true)
        fun checkPosition() {
            val text = sentence.fetchSemanticsNode().boundsInRoot
            val pictureHeight = minOf(frame.height, frame.width / aspect)
            val pictureBottom = frame.center.y + pictureHeight / 2
            val margin = (pictureHeight * .09f).coerceIn(12 * density, 64 * density)
            assertEquals(frame.center.x, text.center.x, 2f)
            assertEquals(pictureBottom - margin - 5 * density, text.bottom, 2f)
        }
        checkPosition()
        compose.onNodeWithText("00:02").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        sentence.performTouchInput { click(layouts.single().getBoundingBox(2).center) }
        compose.runOnIdle {
            assertEquals("word", selected?.term)
            aspect = 16f / 9f
        }
        checkPosition()
    }
}
