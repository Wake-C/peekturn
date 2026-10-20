package app.peekturn.reader.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.annotation.StringRes
import app.peekturn.reader.R
import app.peekturn.reader.diagnostics.diagnosticMetadata
import java.util.Locale

/**
 * What an engine already has, spoken as the very last device attempt. The engine's own choice is left
 * untouched - no voice and no language is selected - so the reader's own engine, a vendor assistant
 * voice for instance, may be the only English-capable one on the device. It is never presented as the
 * requested accent, and it is offered for English text only, because a voice the app cannot name must
 * never be handed text in another script.
 */
@StringRes
internal val DEVICE_DEFAULT_VOICE_NOTICE: Int = R.string.speech_notice_device_default_voice

/** Bounds for engine-supplied detail: an engine cannot flood the log with names or callbacks. */
private const val PROBE_LIMIT = 3
private const val ATTEMPT_LIMIT = 8
private const val UTTERANCE_LIMIT = 8
private const val MAX_ENGINES = 8
/** Voices snapshotted per engine; the full count is kept separately, so "more" is never lost. */
private const val MAX_VOICE_FACTS = 12
private const val VOICE_NAME_LIMIT = 64
private const val ENGINE_NAME_LIMIT = 96
private const val TEXT_LIMIT = 32

/** Cap on feature values rendered per voice; each one is separately field-sanitized. */
private const val MAX_FEATURES = 6
private const val FEATURE_LIMIT = 48

/**
 * One voice as the engine reported it, reduced to what a support report needs: the name it was given,
 * its raw language and country, its BCP-47 tag, its features, and how it is delivered. Names and
 * feature lists are engine-supplied and may be long, so the rendered [summary] is bounded and stripped
 * of line breaks.
 */
internal data class SpeechVoiceFact(
    val name: String,
    val locale: Locale,
    val features: Set<String> = emptySet(),
    val network: Boolean = false,
    val quality: Int = Voice.QUALITY_NORMAL,
    val latency: Int = Voice.LATENCY_NORMAL
) {
    /** The engine's own language fields, sanitized, so a report never relies on a tag alone. */
    val languageLabel: String
        get() = "language=" + diagnosticMetadata(locale.language, TEXT_LIMIT) +
            "; country=" + diagnosticMetadata(locale.country, TEXT_LIMIT)

    val summary: String
        get() = buildString {
            append(diagnosticMetadata(name, VOICE_NAME_LIMIT))
            append(" [")
            append(languageLabel)
            append("; tag=")
            append(diagnosticMetadata(locale.toLanguageTag(), TEXT_LIMIT))
            append("; features: ")
            val shown = features.sorted().take(MAX_FEATURES)
            append(shown.joinToString(",") { diagnosticMetadata(it, FEATURE_LIMIT) }.ifEmpty { "none" })
            val hidden = features.size - shown.size
            if (hidden > 0) append(",+").append(hidden).append(" more")
            append("; network: ")
            append(if (network) "yes" else "no")
            append("; quality: ")
            append(quality)
            append("; latency: ")
            append(latency)
            append(']')
        }
}

/** The framework voice as evidence. Nothing here is inferred; it is what the engine already said. */
internal fun speechVoiceFact(voice: Voice): SpeechVoiceFact = SpeechVoiceFact(
    name = voice.name,
    locale = voice.locale,
    features = voice.features.orEmpty(),
    network = voice.isNetworkConnectionRequired,
    quality = voice.quality,
    latency = voice.latency
)

/**
 * The bounded snapshot an engine's enumeration becomes. Taking before mapping means an engine that
 * lists hundreds of voices costs no facts for the ones the report would discard; [VoicesListed.count]
 * still carries the full number, so the report can say how many were left out.
 */
internal fun speechVoiceFacts(voices: Collection<Voice>): List<SpeechVoiceFact> =
    voices.take(MAX_VOICE_FACTS).map(::speechVoiceFact)

/**
 * One engine's answer while device speech was prepared. Whether an engine listed voices, what it said
 * when asked to select one, what each language probe answered, and what its current voice looked like
 * are different facts, and a report that could not tell them apart would be a guess dressed up as a
 * diagnosis. Voice names, features and locale tags come from the engine; passage text never does.
 */
