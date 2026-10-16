@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.margin.reader.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun readerPaper(theme: String) = when (theme) {
    "warm" -> Color(0xFFEEE5D3)
    "light" -> Color(0xFFFAF9F4)
    else -> Color(0xFF171E23)
}

/** Passive labels have no pointer-input modifiers and cannot steal book gestures. */
@Composable internal fun ReaderPosition(chapter: String, progress: String, theme: String, immersive: Boolean) {
    val ink = if (theme == "dark") Color(0xFFE9E8DF) else Color(0xFF262B2C)
    val labelInk = ink.copy(alpha = if (theme == "dark") 0.55f else 0.7f)
    val stableInsets = WindowInsets.displayCutout.union(
        if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility
    )
    Box(Modifier.fillMaxSize().windowInsetsPadding(stableInsets)) {
        Text(chapter, color = labelInk, fontSize = 10.sp, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 2.dp))
        Text(progress, color = labelInk, fontSize = 10.sp, lineHeight = 12.sp,
            maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 6.dp))
    }
}

/** A temporary compact tool rail. Its visibility never changes navigator measurement. */
@Composable internal fun ReaderChrome(
    theme: String, onLibrary: () -> Unit, onContents: () -> Unit, onSearch: () -> Unit,
    onAppearance: () -> Unit, onHide: () -> Unit
) {
    val ink = if (theme == "dark") Color(0xFFE9E8DF) else Color(0xFF262B2C)
    Box(Modifier.fillMaxSize()) {
        Surface(color = readerPaper(theme), contentColor = ink,
            shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 28.dp)) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLibrary) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Return to library") }
                IconButton(onClick = onContents) { Icon(Icons.AutoMirrored.Outlined.List, "Chapters") }
                IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search book") }
                IconButton(onClick = onAppearance) { Icon(Icons.Outlined.TextFields, "Reading appearance") }
                IconButton(onClick = onHide) { Icon(Icons.Outlined.Close, "Hide reading controls") }
            }
        }
    }
}
