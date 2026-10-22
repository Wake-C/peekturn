package app.peekturn.reader.narration

import android.app.Application
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.media3.common.Player
import app.peekturn.reader.R
import app.peekturn.reader.model.SentenceSpeechProvider
import app.peekturn.reader.speech.BaiduSpeechClient
import app.peekturn.reader.speech.BingSpeechClient
import app.peekturn.reader.speech.SentenceAudioSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import org.readium.navigator.media.common.DefaultMediaMetadataProvider
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** What the reader's compact controls show while the narration service is alive. */
data class NarrationState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val cached: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
    /** An honest limitation of the active provider, shown without blocking the transport. */
    val notice: String? = null,
    /** The first requested sentence, or the reserve after it, is still being rendered. */
    val buffering: Boolean = false,
    val readyParagraphs: Int = 0,
    val targetParagraphs: Int = 8,
    val readyDurationMs: Long = 0L,
    val pendingSynthesis: Int = 0,
    val burst: Boolean = false,
    val paragraphJumps: Long = 0L,
    val sessionId: Long = 0L,
    val startedUtterances: Long = 0L,
    val completedUtterances: Long = 0L,
    val lastPlaybackStartedAtMs: Long = 0L,
    val lastPlaybackCompletedAtMs: Long = 0L,
    val maxGapMs: Long = 0L,
    val lastAudioKey: String? = null,
    val lastAudioConfiguration: String? = null
)

