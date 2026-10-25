package app.peekturn.reader.video

import android.content.res.Configuration
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.peekturn.reader.R
import app.peekturn.reader.learning.LearningPopup
import app.peekturn.reader.learning.LearningPopupPlacement
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.reader.readerTransportPrevious
import app.peekturn.reader.reader.readerTransportNext
import app.peekturn.reader.reader.readerTransportPlay
import app.peekturn.reader.reader.readerTransportPause
import app.peekturn.reader.speech.PronunciationPlayer
import kotlinx.coroutines.withTimeoutOrNull

private val VideoScrim = Color.Black.copy(alpha = 0.68f)
private val CaptionScrim = Color.Black.copy(alpha = 0.66f)
private val LandscapeTransportSize = 76.dp
private val LandscapeFooterHeight = 64.dp
private val CaptionControlsGap = 8.dp
// Two 48dp touch rows; the system navigation inset is added separately.
internal val PortraitToolsHeight = 96.dp

/**
 * A's native surface. The picture, the scrolling caption column and the bottom tools are three
 * independent layers: hiding tools never measures the video or the captions again, and a lookup is
 * a fourth layer owned by the learning package.
 */
@Composable
internal fun VideoScreen(
    vm: VideoViewModel,
    settings: AppSettings,
    speech: PronunciationPlayer,
    onBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape) { vm.onOrientation(landscape) }

    val snackbar = remember { SnackbarHostState() }
    val notice = state.cacheNotice
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val text = notice?.let { stringResource(it.messageResource()) }
    LaunchedEffect(notice, text, settingsOpen) {
        if (text != null && (notice != VideoCacheNotice.Failed || !settingsOpen)) {
            snackbar.showSnackbar(text)
            if (notice != VideoCacheNotice.Failed) vm.consumeCacheNotice()
        }
    }

    val modeFailureText = stringResource(R.string.video_ui_audio_save_failed)
    LaunchedEffect(state.modeSaveFailed, settingsOpen) {
        if (state.modeSaveFailed && !settingsOpen) {
            snackbar.showSnackbar(modeFailureText)
            vm.dismissModeSaveFailure()
        }
    }

    Box(Modifier.fillMaxSize().background(if (landscape && !state.audioOnly) Color.Black else MaterialTheme.colorScheme.background)) {
        if (landscape && !state.audioOnly) {
            LandscapePlayer(
                state = state,
                player = vm.player,
                vm = vm,
                onBack = onBack,
                onSettings = { settingsOpen = true },
                onExitFullscreen = onExitFullscreen
            )
        } else {
            PortraitPlayer(
                state = state,
                player = vm.player,
                vm = vm,
                onBack = onBack,
                onSettings = { settingsOpen = true },
                onFullscreen = if (landscape) onExitFullscreen else onEnterFullscreen
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (landscape && !state.audioOnly) 8.dp else PortraitToolsHeight + CaptionControlsGap)
        )
    }

    if (settingsOpen) {
        VideoSettingsSheet(
            state = state,
            vm = vm,
            onDismiss = { settingsOpen = false }
        )
    }

    val lookup = state.lookup
    if (lookup != null) {
        key(lookup.context) {
            LearningPopup(
                context = lookup.context,
                settings = settings,
                session = vm.learningSession,
                dictionary = vm.dictionary,
                speech = speech,
                onClose = { vm.closeLookup() },
                onCancel = { vm.closeLookup() },
                onAdjust = null,
                onReplay = vm::replayLookupCue,
                onResume = if (state.lookupPausedPlayback) vm::resumeLookupPlayback else null,
                onDictionaryFailure = vm::recordDictionaryFailure,
                placement = if (landscape) LearningPopupPlacement.Center else LearningPopupPlacement.Bottom,
                onReadableContent = { readable -> vm.onLookupReadable(lookup.context, readable) }
            )
        }
    }
}

