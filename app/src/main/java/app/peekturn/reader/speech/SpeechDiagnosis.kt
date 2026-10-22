package app.peekturn.reader.speech

import android.speech.tts.Voice
import androidx.annotation.StringRes
import app.peekturn.reader.R
import app.peekturn.reader.diagnostics.diagnosticMetadata
import java.util.Locale

private const val UTTERANCE_LIMIT = 8
private const val MAX_ENGINES = 8
private const val VOICE_NAME_LIMIT = 64
private const val ENGINE_NAME_LIMIT = 96
private const val TEXT_LIMIT = 32
private const val MAX_FEATURES = 6
private const val FEATURE_LIMIT = 48

/** Read-only provider metadata, never a reason to override or refuse the engine's default voice. */
internal data class SpeechVoiceFact(
    val name: String,
    val locale: Locale,
    val features: Set<String> = emptySet(),
    val network: Boolean = false,
    val quality: Int = Voice.QUALITY_NORMAL,
    val latency: Int = Voice.LATENCY_NORMAL
) {
    val languageLabel: String
        get() = "language=" + diagnosticMetadata(locale.language, TEXT_LIMIT) +
            "; country=" + diagnosticMetadata(locale.country, TEXT_LIMIT)

    val summary: String
        get() = buildString {
            append(diagnosticMetadata(name, VOICE_NAME_LIMIT))
            append(" [").append(languageLabel)
            append("; tag=").append(diagnosticMetadata(locale.toLanguageTag(), TEXT_LIMIT))
            append("; features: ")
            val shown = features.sorted().take(MAX_FEATURES)
            append(shown.joinToString(",") { diagnosticMetadata(it, FEATURE_LIMIT) }.ifEmpty { "none" })
            val hidden = features.size - shown.size
            if (hidden > 0) append(",+").append(hidden).append(" more")
            append("; network: ").append(if (network) "yes" else "no")
            append("; quality: ").append(quality)
            append("; latency: ").append(latency).append(']')
        }
}

internal fun speechVoiceFact(voice: Voice): SpeechVoiceFact = SpeechVoiceFact(
    name = voice.name,
    locale = voice.locale,
    features = voice.features.orEmpty(),
    network = voice.isNetworkConnectionRequired,
    quality = voice.quality,
    latency = voice.latency
)

/** Only answers to calls Peekturn actually makes; voice selection belongs to the engine. */
internal sealed interface SpeechObservation {
    data class DefaultVoiceReported(val voice: SpeechVoiceFact?) : SpeechObservation
    data class InitAnswered(val status: Int) : SpeechObservation
    data class SpeakAnswered(val returned: Int) : SpeechObservation
}

/** Bounded, passage-free facts about the system speech connection and its actual callbacks. */
internal class SpeechEvidence {
    private class EngineRecord(val name: String) {
        var version: String? = null
        var voiceReported = false
        var voice: SpeechVoiceFact? = null
        var initStatus: Int? = null
        var speakReturned: Int? = null
        val utterances = mutableListOf<String>()

        fun label(): String = buildString {
            append(diagnosticMetadata(name, ENGINE_NAME_LIMIT))
            version?.let { append(" (version ").append(diagnosticMetadata(it, TEXT_LIMIT)).append(')') }
        }

        fun isEmpty(): Boolean = !voiceReported && initStatus == null && speakReturned == null && utterances.isEmpty()

        fun details(): String = buildString {
            if (voiceReported) append("engine default voice: ").append(voice?.summary ?: "unavailable")
            initStatus?.let {
                if (isNotEmpty()) append("; ")
                append("init returned ").append(it)
            }
            speakReturned?.let {
                if (isNotEmpty()) append("; ")
                append("speak returned ").append(it)
            }
            if (utterances.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append("utterance: ").append(utterances.joinToString(", "))
            }
        }
    }

    private val records = mutableListOf<EngineRecord>()
    private var current: EngineRecord? = null

    fun addEngine(name: String, version: String? = null) {
        val record = records.firstOrNull { it.name == name } ?: run {
            if (records.size >= MAX_ENGINES) {
                current = null
                return
            }
            EngineRecord(name).also { records += it }
        }
        if (version != null) record.version = version
        current = record
    }

    fun observe(answer: SpeechObservation) {
        val record = current ?: return
        when (answer) {
            is SpeechObservation.DefaultVoiceReported -> {
                record.voiceReported = true
                record.voice = answer.voice
            }
            is SpeechObservation.InitAnswered -> record.initStatus = answer.status
            is SpeechObservation.SpeakAnswered -> record.speakReturned = answer.returned
        }
    }

    fun recordUtterance(event: String, errorCode: Int? = null) {
        val record = current ?: return
        if (record.utterances.size >= UTTERANCE_LIMIT) return
        record.utterances += if (errorCode == null) event else "$event (code $errorCode)"
    }

    fun clear() {
        records.clear()
        current = null
    }

    fun hasFacts(): Boolean = records.isNotEmpty()

    fun facts(): String = buildString {
        append("Device speech report: engines: ")
        append(records.joinToString(", ") { it.label() }.ifEmpty { "none reported" })
        for (record in records) {
            if (record.isEmpty()) continue
            append('\n').append(diagnosticMetadata(record.name, ENGINE_NAME_LIMIT)).append(": ")
            append(record.details())
        }
    }

    fun report(failure: DeviceSpeechFailure): String = facts() + "\nresult: " + failure.verdict + '.'
}

/** Metadata is not a synthesis verdict. Missing-data failures come from actual engine errors. */
internal enum class DeviceSpeechFailure(val verdict: String, @StringRes val message: Int) {
    NotStarted(
        "the system speech engine did not start",
        R.string.speech_failure_engine_not_started
    ),
    MissingData(
        "the system speech engine reports its voice data is missing",
        R.string.speech_failure_missing_english_data
    ),
    PreparationFailed(
        "the system speech engine could not prepare speech",
        R.string.speech_failure_preparation_failed
    ),
    PlaybackFailed(
        "the system speech engine accepted the utterance and then failed to speak it",
        R.string.speech_failure_engine_playback
    )
}

internal fun deviceSpeechFailure(
    engineStarted: Boolean,
    missingEnglishData: Boolean,
    utteranceAccepted: Boolean = false
): DeviceSpeechFailure = when {
    !engineStarted -> DeviceSpeechFailure.NotStarted
    missingEnglishData -> DeviceSpeechFailure.MissingData
    utteranceAccepted -> DeviceSpeechFailure.PlaybackFailed
    else -> DeviceSpeechFailure.PreparationFailed
}
