package app.peekturn.reader.video

import java.text.BreakIterator
import java.util.Locale

/** Deterministic whole-track sentence boundaries, independent of subtitle display blocks. */
internal enum class SentenceAlgorithm { PUNCTUATION, BREAK_ITERATOR, READABLE }

private val standaloneCaptionDirection = Regex("^(?:\\[[^\\]]+\\]\\s*)+$")

internal data class CaptionTextSpan(val from: Int, val through: Int, val cue: CaptionCue,
    val startMs: Long, val precise: Boolean)

internal fun sentenceCaptions(
    document: CaptionDocument,
    algorithm: SentenceAlgorithm = SentenceAlgorithm.READABLE
): List<CaptionCue> {
    if (document.cues.isEmpty()) return emptyList()
    val text = StringBuilder()
    val spans = ArrayList<CaptionTextSpan>()
    for (cue in document.cues) {
        val tokens = normalizedCaptionText(cue.text).split(' ')
        val timed = cue.words?.takeIf { it.map { word -> word.text } == tokens }
        for ((index, token) in tokens.withIndex()) {
            if (text.isNotEmpty()) text.append(' ')
            val from = text.length
            text.append(token)
            spans += CaptionTextSpan(from, text.length, cue,
                timed?.get(index)?.startMs ?: cue.startMs, timed?.get(index)?.exact ?: (index == 0))
        }
    }
    val content = text.toString()
    val boundaries = when (algorithm) {
        SentenceAlgorithm.PUNCTUATION, SentenceAlgorithm.READABLE -> Regex("[.!?][\\\"'”’)]*(?=\\s|$)")
            .findAll(content).map { it.range.last + 1 }.toMutableSet()
        else -> {
            val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
            iterator.setText(content)
            val result = mutableSetOf<Int>()
            var end = iterator.first()
            while (end != BreakIterator.DONE) {
                var trimmed = end
                while (trimmed > 0 && content[trimmed - 1].isWhitespace()) trimmed--
                if (trimmed > 0) result += trimmed
                end = iterator.next()
            }
            result
        }
    }
    if (algorithm == SentenceAlgorithm.READABLE && boundaries.isEmpty()) {
        // No sentence punctuation is not evidence for one track-long sentence. Keep source rows
        // rather than inventing boundaries from length or hiding a whole timeline in one row.
        return playableSentenceRows(document.cues.map { it.copy(text = normalizedCaptionText(it.text)) })
    }
    if (algorithm == SentenceAlgorithm.READABLE) {
        // Speaker changes are real boundaries. A comma alone is not one. Preserve grammatical
        // continuation across display blocks, including a block ending in "because".
        for (index in 1 until spans.size) {
            val previous = spans[index - 1]
            val current = spans[index]
            val token = content.substring(current.from, current.through)
            val previousToken = content.substring(previous.from, previous.through)
            val gap = if (current.cue.id != previous.cue.id) current.cue.startMs - previous.cue.endMs else 0L
            val connector = previousToken.lowercase().trimEnd(',', ';', ':') in
                setOf("because", "and", "or", "the", "a", "to", "of", "my", "your", "is", "are")
            fun directionOnly(cue: CaptionCue): Boolean =
                '_' !in cue.text && standaloneCaptionDirection.matches(cue.text.trim())
            val directionBoundary = current.cue.id != previous.cue.id &&
                (directionOnly(current.cue) || directionOnly(previous.cue))
            if (token == ">>" || directionBoundary || gap >= 2_000 && !connector) {
                boundaries += previous.through
            }
        }
        // Abbreviations are not full stops, even when the following name starts with a capital.
        boundaries.removeAll { end ->
            val before = content.substring(maxOf(0, end - 24), end).trimEnd()
            val title = Regex("(?i)\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc)\\.$").containsMatchIn(before)
            val initial = Regex("\\b[A-Z]\\.$").containsMatchIn(before)
            val following = content.substring(end, minOf(content.length, end + 40))
                .trimStart().substringBefore(' ').trim('>', '"', '“')
            val quotedContinuation = Regex("[.!?][\"”’']+\\)*$").containsMatchIn(before) &&
                following.firstOrNull()?.isLowerCase() == true
            title || quotedContinuation || initial && following !in setOf("And", "But", "Then", "I", "He", "She", "It", "We", "They", "You")
        }
    }
    boundaries += content.length
    val output = ArrayList<CaptionCue>()
    var wordIndex = 0
    for (end in boundaries.sorted()) {
        if (wordIndex >= spans.size) break
        val first = wordIndex
        while (wordIndex < spans.size && spans[wordIndex].through <= end) wordIndex++
        if (wordIndex == first) continue
        val covered = spans.subList(first, wordIndex)
        val start = covered.first().startMs
        val last = covered.last()
        val next = spans.getOrNull(wordIndex)
        val until = if (next != null && next.cue.id == last.cue.id && next.precise && next.startMs > start)
            next.startMs else covered.maxOf { it.cue.endMs }
        output += CaptionCue(output.size, start, maxOf(start + 1, until),
            content.substring(covered.first().from, last.through),
            timingApproximate = !covered.first().precise)
    }
    check(normalizedCaptionText(output.joinToString(" ") { it.text }) == content)
    return playableSentenceRows(output)
}

private data class PlaybackRowSpan(val firstRow: Int, val lastRow: Int, val startMs: Long,
    val endMs: Long, val timingApproximate: Boolean)

/**
 * Shared or backward anchors cannot be independent seek targets. Keep those sentences together.
 * A distinct nearest source anchor remains navigable and marked approximate; absence of word
 * times alone must not collapse a long transcript into one group. Never estimate a word time.
 * Source captions remain unchanged; all group endpoints still come from source timestamps.
 */
private fun playableSentenceRows(rows: List<CaptionCue>): List<CaptionCue> {
    val groups = ArrayList<PlaybackRowSpan>()
    for ((index, row) in rows.withIndex()) {
        var firstRow = index
        var startMs = row.startMs
        var endMs = row.endMs
        var approximate = row.timingApproximate
        while (groups.isNotEmpty() && startMs <= groups.last().startMs) {
            val previous = groups.removeAt(groups.lastIndex)
            firstRow = previous.firstRow
            approximate = previous.timingApproximate || startMs < previous.startMs
            startMs = minOf(previous.startMs, startMs)
            endMs = maxOf(previous.endMs, endMs)
        }
        groups += PlaybackRowSpan(firstRow, index, startMs, endMs, approximate)
    }
    // Spans partition the input. Build each final group's text once, never an ever-growing
    // intermediate string for every same-anchor sentence in a large untimed source block.
    return groups.mapIndexed { index, group ->
        if (group.firstRow == group.lastRow) rows[group.firstRow].copy(id = index)
        else CaptionCue(index, group.startMs, group.endMs,
            normalizedCaptionText(rows.subList(group.firstRow, group.lastRow + 1).joinToString(" ") { it.text }),
            timingApproximate = group.timingApproximate)
    }
}
