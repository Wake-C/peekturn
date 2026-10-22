package app.peekturn.reader.narration

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import app.peekturn.reader.model.SentenceSpeechProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/** Playback settings the Readium TTS navigator exposes to Media3. */
@ExperimentalReadiumApi
internal data class NarrationSettings(
    override val language: Language?,
    override val overrideContentLanguage: Boolean,
    val speed: Float
) : TtsEngine.Settings

/** Readium preferences own language and playback speed; system engine configuration is resolved separately. */
@ExperimentalReadiumApi
internal data class NarrationPreferences(
    override val language: Language? = null,
    val speed: Float? = null
) : TtsEngine.Preferences<NarrationPreferences> {
    override fun plus(other: NarrationPreferences): NarrationPreferences = NarrationPreferences(
        language = other.language ?: language,
        speed = other.speed ?: speed
    )
}

@ExperimentalReadiumApi
internal data class NarrationVoice(override val language: Language) : TtsEngine.Voice

/**
 * Narration failures stay typed until the reader-facing surface chooses localized, actionable copy.
 * The message is technical for Media3's playback exception; no book text is carried here.
 */
@ExperimentalReadiumApi
internal sealed class NarrationError(
    override val message: String,
    override val cause: Error? = null
) : TtsEngine.Error {
    class Unavailable : NarrationError("The read-aloud service returned no complete audio.")
    class TooLong : NarrationError("The sentence is too long to read aloud in one piece.")
    class InvalidAudio : NarrationError("The read-aloud answer was not complete supported audio.")
    class TooLarge : NarrationError("The sentence audio is larger than the cache allows.")
    class Storage : NarrationError("The sentence audio could not be stored.")
    class Playback : NarrationError("The cached sentence audio could not be played.")
    class Content : NarrationError("The publication content could not be read.")
    class Device(cause: Error? = null) :
        NarrationError("The device speech engine could not read this sentence.", cause)
}

/**
 * A Readium [TtsEngine] backed by the persistent sentence cache. It receives one complete sentence
 * from the official tokenizer, plays exactly that sentence, and never falls back to a second source:
 * narration either plays the requested sentence or stops with an error.
 *
 * The engine is source-agnostic: online MP3 and Android file-rendered WAV both arrive through
 * [NarrationAudioResolver] and play through the same MediaPlayer unit. A pause keeps only the clip
 * the reader actually heard; resuming resolves the sentence again so a changed voice, engine or
 * provider configuration cannot replay an old file, and the paused offset is applied only when the
 * resolution returns that same file within the same reading intent.
 */