@Composable
private fun PortraitPlayer(
    state: VideoPlaybackState,
    player: Player,
    vm: VideoViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            VideoPhase.Idle, VideoPhase.Loading -> {
                Column(Modifier.fillMaxSize()) {
                    VideoTopBar(state.item?.title.orEmpty(), onBack, onSettings, true,
                        hasItem = state.item != null, landscape = false,
                        trailingIcon = Icons.Outlined.Fullscreen,
                        trailingDescription = stringResource(R.string.video_ui_fullscreen),
                        onTrailing = onFullscreen)
                    LoadingPanel(Modifier.weight(1f).fillMaxWidth())
                }
            }
            is VideoPhase.Failed -> {
                Column(Modifier.fillMaxSize()) {
                    VideoTopBar(state.item?.title.orEmpty(), onBack, onSettings, true,
                        hasItem = state.item != null, landscape = false,
                        trailingIcon = Icons.Outlined.Fullscreen,
                        trailingDescription = stringResource(R.string.video_ui_fullscreen),
                        onTrailing = onFullscreen)
                    FailureContent(state, phase.kind, vm, landscape = false, Modifier.weight(1f).fillMaxWidth())
                }
            }
            VideoPhase.Ready -> {
                val configuration = LocalConfiguration.current
                val videoHeight = minOf(
                    configuration.screenWidthDp.dp / state.aspectRatio.coerceAtLeast(0.2f),
                    (configuration.screenHeightDp * 0.45f).dp
                )
                Column(Modifier.fillMaxSize()) {
                    VideoTopBar(state.item?.title.orEmpty(), onBack, onSettings, true,
                        hasItem = state.item != null, landscape = false,
                        trailingIcon = Icons.Outlined.Fullscreen,
                        trailingDescription = stringResource(R.string.video_ui_fullscreen),
                        onTrailing = onFullscreen)
                    if (!state.audioOnly) {
                        Box(Modifier.fillMaxWidth().height(videoHeight).background(Color.Black)) {
                            PlayerSurface(player, Modifier.matchParentSize())
                            PictureTapLayer(vm, Modifier.matchParentSize())
                        }
                    }
                    CaptionPanel(
                        state = state,
                        vm = vm,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
                if (state.toolsVisible || state.audioOnly) {
                    PortraitTools(
                        state = state,
                        onSeek = vm::seekTo,
                        onPrevious = vm::previousCue,
                        onTogglePlay = vm::togglePlay,
                        onNext = vm::nextCue,
                        onSpeed = vm::setSpeed,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapePlayer(
    state: VideoPlaybackState,
    player: Player,
    vm: VideoViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val phase = state.phase) {
            VideoPhase.Idle, VideoPhase.Loading -> {
                LoadingPanel(Modifier.fillMaxSize(), onBlack = true)
            }
            is VideoPhase.Failed -> {
                FailureContent(state, phase.kind, vm, landscape = true, Modifier.fillMaxSize())
            }
            VideoPhase.Ready -> {
                PlayerSurface(player, Modifier.matchParentSize())
                PictureTapLayer(vm, Modifier.matchParentSize())
                LandscapeCaptions(state, vm)
                if (state.toolsVisible) {
                    LandscapeChrome(
                        state = state,
                        vm = vm,
                        onBack = onBack,
                        onSettings = onSettings,
                        onExitFullscreen = onExitFullscreen
                    )
                }
            }
        }
        if (state.phase !is VideoPhase.Ready) {
            VideoTopBar(state.item?.title.orEmpty(), onBack, onSettings, true,
                hasItem = state.item != null, landscape = true,
                trailingIcon = Icons.Outlined.FullscreenExit,
                trailingDescription = stringResource(R.string.video_ui_exit_fullscreen),
                onTrailing = onExitFullscreen)
        }
    }
}

@Composable
private fun VideoTopBar(
    title: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    controlsVisible: Boolean,
    hasItem: Boolean,
    landscape: Boolean,
    trailingIcon: ImageVector,
    trailingDescription: String,
    onTrailing: () -> Unit
) {
    if (!controlsVisible) return
    val foreground = if (landscape) Color.White else MaterialTheme.colorScheme.onSurface
    val container = if (landscape) VideoScrim else MaterialTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth()
            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier.statusBarsPadding())
            .background(container)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.video_ui_back),
                    tint = foreground)
            }
            Text(
                title.ifBlank { stringResource(R.string.video_ui_title) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onSettings, enabled = hasItem, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.video_ui_settings),
                    tint = if (hasItem) foreground else foreground.copy(alpha = 0.38f))
            }
            IconButton(onClick = onTrailing, modifier = Modifier.size(48.dp)) {
                Icon(trailingIcon, contentDescription = trailingDescription, tint = foreground)
            }
        }
        if (!landscape) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PictureTapLayer(vm: VideoViewModel, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val description = stringResource(R.string.video_ui_toggle_controls)
    Box(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description
            ) { vm.toggleTools() }
            .semantics { contentDescription = description }
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: Player, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = modifier
    )
}

