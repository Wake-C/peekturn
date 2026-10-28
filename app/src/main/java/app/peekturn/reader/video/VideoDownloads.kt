package app.peekturn.reader.video

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import app.peekturn.reader.AppGraph
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Media3 owns jobs/retries/background execution. This adapter owns source resolution and offline selection. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoDownloads(private val context: Context, private val graph: AppGraph, private val scope: CoroutineScope) {
    private val root = File(context.noBackupFilesDir, "video-downloads")
    private val manifests = VideoDownloadManifestStore(root)
    private val database = StandaloneDatabaseProvider(context)
    private val mutableStates = MutableStateFlow<Map<Long, VideoDownloadState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var poll: Job? = null
    private val http = graph.videoMediaHttpClient.newBuilder()
        .eventListenerFactory(VideoHttpDiagnostics { event ->
            graph.developerLog.recordEvent("video-download-http", event)
        }).build()

    private val cache: Cache by lazy {
        val disk = SimpleCache(File(root, "media"), NoOpCacheEvictor(), database)
        object : Cache by disk {
            override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
                mutations.remove(ContentMetadata.KEY_REDIRECTED_URI)
                disk.applyContentMetadataMutations(key, mutations)
            }
        }
    }

    val manager: DownloadManager by lazy {
        DownloadManager(context, DefaultDownloadIndex(database, "PeekturnOffline"),
            DownloaderFactory { request ->
                val spec = requireNotNull(VideoDownloadSpec.parse(request.uri))
                VideoBundleDownloader(cache, http,
                    prepare = { prepareParts(spec) },
                    removeParts = { manifests.remove(spec.itemId) { keys -> keys.forEach(cache::removeResource) } },
                    onEvent = { event -> graph.developerLog.recordEvent("video-download", "d${spec.itemId}:$event") },
                    onFailure = { failure -> graph.developerLog.recordFailure("video", "download-attempt", failure) })
            }
        ).apply {
            maxParallelDownloads = 1
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) { refresh() }
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    if (finalException != null) graph.developerLog.recordFailure("video", "download", finalException)
                    refresh()
                }
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) { refresh() }
            })
        }
    }

    /** Initialize from the app's main scope so listeners and service commands share Media3's looper. */
    fun initialize() {
        manager
        // Start the library's service while the library screen is foreground. The service owns
        // resuming persisted jobs and promoting active work to a foreground notification.
        DownloadService.start(context, VideoDownloadService::class.java)
    }

    fun download(item: VideoItem, audioOnly: Boolean, height: Int) {
        try {
            val spec = VideoDownloadSpec(item.id, audioOnly, height.coerceIn(0, 8640))
            val request = DownloadRequest.Builder(spec.id, spec.uri).setMimeType("application/vnd.peekturn.offline").build()
            DownloadService.sendAddDownload(context, VideoDownloadService::class.java, request, true)
        } catch (failure: Exception) {
            graph.developerLog.recordFailure("video", "download-start", failure)
            mutableStates.value = mutableStates.value + (item.id to VideoDownloadState(VideoDownloadPhase.Failed, audioOnly))
        }
    }

    fun retry(id: Long) {
        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) { manager.downloadIndex.getDownload("video-$id")?.request }
                if (request != null) DownloadService.sendAddDownload(context, VideoDownloadService::class.java, request, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { graph.developerLog.recordFailure("video", "download-retry", failure) }
        }
    }

    fun remove(id: Long) {
        try { DownloadService.sendRemoveDownload(context, VideoDownloadService::class.java, "video-$id", true) }
        catch (failure: Exception) {
            graph.developerLog.recordFailure("video", "download-remove", failure)
            mutableStates.value = mutableStates.value + (id to VideoDownloadState(VideoDownloadPhase.Failed,
                mutableStates.value[id]?.audioOnly ?: true))
        }
    }

    private fun refresh() {
        poll?.cancel()
        poll = scope.launch {
            do {
                val active = manager.currentDownloads.associateBy { it.request.id }
                val updated = withContext(Dispatchers.IO) {
                    buildMap {
                        manager.downloadIndex.getDownloads().use { cursor ->
                            while (cursor.moveToNext()) {
                                val stored = cursor.download
                                val download = active[stored.request.id] ?: stored
                                val spec = VideoDownloadSpec.parse(download.request.uri) ?: continue
                                val phase = when (download.state) {
                                    Download.STATE_COMPLETED -> VideoDownloadPhase.Complete
                                    Download.STATE_FAILED -> VideoDownloadPhase.Failed
                                    Download.STATE_REMOVING -> VideoDownloadPhase.Removing
                                    Download.STATE_DOWNLOADING -> VideoDownloadPhase.Downloading
                                    else -> VideoDownloadPhase.Queued
                                }
                                put(spec.itemId, VideoDownloadState(phase, spec.audioOnly,
                                    download.percentDownloaded.takeIf { it >= 0 }?.toInt()?.coerceIn(0, 100),
                                    download.bytesDownloaded, manifests.read(spec.itemId)?.captions != null))
                            }
                        }
                    }
                }
                mutableStates.value = updated
                if (updated.values.none { it.active || it.phase == VideoDownloadPhase.Removing }) break
                delay(1_000)
            } while (true)
        }
    }

    fun playbackFactory(): DataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setCacheKeyFactory { spec -> requireNotNull(spec.key) }
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)

    suspend fun session(item: VideoItem): VideoSessionData? {
        val index = withContext(Dispatchers.Main.immediate) { manager.downloadIndex }
        return withContext(Dispatchers.IO) {
            val download = index.getDownload("video-${item.id}") ?: return@withContext null
            if (download.state == Download.STATE_REMOVING) return@withContext null
            val manifest = manifests.read(item.id) ?: return@withContext null
            if (manifest.videoId != item.videoId) return@withContext null
            // A video upgrade can fail while its already-completed audio remains usable. Never
            // present a partial stream as offline: every selected byte range must be in this cache.
            fun complete(part: VideoDownloadStoredPart): Boolean {
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(part.key))
                return length > 0 && cache.isCached(part.key, 0, length)
            }
            val audioRequired = item.readMode || preferredAudioOnly(item, graph.store.settings.value.videoAudioOnly)
            val saved = manifest.playableSnapshot(audioRequired, ::complete) ?: return@withContext null
            val audio = saved.audio?.source()
            val picture = saved.video?.source(saved.videoAudio)
            val playback = picture ?: audio ?: return@withContext null
            val document = saved.captions?.takeIf { !item.trackExplicit || it.trackId == item.selectedTrackId }
            val track = document?.let { CaptionTrack(it.trackId, it.language, it.automatic, "") }
            val localItem = if (picture == null) item.copy(audioOnly = true) else item
            VideoSessionData(localItem, ResolvedVideo(
                metadata = VideoMetadata(item.videoId, item.title, item.author, item.durationMs, item.thumbnailUrl),
                playback = playback, tracks = listOfNotNull(track), audioOnly = audio,
                qualities = picture?.let { listOf(VideoQuality("${saved.height}p", saved.height, it)) }.orEmpty(),
                selectedQualityId = picture?.let { "${saved.height}p" }
            ), document)
        }
    }

    private suspend fun prepareParts(spec: VideoDownloadSpec): List<VideoDownloadPart> {
        val item = graph.store.findVideo(spec.itemId) ?: throw IOException("Video entry unavailable")
        val resolved = graph.videoSource.resolve(item.videoId)
        val audioSource = resolved.audioOnly
        val quality = if (spec.audioOnly) null else {
            preferredQuality(resolved.qualities, spec.height) ?: throw IOException("Video quality unavailable")
        }
        if (spec.audioOnly && audioSource == null) throw IOException("Independent audio unavailable")
        val downloads = linkedMapOf<String, VideoDownloadPart>()
        fun part(url: String, mime: String, key: String?): VideoDownloadStoredPart {
            val part = VideoDownloadStoredPart(key ?: "session-${UUID.randomUUID()}", mime)
            downloads[part.key] = VideoDownloadPart(part.key, url, mime)
            return part
        }
        val audio = audioSource?.let { part(it.videoUrl, it.videoMimeType, it.videoCacheKey) }
        val video = quality?.source?.let { part(it.videoUrl, it.videoMimeType, it.videoCacheKey) }
        val videoAudio = quality?.source?.let { source -> source.audioUrl?.let {
            part(it, source.audioMimeType.orEmpty(), source.audioCacheKey)
        } }
        val previous = manifests.read(item.id)
        val selected = selectInitialTrack(resolved.tracks, item.selectedTrackId, item.trackExplicit)
        val captions = selected?.let { track ->
            try {
                graph.videoCaptions.original(item.videoId, track.id)
                    ?: previous?.captions?.takeIf { it.videoId == item.videoId && it.trackId == track.id }
                    ?: graph.videoSource.captions(item.videoId, track)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                graph.developerLog.recordFailure("video", "download-captions", failure)
                null // Audio/video completion never claims missing captions exist.
            }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val manifest = VideoDownloadManifest(item.videoId, audio, video, videoAudio, quality?.height ?: 0,
            captions, (previous?.keys.orEmpty() + downloads.keys).distinct()).preservingCompleted(previous) { part ->
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(part.key))
            length > 0 && cache.isCached(part.key, 0, length)
        }
        manifests.write(item.id, manifest)
        return downloads.values.toList()
    }
}

