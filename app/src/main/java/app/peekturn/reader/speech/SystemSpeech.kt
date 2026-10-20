package app.peekturn.reader.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.annotation.StringRes
import app.peekturn.reader.R
import java.util.Locale

/**
 * Device-speech preparation, kept apart from the player that hosts it. What an installed voice, the
 * language API and the engine's current voice each say is a set of facts this file turns into one
 * decision; the player only supplies the Android calls and the evidence record.
 */

/**
 * Engines may report ISO 639-2/T languages ("eng") and ISO 3166-1 alpha-3 countries ("USA", "GBR"),
 * which name the same voice as the ISO 639-1 / alpha-2 identifiers this app requests. Normalizing
 * before every comparison keeps English and the requested accent recognizable instead of unsupported.
 */
internal fun normalizeLanguage(language: String): String = when (language.lowercase()) {
    "eng" -> "en"
    else -> language.lowercase()
}

internal fun normalizeCountry(country: String): String = when (country.uppercase()) {
    "USA" -> "US"
    "GBR", "UK" -> "GB"
    else -> country.uppercase()
}

/**
 * Installed English voices, best first: offline before network-only, then the requested accent, then
 * quality, latency and a stable name. A country difference is a preference, never a blocker, and an
 * accent is never a reason to reject a voice that names English.
 */
internal fun englishVoices(voices: Set<Voice>, locale: Locale): List<Voice> =
    voices.filter {
        normalizeLanguage(it.locale.language) == normalizeLanguage(locale.language) &&
            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
    }.sortedWith(
        compareBy<Voice>(
            { it.isNetworkConnectionRequired },
            { normalizeCountry(it.locale.country) != normalizeCountry(locale.country) },
            { -it.quality }, { it.latency }, { it.name }
        )
    )

internal sealed interface SpeechAvailability {
    /**
     * English can be attempted. [noticeRes] is present when the engine's own voice metadata does not
     * confirm the requested accent, or when the language answer is positive but the reported voice
     * is missing or names another language.
     */
    data class Available(@StringRes val noticeRes: Int? = null) : SpeechAvailability
    data object MissingData : SpeechAvailability
    data object Unsupported : SpeechAvailability
}

/**
 * Prepares installed English speech for [locale]. An explicit English voice is preferred: the engine
 * is asked to select the best installed one, offline before network and the requested accent before
 * the other English country, but an accent difference is only a caveat. When no voice can be selected,
 * the standard language API is asked for the requested accent, the other English country and generic
 * English.
 *
 * A positive language answer is what the engine promises: some engines, domestic Android builds in
 * particular, answer for English while their reported current voice is stale, generic or still names
 * another language. That answer is therefore honored so synthesis can be attempted, with an honest
 * caveat that the voice's language or accent metadata cannot be confirmed. A voice this app selected
 * itself must still report English - a non-English enumerated voice is never explicitly selected - and
 * missing data or unsupported answers are never turned into a promise. Every answer is handed to
 * [observed], because what an engine said is the only honest thing a later failure or log can report.
 */
internal inline fun configureSpeech(
    locale: Locale,
    voices: () -> Set<Voice>?,
    setVoice: (Voice) -> Int,
    setLanguage: (Locale) -> Int,
    currentVoice: () -> Voice?,
    observed: (SpeechObservation) -> Unit = {}
): SpeechAvailability {
    val language = normalizeLanguage(locale.language)
    val country = normalizeCountry(locale.country)
    val enumerated = try { voices() } catch (_: RuntimeException) { null }
    observed(
        SpeechObservation.VoicesListed(
            enumerated?.size,
            speechVoiceFacts(enumerated.orEmpty())
        )
    )
    for (candidate in englishVoices(enumerated.orEmpty(), locale)) {
        val fact = speechVoiceFact(candidate)
        val returned = try { setVoice(candidate) } catch (_: RuntimeException) { TextToSpeech.ERROR }
        val actual = if (returned == TextToSpeech.SUCCESS) {
            try { currentVoice() } catch (_: RuntimeException) { null }?.let(::speechVoiceFact)
        } else {
            null
        }
        val rejection = when {
            returned != TextToSpeech.SUCCESS -> "setVoice returned $returned"
            actual != null && normalizeLanguage(actual.locale.language) != language ->
                "selected voice reports ${actual.languageLabel}, not English"
            actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true ->
                "selected voice reports its data is not installed"
            else -> null
        }
        observed(SpeechObservation.VoiceTried(fact, returned, actual, rejection))
        if (rejection != null) {
            // An explicit voice whose own metadata says its data is not installed is a missing-data
            // engine, not a voice this app may hand English text to.
            if (actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
                return SpeechAvailability.MissingData
            }
            continue
        }
        // A reported voice is authoritative, including an empty country: only an unreported one may
        // fall back to what the accepted voice promised.
        val spokenCountry = actual?.locale?.country?.let(::normalizeCountry)
            ?: normalizeCountry(candidate.locale.country)
        return SpeechAvailability.Available(
            if (spokenCountry == country) null else R.string.speech_notice_accent_not_guaranteed
        )
    }
    var missingData = false
    for (probe in listOf(locale, otherEnglishCountry(locale), Locale.ENGLISH)) {
        val support = try { setLanguage(probe) } catch (_: RuntimeException) { TextToSpeech.LANG_NOT_SUPPORTED }
        val actual = try { currentVoice() } catch (_: RuntimeException) { null }?.let(::speechVoiceFact)
        observed(SpeechObservation.LanguageProbed(probe, support, actual))
        if (support == TextToSpeech.LANG_MISSING_DATA) {
            missingData = true
            continue
        }
        if (support !in TextToSpeech.LANG_AVAILABLE..TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) continue
        if (actual?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            return SpeechAvailability.MissingData
        }
        // The engine accepted English. Compatibility engines may not switch or may report the voice
        // they held before the language was set, so synthesis gets its chance and the unconfirmed
        // metadata is disclosed rather than used to refuse a working engine.
        if (actual == null || normalizeLanguage(actual.locale.language) != language) {
            return SpeechAvailability.Available(R.string.speech_notice_language_unconfirmed)
        }
        val spokenCountry = normalizeCountry(actual.locale.country)
        return SpeechAvailability.Available(
            if (spokenCountry == country) null else R.string.speech_notice_accent_not_guaranteed
        )
    }
    return if (missingData) SpeechAvailability.MissingData else SpeechAvailability.Unsupported
}

internal fun otherEnglishCountry(locale: Locale): Locale =
    if (normalizeCountry(locale.country) == "GB") Locale.US else Locale.UK

/** The accent the reader chose, or null for a choice this app does not offer. */
internal fun speechLocale(accent: String): Locale? = when (accent) {
    "US" -> Locale.US
    "UK" -> Locale.UK
    else -> null
}
