package app.peekturn.reader.video

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.peekturn.reader.AppGraph
import app.peekturn.reader.data.OfflineDictionary
import app.peekturn.reader.learning.LearningSession
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.reader.withTypography
import app.peekturn.reader.narration.NarrationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Loading is one state; a caption failure never hides an already playable video. */
internal sealed interface VideoPhase {
    data object Idle : VideoPhase
    data object Loading : VideoPhase
    data object Ready : VideoPhase
    data class Failed(val kind: VideoFailureKind) : VideoPhase
}

internal enum class VideoCaptionStatus { Loading, Ready, Absent, Failed, NoEnglish }

/** The merge switch is a user intention, not a busy flag; failures keep the original subtitles. */
internal enum class VideoMergePhase { Off, ReadingCache, Generating, Available, Failed, Stale }

internal enum class VideoCacheNotice { MergedCleared, AllCleared, Failed }

internal data class VideoLookupRequest(
    val context: LookupContext,
    val cueId: Int,
    val selection: CaptionSelection,
    /** The displayed cue's real start, used only by the explicit replay action. */
    val startMs: Long
)

internal data class VideoPlaybackState(
    val phase: VideoPhase = VideoPhase.Idle,
    val item: VideoItem? = null,
    val tracks: List<CaptionTrack> = emptyList(),
    val selectedTrackId: String? = null,
    val qualities: List<VideoQuality> = emptyList(),
    val selectedQualityId: String? = null,
    val qualityChanging: Boolean = false,
    val audioOnly: Boolean = false,
    val offlinePlayback: Boolean = false,
    val readMode: Boolean = false,
    val audioAvailable: Boolean = false,
    val modeSaving: Boolean = false,
    val modeSaveFailed: Boolean = false,
    val watchSwitchFailure: VideoFailureKind? = null,
    val resumeSaveFailed: Boolean = false,
    val preferredHeight: Int = 1080,
    val captions: CaptionDocument? = null,
    val captionStatus: VideoCaptionStatus = VideoCaptionStatus.Absent,
    val captionFailure: VideoFailureKind? = null,
    val mergedCues: List<CaptionCue>? = null,
    val mergeEnabled: Boolean = false,
    val mergePhase: VideoMergePhase = VideoMergePhase.Off,
    val mergeProgress: Pair<Int, Int>? = null,
    val mergeFailure: VideoFailureKind? = null,
    val cacheAllBytes: Long = 0L,
    val cacheMergedBytes: Long = 0L,
    val cacheNotice: VideoCacheNotice? = null,
    val lookupPausedPlayback: Boolean = false,
    val toolsVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val playRequested: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val aspectRatio: Float = videoAspectRatio(0, 0),
    val lookup: VideoLookupRequest? = null
) {
    /** Late metadata must not rewind a session that already attached its real player state. */
    internal fun withStoredMetadata(stored: VideoItem): VideoPlaybackState =
        if (item == null) copy(item = stored, positionMs = stored.positionMs, durationMs = stored.durationMs) else this

    /** Merged cues replace the original lines; while a merge runs the original stays visible. */
    val displayedCues: List<CaptionCue> get() = mergedCues ?: captions?.cues.orEmpty()

    /** Equal counts do not imply equal boundaries. */
    val mergedUnchanged: Boolean
        get() = mergedCues != null && captions != null && mergedCues.size == captions.cues.size &&
            mergedCues.zip(captions.cues).all { (a, b) ->
                normalizedCaptionText(a.text) == normalizedCaptionText(b.text) && a.startMs == b.startMs && a.endMs == b.endMs
            }
}

