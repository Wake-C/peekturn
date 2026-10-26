package app.peekturn.reader.video

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The tallest default rendition. Taller real MP4 variants stay selectable, but a phone opens at
 * this height (or the closest available below it) instead of starting on a 4K stream.
 */
private const val MAX_VIDEO_HEIGHT = 1080

/** YouTube's language code for the one machine translation the app offers. */
private const val ENGLISH_LANGUAGE_CODE = "en"

/** Response cap for a caption body: 20 MiB, checked while reading and by Content-Length. */
private const val MAX_CAPTION_BYTES = 20 * 1024 * 1024

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"

/**
 * The app's own HTTP client for source and caption requests. Timeouts are bounded, redirects stay
 * enabled, and no proxy is configured: a device-wide proxy or VPN is honoured by Android's default
 * proxy selector, and the app never hard-codes one.
 */
fun defaultVideoHttpClient(onEvent: ((String) -> Unit)? = null): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .cookieJar(VideoSessionCookies())
    .apply { if (onEvent != null) eventListenerFactory(VideoHttpDiagnostics(onEvent)) }
    .build()

/**
 * Resolves a YouTube video through NewPipeExtractor and reads its WebVTT caption tracks.
 *
 * Only the public extractor is used; no po-token or login backend exists. Server-issued anonymous
 * source cookies live only in the bounded HTTP-client session jar. Signed stream
 * URLs stay inside the returned [ResolvedVideo] and are never stored, logged or reused later.
 * Extraction runs on the IO dispatcher, coroutine cancellation cancels the active HTTP call
 * instead of waiting for a timeout, and NewPipe's process-wide statics are initialized exactly
 * once and serialized. A caption-listing failure keeps the video playable and is reported on
 * [ResolvedVideo.captionFailure].
 */
class YouTubeSource(private val http: OkHttpClient = defaultVideoHttpClient()) {
    private val downloader = OkHttpDownloader(http) { activeExtraction }

