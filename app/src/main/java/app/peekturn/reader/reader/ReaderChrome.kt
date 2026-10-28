@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.peekturn.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
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
    val labelStyle = TextStyle(
        fontSize = labelSize,
        lineHeight = labelSize * 1.25f,
        letterSpacing = 0.6.sp
    )
    val progressStyle = labelStyle.copy(
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum"
    )
    Box(Modifier.fillMaxSize().windowInsetsPadding(stableInsets)) {
        val title = chapter.trim()
        if (title.isNotEmpty()) {
            Text(title, color = labelInk, style = labelStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.72f)
                    .padding(start = 20.dp, top = 2.dp))
        }
        val position = progress.trim()
        if (position.isNotEmpty()) {
            Text(position, color = labelInk, style = progressStyle, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f)
                    .padding(start = 20.dp, bottom = 6.dp))
        }
    }
}

/**
 * The expanded reading menu and floating narration card overlay a stable publication viewport;
 * they never take or return book space. The menu keeps the same four entries while narration runs,
 * so the speaker becomes an active state instead of being replaced by a second toolbar.
 */
@Composable internal fun ReaderChrome(
    theme: String,
    immersive: Boolean,
    narration: NarrationState?,
    onContents: () -> Unit,
    onTypography: () -> Unit,
    onOptions: () -> Unit,
    onReadAloud: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNextParagraph: () -> Unit,
    onSpeed: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val systemInsets = WindowInsets.displayCutout.union(
        if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility
    )
    Box(modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (narration != null) {
                ReaderNarrationCard(
                    state = narration,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onNextParagraph = onNextParagraph,
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
            modifier = Modifier.padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CompactTransportButton(
                icon = PeekturnIcons.Previous,
                contentDescription = stringResource(R.string.reader_narration_previous),
                accent = false,
                onClick = onPrevious
            )
            CompactTransportButton(
                icon = if (state.playing) PeekturnIcons.Pause else PeekturnIcons.Play,
                contentDescription = stringResource(
                    if (state.playing) R.string.reader_narration_pause else R.string.reader_narration_play
                ),
                accent = true,
                onClick = onPlayPause
            )
            CompactTransportButton(
                icon = PeekturnIcons.Next,
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
            tint = if (accent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
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
                Modifier.fillMaxWidth().windowInsetsPadding(navigationInset).heightIn(min = 58.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                readerToolbarEntries.forEach { entry ->
                    val label = stringResource(entry.labelRes)
                    val active = entry == ReaderToolbarEntry.READ_ALOUD && narrationActive
                    ReaderToolbarAction(
                        entry = entry,
                        label = label,
                        ink = if (active) scheme.primary else scheme.onSurface,
                        active = active,
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
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val icon = when (entry) {
        ReaderToolbarEntry.CONTENTS -> PeekturnIcons.Contents
        ReaderToolbarEntry.TYPOGRAPHY -> PeekturnIcons.Typography
        ReaderToolbarEntry.OPTIONS -> PeekturnIcons.Settings
        ReaderToolbarEntry.READ_ALOUD -> PeekturnIcons.Speaker
    }
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A's active rail is a short accent rule at the top of the entry, not a second icon colour.
        Box(
            Modifier.fillMaxWidth(0.44f).height(2.dp).background(
                if (active) ink else Color.Transparent,
                RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
            )
        )
        Spacer(Modifier.height(6.dp))
        Icon(
            icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = ink,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.2.sp
            ),
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Spacer(Modifier.height(12.dp))
    }
}