/** Only opaque cache keys and format metadata are persisted, never temporary media addresses. */
internal data class VideoDownloadStoredPart(val key: String, val mime: String) {
    fun source(audio: VideoDownloadStoredPart? = null) = VideoPlaybackSource(uri(key), mime,
        audioUrl = audio?.let { uri(it.key) }, audioMimeType = audio?.mime,
        videoCacheKey = key, audioCacheKey = audio?.key)

    private companion object { fun uri(key: String) = "peekturn-offline://media/$key" }
}

/** A non-recursive, complete fallback keeps an interrupted replacement from hiding saved media. */
internal data class VideoDownloadSnapshot(
    val audio: VideoDownloadStoredPart?, val video: VideoDownloadStoredPart?,
    val videoAudio: VideoDownloadStoredPart?, val height: Int, val captions: CaptionDocument?
) {
    fun completeParts(complete: (VideoDownloadStoredPart) -> Boolean): VideoDownloadSnapshot {
        val picture = video?.takeIf { complete(it) && (videoAudio == null || complete(videoAudio)) }
        return copy(audio = audio?.takeIf(complete), video = picture,
            videoAudio = videoAudio.takeIf { picture != null }, height = if (picture == null) 0 else height)
    }
}

/** A saved Read track can belong to a different completed snapshot from the available picture. */
internal data class VideoDownloadAudioSnapshot(val audio: VideoDownloadStoredPart, val captions: CaptionDocument?) {
    fun snapshot() = VideoDownloadSnapshot(audio, null, null, 0, captions)
}

