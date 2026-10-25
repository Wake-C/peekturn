package app.peekturn.reader.video

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** The largest caption body the parser accepts, matching the source's 20 MiB response limit. */
internal const val MAX_CAPTION_PAYLOAD_CHARS = 20 * 1024 * 1024

/** The largest number of source cues accepted; the final document may be smaller after cleanup. */
internal const val MAX_CAPTION_CUES = 100_000

/**
 * Two consecutive automatic cues this close may belong to the same rolling display window. This
 * is only an adjacency gate; a repeated line is removed only with separate redraw evidence.
 */
private const val ROLLING_GAP_TOLERANCE_MS = 250L

/**
 * YouTube emits display-refresh cues of about 10 ms. Genuine speech cannot repeat a line inside
 * this window, so such a full duplicate is a redraw marker rather than spoken repetition.
 */
private const val TINY_REDRAW_MS = 100L

private val timingPattern = Regex("""^(\S+)\s*-->\s*(\S+)""")
private val longTimestampPattern = Regex("""^(\d{1,6}):([0-5]\d):([0-5]\d)[.,](\d{1,3})$""")
private val shortTimestampPattern = Regex("""^(\d{1,9}):([0-5]\d)[.,](\d{1,3})$""")
private val inlineTimingPattern = Regex("""<\d{1,3}:\d{2}:\d{2}[.,]\d{3}>""")
private val tagPattern = Regex("<[^>]*>")
private val entityPattern = Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z]+);""")
private val whitespacePattern = Regex("""\s+""")

private val namedEntities = mapOf(
    "amp" to 0x26,
    "lt" to 0x3C,
    "gt" to 0x3E,
    "quot" to 0x22,
    "apos" to 0x27,
    "nbsp" to 0x20,
    "lrm" to 0x200E,
    "rlm" to 0x200F
)

/**
 * Parses a WebVTT caption body into a stable, locally validated document.
 *
 * The payload is untrusted display data: inline cue tags are reduced to their text, entities are
 * decoded, and no markup survives into [CaptionCue.text]. Automatic tracks redraw a rolling
 * display window; a repeated leading line is removed only when time-contiguous cues also carry
 * explicit redraw structure: a tiny full-duplicate refresh cue is dropped, and a multi-line cue is
 * trimmed only when the adjacent source cue was such a refresh marker and the repeated line has no
 * word timing of its own. Timed repeats, ambiguous untimed repeats without a marker, and repeated
 * words inside a line are kept: exact word preservation wins over aggressive dedupe.
 *
 * Invariants of the result: ids are 0..n-1 in time order, `startMs >= 0`, `endMs > startMs`, text
 * is non-blank and contains only normalized display text, and [CaptionDocument.sourceHash] is
 * SHA-256 over the normalized cue text and timing sequence.
 *
 * @throws VideoFailure with [VideoFailureKind.Captions] when the body is empty, over the size or
 * cue limit, not WebVTT (an HTML error page, a bot check, or an empty 200 response), or yields no
 * usable cue. The parser never reports an empty document as success.
 */
fun parseVideoCaptions(videoId: String, track: CaptionTrack, payload: String): CaptionDocument {
    if (payload.length > MAX_CAPTION_PAYLOAD_CHARS) throw VideoFailure(VideoFailureKind.Captions)
    val body = payload.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    if (!hasWebVttHeader(body)) throw VideoFailure(VideoFailureKind.Captions)
    val source = readCues(body)
    if (source.isEmpty()) throw VideoFailure(VideoFailureKind.Captions)
    val cues = normalizeCues(source, track.automatic)
    if (cues.isEmpty() || cues.size > MAX_CAPTION_CUES) throw VideoFailure(VideoFailureKind.Captions)
    return CaptionDocument(
        videoId = videoId,
        trackId = track.id,
        language = track.language,
        automatic = track.automatic,
        sourceHash = captionSourceHash(cues),
        cues = cues
    )
}

private data class RawCue(
    val startMs: Long,
    val endMs: Long,
    val lines: List<String>,
    /** Word-timing markup per normalized line; a timed line is current speech, not carry-over. */
    val lineHasWordTiming: List<Boolean>,
    val lineWords: List<List<CaptionWord>>
)

private fun hasWebVttHeader(body: String): Boolean {
    for (line in body.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (!trimmed.startsWith("WEBVTT", ignoreCase = true)) return false
        return trimmed.length == 6 || !trimmed[6].isLetterOrDigit()
    }
    return false
}

private fun readCues(body: String): List<RawCue> {
    val lines = body.split('\n')
    val cues = ArrayList<RawCue>()
    var position = 0
    while (position < lines.size) {
        while (position < lines.size && lines[position].isEmpty()) position++
        if (position >= lines.size) break
        val blockStart = position
        // Only an empty line separates cues. YouTube's rolling captions carry a space-only line
        // inside the cue payload; treating that as a separator would drop the spoken text.
        while (position < lines.size && lines[position].isNotEmpty()) position++
        val block = lines.subList(blockStart, position)
        val first = block[0].trim()
        val nonCueBlock = first == "NOTE" || first.startsWith("NOTE ") ||
            first == "STYLE" || first.startsWith("STYLE ") ||
            first == "REGION" || first.startsWith("REGION ")
        if (nonCueBlock) continue
        var timingIndex = -1
        for (index in block.indices) {
            if (block[index].contains("-->")) {
                timingIndex = index
                break
            }
        }
        if (timingIndex < 0) continue
        val timing = parseTiming(block[timingIndex]) ?: continue
        val payloadLines = block.subList(timingIndex + 1, block.size)
        val textLines = ArrayList<String>(payloadLines.size)
        val lineHasWordTiming = ArrayList<Boolean>(payloadLines.size)
        val lineWords = ArrayList<List<CaptionWord>>(payloadLines.size)
        for (line in payloadLines) {
            val wordTimed = line.contains("<c") || line.contains("</c>") ||
                inlineTimingPattern.containsMatchIn(line)
            val normalized = normalizeCaptionLine(line)
            if (normalized != null) {
                textLines.add(normalized)
                lineHasWordTiming.add(wordTimed)
                lineWords.add(timedWords(line, timing.first, timing.second))
            }
        }
        if (textLines.isEmpty()) continue
        cues.add(
            RawCue(
                timing.first,
                timing.second,
                textLines,
                lineHasWordTiming,
                lineWords
            )
        )
        if (cues.size > MAX_CAPTION_CUES) throw VideoFailure(VideoFailureKind.Captions)
    }
    return cues
}

private fun parseTiming(line: String): Pair<Long, Long>? {
    val match = timingPattern.find(line.trim()) ?: return null
    val start = parseTimestamp(match.groupValues[1]) ?: return null
    val end = parseTimestamp(match.groupValues[2]) ?: return null
    return start to end
}

private fun parseTimestamp(token: String): Long? {
    longTimestampPattern.matchEntire(token)?.let { match ->
        return timestampMillis(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
            match.groupValues[4]
        )
    }
    shortTimestampPattern.matchEntire(token)?.let { match ->
        return timestampMillis(
            0L,
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3]
        )
    }
    return null
}

private fun timestampMillis(hours: Long, minutes: Long, seconds: Long, fraction: String): Long? {
    val millis = fraction.padEnd(3, '0').toLongOrNull() ?: return null
    return try {
        Math.addExact(
            Math.addExact(
                Math.multiplyExact(hours, 3_600_000L),
                Math.multiplyExact(minutes, 60_000L)
            ),
            Math.addExact(Math.multiplyExact(seconds, 1_000L), millis)
        )
    } catch (_: ArithmeticException) {
        null
    }
}

/** Keep only source-provided word starts; never distribute a cue's duration over its words. */
private fun timedWords(raw: String, start: Long, end: Long): List<CaptionWord> {
    if (!inlineTimingPattern.containsMatchIn(raw)) return emptyList()
    val result = ArrayList<CaptionWord>()
    var at = start
    var from = 0
    for (mark in inlineTimingPattern.findAll(raw)) {
        normalizeCaptionLine(raw.substring(from, mark.range.first))?.split(' ')?.forEachIndexed { index, word ->
            result += CaptionWord(word, at, exact = index == 0)
        }
        val next = parseTimestamp(mark.value.drop(1).dropLast(1)) ?: return emptyList()
        if (next < at || next !in start..end) return emptyList()
        at = next
        from = mark.range.last + 1
    }
    normalizeCaptionLine(raw.substring(from))?.split(' ')?.forEachIndexed { index, word ->
        result += CaptionWord(word, at, exact = index == 0)
    }
    return result
}

private fun normalizeCaptionLine(raw: String): String? {
    var text = tagPattern.replace(raw, "")
    text = decodeEntities(text)
    text = text.replace('\u00A0', ' ').replace('\u0000', ' ')
    text = whitespacePattern.replace(text, " ").trim()
    return text.ifEmpty { null }
}

private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return entityPattern.replace(text) { match ->
        val raw = match.groupValues[1]
        val codePoint = when {
            raw.startsWith("#x", ignoreCase = true) -> raw.drop(2).toIntOrNull(16)
            raw.startsWith('#') -> raw.drop(1).toIntOrNull()
            else -> namedEntities[raw.lowercase(Locale.ROOT)]
        }
        if (codePoint != null && codePoint in 1..0x10FFFF &&
            codePoint !in 0xD800..0xDFFF
        ) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }
}

private fun normalizeCues(source: List<RawCue>, automatic: Boolean): List<CaptionCue> {
    val ordered = source.sortedBy { it.startMs }
    // A refresh marker is a tiny cue that fully repeats the immediately previous display. It is
    // the corroborating rolling structure YouTube emits between long word-timed cues.
    val redrawMarkers = BooleanArray(ordered.size)
    if (automatic) {
        for (index in 1 until ordered.size) {
            redrawMarkers[index] = isTinyFullDuplicate(ordered[index - 1], ordered[index])
        }
    }
    val kept = ArrayList<RawCue>(ordered.size)
    for ((index, cue) in ordered.withIndex()) {
        var lines = cue.lines
        if (automatic && kept.isNotEmpty()) {
            val previous = kept[kept.size - 1]
            if (isRollingContinuation(previous, cue)) {
                val repeated = repeatedLeadingLines(previous.lines, lines)
                val previousRawWasMarker = index > 0 && redrawMarkers[index - 1]
                if (repeated > 0 &&
                    hasRedrawEvidence(cue, previousRawWasMarker, repeated)
                ) {
                    lines = lines.subList(repeated, lines.size)
                }
            }
        }
        if (lines.isEmpty()) continue
        kept.add(if (lines === cue.lines) cue else cue.copy(lines = lines,
            lineWords = cue.lineWords.takeLast(lines.size)))
    }
    val cues = ArrayList<CaptionCue>(kept.size)
    for (cue in kept) {
        val start = maxOf(0L, cue.startMs)
        val end = if (cue.endMs > start) cue.endMs else start + 1
        val text = cue.lines.joinToString("\n")
        val words = cue.lineWords.flatten().takeIf { values ->
            values.isNotEmpty() && normalizedCaptionText(values.joinToString(" ") { it.text }) == normalizedCaptionText(text)
        }
        cues.add(CaptionCue(cues.size, start, end, text, words))
    }
    return cues
}

private fun isRollingContinuation(previous: RawCue, cue: RawCue): Boolean =
    cue.startMs >= previous.startMs && cue.startMs <= previous.endMs + ROLLING_GAP_TOLERANCE_MS

/** A tiny cue that fully repeats the newest display line(s) of the previous cue is a refresh. */
private fun isTinyFullDuplicate(previous: RawCue, cue: RawCue): Boolean {
    val duration = cue.endMs - cue.startMs
    if (duration < 0 || duration > TINY_REDRAW_MS) return false
    if (cue.lineHasWordTiming.any { it }) return false
    if (cue.startMs < previous.startMs || cue.startMs > previous.endMs + ROLLING_GAP_TOLERANCE_MS) {
        return false
    }
    if (cue.lines.isEmpty()) return false
    return repeatedLeadingLines(previous.lines, cue.lines) == cue.lines.size
}

/**
 * Rolling redraw must be proven by display structure, never by adjacency and equal text alone.
 * Exact word preservation takes precedence over aggressive dedupe.
 *
 * - A tiny fully duplicated cue is a display refresh and is dropped.
 * - A multi-line cue is trimmed only when the adjacent source cue was such a refresh marker and
 *   the repeated leading line is untimed. A repeated first line with its own word timing is
 *   current speech and is kept, as is an ambiguous untimed repeat without a refresh marker.
 */
private fun hasRedrawEvidence(
    cue: RawCue,
    previousRawWasMarker: Boolean,
    repeatedLines: Int
): Boolean {
    if (cue.lineHasWordTiming.take(repeatedLines).any { it }) return false
    if (cue.endMs - cue.startMs <= TINY_REDRAW_MS && repeatedLines == cue.lines.size) return true
    if (repeatedLines >= cue.lines.size) return false
    return previousRawWasMarker
}

/**
 * The number of leading lines in [current] that repeat the newest lines of [previous]. YouTube's
 * rolling window redraws the last displayed line at the top of the next cue; the count is capped
 * by the shorter list so a whole duplicated cue is dropped by the caller.
 */
private fun repeatedLeadingLines(previous: List<String>, current: List<String>): Int {
    val maximum = minOf(previous.size, current.size)
    for (size in maximum downTo 1) {
        var matches = true
        for (offset in 0 until size) {
            if (previous[previous.size - size + offset] != current[offset]) {
                matches = false
                break
            }
        }
        if (matches) return size
    }
    return 0
}

internal fun captionSourceHash(cues: List<CaptionCue>): String {
    val canonical = buildString {
        for (cue in cues) {
            append(cue.startMs).append('\t').append(cue.endMs).append('\t')
            append(cue.text).append('\n')
            cue.words?.forEach { word -> append(word.startMs).append(':').append(word.exact).append(':').append(word.text).append('\n') }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return digest.toHex()
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String {
    val text = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        text.append(HEX_DIGITS[value ushr 4])
        text.append(HEX_DIGITS[value and 0x0F])
    }
    return text.toString()
}
