package app.peekturn.reader.learning

import app.peekturn.reader.model.ExplanationExample
import java.util.Locale

/**
 * The language the learner reads explanations in. It is deliberately not a stored setting: the
 * effective Activity locale already combines the app-language choice with "follow system", so a
 * request resolves this at the moment it is made.
 */
enum class ExplanationLanguage {
    English,
    Chinese;

    companion object {
        /** Follows the activity's actual locale; unsupported device languages use English. */
        fun forLocale(locale: Locale): ExplanationLanguage =
            if (locale.language.equals("zh", ignoreCase = true)) Chinese else English
    }
}

/** English first, then its translation only when the interface can show one. */
internal fun ExplanationExample.displayLines(language: ExplanationLanguage): List<String> =
    if (language == ExplanationLanguage.Chinese && translation.isNotBlank()) listOf(text, translation)
    else listOf(text)