@Composable
private fun LoadingPanel(modifier: Modifier, onBlack: Boolean = false) {
    val color = if (onBlack) Color.White else MaterialTheme.colorScheme.onBackground
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = if (onBlack) Color.White else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.video_ui_loading), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A media failure is never dressed as a playable surface. The stored title and any readable cached
 * subtitles remain; there is no transport, no seek and no synthetic progress.
 */
@Composable
private fun FailureContent(
    state: VideoPlaybackState,
    kind: VideoFailureKind,
    vm: VideoViewModel,
    landscape: Boolean,
    modifier: Modifier
) {
    val cues = state.captions?.cues.orEmpty()
    val frame = if (landscape) {
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 16.dp)
    } else {
        modifier.fillMaxWidth().padding(24.dp)
    }
    Column(frame, horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.audioOnly && !state.audioAvailable) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.video_ui_audio_unavailable))
            TextButton(onClick = { vm.setAudioOnly(false) }) { Text(stringResource(R.string.video_ui_watch_video)) }
            Spacer(Modifier.weight(1f))
            return@Column
        }
        if (cues.isEmpty()) {
            Spacer(Modifier.weight(1f))
            FailureMessage(kind, vm::retryLoad)
            Spacer(Modifier.weight(1f))
        } else {
            FailureMessage(kind, vm::retryLoad)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.video_ui_cached_captions_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            state.captions?.let { document ->
                val uiLocale = LocalConfiguration.current.locales[0]
                Text(
                    stringResource(
                        R.string.video_ui_track_label,
                        localizedLanguageName(document.language, uiLocale)
                            ?: stringResource(R.string.video_ui_unknown_language),
                        stringResource(
                            if (document.automatic) R.string.video_ui_track_auto else R.string.video_ui_track_manual
                        )
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(cues, key = { it.id }) { cue ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            formatClock(cue.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(52.dp).padding(top = 2.dp)
                        )
                        Text(
                            cue.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureMessage(kind: VideoFailureKind, onRetry: () -> Unit) {
    Text(
        stringResource(videoFailureMessage(kind)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    if (kind != VideoFailureKind.InvalidLink) {
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.video_ui_retry))
        }
    }
}

internal fun videoFailureMessage(kind: VideoFailureKind): Int = when (kind) {
    VideoFailureKind.InvalidLink -> R.string.video_ui_error_invalid_link
    VideoFailureKind.Network -> R.string.video_ui_error_network
    VideoFailureKind.Unavailable -> R.string.video_ui_error_unavailable
    VideoFailureKind.Restricted -> R.string.video_ui_error_restricted
    VideoFailureKind.Unsupported -> R.string.video_ui_error_unsupported
    VideoFailureKind.Captions -> R.string.video_ui_error_captions
    VideoFailureKind.Provider -> R.string.video_ui_error_provider
    VideoFailureKind.Storage -> R.string.video_ui_error_storage
}

@Composable
internal fun PortraitTools(
    state: VideoPlaybackState,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().height(PortraitToolsHeight).padding(horizontal = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactClock(formatClock(state.positionMs))
                SeekBar(state, onSeek, compact = true, modifier = Modifier.weight(1f))
                CompactClock(if (state.durationMs > 0L) formatClock(state.durationMs) else "--:--")
            }
            // Absolute centring keeps the transport still when the speed label changes width.
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    CueButton(
                        readerTransportPrevious,
                        stringResource(R.string.video_ui_previous_subtitle),
                        enabled = previousCueIndex(state.displayedCues, state.positionMs) != null,
                        onClick = onPrevious
                    )
                    PlayButton(state, onTogglePlay, size = 48.dp)
                    CueButton(
                        readerTransportNext,
                        stringResource(R.string.video_ui_next_subtitle),
                        enabled = nextCueIndex(state.displayedCues, state.positionMs) != null,
                        onClick = onNext
                    )
                }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    SpeedControl(state.speed, onSpeed, compact = true)
                }
            }
        }
    }
}

