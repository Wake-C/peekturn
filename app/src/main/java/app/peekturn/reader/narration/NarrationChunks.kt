package app.peekturn.reader.narration

/** One provider request may voice at most this much text; popup speech keeps the same bound. */
internal const val NARRATION_CHUNK_CHARS = 190

/**
 * A complete sentence past this many provider chunks is refused rather than read in part. The bound
 * is a deliberate failure, not a truncation: a 3,000-character run with no sentence edge is almost
 * certainly malformed text, and reading only its beginning would claim more than the app can voice.
 */
internal const val NARRATION_MAX_CHUNKS = 16

/**
 * Splits one complete sentence into bounded provider requests without changing a character. Breaks
 * happen at whitespace when possible and at the hard bound only for an unbroken run. The caller still
 * stores and plays the whole sentence as one unit; these are transport chunks, never cache keys.
 */
internal fun narrationChunks(
    sentence: String,
    limit: Int = NARRATION_CHUNK_CHARS,
    maxChunks: Int = NARRATION_MAX_CHUNKS
): List<String>? {
    if (sentence.isBlank()) return null
    if (sentence.length <= limit) return listOf(sentence)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < sentence.length) {
        if (chunks.size == maxChunks) return null
        val remaining = sentence.length - start
        if (remaining <= limit) {
            chunks += sentence.substring(start)
            break
        }
        val hardEnd = start + limit
        var end = hardEnd
        var probe = hardEnd
        while (probe > start && !sentence[probe - 1].isWhitespace()) probe--
        if (probe > start) end = probe - 1
        if (end <= start) end = hardEnd
        val chunk = sentence.substring(start, end).trimEnd()
        chunks += chunk
        start = end
        while (start < sentence.length && sentence[start].isWhitespace()) start++
    }
    return chunks.takeIf { it.isNotEmpty() }
}