/**
 * Owns one continuous narration session: a Readium TtsNavigator, the selected provider, and the
 * locator the visible reader follows while foregrounded. Microsoft, Baidu and Android system speech
 * all deliver complete sentence audio through a [NarrationAudioResolver]: online providers keep
 * their MP3 synthesizer, while Android renders the system engine's preferred voice to a cached WAV.
 * A [BufferedNarrationResolver] prioritizes the requested sentence and prepares real Readium
 * paragraph lookahead as soon as audio is requested. The service keeps this object alive
 * independently of ReaderActivity; the visual navigator never owns audio or the publication used
 * for background reading.
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
    val provider: SentenceSpeechProvider,
    initialSpeed: Float,
    providerGate: Mutex,
    systemSynthesisGate: Semaphore,
    private val onEnded: () -> Unit,
    private val onLocator: (Locator) -> Unit,
    private val onDiagnostic: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paragraphJumps = Mutex()
    private var paragraphRequestEpoch = 0L
    private val sessionId = sessionIds.incrementAndGet()
    private var gapBaselineAtMs = 0L
    private var cachedStarts = 0L
    private var lastPlaybackIdentity = "provider=${provider.name}"
    private var currentSpokenText: String? = null
    private var lastBufferReportAtMs = 0L
    private var lastBuffering = false
    private var lastBurst = false
    private val stateMutable = MutableStateFlow(
        NarrationState(
            active = true,
            sessionId = sessionId,
            speed = initialSpeed.coerceIn(0.5f, 2f),
            notice = if (provider == SentenceSpeechProvider.BAIDU) {
                application.getString(R.string.speech_notice_baidu_accent)
            } else {
                null
            }
        )
    )
    val state: StateFlow<NarrationState> = stateMutable.asStateFlow()

    private val locationMutable = MutableStateFlow<Locator?>(null)
    val location: StateFlow<Locator?> = locationMutable.asStateFlow()

    private val onlineSource: SentenceAudioSource? = when (provider) {
        SentenceSpeechProvider.MICROSOFT -> BingSpeechClient()
        SentenceSpeechProvider.BAIDU -> BaiduSpeechClient()
        SentenceSpeechProvider.ANDROID -> null
    }
    private val content = NarrationContent(publication, onDiagnostic = ::diagnostic)
    private var speed: Float = initialSpeed.coerceIn(0.5f, 2f)
    private val resumePolicy = NarrationResumePolicy()

    /**
     * Android renders the system engine's speech to a cached WAV; online providers keep their MP3
     * synthesizer and gate. The buffered wrapper owns current-sentence priority and lookahead, so
     * playback never starts its own prefetch queue.
     */
    private val source: NarrationAudioResolver? = when (provider) {
        SentenceSpeechProvider.ANDROID -> SystemNarrationSynthesizer(
            context = application,
            cache = cache,
            scope = scope,
            gate = systemSynthesisGate,
            onDiagnostic = ::diagnostic
        )
        SentenceSpeechProvider.MICROSOFT, SentenceSpeechProvider.BAIDU -> {
            val spec = narrationVoice(provider, accent)
            val online = onlineSource
            if (spec == null || online == null) {
                null
            } else {
                NarrationSynthesizer(
                    cache = cache,
                    source = NarrationChunkSource { text, spokenAccent ->
                        online.lookup(text, spokenAccent)?.audio
                    },
                    scope = scope,
                    accent = accent,
                    voice = spec,
                    providerGate = providerGate
                )
            }
        }
    }
    private val resolver: BufferedNarrationResolver? = source?.let { raw ->
        BufferedNarrationResolver(
            source = raw,
            scope = scope,
            following = ::followingParagraphs,
            playbackSpeed = { speed },
            intentGeneration = { resumePolicy.checkpointGeneration() },
            onDiagnostic = ::diagnostic,
            onState = ::onBufferState
        )
    }

    private var navigator: TtsNavigator<
        NarrationSettings,
        NarrationPreferences,
        NarrationError,
        NarrationVoice
        >? = null
    private var startSkipper: SentenceStartSkipper? = null
    private var lookupLocator: Locator? = null

    /**
     * A `go()` target that Readium has not yet published to `navigator.location`; the stale location
     * is kept only to recognize the next real update.
     */
    private var pendingLocator: Locator? = null
    private var staleLocator: Locator? = null
    private var stopped = false

    /** Starts at [initialLocator], then at [startSentence] when the visible offset identifies one. */
    suspend fun start(initialLocator: Locator?, startSentence: String? = null): Boolean {
        val buffered = resolver
        diagnostic("event=session_start provider=${provider.name} speed=$speed")
        if (buffered == null) {
            stateMutable.update {
                it.copy(active = true, error = application.getString(R.string.reader_narration_error_voice))
            }
            return false
        }
        val ttsProvider: TtsEngineProvider<
            NarrationSettings,
            NarrationPreferences,
            PreferencesEditor<NarrationPreferences>,
            NarrationError,
            NarrationVoice
            > = NarrationEngineProvider(
            application = application,
            accent = accent,
            resolver = buffered,
            onStarted = ::onUtteranceStarted,
            onCompleted = ::onUtteranceCompleted,
            resumePolicy = resumePolicy,
            provider = provider
        )
        startSkipper = startSentence?.takeIf { it.isNotBlank() }?.let(::SentenceStartSkipper)
        val factory = TtsNavigatorFactory(
            application = application,
            publication = publication,
            ttsEngineProvider = ttsProvider,
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
        action("play_requested")
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
        action("lookup_pause")
        lookupLocator = currentUtteranceLocator() ?: locationMutable.value
        cancelLookahead()
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
        if (pausedAt != (currentUtteranceLocator() ?: locationMutable.value)) return false
        action("lookup_resume")
        nav.play()
        return true
    }

    /**
     * Moves narration to a new visible locator and arms the shared start-sentence skipper. Readium
     * does not publish the seek target until after the first utterance, so the target is kept as
     * the lookahead anchor until `navigator.location` actually changes.
     */
    fun go(locator: Locator, startSentence: String? = null) {
        seekTo(locator, startSentence, playWhenReady = true)
    }

    private fun seekTo(
        locator: Locator,
        startSentence: String?,
        playWhenReady: Boolean,
        preserveQueuedParagraphRequests: Boolean = false
    ) {
        if (!preserveQueuedParagraphRequests) paragraphRequestEpoch++
        action("seek_requested")
        currentSpokenText = null
        resumePolicy.invalidateCheckpoint()
        stateMutable.update { it.copy(error = null) }
        cancelLookahead()
        staleLocator = navigator?.location?.value?.utteranceLocator ?: locationMutable.value
        pendingLocator = locator
        if (startSentence.isNullOrBlank()) {
            startSkipper = null
        } else {
            val existing = startSkipper
            if (existing == null) startSkipper = SentenceStartSkipper(startSentence)
            else existing.reset(startSentence)
        }
        navigator?.let { nav ->
            nav.go(locator)
            if (playWhenReady) nav.play() else nav.pause()
        }
    }

    fun pause() {
        action("pause_requested")
        resumePolicy.clearLookupPause()
        pauseClip()
    }

    private fun pauseClip() {
        cancelLookahead()
        navigator?.pause()
    }

    fun next() {
        action("next_sentence")
        resumePolicy.invalidateCheckpoint()
        paragraphRequestEpoch++
        currentSpokenText = null
        pendingLocator = null
        staleLocator = null
        cancelLookahead()
        navigator?.skipToNextUtterance()
    }
    fun nextParagraph() {
        val requestEpoch = paragraphRequestEpoch
        scope.launch {
            paragraphJumps.withLock {
                if (stopped || requestEpoch != paragraphRequestEpoch) return@withLock
                val locator = currentUtteranceLocator() ?: return@withLock
                val requestedText = if (pendingLocator != null || state.value.buffering) {
                    locator.text.highlight.orEmpty()
                } else {
                    currentSpokenText ?: locator.text.highlight.orEmpty()
                }
                val generation = resumePolicy.checkpointGeneration()
                val target = try {
                    content.nextParagraph(locator, requestedText)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diagnostic("event=paragraph_navigation_failed")
                    return@withLock
                }
                if (stopped || generation != resumePolicy.checkpointGeneration()) return@withLock
                if (target == null) {
                    diagnostic("event=paragraph_unavailable")
                    return@withLock
                }
                action("next_paragraph")
                stateMutable.update { it.copy(paragraphJumps = it.paragraphJumps + 1L) }
                seekTo(target, null, playWhenReady = state.value.playing, preserveQueuedParagraphRequests = true)
            }
        }
    }


    fun previous() {
        action("previous_sentence")
        resumePolicy.invalidateCheckpoint()
        paragraphRequestEpoch++
        currentSpokenText = null
        pendingLocator = null
        staleLocator = null
        cancelLookahead()
        navigator?.skipToPreviousUtterance()
    }

    fun retry() {
        action("retry_requested")
        resumePolicy.invalidateCheckpoint()
        paragraphRequestEpoch++
        cancelLookahead()
        stateMutable.update { it.copy(error = null, playing = true) }
        navigator?.play()
    }

    fun setSpeed(speed: Float) {
        // A speed change keeps the utterance's checkpoint: the same sentence resumes where paused.
        this.speed = speed.coerceIn(0.5f, 2f)
        action("speed_changed speed=${this.speed}")
        stateMutable.update { it.copy(speed = this.speed) }
        navigator?.submitPreferences(NarrationPreferences(speed = this.speed))
    }

    fun stop(notify: Boolean = true) {
        if (stopped) return
        action("stop_requested")
        stopped = true
        resumePolicy.invalidateCheckpoint()
        lookupLocator = null
        pendingLocator = null
        staleLocator = null
        cancelLookahead()
        val nav = navigator
        navigator = null
        if (nav != null) {
            // Closing the navigator tears down its engine, which closes the resolver exactly once.
            nav.close()
        } else {
            // A start that failed before creating the navigator still owns system TTS clients.
            resolver?.close()
        }
        stateMutable.update {
            it.copy(active = false, playing = false, cached = false, buffering = false)
        }
        if (notify) onEnded()
        scope.cancel()
    }

    private fun cancelLookahead() {
        resolver?.cancelPending()
    }

    /** The locator of the utterance being requested, fresh even before Readium publishes a seek. */
    private fun currentUtteranceLocator(): Locator? {
        val current = navigator?.location?.value?.utteranceLocator
        val pending = pendingLocator
        if (pending != null) {
            if (current != null && current != staleLocator) {
                pendingLocator = null
                staleLocator = null
                return current
            }
            return pending
        }
        return current ?: locationMutable.value
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
     * Readium reports a clip only once it actually starts. Lookahead is already owned by the
     * buffer and begins when audio is requested, so there is no started-only prefetch here.
     */
    private fun onUtteranceStarted(text: String, audio: NarrationAudio) {
        val now = SystemClock.elapsedRealtime()
        val gapMs = if (gapBaselineAtMs > 0L) (now - gapBaselineAtMs).coerceAtLeast(0L) else 0L
        gapBaselineAtMs = 0L
        currentSpokenText = text
        lastPlaybackIdentity = audio.diagnosticIdentity ?: "provider=${provider.name}"
        if (audio.fromCache) cachedStarts++
        stateMutable.update {
            it.copy(
                cached = audio.fromCache,
                playing = true,
                startedUtterances = it.startedUtterances + 1L,
                lastPlaybackStartedAtMs = now,
                maxGapMs = maxOf(it.maxGapMs, gapMs),
                lastAudioKey = audio.file.nameWithoutExtension.take(12),
                lastAudioConfiguration = audio.configuration?.take(12)
            )
        }
        val current = state.value
        diagnostic(
            "event=play_start number=${current.startedUtterances} cached=${audio.fromCache} " +
                "audioMs=${audio.durationMs} key=${current.lastAudioKey} " +
                "profile=${current.lastAudioConfiguration ?: "unreported"} gapMs=$gapMs $lastPlaybackIdentity"
        )
    }

    private fun onUtteranceCompleted() {
        val now = SystemClock.elapsedRealtime()
        gapBaselineAtMs = now
        stateMutable.update {
            it.copy(
                completedUtterances = it.completedUtterances + 1L,
                lastPlaybackCompletedAtMs = now
            )
        }
        val current = state.value
        diagnostic(
            "event=play_complete completed=${current.completedUtterances} cachedStarts=$cachedStarts " +
                "maxGapMs=${current.maxGapMs} readyParagraphs=${current.readyParagraphs} " +
                "readyMs=${current.readyDurationMs} active=${current.pendingSynthesis} $lastPlaybackIdentity"
        )
    }

    private fun onBufferState(buffer: NarrationBufferState) {
        stateMutable.update {
            it.copy(
                buffering = buffer.buffering,
                cached = it.cached && !buffer.buffering,
                readyDurationMs = buffer.readyDurationMs,
                readyParagraphs = buffer.readyParagraphs,
                targetParagraphs = buffer.targetParagraphs,
                pendingSynthesis = buffer.pendingSynthesis,
                burst = buffer.burst
            )
        }
        val now = SystemClock.elapsedRealtime()
        val changedPhase = buffer.buffering != lastBuffering || buffer.burst != lastBurst
        if (changedPhase || now - lastBufferReportAtMs >= 1_000L) {
            lastBufferReportAtMs = now
            lastBuffering = buffer.buffering
            lastBurst = buffer.burst
            diagnostic(
                "event=reserve buffering=${buffer.buffering} readyMs=${buffer.readyDurationMs} " +
                    "readyParagraphs=${buffer.readyParagraphs} target=${buffer.targetParagraphs} " +
                    "active=${buffer.pendingSynthesis} mode=${if (buffer.burst) "burst" else "steady"}"
            )
        }
    }

    private fun action(event: String) {
        gapBaselineAtMs = 0L
        diagnostic("event=$event")
    }

    private fun diagnostic(report: String) {
        runCatching { onDiagnostic("narration session=$sessionId $report") }
    }

    private companion object {
        val sessionIds = AtomicLong(0L)
    }

    /**
     * Paragraph source for the buffer. The locator is resolved when lookahead is requested, not
     * when a clip starts, so the walk starts at the utterance actually being requested and uses a
     * pending `go()` target while Readium's published location is still stale.
     */
    private fun followingParagraphs(text: String): Flow<NarrationParagraph> {
        val locator = currentUtteranceLocator() ?: return emptyFlow()
        return content.following(locator, text)
    }

    private fun message(error: TtsNavigator.Error): String = when (error) {
        is TtsNavigator.Error.EngineError<*> -> when (val cause = error.cause) {
            is NarrationError.TooLong -> application.getString(R.string.reader_narration_error_too_long)
            is NarrationError.Storage, is NarrationError.TooLarge ->
                application.getString(R.string.reader_narration_error_storage)
            is NarrationError.Device ->
                if ((cause.cause as? AndroidSpeechFailure)?.code == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
                    application.getString(R.string.speech_failure_missing_english_data)
                } else {
                    application.getString(R.string.reader_narration_error_playback)
                }
            is NarrationError.Playback, is NarrationError.InvalidAudio ->
                application.getString(R.string.reader_narration_error_playback)
            else -> application.getString(R.string.reader_narration_error_unavailable)
        }
        is TtsNavigator.Error.ContentError ->
            application.getString(R.string.reader_narration_error_content)
    }
}