/**
 * The one owner of a video session: the only ExoPlayer, the real source resolution, the subtitle
 * track, the merge request, the lookup pause and the position writer. Compose reads [state] and
 * sends actions; no composable parses a source, opens a cache file or persists a position.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoViewModel(
    private val context: Context,
    private val graph: AppGraph
) : ViewModel() {

    private val _state = MutableStateFlow(VideoPlaybackState())
    val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()

    val learningSession = LearningSession(cache = graph.explanationCache) { category, cause ->
        graph.developerLog.recordFailure("learning", category, cause)
    }
    val dictionary: OfflineDictionary get() = graph.dictionary
    val downloads get() = graph.videoDownloads.states
    fun downloadCurrentVideo() {
        val state = _state.value
        state.item?.let {
            if (graph.videoDownloads.states.value[it.id]?.phase == VideoDownloadPhase.Failed) graph.videoDownloads.retry(it.id)
            else graph.videoDownloads.download(it, state.audioOnly,
                state.qualities.firstOrNull { quality -> quality.id == state.selectedQualityId }?.height ?: state.preferredHeight)
        }
    }
    fun downloadPicture() {
        val state = _state.value
        state.item?.let { graph.videoDownloads.download(it, false,
            state.qualities.firstOrNull { quality -> quality.id == state.selectedQualityId }?.height ?: state.preferredHeight) }
    }
    fun removeDownload() { _state.value.item?.id?.let { graph.videoDownloads.remove(it) } }
    fun streamInstead() {
        val current = _state.value
        val id = current.item?.id ?: return
        start(id, null, force = true, autoPlay = player.playWhenReady && current.lookup == null,
            preferDownload = false, startAtMs = lastKnownPosition())
    }

    private val httpClient get() = graph.videoMediaHttpClient

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setPriorityTaskManager(graph.videoTransferPriority)
        .setLoadControl(videoLoadControl())
        .build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
    }

    private data class Request(val id: Long?, val url: String?, val preferDownload: Boolean)

    private var request: Request? = null
    private var audioSource: VideoPlaybackSource? = null
    private var sessionGeneration = 0L
    private var sessionJob: Job? = null
    private var narrationStop: Job? = null
    private var metadataJob: Job? = null
    private data class ActiveQualitySwitch(val token: Long, val previousId: String?, val plan: QualitySwitch)
    private var qualityGeneration = 0L
    private var activeQualitySwitch: ActiveQualitySwitch? = null
    private var openingStartedAt = 0L
    private var firstFramePending = false
    private var ticker: Job? = null
    private var foreground = false
    private var lastSavedPosition = -1L
    private val progress = VideoProgressCheckpoint()
    private var positionWrite: Job? = null
    private var lastLandscape: Boolean? = null
    private var track: VideoTimeTracker? = null
    private var trackerItemId: Long? = null

    private var captionJob: Job? = null
    private var captionGeneration = 0L

    private data class MergeRun(
        val token: Long,
        val document: CaptionDocument
    )

    private var mergeRun: MergeRun? = null
    private var mergeJob: Job? = null
    private var mergeGeneration = 0L
    @Volatile private var mergeVisible = false
    private var mergeBlocked = false
    private var pendingMergedCues: List<CaptionCue>? = null
    private var cacheGeneration = 0L
    private var cacheBytesJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (firstFramePending) {
                    firstFramePending = false
                    graph.developerLog.recordEvent("video", "first-frame-ms:${SystemClock.elapsedRealtime() - openingStartedAt}")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                observeProgress()
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying && firstFramePending && _state.value.audioOnly) {
                    firstFramePending = false
                    graph.developerLog.recordEvent("video", "first-audio-ms:${SystemClock.elapsedRealtime() - openingStartedAt}")
                }
                track?.foreground(foreground || isPlaying)
                track?.playing(isPlaying)
                if (isPlaying) startTicker() else if (!foreground) stopTicker()
                if (!isPlaying && !player.playWhenReady) persistPosition()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _state.update {
                    it.copy(
                        playRequested = playWhenReady,
                        ended = if (playbackStateEnded()) true else it.ended
                    )
                }
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    // Focus loss is not a user pause: it still may never resume an old lookup.
                    persistPosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        graph.developerLog.recordEvent("video", "media-ready-at-ms:${player.currentPosition.coerceAtLeast(0L)}")
                        val switching = activeQualitySwitch
                        if (switching != null && switching.token == qualityGeneration) {
                            activeQualitySwitch = null
                            _state.update { it.copy(qualityChanging = false) }
                        }
                        updateDuration()
                    }
                    Player.STATE_BUFFERING -> graph.developerLog.recordEvent("video", "media-buffering-at-ms:${player.currentPosition.coerceAtLeast(0L)}")
                    Player.STATE_ENDED -> {
                        _state.update { it.copy(ended = true) }
                        persistPosition()
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = videoAspectRatio(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                _state.update { it.copy(aspectRatio = ratio) }
            }

            override fun onPlayerError(error: PlaybackException) {
                graph.developerLog.recordFailure("video", "playback", error)
                val switching = activeQualitySwitch
                if (switching != null && switching.token == qualityGeneration) {
                    activeQualitySwitch = null
                    // No automatic retry: report the failure, but keep the last working rendition
                    // as the remembered choice instead of claiming the failed one succeeded.
                    _state.update {
                        it.copy(selectedQualityId = switching.previousId, qualityChanging = false)
                    }
                }
                val at = player.currentPosition.coerceAtLeast(0L).takeIf { it > 0L }
                    ?: _state.value.positionMs
                _state.update { it.copy(positionMs = at) }
                persistPosition()
                fail(playbackFailureKind(error.errorCode))
            }
        })
    }

    private fun playbackStateEnded(): Boolean = player.playbackState == Player.STATE_ENDED

    /**
     * The Activity passes either a stored entry or a shared/pasted canonical link. Repeating the
     * same request after a configuration change is a no-op so the player is never recreated.
     */
    fun start(id: Long?, url: String?, force: Boolean = false, autoPlay: Boolean = true,
        preferDownload: Boolean = true, startAtMs: Long? = null) {
        val next = Request(id, url, preferDownload)
        val speed = _state.value.speed.takeIf { id != null && _state.value.item?.id == id } ?: 1f
        if (!force && (request == next || id != null && _state.value.item?.id == id && _state.value.phase is VideoPhase.Ready)) return
        persistPosition()
        progress.begin(null) // Stop/clear callbacks still belong to the previous player timeline.
        request = next
        val token = ++sessionGeneration
        openingStartedAt = SystemClock.elapsedRealtime()
        firstFramePending = true
        sessionJob?.cancel()
        captionJob?.cancel()
        captionGeneration++
        mergeJob?.cancel()
        mergeGeneration++
        mergeVisible = false
        mergeRun = null
        mergeBlocked = false
        pendingMergedCues = null
        qualityGeneration++
        activeQualitySwitch = null
        cacheGeneration++
        closeLookup()
        track?.pause()
        track = null
        trackerItemId = null
        player.stop()
        player.clearMediaItems()
        narrationStop?.cancel()
        narrationStop = viewModelScope.launch { silenceNarration() }
        lastSavedPosition = -1L
        val mergeEnabled = graph.store.settings.value.videoMergeCaptions
        _state.value = VideoPlaybackState(
            phase = VideoPhase.Loading,
            mergeEnabled = mergeEnabled,
            toolsVisible = lastLandscape == true || graph.store.settings.value.videoAudioOnly,
            preferredHeight = graph.store.settings.value.videoQualityHeight,
            speed = speed
        )
        metadataJob?.cancel()
        if (id != null) {
            metadataJob = viewModelScope.launch {
                val stored = try {
                    graph.store.findVideo(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    graph.developerLog.recordFailure("video", "metadata", failure)
                    null
                }
                // The title may appear while the source resolves; the phase stays Loading, so this
                // is stored metadata only and never fakes a playable picture or subtitle.
                if (stored != null && token == sessionGeneration) {
                    _state.update { it.withStoredMetadata(stored) }
                    if (foreground) rememberForegroundVideo()
                }
            }
        }
        sessionJob = viewModelScope.launch {
            try {
                var linkStart: Long? = startAtMs
                val data = if (url != null) {
                    val link = YouTubeUrls.parse(url) ?: throw VideoFailure(VideoFailureKind.InvalidLink)
                    linkStart = link.startMs
                    graph.videos.`import`(link, preferDownload)
                } else {
                    val videoId = id ?: throw VideoFailure(VideoFailureKind.InvalidLink)
                    graph.videos.open(videoId, preferDownload)
                }
                if (token != sessionGeneration) return@launch
                attachSession(data, linkStart, autoPlay = autoPlay, token = token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VideoFailure) {
                if (token == sessionGeneration) {
                    graph.developerLog.recordFailure("video", "open", failure.cause)
                    failLoad(failure.kind, id, url, token)
                }
            } catch (failure: Exception) {
                if (token == sessionGeneration) {
                    graph.developerLog.recordFailure("video", "open", failure)
                    failLoad(VideoFailureKind.Storage, id, url, token)
                }
            }
        }
    }

    /** One explicit retry for the visible failure. No background or unbounded retries exist. */
    fun retryLoad() {
        val failed = _state.value.phase as? VideoPhase.Failed ?: return
        if (failed.kind == VideoFailureKind.InvalidLink) return
        val item = _state.value.item
        if (item == null) {
            start(request?.id, request?.url, force = true)
            return
        }
        val token = ++sessionGeneration
        openingStartedAt = SystemClock.elapsedRealtime()
        firstFramePending = true
        sessionJob?.cancel()
        captionGeneration++
        qualityGeneration++
        activeQualitySwitch = null
        val startAt = lastKnownPosition()
        narrationStop?.cancel()
        narrationStop = viewModelScope.launch { silenceNarration() }
        _state.update { it.copy(phase = VideoPhase.Loading, qualityChanging = false, modeSaving = false, modeSaveFailed = false) }
        sessionJob = viewModelScope.launch {
            try {
                val data = graph.videos.open(item.id, request?.preferDownload ?: true)
                if (token != sessionGeneration) return@launch
                attachSession(data, startAt, autoPlay = true, token = token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VideoFailure) {
                if (token == sessionGeneration) {
                    graph.developerLog.recordFailure("video", "reopen", failure.cause)
                    failLoad(failure.kind, item.id, null, token)
                }
            } catch (failure: Exception) {
                if (token == sessionGeneration) {
                    graph.developerLog.recordFailure("video", "reopen", failure)
                    failLoad(VideoFailureKind.Storage, item.id, null, token)
                }
            }
        }
    }

    private suspend fun attachSession(
        data: VideoSessionData,
        requestedStartMs: Long?,
        autoPlay: Boolean,
        token: Long,
        preserveCheckpoint: Boolean = false
    ) {
        val item = data.item
        val resolved = data.resolved
        val duration = resolved.metadata.durationMs
        val start = playbackStartMs(
            itemPositionMs = item.positionMs,
            linkStartMs = requestedStartMs,
            durationMs = duration
        )
        val document = data.captions
        val documentTrack = document?.let { cached ->
            if (cached.videoId != item.videoId) null
            else resolved.tracks.firstOrNull { it.id == cached.trackId }
        }
        // A cached document for another video or track is not evidence for this session. An
        // explicit user choice alone decides identity; otherwise only English may attach silently.
        val sessionDocument = document?.takeIf {
            documentTrack != null && (if (item.trackExplicit) {
                item.selectedTrackId == documentTrack.id
            } else {
                isEnglishTrack(documentTrack)
            })
        }
        val selected = documentTrack?.takeIf { sessionDocument != null }
            ?: selectInitialTrack(resolved.tracks, item.selectedTrackId, item.trackExplicit)
        val status = when {
            sessionDocument != null -> VideoCaptionStatus.Ready
            data.captionFailure != null -> VideoCaptionStatus.Failed
            selected != null -> VideoCaptionStatus.Loading
            resolved.tracks.isNotEmpty() -> VideoCaptionStatus.NoEnglish
            else -> VideoCaptionStatus.Absent
        }
        val preferences = graph.store.settings.value
        val qualityId = preferredQuality(resolved.qualities, preferences.videoQualityHeight)?.id
            ?: initialQualityId(resolved.qualities, resolved.playback, resolved.selectedQualityId)
        audioSource = resolved.audioOnly
        val audioOnly = preferredAudioOnly(item, preferences.videoAudioOnly)
        val playback = if (audioOnly && audioSource != null) audioSource!!
            else resolved.qualities.firstOrNull { it.id == qualityId }?.source ?: resolved.playback
        graph.developerLog.recordEvent("video", "source-ready-ms:${SystemClock.elapsedRealtime() - openingStartedAt}")
        _state.update {
            it.copy(
                phase = VideoPhase.Ready,
                item = item,
                tracks = resolved.tracks,
                selectedTrackId = selected?.id,
                qualities = resolved.qualities,
                selectedQualityId = qualityId,
                qualityChanging = false,
                audioOnly = audioOnly,
                offlinePlayback = playback.videoUrl.startsWith("peekturn-offline://"),
                readMode = item.readMode,
                audioAvailable = audioSource != null,
                modeSaving = false,
                modeSaveFailed = false,
                watchSwitchFailure = data.watchFallbackFailure,
                preferredHeight = preferences.videoQualityHeight,
                toolsVisible = if (item.readMode) false else it.toolsVisible || audioOnly,
                captions = sessionDocument,
                captionStatus = status,
                captionFailure = data.captionFailure,
                mergedCues = null,
                mergePhase = VideoMergePhase.Off,
                mergeProgress = null,
                mergeFailure = null,
                positionMs = start,
                durationMs = duration,
                isPlaying = false,
                playRequested = false,
                ended = false,
                aspectRatio = videoAspectRatio(0, 0)
            )
        }
        if (!preserveCheckpoint) {
            lastSavedPosition = item.positionMs
            progress.begin(item.id)
        }
        if (foreground) rememberForegroundVideo()
        val itemTracker = trackerFor(item)
        if (foreground) itemTracker.foreground(true)
        // Caption HTTP can start while the previous narration is being stopped.
        if (status == VideoCaptionStatus.Loading && selected != null) loadTrack(selected, force = false)
        if (_state.value.mergeEnabled && sessionDocument != null && !mergeBlocked) startMerge(sessionDocument)
        narrationStop?.join()
        if (token != sessionGeneration) return
        if (audioOnly && audioSource == null) {
            // Respect an audio-only request: never download a muxed video behind a hidden picture.
            fail(VideoFailureKind.Unsupported)
            return
        }
        preparePlayer(playback, start, autoPlay && shouldAutoPlay(start, duration))
        startTicker()
        refreshCacheBytes()
    }

    private fun preparePlayer(source: VideoPlaybackSource, startMs: Long, autoPlay: Boolean) {
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.setHandleAudioBecomingNoisy(true)
        player.setMediaSource(mediaSource(source), startMs)
        player.setPlaybackSpeed(_state.value.speed)
        disableEmbeddedText()
        player.prepare()
        // A lookup opened while the previous narration was being silenced owns the pause.
        player.playWhenReady = autoPlay && _state.value.lookup == null
    }

    /** The app renders the selected caption track itself; embedded subtitles must not appear twice. */
    private fun disableEmbeddedText() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun mediaSource(source: VideoPlaybackSource): MediaSource {
        val upstream = OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(source.headers)
        val dataSourceFactory = graph.videoMediaCache.factory(upstream, cacheable = false)
        fun progressive(url: String, mime: String, cacheKey: String?): MediaSource {
            val metadata = _state.value.item
            val item = MediaItem.Builder().setUri(url).setMediaMetadata(
                MediaMetadata.Builder().setTitle(metadata?.title).setArtist(metadata?.author).build()
            ).apply {
                if (mime.isNotBlank()) setMimeType(mime)
                setCustomCacheKey(cacheKey)
            }.build()
            val factory = if (url.startsWith("peekturn-offline://")) graph.videoDownloads.playbackFactory()
                else graph.videoMediaCache.factory(upstream, cacheable = cacheKey != null)
            return ProgressiveMediaSource.Factory(factory)
                .createMediaSource(item)
        }
        val videoMime = source.videoMimeType
        val audioUrl = source.audioUrl
        val videoSource = when {
            videoMime.contains("mpegurl", ignoreCase = true) || videoMime.contains("m3u8", ignoreCase = true) ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(source.videoUrl))
            videoMime.contains("dash", ignoreCase = true) || videoMime.contains("mpd", ignoreCase = true) ->
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(source.videoUrl))
            else -> progressive(source.videoUrl, videoMime, source.videoCacheKey)
        }
        if (audioUrl == null) return videoSource
        return MergingMediaSource(videoSource, progressive(audioUrl, source.audioMimeType.orEmpty(), source.audioCacheKey))
    }

    /**
     * A real video starts after the reader's narration and the popup's pronunciation are silent, so
     * two audio owners never play at once. Narration is app-private and may already be backgrounded.
     */
    private suspend fun silenceNarration() {
        graph.speech.stop()
        var connection: ServiceConnection? = null
        try {
            withTimeoutOrNull(1500L) {
                suspendCancellableCoroutine { continuation ->
                    val created = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            (service as? NarrationService.Binder)?.stop()
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit

                        override fun onNullBinding(name: ComponentName?) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                    connection = created
                    NarrationService.bind(context, created)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.developerLog.recordFailure("video", "narration-stop", failure)
        } finally {
            connection?.let { NarrationService.unbind(context, it) }
        }
    }

    fun onForeground() {
        foreground = true
        rememberForegroundVideo()
        track?.foreground(true)
        track?.playing(player.isPlaying)
        startTicker()
    }

    private fun rememberForegroundVideo() {
        val id = _state.value.item?.id ?: return
        graph.scope.launch {
            if (!foreground || _state.value.item?.id != id) return@launch
            try { graph.store.markVideoOpened(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "remember-open", failure)
                _state.update { it.copy(resumeSaveFailed = true) }
            }
        }
    }

    /** Activity visibility only changes learning accounting; the media service keeps playback. */
    fun onBackground() {
        foreground = false
        persistPosition()
        closeLookup()
        if (!player.isPlaying) stopTicker()
        val active = track
        if (active != null) {
            active.foreground(player.isPlaying)
            active.playing(player.isPlaying)
            graph.scope.launch { active.flush() }
        }
    }

    fun togglePlay() {
        if (_state.value.item == null) return
        if (_state.value.ended || player.playbackState == Player.STATE_ENDED) {
            progress.seek()
            player.seekTo(0L)
            _state.update { it.copy(positionMs = 0L, ended = false) }
            player.play()
            return
        }
        if (player.playWhenReady) {
            player.pause()
            persistPosition()
        } else {
            player.play()
        }
    }

    fun setSpeed(speed: Float) {
        if (speed !in videoSpeeds) return
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    /**
     * Swaps the media source of the one existing player. Captions, cue timestamps, the current
     * position and the speed are untouched; only the rendition changes. While an explanation owns
     * the pause the switch stays silent, and a late error from this swap cannot join a newer one.
     */
    fun selectQuality(quality: VideoQuality) {
        val current = _state.value
        if (current.modeSaving || current.qualities.none { it.id == quality.id }) return
        saveHeight(quality.height)
        if (current.audioOnly) {
            _state.update { it.copy(selectedQualityId = quality.id) }
            return
        }
        val recovering = current.phase is VideoPhase.Failed
        if (current.phase !is VideoPhase.Ready && !recovering) return
        val position = if (player.playbackState == Player.STATE_IDLE) current.positionMs
        else player.currentPosition.coerceAtLeast(0L)
        val plan = qualitySwitch(
            qualities = current.qualities,
            selectedQualityId = current.selectedQualityId,
            targetQualityId = quality.id,
            positionMs = position,
            playWhenReady = player.playWhenReady,
            lookupOpen = current.lookup != null,
            speed = current.speed,
            // After a failure the failed source may be the same id shown as remembered; an
            // explicit tap must still prepare it instead of being treated as a no-op.
            force = recovering
        ) ?: return
        val previousId = activeQualitySwitch?.takeIf { it.token == qualityGeneration }?.previousId
            ?: current.selectedQualityId
        val token = ++qualityGeneration
        activeQualitySwitch = ActiveQualitySwitch(token, previousId, plan)
        _state.update {
            it.copy(
                phase = if (recovering) VideoPhase.Ready else it.phase,
                selectedQualityId = plan.quality.id,
                qualityChanging = true,
                positionMs = plan.positionMs,
                isPlaying = false,
                playRequested = plan.resumeOnReady
            )
        }
        player.pause()
        player.setMediaSource(mediaSource(plan.quality.source), plan.positionMs)
        player.setPlaybackSpeed(plan.speed)
        disableEmbeddedText()
        player.prepare()
        player.playWhenReady = plan.resumeOnReady
        if (recovering) startTicker()
    }

    private fun saveHeight(height: Int) {
        if (height <= 0) return
        _state.update { it.copy(preferredHeight = height) }
        viewModelScope.launch {
            try { graph.store.updateSettings { it.copy(videoQualityHeight = height) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { graph.developerLog.recordFailure("video", "quality-preference", failure) }
        }
    }

    suspend fun saveReadingAppearance(reading: ReaderPreferences, theme: String): Boolean = try {
        graph.store.updateSettings { it.withTypography(reading).copy(appTheme = theme) }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        graph.developerLog.recordFailure("video", "reading-appearance", failure)
        false
    }

    fun setAudioOnly(enabled: Boolean) {
        if (!enabled && _state.value.qualities.isEmpty()) requestWatchSource()
        else setPresentation(enabled, readMode = false)
    }

    fun setReadMode(enabled: Boolean) {
        if (enabled && (_state.value.displayedCues.isEmpty() || !_state.value.audioAvailable)) return
        if (!enabled && _state.value.qualities.isEmpty()) requestWatchSource()
        else setPresentation(audioOnly = enabled, readMode = enabled)
    }

    /** A saved audio stream is a playback source, not a permanent limit on this video's modes. */
    private fun requestWatchSource() {
        val current = _state.value
        val item = current.item ?: return
        if (current.modeSaving || current.phase !is VideoPhase.Ready && current.phase !is VideoPhase.Failed) return
        val token = sessionGeneration
        _state.update { it.copy(modeSaving = true, watchSwitchFailure = null) }
        viewModelScope.launch {
            try {
                // The picture may have completed downloading since this Read session opened.
                // Keep the current audio/player untouched while a missing picture resolves online.
                val local = graph.videoDownloads.session(item)
                val data = local?.takeIf { it.resolved.qualities.isNotEmpty() }
                    ?: graph.videos.open(item.id, preferDownload = false)
                if (token != sessionGeneration || _state.value.item?.id != item.id) return@launch
                val cachedAudio = audioSource?.takeIf {
                    it.videoUrl.startsWith("peekturn-offline://") && it.videoCacheKey?.startsWith("media-") == true &&
                        it.videoCacheKey == data.resolved.audioOnly?.videoCacheKey
                }
                val resolved = if (cachedAudio == null) data.resolved else {
                    fun pair(source: VideoPlaybackSource): VideoPlaybackSource =
                        if (source.audioCacheKey == cachedAudio.videoCacheKey) source.copy(
                            audioUrl = cachedAudio.videoUrl, audioMimeType = cachedAudio.videoMimeType,
                            audioCacheKey = cachedAudio.videoCacheKey
                        ) else source
                    data.resolved.copy(audioOnly = cachedAudio, playback = pair(data.resolved.playback),
                        qualities = data.resolved.qualities.map { it.copy(source = pair(it.source)) })
                }
                graph.store.selectVideoPresentation(item.id, audioOnly = false, readMode = false)
                if (token != sessionGeneration) return@launch
                val at = lastKnownPosition()
                val resume = player.playWhenReady && _state.value.lookup == null
                val document = data.captions ?: _state.value.captions
                attachSession(data.copy(item = data.item.copy(audioOnly = false, readMode = false),
                    resolved = resolved, captions = document), at, autoPlay = resume, token = token, preserveCheckpoint = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "watch-switch", failure)
                if (token == sessionGeneration) _state.update { it.copy(modeSaving = false,
                    watchSwitchFailure = (failure as? VideoFailure)?.kind ?: VideoFailureKind.Storage) }
            }
        }
    }

    fun dismissWatchSwitchFailure() { _state.update { it.copy(watchSwitchFailure = null) } }

    private fun setPresentation(audioOnly: Boolean, readMode: Boolean) {
        val enabled = audioOnly
        val current = _state.value
        if (current.modeSaving || current.phase !is VideoPhase.Ready && current.phase !is VideoPhase.Failed ||
            enabled == current.audioOnly && readMode == current.readMode) return
        val source = if (enabled) audioSource else current.qualities.firstOrNull {
            it.id == current.selectedQualityId
        }?.source
        if (source == null) return
        val itemId = current.item?.id ?: return
        val token = sessionGeneration
        _state.update { it.copy(modeSaving = true, modeSaveFailed = false) }
        // Persist before presenting a remembered choice. The app scope finishes this small write
        // even when a paused Activity/service is closed while SQLite is committing it.
        graph.scope.launch {
            try {
                graph.store.selectVideoPresentation(itemId, enabled, readMode)
                if (token != sessionGeneration || _state.value.item?.id != itemId) return@launch
                val at = lastKnownPosition()
                val resume = player.playWhenReady && _state.value.lookup == null
                qualityGeneration++
                activeQualitySwitch = null
                _state.update { it.copy(phase = VideoPhase.Ready, audioOnly = enabled, readMode = readMode,
                    item = it.item?.copy(audioOnly = enabled, readMode = readMode), toolsVisible = !readMode, qualityChanging = false, modeSaving = false) }
                if (enabled != current.audioOnly) {
                    player.pause()
                    preparePlayer(source, at, resume)
                    startTicker()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "audio-preference", failure)
                if (token == sessionGeneration) _state.update { it.copy(modeSaving = false, modeSaveFailed = true) }
            }
        }
    }

    fun dismissResumeSaveFailure() { _state.update { it.copy(resumeSaveFailed = false) } }

    fun dismissModeSaveFailure() { _state.update { it.copy(modeSaveFailed = false) } }

    /** A seek changes the live clock, not durable progress, until its own playback qualifies. */
    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        val target = positionMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
        graph.developerLog.recordEvent("video", "seek-ms:${player.currentPosition.coerceAtLeast(0L)}:$target:buffered=${player.bufferedPosition.coerceAtLeast(0L)}")
        progress.seek()
        player.seekTo(target)
        _state.update { it.copy(positionMs = target, ended = false) }
    }

    fun playCue(index: Int) {
        if (_state.value.displayedCues.getOrNull(index) == null) return
        closeLookup()
        seekToCue(index)
        player.play()
    }

    fun seekToCue(index: Int) {
        val cue = _state.value.displayedCues.getOrNull(index) ?: return
        seekTo(cue.startMs)
    }

    fun previousCue() {
        val cues = _state.value.displayedCues
        val position = lastKnownPosition()
        previousCueIndex(cues, position)?.let { seekToCue(it) }
    }

    fun nextCue() {
        val cues = _state.value.displayedCues
        val position = lastKnownPosition()
        nextCueIndex(cues, position)?.let { seekToCue(it) }
    }

    fun selectTrack(trackRecord: CaptionTrack) {
        val item = _state.value.item ?: return
        if (trackRecord.id == _state.value.selectedTrackId) return
        closeLookup()
        _state.update {
            it.copy(
                item = it.item?.copy(selectedTrackId = trackRecord.id, trackExplicit = true),
                selectedTrackId = trackRecord.id
            )
        }
        viewModelScope.launch {
            try {
                graph.store.selectVideoTrack(item.id, trackRecord.id, explicit = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "track-save", failure)
            }
        }
        loadTrack(trackRecord, force = false)
    }

    private fun loadTrack(trackRecord: CaptionTrack, force: Boolean) {
        val item = _state.value.item ?: return
        val token = ++captionGeneration
        captionJob?.cancel()
        mergeGeneration++
        mergeVisible = false
        mergeRun = null
        pendingMergedCues = null
        val mergePhase = when {
            !_state.value.mergeEnabled -> VideoMergePhase.Off
            mergeBlocked -> VideoMergePhase.Stale
            else -> VideoMergePhase.ReadingCache
        }
        _state.update {
            it.copy(
                selectedTrackId = trackRecord.id,
                captions = null,
                captionStatus = VideoCaptionStatus.Loading,
                captionFailure = null,
                mergedCues = null,
                mergeProgress = null,
                mergeFailure = null,
                mergePhase = mergePhase
            )
        }
        captionJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val document = graph.videos.loadCaptions(item, trackRecord, force)
                graph.developerLog.recordEvent("video", "captions-ready-ms:${SystemClock.elapsedRealtime() - startedAt}")
                if (token != captionGeneration) return@launch
                // Never let a cached or fetched document for another video or track become the
                // selected track's text; a wrong identity is a caption failure, not a silent reuse.
                if (document.videoId != item.videoId || document.trackId != trackRecord.id) {
                    throw VideoFailure(VideoFailureKind.Captions)
                }
                _state.update {
                    it.copy(
                        captions = document,
                        selectedTrackId = document.trackId,
                        captionStatus = VideoCaptionStatus.Ready,
                        captionFailure = null
                    )
                }
                refreshCacheBytes()
                if (_state.value.mergeEnabled && !mergeBlocked) startMerge(document)
                else if (_state.value.mergeEnabled) _state.update { it.copy(mergePhase = VideoMergePhase.Stale) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VideoFailure) {
                if (token == captionGeneration) {
                    graph.developerLog.recordFailure("video", "captions", failure.cause)
                    _state.update {
                        it.copy(
                            captions = null,
                            captionStatus = VideoCaptionStatus.Failed,
                            captionFailure = failure.kind,
                            mergedCues = null,
                            mergePhase = if (it.mergeEnabled) VideoMergePhase.Stale else VideoMergePhase.Off
                        )
                    }
                }
            } catch (failure: Exception) {
                if (token == captionGeneration) {
                    graph.developerLog.recordFailure("video", "captions", failure)
                    _state.update {
                        it.copy(
                            captions = null,
                            captionStatus = VideoCaptionStatus.Failed,
                            captionFailure = VideoFailureKind.Captions,
                            mergedCues = null,
                            mergePhase = if (it.mergeEnabled) VideoMergePhase.Stale else VideoMergePhase.Off
                        )
                    }
                }
            }
        }
    }

    fun retryCaptions() {
        val current = _state.value
        val item = current.item ?: return
        val selected = current.tracks.firstOrNull { it.id == current.selectedTrackId }
            ?: selectInitialTrack(current.tracks, item.selectedTrackId, item.trackExplicit)
        if (selected != null) {
            loadTrack(selected, force = true)
            return
        }
        retryCaptionListing(item)
    }

    /**
     * A failed caption listing produced no track at all, so there is nothing to reload. Re-resolve
     * the track listing only; the current player, position and play intent are left untouched.
     */
    private fun retryCaptionListing(item: VideoItem) {
        val sessionToken = sessionGeneration
        val token = ++captionGeneration
        captionJob?.cancel()
        mergeGeneration++
        mergeRun = null
        mergeVisible = false
        pendingMergedCues = null
        _state.update {
            it.copy(
                captionStatus = VideoCaptionStatus.Loading,
                captionFailure = null,
                mergedCues = null,
                mergePhase = if (it.mergeEnabled) VideoMergePhase.ReadingCache else VideoMergePhase.Off,
                mergeProgress = null,
                mergeFailure = null
            )
        }
        captionJob = viewModelScope.launch {
            var listed: List<CaptionTrack> = emptyList()
            try {
                val resolved = graph.videoSource.resolve(item.videoId)
                if (token != captionGeneration || sessionToken != sessionGeneration) return@launch
                listed = resolved.tracks
                val current = _state.value.item ?: item
                val track = selectInitialTrack(listed, current.selectedTrackId, current.trackExplicit)
                if (track == null) {
                    _state.update {
                        it.copy(
                            tracks = listed,
                            selectedTrackId = null,
                            captions = null,
                            captionStatus = when {
                                resolved.captionFailure != null -> VideoCaptionStatus.Failed
                                listed.isNotEmpty() -> VideoCaptionStatus.NoEnglish
                                else -> VideoCaptionStatus.Absent
                            },
                            captionFailure = resolved.captionFailure
                        )
                    }
                    refreshCacheBytes()
                    return@launch
                }
                // Re-resolving the listing is not itself a user choice.
                _state.update {
                    it.copy(
                        tracks = listed,
                        selectedTrackId = track.id,
                        item = it.item?.copy(
                            selectedTrackId = track.id,
                            trackExplicit = false
                        )
                    )
                }
                try {
                    graph.store.selectVideoTrack(item.id, track.id, explicit = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    graph.developerLog.recordFailure("video", "track-save", failure)
                }
                if (token != captionGeneration || sessionToken != sessionGeneration) return@launch
                val document = graph.videos.loadCaptions(item, track)
                if (token != captionGeneration || sessionToken != sessionGeneration) return@launch
                if (document.videoId != item.videoId || document.trackId != track.id) {
                    throw VideoFailure(VideoFailureKind.Captions)
                }
                _state.update {
                    it.copy(
                        tracks = listed,
                        captions = document,
                        selectedTrackId = document.trackId,
                        item = it.item?.copy(selectedTrackId = document.trackId),
                        captionStatus = VideoCaptionStatus.Ready,
                        captionFailure = null
                    )
                }
                refreshCacheBytes()
                if (_state.value.mergeEnabled && !mergeBlocked) startMerge(document)
                else if (_state.value.mergeEnabled) _state.update { it.copy(mergePhase = VideoMergePhase.Stale) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VideoFailure) {
                if (token == captionGeneration && sessionToken == sessionGeneration) {
                    graph.developerLog.recordFailure("video", "caption-listing", failure.cause)
                    _state.update {
                        val current = it.item
                        it.copy(
                            tracks = if (listed.isNotEmpty()) listed else it.tracks,
                            selectedTrackId = if (listed.isNotEmpty()) {
                                selectInitialTrack(listed, current?.selectedTrackId, current?.trackExplicit ?: false)?.id
                            } else {
                                it.selectedTrackId
                            },
                            captions = null,
                            captionStatus = VideoCaptionStatus.Failed,
                            captionFailure = failure.kind,
                            mergedCues = null,
                            mergePhase = if (it.mergeEnabled) VideoMergePhase.Stale else VideoMergePhase.Off
                        )
                    }
                    refreshCacheBytes()
                }
            } catch (failure: Exception) {
                if (token == captionGeneration && sessionToken == sessionGeneration) {
                    graph.developerLog.recordFailure("video", "caption-listing", failure)
                    _state.update {
                        it.copy(
                            tracks = if (listed.isNotEmpty()) listed else it.tracks,
                            captions = null,
                            captionStatus = VideoCaptionStatus.Failed,
                            captionFailure = VideoFailureKind.Captions,
                            mergedCues = null,
                            mergePhase = if (it.mergeEnabled) VideoMergePhase.Stale else VideoMergePhase.Off
                        )
                    }
                    refreshCacheBytes()
                }
            }
        }
    }

    /** The switch expresses intent; closing it never deletes cache and in-flight work may finish. */
    fun setMergeEnabled(enabled: Boolean) {
        val current = _state.value
        if (current.mergeEnabled == enabled && current.mergePhase != VideoMergePhase.Stale) return
        _state.update {
            it.copy(
                mergeEnabled = enabled,
                mergedCues = if (enabled) it.mergedCues else null,
                mergePhase = when {
                    !enabled -> VideoMergePhase.Off
                    it.captions == null -> VideoMergePhase.Off
                    else -> VideoMergePhase.ReadingCache
                },
                mergeProgress = null,
                mergeFailure = null
            )
        }
        if (!enabled) {
            // The running request may still cache its result; its completion cannot turn the
            // switch back on because applying requires the current intention and visibility.
            mergeVisible = false
            pendingMergedCues = null
        } else {
            mergeBlocked = false
        }
        if (current.item != null) {
            viewModelScope.launch {
                try {
                    graph.store.updateSettings { it.copy(videoMergeCaptions = enabled) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    graph.developerLog.recordFailure("video", "merge-setting", failure)
                }
            }
        }
        if (enabled) {
            val document = current.captions ?: return
            startMerge(document)
        }
    }

    private fun startMerge(document: CaptionDocument) {
        if (!_state.value.mergeEnabled || mergeBlocked) return
        val running = mergeRun
        if (mergeJob?.isActive == true && running != null &&
            running.document.videoId == document.videoId &&
            running.document.trackId == document.trackId &&
            running.document.sourceHash == document.sourceHash
        ) {
            mergeVisible = true
            _state.update {
                it.copy(
                    mergePhase = if (it.mergeProgress != null) VideoMergePhase.Generating else VideoMergePhase.ReadingCache,
                    mergeFailure = null
                )
            }
            return
        }
        mergeJob?.cancel()
        val token = ++mergeGeneration
        val run = MergeRun(token, document)
        mergeRun = run
        mergeVisible = true
        _state.update {
            it.copy(
                mergedCues = null,
                mergePhase = VideoMergePhase.ReadingCache,
                mergeProgress = null,
                mergeFailure = null
            )
        }
        mergeJob = viewModelScope.launch {
            try {
                val began = SystemClock.elapsedRealtime()
                val cues = withContext(Dispatchers.Default) { sentenceCaptions(document) }
                graph.developerLog.recordEvent("video", "sentences-ready-ms:${SystemClock.elapsedRealtime() - began}")
                if (token != mergeGeneration) return@launch
                if (mergeVisible && _state.value.mergeEnabled) {
                    if (_state.value.lookup != null) {
                        // An open explanation owns the visible line until it is closed.
                        pendingMergedCues = cues
                        _state.update { it.copy(mergePhase = VideoMergePhase.Available, mergeProgress = null) }
                    } else {
                        _state.update {
                            it.copy(
                                mergedCues = cues,
                                mergePhase = VideoMergePhase.Available,
                                mergeProgress = null,
                                mergeFailure = null
                            )
                        }
                    }
                }
                refreshCacheBytes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VideoFailure) {
                if (token == mergeGeneration && mergeVisible) {
                    graph.developerLog.recordFailure("video", "merge", failure.cause)
                    _state.update {
                        it.copy(mergedCues = null, mergePhase = VideoMergePhase.Failed,
                            mergeProgress = null, mergeFailure = failure.kind)
                    }
                }
            } catch (failure: Exception) {
                if (token == mergeGeneration && mergeVisible) {
                    graph.developerLog.recordFailure("video", "merge", failure)
                    _state.update {
                        it.copy(mergedCues = null, mergePhase = VideoMergePhase.Failed,
                            mergeProgress = null, mergeFailure = VideoFailureKind.Provider)
                    }
                }
            }
        }
    }

    /** Local sentence organization never reuses model group caches or sends text to a provider. */
    fun regenerateMerged() {
        val current = _state.value
        val document = current.captions ?: return
        val itemId = current.item?.id ?: return
        val sessionToken = sessionGeneration
        val generation = ++mergeGeneration
        mergeBlocked = false
        mergeJob?.cancel()
        mergeRun = null
        mergeVisible = false
        pendingMergedCues = null
        _state.update {
            it.copy(mergedCues = null, mergePhase = VideoMergePhase.ReadingCache,
                mergeProgress = null, mergeFailure = null, cacheNotice = null)
        }
        viewModelScope.launch {
            var cleared = true
            try {
                graph.videos.clearCaptions(document.videoId, mergedOnly = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                cleared = false
                graph.developerLog.recordFailure("video", "merge-clear", failure)
            }
            // The clear suspends; a new track, video or cache intent owns the UI now.
            if (generation != mergeGeneration || sessionToken != sessionGeneration ||
                _state.value.item?.id != itemId
            ) {
                return@launch
            }
            if (!cleared) {
                _state.update {
                    it.copy(
                        mergePhase = VideoMergePhase.Failed,
                        mergeProgress = null,
                        mergeFailure = VideoFailureKind.Storage,
                        cacheNotice = VideoCacheNotice.Failed
                    )
                }
                refreshCacheBytes()
                return@launch
            }
            val now = _state.value
            val fresh = now.captions
            if (now.mergeEnabled && fresh != null && fresh.videoId == document.videoId &&
                fresh.trackId == document.trackId && fresh.sourceHash == document.sourceHash
            ) {
                startMerge(fresh)
            }
            refreshCacheBytes()
        }
    }

    /**
     * Cache clearing is content-private housekeeping: the entry, its position and its learning time
     * survive, and every request started before this clear loses the right to write into the UI.
     */
    fun clearCaptionCache(mergedOnly: Boolean) {
        val current = _state.value
        val item = current.item ?: return
        _state.update { it.copy(cacheNotice = null) }
        mergeGeneration++
        mergeJob?.cancel()
        mergeRun = null
        mergeVisible = false
        pendingMergedCues = null
        if (!mergedOnly) {
            captionGeneration++
            captionJob?.cancel()
        }
        closeLookup()
        val token = ++cacheGeneration
        viewModelScope.launch {
            try {
                graph.videos.clearCaptions(item.videoId, mergedOnly)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "cache-clear", failure)
                if (token == cacheGeneration) {
                    _state.update {
                        it.copy(
                            mergePhase = when {
                                !it.mergeEnabled -> VideoMergePhase.Off
                                it.mergedCues != null -> VideoMergePhase.Available
                                else -> VideoMergePhase.Stale
                            },
                            mergeProgress = null,
                            cacheNotice = VideoCacheNotice.Failed
                        )
                    }
                    refreshCacheBytes()
                }
                return@launch
            }
            if (token != cacheGeneration) return@launch
            mergeBlocked = true
            if (mergedOnly) {
                _state.update {
                    it.copy(
                        mergedCues = null,
                        mergePhase = if (it.mergeEnabled) VideoMergePhase.Stale else VideoMergePhase.Off,
                        mergeProgress = null,
                        mergeFailure = null,
                        cacheNotice = VideoCacheNotice.MergedCleared
                    )
                }
                refreshCacheBytes()
            } else {
                val selected = _state.value.selectedTrackId
                    ?.let { id -> _state.value.tracks.firstOrNull { it.id == id } }
                _state.update {
                    it.copy(
                        captions = null,
                        mergedCues = null,
                        captionStatus = when {
                            selected != null -> VideoCaptionStatus.Loading
                            it.tracks.isNotEmpty() -> VideoCaptionStatus.NoEnglish
                            else -> VideoCaptionStatus.Absent
                        },
                        captionFailure = null,
                        mergePhase = if (it.mergeEnabled) VideoMergePhase.Stale else VideoMergePhase.Off,
                        mergeProgress = null,
                        mergeFailure = null,
                        cacheNotice = VideoCacheNotice.AllCleared
                    )
                }
                if (selected != null) loadTrack(selected, force = true)
                refreshCacheBytes()
            }
        }
    }

    fun consumeCacheNotice() {
        _state.update { it.copy(cacheNotice = null) }
    }

    private fun refreshCacheBytes() {
        val item = _state.value.item ?: return
        cacheBytesJob?.cancel()
        cacheBytesJob = viewModelScope.launch {
            try {
                val all = graph.videos.captionCacheBytes(item.videoId, mergedOnly = false)
                val merged = graph.videos.captionCacheBytes(item.videoId, mergedOnly = true)
                if (_state.value.item?.id == item.id) {
                    _state.update { it.copy(cacheAllBytes = all, cacheMergedBytes = merged) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "cache-size", failure)
            }
        }
    }

    /**
     * A lookup pauses the picture before any request leaves the device and records the exact cue
     * and word. Closing it never resumes: only a new, explicit play action can.
     */
    fun openLookup(cueId: Int, selection: CaptionSelection) {
        val current = _state.value
        val document = current.captions ?: return
        val item = current.item ?: return
        val cues = current.displayedCues
        val index = cues.indexOfFirst { it.id == cueId }
        if (index < 0) return
        val cue = cues[index]
        if (selection.start < 0 || selection.end > cue.text.length || selection.start >= selection.end) return
        val pausedByLookup = current.lookupPausedPlayback ||
            (player.playWhenReady && player.playbackState != Player.STATE_ENDED)
        if (player.playWhenReady) player.pause()
        val lookup = VideoLookupRequest(
            context = videoLookupContext(
                selection = selection.term,
                cueIndex = index,
                sourceStart = selection.start,
                sourceEnd = selection.end,
                cues = cues,
                sourceTitle = item.title,
                videoId = item.videoId,
                sectionKey = captionSectionKey(document, cues, index)
            ),
            cueId = cueId,
            selection = selection,
            startMs = cue.startMs
        )
        // Explanation reading time begins only when the popup reports readable content, never
        // while the model request is still pending. A replacement selection also stops the old
        // answer's readable window immediately.
        track?.explaining(false)
        _state.update { it.copy(lookup = lookup, lookupPausedPlayback = pausedByLookup) }
    }

    /** Called by the popup for its current, non-stale lookup only. */
    fun onLookupReadable(context: LookupContext, readable: Boolean) {
        val lookup = _state.value.lookup
        if (lookup == null) {
            track?.explaining(false)
            return
        }
        if (lookup.context != context) return
        track?.explaining(readable)
        if (readable) track?.interact()
    }

    fun closeLookup() {
        val lookup = _state.value.lookup ?: return
        // X, Back and an outside tap never leave the player unexpectedly running: a replay heard
        // while the answer was open is paused here and needs the ordinary play action to continue.
        if (player.playWhenReady) player.pause()
        learningSession.dismiss(lookup.context)
        graph.speech.stop()
        val deferred = pendingMergedCues
        pendingMergedCues = null
        _state.update {
            it.copy(
                lookup = null,
                lookupPausedPlayback = false,
                mergedCues = if (deferred != null && it.mergeEnabled) deferred else it.mergedCues
            )
        }
        track?.explaining(false)
    }

    /**
     * Explicit footer action: restart the sentence the lookup came from. The explanation stays
     * visible so the sentence can be read while it is heard; the explicit resume action is gone
     * because this lookup no longer owns a pause.
     */
    fun replayLookupCue() {
        val lookup = _state.value.lookup ?: return
        if (_state.value.phase !is VideoPhase.Ready) {
            closeLookup()
            return
        }
        seekTo(lookup.startMs)
        player.play()
        _state.update { it.copy(lookupPausedPlayback = false) }
        track?.interact()
    }

    /** Explicit footer action: resume only the pause this lookup caused, at the same position. */
    fun resumeLookupPlayback() {
        val pausedByLookup = _state.value.lookupPausedPlayback
        if (!pausedByLookup || _state.value.lookup == null) return
        closeLookup()
        if (_state.value.phase is VideoPhase.Ready && player.playbackState != Player.STATE_ENDED) {
            player.play()
        }
    }

    fun recordDictionaryFailure(cause: Throwable) {
        graph.developerLog.recordFailure("dictionary", "lookup", cause)
    }

    fun toggleTools() {
        _state.update { it.copy(toolsVisible = !it.toolsVisible) }
    }

    fun hideTools() {
        _state.update { it.copy(toolsVisible = false) }
    }

    /** Landscape enters with its controls visible; portrait starts with the A hidden transport. */
    fun onOrientation(landscape: Boolean) {
        if (lastLandscape == landscape) return
        lastLandscape = landscape
        _state.update { if (it.readMode) it else it.copy(toolsVisible = landscape) }
    }

    fun interact() {
        track?.interact()
    }

    private fun updateDuration() {
        val mediaDuration = player.duration
        if (mediaDuration == C.TIME_UNSET || mediaDuration <= 0L) return
        // Preparing a paused source at its exact end can report READY rather than ENDED.
        val atEnd = player.currentPosition >= mediaDuration
        _state.update { it.copy(durationMs = mediaDuration, ended = atEnd) }
    }

    private fun startTicker() {
        if (!foreground && !player.isPlaying) return
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(if (foreground) 200L else 1_000L)
                val current = _state.value
                if (current.phase !is VideoPhase.Ready) continue
                if (player.playbackState == Player.STATE_IDLE) continue
                val position = player.currentPosition.coerceAtLeast(0L)
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: current.durationMs
                _state.update { it.copy(positionMs = position, durationMs = duration) }
                val eligible = observeProgress() ?: continue
                val saved = lastSavedPosition
                if (saved < 0L || kotlin.math.abs(eligible - saved) >= POSITION_SAVE_INTERVAL_MS) persistPosition()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun trackerFor(item: VideoItem): VideoTimeTracker {
        val existing = track
        if (existing != null && trackerItemId == item.id) return existing
        if (existing != null) {
            existing.pause()
            graph.scope.launch { existing.flush() }
        }
        val created = VideoTimeTracker(graph.store, item.id, graph.scope)
        track = created
        trackerItemId = item.id
        return created
    }

    private fun lastKnownPosition(): Long {
        val current = _state.value
        if (current.phase !is VideoPhase.Ready) return current.positionMs
        // Before the media source is prepared the player has no timeline position; the stored
        // session start is the only honest value at that moment.
        if (player.playbackState == Player.STATE_IDLE) return current.positionMs
        return player.currentPosition.coerceAtLeast(0L)
    }

    private fun observeProgress(): Long? {
        val current = _state.value
        val item = current.item ?: return null
        return progress.observe(item.id, player.currentPosition, SystemClock.elapsedRealtime(),
            current.phase is VideoPhase.Ready && player.playbackState == Player.STATE_READY && player.isPlaying)
    }

    private fun persistPosition() {
        val item = _state.value.item ?: return
        observeProgress()
        val eligible = progress.position(item.id) ?: return
        val duration = _state.value.durationMs
        val value = eligible.let { if (duration > 0L) it.coerceAtMost(duration) else it }
        if (value == lastSavedPosition) return
        lastSavedPosition = value
        // Serial app-scoped writes outlive Activity/service teardown, without letting an older
        // checkpoint complete after a newer one and rewind the library's resume position.
        val previous = positionWrite
        positionWrite = graph.scope.launch {
            previous?.join()
            try { graph.store.saveVideoPosition(item.id, value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "position", failure)
                if (_state.value.item?.id == item.id && lastSavedPosition == value) lastSavedPosition = -1L
            }
        }
    }

    /**
     * A media failure keeps the stored entry and any readable cached subtitle text. It never
     * pretends the video is playable and never resolves or downloads anything in the background.
     */
    private suspend fun failLoad(kind: VideoFailureKind, id: Long?, url: String?, token: Long) {
        if (token != sessionGeneration) return
        closeLookup()
        if (player.isPlaying || player.playWhenReady) player.pause()
        stopTicker()
        val item = failureItem(id, url)
        if (token != sessionGeneration) return
        val cached = item?.let { cachedDocument(it) }
        if (token != sessionGeneration) return
        val sameItem = _state.value.item?.id == item?.id
        val document = cached ?: if (sameItem) _state.value.captions else null
        _state.update {
            it.copy(
                phase = VideoPhase.Failed(kind),
                item = item ?: it.item,
                tracks = emptyList(),
                selectedTrackId = document?.trackId ?: it.selectedTrackId,
                captions = document,
                captionStatus = when {
                    document != null -> VideoCaptionStatus.Ready
                    sameItem -> it.captionStatus
                    else -> VideoCaptionStatus.Absent
                },
                captionFailure = null,
                mergedCues = null,
                qualityChanging = false,
                mergePhase = VideoMergePhase.Off,
                mergeProgress = null,
                mergeFailure = null,
                positionMs = if (sameItem) it.positionMs else item?.positionMs ?: it.positionMs,
                durationMs = if (sameItem) it.durationMs else item?.durationMs ?: it.durationMs,
                isPlaying = false,
                playRequested = false,
                ended = false,
                lookupPausedPlayback = false
            )
        }
        if (item != null) refreshCacheBytes()
    }

    private suspend fun failureItem(id: Long?, url: String?): VideoItem? = try {
        if (id != null) {
            graph.store.findVideo(id)
        } else {
            YouTubeUrls.parse(url.orEmpty())?.videoId?.let { videoId ->
                graph.store.findVideoBySource(videoId)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        graph.developerLog.recordFailure("video", "failure-item", failure)
        null
    }

    private suspend fun cachedDocument(item: VideoItem): CaptionDocument? = try {
        val document = graph.videos.cachedCaptions(item)
        // Offline saved text follows the same identity rule as the live session: an explicit
        // choice is shown, otherwise only English. A stale automatic foreign cache is ignored.
        document?.takeIf {
            if (item.trackExplicit) it.trackId == item.selectedTrackId
            else isEnglishLanguage(it.language)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        graph.developerLog.recordFailure("video", "cached-captions", failure)
        null
    }

    /** A stream that fails after playback retained its original subtitles; load a cache if absent. */
    private fun loadCachedCaptionsAfterFailure() {
        val current = _state.value
        val item = current.item ?: return
        if (current.phase !is VideoPhase.Failed || current.captions != null) return
        viewModelScope.launch {
            val document = cachedDocument(item)
            if (document == null) return@launch
            val still = _state.value
            if (still.item?.id == item.id && still.phase is VideoPhase.Failed && still.captions == null) {
                _state.update {
                    it.copy(
                        captions = document,
                        selectedTrackId = document.trackId,
                        captionStatus = VideoCaptionStatus.Ready
                    )
                }
            }
        }
    }

    private fun fail(kind: VideoFailureKind) {
        stopTicker()
        if (player.isPlaying || player.playWhenReady) player.pause()
        track?.playing(false)
        _state.update {
            it.copy(
                phase = VideoPhase.Failed(kind),
                isPlaying = false,
                playRequested = false,
                qualityChanging = false
            )
        }
        loadCachedCaptionsAfterFailure()
    }

    override fun onCleared() {
        sessionGeneration++ // Invalidate any app-scoped preference completion before releasing audio.
        persistPosition()
        progress.begin(null)
        stopTicker()
        narrationStop?.cancel()
        sessionJob?.cancel()
        metadataJob?.cancel()
        captionJob?.cancel()
        cacheBytesJob?.cancel()
        mergeJob?.cancel()
        mergeGeneration++
        val active = track
        active?.pause()
        if (active != null) graph.scope.launch { active.flush() }
        track = null
        learningSession.close()
        player.release()
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val graph: AppGraph
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VideoViewModel::class.java))
            return VideoViewModel(context.applicationContext, graph) as T
        }
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5000L
    }
}

/** Aspect ratio of the real stream; 16:9 is only the layout placeholder before the first frame. */
internal fun videoAspectRatio(width: Int, height: Int, pixelRatio: Float = 1f): Float {
    if (width <= 0 || height <= 0 || pixelRatio <= 0f) return 16f / 9f
    return (width * pixelRatio / height).coerceIn(0.2f, 5f)
}