@Composable
private fun LandscapeChrome(
    state: VideoPlaybackState,
    vm: VideoViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    // One full-screen layer: the transport is centered against the screen itself, not against the
    // unequal space left between the top and bottom chrome.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            VideoTopBar(
                title = state.item?.title.orEmpty(),
                onBack = onBack,
                onSettings = onSettings,
                controlsVisible = true,
                hasItem = state.item != null,
                landscape = true,
                trailingIcon = Icons.Outlined.FullscreenExit,
                trailingDescription = stringResource(R.string.video_ui_exit_fullscreen),
                onTrailing = onExitFullscreen
            )
        }
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CueButton(
                Icons.Outlined.SkipPrevious,
                stringResource(R.string.video_ui_previous_subtitle),
                enabled = previousCueIndex(state.displayedCues, state.positionMs) != null,
                onDark = true
            ) { vm.previousCue() }
            PlayButton(state, vm::togglePlay, size = LandscapeTransportSize, overlay = true)
            CueButton(
                Icons.Outlined.SkipNext,
                stringResource(R.string.video_ui_next_subtitle),
                enabled = nextCueIndex(state.displayedCues, state.positionMs) != null,
                onDark = true
            ) { vm.nextCue() }
        }
        Row(
            Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .heightIn(min = LandscapeFooterHeight)
                .background(VideoScrim)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimeText(state.positionMs, state.durationMs, color = Color.White)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { SeekBar(state, vm::seekTo, light = true) }
            Spacer(Modifier.width(12.dp))
            SpeedControl(state.speed, vm::setSpeed, light = true)
        }
    }
}

@Composable
private fun CompactClock(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
private fun TimeText(positionMs: Long, durationMs: Long, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Text(
        stringResource(
            R.string.video_ui_time_position,
            formatClock(positionMs),
            if (durationMs > 0L) formatClock(durationMs) else "--:--"
        ),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    state: VideoPlaybackState,
    onSeek: (Long) -> Unit,
    light: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val enabled = duration > 0L
    val value = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, duration.coerceAtLeast(1L).toFloat())
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = if (light) Color.White else MaterialTheme.colorScheme.onSurface,
        activeTrackColor = if (light) Color.White else MaterialTheme.colorScheme.onSurface,
        inactiveTrackColor = if (light) Color.White.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outlineVariant
    )
    Slider(
        value = if (enabled) value else 0f,
        onValueChange = { if (enabled) scrub = it },
        onValueChangeFinished = {
            scrub?.let { onSeek(it.toLong()) }
            scrub = null
        },
        enabled = enabled,
        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
        modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 36.dp),
        colors = colors,
        interactionSource = interaction,
        thumb = {
            // Fill the bounded touch row so Material's thumb slot cannot top-align the circle.
            if (compact) Box(Modifier.width(10.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f), CircleShape))
            } else SliderDefaults.Thumb(interactionSource = interaction, colors = colors, enabled = enabled)
        },
        track = { slider ->
            if (compact) SliderDefaults.Track(
                sliderState = slider,
                modifier = Modifier.height(3.dp),
                colors = colors,
                enabled = enabled,
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null
            ) else SliderDefaults.Track(sliderState = slider, colors = colors, enabled = enabled)
        }
    )
}

