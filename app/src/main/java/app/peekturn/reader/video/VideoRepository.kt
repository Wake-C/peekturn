package app.peekturn.reader.video

import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.model.ModelConfig
import kotlinx.coroutines.CancellationException

/** English source captions first; unrelated languages are never an implicit fallback. */
fun preferredTrack(tracks: List<CaptionTrack>): CaptionTrack? {
    val english = tracks.filter { isEnglishLanguage(it.language) }
    return english.firstOrNull { !it.automatic && it.translatedFrom == null }
        ?: english.firstOrNull { it.translatedFrom == null }
        ?: english.firstOrNull()
}

/**
 * The one place that turns a YouTube identity into a library entry, a playable session and cached
 * captions. Resolved media URLs live only in the returned session and are never written here.
 *
 * The public constructor is the integration contract. The internal one is the deterministic seam
 * used by tests: resolving, fetching and merging are the only source/model dependencies, so every
 * storage and cache rule is exercised without a network or a provider.
 */
class VideoRepository internal constructor(
    private val store: PeekturnStore,
    private val cache: VideoCaptionCache,
    private val resolveSource: suspend (videoId: String) -> ResolvedVideo,
    private val fetchCaptions: suspend (videoId: String, track: CaptionTrack) -> CaptionDocument,
    private val mergeGroups: suspend (ModelConfig, CaptionDocument, (Int, Int) -> Unit) -> List<CaptionGroup>,
    private val offlineSession: suspend (VideoItem) -> VideoSessionData? = { null }
) {
    constructor(
        store: PeekturnStore,
        source: YouTubeSource,
        cache: VideoCaptionCache,
        merger: CaptionMergeClient,
        offlineSession: suspend (VideoItem) -> VideoSessionData? = { null }
    ) : this(
        store = store,
        cache = cache,
        resolveSource = { videoId -> source.resolve(videoId) },
        fetchCaptions = { videoId, track -> source.captions(videoId, track) },
        mergeGroups = { config, document, progress -> merger.merge(config, document, progress) },
        offlineSession = offlineSession
    )

    /**
     * Resolves the source, stores its public metadata and returns the session that plays right away.
     * A caption problem is carried in the session, never disguised as an import failure; a media
     * problem throws and stores nothing.
     */
    suspend fun `import`(link: VideoLink, preferDownload: Boolean = true): VideoSessionData {
        val known = if (preferDownload) store.findVideoBySource(link.videoId) else null
        val cached = known?.let { offlineSession(it) }
        if (known != null && cached != null && suppliesChosenView(known, cached)) return cached
        val resolved = try { resolve(link.videoId) }
        catch (failure: VideoFailure) { if (cached != null) return cached.copy(watchFallbackFailure = failure.kind) else throw failure }
        val stored = try {
            store.upsertVideo(resolved.metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VideoFailure) {
            throw failure
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Storage, e)
        }
        // The returned session is played immediately, so this import is also an open of the entry.
        return session(opened(stored), resolved)
    }

    /** Re-resolves the temporary media URLs and restores position, track choice and cached captions. */
    suspend fun open(id: Long, preferDownload: Boolean = true): VideoSessionData {
        val stored = try {
            store.findVideo(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Storage, e)
        }
        val item = stored ?: throw VideoFailure(VideoFailureKind.Storage)
        val cached = if (preferDownload) offlineSession(item) else null
        if (cached != null && suppliesChosenView(item, cached)) return cached
        val resolved = try { resolve(item.videoId) }
        catch (failure: VideoFailure) { if (cached != null) return cached.copy(watchFallbackFailure = failure.kind) else throw failure }
        return session(opened(item), resolved)
    }

    private fun suppliesChosenView(item: VideoItem, cached: VideoSessionData): Boolean =
        if (item.readMode || preferredAudioOnly(item, store.settings.value.videoAudioOnly)) cached.resolved.audioOnly != null
        else cached.resolved.qualities.isNotEmpty()

    /**
     * The cached parsed track for a stable track identity, fetched for real only on a miss or when
     * [force] asks for a regeneration. A corrupted cache file is a miss, never empty captions.
     */
    suspend fun loadCaptions(item: VideoItem, track: CaptionTrack, force: Boolean = false): CaptionDocument {
        val videoId = item.videoId
        val expectedEpoch = cache.epoch(videoId)
        if (!force) {
            cache.original(videoId, track.id)?.let { cached ->
                if (cached.videoId == videoId && cached.trackId == track.id) return cached
            }
        }
        val document = try {
            fetchCaptions(videoId, track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VideoFailure) {
            throw failure
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Captions, e)
        }
        if (!usable(document, videoId, track.id)) throw VideoFailure(VideoFailureKind.Captions)
        cache.putOriginal(document, expectedEpoch)
        return document
    }

    /**
     * Reuses validated formatting before requesting a model. A global model change does not spend
     * another request: an older partition retains its own identity until an explicit regeneration
     * clears it. New output uses the current configuration and the generation observed before work.
     */
    suspend fun merged(
        document: CaptionDocument,
        profileId: String,
        config: ModelConfig,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<CaptionCue> {
        if (document.cues.isEmpty()) return emptyList()
        val mergeKey = captionMergeIdentity(profileId, config)
        val expectedEpoch = cache.epoch(document.videoId)
        cache.groups(document, mergeKey)?.let { cached ->
            applied(document, cached)?.let { return it }
        }
        cache.latestMerged(document)?.let { return it }
        val groups = try {
            mergeGroups(config, document, onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VideoFailure) {
            throw failure
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Provider, e)
        }
        val cues = applied(document, groups) ?: throw VideoFailure(VideoFailureKind.Provider)
        cache.putGroups(document, mergeKey, groups, expectedEpoch)
        return cues
    }

    /**
     * The parsed track already stored for this entry's remembered choice. It never resolves media
     * and never touches the network, so an offline reopen can still show readable subtitles beside
     * an honest media error; it does not make the video playable.
     */
    suspend fun cachedCaptions(item: VideoItem): CaptionDocument? {
        val trackId = item.selectedTrackId?.takeIf { it.isNotBlank() } ?: return null
        return cache.original(item.videoId, trackId)
    }

    /** Clears caption files only: the entry, its position and its learning time are untouched. */
    suspend fun clearCaptions(videoId: String, mergedOnly: Boolean) = cache.clear(videoId, mergedOnly)

    suspend fun captionCacheBytes(videoId: String, mergedOnly: Boolean = false): Long =
        cache.sizeBytes(videoId, mergedOnly)

    /**
     * Removes the entry, its cascade-deleted daily time and every caption file. The cache is cleared
     * before the row, so a producer still running cannot repopulate a deleted video.
     */
    suspend fun delete(item: VideoItem) {
        cache.clear(item.videoId, mergedOnly = false)
        try {
            store.deleteVideo(item.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Storage, e)
        }
    }

    private suspend fun session(item: VideoItem, resolved: ResolvedVideo): VideoSessionData {
        val track = trackFor(item, resolved.tracks)
        if (track == null) {
            val cleared = if (item.selectedTrackId != null && !item.trackExplicit) {
                store.selectVideoTrack(item.id, null, explicit = false)
                item.copy(selectedTrackId = null)
            } else item
            return VideoSessionData(cleared, resolved, null, resolved.captionFailure)
        }
        val chosen = rememberTrack(item, track)
        // Network captions must not delay preparing the media. The player loads a missing track
        // independently; the source cache is safe to show immediately.
        return VideoSessionData(chosen, resolved, cache.original(item.videoId, track.id))
    }

    private suspend fun rememberTrack(item: VideoItem, track: CaptionTrack): VideoItem {
        if (item.selectedTrackId == track.id) return item
        return try {
            store.selectVideoTrack(item.id, track.id, explicit = false)
            item.copy(selectedTrackId = track.id, trackExplicit = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Storage, e)
        }
    }

    private fun trackFor(item: VideoItem, tracks: List<CaptionTrack>): CaptionTrack? =
        item.selectedTrackId?.takeIf { item.trackExplicit }
            ?.let { id -> tracks.firstOrNull { it.id == id } } ?: preferredTrack(tracks)

    private suspend fun opened(item: VideoItem): VideoItem = try {
        store.markVideoOpened(item.id, remember = false)
        store.findVideo(item.id) ?: item
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        throw VideoFailure(VideoFailureKind.Storage, e)
    }

    private suspend fun resolve(videoId: String): ResolvedVideo {
        val resolved = try {
            resolveSource(videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VideoFailure) {
            throw failure
        } catch (e: Exception) {
            throw VideoFailure(VideoFailureKind.Provider, e)
        }
        // A source answer for another identity would store the wrong video; treat it as a failure.
        if (resolved.metadata.videoId != videoId) throw VideoFailure(VideoFailureKind.Provider)
        return resolved
    }

    private fun usable(document: CaptionDocument, videoId: String, trackId: String): Boolean =
        document.videoId == videoId && document.trackId == trackId &&
            document.sourceHash.isNotBlank() && document.cues.isNotEmpty() &&
            document.cues.all { it.startMs >= 0 && it.endMs > it.startMs && it.text.isNotBlank() }

    private fun applied(document: CaptionDocument, groups: List<CaptionGroup>): List<CaptionCue>? = try {
        applyCaptionGroups(document, groups)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
