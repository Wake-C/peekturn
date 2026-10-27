package app.peekturn.reader.video

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.model.ModelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var store: PeekturnStore
    private lateinit var cache: VideoCaptionCache
    private lateinit var repository: VideoRepository
    private var metadata = VideoMetadata(VIDEO, "The Drug To Master Reality", "Kurzgesagt", 679_000, null)
    private var tracks: List<CaptionTrack> = emptyList()
    private var trackListingFailure: VideoFailureKind? = null
    private val documents = mutableMapOf<String, CaptionDocument>()
    private var resolveFailure: VideoFailure? = null
    private var captionFailure: VideoFailure? = null
    private var resolveCalls = 0
    private var fetchCalls = 0
    private var mergeCalls = 0
    private var mergeResult: suspend (CaptionDocument) -> List<CaptionGroup> =
        { document -> document.cues.map { CaptionGroup(it.id, it.id) } }

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
        store = PeekturnStore(context)
        cache = VideoCaptionCache(temporary.newFolder("video-captions"))
        repository = VideoRepository(
            store = store,
            cache = cache,
            resolveSource = { videoId ->
                resolveCalls++
                resolveFailure?.let { throw it }
                ResolvedVideo(
                    metadata.copy(videoId = videoId),
                    VideoPlaybackSource("https://media.invalid/video.mp4", "video/mp4"),
                    tracks,
                    captionFailure = trackListingFailure
                )
            },
            fetchCaptions = { _, track ->
                fetchCalls++
                captionFailure?.let { throw it }
                documents[track.id] ?: throw VideoFailure(VideoFailureKind.Captions)
            },
            mergeGroups = { _, document, onProgress ->
                mergeCalls++
                onProgress(1, 1)
                mergeResult(document)
            }
        )
    }

    @Test fun completedOfflineEntryOpensAndReimportsWithoutResolvingOrFetching() = runBlocking {
        val item = store.upsertVideo(metadata)
        store.selectVideoPresentation(item.id, audioOnly = true, readMode = true)
        store.saveVideoPosition(item.id, 123_000)
        val local = VideoPlaybackSource("peekturn-offline://media/opaque", "audio/mp4", videoCacheKey = "opaque")
        val offline = VideoRepository(store, cache,
            resolveSource = { throw AssertionError("Offline opening must not resolve YouTube") },
            fetchCaptions = { _, _ -> throw AssertionError("Offline opening must not fetch captions") },
            mergeGroups = { _, _, _ -> throw AssertionError("Offline opening must not call a model") },
            offlineSession = { saved -> VideoSessionData(saved.copy(audioOnly = true),
                ResolvedVideo(metadata, local, emptyList(), audioOnly = local), null) })
        assertEquals(local, offline.open(item.id).resolved.audioOnly)
        assertEquals(123_000L, offline.open(item.id).item.positionMs)
        assertEquals(local, offline.`import`(VideoLink(VIDEO)).resolved.playback)
        assertEquals(123_000L, store.findVideo(item.id)!!.positionMs)
    }

    @Test fun streamingCanBypassADownloadWithoutDeletingIt() = runBlocking {
        val item = store.upsertVideo(metadata)
        store.selectVideoPresentation(item.id, audioOnly = true, readMode = false)
        var onlineResolutions = 0
        val local = VideoPlaybackSource("peekturn-offline://media/opaque", "audio/mp4", videoCacheKey = "opaque")
        val remote = VideoPlaybackSource("https://media.invalid/video.mp4", "video/mp4")
        val repo = VideoRepository(store, cache,
            resolveSource = { onlineResolutions++; ResolvedVideo(metadata, remote, emptyList()) },
            fetchCaptions = { _, _ -> throw AssertionError("No tracks") }, mergeGroups = { _, _, _ -> emptyList() },
            offlineSession = { saved -> VideoSessionData(saved, ResolvedVideo(metadata, local, emptyList(), audioOnly = local), null) })
        assertEquals(remote, repo.open(item.id, preferDownload = false).resolved.playback)
        assertEquals(1, onlineResolutions)
        assertEquals(local, repo.open(item.id).resolved.playback)
        assertEquals(1, onlineResolutions)
    }

    @Test fun savedWatchModeIsNotOverriddenByAnAudioDownload() = runBlocking {
        val item = store.upsertVideo(metadata)
        store.selectVideoPresentation(item.id, audioOnly = false, readMode = false)
        val local = VideoPlaybackSource("peekturn-offline://media/opaque", "audio/mp4", videoCacheKey = "opaque")
        val remote = VideoPlaybackSource("https://media.invalid/video.mp4", "video/mp4")
        val repo = VideoRepository(store, cache,
            resolveSource = { ResolvedVideo(metadata, remote, emptyList(), qualities = listOf(VideoQuality("240p", 240, remote))) },
            fetchCaptions = { _, _ -> throw AssertionError("No tracks") }, mergeGroups = { _, _, _ -> emptyList() },
            offlineSession = { saved -> VideoSessionData(saved.copy(audioOnly = true),
                ResolvedVideo(metadata, local, emptyList(), audioOnly = local), null) })
        val session = repo.open(item.id)
        assertFalse("Reopening Watch online must not force audio-only", session.item.audioOnly!!)
        assertEquals(remote, session.resolved.playback)
        assertEquals(remote, repo.`import`(VideoLink(VIDEO)).resolved.playback)
    }

    @Test fun savedWatchModeFallsBackToSavedAudioWhenVideoCannotResolve() = runBlocking {
        val item = store.upsertVideo(metadata)
        store.selectVideoPresentation(item.id, audioOnly = false, readMode = false)
        store.saveVideoPosition(item.id, 123_000)
        val local = VideoPlaybackSource("peekturn-offline://media/opaque", "audio/mp4", videoCacheKey = "opaque")
        val repo = VideoRepository(store, cache,
            resolveSource = { throw VideoFailure(VideoFailureKind.Network) },
            fetchCaptions = { _, _ -> throw AssertionError("No network captions") }, mergeGroups = { _, _, _ -> emptyList() },
            offlineSession = { saved -> VideoSessionData(saved.copy(audioOnly = true),
                ResolvedVideo(metadata, local, emptyList(), audioOnly = local), null) })
        val session = repo.open(item.id)
        assertEquals(local, session.resolved.playback)
        assertEquals(123_000L, session.item.positionMs)
        assertEquals(VideoFailureKind.Network, session.watchFallbackFailure)
        assertEquals(false, store.findVideo(item.id)!!.audioOnly)
    }

    @Test fun importingPreparesMediaWithoutWaitingForNetworkCaptions() = runBlocking {
        tracks = listOf(
            CaptionTrack("fr-manual", "fr", false, "https://captions.invalid/fr"),
            CaptionTrack("en-auto", "en", true, "https://captions.invalid/en-auto"),
            CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"),
            CaptionTrack("de-manual", "de", false, "https://captions.invalid/de")
        )
        documents["en-manual"] = document("en-manual")

        val session = repository.`import`(VideoLink(VIDEO))

        val stored = store.findVideoBySource(VIDEO)
        assertNotNull(stored)
        assertEquals(VIDEO, stored!!.videoId)
        assertEquals("The Drug To Master Reality", stored.title)
        assertEquals("Kurzgesagt", stored.author)
        assertEquals(679_000L, stored.durationMs)
        assertTrue(stored.lastWatchedAt > 0)
        assertEquals(listOf(stored), store.videos.value)
        assertEquals(stored.id, session.item.id)
        assertEquals(VIDEO, session.resolved.metadata.videoId)
        assertEquals("en-manual", session.item.selectedTrackId)
        assertNull(session.captions)
        assertNull(session.captionFailure)
        assertEquals(1, resolveCalls)
        assertEquals(0, fetchCalls)
        assertEquals(0L, cache.sizeBytes(VIDEO))
    }

    @Test fun aVideoWithoutAnyTrackIsStillImportedWithoutACaptionFailure() = runBlocking {
        val session = repository.`import`(VideoLink(VIDEO))

        assertNotNull(store.findVideoBySource(VIDEO))
        assertNull(session.captions)
        assertNull("A successful empty listing is not a failure", session.captionFailure)
        assertEquals(0, fetchCalls)
    }

    @Test fun aFailedCaptionListingIsReportedWhileTheVideoStaysPlayable() = runBlocking {
        trackListingFailure = VideoFailureKind.Captions

        val session = repository.`import`(VideoLink(VIDEO))

        assertNotNull("The video is imported even when its caption list failed", store.findVideoBySource(VIDEO))
        assertNull(session.captions)
        assertEquals(VideoFailureKind.Captions, session.captionFailure)
        assertEquals(VIDEO, session.resolved.metadata.videoId)
        assertEquals("https://media.invalid/video.mp4", session.resolved.playback.videoUrl)
        assertEquals("A failed listing never fetches a track", 0, fetchCalls)
    }

    @Test fun openingWithAFailedCaptionListingKeepsPositionAndReportsTheFailure() = runBlocking {
        trackListingFailure = VideoFailureKind.Network
        val item = store.upsertVideo(metadata)
        store.saveVideoPosition(item.id, 7_000)

        val session = repository.open(item.id)

        assertNull(session.captions)
        assertEquals(VideoFailureKind.Network, session.captionFailure)
        assertEquals(7_000L, session.item.positionMs)
        assertEquals(0, fetchCalls)
    }

    @Test fun captionNetworkFailureCannotBlockTheInitialMediaSession() = runBlocking {
        tracks = listOf(CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"))
        captionFailure = VideoFailure(VideoFailureKind.Network)

        val session = repository.`import`(VideoLink(VIDEO))

        assertNotNull(store.findVideoBySource(VIDEO))
        assertNull(session.captions)
        assertNull(session.captionFailure)
        assertEquals(0, fetchCalls)
        assertEquals(0L, cache.sizeBytes(VIDEO))
    }

    @Test fun mediaFailureStoresNothing() = runBlocking {
        resolveFailure = VideoFailure(VideoFailureKind.Unavailable)

        val failure = runCatching { repository.`import`(VideoLink(VIDEO)) }.exceptionOrNull()

        assertTrue(failure is VideoFailure)
        assertEquals(VideoFailureKind.Unavailable, (failure as VideoFailure).kind)
        assertTrue(store.videos.value.isEmpty())
        assertNull(store.findVideoBySource(VIDEO))
    }

    @Test fun reimportingTheSameVideoKeepsOneRowPositionAndChosenTrack() = runBlocking {
        tracks = listOf(
            CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"),
            CaptionTrack("en-auto", "en", true, "https://captions.invalid/en-auto")
        )
        documents["en-manual"] = document("en-manual")
        documents["en-auto"] = document("en-auto", automatic = true)
        val first = repository.`import`(VideoLink(VIDEO))
        store.saveVideoPosition(first.item.id, 5_000)
        store.selectVideoTrack(first.item.id, "en-auto")
        val fetchesAfterFirstImport = fetchCalls

        val session = repository.`import`(VideoLink(VIDEO))

        assertEquals(1, store.videos.value.size)
        assertEquals(5_000L, session.item.positionMs)
        assertEquals("en-auto", session.item.selectedTrackId)
        assertNull(session.captions)
        assertEquals(fetchesAfterFirstImport, fetchCalls)
    }

    @Test fun openingAMissingEntryFailsWithoutTouchingTheSource() = runBlocking {
        val failure = runCatching { repository.open(999) }.exceptionOrNull()

        assertTrue(failure is VideoFailure)
        assertEquals(VideoFailureKind.Storage, (failure as VideoFailure).kind)
        assertEquals(0, resolveCalls)
    }

    @Test fun openingResolvesFreshMediaEachTimeAndReusesCachedCaptions() = runBlocking {
        tracks = listOf(CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"))
        documents["en-manual"] = document("en-manual")
        val item = store.upsertVideo(metadata)
        store.saveVideoPosition(item.id, 12_000)

        val first = repository.open(item.id)
        repository.loadCaptions(first.item, tracks.single())
        val second = repository.open(item.id)

        assertEquals(12_000L, first.item.positionMs)
        assertEquals(12_000L, second.item.positionMs)
        assertEquals("en-manual", second.captions?.trackId)
        assertEquals("The chosen track is remembered for later offline reads", "en-manual", second.item.selectedTrackId)
        assertEquals(2, resolveCalls)
        assertEquals("The parsed track is fetched once and then served from the cache", 1, fetchCalls)
    }

    @Test fun loadCaptionsReusesTheCacheUnlessARegenerationIsRequested() = runBlocking {
        val track = CaptionTrack("en-manual", "en", false, "https://captions.invalid/en")
        documents["en-manual"] = document("en-manual")
        val item = store.upsertVideo(metadata)

        val first = repository.loadCaptions(item, track)
        val second = repository.loadCaptions(item, track)
        assertEquals(1, fetchCalls)
        assertEquals(first, second)

        repository.loadCaptions(item, track, force = true)
        assertEquals(2, fetchCalls)
    }

    @Test fun aSuccessfulSessionRemembersItsTrackForALaterLookup() = runBlocking {
        tracks = listOf(
            CaptionTrack("en-auto", "en", true, "https://captions.invalid/en-auto"),
            CaptionTrack("en-manual", "en", false, "https://captions.invalid/en")
        )
        documents["en-manual"] = document("en-manual")

        val session = repository.`import`(VideoLink(VIDEO))

        assertEquals("en-manual", session.item.selectedTrackId)
        assertEquals("en-manual", store.findVideo(session.item.id)?.selectedTrackId)
    }

    @Test fun cachedCaptionsServeTheStoredTrackWithoutTouchingTheSource() = runBlocking {
        tracks = listOf(CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"))
        documents["en-manual"] = document("en-manual")
        val session = repository.`import`(VideoLink(VIDEO))
        repository.loadCaptions(session.item, tracks.single())
        val stored = store.findVideo(session.item.id)!!
        val cachedBytes = cache.sizeBytes(VIDEO)
        val offline = VideoRepository(
            store = store,
            cache = cache,
            resolveSource = { throw AssertionError("A cached read must not resolve media") },
            fetchCaptions = { _, _ -> throw AssertionError("A cached read must not fetch captions") },
            mergeGroups = { _, _, _ -> throw AssertionError("A cached read must not merge") }
        )

        val cached = offline.cachedCaptions(stored)

        assertNotNull(cached)
        assertEquals("en-manual", cached!!.trackId)
        assertEquals(document("en-manual").cues, cached.cues)
        assertEquals("The item metadata is unchanged by the cached read", stored, store.findVideo(stored.id))
        assertEquals("The raw parsed track is preserved byte for byte", cachedBytes, cache.sizeBytes(VIDEO))
        assertEquals(document("en-manual"), cache.original(VIDEO, "en-manual"))
        assertEquals(1, resolveCalls)
        assertEquals(1, fetchCalls)
    }

    @Test fun cachedCaptionsIsNullWithoutARememberedTrackOrStoredText() = runBlocking {
        val item = store.upsertVideo(metadata)

        assertNull("No remembered track means no cached text to claim", repository.cachedCaptions(item))
        assertNull(repository.cachedCaptions(item.copy(selectedTrackId = "en-manual")))
        assertEquals(0, resolveCalls)
        assertEquals(0, fetchCalls)
    }

    @Test fun aSourceAnswerForAnotherVideoIsRejectedAndNeverCached() = runBlocking {
        val track = CaptionTrack("en-manual", "en", false, "https://captions.invalid/en")
        documents["en-manual"] = document("en-manual", videoId = "some-other-video")
        val item = store.upsertVideo(metadata)

        val failure = runCatching { repository.loadCaptions(item, track) }.exceptionOrNull()

        assertTrue(failure is VideoFailure)
        assertEquals(VideoFailureKind.Captions, (failure as VideoFailure).kind)
        assertEquals(0L, cache.sizeBytes(VIDEO))
    }

    @Test fun anEmptyCaptionPayloadIsAFailureNotAnEmptyTrack() = runBlocking {
        val track = CaptionTrack("en-manual", "en", false, "https://captions.invalid/en")
        documents["en-manual"] = document("en-manual", count = 0)
        val item = store.upsertVideo(metadata)

        val failure = runCatching { repository.loadCaptions(item, track) }.exceptionOrNull()

        assertTrue(failure is VideoFailure)
        assertEquals(VideoFailureKind.Captions, (failure as VideoFailure).kind)
    }

    @Test fun modelChangesKeepExistingCaptionsUntilExplicitRegeneration() = runBlocking {
        val document = document("en-manual")
        mergeResult = { source ->
            listOf(CaptionGroup(source.cues[0].id, source.cues[1].id), CaptionGroup(source.cues[2].id, source.cues[3].id))
        }
        val config = ModelConfig()
        store.upsertVideo(metadata)

        val first = repository.merged(document, "profile", config)
        assertEquals(1, mergeCalls)
        assertEquals(2, first.size)
        assertEquals(document.cues.first().startMs, first.first().startMs)
        assertEquals(document.cues.last().endMs, first.last().endMs)
        assertEquals(normalizedText(document.cues), normalizedText(first))
        assertTrue(repository.captionCacheBytes(VIDEO, mergedOnly = true) > 0)

        val second = repository.merged(document, "profile", config)
        assertEquals("A cache hit must not pay for another model request", 1, mergeCalls)
        assertEquals(first, second)

        val changedConfig = config.copy(model = "another-model")
        assertEquals(first, repository.merged(document, "profile", changedConfig))
        assertEquals("Changing a global model must not silently pay for another merge", 1, mergeCalls)
        assertNull("Old output must not be relabeled as the new model",
            cache.groups(document, captionMergeIdentity("profile", changedConfig)))

        repository.clearCaptions(VIDEO, mergedOnly = true)
        mergeResult = { source -> source.cues.map { CaptionGroup(it.id, it.id) } }
        assertEquals(document.cues, repository.merged(document, "profile", changedConfig))
        assertEquals(2, mergeCalls)
        assertNotNull(cache.groups(document, captionMergeIdentity("profile", changedConfig)))
    }

    @Test fun clearingMergedCaptionsKeepsTheParsedTrackAndOnlyForcesRegrouping() = runBlocking {
        val document = document("en-manual")
        tracks = listOf(CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"))
        documents["en-manual"] = document
        val item = store.upsertVideo(metadata)
        repository.loadCaptions(item, tracks.single())
        repository.merged(document, "profile", ModelConfig())
        assertEquals(1, mergeCalls)
        val fetches = fetchCalls

        repository.clearCaptions(VIDEO, mergedOnly = true)

        assertEquals(0L, repository.captionCacheBytes(VIDEO, mergedOnly = true))
        assertEquals("Parsed captions survive a merged-only clear", fetches, fetchCalls)
        assertNotNull(repository.loadCaptions(item, tracks.single()))
        assertEquals(fetches, fetchCalls)
        repository.merged(document, "profile", ModelConfig())
        assertEquals(2, mergeCalls)
    }

    @Test fun clearingAllCaptionsKeepsTheEntryPositionAndLearningTime() = runBlocking {
        tracks = listOf(CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"))
        documents["en-manual"] = document("en-manual")
        val session = repository.`import`(VideoLink(VIDEO))
        repository.loadCaptions(session.item, tracks.single())
        store.saveVideoPosition(session.item.id, 4_000)
        store.addVideoTime(session.item.id, "2026-10-14", 25)

        repository.clearCaptions(VIDEO, mergedOnly = false)

        assertEquals(0L, cache.sizeBytes(VIDEO))
        val item = store.findVideo(session.item.id)
        assertNotNull(item)
        assertEquals(4_000L, item!!.positionMs)
        assertEquals(25L, store.stats.value.videos.single().seconds)

        val reopened = repository.open(session.item.id)
        repository.loadCaptions(reopened.item, tracks.single())
        assertEquals("A cleared cache is re-fetched for real", 2, fetchCalls)
    }

    @Test fun deletingAVideoClearsOnlyItsCacheRowAndTime() = runBlocking {
        tracks = listOf(CaptionTrack("en-manual", "en", false, "https://captions.invalid/en"))
        documents["en-manual"] = document("en-manual")
        val first = repository.`import`(VideoLink(VIDEO))
        store.addVideoTime(first.item.id, "2026-10-14", 40)

        metadata = metadata.copy(videoId = "second-video", title = "Second")
        documents["en-manual"] = document("en-manual", videoId = "second-video")
        val second = repository.`import`(VideoLink("second-video"))
        repository.loadCaptions(second.item, tracks.single())
        store.addVideoTime(second.item.id, "2026-10-14", 15)

        repository.delete(first.item)

        assertNull(store.findVideo(first.item.id))
        assertNotNull(store.findVideo(second.item.id))
        assertEquals(0L, cache.sizeBytes(VIDEO))
        assertTrue("The other video keeps its captions", cache.sizeBytes("second-video") > 0)
        assertEquals(listOf(second.item.id), store.videos.value.map { it.id })
        assertEquals(listOf(second.item.id), store.stats.value.videos.map { it.itemId })
        assertEquals(15L, store.stats.value.days.single().seconds)
    }

    @Test fun aMergeThatFinishesAfterAClearCannotRepopulateTheCache() = runBlocking {
        val document = document("en-manual")
        val item = store.upsertVideo(metadata)
        store.saveVideoPosition(item.id, 3_000)
        store.addVideoTime(item.id, "2026-10-14", 20)
        mergeResult = { source ->
            cache.clear(source.videoId, mergedOnly = false)
            source.cues.map { CaptionGroup(it.id, it.id) }
        }

        val cues = repository.merged(document, "profile", ModelConfig())

        assertEquals(document.cues.size, cues.size)
        assertEquals("A stale completion is dropped, not written", 0L, repository.captionCacheBytes(VIDEO, mergedOnly = true))
        assertEquals(3_000L, store.findVideo(item.id)?.positionMs)
        assertEquals(20L, store.stats.value.videos.single().seconds)
    }

    @Test fun englishSourcesPrecedeTranslationAndUnrelatedLanguagesAreNotAutomatic() {
        val french = CaptionTrack("fr", "fr", false, "u1")
        val englishAutomatic = CaptionTrack("en-auto", "en", true, "u2")
        val englishManual = CaptionTrack("en-manual", "en", false, "u3")
        val german = CaptionTrack("de", "de", false, "u4")

        assertEquals(englishManual, preferredTrack(listOf(french, englishAutomatic, englishManual, german)))
        assertEquals(englishAutomatic, preferredTrack(listOf(french, englishAutomatic, german)))
        val translated = CaptionTrack("fr-en", "en", true, "u5", translatedFrom = "fr")
        assertEquals(translated, preferredTrack(listOf(french, translated, german)))
        assertEquals(englishAutomatic, preferredTrack(listOf(translated, englishAutomatic)))
        assertNull(preferredTrack(listOf(french, german)))
        assertNull(preferredTrack(emptyList()))
    }

    @Test fun automaticForeignFallbackIsNotRestoredButExplicitChoiceIs() = runBlocking {
        val foreign = CaptionTrack("ko", "ko", true, "url")
        tracks = listOf(foreign)
        val item = store.upsertVideo(metadata)
        store.selectVideoTrack(item.id, "ko", explicit = false)
        assertNull(repository.open(item.id).item.selectedTrackId)
        tracks = listOf(foreign, CaptionTrack("en", "en", true, "url"))
        assertEquals("en", repository.open(item.id).item.selectedTrackId)
        store.selectVideoTrack(item.id, "ko")
        assertEquals("ko", repository.open(item.id).item.selectedTrackId)
        assertTrue(repository.open(item.id).item.trackExplicit)
    }

    private fun document(
        trackId: String,
        videoId: String = VIDEO,
        count: Int = 4,
        automatic: Boolean = false
    ): CaptionDocument {
        val cues = (0 until count).map { index ->
            CaptionCue(index, index * 1_000L, index * 1_000L + 900L, "word$index sentence")
        }
        return CaptionDocument(videoId, trackId, "en", automatic, captionSourceHash(cues), cues)
    }

    private fun normalizedText(cues: List<CaptionCue>): String =
        cues.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val VIDEO = "u4LUix-BU0s"
    }
}