@Composable
private fun SpeedControl(speed: Float, onSpeed: (Float) -> Unit, light: Boolean = false, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val label = speedText(speed)
    val description = stringResource(R.string.video_ui_speed)
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .then(if (compact) Modifier.width(72.dp) else Modifier)
                .semantics { contentDescription = "$description, $label" },
            contentPadding = if (compact) PaddingValues(horizontal = 4.dp) else ButtonDefaults.TextButtonContentPadding
        ) {
            Text(
                label,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                color = if (light) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            videoSpeeds.forEach { option ->
                DropdownMenuItem(
                    text = { Text(speedText(option)) },
                    onClick = { open = false; onSpeed(option) },
                    trailingIcon = if (option == speed) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun CueButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onDark: Boolean = false,
    onClick: () -> Unit
) {
    val base = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) base else base.copy(alpha = 0.32f)
        )
    }
}

@Composable
private fun PlayButton(state: VideoPlaybackState, onTogglePlay: () -> Unit, size: Dp, overlay: Boolean = false) {
    val ended = state.ended
    val playing = state.playRequested && !ended
    val icon = if (overlay) {
        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
    } else {
        if (playing) readerTransportPause else readerTransportPlay
    }
    val description = stringResource(
        when {
            ended -> R.string.video_ui_replay
            playing -> R.string.video_ui_pause
            else -> R.string.video_ui_play
        }
    )
    if (overlay) {
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(size).background(VideoScrim, CircleShape)
        ) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.46f))
        }
    } else {
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(size)) {
            Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CaptionPanel(
    state: VideoPlaybackState,
    vm: VideoViewModel,
    modifier: Modifier
) {
    val cues = state.displayedCues
    val listState = rememberLazyListState()
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    var follow by rememberSaveable { mutableStateOf(true) }
    var followRequest by remember { mutableIntStateOf(0) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val activeIndex = activeCueIndex(cues, state.positionMs)
    // Gaps have no highlighted cue, but a seek still brings the nearby transcript into view.
    val followIndex = activeIndex ?: navigationCueIndex(cues, state.positionMs)
        ?: 0.takeIf { cues.isNotEmpty() }
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(followIndex, follow, followRequest, state.selectedTrackId, cues.size, viewportHeight) {
        if (!follow || dragging || followIndex == null) return@LaunchedEffect
        val offset = (viewportHeight * 0.26f).toInt().coerceAtLeast(0)
        listState.animateScrollToItem(followIndex, scrollOffset = -offset)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.video_ui_captions),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    follow = true
                    vm.interact()
                    followRequest++
                },
                enabled = cues.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.video_ui_follow),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            state.captionStatus == VideoCaptionStatus.Loading -> CaptionNotice(
                text = stringResource(R.string.video_ui_caption_loading),
                loading = true,
                modifier = Modifier.weight(1f)
            )
            state.captionStatus == VideoCaptionStatus.NoEnglish -> CaptionNotice(
                text = stringResource(R.string.video_ui_no_english_captions),
                loading = false,
                modifier = Modifier.weight(1f)
            )
            state.captionStatus == VideoCaptionStatus.Absent || cues.isEmpty() && state.captionStatus == VideoCaptionStatus.Ready ->
                CaptionNotice(
                    text = stringResource(R.string.video_ui_no_captions),
                    loading = false,
                    modifier = Modifier.weight(1f)
                )
            state.captionStatus == VideoCaptionStatus.Failed -> CaptionFailure(
                text = stringResource(R.string.video_ui_caption_failed),
                onRetry = vm::retryCaptions,
                modifier = Modifier.weight(1f)
            )
            cues.isEmpty() -> CaptionNotice(
                text = stringResource(R.string.video_ui_caption_loading),
                loading = true,
                modifier = Modifier.weight(1f)
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = PortraitToolsHeight + CaptionControlsGap + navigationBottom
                )
            ) {
                itemsIndexed(cues, key = { _, cue -> cue.id }) { index, cue ->
                    val current = index == activeIndex
                    val selection = state.lookup?.takeIf { it.cueId == cue.id }?.selection
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .background(
                                if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier.width(52.dp)
                                .heightIn(min = 48.dp)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.video_ui_seek)
                                ) { vm.playCue(index) }
                                .padding(top = 1.dp)
                        ) {
                            Text(
                                (if (cue.timingApproximate) "~ " else "") + formatClock(cue.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        CaptionText(
                            cue = cue,
                            selected = selection,
                            highlight = MaterialTheme.colorScheme.primaryContainer,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            onLookup = { vm.openLookup(cue.id, it) },
                            onBlankTap = { vm.seekToCue(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptionNotice(text: String, loading: Boolean, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CaptionFailure(text: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.video_ui_retry_captions))
        }
    }
}

@Composable
private fun LandscapeCaptions(state: VideoPlaybackState, vm: VideoViewModel) {
    val cues = state.displayedCues
    val index = activeCueIndex(cues, state.positionMs) ?: return
    val cue = cues.getOrNull(index) ?: return
    val selection = state.lookup?.takeIf { it.cueId == cue.id }?.selection
    val tapBlocker = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        val bottom = LandscapeFooterHeight + CaptionControlsGap +
            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val captionHeight = (maxHeight / 2 - LandscapeTransportSize / 2 -
            CaptionControlsGap - bottom).coerceAtLeast(1.dp)
        val compact = maxHeight < 360.dp
        Surface(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .padding(bottom = bottom)
                .heightIn(max = captionHeight)
                .clickable(interactionSource = tapBlocker, indication = null) { },
            shape = RoundedCornerShape(8.dp),
            color = CaptionScrim,
            contentColor = Color.White
        ) {
            // Reserve the same lane with chrome shown or hidden. A long source cue scrolls rather
            // than drawing through the central transport or losing words to an ellipsis.
            key(state.selectedTrackId, cue.id, cue.text) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        formatClock(cue.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    CaptionText(
                        cue = cue,
                        selected = selection,
                        highlight = Color.White.copy(alpha = 0.24f),
                        color = Color.White,
                        fontSize = if (compact) 16.sp else 19.sp,
                        lineHeight = if (compact) 19.sp else 26.sp,
                        modifier = Modifier.weight(1f),
                        onLookup = { vm.openLookup(cue.id, it) },
                        onBlankTap = null
                    )
                }
            }
        }
    }
}

/**
 * One caption line. Tap resolves the word under the finger and looks it up; a blank tap seeks only
 * when the caller says so. Long press selects the word, and dragging extends the phrase; the gesture
 * consumes the selection so a lookup can never also seek or toggle the player chrome.
 */
@Composable
private fun CaptionText(
    cue: CaptionCue,
    selected: CaptionSelection?,
    highlight: Color,
    /** Explicit, because a bare Box does not carry Material's LocalContentColor. */
    color: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 23.sp,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    onLookup: (CaptionSelection) -> Unit,
    onBlankTap: (() -> Unit)?
) {
    var layout by remember(cue.id) { mutableStateOf<TextLayoutResult?>(null) }
    var dragging by remember(cue.id) { mutableStateOf<CaptionSelection?>(null) }
    val shown = dragging ?: selected
    val text = remember(cue.text, shown, highlight) {
        buildAnnotatedString {
            append(cue.text)
            if (shown != null && shown.start in 0..shown.end && shown.end <= cue.text.length) {
                addStyle(SpanStyle(background = highlight), shown.start, shown.end)
            }
        }
    }
    Text(
        text,
        modifier = modifier.pointerInput(cue.id, layout) {
            val layoutResult = layout ?: return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val anchorOffset = layoutResult.getOffsetForPosition(down.position)
                var longPressed = false
                var moved = false
                var released = false
                var selection: CaptionSelection? = null
                val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                while (true) {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    val event = if (!longPressed && remaining > 0L) {
                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    } else {
                        awaitPointerEvent()
                    }
                    if (event == null) {
                        longPressed = true
                        selection = wordSelectionAt(cue.text, anchorOffset)
                        dragging = selection
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    if (!longPressed && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        break
                    }
                    if (longPressed) {
                        val focus = layoutResult.getOffsetForPosition(change.position)
                        selection = phraseSelection(cue.text, anchorOffset, focus)
                        dragging = selection
                        change.consume()
                    }
                }
                dragging = null
                when {
                    longPressed -> selection?.let(onLookup)
                    released && !moved -> {
                        val candidate = wordSelectionAt(
                            cue.text,
                            layoutResult.getOffsetForPosition(down.position)
                        )
                        // Compose clamps a tap past the end of a short line to the nearest
                        // character; only a point inside a rendered glyph may open a lookup.
                        val glyphs = candidate?.let { selection ->
                            (selection.start until selection.end).map { layoutResult.getBoundingBox(it) }
                        }.orEmpty()
                        val word = wordHit(candidate, glyphs, down.position)
                        if (word != null) onLookup(word) else onBlankTap?.invoke()
                    }
                }
            }
        },
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSettingsSheet(
    state: VideoPlaybackState,
    vm: VideoViewModel,
    onDismiss: () -> Unit
) {
    var confirmClearAll by remember { mutableStateOf(false) }
    var tracksExpanded by remember { mutableStateOf(false) }
    val audioLabel = stringResource(R.string.video_ui_audio_only)
    val uiLocale = LocalConfiguration.current.locales[0]
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.video_ui_settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.video_ui_audio_only), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when {
                        state.modeSaving -> R.string.video_ui_audio_saving
                        state.audioAvailable -> R.string.video_ui_audio_detail
                        else -> R.string.video_ui_audio_unavailable
                    }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.audioOnly, onCheckedChange = vm::setAudioOnly,
                    modifier = Modifier.semantics { contentDescription = audioLabel },
                    enabled = !state.modeSaving && (state.audioOnly || state.audioAvailable && state.phase is VideoPhase.Ready))
            }
            if (state.modeSaveFailed) Text(stringResource(R.string.video_ui_audio_save_failed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            MergeSettings(state, vm)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.video_ui_subtitle_track),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            if (state.tracks.isEmpty()) {
                Text(
                    stringResource(R.string.video_ui_no_tracks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                val selectedTrack = state.tracks.firstOrNull { it.id == state.selectedTrackId }
                TextButton(onClick = { tracksExpanded = !tracksExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.video_ui_change_subtitles,
                        selectedTrack?.let { localizedLanguageName(it.language, uiLocale) }
                            ?: stringResource(R.string.video_ui_unknown_language)))
                }
                if (tracksExpanded) Column(Modifier.fillMaxWidth().selectableGroup()) {
                    state.tracks.forEach { track ->
                        val selected = track.id == state.selectedTrackId
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = selected, role = Role.RadioButton) { vm.selectTrack(track) }
                                .heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    localizedLanguageName(track.language, uiLocale)
                                        ?: stringResource(R.string.video_ui_unknown_language),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    when {
                                        track.translatedFrom != null -> stringResource(
                                            R.string.video_ui_track_translated,
                                            localizedLanguageName(track.translatedFrom, uiLocale)
                                                ?: stringResource(R.string.video_ui_unknown_language)
                                        )
                                        track.automatic -> stringResource(R.string.video_ui_track_auto)
                                        else -> stringResource(R.string.video_ui_track_manual)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (state.qualities.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.video_ui_default_quality, state.preferredHeight),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(R.string.video_ui_quality_remembered),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    state.qualities.forEach { quality ->
                        val selected = quality.id == state.selectedQualityId
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = !state.qualityChanging && !state.modeSaving,
                                    role = Role.RadioButton
                                ) { vm.selectQuality(quality) }
                                .heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (quality.height > 0) {
                                    stringResource(R.string.video_ui_quality_label, quality.height)
                                } else {
                                    quality.id
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                if (state.qualityChanging) {
                    Text(
                        stringResource(R.string.video_ui_quality_switching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.video_ui_cache_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.video_ui_cache_merged, formatCacheBytes(state.cacheMergedBytes)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.video_ui_cache_all, formatCacheBytes(state.cacheAllBytes)),
                style = MaterialTheme.typography.bodyMedium
            )
            state.cacheNotice?.let { notice ->
                Text(
                    stringResource(notice.messageResource()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notice == VideoCacheNotice.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { vm.clearCaptionCache(mergedOnly = true) },
                    enabled = state.cacheMergedBytes > 0L,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.video_ui_clear_merged)) }
                TextButton(
                    onClick = { confirmClearAll = true },
                    enabled = state.cacheAllBytes > 0L,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.video_ui_clear_all)) }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.video_ui_done))
            }
        }
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.video_ui_clear_all_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.video_ui_clear_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    vm.clearCaptionCache(mergedOnly = false)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.video_ui_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.video_ui_cancel))
                }
            }
        )
    }
}

