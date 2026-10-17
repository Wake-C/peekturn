@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.margin.reader.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun readerPaper(theme: String) = when (theme) {
    "warm" -> Color(0xFFEEE5D3)
    "light" -> Color(0xFFFAF9F4)
    else -> Color(0xFF171E23)
}

/**
 * Passive labels have no pointer-input modifiers and cannot steal book gestures.
 *
 * The reader reserves as little as 16dp above the book text for this label, so unlike body text it
 * keeps a fixed on-screen size: scaling it with the system font size would push it onto the first
 * line of the book. Book text itself still scales normally, and the reader keeps a real chapter
 * title visible for large-text readers at the cost of a label that stays 11dp tall.
 */
@Composable internal fun ReaderPosition(chapter: String, progress: String, theme: String, immersive: Boolean) {
    val ink = if (theme == "dark") Color(0xFFE9E8DF) else Color(0xFF262B2C)
    val labelInk = ink.copy(alpha = if (theme == "dark") 0.62f else 0.72f)
    val stableInsets = WindowInsets.displayCutout.union(
        if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility
    )
    val labelSize = with(LocalDensity.current) { 11.dp.toSp() }
    Box(Modifier.fillMaxSize().windowInsetsPadding(stableInsets)) {
        val title = chapter.trim()
        if (title.isNotEmpty()) {
            Text(title, color = labelInk, fontSize = labelSize, lineHeight = labelSize * 1.25f,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.72f)
                    .padding(start = 20.dp, top = 2.dp))
        }
        val position = progress.trim()
        if (position.isNotEmpty()) {
            Text(position, color = labelInk, fontSize = labelSize, lineHeight = labelSize * 1.25f,
                maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f)
                    .padding(start = 20.dp, bottom = 6.dp))
        }
    }
}

/**
 * A temporary compact tool rail. Its visibility never changes navigator measurement, and a tap on the
 * rail's own bounds is consumed so it cannot also reach the book underneath; the page area around
 * the rail stays page area.
 */
@Composable internal fun ReaderChrome(
    theme: String, onContents: () -> Unit, onSearch: () -> Unit, onAppearance: () -> Unit
) {
    val ink = if (theme == "dark") Color(0xFFE9E8DF) else Color(0xFF262B2C)
    Box(Modifier.fillMaxSize()) {
        Surface(color = readerPaper(theme), contentColor = ink,
            shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 28.dp)
                .pointerInput(Unit) { detectTapGestures { } }) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onContents, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.List, "Chapters", tint = ink, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Search, "Search book", tint = ink, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onAppearance, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.TextFields, "Reading appearance", tint = ink, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