    /**
     * @throws VideoFailure with [VideoFailureKind.InvalidLink] for a malformed id and a typed
     * provider/network/content failure for everything the extractor cannot return. Cancelling the
     * caller cancels the in-flight extractor request so no work continues after the user leaves.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun resolve(videoId: String): ResolvedVideo {
        if (!YouTubeUrls.isVideoId(videoId)) throw VideoFailure(VideoFailureKind.InvalidLink)
        return withContext(Dispatchers.IO) {
            ensureExtractorInitialized()
            extractionMutex.withLock {
                val gate = ExtractionGate()
                val job = currentCoroutineContext()[Job]!!
                // NewPipe extraction is blocking; cancellation must abort the socket, not wait.
                val cancellation = job.invokeOnCompletion(
                    onCancelling = true,
                    invokeImmediately = true
                ) { cause -> if (cause != null) gate.cancel() }
                activeExtraction = gate
                try {
                    currentCoroutineContext().ensureActive()
                    val resolved = extract(videoId)
                    // A cancelled caller must not receive a late extraction result.
                    currentCoroutineContext().ensureActive()
                    resolved
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: VideoFailure) {
                    throw failure
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw videoFailureFor(error)
                } finally {
                    activeExtraction = null
                    gate.release()
                    cancellation.dispose()
                    gate.cancel()
                }
            }
        }
    }

    /**
     * Fetches one caption track. The track URL is an expiring signed URL from the same session, so
     * this is called right after [resolve]; a stale URL is a caption failure, not a media failure.
     * Cancelling the caller cancels the blocking response read.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun captions(videoId: String, track: CaptionTrack): CaptionDocument {
        if (!YouTubeUrls.isVideoId(videoId)) throw VideoFailure(VideoFailureKind.InvalidLink)
        if (!isRemoteUrl(track.url)) throw VideoFailure(VideoFailureKind.Captions)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(track.url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/vtt,text/plain;q=0.9,*/*;q=0.1")
                .build()
            val call = http.newCall(request)
            val job = currentCoroutineContext()[Job]!!
            val cancellation = job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true
            ) { cause -> if (cause != null) call.cancel() }
            try {
                call.execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) throw VideoFailure(VideoFailureKind.Captions)
                    val body = response.body ?: throw VideoFailure(VideoFailureKind.Captions)
                    if (body.contentLength() > MAX_CAPTION_BYTES) {
                        throw VideoFailure(VideoFailureKind.Captions)
                    }
                    val bytes = body.byteStream().use { input ->
                        readAtMost(input, MAX_CAPTION_BYTES)
                    }
                    parseVideoCaptions(videoId, track, String(bytes, StandardCharsets.UTF_8))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VideoFailure) {
                throw failure
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw VideoFailure(VideoFailureKind.Captions, error)
            } finally {
                cancellation.dispose()
                call.cancel()
            }
        }
    }

    private fun extract(videoId: String): ResolvedVideo {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val streamType = extractor.streamType
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            throw VideoFailure(VideoFailureKind.Unsupported)
        }
        val title = extractor.name?.trim().orEmpty()
        if (title.isEmpty()) throw VideoFailure(VideoFailureKind.Provider)
        val author = optional { extractor.uploaderName }?.trim().orEmpty()
        val durationSeconds = optional { extractor.length } ?: 0L
        val thumbnailUrl = optional { extractor.thumbnails }
            .orEmpty()
            .filter { image ->
                image.url.startsWith("https://", ignoreCase = true) && isRemoteUrl(image.url)
            }
            .maxByOrNull { image -> image.width.toLong() * image.height.toLong() }
            ?.url
        val audioChoices = extractor.audioStreams.mapNotNull { audioChoice(it, videoId) }
        val renditions = playbackRenditions(
            muxed = extractor.videoStreams.mapNotNull { videoChoice(it, videoId) },
            videoOnly = extractor.videoOnlyStreams.mapNotNull { videoChoice(it, videoId) },
            audio = audioChoices
        )
        if (renditions.isEmpty()) throw VideoFailure(VideoFailureKind.Unsupported)
        val selected = defaultRendition(renditions) ?: throw VideoFailure(VideoFailureKind.Unsupported)
        val captions = captionTracks(extractor, activeExtraction?.captionSource())
        return ResolvedVideo(
            metadata = VideoMetadata(
                videoId = videoId,
                title = title,
                author = author,
                durationMs = durationMillis(durationSeconds),
                thumbnailUrl = thumbnailUrl
            ),
            playback = playbackSource(selected),
            tracks = captions.tracks,
            captionFailure = captions.failure,
            qualities = renditions.map { rendition ->
                VideoQuality(rendition.id, rendition.height, playbackSource(rendition))
            },
            selectedQualityId = selected.id,
            audioOnly = audioOnlySource(audioChoices)
        )
    }

    /** Every quality is a session-only pair of real URLs from this resolution. */
    private fun playbackSource(rendition: VideoRendition): VideoPlaybackSource =
        VideoPlaybackSource(
            videoUrl = rendition.video.url,
            videoMimeType = rendition.video.mimeType,
            audioUrl = rendition.audio?.url,
            audioMimeType = rendition.audio?.mimeType,
            headers = playbackHeaders(rendition.video.url),
            videoCacheKey = rendition.video.cacheKey,
            audioCacheKey = rendition.audio?.cacheKey
        )

    /**
     * Listing captions is best-effort: the video is already playable and the track list is the only
     * thing at stake. A successful listing with no tracks is "no captions"; a listing or mapping
     * failure keeps the video playable and reports [VideoFailureKind.Captions] so the app can show
     * the failed state instead of claiming the video has none. VTT is requested directly because
     * [StreamInfo] exposes only TTML by default.
     *
     * NewPipe 0.26.5 strips `tlang` and exposes no translation list, so stable track identity and
     * real translation support come from the player response its extractor already fetched. The
     * downloader captures only those two pieces for the current resolve and never logs the body.
     */
    private fun captionTracks(
        extractor: StreamExtractor,
        source: CaptionSourceData?
    ): CaptionListing {
        val streams = try {
            extractor.getSubtitles(MediaFormat.VTT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CaptionListing(emptyList(), VideoFailureKind.Captions)
        }
        return try {
            val extracted = streams.mapNotNull { stream ->
                val language = stream.languageTag?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (language.isEmpty()) return@mapNotNull null
                val url = stream.content?.trim().orEmpty()
                if (!isRemoteUrl(url)) return@mapNotNull null
                CaptionStream(language, stream.isAutoGenerated, url)
            }
            CaptionListing(mapCaptionTracks(extracted, source), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CaptionListing(emptyList(), VideoFailureKind.Captions)
        }
    }

    private data class CaptionListing(val tracks: List<CaptionTrack>, val failure: VideoFailureKind?)

    private fun videoChoice(stream: VideoStream, videoId: String): VideoChoice? {
        if (stream.format != MediaFormat.MPEG_4) return null
        if (stream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return null
        val url = stream.content?.trim().orEmpty()
        if (!isRemoteUrl(url)) return null
        val codec = stream.codec.orEmpty().lowercase(Locale.ROOT)
        val avc = codec.startsWith("avc") || codec.contains("h264") || codec.contains("h.264")
        return VideoChoice(url, "video/mp4", heightOf(stream), avc,
            mediaStreamCacheKey(videoId, optional { stream.itag } ?: -1, codec, "", url))
    }

    private fun audioChoice(stream: AudioStream, videoId: String): AudioChoice? {
        if (stream.format != MediaFormat.M4A) return null
        if (stream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return null
        val url = stream.content?.trim().orEmpty()
        if (!isRemoteUrl(url)) return null
        val original = optional { stream.audioTrackType } == AudioTrackType.ORIGINAL
        val bitrate = optional { stream.averageBitrate }?.takeIf { it > 0 }
            ?: optional { stream.bitrate } ?: 0
        return AudioChoice(url, "audio/mp4", bitrate, original,
            mediaStreamCacheKey(videoId, optional { stream.itag } ?: -1, stream.codec.orEmpty(),
                optional { stream.audioTrackId }.orEmpty(), url))
    }

    private inline fun <T> optional(read: () -> T): T? = try {
        read()
    } catch (_: Exception) {
        null
    }

    private fun ensureExtractorInitialized() {
        synchronized(initLock) {
            if (extractorInitialized) return
            NewPipe.init(downloader, Localization("en"), ContentCountry("US"))
            extractorInitialized = true
        }
    }

    private companion object {
        private val initLock = Any()
        private val extractionMutex = Mutex()

        @Volatile
        private var extractorInitialized = false

        /**
         * The gate of the extraction currently holding [extractionMutex]. Extractor calls are
         * serialized process-wide, so one slot is enough; the initialized downloader reads it for
         * every request, which keeps cancellation working even when a later source instance did
         * not own NewPipe initialization.
         */
        @Volatile
        private var activeExtraction: ExtractionGate? = null
    }
}

/**
 * Cancellation for one resolve operation. The blocking downloader attaches each in-flight call, and
 * the caller's cancellation handler marks the gate so a request that starts after cancellation is
 * aborted at once instead of running to its timeout.
 */
private class ExtractionGate {
    private val call = AtomicReference<Call?>(null)
    private val captions = AtomicReference<CaptionSourceData?>(null)

    @Volatile
    private var cancelled = false

    fun attach(active: Call?) {
        call.set(active)
        if (active != null && cancelled) active.cancel()
    }

    fun cancel() {
        cancelled = true
        call.getAndSet(null)?.cancel()
    }

    fun ensureActive() {
        if (cancelled) throw IOException("Extraction cancelled")
    }

    /**
     * Keeps the first player response that actually has caption tracks. Upstream assigns the
     * Android response's renderer unconditionally, then iOS and visionOS only when the earlier
     * renderer object is null or empty. Subtitles can only be listed from a non-empty caption
     * array, so the first such array is the one that supplied the listing; downstream pairing by
     * count and language still rejects a capture whose client differed. Only language, track
     * identity, display name and the translation flag are retained; the response body and its
     * signed URLs are not.
     */
    fun captureCaptions(url: String, body: String?) {
        if (captions.get() != null || body == null) return
        if (!url.contains("/player")) return
        parseCaptionSource(body)?.let { captions.compareAndSet(null, it) }
    }

    fun captionSource(): CaptionSourceData? = captions.get()

    fun release() {
        captions.set(null)
    }
}

internal data class VideoChoice(val url: String, val mimeType: String, val height: Int,
                                val avc: Boolean = false, val cacheKey: String? = null)

internal data class AudioChoice(val url: String, val mimeType: String, val bitrate: Int = 0,
                                val original: Boolean = false, val cacheKey: String? = null)

/** A standalone audio media source, never a muxed video hidden by the UI. */
internal fun audioOnlySource(audio: List<AudioChoice>): VideoPlaybackSource? = bestAudioChoice(audio)?.let {
    VideoPlaybackSource(it.url, it.mimeType, headers = playbackHeaders(it.url), videoCacheKey = it.cacheKey)
}

/** One selectable rendition: a real MP4 video URL and, for a split stream, its real M4A pair. */
internal data class VideoRendition(val id: String, val height: Int, val video: VideoChoice,
                                   val audio: AudioChoice?)

/**
 * Turns the extractor's real streams into selectable renditions.
 *
 * One rendition per positive height; taller-than-1080p MP4 options stay visible because they are
 * real, but they are not the default. At a height, a muxed stream wins because it already carries
 * audio, then the H.264/AVC variant wins because it is the most widely decodable codec. A
 * video-only stream is only offered with a real M4A partner: a silent picture is not a rendition.
 * No quality is invented or upscaled; an unknown-height stream appears as the sole `auto` fallback
 * when nothing has a real height.
 */
internal fun playbackRenditions(
    muxed: List<VideoChoice>,
    videoOnly: List<VideoChoice>,
    audio: List<AudioChoice>
): List<VideoRendition> {
    val bestAudio = bestAudioChoice(audio)
    val heights = (muxed.map { it.height } + videoOnly.map { it.height })
        .filter { it > 0 }
        .distinct()
        .sortedDescending()
    val renditions = heights.mapNotNull { height ->
        val muxedAtHeight = muxed.filter { it.height == height }
            .sortedByDescending { it.avc }
            .firstOrNull()
        if (muxedAtHeight != null) {
            return@mapNotNull VideoRendition("${height}p", height, muxedAtHeight, null)
        }
        val video = videoOnly.filter { it.height == height }
            .sortedByDescending { it.avc }
            .firstOrNull() ?: return@mapNotNull null
        val partner = bestAudio ?: return@mapNotNull null
        VideoRendition("${height}p", height, video, partner)
    }
    if (renditions.isNotEmpty()) return renditions
    // A stream outside the height ladder is used only when its height is genuinely unknown.
    val fallbackMuxed = muxed.filter { it.height <= 0 }
        .sortedByDescending { it.avc }
        .firstOrNull()
    if (fallbackMuxed != null) return listOf(VideoRendition("auto", 0, fallbackMuxed, null))
    val fallbackVideo = videoOnly.filter { it.height <= 0 }
        .sortedByDescending { it.avc }
        .firstOrNull()
    val partner = bestAudio
    return if (fallbackVideo != null && partner != null) {
        listOf(VideoRendition("auto", 0, fallbackVideo, partner))
    } else emptyList()
}

/**
 * The default is the tallest real rendition no taller than 1080p, so 1080p is chosen when present
 * and 720p remains the best available choice when it is not. When every real option is taller than
 * 1080p, the shortest of those is used rather than claiming the video is unsupported.
 */
internal fun defaultRendition(renditions: List<VideoRendition>): VideoRendition? {
    val known = renditions.filter { it.height > 0 }
    if (known.isEmpty()) return renditions.firstOrNull()
    val bounded = known.filter { it.height <= MAX_VIDEO_HEIGHT }
    return bounded.maxByOrNull { it.height } ?: known.minByOrNull { it.height }
}

/**
 * The audio partner for a split rendition. Only streams with real M4A/AAC metadata reach here. An
 * original-language track is chosen when the extractor marked one; otherwise the best bitrate wins.
 * This never becomes a user-facing audio selector.
 */
private fun bestAudioChoice(audio: List<AudioChoice>): AudioChoice? {
    if (audio.isEmpty()) return null
    val originals = audio.filter { it.original }
    val pool = originals.ifEmpty { audio }
    return pool.maxByOrNull { it.bitrate } ?: pool.first()
}

private fun heightOf(stream: VideoStream): Int {
    if (stream.height > 0) return stream.height
    return stream.resolution.orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}

private fun durationMillis(seconds: Long): Long {
    if (seconds <= 0) return 0
    return try {
        Math.multiplyExact(seconds, 1_000L)
    } catch (_: ArithmeticException) {
        0
    }
}

internal fun isRemoteUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.rawUserInfo != null) return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    return !uri.host.isNullOrBlank()
}

/** English source captions first, then their automatic track, then the English translation. */
private fun trackRank(track: CaptionTrack): Int = when {
    isEnglishLanguage(track.language) && track.translatedFrom == null && !track.automatic -> 0
    isEnglishLanguage(track.language) && track.translatedFrom == null -> 1
    isEnglishLanguage(track.language) -> 2
    else -> 3
}

/**
 * The parts of the player caption map NewPipe 0.26.5 does not expose: the stable track identity and
 * whether translation is really offered. Values live only for the current resolve; no signed URL or
 * response body is retained.
 */
internal data class CaptionSourceTrack(
    val language: String,
    val automatic: Boolean,
    val vssId: String?,
    val name: String?,
    val translatable: Boolean
)

internal data class CaptionSourceData(
    val tracks: List<CaptionSourceTrack>,
    val translationLanguages: Set<String>
)

/** A subtitle URL the extractor mapped for this session. */
internal data class CaptionStream(val language: String, val automatic: Boolean, val url: String)

/**
 * Reads `captions.playerCaptionsTracklistRenderer` from a YouTube player response. Returns null
 * when the body has no non-empty caption list, so a later client response can supply it instead.
 * Only identity, display name and translation metadata are copied out.
 */
internal fun parseCaptionSource(body: String): CaptionSourceData? {
    if (!body.contains("\"captionTracks\"")) return null
    return runCatching {
        val root = JsonParser.parseString(body) as? JsonObject ?: return null
        val renderer = root.getAsJsonObject("captions")
            ?.getAsJsonObject("playerCaptionsTracklistRenderer") ?: return null
        val captions = renderer.getAsJsonArray("captionTracks") ?: return null
        val tracks = ArrayList<CaptionSourceTrack>(captions.size())
        for (element in captions) {
            val track = element as? JsonObject ?: continue
            val language = track.stringValue("languageCode")
                ?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() } ?: continue
            // The extractor only lists tracks with all three fields; mirror that filter here.
            if (track.stringValue("baseUrl") == null) continue
            val vssId = track.stringValue("vssId") ?: continue
            tracks.add(
                CaptionSourceTrack(
                    language = language,
                    automatic = vssId.startsWith("a."),
                    vssId = vssId,
                    name = displayName(track.getAsJsonObject("name")),
                    translatable = track.booleanValue("isTranslatable")
                )
            )
        }
        if (tracks.isEmpty()) return null
        val languages = renderer.getAsJsonArray("translationLanguages")
            ?.mapNotNull { element ->
                (element as? JsonObject)?.stringValue("languageCode")
                    ?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            }
            ?.toSet()
            .orEmpty()
        CaptionSourceData(tracks, languages)
    }.getOrNull()
}

