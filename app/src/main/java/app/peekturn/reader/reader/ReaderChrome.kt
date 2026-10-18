@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.peekturn.reader.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R
import app.peekturn.reader.narration.NarrationState
import app.peekturn.reader.ui.isDarkTheme
import app.peekturn.reader.ui.peekturnColorScheme
import kotlin.math.roundToInt

internal fun readerPaper(theme: String) = peekturnColorScheme(theme).background

/**
 * The tint the navigator paints behind selected book text. Readium CSS draws `::selection` from
 * `--RS__selectionBackgroundColor`, whose stock #b4d8fe is the platform blue that fights the warm
 * palette; the theme's own accent at one alpha keeps the highlight inside the palette in every
 * theme while selected text keeps its ink colour.
 */
internal fun readerSelectionBackground(theme: String): String {
    val accent = peekturnColorScheme(theme).primary
    return "rgba(${(accent.red * 255f).roundToInt()}, ${(accent.green * 255f).roundToInt()}, " +
        "${(accent.blue * 255f).roundToInt()}, $SELECTION_ALPHA)"
}

private const val SELECTION_ALPHA = 0.32f

/**
 * Passive labels have no pointer-input modifiers and cannot steal book gestures. They are an
 * overlay, so they never change the publication viewport.
 *
 * The reader reserves as little as 16dp above the book text for this label, so unlike body text it
 * keeps a fixed on-screen size: scaling it with the system font size would push it onto the first
 * line of the book. Book text itself still scales normally, and the reader keeps a real chapter
 * title visible for large-text readers at the cost of a label that stays 11dp tall.
 */
@Composable internal fun ReaderPosition(
    chapter: String,
    progress: String,
    theme: String,
    stableInsets: WindowInsets
) {
    val scheme = peekturnColorScheme(theme)
    val ink = scheme.onBackground
    val labelInk = ink.copy(alpha = if (isDarkTheme(theme)) 0.62f else 0.72f)
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
 * The expanded reading tools. Every surface is an overlay over a stable publication viewport:
 * the top bar, the four-entry menu and the floating narration card never take or return book
 * space. The menu keeps the same four entries while narration runs, so the speaker becomes an
 * active state instead of being replaced by a second toolbar.
 */
@Composable internal fun ReaderChrome(
    theme: String,
    immersive: Boolean,
    narration: NarrationState?,
    onHide: () -> Unit,
    onContents: () -> Unit,
    onTypography: () -> Unit,
    onOptions: () -> Unit,
    onReadAloud: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = peekturnColorScheme(theme)
    val systemInsets = WindowInsets.displayCutout.union(
        if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility
    )
    Box(modifier.fillMaxSize()) {
        ReaderTopBar(
            title = stringResource(R.string.reader_tools_title),
            closeLabel = stringResource(R.string.reader_tools_close),
            onClose = onHide,
            color = scheme.surface,
            contentColor = scheme.onSurface,
            insets = systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (narration != null) {
                ReaderNarrationCard(
                    state = narration,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSpeed = onSpeed,
                    onStop = onStop,
                    onRetry = onRetry,
                    modifier = Modifier
                        .windowInsetsPadding(systemInsets.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            ReaderMenuBar(
                theme = theme,
                navigationInset = systemInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                narrationActive = narration != null,
                onContents = onContents,
                onTypography = onTypography,
                onOptions = onOptions,
                onReadAloud = onReadAloud
            )
        }
    }
}

/**
 * A collapsed narration session leaves only three transport buttons in the footer band. They are
 * centred against the whole screen, not the width left after the page counter, and they consume
 * only their own taps; the page around them keeps Readium's original gestures.
 */
@Composable internal fun ReaderCompactNarration(
    theme: String,
    immersive: Boolean,
    state: NarrationState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val insets = WindowInsets.displayCutout.union(
        if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility
    ).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    Box(Modifier.fillMaxSize().windowInsetsPadding(insets), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier.padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CompactTransportButton(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.reader_narration_previous),
                accent = false,
                onClick = onPrevious
            )
            CompactTransportButton(
                icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (state.playing) R.string.reader_narration_pause else R.string.reader_narration_play
                ),
                accent = true,
                onClick = onPlayPause
            )
            CompactTransportButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.reader_narration_next),
                accent = false,
                onClick = onNext
            )
        }
    }
}

