package app.peekturn.reader.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CaptionViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun watchRowsFitTheirTextAndKeepSeparateWordAndRowActions() {
        val short = CaptionCue(1, 3_000, 5_000, "Why are you here?")
        val long = CaptionCue(2, 7_000, 12_000,
            "This longer sentence wraps across several lines in the transcript of a narrow portrait player.")
        var played = 0
        var selected: CaptionSelection? = null
        var enabled by mutableStateOf(true)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) {
                PeekturnTheme {
                    Column(Modifier.width(260.dp)) {
                        for (cue in listOf(short, long)) {
                            VideoTranscriptRow(cue, null, current = cue == short, enabled = enabled,
                                onPlay = { played++ }, onLookup = { selected = it })
                        }
                    }
                }
            }
        }
        val first = compose.onNode(hasText(short.text) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val next = compose.onNode(hasText(long.text) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, first.height, 1f)
        assertEquals(first.bottom, next.top, 1f)
        assertTrue("Wrapped row must expand: $next", next.height > first.height)

        fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val stamp = compose.onNodeWithText("00:07", useUnmergedTree = true)
        val sentence = compose.onNodeWithText(long.text, useUnmergedTree = true)
        val body = layout(sentence)
        assertTrue(body.lineCount > 1 && !body.didOverflowHeight)
        assertEquals(stamp.fetchSemanticsNode().boundsInRoot.top + layout(stamp).firstBaseline,
            sentence.fetchSemanticsNode().boundsInRoot.top + body.firstBaseline, 1f)
        sentence.performTouchInput { click(body.getBoundingBox(1).center) }
        compose.runOnIdle { assertEquals("This", selected?.term); assertEquals(0, played) }
        stamp.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, played)
            selected = null
            enabled = false
        }
        sentence.performTouchInput { click(body.getBoundingBox(1).center) }
        stamp.performTouchInput { click(center) }
        compose.runOnIdle { assertNull(selected); assertEquals(1, played) }
    }

    @Test fun cachedSentenceAllowsWordLookupWithoutOfferingPlayback() {
        val cue = CaptionCue(3, 314_000, 320_000, "Here again.")
        var selected: CaptionSelection? = null
        compose.setContent {
            PeekturnTheme {
                VideoTranscriptRow(cue, null, current = true, onPlay = null, onLookup = { selected = it })
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        val text = compose.onNodeWithText(cue.text)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        text.performTouchInput { click(layouts.single().getBoundingBox(1).center) }
        compose.runOnIdle { assertEquals("Here", selected?.term) }
    }

    @Test fun browseAheadThenLocateWhilePausedOrReturnFromLookupAndFollowPlayback() {
        var playing by mutableStateOf(true)
        var lookup by mutableStateOf(false)
        var current by mutableIntStateOf(1)
        var locate by mutableIntStateOf(0)
        var showTranscript by mutableStateOf(true)
        lateinit var list: LazyListState
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (showTranscript) holder.SaveableStateProvider("transcript") {
                list = rememberCaptionListState(current, playing, lookup, locate)
                LazyColumn(Modifier.size(240.dp, 220.dp).testTag("captions"), state = list) {
                    items(120) { Text("Sentence $it", Modifier.height(48.dp)) }
                }
            }
        }
        fun browseAhead() {
            repeat(3) { compose.onNodeWithTag("captions").performTouchInput { swipeUp() } }
            compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > current) }
        }
        fun assertCurrentVisible() = compose.runOnIdle {
            assertTrue(list.layoutInfo.visibleItemsInfo.any { it.index == current })
        }
        browseAhead()
        val browsed = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        compose.runOnIdle { showTranscript = false }
        compose.onNodeWithTag("captions").assertDoesNotExist()
        compose.runOnIdle { showTranscript = true }
        compose.runOnIdle { assertEquals(browsed, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
        compose.runOnIdle { current++; playing = false }
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > current); locate++ }
        assertCurrentVisible()
        compose.runOnIdle { assertFalse(playing); playing = true }
        compose.waitForIdle()
        browseAhead()
        compose.runOnIdle { playing = false; lookup = true }
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > current); lookup = false; playing = true }
        assertCurrentVisible()
        compose.runOnIdle { current = 30 }
        assertCurrentVisible()
    }
}
