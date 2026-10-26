package app.peekturn.reader.video

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.peekturn.reader.R

/** Like the book reader, tools overlay one stable text viewport; toggling never repaginates it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoReadingLayout(
    immersive: Boolean,
    toolsVisible: Boolean,
    onToggleTools: () -> Unit,
    content: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    expandedTransport: @Composable () -> Unit,
    compactTransport: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val stableInsets = WindowInsets.displayCutout.union(
        if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility
    )
    val toggle by rememberUpdatedState(onToggleTools)
    val toggleLabel = stringResource(R.string.video_ui_toggle_controls)
    Box(modifier.fillMaxSize().windowInsetsPadding(stableInsets)) {
        // Both tool states reserve the same quiet footer band; text never paints under the small controls.
        Box(Modifier.fillMaxSize().padding(bottom = ReadingToolsHeight)
            .semantics { customActions = listOf(CustomAccessibilityAction(toggleLabel) { toggle(); true }) }
            .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) }
        ) { content() }
        if (toolsVisible) {
            Box(Modifier.align(Alignment.TopCenter)) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter)) { expandedTransport() }
        } else {
            Box(Modifier.align(Alignment.BottomCenter)) { compactTransport() }
        }
    }
}
