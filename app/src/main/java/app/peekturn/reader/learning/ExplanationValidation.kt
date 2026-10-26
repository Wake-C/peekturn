package app.peekturn.reader.learning

import app.peekturn.reader.model.Explanation

/** The parsed-text ceiling shared by the streaming parser and the persisted-record reader. */
internal const val EXPLANATION_MAX_RESPONSE_CHARACTERS = 128_000
internal const val EXPLANATION_RELATION_MAX_CHARACTERS = 160

internal fun validExplanationRelation(value: String): Boolean =
    value.length <= EXPLANATION_RELATION_MAX_CHARACTERS &&
        value.none { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }

/**
 * The single completeness rule for a finished explanation. The streaming parser still validates
 * each example as it closes (so a partial snapshot is never wrong); this predicate is the final
 * boundary for both a provider's `finish()` and a persisted record read back from disk.
 */
internal fun Explanation.isCompleteFor(language: ExplanationLanguage): Boolean {
    if (term.isBlank() || partOfSpeech.isBlank() || meaning.isBlank() || context.isBlank()) return false
    if (examples.size !in 1..2 || examples.any { it.text.isBlank() }) return false
    if (language == ExplanationLanguage.Chinese && examples.any { it.translation.isBlank() }) return false
    if (!validExplanationRelation(synonyms) || !validExplanationRelation(antonyms)) return false
    val characters = synonyms.length + antonyms.length + term.length + partOfSpeech.length + ukIpa.length + usIpa.length +
        meaning.length + context.length + examples.sumOf { it.text.length + it.translation.length }
    return characters <= EXPLANATION_MAX_RESPONSE_CHARACTERS
}
