@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.margin.reader.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.margin.reader.ui.isDarkTheme
import app.margin.reader.ui.marginColorScheme
import kotlin.math.roundToInt

internal fun readerPaper(theme: String) = marginColorScheme(theme).background

/**
 * The tint the navigator paints behind selected book text. Readium CSS draws `::selection` from
 * `--RS__selectionBackgroundColor`, whose stock #b4d8fe is the platform blue that fights the warm
 * palette; the theme's own accent at one alpha keeps the highlight inside the palette in every
 * theme while selected text keeps its ink colour.
 */
internal fun readerSelectionBackground(theme: String): String {
    val accent = marginColorScheme(theme).primary
    return "rgba(${(accent.red * 255f).roundToInt()}, ${(accent.green * 255f).roundToInt()}, " +
        "${(accent.blue * 255f).roundToInt()}, $SELECTION_ALPHA)"
}

private const val SELECTION_ALPHA = 0.32f

/**
 * Passive labels have no pointer-input modifiers and cannot steal book gestures.
 *
 * The reader reserves as little as 16dp above the book text for this label, so unlike body text it
 * keeps a fixed on-screen size: scaling it with the system font size would push it onto the first
 * line of the book. Book text itself still scales normally, and the reader keeps a real chapter
 * title visible for large-text readers at the cost of a label that stays 11dp tall.
 */
@Composable internal fun ReaderPosition(chapter: String, progress: String, theme: String, immersive: Boolean) {
    val scheme = marginColorScheme(theme)
    val ink = scheme.onBackground
    val labelInk = ink.copy(alpha = if (isDarkTheme(theme)) 0.62f else 0.72f)
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
 *
 * The rail floats over the text column, so it takes a raised container role plus an outlineVariant
 * hairline instead of the page paper: a paper-coloured panel reads as a hole punched through the
 * last visible line rather than as a surface the reader can reach for. The container is opaque and
 * no scrim is drawn, because a scrim would soften book text beyond the rail's own footprint. Shape,
 * padding, icon size, spacing and insets are unchanged, so the footprint is identical.
 */
@Composable internal fun ReaderChrome(
    theme: String, onContents: () -> Unit, onSearch: () -> Unit, onAppearance: () -> Unit
) {
    val ink = marginColorScheme(theme).onBackground
    Box(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = ink,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 28.dp)
                .pointerInput(Unit) { detectTapGestures { } }) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onContents, modifier = Modifier.size(48.dp)) {
                    Icon(railContents, "Chapters", tint = ink, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Search, "Search book", tint = ink, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onAppearance, modifier = Modifier.size(48.dp)) {
                    Icon(railAppearance, "Reading appearance", tint = ink, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

// The stock outlined set mixes thick bars, a hairline magnifier and a heavy filled "Tt", which read
// as three families in one row. The rail's own two marks share one hairline weight on Material's
// 24dp grid, so all three glyphs carry the same stroke and optical size next to Icons.Outlined.Search.
private const val RAIL_STROKE = 1.6f

private fun railPath(builder: ImageVector.Builder, pathBuilder: PathBuilder.() -> Unit): ImageVector.Builder =
    builder.path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = RAIL_STROKE,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = pathBuilder)

private fun PathBuilder.ring(centerX: Float, centerY: Float, radius: Float) {
    moveTo(centerX - radius, centerY)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = radius * 2, dy1 = 0f)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -radius * 2, dy1 = 0f)
    close()
}

/** Ruled lines with a leading bullet: the shapes of the stock list glyph, at the rail's weight. */
private val railContents: ImageVector = ImageVector.Builder(
    name = "MarginRailContents", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    for (y in listOf(6.4f, 12f, 17.6f)) {
        railPath(this) { moveTo(4.2f, y); lineTo(6.2f, y) }
        railPath(this) { moveTo(9.4f, y); lineTo(20f, y) }
    }
}.build()

/** "Aa" is the reading-app mark for appearance; drawn here so its stroke matches the rail. */
private val railAppearance: ImageVector = ImageVector.Builder(
    name = "MarginRailAppearance", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    // Cap height 6.4-18.4 keeps the letters from crowding the neighbouring hairline magnifier.
    railPath(this) { moveTo(9.2f, 6.4f); lineTo(4.4f, 18.4f) }
    railPath(this) { moveTo(9.2f, 6.4f); lineTo(14f, 18.4f) }
    railPath(this) { moveTo(6.2f, 14f); lineTo(12.2f, 14f) }
    // The small "a" is a bowl with a stem starting at the bowl's own top edge, not above it: a stem
    // that rises past the bowl is a "d", which is how this mark used to read.
    val bowlRadius = 2.15f
    val bowlCenterX = 19f
    val bowlCenterY = 16.35f
    railPath(this) { ring(bowlCenterX, bowlCenterY, bowlRadius) }
    railPath(this) {
        moveTo(bowlCenterX + bowlRadius, bowlCenterY - bowlRadius)
        lineTo(bowlCenterX + bowlRadius, 18.4f)
    }
}.build()