@Composable private fun CompactTransportButton(
    icon: ImageVector,
    contentDescription: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable private fun ReaderTopBar(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    color: Color,
    contentColor: Color,
    insets: WindowInsets,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(insets).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.padding(start = 4.dp).size(48.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = closeLabel)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                // A matching invisible slot keeps the title optically centred under the close mark.
                Spacer(Modifier.width(52.dp))
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable private fun ReaderMenuBar(
    theme: String,
    navigationInset: WindowInsets,
    narrationActive: Boolean,
    onContents: () -> Unit,
    onTypography: () -> Unit,
    onOptions: () -> Unit,
    onReadAloud: () -> Unit
) {
    val scheme = peekturnColorScheme(theme)
    Surface(
        color = scheme.surface,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(navigationInset).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                readerToolbarEntries.forEach { entry ->
                    val label = stringResource(entry.labelRes)
                    ReaderToolbarAction(
                        entry = entry,
                        label = label,
                        ink = if (entry == ReaderToolbarEntry.READ_ALOUD && narrationActive) scheme.primary else scheme.onSurface,
                        modifier = Modifier.weight(1f),
                        onClick = when (entry) {
                            ReaderToolbarEntry.CONTENTS -> onContents
                            ReaderToolbarEntry.TYPOGRAPHY -> onTypography
                            ReaderToolbarEntry.OPTIONS -> onOptions
                            ReaderToolbarEntry.READ_ALOUD -> onReadAloud
                        }
                    )
                }
            }
        }
    }
}

@Composable private fun ReaderToolbarAction(
    entry: ReaderToolbarEntry,
    label: String,
    ink: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val icon = when (entry) {
        ReaderToolbarEntry.CONTENTS -> Icons.Outlined.Toc
        ReaderToolbarEntry.TYPOGRAPHY -> toolbarTypography
        ReaderToolbarEntry.OPTIONS -> Icons.Outlined.Tune
        ReaderToolbarEntry.READ_ALOUD -> toolbarSpeaker
    }
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = ink,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

// The stock outlined set mixes thick bars and a heavy filled "Tt", which read as different
// families beside the hairline magnifier. The rail's own mark shares one hairline weight on
// Material's 24dp grid, so the four glyphs carry the same stroke and optical size.
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

/** "Aa" is the reading-app mark for typography, drawn at the toolbar's stroke weight. */
private val toolbarTypography: ImageVector = ImageVector.Builder(
    name = "PeekturnToolbarTypography", defaultWidth = 24.dp, defaultHeight = 24.dp,
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

/** The read-aloud speaker shares the menu's hairline weight and optical size. */
private val toolbarSpeaker: ImageVector = ImageVector.Builder(
    name = "PeekturnToolbarSpeaker", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    railPath(this) {
        moveTo(4f, 9f)
        lineTo(8f, 9f)
        lineTo(13f, 5f)
        lineTo(13f, 19f)
        lineTo(8f, 15f)
        lineTo(4f, 15f)
        close()
    }
    railPath(this) {
        moveTo(16.5f, 8.5f)
        arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 0f, dy1 = 7f)
    }
    railPath(this) {
        moveTo(19f, 5.5f)
        arcToRelative(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 0f, dy1 = 13f)
    }
}.build()

/** Binoculars mark the whole-book search entry inside the contents header. */
internal val toolbarBinoculars: ImageVector = ImageVector.Builder(
    name = "PeekturnToolbarBinoculars", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    railPath(this) { moveTo(3f, 13f); lineTo(6f, 5f); lineTo(9f, 5f); lineTo(10f, 14f) }
    railPath(this) { moveTo(21f, 13f); lineTo(18f, 5f); lineTo(15f, 5f); lineTo(14f, 14f) }
    railPath(this) { moveTo(10f, 11f); lineTo(14f, 11f) }
    railPath(this) { moveTo(7f, 5f); lineTo(7f, 3f) }
    railPath(this) { moveTo(17f, 5f); lineTo(17f, 3f) }
    railPath(this) { ring(6.5f, 15.5f, 4.5f) }
    railPath(this) { ring(17.5f, 15.5f, 4.5f) }
}.build()
