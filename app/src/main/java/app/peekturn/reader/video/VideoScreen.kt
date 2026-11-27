package app.peekturn.reader.video

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
import app.peekturn.reader.ui.PeekturnIconButton
import app.peekturn.reader.learning.LearningPopup
import app.peekturn.reader.learning.LearningPopupPlacement
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.reader.ReaderTypographySheet
import app.peekturn.reader.reader.ReaderTextMetrics
import app.peekturn.reader.reader.readerTextMetrics
import app.peekturn.reader.reader.rememberReaderFontFamily
import app.peekturn.reader.ui.SettingsDisclosure
import app.peekturn.reader.ui.activeSentenceBackground
import app.peekturn.reader.speech.PronunciationPlayer
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

private val VideoScrim = Color.Black.copy(alpha = 0.68f)
private val CaptionScrim = Color.Black.copy(alpha = 0.66f)
private val LandscapeTransportSize = 76.dp
private val LandscapeFooterHeight = 56.dp
private val CaptionControlsGap = 8.dp
// Two 48dp touch rows; the system navigation inset is added separately.
internal val PortraitToolsHeight = 96.dp
internal val ReadingToolsHeight = 56.dp

/**
 * A's native surface. The picture, the scrolling caption column and the bottom tools are three
 * independent layers: hiding tools never measures the video or the captions again, and a lookup is
 * a fourth layer owned by the learning package.
 */
