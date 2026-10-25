package app.peekturn.reader.video

import java.util.Locale

/**
 * The one English-language test shared by source ranking and playback policy. It is deliberately
 * free of Android/Media3 imports so source-side JVM tests can use the identical rule.
 */
internal fun isEnglishLanguage(languageTag: String): Boolean {
    val language = languageTag.trim().lowercase(Locale.ROOT)
    return language == "en" || language.startsWith("en-") || language.startsWith("en_")
}
