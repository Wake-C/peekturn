package app.peekturn.reader.model

/** Original EPUB and its durable Readium location; percentages are display-only. */
data class Book(
    val id: Long,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val locatorJson: String? = null,
    val progression: Double = 0.0,
    val lastReadAt: Long = 0L
)

/** Sampling temperature for explanations: it controls randomness, not speed or answer length. */
data class ModelConfig(
    val name: String = "DeepSeek",
    val endpoint: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val apiKey: String = "",
    val temperature: Double = DEFAULT_TEMPERATURE
) {
    companion object {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val DEFAULT_TEMPERATURE = 0.3

        /** Only a finite value inside the supported range may be sent to a provider. */
        fun validTemperature(value: Double) = value.isFinite() && value >= MIN_TEMPERATURE && value <= MAX_TEMPERATURE

        /** Invalid, missing or non-finite stored values fall back to the documented default. */
        fun sanitizeTemperature(value: Double) = if (validTemperature(value)) value else DEFAULT_TEMPERATURE
    }
}

data class ReaderPreferences(
    val fontSize: Double = 100.0,
    /** System default or a bundled font id; explicit saved choices remain unchanged. */
    val fontFamily: String = "system",
    val lineHeight: Double = 1.6,
    /** Extra spacing between letters in em, matching Readium's own EpubPreferences unit. */
    val letterSpacing: Double = 0.0,
    val pageMargins: Double = 1.0,
    val paragraphSpacing: Double = 0.7,
    val scroll: Boolean = false,
    val topPaddingDp: Double = 16.0,
    val bottomPaddingDp: Double = 24.0,
    val immersive: Boolean = true
)

data class LlmProfile(val id: String, val config: ModelConfig)

/**
 * Where non-word pronunciation comes from: selected phrases, generated examples, Settings previews
 * and read-aloud narration all follow the one stored choice. A selected single word keeps its own
 * dictionary-first route regardless. [ANDROID] uses the installed device speech engine instead of
 * the Microsoft or Baidu services; that engine may itself use a network voice. [MICROSOFT] and
 * [BAIDU] are separate choices with no automatic cross-route fallback.
 */
enum class SentenceSpeechProvider {
    MICROSOFT,
    ANDROID,
    BAIDU;

    companion object {
        /** Missing or unrecognised stored values are repaired to this choice. */
        val DEFAULT = MICROSOFT
    }
}

data class AppSettings(
    val quickExplain: Boolean = false,
    val autoPronounce: Boolean = false,
    val accent: String = "US",
    val sentenceSpeechProvider: SentenceSpeechProvider = SentenceSpeechProvider.DEFAULT,
    val profiles: List<LlmProfile> = listOf(LlmProfile("default", ModelConfig())),
    val activeProfileId: String = "default",
    val reading: ReaderPreferences = ReaderPreferences(),
    val appTheme: String = "dark",
    /** "system", "en" or "zh"; stored as the in-app choice, never inferred from the current locale. */
    val uiLanguage: String = "system",
    val volumeKeysNarration: Boolean = false,
    val videoMergeCaptions: Boolean = false,
    /** A height preference, never an expiring stream id. */
    val videoQualityHeight: Int = 1080,
    /** Compatibility default for videos without an explicit per-video playback mode. */
    val videoAudioOnly: Boolean = false
) {
    val model: ModelConfig
        get() = profiles.firstOrNull { it.id == activeProfileId }?.config ?: ModelConfig()
}

data class LookupContext(
    val selection: String,
    val before: String = "",
    val after: String = "",
    val sourceTitle: String = "",
    val sectionTitle: String = "",
    /** Namespaced identity, for example epub:42 or youtube:videoId. */
    val sourceId: String = "",
    /** Stable source location, independent of the displayed text layout. */
    val sectionKey: String = ""
)

/**
 * Whitespace as EPUBs actually carry it: the same class the explanation client uses to count context
 * words, so a non-breaking space between a heading and its paragraph cannot defeat the shaping below.
 */
private val contextWhitespace = Regex("[\\s\\p{Z}\\u0085]+")

/**
 * A terminator that really ends a sentence: a point only counts when a capital or the end of the
 * text follows it, so decimals ("3.5") and initials ("U.S. market") are not edges.
 */
private val contextSentenceEnd = Regex("[.!?][\"'”’)\\]]*(?=\\s+[\"'“”‘’(]*[A-Z]|\\s*$)")

/**
 * The context window around a selection, as the model receives it and the popup echoes it. Readium
 * reports the surrounding text it can reach with no separator where one block ends and the next
 * begins, so a heading and the paragraph after it arrive glued together ("the bookChapter 1"). Each
 * side is collapsed to single spaces and cut back to its nearest sentence edge, which reads as
 * language instead of concatenated markup. Two words are kept when a side has them, and the
 * selection itself is never altered.
 */
internal fun contextBeforeSelection(raw: String): String {
    val collapsed = raw.replace(contextWhitespace, " ").trim()
    val edge = contextSentenceEnd.findAll(collapsed).lastOrNull() ?: return collapsed
    val tail = collapsed.substring(edge.range.last + 1).trim()
    return if (tail.count { it == ' ' } >= 1) tail else collapsed
}

internal fun contextAfterSelection(raw: String): String {
    val collapsed = raw.replace(contextWhitespace, " ").trim()
    val edge = contextSentenceEnd.find(collapsed) ?: return collapsed
    val head = collapsed.substring(0, edge.range.last + 1).trim()
    return if (head.count { it == ' ' } >= 1) head else collapsed
}

/**
 * One generated example. The English sentence is always the spoken form; the translation exists so
 * a Chinese interface can show it under that sentence without changing what pronunciation reads.
 */
data class ExplanationExample(val text: String = "", val translation: String = "") {
    val spokenText: String get() = text
}

data class Explanation(
    val term: String = "",
    val partOfSpeech: String = "",
    val ukIpa: String = "",
    val usIpa: String = "",
    val meaning: String = "",
    val context: String = "",
    val examples: List<ExplanationExample> = emptyList(),
    val synonyms: String = "",
    val antonyms: String = ""
)

data class DictionaryEntry(val word: String, val phonetic: String, val partOfSpeech: String, val meaning: String)
data class ReadingDay(val date: String, val seconds: Long)
data class BookReadingTime(val bookId: Long, val seconds: Long)
data class VideoReadingTime(val itemId: Long, val seconds: Long)
data class ReadingStats(val days: List<ReadingDay> = emptyList(), val books: List<BookReadingTime> = emptyList(),
    val videos: List<VideoReadingTime> = emptyList())
