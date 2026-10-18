package app.peekturn.reader.narration

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

/**
 * Sentence edges for read-aloud, taken from the platform ICU sentence iterator through Readium's
 * own [DefaultTextContentTokenizer]. ICU already handles decimals, ellipsis, quotes and the general
 * English sentence rules; the only addition is merging a short, evidence-backed list of titles and
 * common dotted abbreviations that ICU deliberately treats as sentence ends. This is a narrow
 * post-pass over ICU ranges, not a second sentence parser.
 */
private val mergeAfterPeriod = setOf(
    "mr.", "mrs.", "ms.", "mx.", "dr.", "prof.", "sr.", "jr.", "st.", "mt.", "vs.", "etc.",
    "e.g.", "i.e.", "cf.", "al.", "no.", "nos.", "fig.", "figs.", "vol.", "vols.", "p.", "pp.",
    "inc.", "ltd.", "co.", "corp.", "dept.", "est.", "approx.", "capt.", "col.",
    "gen.", "lt.", "sgt.", "rev.", "hon.", "gov.", "sen.", "rep.", "pres.", "u.s.", "u.k.", "u.n."
)

private val singleInitial = Regex("^[A-Za-z]\\.$")
private val dottedAcronyms = setOf("u.s.", "u.k.", "u.n.")

/**
 * A short guard for the one case a listed acronym is also a real sentence end. "The U.S. Army"
 * continues, while "He lives in the U.S. Tomorrow he leaves." does not. Only these three acronyms
 * use it; titles and initials keep their unconditional repair.
 */
private val sentenceOpeners = setOf(
    "a", "an", "the", "this", "that", "these", "those", "it", "he", "she", "they", "we", "i",
    "you", "tomorrow", "today", "yesterday", "then", "but", "however", "after", "before", "when",
    "if", "there", "here", "what", "why", "how", "his", "her", "their", "our", "my", "your", "one",
    "no", "yes", "so", "and", "or", "yet", "for", "as", "at", "in", "on", "by", "from", "with",
    "without", "not", "never", "always", "now", "later", "soon", "still", "even", "also", "only",
    "just", "more", "most", "some", "any", "all", "both", "each", "every", "another", "other",
    "such", "same", "different", "new", "old", "first", "last", "next", "final"
)

/**
 * A one-shot bridge from "what the reader can see" to Readium's utterance iterator. It drops the
 * leading sentences of the first visible block until the canonical sentence containing the visible
 * text begins, then passes everything through. The fallback after [MAX_SKIP_SEGMENTS] prevents a
 * cross-segment tokenization mismatch from swallowing the rest of the block or chapter.
 */
internal class SentenceStartSkipper(targetSentence: String) {
    private var target = canonicalSentence(targetSentence)
    private var targetBlock: String? = null
    private var segments = 0

    /** Re-arms the same shared skipper for a new playback position. */
    fun reset(targetSentence: String) {
        target = canonicalSentence(targetSentence)
        targetBlock = null
        segments = 0
    }

    /**
     * Drops leading sentences until the target, and repeats that trim whenever Readium tokenizes
     * the same start block again (it builds more than one utterance window). Other blocks pass
     * through unchanged, so earlier context and later text are never swallowed.
     */
    fun filter(data: String, ranges: List<IntRange>): List<IntRange> {
        targetBlock?.let { block ->
            if (data != block) return ranges
            val targetIndex = ranges.indexOfFirst { canonicalSentence(data.substring(it)) == target }
            return if (targetIndex < 0) ranges else ranges.drop(targetIndex)
        }
        segments++
        if (segments > MAX_SKIP_SEGMENTS) return ranges
        val targetIndex = ranges.indexOfFirst { canonicalSentence(data.substring(it)) == target }
        if (targetIndex < 0) return emptyList()
        targetBlock = data
        return ranges.drop(targetIndex)
    }

    private companion object {
        const val MAX_SKIP_SEGMENTS = 8
    }
}

@OptIn(ExperimentalReadiumApi::class)
internal class SentenceTextTokenizer(
    language: Language? = null,
    private val skipper: SentenceStartSkipper? = null
) : TextTokenizer {
    private val delegate = DefaultTextContentTokenizer(TextUnit.Sentence, language)

    override fun tokenize(data: String): List<IntRange> {
        val ranges = delegate.tokenize(data).mapNotNull { trim(data, it) }
        val merged = if (ranges.size < 2) {
            ranges
        } else {
            buildList {
                for (index in ranges.indices) {
                    val range = ranges[index]
                    val previousRaw = if (index > 0) ranges[index - 1] else null
                    if (previousRaw != null && mergesWithPrevious(data, previousRaw, range)) {
                        this[lastIndex] = this[lastIndex].first until (range.last + 1)
                    } else {
                        add(range)
                    }
                }
            }
        }
        return skipper?.filter(data, merged) ?: merged
    }

    /** ICU also returns leading/trailing spaces; the spoken unit never starts or ends with one. */
    private fun trim(text: String, range: IntRange): IntRange? {
        var start = range.first
        var end = range.last
        while (start <= end && text[start].isWhitespace()) start++
        while (end >= start && text[end].isWhitespace()) end--
        if (start > end || text.substring(start, end + 1).none { it.isLetterOrDigit() }) return null
        return start..end
    }

    /**
     * A boundary after one of the listed forms or a single initial is not a sentence edge. The
     * decision uses the raw ICU range before the boundary, not the accumulated merged range, so an
     * abbreviation cannot pull the following sentence into a growing chain. Unlisted acronyms are
     * never merged by pattern alone.
     */
    private fun mergesWithPrevious(text: String, previous: IntRange, next: IntRange): Boolean {
        val left = text.substring(previous.first, previous.last + 1).trimEnd()
        if (!left.endsWith(".")) return false
        val token = left.substring(left.indexOfLast { it.isWhitespace() } + 1).lowercase()
        if (token in dottedAcronyms) {
            val following = text.substring(next.first, next.last + 1).trimStart()
            val word = following.substringBefore(' ')
                .trim('.', ',', ';', ':', '!', '?', '"', '”', '’')
                .lowercase()
            if (word in sentenceOpeners) return false
        }
        if (token in mergeAfterPeriod) return true
        if (!singleInitial.matches(token)) return false
        val following = text.substring(next.first, next.last + 1).trimStart()
        return following.firstOrNull()?.isUpperCase() == true
    }
}

@OptIn(ExperimentalReadiumApi::class)
internal fun sentenceRanges(text: String): List<IntRange> = SentenceTextTokenizer().tokenize(text)

/**
 * The sentence containing [offset], or the next sentence when the offset sits between sentences.
 * This is what turns a middle-of-phrase start into the containing sentence's beginning; no suffix
 * is ever synthesized.
 */
internal fun sentenceContaining(text: String, offset: Int): IntRange {
    val ranges = sentenceRanges(text)
    if (ranges.isEmpty()) return 0..-1
    val at = offset.coerceIn(0, text.length)
    return ranges.firstOrNull { at in it }
        ?: ranges.firstOrNull { it.first >= at }
        ?: ranges.last()
}

/** The text spoken and cached for one sentence: whitespace collapsed, punctuation untouched. */
internal fun canonicalSentence(text: String): String =
    Regex("[\\s\\p{Z}\\u0085]+").replace(text.trim(), " ")
