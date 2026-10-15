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

data class ModelConfig(
    val name: String = "DeepSeek",
    val endpoint: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val apiKey: String = "",
    val thinking: Boolean = false
)

data class ReaderPreferences(
    val fontSize: Double = 100.0,
    val fontFamily: String = "serif",
    val lineHeight: Double = 1.6,
    val pageMargins: Double = 1.0,
    val paragraphSpacing: Double = 0.7,
    val scroll: Boolean = false,
    val theme: String = "dark"
)

data class AppSettings(
    val quickExplain: Boolean = false,
    val autoPronounce: Boolean = false,
    val accent: String = "US",
    val model: ModelConfig = ModelConfig(),
    val reading: ReaderPreferences = ReaderPreferences()
)

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