@Composable
internal fun VideoScreen(
    window: Window,
    vm: VideoViewModel,
    state: VideoPlaybackState,
    fullscreen: Boolean,
    settings: AppSettings,
    speech: PronunciationPlayer,
    onBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
    playlist: VideoPlaylist? = null,
    episodes: List<VideoItem> = emptyList(),
    onEpisode: (Long) -> Unit = {}
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(fullscreen) { vm.onFullscreenChanged(fullscreen) }
    val inlineState = key(state.item?.id) { rememberSaveableStateHolder() }

    var choosingEpisode by rememberSaveable(playlist?.id) { mutableStateOf(false) }
    val onEpisodes: (() -> Unit)? = if (episodes.isEmpty()) null else { { choosingEpisode = true } }
    val snackbar = remember { SnackbarHostState() }
    val notice = state.cacheNotice
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var savingAppearance by remember { mutableStateOf(false) }
    var appearanceSaveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val appearanceFailure = stringResource(R.string.video_ui_appearance_save_failed)
    val text = notice?.let { stringResource(it.messageResource()) }
    LaunchedEffect(notice, text, settingsOpen) {
        if (text != null && (notice != VideoCacheNotice.Failed || !settingsOpen)) {
            snackbar.showSnackbar(text)
            if (notice != VideoCacheNotice.Failed) vm.consumeCacheNotice()
        }
    }

    val resumeFailureText = stringResource(R.string.video_ui_resume_save_failed)
    LaunchedEffect(state.resumeSaveFailed) {
        if (state.resumeSaveFailed) {
            snackbar.showSnackbar(resumeFailureText)
            vm.dismissResumeSaveFailure()
        }
    }
    val watchSwitchFailure = state.watchSwitchFailure
    val watchFailureText = watchSwitchFailure?.let {
        stringResource(R.string.video_ui_watch_switch_failed, stringResource(videoFailureMessage(it)))
    }
    LaunchedEffect(watchSwitchFailure, watchFailureText) {
        if (watchFailureText != null) {
            snackbar.showSnackbar(watchFailureText)
            vm.dismissWatchSwitchFailure()
        }
    }
    val modeFailureText = stringResource(R.string.video_ui_audio_save_failed)
    LaunchedEffect(state.modeSaveFailed, settingsOpen) {
        if (state.modeSaveFailed && !settingsOpen) {
            snackbar.showSnackbar(modeFailureText)
            vm.dismissModeSaveFailure()
        }
    }

    Box(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)) {
        if (fullscreen) {
            FullscreenPlayer(
                window = window,
                state = state,
                player = vm.player,
                vm = vm,
                onSettings = { settingsOpen = true },
                onEpisodes = onEpisodes,
                onExitFullscreen = onExitFullscreen
            )
        } else {
            inlineState.SaveableStateProvider("transcript") {
                PortraitPlayer(
                    state = state,
                    reading = settings.reading,
                    player = vm.player,
                    vm = vm,
                    onBack = onBack,
                    onSettings = { settingsOpen = true },
                    onEpisodes = onEpisodes,
                    onFullscreen = onEnterFullscreen.takeUnless { state.audioOnly }
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (fullscreen) 8.dp else PortraitToolsHeight + CaptionControlsGap)
        )
    }

    if (choosingEpisode && playlist != null) {
        EpisodeSheet(playlist.title, episodes, state.item?.id, onDismiss = { choosingEpisode = false }) { id ->
            choosingEpisode = false
            if (id != state.item?.id) onEpisode(id)
        }
    }
    if (settingsOpen) {
        VideoSettingsSheet(
            state = state,
            vm = vm,
            onDismiss = { settingsOpen = false },
            downloadState = downloads[state.item?.id],
            onReadingAppearance = if (state.readMode) ({ settingsOpen = false; appearanceSaveFailed = false; appearanceOpen = true }) else null
        )
    }
    if (appearanceOpen) {
        ReaderTypographySheet(
            settings = settings,
            saving = savingAppearance,
            saveError = if (appearanceSaveFailed) appearanceFailure else null,
            onApply = { reading, theme ->
                if (!savingAppearance) {
                    savingAppearance = true
                    appearanceSaveFailed = false
                    scope.launch {
                        try {
                            if (vm.saveReadingAppearance(reading, theme)) appearanceOpen = false
                            else appearanceSaveFailed = true
                        } finally { savingAppearance = false }
                    }
                }
            },
            onDismiss = { if (!savingAppearance) appearanceOpen = false }
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
                onClose = vm::dismissLookup,
                onCancel = vm::dismissLookup,
                onAdjust = null,
                initialDictionary = (state.phase as? VideoPhase.Failed)?.kind == VideoFailureKind.Network,
                onReplay = if (state.phase == VideoPhase.Ready) vm::replayLookupCue else null,
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
    reading: ReaderPreferences,
    player: Player,
    vm: VideoViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onEpisodes: (() -> Unit)?,
    onFullscreen: (() -> Unit)?
) {
    var locateRequest by rememberSaveable(state.item?.id) { mutableIntStateOf(0) }
    fun locateCaption() { locateRequest++ }
    val previous = { vm.previousCue(); locateCaption() }
    val next = { vm.nextCue(); locateCaption() }
    Box(Modifier.fillMaxSize()) {
        val ready = state.phase == VideoPhase.Ready
        when {
            state.phase is VideoPhase.Failed -> {
                Column(Modifier.fillMaxSize()) {
                    VideoTopBar(state.item?.title.orEmpty(), onBack, onSettings, true, onEpisodes = onEpisodes,
                        hasItem = state.item != null, landscape = false,
                        trailingIcon = PeekturnIcons.Fullscreen,
                        trailingDescription = stringResource(R.string.video_ui_fullscreen),
                        onTrailing = onFullscreen)
                    FailureContent(state, state.phase.kind, vm, landscape = false, Modifier.weight(1f).fillMaxWidth())
                }
            }
            !ready && state.item == null -> {
                Column(Modifier.fillMaxSize()) {
                    VideoTopBar("", onBack, onSettings, true, onEpisodes = onEpisodes,
                        hasItem = false, landscape = false,
                        trailingIcon = PeekturnIcons.Fullscreen,
                        trailingDescription = stringResource(R.string.video_ui_fullscreen),
                        onTrailing = onFullscreen)
                    LoadingPanel(Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> {
                val configuration = LocalConfiguration.current
                val videoHeight = minOf(
                    configuration.screenWidthDp.dp / state.aspectRatio.coerceAtLeast(0.2f),
                    (configuration.screenHeightDp * 0.45f).dp
                )
                val topBar: @Composable (Boolean) -> Unit = { applyInsets ->
                    VideoTopBar(state.item?.title.orEmpty(), onBack, onSettings, true, onEpisodes = onEpisodes,
                        hasItem = ready, landscape = false,
                        trailingIcon = if (state.readMode) PeekturnIcons.Watch else PeekturnIcons.Fullscreen,
                        trailingDescription = stringResource(if (state.readMode) R.string.video_ui_watch_video else R.string.video_ui_fullscreen),
                        onTrailing = if (state.readMode) { { vm.setReadMode(false) } } else onFullscreen,
                        trailingEnabled = !state.modeSaving && (!state.readMode || ready), trailingBusy = state.modeSaving,
                        applySystemInsets = applyInsets)
                }
                val captions: @Composable (Modifier) -> Unit = { modifier ->
                    CaptionPanel(state, reading, vm, locateRequest,
                        onPlayCue = { index -> vm.playCue(index); locateCaption() }, modifier = modifier)
                }
                val transport: @Composable (Boolean) -> Unit = { applyInsets ->
                    PortraitTools(state, { vm.seekTo(it); locateCaption() }, previous, vm::togglePlay, next, vm::setSpeed,
                        onLocate = ::locateCaption,
                        applyNavigationInsets = applyInsets)
                }
                if (state.readMode) {
                    VideoReadingLayout(
                        immersive = reading.immersive,
                        toolsVisible = !ready || state.toolsVisible,
                        onToggleTools = vm::toggleTools,
                        content = { captions(Modifier.fillMaxSize()) },
                        topBar = { topBar(false) },
                        expandedTransport = {
                            if (ready) transport(false) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        },
                        compactTransport = { if (ready) VideoCompactTransport(state, previous, vm::togglePlay, next) }
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        topBar(true)
                        if (!state.audioOnly) {
                            Box(Modifier.fillMaxWidth().height(videoHeight).background(Color.Black)) {
                                if (ready) PlayerSurface(player, Modifier.matchParentSize())
                                if (!ready || state.awaitingFirstFrame) OpeningPicture(state, Modifier.matchParentSize())
                                if (ready) PictureTapLayer(vm, Modifier.matchParentSize())
                            }
                        }
                        if (state.audioOnly && !ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                        captions(Modifier.weight(1f).fillMaxWidth())
                    }
                    if (ready && (state.toolsVisible || state.audioOnly)) {
                        Box(Modifier.align(Alignment.BottomCenter)) { transport(true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenPlayer(
    window: Window,
    state: VideoPlaybackState,
    player: Player,
    vm: VideoViewModel,
    onSettings: () -> Unit,
    onEpisodes: (() -> Unit)?,
    onExitFullscreen: () -> Unit
) {
    DisposableEffect(window) {
        val original = window.attributes.screenBrightness
        onDispose {
            if (window.attributes.screenBrightness != original) {
                window.attributes = window.attributes.apply { screenBrightness = original }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val phase = state.phase) {
            VideoPhase.Idle, VideoPhase.Loading -> {
                if (state.item == null) LoadingPanel(Modifier.fillMaxSize(), onBlack = true)
                else OpeningPicture(state, Modifier.fillMaxSize())
            }
            is VideoPhase.Failed -> {
                FailureContent(state, phase.kind, vm, landscape = true, Modifier.fillMaxSize())
            }
            VideoPhase.Ready -> {
                PlayerSurface(player, Modifier.matchParentSize())
                if (state.awaitingFirstFrame) OpeningPicture(state, Modifier.matchParentSize())
                FullscreenPictureGestures(window, vm::toggleTools, Modifier.matchParentSize())
                FullscreenCaptions(state, vm::playCue, vm::openLookup)
                if (state.toolsVisible) {
                    FullscreenChrome(
                        state = state,
                        vm = vm,
                        onSettings = onSettings,
                        onEpisodes = onEpisodes,
                        onExitFullscreen = onExitFullscreen
                    )
                }
            }
        }
        if (state.phase !is VideoPhase.Ready) {
            VideoTopBar(state.item?.title.orEmpty(), onExitFullscreen, onSettings, true, onEpisodes = onEpisodes,
                hasItem = state.item != null, landscape = true,
                trailingIcon = PeekturnIcons.ExitFullscreen,
                trailingDescription = stringResource(R.string.video_ui_exit_fullscreen),
                onTrailing = onExitFullscreen)
        }
    }
}

@Composable
internal fun VideoTopBar(
    title: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    controlsVisible: Boolean,
    hasItem: Boolean,
    landscape: Boolean,
    trailingIcon: ImageVector,
    trailingDescription: String,
    onTrailing: (() -> Unit)?,
    trailingEnabled: Boolean = true,
    trailingBusy: Boolean = false,
    applySystemInsets: Boolean = true,
    onEpisodes: (() -> Unit)? = null
) {
    if (!controlsVisible) return
    val foreground = if (landscape) Color.White else MaterialTheme.colorScheme.onSurface
    val container = if (landscape) VideoScrim else MaterialTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth()
            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else if (applySystemInsets) Modifier.statusBarsPadding() else Modifier)
            .background(container)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(PeekturnIcons.Back,
                    contentDescription = stringResource(R.string.video_ui_back),
                    tint = foreground)
            }
            Column(Modifier.weight(1f).then(if (onEpisodes == null) Modifier else Modifier
                .clickable(role = Role.Button, onClick = onEpisodes).heightIn(min = 48.dp)),
                verticalArrangement = Arrangement.Center) {
                Text(title.ifBlank { stringResource(R.string.video_ui_title) },
                    style = MaterialTheme.typography.titleMedium, color = foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onEpisodes != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.playlist_episodes), style = MaterialTheme.typography.labelMedium,
                        color = foreground)
                    Icon(PeekturnIcons.ChevronDown, null, Modifier.size(16.dp), tint = foreground)
                }
            }
            IconButton(onClick = onSettings, enabled = hasItem, modifier = Modifier.size(48.dp)) {
                Icon(PeekturnIcons.Settings,
                    contentDescription = stringResource(R.string.video_ui_settings),
                    tint = if (hasItem) foreground else foreground.copy(alpha = 0.38f))
            }
            if (onTrailing != null) IconButton(onClick = onTrailing, enabled = trailingEnabled, modifier = Modifier.size(48.dp)) {
                if (trailingBusy) CircularProgressIndicator(
                    Modifier.size(20.dp).semantics { contentDescription = trailingDescription }, strokeWidth = 2.dp)
                else Icon(trailingIcon, contentDescription = trailingDescription, tint = foreground)
            }
        }
        if (!landscape) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeSheet(title: String, episodes: List<VideoItem>, selectedId: Long?,
    onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    val current = episodes.indexOfFirst { it.id == selectedId }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, Modifier.padding(horizontal = 22.dp),
            style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.playlist_library_episodes, episodes.size, episodes.size),
            Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 14.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0)),
            contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
            itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                val selected = episode.id == selectedId
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                    .selectable(selected, role = Role.RadioButton,
                        onClick = { onSelect(episode.id) }).heightIn(min = 54.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((index + 1).toString(), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.widthIn(min = 24.dp), color = color)
                    Text(episode.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = color)
                    if (selected) Icon(PeekturnIcons.Check, null, Modifier.size(16.dp), tint = color)
                    if (episode.durationMs > 0) Text(formatClock(episode.durationMs),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
private fun OpeningPicture(state: VideoPlaybackState, modifier: Modifier) {
    val poster = rememberVideoThumbnail(state.item?.thumbnailUrl)
    val loading = stringResource(R.string.video_ui_loading)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (poster != null) {
            Image(poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)))
        }
        CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = loading },
            color = Color.White, strokeWidth = 2.dp)
        if (state.positionMs > 0) {
            Surface(Modifier.align(Alignment.BottomStart).padding(8.dp),
                shape = RoundedCornerShape(4.dp), color = VideoScrim, contentColor = Color.White) {
                Text(formatClock(state.positionMs), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
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
    val cues = state.displayedCues
    val frame = if (landscape) {
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 16.dp)
    } else {
        modifier.fillMaxWidth().padding(24.dp)
    }
    Column(frame, horizontalAlignment = Alignment.CenterHorizontally) {
        if (cues.isEmpty()) Spacer(Modifier.weight(1f))
        if (state.audioOnly && !state.audioAvailable && kind == VideoFailureKind.Unsupported) {
            Text(stringResource(R.string.video_ui_audio_unavailable))
            TextButton(onClick = { vm.setAudioOnly(false) }) { Text(stringResource(R.string.video_ui_watch_video)) }
        } else {
            FailureMessage(kind, vm.recoveryVideoLink(), vm::retryLoad)
        }
        if (cues.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            val document = state.captions
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.video_ui_cached_captions_notice),
                    style = MaterialTheme.typography.labelMedium)
                if (document != null) Text(
                    stringResource(R.string.video_ui_track_label,
                        localizedLanguageName(document.language, LocalConfiguration.current.locales[0])
                            ?: stringResource(R.string.video_ui_unknown_language),
                        stringResource(if (document.automatic) R.string.video_ui_track_auto else R.string.video_ui_track_manual)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val active = activeCueIndex(cues, state.positionMs)
            val list = key(state.item?.id, state.selectedTrackId) {
                rememberCaptionListState(active ?: navigationCueIndex(cues, state.positionMs) ?: 0,
                    playRequested = false, lookupOpen = state.lookup != null, locateRequest = 0)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                itemsIndexed(cues, key = { _, cue -> cue.id }) { index, cue ->
                    VideoTranscriptRow(cue, state.lookup?.takeIf { it.cueId == cue.id }?.selection,
                        current = active == index, onPlay = null, onLookup = { vm.openLookup(cue.id, it) })
                }
            }
        }
    }
}

@Composable
internal fun FailureMessage(kind: VideoFailureKind, link: VideoLink?, onRetry: () -> Unit) {
    val context = LocalContext.current
    val verify = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onRetry()
    }
    Text(
        stringResource(videoFailureMessage(kind)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    if (kind != VideoFailureKind.InvalidLink) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == VideoFailureKind.SignInRequired && link != null) {
                Button(onClick = { openYouTubePage(context, link.watchUri()) },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.video_ui_open_youtube))
                }
            } else if (kind == VideoFailureKind.Verification && link != null) {
                TextButton(
                    onClick = {
                        verify.launch(Intent(context, VideoVerificationActivity::class.java).apply {
                            data = link.watchUri()
                        })
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.video_ui_verify_youtube)) }
            }
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.video_ui_retry))
            }
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
    VideoFailureKind.SignInRequired -> R.string.video_ui_error_sign_in
    VideoFailureKind.Verification -> R.string.video_ui_error_verification
}

@Composable
internal fun PortraitTools(
    state: VideoPlaybackState,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onLocate: () -> Unit = {},
    applyNavigationInsets: Boolean = true
) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (applyNavigationInsets) Modifier.navigationBarsPadding() else Modifier)
                .height(if (state.readMode) ReadingToolsHeight else PortraitToolsHeight).padding(horizontal = 12.dp)
        ) {
            if (!state.readMode) Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactClock(formatClock(state.positionMs))
                SeekBar(state, onSeek, compact = true, modifier = Modifier.weight(1f))
                CompactClock(if (state.durationMs > 0L) formatClock(state.durationMs) else "--:--")
            }
            Row(
                Modifier.fillMaxWidth().height(if (state.readMode) ReadingToolsHeight else 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Equal side slots keep play centred, with separate hit areas for all five actions.
                Box(Modifier.width(56.dp)) {
                    PeekturnIconButton(
                        icon = PeekturnIcons.Follow,
                        description = stringResource(R.string.video_ui_locate_caption),
                        onClick = onLocate,
                        enabled = state.displayedCues.isNotEmpty()
                    )
                }
                CueButton(PeekturnIcons.Previous, stringResource(R.string.video_ui_previous_subtitle),
                    enabled = previousCueIndex(state.displayedCues, state.positionMs) != null, onClick = onPrevious)
                PlayButton(state, onTogglePlay, size = 48.dp)
                CueButton(PeekturnIcons.Next, stringResource(R.string.video_ui_next_subtitle),
                    enabled = nextCueIndex(state.displayedCues, state.positionMs) != null, onClick = onNext)
                SpeedControl(state.speed, onSpeed, compact = true)
            }
        }
    }
}