@ExperimentalReadiumApi
internal class CachedNarrationEngine(
    application: Application,
    private val accent: String,
    private val resolver: NarrationAudioResolver,
    private val onStarted: (text: String, audio: NarrationAudio) -> Unit,
    private val resumePolicy: NarrationResumePolicy = NarrationResumePolicy(),
    private val provider: SentenceSpeechProvider = SentenceSpeechProvider.MICROSOFT,
    private val onCompleted: () -> Unit = {}
) : TtsEngine<NarrationSettings, NarrationPreferences, NarrationError, NarrationVoice> {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSettings = MutableStateFlow(
        NarrationSettings(language = null, overrideContentLanguage = false, speed = 1f)
    )
    override val settings: StateFlow<NarrationSettings> = mutableSettings.asStateFlow()

    override val voices: Set<NarrationVoice> = when (provider) {
        SentenceSpeechProvider.MICROSOFT -> buildSet {
            narrationVoice("US")?.let { add(NarrationVoice(Language(Locale.US))) }
            narrationVoice("UK")?.let { add(NarrationVoice(Language(Locale.UK))) }
        }
        SentenceSpeechProvider.BAIDU -> setOf(NarrationVoice(Language(Locale.ENGLISH)))
        SentenceSpeechProvider.ANDROID -> emptySet()
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var listener: TtsEngine.Listener<NarrationError>? = null
    private var media: MediaPlayer? = null
    private var job: Job? = null
    private var active: TtsEngine.RequestId? = null
    private var currentText = ""
    private var currentLanguage: Language? = null
    private var speed = 1f
    private var closed = false

    /**
     * The one paused clip that may be resumed, and the reading intent it belongs to. Readium's
     * pause calls [stop]; the next [speak] for the same sentence is the resume. Any navigation
     * advances the policy generation and makes this checkpoint unusable.
     */
    private var pausePoint: PausePoint? = null
    private var currentAudio: NarrationAudio? = null
    private var clipGeneration = 0L
    private var started = false

    private data class PausePoint(
        val text: String,
        val language: Language?,
        val positionMs: Int,
        val generation: Long,
        val audio: NarrationAudio
    )

    override fun setListener(listener: TtsEngine.Listener<NarrationError>?) {
        this.listener = listener
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        if (closed) return
        // A stop caused by Readium's pause keeps one checkpoint. The same sentence requested again
        // at the same reading intent is the resume; anything else starts at zero.
        val resume = pausePoint?.takeIf {
            it.text == text &&
                it.language == language &&
                it.generation == resumePolicy.checkpointGeneration()
        }
        stopPlayback(notify = false, preserve = false)
        active = requestId
        currentText = text
        currentLanguage = language
        clipGeneration = resumePolicy.checkpointGeneration()
        job = scope.launch {
            val result = try {
                // Always resolve the current source configuration. The paused clip is only a
                // position reference; it is never replayed just because its file still exists.
                resolver.audioFor(text)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: NarrationFailure) {
                Result.failure(failure)
            } catch (_: Exception) {
                Result.failure(NarrationFailure.Unavailable())
            }
            if (closed || active != requestId) return@launch
            result.fold(
                onSuccess = { audio ->
                    // Only the same resolved file at the same reading intent may carry the offset.
                    val pausedMs = resume
                        ?.takeIf { it.audio.file.absolutePath == audio.file.absolutePath }
                        ?.positionMs
                        ?: 0
                    play(audio, requestId, pausedMs)
                },
                onFailure = { failure ->
                    fail(
                        requestId,
                        (failure as? NarrationFailure)?.toNarrationError() ?: NarrationError.Unavailable()
                    )
                }
            )
        }
    }

    override fun stop() {
        // Readium implements pause as cancellation followed by stop(). Keeping the checkpoint here
        // is what lets the next speak() of the same sentence continue at the audible position.
        stopPlayback(notify = true, preserve = true)
        resolver.cancelPending()
    }

    override fun close() {
        if (closed) return
        closed = true
        stopPlayback(notify = false, preserve = false)
        // The resolver owns TTS clients and buffer scope; the session is over, so release them.
        resolver.close()
        scope.cancel()
        listener = null
    }

    override fun submitPreferences(preferences: NarrationPreferences) {
        speed = (preferences.speed ?: speed).coerceIn(0.5f, 2.0f)
        mutableSettings.value = NarrationSettings(
            language = preferences.language ?: mutableSettings.value.language,
            overrideContentLanguage = false,
            speed = speed
        )
        if (started) {
            // While a resume is prepared but not yet seeked/started, writing playbackParams can
            // itself start playback at zero. beginPlayback applies the latest speed after the
            // seek, so only a genuinely playing player is updated here.
            media?.let { player ->
                runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
            }
        }
    }

    /** Plays only the cached file the synthesizer produced; the path is app-private and opaque. */
    private fun play(audio: NarrationAudio, requestId: TtsEngine.RequestId, pausedMs: Int) {
        if (closed || active != requestId) return
        val player = try {
            MediaPlayer()
        } catch (_: RuntimeException) {
            fail(requestId, NarrationError.Playback())
            return
        }
        media = player
        currentAudio = audio
        // Navigation may have happened while the sentence was being fetched; only a clip from the
        // still-current reading intent may resume an offset.
        val resumeMs =
            if (clipGeneration == resumePolicy.checkpointGeneration()) pausedMs.coerceAtLeast(0) else 0
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(audio.file.absolutePath)
            player.setOnPreparedListener { prepared ->
                if (closed || active != requestId || media !== prepared) {
                    prepared.release()
                    return@setOnPreparedListener
                }
                if (resumeMs > 0) {
                    // Do not touch playbackParams before the seek: on Android, setting a non-zero
                    // speed in the prepared state is equivalent to start() and would replay the
                    // clip's prefix before the seek lands. Seek first, then start and set speed.
                    prepared.setOnSeekCompleteListener { seeked ->
                        if (closed || active != requestId || media !== seeked) return@setOnSeekCompleteListener
                        beginPlayback(seeked, requestId, audio)
                    }
                    if (runCatching { prepared.seekTo(resumeMs) }.isFailure) {
                        fail(requestId, NarrationError.Playback())
                    }
                } else {
                    beginPlayback(prepared, requestId, audio)
                }
            }
            player.setOnCompletionListener { completed ->
                if (closed || active != requestId || media !== completed) return@setOnCompletionListener
                active = null
                media = null
                started = false
                pausePoint = null
                currentAudio = null
                runCatching { completed.release() }
                runCatching { onCompleted() }
                listener?.onDone(requestId)
            }
            player.setOnErrorListener { failed, _, _ ->
                if (!closed && active == requestId && media === failed) {
                    fail(requestId, NarrationError.Playback())
                }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            fail(requestId, NarrationError.Playback())
        }
    }

    private fun beginPlayback(
        prepared: MediaPlayer,
        requestId: TtsEngine.RequestId,
        audio: NarrationAudio
    ) {
        if (closed || active != requestId || media !== prepared) return
        val startedPlaying = runCatching { prepared.start() }.isSuccess
        if (!startedPlaying) {
            fail(requestId, NarrationError.Playback())
            return
        }
        started = true
        // Apply speed after start: the prepared-state call in older code could itself begin
        // playback, and some implementations accept the speed only once playback has begun.
        runCatching { prepared.playbackParams = prepared.playbackParams.setSpeed(speed) }
        listener?.onStart(requestId)
        runCatching { onStarted(currentText, audio) }
    }

    private fun fail(requestId: TtsEngine.RequestId, error: NarrationError) {
        if (closed || active != requestId) return
        active = null
        pausePoint = null
        currentAudio = null
        releaseMedia()
        listener?.onError(requestId, error)
    }

    private fun stopPlayback(notify: Boolean, preserve: Boolean) {
        job?.cancel()
        job = null
        val previous = active
        active = null
        if (preserve) {
            capturePausePoint(previous)
        } else {
            pausePoint = null
        }
        currentText = ""
        currentAudio = null
        releaseMedia()
        if (notify && previous != null) listener?.onInterrupted(previous)
    }

    /**
     * Samples the audible position before the MediaPlayer is released. The checkpoint belongs to
     * the reading intent that started the clip; if navigation invalidated that intent, it is
     * discarded instead of being carried into the next utterance.
     */
    private fun capturePausePoint(previous: TtsEngine.RequestId?) {
        if (previous == null) return
        val player = media
        if (player == null || !started) {
            pausePoint = null
            return
        }
        val position = runCatching { player.currentPosition }.getOrDefault(0)
        val keep = position > 0 && clipGeneration == resumePolicy.checkpointGeneration()
        val audio = currentAudio
        if (!keep || audio == null) {
            pausePoint = null
            return
        }
        pausePoint = PausePoint(
            text = currentText,
            language = currentLanguage,
            positionMs = position,
            generation = clipGeneration,
            audio = audio
        )
    }

    private fun releaseMedia() {
        val previous = media
        media = null
        started = false
        runCatching { previous?.release() }
    }
}

@OptIn(ExperimentalReadiumApi::class)
internal fun NarrationFailure.toNarrationError(): NarrationError = when (this) {
    is NarrationFailure.TooLong -> NarrationError.TooLong()
    is NarrationFailure.InvalidAudio -> NarrationError.InvalidAudio()
    is NarrationFailure.TooLarge -> NarrationError.TooLarge()
    is NarrationFailure.Storage -> NarrationError.Storage()
    is NarrationFailure.Unavailable -> NarrationError.Unavailable()
    is NarrationFailure.Device -> NarrationError.Device(AndroidSpeechFailure(code))
}

/** A numeric platform failure stays numeric so no real error can read like success. */
internal class AndroidSpeechFailure(
    val code: Int,
    detail: String = "The device speech engine failed."
) : Error {
    override val message: String = "$detail (code $code)"
    override val cause: Error? = null
}

/**
 * The Readium TTS provider for cached narration. It uses the official sentence tokenizer, maps
 * speed to Media3 playback parameters, and keeps the session fixed to the chosen provider: the
 * reader's US or UK Microsoft voice, Baidu's single English voice, or Android's own preferred
 * engine voice for file-rendered WAV. The resolver owns synthesis and cache identity; Readium only
 * selects languages and transport.
 */
@ExperimentalReadiumApi
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NarrationEngineProvider(
    private val application: Application,
    private val accent: String,
    private val resolver: NarrationAudioResolver,
    private val onStarted: (text: String, audio: NarrationAudio) -> Unit,
    private val resumePolicy: NarrationResumePolicy,
    private val provider: SentenceSpeechProvider = SentenceSpeechProvider.MICROSOFT,
    private val onCompleted: () -> Unit = {}
) : TtsEngineProvider<
    NarrationSettings,
    NarrationPreferences,
    PreferencesEditor<NarrationPreferences>,
    NarrationError,
    NarrationVoice
    > {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: NarrationPreferences
    ): Try<TtsEngine<NarrationSettings, NarrationPreferences, NarrationError, NarrationVoice>, Error> {
        // Android narration has no Readium voice to select: the system engine owns its voice, and
        // the cache configuration comes from the resolver. Online providers still need their spec.
        if (provider != SentenceSpeechProvider.ANDROID && narrationVoice(provider, accent) == null) {
            return Try.failure(NarrationError.Unavailable())
        }
        val engine = CachedNarrationEngine(
            application = application,
            accent = accent,
            resolver = resolver,
            onStarted = onStarted,
            resumePolicy = resumePolicy,
            provider = provider,
            onCompleted = onCompleted
        )
        engine.submitPreferences(initialPreferences)
        return Try.success(engine)
    }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: NarrationPreferences
    ): PreferencesEditor<NarrationPreferences> = object : PreferencesEditor<NarrationPreferences> {
        override val preferences: NarrationPreferences = initialPreferences
        override fun clear() = Unit
    }

    override fun createEmptyPreferences(): NarrationPreferences = NarrationPreferences()

    override fun getPlaybackParameters(settings: NarrationSettings): PlaybackParameters =
        PlaybackParameters(settings.speed)

    override fun updatePlaybackParameters(
        previousPreferences: NarrationPreferences,
        playbackParameters: PlaybackParameters
    ): NarrationPreferences = previousPreferences.copy(speed = playbackParameters.speed)

    override fun mapEngineError(error: NarrationError): PlaybackException = PlaybackException(
        error.message,
        null,
        when (error) {
            is NarrationError.Unavailable -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            is NarrationError.Content -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            is NarrationError.InvalidAudio -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            else -> PlaybackException.ERROR_CODE_UNSPECIFIED
        }
    )
}
