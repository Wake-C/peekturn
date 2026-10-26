package app.peekturn.reader.video

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoReadingImmersionTest {
    @get:Rule val compose = createComposeRule()
    private val cue = CaptionCue(1, 1_000, 2_000, "A quiet reading page.")
    private val state = VideoPlaybackState(readMode = true, audioOnly = true, positionMs = 1_500,
        captions = CaptionDocument("fixture", "en", "en", false, "fixture", listOf(
            CaptionCue(0, 0, 1_000, "Before."), cue, CaptionCue(2, 2_000, 3_000, "After.")
        )))
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun blankTapTogglesOverlaysWithoutMovingTextAndWordTapOnlyLooksUp() {
        var visible by mutableStateOf(false)
        var immersive by mutableStateOf(true)
        var lookups = 0
        var plays = 0
        compose.setContent {
            PeekturnTheme {
                Box(Modifier.width(320.dp).height(600.dp)) {
                    VideoReadingLayout(immersive, visible, { visible = !visible },
                        content = {
                            Column(Modifier.fillMaxSize().testTag("page").padding(20.dp)) {
                                VideoReadingParagraph(cue, current = true, onLookup = { lookups++ },
                                    onBlankTap = { visible = !visible })
                            }
                        },
                        topBar = { Box(Modifier.fillMaxWidth().height(56.dp)) { Text("Top tools") } },
                        expandedTransport = { Text("Expanded controls") },
                        compactTransport = { VideoCompactTransport(state, {}, { plays++ }, {}) })
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(cue.text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.onNodeWithText(cue.text).performTouchInput { click(layouts.single().getBoundingBox(0).center) }
        compose.runOnIdle { assertEquals(1, lookups); assertFalse(visible) }
        // Whitespace within the paragraph is handled by the text; it must not also toggle its parent.
        compose.onNodeWithText(cue.text).performTouchInput { click(Offset(width - 1f, 1f)) }
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithTag("page").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.runOnIdle { assertFalse(visible) }
        val play = compose.onNodeWithContentDescription(label(R.string.video_ui_play))
        assertTrue(compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot.bottom <= play.fetchSemanticsNode().boundsInRoot.top)
        play.performClick()
        compose.runOnIdle { assertEquals(1, plays); assertFalse(visible) }
        compose.onNodeWithText(label(R.string.video_ui_follow)).assertDoesNotExist()
        for (useImmersive in listOf(true, false)) {
            compose.runOnIdle { immersive = useImmersive }
            val before = compose.onNodeWithText(cue.text).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("page").performTouchInput { click(Offset(width / 2f, height / 2f)) }
            compose.onNodeWithText("Top tools").assertExists()
            compose.onNodeWithText("Expanded controls").assertExists()
            assertEquals(before, compose.onNodeWithText(cue.text).fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("page").performTouchInput { click(Offset(width / 2f, height / 2f)) }
            compose.onNodeWithText("Top tools").assertDoesNotExist()
            assertEquals(before, compose.onNodeWithText(cue.text).fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun scrollingDoesNotToggleToolsAndCompactControlsKeepTheirActions() {
        var visible by mutableStateOf(false)
        var previous = 0
        var next = 0
        var firstVisible = { 0 }
        compose.setContent {
            val list = rememberLazyListState()
            firstVisible = { list.firstVisibleItemIndex }
            PeekturnTheme {
                Box(Modifier.width(320.dp).height(500.dp)) {
                    VideoReadingLayout(true, visible, { visible = !visible },
                        content = {
                            LazyColumn(Modifier.fillMaxSize().testTag("scroll"), state = list) {
                                items(30) { index ->
                                    VideoReadingParagraph(cue.copy(id = index, text = "Reading paragraph $index."),
                                        current = false, onLookup = {}, onBlankTap = { visible = !visible })
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        },
                        topBar = { Text("Top tools") }, expandedTransport = { Text("Expanded controls") },
                        compactTransport = { VideoCompactTransport(state, { previous++ }, {}, { next++ }) })
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(firstVisible() > 0); assertFalse(visible) }
        compose.onNodeWithContentDescription(label(R.string.video_ui_previous_subtitle)).performClick()
        compose.onNodeWithContentDescription(label(R.string.video_ui_next_subtitle)).performClick()
        compose.runOnIdle { assertEquals(1, previous); assertEquals(1, next); assertFalse(visible) }
        compose.onNodeWithText("Top tools").assertDoesNotExist()
    }
}