internal sealed interface SpeechObservation {
    /**
     * Voices the engine listed, or null when it refused to answer, failed, or could not be asked.
     * [count] is the full number the engine reported; [voices] is the bounded snapshot taken when the
     * answer arrived, so a later report can say how many details were left out.
     */
    data class VoicesListed(
        val count: Int?,
        val voices: List<SpeechVoiceFact> = emptyList()
    ) : SpeechObservation

    /** One explicit voice the app asked the engine to select, its return value and actual result. */
    data class VoiceTried(
        val voice: SpeechVoiceFact,
        val returned: Int,
        val actual: SpeechVoiceFact?,
        val rejection: String? = null
    ) : SpeechObservation

    /** The engine's answer for one language probe, exactly as returned, and the voice it then held. */
    data class LanguageProbed(
        val locale: Locale,
        val support: Int,
        val voice: SpeechVoiceFact? = null
    ) : SpeechObservation

    /** The engine's initialization callback, exactly as delivered. */
    data class InitAnswered(val status: Int) : SpeechObservation

    /** What speak() returned for the current request. */
    data class SpeakAnswered(val returned: Int) : SpeechObservation
}

/**
 * What the device actually answered while one request tried to reach a speaker, so a failure reports
 * the evidence instead of inventing a cause. The player records answers as they arrive, tagged with
 * the engine that gave them; only a request that ends without a speaker reads them back as a report,
 * while a successful preparation reads the same facts back to the diagnostic log. The highest voice
 * count seen names whether any engine enumerated anything at all. Everything is bounded: an engine
 * cannot flood the log with voice names, features or callbacks.
 */
internal class SpeechEvidence {
    private class EngineRecord(val name: String) {
        var version: String? = null
        var listed: Int? = null
        var latestCount: Int? = null
        var enumerationRefused = false
        val voices = mutableListOf<SpeechVoiceFact>()
        val attempts = mutableListOf<SpeechObservation.VoiceTried>()
        val probes = mutableListOf<SpeechObservation.LanguageProbed>()
        var initStatus: Int? = null
        var speakReturned: Int? = null
        val utterances = mutableListOf<String>()

        fun label(): String = buildString {
            append(diagnosticMetadata(name, ENGINE_NAME_LIMIT))
            version?.let { append(" (version ").append(diagnosticMetadata(it, TEXT_LIMIT)).append(')') }
        }

        fun isEmpty(): Boolean = voices.isEmpty() && attempts.isEmpty() && probes.isEmpty() &&
            listed == null && !enumerationRefused && initStatus == null && speakReturned == null &&
            utterances.isEmpty()

        fun details(): String = buildString {
            append("voices listed: ")
            append(
                when {
                    latestCount != null -> latestCount.toString()
                    enumerationRefused -> "unavailable"
                    else -> "not asked"
                }
            )
            if (voices.isNotEmpty()) {
                append(" [")
                append(voices.joinToString("; ") { it.summary })
                val extra = (latestCount ?: voices.size) - voices.size
                if (extra > 0) append("; +").append(extra).append(" more")
                append(']')
            }
            for (attempt in attempts) {
                append("; setVoice \"")
                append(diagnosticMetadata(attempt.voice.name, VOICE_NAME_LIMIT))
                append("\" returned ")
                append(attempt.returned)
                append(" -> ")
                append(attempt.actual?.summary ?: "no voice reported")
                attempt.rejection?.let {
                    append("; rejected: ").append(diagnosticMetadata(it, 200))
                }
            }
            for (probe in probes) {
                append("; ")
                append(probeName(probe.locale))
                append(": ")
                append(languageAnswer(probe.support))
                probe.voice?.let { append(" (voice ").append(it.summary).append(')') }
            }
            initStatus?.let { append("; init returned ").append(it) }
            speakReturned?.let { append("; speak returned ").append(it) }
            if (utterances.isNotEmpty()) append("; utterance: ").append(utterances.joinToString(", "))
        }
    }

    private val records = mutableListOf<EngineRecord>()
    private var current: EngineRecord? = null

    /**
     * The engine about to be asked. A package name and the version the platform reports make the
     * report identifiable; the engine the reader chose is the one whose facts matter most.
     */
    fun addEngine(name: String, version: String? = null) {
        val record = records.firstOrNull { it.name == name }
            ?: EngineRecord(name).also { records += it }
        if (version != null) record.version = version
        current = record
    }