@Composable
internal fun VideoCompactTransport(
    state: VideoPlaybackState,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit
) {
    Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CueButton(PeekturnIcons.Previous, stringResource(R.string.video_ui_previous_subtitle),
            enabled = previousCueIndex(state.displayedCues, state.positionMs) != null, onClick = onPrevious)
        PlayButton(state, onTogglePlay, size = 48.dp)
        CueButton(PeekturnIcons.Next, stringResource(R.string.video_ui_next_subtitle),
            enabled = nextCueIndex(state.displayedCues, state.positionMs) != null, onClick = onNext)
    }
}

@Composable
private fun FullscreenChrome(
    state: VideoPlaybackState,
    vm: VideoViewModel,
    onSettings: () -> Unit,
    onEpisodes: (() -> Unit)?,
    onExitFullscreen: () -> Unit
) {
    // One full-screen layer: the transport is centered against the screen itself, not against the
    // unequal space left between the top and bottom chrome.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            VideoTopBar(
                title = state.item?.title.orEmpty(),
                onBack = onExitFullscreen,
                onSettings = onSettings,
                onEpisodes = onEpisodes,
                controlsVisible = true,
                hasItem = state.item != null,
                landscape = true,
                trailingIcon = PeekturnIcons.ExitFullscreen,
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
                PeekturnIcons.Previous,
                stringResource(R.string.video_ui_previous_subtitle),
                enabled = previousCueIndex(state.displayedCues, state.positionMs) != null,
                onDark = true
            ) { vm.previousCue() }
            PlayButton(state, vm::togglePlay, size = LandscapeTransportSize, overlay = true)
            CueButton(
                PeekturnIcons.Next,
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
                .padding(horizontal = 20.dp, vertical = 4.dp),
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
                .then(if (compact) Modifier.width(56.dp) else Modifier)
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
                        { Icon(PeekturnIcons.Check, contentDescription = null) }
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
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
            .then(if (onDark) Modifier.background(VideoScrim, CircleShape) else Modifier)
    ) {
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
    val icon = if (playing) PeekturnIcons.Pause else PeekturnIcons.Play
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

/** A drag gives the reader the list until they resume playback or request the current cue. */
@Composable
internal fun rememberCaptionListState(currentIndex: Int?, playRequested: Boolean, lookupOpen: Boolean,
    locateRequest: Int): LazyListState {
    val list = rememberLazyListState()
    var browsing by rememberSaveable(locateRequest) { mutableStateOf(false) }
    var initiallyLocated by remember(list) { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) browsing = true }
    // Returning from fullscreen restores a browsed transcript unless playback actually resumed.
    val followingPlayback = playRequested && !lookupOpen
    var previouslyPlaying by rememberSaveable { mutableStateOf(followingPlayback) }
    LaunchedEffect(followingPlayback) {
        if (followingPlayback && !previouslyPlaying) browsing = false
        previouslyPlaying = followingPlayback
    }
    val height by remember { derivedStateOf { list.layoutInfo.viewportSize.height } }
    val count by remember { derivedStateOf { list.layoutInfo.totalItemsCount } }
    val tracking = !browsing && !dragging && !lookupOpen
    LaunchedEffect(currentIndex, tracking, locateRequest, height, count) {
        if (tracking && currentIndex != null && currentIndex in 0 until count && height > 0) {
            val offset = -(height * 0.26f).toInt()
            if (initiallyLocated) list.animateScrollToItem(currentIndex, scrollOffset = offset)
            else list.scrollToItem(currentIndex, scrollOffset = offset)
            initiallyLocated = true
        }
    }
    return list
}

