package app.margin.reader.model

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
    val fontFamily: String = "serif",
    val lineHeight: Double = 1.6,
    val pageMargins: Double = 1.0,
    val paragraphSpacing: Double = 0.7,
    val scroll: Boolean = false,
    val topPaddingDp: Double = 16.0,
    val bottomPaddingDp: Double = 24.0,
    val immersive: Boolean = true
)

data class LlmProfile(val id: String, val config: ModelConfig)

data class AppSettings(
    val quickExplain: Boolean = false,
    val autoPronounce: Boolean = false,
    val accent: String = "US",
    val profiles: List<LlmProfile> = listOf(LlmProfile("default", ModelConfig())),
    val activeProfileId: String = "default",
    val reading: ReaderPreferences = ReaderPreferences(),
    val appTheme: String = "dark"
) {
    val model: ModelConfig
        get() = profiles.firstOrNull { it.id == activeProfileId }?.config ?: ModelConfig()
}

data class LookupContext(
    val selection: String,
    val before: String = "",
    val after: String = "",
    val bookTitle: String = "",
    val chapterTitle: String = ""
)

data class Explanation(
    val term: String = "",
    val partOfSpeech: String = "",
    val ukIpa: String = "",
    val usIpa: String = "",
    val meaning: String = "",
    val context: String = "",
    val examples: List<String> = emptyList()
)

data class DictionaryEntry(val word: String, val phonetic: String, val partOfSpeech: String, val meaning: String)
data class ReadingDay(val date: String, val seconds: Long)
data class BookReadingTime(val bookId: Long, val seconds: Long)
data class ReadingStats(val days: List<ReadingDay> = emptyList(), val books: List<BookReadingTime> = emptyList())