@Composable
private fun MergeSettings(state: VideoPlaybackState, vm: VideoViewModel) {
    val hasSubtitles = state.captions != null && state.phase is VideoPhase.Ready
    val regenerate: (() -> Unit)? = if (hasSubtitles) vm::regenerateMerged else null
    val regenerateLabel = if (hasSubtitles) stringResource(R.string.video_ui_merge_regenerate) else null
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.video_ui_merge_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.video_ui_merge_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        val sentenceLabel = stringResource(R.string.video_ui_merge_title)
        Switch(
            modifier = Modifier.semantics { contentDescription = sentenceLabel },
            checked = state.mergeEnabled,
            onCheckedChange = vm::setMergeEnabled,
            enabled = hasSubtitles
        )
    }
    if (!hasSubtitles) {
        Text(
            stringResource(R.string.video_ui_merge_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    when (state.mergePhase) {
        VideoMergePhase.ReadingCache -> MergeStatus(stringResource(R.string.video_ui_merge_reading))
        VideoMergePhase.Generating -> {
            val progress = state.mergeProgress
            MergeStatus(
                if (progress != null) {
                    stringResource(R.string.video_ui_merge_generating, progress.first, progress.second)
                } else {
                    stringResource(R.string.video_ui_merge_generating, 0, 0)
                },
                loading = true
            )
        }
        VideoMergePhase.Available -> MergeStatus(
            stringResource(
                if (state.mergedUnchanged) R.string.video_ui_merge_unchanged
                else R.string.video_ui_merge_available
            ),
            action = regenerateLabel,
            onAction = regenerate
        )
        VideoMergePhase.Failed -> {
            MergeStatus(
                stringResource(R.string.video_ui_merge_failed),
                error = true,
                action = regenerateLabel,
                onAction = regenerate
            )
        }
        VideoMergePhase.Stale -> {
            MergeStatus(
                stringResource(R.string.video_ui_merge_stale),
                action = regenerateLabel,
                onAction = regenerate
            )
        }
        VideoMergePhase.Off -> Unit
    }
}

@Composable
private fun MergeStatus(
    text: String,
    loading: Boolean = false,
    error: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

private fun VideoCacheNotice.messageResource(): Int = when (this) {
    VideoCacheNotice.MergedCleared -> R.string.video_ui_cache_merged_cleared
    VideoCacheNotice.AllCleared -> R.string.video_ui_cache_all_cleared
    VideoCacheNotice.Failed -> R.string.video_ui_cache_clear_failed
}