@Composable
private fun CaptionPanel(
    state: VideoPlaybackState,
    reading: ReaderPreferences,
    vm: VideoViewModel,
    locateRequest: Int,
    onPlayCue: (Int) -> Unit,
    modifier: Modifier
) {
    val cues = state.displayedCues
    val configuration = LocalConfiguration.current
    val actionsEnabled = state.phase is VideoPhase.Ready
    val density = LocalDensity.current
    val metrics = readerTextMetrics(reading, configuration.screenWidthDp.toFloat())
    val readingFamily = rememberReaderFontFamily(reading.fontFamily)
    val paragraphGap = with(density) { metrics.paragraphGapSp.sp.toDp() }
    val maximumTextWidth = with(density) { metrics.maximumWidthSp.sp.toDp() }
    val activeIndex = activeCueIndex(cues, state.positionMs)
    // Gaps have no highlighted cue, but a seek still brings the nearby transcript into view.
    val followIndex = activeIndex ?: navigationCueIndex(cues, state.positionMs)
        ?: 0.takeIf { cues.isNotEmpty() }
    val navigationBottom = if (state.readMode) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = key(state.item?.id, state.selectedTrackId, state.readMode) {
        rememberCaptionListState(followIndex, state.playRequested, state.lookup != null, locateRequest)
    }
    Column(modifier) {
        if (!state.readMode) Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.video_ui_captions),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            PeekturnIconButton(
                icon = PeekturnIcons.BookOpen,
                description = stringResource(R.string.video_ui_read),
                onClick = { vm.setReadMode(true) },
                enabled = cues.isNotEmpty() && state.audioAvailable && !state.modeSaving
            )
        }
        if (!state.readMode) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            state.phase == VideoPhase.Loading && cues.isEmpty() -> Spacer(Modifier.weight(1f))
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
                modifier = Modifier.weight(1f)
                    .then(if (state.readMode) Modifier.widthIn(max = maximumTextWidth).align(Alignment.CenterHorizontally) else Modifier)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = if (state.readMode) metrics.sidePaddingDp.dp else 16.dp,
                    end = if (state.readMode) metrics.sidePaddingDp.dp else 16.dp,
                    top = if (state.readMode) metrics.topPaddingDp.dp else 8.dp,
                    bottom = if (state.readMode) metrics.bottomPaddingDp.dp + navigationBottom
                        else PortraitToolsHeight + CaptionControlsGap + navigationBottom
                ),
                verticalArrangement = Arrangement.spacedBy(if (state.readMode) paragraphGap else 0.dp)
            ) {
                itemsIndexed(cues, key = { _, cue -> cue.id }) { index, cue ->
                    val current = index == activeIndex
                    val selection = state.lookup?.takeIf { it.cueId == cue.id }?.selection
                    if (state.readMode) {
                        VideoReadingParagraph(
                            cue = cue, selected = selection, current = current,
                            gesturesEnabled = actionsEnabled,
                            onLookup = { vm.openLookup(cue.id, it) },
                            preferences = reading, fontFamily = readingFamily, metrics = metrics,
                            onBlankTap = vm::toggleTools
                        )
                    } else {
                        VideoTranscriptRow(
                            cue = cue,
                            selected = selection,
                            current = current,
                            enabled = actionsEnabled,
                            onPlay = { onPlayCue(index) },
                            onLookup = { vm.openLookup(cue.id, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoTranscriptRow(
    cue: CaptionCue,
    selected: CaptionSelection?,
    current: Boolean,
    enabled: Boolean = true,
    onPlay: (() -> Unit)?,
    onLookup: (CaptionSelection) -> Unit
) {
    val replay = current && enabled && onPlay != null
    val playLabel = stringResource(if (replay) R.string.learning_popup_replay_sentence else R.string.video_ui_seek)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(
                if (current) MaterialTheme.colorScheme.activeSentenceBackground else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(if (onPlay == null) Modifier else Modifier.clickable(enabled = enabled,
                role = Role.Button, onClickLabel = playLabel, onClick = onPlay))
            .heightIn(min = 48.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(Modifier.widthIn(min = 52.dp).alignByBaseline(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (cue.timingApproximate) "~ " else "") + formatClock(cue.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Reserve the glyph width on every row so text keeps its wrapping when the active cue changes.
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                if (replay) Icon(PeekturnIcons.Replay, playLabel, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        CaptionText(
            cue = cue,
            selected = selected,
            highlight = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).alignByBaseline(),
            gesturesEnabled = enabled,
            onLookup = onLookup,
            onBlankTap = null
        )
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
internal fun FullscreenCaptions(state: VideoPlaybackState, onReplay: (Int) -> Unit, onLookup: (Int, CaptionSelection) -> Unit) {
    val cues = state.displayedCues
    val index = activeCueIndex(cues, state.positionMs) ?: return
    val cue = cues.getOrNull(index) ?: return
    val selection = state.lookup?.takeIf { it.cueId == cue.id }?.selection
    val tapBlocker = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val portrait = maxHeight > maxWidth
        val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val footer = safeBottom + LandscapeFooterHeight + CaptionControlsGap
        // PlayerView fits the picture without cropping. Keep portrait captions on that picture,
        // even when a wide video leaves a large black band below it.
        val bottom = if (portrait) {
            val pictureHeight = minOf(maxHeight, maxWidth / state.aspectRatio)
            val pictureMargin = (pictureHeight * 0.09f).coerceIn(12.dp, 64.dp)
            val pictureBottom = (maxHeight - pictureHeight) / 2 + pictureMargin
            maxOf(pictureBottom, if (state.toolsVisible) footer else safeBottom + 12.dp)
        } else if (state.toolsVisible) footer
            else safeBottom + ((maxHeight - safeBottom) * 0.09f).coerceIn(20.dp, 48.dp)
        val belowControls = maxHeight / 2 - LandscapeTransportSize / 2 - CaptionControlsGap - bottom
        val captionHeight = if (portrait && !state.toolsVisible) maxHeight * .26f
            else belowControls.coerceAtLeast(1.dp)
        val compact = maxHeight < 360.dp
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = if (portrait) 16.dp else 24.dp)
                .widthIn(max = 760.dp)
                .padding(bottom = bottom)
                .heightIn(max = captionHeight)
                .clickable(interactionSource = tapBlocker, indication = null) { },
            shape = RoundedCornerShape(8.dp),
            color = CaptionScrim,
            contentColor = Color.White
        ) {
            // Long cues scroll within the space below the central transport.
            key(state.selectedTrackId, cue.id, cue.text) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = if (portrait) 5.dp else 2.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.Top
                ) {
                    if (!portrait) {
                        Text(
                            formatClock(cue.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    CaptionText(
                        cue = cue,
                        selected = selection,
                        highlight = Color.White.copy(alpha = 0.24f),
                        color = Color.White,
                        fontSize = if (compact) 16.sp else 19.sp,
                        lineHeight = if (compact) 19.sp else 26.sp,
                        textAlign = if (portrait) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.weight(1f, fill = !portrait).align(Alignment.Bottom),
                        onLookup = { onLookup(cue.id, it) },
                        onBlankTap = null
                    )
                    if (state.toolsVisible) {
                        IconButton(onClick = { onReplay(index) }, modifier = Modifier.size(40.dp)) {
                            Icon(PeekturnIcons.Replay, stringResource(R.string.learning_popup_replay_sentence),
                                Modifier.size(20.dp))
                        }
                    }
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
internal fun VideoReadingParagraph(
    cue: CaptionCue,
    selected: CaptionSelection? = null,
    current: Boolean,
    onLookup: (CaptionSelection) -> Unit,
    gesturesEnabled: Boolean = true,
    preferences: ReaderPreferences = ReaderPreferences(),
    fontFamily: FontFamily = rememberReaderFontFamily(preferences.fontFamily),
    metrics: ReaderTextMetrics = readerTextMetrics(preferences, LocalConfiguration.current.screenWidthDp.toFloat()),
    onBlankTap: (() -> Unit)? = null
) {
    val flowing = remember(cue.text) { VideoReadingText.from(cue.text) }
    val displayCue = remember(cue, flowing) { cue.copy(text = flowing.text) }
    CaptionText(
        cue = displayCue,
        selected = flowing.toDisplay(selected),
        highlight = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onBackground,
        fontFamily = fontFamily,
        fontSize = metrics.fontSizeSp.sp,
        lineHeight = metrics.lineHeightSp.sp,
        letterSpacing = metrics.letterSpacingEm.em,
        activeBackground = if (current) MaterialTheme.colorScheme.activeSentenceBackground else Color.Unspecified,
        modifier = Modifier.fillMaxWidth(),
        gesturesEnabled = gesturesEnabled,
        onLookup = { flowing.toSource(it)?.let(onLookup) },
        onBlankTap = onBlankTap
    )
}

@Composable
private fun CaptionText(
    cue: CaptionCue,
    selected: CaptionSelection?,
    highlight: Color,
    /** Explicit, because a bare Box does not carry Material's LocalContentColor. */
    color: Color,
    fontSize: TextUnit = 15.sp,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = 23.sp,
    textAlign: TextAlign = TextAlign.Start,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    activeBackground: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    onLookup: (CaptionSelection) -> Unit,
    onBlankTap: (() -> Unit)?
) {
    var layout by remember(cue.id) { mutableStateOf<TextLayoutResult?>(null) }
    var dragging by remember(cue.id) { mutableStateOf<CaptionSelection?>(null) }
    val shown = dragging ?: selected
    val text = remember(cue.text, shown, highlight, activeBackground) {
        buildAnnotatedString {
            append(cue.text)
            if (activeBackground != Color.Unspecified && cue.text.isNotEmpty()) {
                addStyle(SpanStyle(background = activeBackground), 0, cue.text.length)
            }
            if (shown != null && shown.start in 0..shown.end && shown.end <= cue.text.length) {
                addStyle(SpanStyle(background = highlight), shown.start, shown.end)
            }
        }
    }
    Text(
        text,
        modifier = modifier.pointerInput(cue.id, layout, gesturesEnabled) {
            if (!gesturesEnabled) return@pointerInput
            val layoutResult = layout ?: return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val anchorOffset = layoutResult.getOffsetForPosition(down.position)
                var longPressed = false
                var moved = false
                var released = false
                var releasedChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
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
                        releasedChange = change
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
                    longPressed -> selection?.let { releasedChange?.consume(); onLookup(it) }
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
                        if (word != null) {
                            releasedChange?.consume()
                            onLookup(word)
                        } else if (onBlankTap != null) {
                            releasedChange?.consume()
                            onBlankTap()
                        }
                    }
                }
            }
        },
        color = color,
        fontSize = fontSize,
        fontFamily = fontFamily,
        lineHeight = lineHeight,
        textAlign = textAlign,
        letterSpacing = letterSpacing,
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
    onDismiss: () -> Unit,
    onReadingAppearance: (() -> Unit)? = null,
    downloadState: VideoDownloadState? = null
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
            if (onReadingAppearance != null) {
                SettingsDisclosure(stringResource(R.string.reader_typography_title),
                    stringResource(R.string.video_ui_shared_reading_appearance), onClick = onReadingAppearance)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            VideoDownloadControls(downloadState, vm::downloadCurrentVideo, vm::removeDownload, vm::downloadPicture)
            if (state.offlinePlayback) TextButton(onClick = { onDismiss(); vm.streamInstead() }) {
                Text(stringResource(R.string.video_download_stream_instead))
            }
            if (state.qualities.isEmpty() && state.audioOnly && state.phase is VideoPhase.Ready) {
                Text(stringResource(R.string.video_download_audio_only_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            val qualityHeights = (state.knownQualityHeights + state.qualities.map { it.height }).distinct().sortedDescending()
            val qualityBusy = state.qualityChanging || state.refreshingQualities || state.modeSaving
            if (qualityHeights.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.video_ui_quality_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    qualityHeights.forEach { height ->
                        val quality = state.qualities.find { it.height == height }
                        val selected = quality != null && quality.id == state.selectedQualityId
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = !qualityBusy,
                                    role = Role.RadioButton
                                ) { vm.selectKnownQuality(height) }
                                .heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (height > 0) stringResource(R.string.video_ui_quality_label, height)
                                else quality?.id.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (quality == null) Text(stringResource(R.string.video_ui_quality_online),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.qualityUnavailable) Text(stringResource(R.string.video_ui_quality_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (qualityHeights.size == 1 || state.qualityUnavailable || state.refreshingQualities) {
                    TextButton(onClick = { vm.refreshQualities() }, enabled = !qualityBusy) {
                        Text(stringResource(if (state.refreshingQualities) R.string.video_ui_quality_loading
                            else R.string.video_ui_quality_refresh))
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
