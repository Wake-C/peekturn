package app.peekturn.reader.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * One merge request never carries more than this many consecutive cues or characters, so a long
 * video is never offered to the model as one context and a window can end only between cues.
 */
internal const val CAPTION_MERGE_MAX_WINDOW_CUES = 80
internal const val CAPTION_MERGE_MAX_WINDOW_CHARACTERS = 8_000

/** Local rules the model cannot override: a merged group stays short and does not cross a pause. */
internal const val CAPTION_MERGE_MAX_DURATION_MS = 12_000L
internal const val CAPTION_MERGE_MAX_WORDS = 30
internal const val CAPTION_MERGE_MAX_GAP_MS = 1_000L

private val captionWhitespace = Regex("[\\s\\p{Z}\\u0085]+")
private val captionWord = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

internal fun normalizedCaptionText(value: String): String = value.trim().replace(captionWhitespace, " ")

/**
 * The cue windows a merge asks about, in source order. Windows are consecutive and disjoint, so a
 * cue is never repeated, dropped or moved; a single oversized cue simply forms its own window and
 * stays unmergeable rather than losing words to fit a limit.
 */
internal fun captionMergeWindows(cues: List<CaptionCue>): List<List<CaptionCue>> {
    if (cues.isEmpty()) return emptyList()
    val windows = ArrayList<List<CaptionCue>>()
    var start = 0
    var characters = 0
    var index = 0
    while (index < cues.size) {
        val length = normalizedCaptionText(cues[index].text).length
        val added = length + if (index > start) 1 else 0
        if (index - start >= CAPTION_MERGE_MAX_WINDOW_CUES ||
            index > start && characters + added > CAPTION_MERGE_MAX_WINDOW_CHARACTERS
        ) {
            windows += cues.subList(start, index).toList()
            start = index
            characters = 0
            continue
        }
        characters += added
        index++
    }
    windows += cues.subList(start, cues.size).toList()
    return windows
}

/**
 * Reads the only thing the model may decide: where each group ends. Anything that is not a JSON
 * object with an integral `endCueIds` array is rejected instead of guessed at.
 */
internal fun parseCaptionEndIds(content: String): List<Int>? {
    val root = runCatching { JsonParser.parseString(content) }.getOrNull() as? JsonObject ?: return null
    val array = root.get("endCueIds") as? JsonArray ?: return null
    val ids = ArrayList<Int>(array.size())
    for (element in array) {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isNumber) return null
        val value = primitive.asDouble
        if (value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
        ids += value.toInt()
    }
    return ids
}

/**
 * Validates model boundaries against one window and turns them into a complete partition: every
 * cue appears once and in order. Unknown, repeated, descending or missing final ids are refused,
 * because they cannot describe this window. The model owns semantic boundaries but not the local
 * budgets: a group that exceeds the duration, word or pause limit is split greedily at original
 * cue boundaries, so a valid partition still yields safe groups instead of losing the whole window.
 * A single source cue is kept as-is even when it already exceeds a limit.
 */
internal fun captionGroupsForWindow(window: List<CaptionCue>, endIds: List<Int>): List<CaptionGroup>? {
    if (window.isEmpty() || endIds.isEmpty()) return null
    // Structure first: a boundary list that does not name every cue exactly once is unrecoverable.
    val ends = ArrayList<Int>(endIds.size)
    var previous = -1
    for (endId in endIds) {
        val end = window.indexOfFirst { it.id == endId }
        if (end <= previous) return null // Unknown, repeated or out-of-order boundary.
        ends += end
        previous = end
    }
    // Cues after the last boundary would be missing from a "complete" partition.
    if (ends.last() != window.lastIndex) return null
    val groups = ArrayList<CaptionGroup>(endIds.size)
    var start = 0
    for (end in ends) {
        // Every emitted group is within the local limits; only the model's boundary is lowered.
        while (start <= end) {
            val last = if (captionGroupAllowed(window, start, end)) end
                else captionGroupEndLimit(window, start, end)
            groups += CaptionGroup(window[start].id, window[last].id)
            start = last + 1
        }
    }
    return groups
}

/** The provider chooses a sentence boundary inside this locally computed hard budget. */
internal fun captionGroupEndLimit(
    window: List<CaptionCue>,
    start: Int,
    throughIndex: Int = window.lastIndex
): Int {
    var words = 0
    var latestEnd = Long.MIN_VALUE
    for (index in start..throughIndex) {
        val cue = window[index]
        if (index > start && cue.startMs - latestEnd > CAPTION_MERGE_MAX_GAP_MS) return index - 1
        if (cue.endMs > latestEnd) latestEnd = cue.endMs
        words += captionWord.findAll(cue.text).count()
        if (latestEnd - window[start].startMs > CAPTION_MERGE_MAX_DURATION_MS ||
            words > CAPTION_MERGE_MAX_WORDS
        ) return maxOf(start, index - 1)
    }
    return throughIndex
}

private fun captionGroupAllowed(window: List<CaptionCue>, start: Int, end: Int): Boolean =
    end <= start || captionGroupEndLimit(window, start, end) == end

/**
 * The display cues for a validated partition. Text is only the original cue texts joined, a merged
 * cue starts at its first cue's start and ends at the latest end it covers, and its id is the first
 * cue id, so the result is stable and the original track remains the source of truth.
 */
fun applyCaptionGroups(document: CaptionDocument, groups: List<CaptionGroup>): List<CaptionCue> {
    if (document.cues.isEmpty()) {
        require(groups.isEmpty()) { "Caption groups must not describe an empty track." }
        return emptyList()
    }
    require(groups.isNotEmpty()) { "Caption groups must cover the whole track." }
    val merged = ArrayList<CaptionCue>(groups.size)
    var index = 0
    for (group in groups) {
        val first = document.cues.getOrNull(index)
            ?: throw IllegalArgumentException("Caption groups must partition the original cues in order.")
        require(first.id == group.firstCueId) { "Caption groups must partition the original cues in order." }
        var end = index
        while (document.cues[end].id != group.lastCueId) {
            require(end + 1 < document.cues.size) { "A caption group ends at an unknown cue." }
            end++
        }
        require(captionGroupAllowed(document.cues, index, end)) {
            "Cached caption groups must obey the same duration, word and pause limits."
        }
        val covered = document.cues.subList(index, end + 1)
        merged += CaptionCue(
            id = first.id,
            startMs = first.startMs,
            endMs = covered.maxOf { it.endMs },
            text = normalizedCaptionText(covered.joinToString(" ") { it.text })
        )
        index = end + 1
    }
    require(index == document.cues.size) { "Caption groups must cover the whole track." }
    return merged
}