/**
 * Maps the extractor's subtitle URLs onto public tracks. Identity comes from `vssId` (for example
 * `.en` or `a.en`), so two manual tracks in the same language no longer collapse. Without captured
 * metadata it falls back to language, kind and display name; signed query tokens never enter an id.
 *
 * An English translation is added only when the captured player map says the source track is
 * translatable and lists English among its real translation languages. Its URL keeps every query
 * field the extractor supplied and only gains `tlang=en`; the public track records the source
 * language in [CaptionTrack.translatedFrom].
 */
internal fun mapCaptionTracks(
    streams: List<CaptionStream>,
    source: CaptionSourceData?
): List<CaptionTrack> {
    // getSubtitles walks the same captionTracks array in order, so equal counts pair index by index.
    val aligned = source?.tracks?.takeIf { it.size == streams.size }
    val tracks = ArrayList<CaptionTrack>(streams.size * 2)
    val seen = HashSet<String>(streams.size * 2)
    streams.forEachIndexed { index, stream ->
        val metadata = aligned?.get(index)?.takeIf {
            it.language == stream.language && it.automatic == stream.automatic
        }
        val id = captionTrackId(stream, metadata)
        if (seen.add(id)) {
            tracks.add(
                CaptionTrack(
                    id = id,
                    language = stream.language,
                    automatic = stream.automatic,
                    url = stream.url
                )
            )
        }
        if (metadata != null &&
            supportsEnglishTranslation(stream.language, metadata, source.translationLanguages)
        ) {
            val translatedUrl = withTranslationTarget(stream.url, ENGLISH_LANGUAGE_CODE)
            if (translatedUrl != null) {
                val translatedId = "$id:translated:$ENGLISH_LANGUAGE_CODE"
                if (seen.add(translatedId)) {
                    tracks.add(
                        CaptionTrack(
                            id = translatedId,
                            language = ENGLISH_LANGUAGE_CODE,
                            automatic = true,
                            url = translatedUrl,
                            translatedFrom = stream.language
                        )
                    )
                }
            }
        }
    }
    return tracks.sortedWith(compareBy(::trackRank))
}

