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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.peekturn.reader.model.FullscreenCaptionSize
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
        val replayLabel = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(app.peekturn.reader.R.string.learning_popup_replay_sentence)
        var replayed: Int? = null
        var controls by mutableStateOf(false)
        var density = 1f
        var safeBottom = 0
        compose.setContent {
            density = LocalDensity.current.density
            safeBottom = WindowInsets.safeDrawing.getBottom(LocalDensity.current)
            PeekturnTheme {
                Box(Modifier.height(320.dp).testTag("frame")) {
                    FullscreenCaptions(state.copy(toolsVisible = controls), onReplay = { replayed = it }) { _, _ -> }
                }
            }
        }
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        fun bottomGapDp(): Float {
            val text = compose.onNodeWithText(cue.text).fetchSemanticsNode().boundsInRoot
            return (frame.bottom - text.bottom - safeBottom) / density
        }
        val replay = compose.onNodeWithContentDescription(replayLabel)
        replay.assertDoesNotExist()
        val viewingGap = bottomGapDp()
        assertTrue("Viewing margin: $viewingGap dp", viewingGap in 22f..36f)
        compose.runOnIdle { controls = true }
        replay.performClick()
        compose.runOnIdle { assertEquals(0, replayed) }
        val controlsGap = bottomGapDp()
        assertTrue("Footer clearance: $controlsGap dp", controlsGap in 64f..70f)
        compose.runOnIdle { controls = false }
        assertEquals(viewingGap, bottomGapDp(), 1f)
    }

    @Test fun portraitCaptionSitsJustBelowAWidePictureAndFallsBackForATallPicture() {
        val cue = CaptionCue(3, 2000, 6000, "A word in context.")
        val state = VideoPlaybackState(positionMs = 3500, captions = CaptionDocument(
            "video", "en", "en", false, "hash", listOf(cue)))
        var aspect by mutableStateOf(16f / 9f)
        var controls by mutableStateOf(false)
        var selected: CaptionSelection? = null
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            PeekturnTheme {
                Box(Modifier.size(300.dp, 560.dp).testTag("portrait-frame")) {
                    FullscreenCaptions(state.copy(aspectRatio = aspect, toolsVisible = controls),
                        onReplay = {}) { _, word -> selected = word }
                }
            }
        }
        val frame = compose.onNodeWithTag("portrait-frame").fetchSemanticsNode().boundsInRoot
        val sentence = compose.onNodeWithText(cue.text, useUnmergedTree = true)
        val widePictureBottom = frame.center.y + frame.width / aspect / 2
        fun assertBelowPicture() {
            val text = sentence.fetchSemanticsNode().boundsInRoot
            assertEquals(frame.center.x, text.center.x, 2f)
            // 8dp from the picture to the caption surface, then 5dp of text padding.
            assertEquals(widePictureBottom + 13 * density, text.top, 2f)
        }
        assertBelowPicture()
        compose.runOnIdle { controls = true }
        assertBelowPicture()
        compose.onNodeWithText("00:02").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        sentence.performTouchInput { click(layouts.single().getBoundingBox(2).center) }
        compose.runOnIdle {
            assertEquals("word", selected?.term)
            controls = false
            aspect = 9f / 16f
        }
        val text = sentence.fetchSemanticsNode().boundsInRoot
        val pictureHeight = minOf(frame.height, frame.width / aspect)
        val margin = (pictureHeight * .09f).coerceIn(12 * density, 64 * density)
        assertEquals(frame.center.y + pictureHeight / 2 - margin - 5 * density, text.bottom, 2f)
    }

    @Test fun longLargeCaptionsScrollInTheBandAboveTheFooter() {
        val cue = CaptionCue(1, 0, 5000, "A long subtitle with many words. ".repeat(24))
        val state = VideoPlaybackState(positionMs = 500, aspectRatio = 16f / 9f, toolsVisible = true,
            captions = CaptionDocument("video", "en", "en", false, "hash", listOf(cue)))
        var density = 1f
        var inset = 0
        compose.setContent {
            density = LocalDensity.current.density
            inset = WindowInsets.safeDrawing.getBottom(LocalDensity.current)
            PeekturnTheme {
                Box(Modifier.size(300.dp, 560.dp).testTag("frame")) {
                    FullscreenCaptions(state, FullscreenCaptionSize.LARGE, onReplay = {}) { _, _ -> }
                }
            }
        }
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        val scrolling = compose.onNode(hasScrollAction(), useUnmergedTree = true)
        val viewport = scrolling.fetchSemanticsNode().boundsInRoot
        val pictureBottom = frame.center.y + frame.width / state.aspectRatio / 2
        assertTrue(viewport.top > pictureBottom)
        assertTrue(viewport.bottom <= frame.bottom - inset - 56 * density)
        scrolling.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, viewport.height) }
        val scrollAxis = scrolling.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue(scrollAxis.value() > 0f) }
    }

    @Test fun fullscreenSizeChangesTextAndLineHeightInBothOrientations() {
        val cue = CaptionCue(1, 0, 5000, "A short subtitle.")
        val state = VideoPlaybackState(positionMs = 500, captions = CaptionDocument(
            "video", "en", "en", false, "hash", listOf(cue)))
        var portrait by mutableStateOf(true)
        var size by mutableStateOf(FullscreenCaptionSize.MEDIUM)
        compose.setContent {
            PeekturnTheme {
                Box(if (portrait) Modifier.size(280.dp, 480.dp) else Modifier.size(300.dp, 240.dp)) {
                    FullscreenCaptions(state, size, onReplay = {}) { _, _ -> }
                }
            }
        }
        val sentence = compose.onNodeWithText(cue.text, useUnmergedTree = true)
        for (upright in listOf(true, false)) {
            for (choice in FullscreenCaptionSize.entries) {
                compose.runOnIdle { portrait = upright; size = choice }
                val layouts = mutableListOf<TextLayoutResult>()
                sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val style = layouts.single().layoutInput.style
                assertEquals((if (upright) 19f else 16f) * choice.scale, style.fontSize.value, .01f)
                assertEquals((if (upright) 26f else 19f) * choice.scale, style.lineHeight.value, .01f)
            }
        }
    }
}
