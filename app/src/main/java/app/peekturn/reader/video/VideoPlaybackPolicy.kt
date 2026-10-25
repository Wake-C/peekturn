package app.peekturn.reader.video

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.PlaybackException
import app.peekturn.reader.model.LookupContext
import java.util.Locale
import kotlin.math.abs

/** Ordering comes from the player itself; these are the only speeds the transport offers. */
internal val videoSpeeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** A point in a source restarting at the saved position unless the shared link named a time. */
internal fun playbackStartMs(itemPositionMs: Long, linkStartMs: Long?, durationMs: Long): Long {
    val candidate = (linkStartMs ?: itemPositionMs).coerceAtLeast(0L)
    return if (durationMs > 0L) candidate.coerceAtMost(durationMs) else candidate
}

/**
 * A position on the last second is the real end of the video. Reopening there must not loop the
 * ending; playback starts only when there is somewhere left to play.
 */
internal fun shouldAutoPlay(startMs: Long, durationMs: Long): Boolean =
    durationMs <= 0L || startMs < (durationMs - END_TOLERANCE_MS).coerceAtLeast(0L)

private const val END_TOLERANCE_MS = 1000L

/**
 * Only an explicit user choice is restored; an automatic earlier fallback is not intent. Without
 * one, English source captions are preferred and a video with no English track has no selection:
 * another language is never silently substituted.
 */
internal fun selectInitialTrack(
    tracks: List<CaptionTrack>,
    savedTrackId: String?,
    explicit: Boolean = true
): CaptionTrack? =
    (savedTrackId?.takeIf { explicit }?.let { saved -> tracks.firstOrNull { it.id == saved } })
        ?: preferredTrack(tracks)

/** A source track whose declared language is English, including regional tags. */
internal fun isEnglishTrack(track: CaptionTrack): Boolean = isEnglishLanguage(track.language)

/** A localized track name for the current UI language; null when the source gave no usable tag. */
internal fun localizedLanguageName(tag: String?, locale: Locale): String? {
    val raw = tag?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val language = Locale.forLanguageTag(raw.replace('_', '-'))
    if (language.language in NON_LANGUAGES) return null
    val name = language.getDisplayName(locale).trim()
    if (name.isEmpty() || name.equals(raw, ignoreCase = true)) return null
    return name
}

private val NON_LANGUAGES = setOf("", "und", "zxx", "mul")

/**
 * A real rendition switch on the same player keeps the clock, the play intention and the speed.
 * An open lookup owns its pause, so a switch behind it must not resume audio. A same rendition is
 * not a switch unless an explicit recovery forces it; an unknown rendition is never one.
 */
internal data class QualitySwitch(
    val quality: VideoQuality,
    val positionMs: Long,
    val resumeOnReady: Boolean,
    val speed: Float
)

internal fun qualitySwitch(
    qualities: List<VideoQuality>,
    selectedQualityId: String?,
    targetQualityId: String,
    positionMs: Long,
    playWhenReady: Boolean,
    lookupOpen: Boolean,
    speed: Float,
    force: Boolean = false
): QualitySwitch? {
    if (targetQualityId == selectedQualityId && !force) return null
    val quality = qualities.firstOrNull { it.id == targetQualityId } ?: return null
    return QualitySwitch(
        quality = quality,
        positionMs = positionMs.coerceAtLeast(0L),
        resumeOnReady = playWhenReady && !lookupOpen,
        speed = speed
    )
}

/** The rendition actually playing now, or null when the source offered no alternate quality list. */
internal fun initialQualityId(
    qualities: List<VideoQuality>,
    playback: VideoPlaybackSource,
    selectedQualityId: String?
): String? =
    selectedQualityId?.takeIf { id -> qualities.any { it.id == id } }
        ?: qualities.firstOrNull { it.source == playback }?.id

/**
 * The cue a real player position falls in. Overlapping source cues resolve to the first one that
 * contains the time. Silence between cues and after the last end has no current line: the UI must
 * never invent active subtitle text outside the source cue's own interval.
 */
internal fun activeCueIndex(cues: List<CaptionCue>, positionMs: Long): Int? {
    if (cues.isEmpty()) return null
    val containing = cues.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }
    return containing.takeIf { it >= 0 }
}

/**
 * The last cue that has started, used only for previous/next navigation. It is deliberately not
 * the visible current line, so controls stay useful through a gap without highlighting old text.
 */
internal fun navigationCueIndex(cues: List<CaptionCue>, positionMs: Long): Int? {
    if (cues.isEmpty()) return null
    val started = cues.indexOfLast { it.startMs <= positionMs }
    return started.takeIf { it >= 0 }
}

internal fun previousCueIndex(cues: List<CaptionCue>, positionMs: Long): Int? {
    val current = activeCueIndex(cues, positionMs)
    if (current != null) return (current - 1).takeIf { it >= 0 }
    // Inside a gap the last started cue is behind the playhead, so it is a legal previous target.
    return navigationCueIndex(cues, positionMs)
}

internal fun nextCueIndex(cues: List<CaptionCue>, positionMs: Long): Int? {
    if (cues.isEmpty()) return null
    val current = activeCueIndex(cues, positionMs)
    if (current != null) return (current + 1).takeIf { it <= cues.lastIndex }
    // Before the first cue the next target is the first line; after the last end there is none.
    val navigation = navigationCueIndex(cues, positionMs) ?: return 0
    return (navigation + 1).takeIf { it <= cues.lastIndex }
}