/** `vssId` when captured, otherwise non-secret source fields; never a signed URL token. */
internal fun captionTrackId(stream: CaptionStream, source: CaptionSourceTrack?): String {
    val vssId = source?.vssId?.trim()?.takeIf { it.isNotEmpty() }
    if (vssId != null) return "vtt:$vssId"
    val nameKey = source?.name?.let(::stableNameKey)
    val builder = StringBuilder("vtt:")
        .append(stream.language)
        .append(':')
        .append(if (stream.automatic) "asr" else "manual")
    if (nameKey != null) builder.append(':').append(nameKey)
    return builder.toString()
}

/**
 * Adds a translation target to an existing caption URL without rewriting any other parameter.
 * Returns null when the URL already names a target or is not a remote URL.
 */
internal fun withTranslationTarget(url: String, languageCode: String): String? {
    if (!isRemoteUrl(url) || languageCode.isBlank()) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (YouTubeUrls.rawQueryParameters(uri.rawQuery).any { it.first.equals("tlang", true) }) {
        return null
    }
    val fragmentStart = url.indexOf('#')
    val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
    val base = if (fragmentStart >= 0) url.substring(0, fragmentStart) else url
    val separator = when {
        !base.contains('?') -> "?"
        base.endsWith('?') || base.endsWith('&') -> ""
        else -> "&"
    }
    return "$base${separator}tlang=$languageCode$fragment"
}

