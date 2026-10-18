package app.peekturn.reader.narration

import android.app.Application
import androidx.media3.common.Player
import app.peekturn.reader.R
import app.peekturn.reader.speech.BingSpeechClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.readium.navigator.media.common.DefaultMediaMetadataProvider
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** What the reader's compact controls show while the narration service is alive. */
data class NarrationState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val cached: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null
)

/**
 * Owns one continuous narration session: a Readium TtsNavigator, a persistent sentence cache, one
 * synthesizer with bounded lookahead, and the locator the visible reader follows while foregrounded.
 * The service keeps this object alive independently of ReaderActivity; the visual navigator never
 * owns audio or the publication used for background reading.
 *
 * Audio focus, becoming-noisy and notification play/pause are owned by Readium's TtsSessionAdapter
 * exposed through [asMedia3Player]. That adapter requests speech focus, pauses on focus loss or
 * headphone disconnect, and maps notification transport controls back to TtsPlayer; this class does
 * not add a second focus owner beside the popup PronunciationPlayer.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class NarrationPlayback(
    private val application: Application,
    val bookId: Long,
    val publication: Publication,
    private val cache: SentenceAudioCache,
    private val accent: String,
    initialSpeed: Float,
    providerGate: Mutex,
    private val onEnded: () -> Unit,
    private val onLocator: (Locator) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateMutable = MutableStateFlow(
        NarrationState(active = true, speed = initialSpeed.coerceIn(0.5f, 2f))
    )
    val state: StateFlow<NarrationState> = stateMutable.asStateFlow()

    private val locationMutable = MutableStateFlow<Locator?>(null)
    val location: StateFlow<Locator?> = locationMutable.asStateFlow()

    private val voice = narrationVoice(accent)
    private val bing = BingSpeechClient()
    private val synthesizer = voice?.let { spec ->
        NarrationSynthesizer(
            cache = cache,
            source = NarrationChunkSource { text, spokenAccent ->
                bing.lookup(text, spokenAccent)?.audio
            },
            scope = scope,
            accent = accent,
            voice = spec,
            providerGate = providerGate
        )
    }
    private val content = NarrationContent(publication)

    private var navigator: TtsNavigator<
        NarrationSettings,
        NarrationPreferences,
        NarrationError,
        NarrationVoice
        >? = null
    private var prefetchJob: Job? = null
    private var startSkipper: SentenceStartSkipper? = null
    private var speed: Float = initialSpeed.coerceIn(0.5f, 2f)
    private val resumePolicy = NarrationResumePolicy()
    private var lookupLocator: Locator? = null

    /** Starts at [initialLocator], then at [startSentence] when the visible offset identifies one. */
    suspend fun start(initialLocator: Locator?, startSentence: String? = null): Boolean {
        val synth = synthesizer
        if (voice == null || synth == null) {
            stateMutable.update { it.copy(active = true, error = application.getString(R.string.reader_narration_error_voice)) }
            return false
        }
        val provider = NarrationEngineProvider(
            application = application,
            accent = accent,
            synthesizer = synth,
            onStarted = ::onUtteranceStarted,
            resumePolicy = resumePolicy
        )
        startSkipper = startSentence?.takeIf { it.isNotBlank() }?.let(::SentenceStartSkipper)
        val factory = TtsNavigatorFactory(
            application = application,
            publication = publication,
            ttsEngineProvider = provider,
            tokenizerFactory = { language -> SentenceTextTokenizer(language, startSkipper) },
            metadataProvider = DefaultMediaMetadataProvider()
        )
        if (factory == null) {
            stateMutable.update { it.copy(error = application.getString(R.string.reader_narration_error_content)) }
            return false
        }
        val created = factory.createNavigator(
            listener = object : TtsNavigator.Listener {
                override fun onStopRequested() {
                    stop(notify = true)
                }
            },
            initialLocator = initialLocator,
            initialPreferences = NarrationPreferences(speed = speed)
        )
        val nav = created.getOrNull()
        if (nav == null) {
            stateMutable.update { it.copy(error = application.getString(R.string.reader_narration_error_start)) }
            return false
        }
        navigator = nav
        locationMutable.value = initialLocator
        observe(nav)
        stateMutable.update { it.copy(active = true, error = null) }
        return true
    }

    fun asMedia3Player(): Player? = navigator?.asMedia3Player()

    fun play() {
        resumePolicy.clearLookupPause()
        stateMutable.update { it.copy(error = null) }
        navigator?.play()
    }

    /**
     * Pauses for a dictionary lookup and takes ownership of the resume only when narration was
     * playing. Manual pause/play, navigation, retry, stop or a replacement session clears that
     * ownership before [resumeAfterLookup] can act.
     */
    fun pauseForLookup(): Boolean {
        val nav = navigator ?: return false
        val current = state.value
        if (!resumePolicy.beginLookupPause(current.active, current.playing)) return false
        lookupLocator = locationMutable.value
        prefetchJob?.cancel()
        prefetchJob = null
        nav.pause()
        return true
    }

    /** Resumes only the unchanged utterance this lookup paused; every other path returns false. */
    fun resumeAfterLookup(): Boolean {
        val nav = navigator ?: return false
        if (!resumePolicy.claimLookupResume()) return false
        val pausedAt = lookupLocator
        lookupLocator = null
        if (!state.value.active || state.value.playing) return false
        if (pausedAt != locationMutable.value) return false
        nav.play()
        return true
    }

    /** Moves narration to a new visible locator and arms the shared start-sentence skipper. */
    fun go(locator: Locator, startSentence: String? = null) {
        resumePolicy.invalidateCheckpoint()
        stateMutable.update { it.copy(error = null) }
        prefetchJob?.cancel()
        if (startSentence.isNullOrBlank()) {
            startSkipper = null
        } else {
            val existing = startSkipper
            if (existing == null) startSkipper = SentenceStartSkipper(startSentence)
            else existing.reset(startSentence)
        }
        navigator?.let { nav ->
            nav.go(locator)
            nav.play()
        }
    }

    fun pause() {
        resumePolicy.clearLookupPause()
        pauseClip()
    }

    private fun pauseClip() {
        prefetchJob?.cancel()
        prefetchJob = null
        navigator?.pause()
    }

    fun next() {
        resumePolicy.invalidateCheckpoint()
        prefetchJob?.cancel()
        navigator?.skipToNextUtterance()
    }

    fun previous() {
        resumePolicy.invalidateCheckpoint()
        prefetchJob?.cancel()
        navigator?.skipToPreviousUtterance()
    }

    fun retry() {
        resumePolicy.invalidateCheckpoint()
        stateMutable.update { it.copy(error = null, playing = true) }
        navigator?.play()
    }

    fun setSpeed(speed: Float) {
        // A speed change keeps the utterance's checkpoint: the same sentence resumes where paused.
        this.speed = speed.coerceIn(0.5f, 2f)
        stateMutable.update { it.copy(speed = this.speed) }
        navigator?.submitPreferences(NarrationPreferences(speed = this.speed))
    }

    fun stop(notify: Boolean = true) {
        resumePolicy.invalidateCheckpoint()
        lookupLocator = null
        prefetchJob?.cancel()
        prefetchJob = null
        navigator?.close()
        navigator = null
        synthesizer?.cancelPending()
        stateMutable.update { it.copy(active = false, playing = false, cached = false) }
        if (notify) onEnded()
        scope.cancel()
    }

    private fun observe(nav: TtsNavigator<NarrationSettings, NarrationPreferences, NarrationError, NarrationVoice>) {
        scope.launch {
            nav.location.collect { location ->
                locationMutable.value = location.utteranceLocator
                onLocator(location.utteranceLocator)
            }
        }
        scope.launch {
            nav.playback.collect { playback ->
                when (val state = playback.state) {
                    TtsNavigator.State.Ready -> stateMutable.update {
                        it.copy(active = true, playing = playback.playWhenReady)
                    }
                    TtsNavigator.State.Ended -> {
                        resumePolicy.invalidateCheckpoint()
                        lookupLocator = null
                        stateMutable.update { it.copy(playing = false, active = false) }
                        onEnded()
                    }
                    is TtsNavigator.State.Failure -> {
                        resumePolicy.invalidateCheckpoint()
                        lookupLocator = null
                        stateMutable.update {
                            it.copy(playing = false, error = message(state.error))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Prefetch only once the current sentence has actually started, so current audio never waits
     * behind lookahead. The window is two sentences or about fifty words, never a chapter burst.
     */
    private fun onUtteranceStarted(text: String, fromCache: Boolean) {
        stateMutable.update { it.copy(cached = fromCache, playing = true) }
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val locator = locationMutable.value ?: return@launch
            val following = try {
                content.following(
                    locator = locator,
                    currentText = text,
                    maxAhead = NARRATION_PREFETCH_SENTENCES,
                    wordTarget = NARRATION_PREFETCH_WORDS
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            for (sentence in following) {
                try {
                    synthesizer?.prefetch(sentence)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Lookahead is best-effort; the on-demand request owns a visible failure.
                }
            }
        }
    }

    private fun message(error: TtsNavigator.Error): String = when (error) {
        is TtsNavigator.Error.EngineError<*> -> when (error.cause) {
            is NarrationError.TooLong -> application.getString(R.string.reader_narration_error_too_long)
            is NarrationError.Storage, is NarrationError.TooLarge ->
                application.getString(R.string.reader_narration_error_storage)
            is NarrationError.Playback, is NarrationError.InvalidAudio ->
                application.getString(R.string.reader_narration_error_playback)
            else -> application.getString(R.string.reader_narration_error_unavailable)
        }
        is TtsNavigator.Error.ContentError ->
            application.getString(R.string.reader_narration_error_content)
    }
}