/**
 * Stable content/time identity for lookup, including multiple sentences inside one source cue.
 * The old original-cue range cannot identify these boundaries.
 */
internal fun captionSectionKey(document: CaptionDocument, displayed: List<CaptionCue>, index: Int): String {
    val cue = displayed.getOrNull(index) ?: return "${document.sourceHash}:sentence-v1:empty"
    return "${document.sourceHash}:sentence-v1:${captionSourceHash(listOf(cue))}"
}

/**
 * The lookup keeps the selected cue on both sides and only a short neighbouring line, never the
 * whole caption track. Each side is capped exactly like the EPUB reader's 30-word window.
 */
internal fun videoLookupContext(
    selection: String,
    cueIndex: Int,
    sourceStart: Int,
    sourceEnd: Int,
    cues: List<CaptionCue>,
    sourceTitle: String,
    videoId: String,
    sectionKey: String
): LookupContext {
    val cue = cues.getOrNull(cueIndex)
    val text = cue?.text.orEmpty()
    val start = sourceStart.coerceIn(0, text.length)
    val end = sourceEnd.coerceIn(start, text.length)
    val previous = cues.getOrNull(cueIndex - 1)?.text.orEmpty()
    val next = cues.getOrNull(cueIndex + 1)?.text.orEmpty()
    val before = contextWords("$previous ${text.substring(0, start)}", fromEnd = true)
    val after = contextWords("${text.substring(end)} $next", fromEnd = false)
    return LookupContext(
        selection = selection,
        before = before,
        after = after,
        sourceTitle = sourceTitle,
        sectionTitle = "",
        sourceId = "youtube:$videoId",
        sectionKey = sectionKey
    )
}

private fun contextWords(text: String, fromEnd: Boolean): String {
    val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.size <= CONTEXT_WORDS) return words.joinToString(" ")
    val kept = if (fromEnd) words.takeLast(CONTEXT_WORDS) else words.take(CONTEXT_WORDS)
    return kept.joinToString(" ")
}

private const val CONTEXT_WORDS = 30
private val WHITESPACE = Regex("[\\s\\p{Z}\\u0085]+")

/** The visible word under a tap, or null on punctuation and blank space. */
internal fun wordSelectionAt(text: String, rawOffset: Int): CaptionSelection? {
    if (text.isEmpty()) return null
    val offset = rawOffset.coerceIn(0, text.lastIndex)
    if (!text[offset].isWordCharacter()) return null
    var start = offset
    while (start > 0 && text[start - 1].isWordCharacter()) start--
    var end = offset + 1
    while (end < text.length && text[end].isWordCharacter()) end++
    return trimmedSelection(text, start, end)
}

/** A long press selects at least a word; dragging extends to the phrase under the finger. */
internal fun phraseSelection(text: String, anchorOffset: Int, focusOffset: Int): CaptionSelection? {
    if (text.isEmpty()) return null
    var start = anchorOffset.coerceIn(0, text.lastIndex)
    var end = focusOffset.coerceIn(0, text.length)
    if (start > end) {
        val swap = start
        start = end
        end = swap
    }
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    while (end < text.length && !text[end].isWhitespace()) end++
    return trimmedSelection(text, start, end)
}

private fun trimmedSelection(text: String, rawStart: Int, rawEnd: Int): CaptionSelection? {
    val part = text.substring(rawStart, rawEnd)
    val first = part.indexOfFirst { it.isLetterOrDigit() }
    if (first < 0) return null
    val last = part.indexOfLast { it.isLetterOrDigit() }
    val start = rawStart + first
    val end = rawStart + last + 1
    val term = text.substring(start, end)
    return if (term.isBlank()) null else CaptionSelection(start, end, term)
}

private fun Char.isWordCharacter(): Boolean = isLetterOrDigit() || this == '\'' || this == '’' || this == '-' || this == '_'

/** One selected range inside a displayed cue; both offsets are UTF-16 indices into its text. */
internal data class CaptionSelection(val start: Int, val end: Int, val term: String)

/**
 * A text offset is not proof of a hit: Compose maps a tap in unused space to the nearest character.
 * Only a point inside one of the word's rendered glyph rectangles may open a lookup; anything else
 * is blank space (which the caller uses to seek).
 */
internal fun wordHit(hit: CaptionSelection?, glyphBounds: List<Rect>, point: Offset): CaptionSelection? =
    hit?.takeIf { glyphBounds.any { bounds -> bounds.contains(point) } }

/** Clock text is compact in the transport and never shows a fabricated hour for short videos. */
internal fun formatClock(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun speedText(speed: Float): String {
    if (abs(speed - speed.toInt()) < 0.001f) return "${speed.toInt()}×"
    return String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
}

/** Cache figures are informational only; the panel never implies saved video media. */
internal fun formatCacheBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kib = bytes / 1024.0
    val mib = kib / 1024.0
    return when {
        mib >= 1.0 -> String.format(Locale.US, "%.1f MB", mib)
        kib >= 1.0 -> String.format(Locale.US, "%.1f KB", kib)
        else -> "$bytes B"
    }
}

/** A real playback failure is categorized for one retryable, app-owned error state. */
internal fun playbackFailureKind(errorCode: Int): VideoFailureKind = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> VideoFailureKind.Network
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> VideoFailureKind.Unsupported
    else -> VideoFailureKind.Unavailable
}