    fun observe(answer: SpeechObservation) {
        when (answer) {
            is SpeechObservation.VoicesListed -> {
                val record = current ?: return
                val count = answer.count
                if (count == null) {
                    record.enumerationRefused = true
                } else if (record.listed == null || count > record.listed!!) {
                    record.listed = count
                }
                record.latestCount = count
                record.voices.clear()
                record.voices += answer.voices.take(MAX_VOICE_FACTS)
            }
            is SpeechObservation.VoiceTried -> {
                val record = current ?: return
                if (record.attempts.size < ATTEMPT_LIMIT) record.attempts += answer
            }
            is SpeechObservation.LanguageProbed -> {
                val record = current ?: return
                if (record.probes.size < PROBE_LIMIT) record.probes += answer
            }
            is SpeechObservation.InitAnswered -> current?.initStatus = answer.status
            is SpeechObservation.SpeakAnswered -> current?.speakReturned = answer.returned
        }
    }

    /**
     * A lifecycle callback for the engine being asked. The event names are fixed by the player; only
     * an engine's numeric error code is added, never the text that was spoken.
     */
    fun recordUtterance(event: String, errorCode: Int? = null) {
        val record = current ?: return
        if (record.utterances.size >= UTTERANCE_LIMIT) return
        record.utterances += if (errorCode == null) event else "$event (code $errorCode)"
    }

    fun clear() {
        records.clear()
        current = null
    }

    /** Whether any engine said anything worth logging; an online-only request has no such facts. */
    fun hasFacts(): Boolean = records.isNotEmpty()

    /**
     * What the devices answered, without a failure verdict: the engines asked, the voices each one
     * listed, every selection attempt and language answer, and the initialization and utterance
     * callbacks. Deliberately technical and not localized, so it can be matched with the code path.
     */
    fun facts(): String = buildString {
        append("Device speech report: engines tried: ")
        append(records.take(MAX_ENGINES).joinToString(", ") { it.label() }.ifEmpty { "none found" })
        if (records.size > MAX_ENGINES) append(" (+").append(records.size - MAX_ENGINES).append(" more)")
        append("; voices listed: ")
        append(listedLabel())
        for (record in records.take(MAX_ENGINES)) {
            if (record.isEmpty()) continue
            append('\n')
            append(diagnosticMetadata(record.name, ENGINE_NAME_LIMIT))
            append(": ")
            append(record.details())
        }
    }

    /** The copyable report: the observed facts, then the one cause they support. */
    fun report(failure: DeviceSpeechFailure): String =
        facts() + "\nresult: " + failure.verdict + '.'

    private fun listedLabel(): String {
        val count = records.mapNotNull { it.listed }.maxOrNull()
        return when {
            count != null -> count.toString()
            records.any { it.enumerationRefused } -> "unavailable"
            else -> "not asked"
        }
    }
}

/**
 * How a device-speech attempt ended. The reader-facing message and the report's last word come from
 * one class, so neither can name a cause the app never observed - an engine that refused English is
 * not the same device fact as an engine that never started, and neither is an engine whose own voice
 * data it says is missing.
 */
internal enum class DeviceSpeechFailure(val verdict: String, @StringRes val message: Int) {
    /** No engine ever initialized, so nothing was learnt about any engine's voices. */
    NotStarted(
        "the device's speech engine did not start",
        R.string.speech_failure_engine_not_started
    ),

    /** An engine started and reported that its English voice data is missing. */
    MissingData(
        "the engine reports its English voice data is missing",
        R.string.speech_failure_missing_english_data
    ),

    /** An engine started and offered no usable English voice, and its own voice could not speak either. */
    NoEnglishVoice(
        "no installed speech engine offered an English voice",
        R.string.speech_failure_no_english_voice
    ),

    /**
     * An engine accepted the text with its own voice and then failed to speak it. That is a playback
     * failure, not a missing voice, and telling the reader the voice is missing would be a false
     * negative - the exact mistake that made a working Chinese OEM phone look voiceless.
     */
    PlaybackFailed(
        "the engine accepted an English utterance and then failed to speak it",
        R.string.speech_failure_engine_playback
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
internal fun substitutedDeviceVoiceNotice(availability: SpeechAvailability, text: String): Int? =
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
