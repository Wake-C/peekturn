package app.peekturn.reader.video

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CaptionViewportTest {
    @get:Rule val compose = createComposeRule()

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
