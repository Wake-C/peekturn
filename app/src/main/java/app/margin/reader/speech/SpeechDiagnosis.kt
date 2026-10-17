package app.margin.reader.speech

import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * What an engine already has, spoken as the very last device attempt. The engine's own choice is left
 * untouched - no voice and no language is selected - so the reader's own engine, a vendor assistant
 * voice for instance, may be the only English-capable one on the device. It is never presented as the
 * requested accent, and it is offered for English text only, because a voice the app cannot name must
 * never be handed text in another script.
 */
internal const val DEVICE_DEFAULT_VOICE_NOTICE =
    "Using this device's own voice instead of an installed English one; the requested accent is not guaranteed."

/**
 * One engine's answer while device speech was prepared. Whether an engine listed voices and what each
 * language probe answered are different facts, and a report that cannot tell them apart would be a
 * guess dressed up as a diagnosis.
 */
internal sealed interface SpeechObservation {
    /** Voices the engine listed, or null when it refused to answer, failed, or could not be asked. */
    data class VoicesListed(val count: Int?) : SpeechObservation

    /** The engine's answer for one language probe, exactly as returned. */
    data class LanguageProbed(val locale: Locale, val support: Int) : SpeechObservation
}

/**
 * What the device actually answered while one request tried to reach a speaker, so a failure reports
 * the evidence instead of inventing a cause. The player records answers as they arrive; only a request
 * that ends without a speaker reads it back. The first engine asked is the one the reader chose in
 * system settings, so its language answers are the ones a report keeps, and the highest voice count
 * seen names whether any engine enumerated anything at all.
 */
internal class SpeechEvidence {
    private val engines = LinkedHashSet<String>()
    private val probes = mutableListOf<SpeechObservation.LanguageProbed>()
    private var listed: Int? = null
    private var enumerationRefused = false

    fun addEngine(name: String) {
        engines += name
    }

    fun observe(answer: SpeechObservation) {
        when (answer) {
            is SpeechObservation.VoicesListed -> {
                val count = answer.count
                if (count == null) enumerationRefused = true else if (listed == null || count > listed!!) listed = count
            }
            is SpeechObservation.LanguageProbed -> if (probes.size < PROBE_LIMIT) probes += answer
        }
    }

    fun clear() {
        engines.clear()
        probes.clear()
        listed = null
        enumerationRefused = false
    }

    /**
     * The line the reader can read and send back: which engines were asked, whether any of them
     * enumerated voices, what each English probe answered, and why the attempt ended.
     */
    fun report(failure: DeviceSpeechFailure): String = buildString {
        val listedCount = listed
        append("Device speech report: engines tried: ")
        append(engines.joinToString(", ").ifEmpty { "none found" })
        append("; voices listed: ")
        append(
            when {
                listedCount != null -> listedCount.toString()
                enumerationRefused -> "unavailable"
                else -> "not asked"
            }
        )
        for (probe in probes) {
            append("; ")
            append(probeName(probe.locale))
            append(": ")
            append(languageAnswer(probe.support))
        }
        append("; result: ")
        append(failure.verdict)
        append('.')
    }

    private companion object {
        /** One report per engine's three probes would bury the answer the reader chose in the rest. */
        const val PROBE_LIMIT = 3
    }
}

/**
 * How a device-speech attempt ended. The reader-facing message and the report's last word come from
 * one class, so neither can name a cause the app never observed - an engine that refused English is
 * not the same device fact as an engine that never started, and neither is an engine whose own voice
 * data it says is missing.
 */
internal enum class DeviceSpeechFailure(val verdict: String, val message: String) {
    /** No engine ever initialized, so nothing was learnt about any engine's voices. */
    NotStarted(
        "the device's speech engine did not start",
        NO_SPEECH_ENGINE
    ),

    /** An engine started and reported that its English voice data is missing. */
    MissingData(
        "the engine reports its English voice data is missing",
        MISSING_ENGLISH_DATA
    ),

    /** An engine started and offered no usable English voice, and its own voice could not speak either. */
    NoEnglishVoice(
        "no installed speech engine offered an English voice",
        NO_ENGLISH_VOICE
    ),

    /**
     * An engine accepted the text with its own voice and then failed to speak it. That is a playback
     * failure, not a missing voice, and telling the reader the voice is missing would be a false
     * negative - the exact mistake that made a working Chinese OEM phone look voiceless.
     */
    PlaybackFailed(
        "the engine accepted an English utterance and then failed to speak it",
        "Your speech engine accepted English but could not speak it. Check system speech settings, or choose another engine."
    )
}

/**
 * The most actionable cause a finished attempt found: an engine that started is more informative than
 * one that never answered, an engine that named missing English data is more actionable than one
 * that named no English voice at all, and an engine that accepted an utterance before failing is a
 * playback problem rather than a missing voice.
 */
internal fun deviceSpeechFailure(
    engineStarted: Boolean,
    missingEnglishData: Boolean,
    utteranceAccepted: Boolean = false
): DeviceSpeechFailure = when {
    !engineStarted -> DeviceSpeechFailure.NotStarted
    missingEnglishData -> DeviceSpeechFailure.MissingData
    utteranceAccepted -> DeviceSpeechFailure.PlaybackFailed
    else -> DeviceSpeechFailure.NoEnglishVoice
}

/**
 * The notice for the final device attempt, or null when it must not be taken. No routes for the
 * requested accent worked, so the engine's own voice is tried without changing anything - and only
 * for text whose own script says English, because a Chinese selection must not be spoken by a voice
 * the app would then have to call an English one.
 */
internal fun substitutedDeviceVoiceNotice(availability: SpeechAvailability, text: String): String? =
    if (availability !is SpeechAvailability.Available && isEnglishText(text)) DEVICE_DEFAULT_VOICE_NOTICE else null

/**
 * Whether the text itself is English: it has letters and all of them are Latin, so accents and
 * apostrophes are fine while another script is not. The last-resort voice is chosen by the engine, not
 * by this app, so the text is the only thing that can vouch for the language it will be read in.
 */
internal fun isEnglishText(text: String): Boolean =
    text.any { it.isLetter() } &&
        text.none { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }

/** The probe name a report uses: the requested accent, the other English country, or plain English. */
internal fun probeName(locale: Locale): String = when (normalizeCountry(locale.country)) {
    "US" -> "US English"
    "GB" -> "UK English"
    else -> "English"
}

/** The engine's own language answer in words, so a report never upgrades it into a verdict of its own. */
internal fun languageAnswer(support: Int): String = when (support) {
    TextToSpeech.LANG_MISSING_DATA -> "data missing"
    TextToSpeech.LANG_NOT_SUPPORTED -> "not supported"
    TextToSpeech.LANG_AVAILABLE -> "language only"
    TextToSpeech.LANG_COUNTRY_AVAILABLE -> "country available"
    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "available"
    else -> "unexpected answer ($support)"
}