internal data class VideoDownloadManifest(
    val videoId: String, val audio: VideoDownloadStoredPart?, val video: VideoDownloadStoredPart?,
    val videoAudio: VideoDownloadStoredPart?, val height: Int,
    val captions: CaptionDocument?, val keys: List<String>, val fallback: VideoDownloadSnapshot? = null,
    val audioFallback: VideoDownloadAudioSnapshot? = null
) {
    /** Select one coherent bundle: never pair a new picture with an older audio revision. */
    fun playableSnapshot(
        audioRequired: Boolean = false,
        complete: (VideoDownloadStoredPart) -> Boolean
    ): VideoDownloadSnapshot? {
        val current = VideoDownloadSnapshot(audio, video, videoAudio, height, captions).completeParts(complete)
        val previous = fallback?.completeParts(complete)
        val previousAudio = audioFallback?.snapshot()?.completeParts(complete)
        val candidates = listOfNotNull(current, previous, previousAudio).filter { !audioRequired || it.audio != null }
        return candidates.firstOrNull { it.video != null } ?: candidates.firstOrNull { it.audio != null }
    }

    /** Keep at most one picture bundle and one independent audio snapshot, never a history chain. */
    fun preservingCompleted(
        previous: VideoDownloadManifest?,
        complete: (VideoDownloadStoredPart) -> Boolean
    ): VideoDownloadManifest {
        if (previous == null || previous.videoId != videoId) return copy(fallback = null, audioFallback = null)
        val pictureOrAudio = previous.playableSnapshot(complete = complete)
        val read = previous.playableSnapshot(audioRequired = true, complete = complete)
        // Usually one coherent fallback serves both modes. Only store a second caption document
        // when Read needs a different saved audio snapshot; never attach newer captions to it.
        val separateAudio = read?.let { snapshot ->
            snapshot.audio?.takeUnless {
                it == pictureOrAudio?.audio && snapshot.captions == pictureOrAudio?.captions
            }?.let { VideoDownloadAudioSnapshot(it, snapshot.captions) }
        }
        return copy(fallback = pictureOrAudio, audioFallback = separateAudio)
    }
}

/** AtomicFile recovery and writes share a lock, including Android 26–28's backup-file protocol. */
internal class VideoDownloadManifestStore(private val root: File) {
    private val gson = Gson()
    private fun file(id: Long) = android.util.AtomicFile(File(root, "entry-$id.json"))

    @Synchronized fun read(id: Long): VideoDownloadManifest? = try {
        file(id).openRead().bufferedReader(Charsets.UTF_8).use { gson.fromJson(it, VideoDownloadManifest::class.java) }
    } catch (_: Exception) { null }

    @Synchronized fun write(id: Long, value: VideoDownloadManifest) {
        root.mkdirs()
        val file = file(id)
        val output = file.startWrite()
        try {
            output.write(gson.toJson(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) { file.failWrite(output); throw failure }
    }

    @Synchronized fun remove(id: Long, removeKeys: (List<String>) -> Unit) {
        read(id)?.let { removeKeys(it.keys) }
        file(id).delete()
    }
}