private fun supportsEnglishTranslation(
    language: String,
    track: CaptionSourceTrack,
    languages: Set<String>
): Boolean = track.translatable && !isEnglishLanguage(language) &&
    languages.any { it.equals(ENGLISH_LANGUAGE_CODE, ignoreCase = true) }

private fun stableNameKey(name: String): String? {
    val builder = StringBuilder()
    var separator = false
    for (character in name.trim().lowercase(Locale.ROOT)) {
        if (character.isLetterOrDigit()) {
            if (separator && builder.isNotEmpty()) builder.append('-')
            builder.append(character)
            separator = false
            if (builder.length >= 32) break
        } else {
            separator = true
        }
    }
    return builder.toString().takeIf { it.isNotEmpty() }
}

private fun JsonObject.stringValue(name: String): String? {
    val element = get(name) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    val primitive = element.asJsonPrimitive
    return if (primitive.isString) primitive.asString else null
}

private fun JsonObject.booleanValue(name: String): Boolean {
    val element = get(name) ?: return false
    return runCatching { element.asBoolean }.getOrDefault(false)
}

private fun displayName(element: JsonObject?): String? {
    val simple = element?.stringValue("simpleText")?.trim()?.takeIf { it.isNotEmpty() }
    if (simple != null) return simple
    val runs = element?.getAsJsonArray("runs") ?: return null
    return runs.mapNotNull { run -> (run as? JsonObject)?.stringValue("text") }
        .joinToString("")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * Request headers for a signed googlevideo URL. The client marker decides the user agent and the
 * browser-only headers; query parameters are never rewritten.
 */
internal fun playbackHeaders(videoUrl: String): Map<String, String> {
    val client = queryValue(videoUrl, "c")?.uppercase(Locale.ROOT).orEmpty()
    val headers = LinkedHashMap<String, String>()
    headers["User-Agent"] = if (client == "VISIONOS") visionOsUserAgent() else DESKTOP_USER_AGENT
    headers["Accept-Language"] = "en-US,en;q=0.9"
    headers["Accept-Encoding"] = "identity"
    if (client == "WEB" || client.startsWith("WEB_")) {
        headers["Origin"] = "https://www.youtube.com"
        headers["Referer"] = "https://www.youtube.com"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "cross-site"
    }
    return headers
}

private fun queryValue(url: String, name: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return YouTubeUrls.rawQueryParameters(uri.rawQuery).firstOrNull { it.first == name }?.second
}

private fun visionOsUserAgent(): String = try {
    YoutubeParsingHelper.getVisionOsUserAgent(Localization("en"))
} catch (_: Exception) {
    DESKTOP_USER_AGENT
}

/**
 * Typed failure categories the UI can distinguish. Bot checks are provider failures (the request
 * may succeed later or from another network); 429 and transport errors are network failures.
 */
internal fun videoFailureFor(error: Throwable): VideoFailure = when (error) {
    is VideoFailure -> error
    is ReCaptchaException -> VideoFailure(VideoFailureKind.Network, error)
    is SignInConfirmNotBotException -> VideoFailure(VideoFailureKind.Provider, error)
    is GeographicRestrictionException,
    is AgeRestrictedContentException,
    is PrivateContentException,
    is PaidContentException,
    is YoutubeMusicPremiumContentException,
    is UnsupportedContentInCountryException -> VideoFailure(VideoFailureKind.Restricted, error)
    is AccountTerminatedException -> VideoFailure(VideoFailureKind.Unavailable, error)
    is ContentNotAvailableException -> VideoFailure(VideoFailureKind.Unavailable, error)
    is ContentNotSupportedException -> VideoFailure(VideoFailureKind.Unsupported, error)
    is StreamInfo.StreamExtractException -> VideoFailure(VideoFailureKind.Unavailable, error)
    is IOException -> VideoFailure(VideoFailureKind.Network, error)
    is ExtractionException -> VideoFailure(VideoFailureKind.Provider, error)
    else -> VideoFailure(VideoFailureKind.Provider, error)
}

private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (output.size().toLong() + read > limit) throw VideoFailure(VideoFailureKind.Captions)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/** NewPipe's downloader contract over OkHttp: forms, JSON handlers and redirect-aware GETs. */
private class OkHttpDownloader(
    private val client: OkHttpClient,
    private val activeGate: () -> ExtractionGate?
) : Downloader() {
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val gate = activeGate()
        gate?.ensureActive()
        val builder = Request.Builder().url(request.url()).header("User-Agent", DESKTOP_USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val contentType = request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
            ?: "application/json".toMediaTypeOrNull()
        val payload = request.dataToSend()
        val method = request.httpMethod()
        val body = when {
            payload != null -> payload.toRequestBody(contentType)
            method.equals("POST", ignoreCase = true) -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        val call = client.newCall(builder.method(method, body).build())
        gate?.attach(call)
        try {
            return call.execute().use { response ->
                // NewPipe treats 429 as a challenge and never parses it as content.
                if (response.code == 429) {
                    throw ReCaptchaException("reCaptcha challenge requested", request.url())
                }
                val body = response.body?.string()
                // Player bodies carry the only real translation metadata; keep just that, in memory.
                gate?.captureCaptions(request.url(), body)
                ExtractorResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    body,
                    response.request.url.toString()
                )
            }
        } finally {
            gate?.attach(null)
        }
    }
}
