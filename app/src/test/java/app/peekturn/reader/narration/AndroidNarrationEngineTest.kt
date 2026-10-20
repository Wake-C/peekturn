package app.peekturn.reader.narration

import android.app.Application
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Real behavior of the native platform-speech narration adapter: preparation waits for the actual
 * engine callback, only the latest queued sentence survives, accepted-English metadata conflicts are
 * allowed with a notice, numeric errors stay errors, and late callbacks cannot resurrect a stopped
 * utterance.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidNarrationEngineTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val main = Looper.getMainLooper()
    private val diagnostics = mutableListOf<String>()
    private val notices = mutableListOf<Int?>()
    private val reports = mutableListOf<String?>()
    private val engines = mutableListOf<AndroidNarrationEngine>()
    private lateinit var factory: FakeFactory

    @After fun tearDown() {
        engines.forEach { it.close() }
        idle()
    }

    private fun idle() = shadowOf(main).idle()

    private fun newEngine(
        platforms: List<FakeSpeech>,
        alternatives: List<String> = emptyList(),
        maxInputLength: Int = 4_000,
        timeoutMs: Long = 1_000,
        startTimeoutMs: Long = 1_000,
        speed: Double = 1.0
    ): AndroidNarrationEngine {
        diagnostics.clear()
        notices.clear()
        reports.clear()
        factory = FakeFactory(platforms)
        val engine = AndroidNarrationEngine(
            context = context,
            language = Language(Locale.US),
            initialSpeed = speed,
            onDiagnostic = { diagnostics += it },
            onNotice = { notices += it },
            onFailureReport = { reports += it },
            platformFactory = factory,
            alternativeEngines = { alternatives },
            initializationTimeoutMs = timeoutMs,
            startTimeoutMs = startTimeoutMs,
            maxSpeechInputLength = maxInputLength
        )
        engines += engine
        return engine
    }

    private fun voice(name: String, locale: Locale = Locale.US): Voice =
        Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())

    private fun request(id: String) = TtsEngine.RequestId(id)

    @Test fun onlyTheLatestSentenceIsSpokenWhenSpeechBecomesReady() {
        val speech = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(speech), alternatives = listOf("spare.engine"))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("first"), "The first sentence is replaced.", null)
        engine.speak(request("second"), "Only the second sentence is read.", null)

        assertTrue("nothing may be spoken before the engine reports initialization", speech.spoken.isEmpty())
        assertEquals("no alternative engine is contacted before the default declines", listOf<String?>(null), factory.requested)

        speech.answerInit(TextToSpeech.SUCCESS)
        idle()
        speech.start()
        idle()

        assertEquals(listOf("Only the second sentence is read."), speech.spoken)
        assertEquals("the first queued sentence was superseded, not voiced", 1, speech.spoken.size)
        assertEquals(listOf("second"), listener.starts.map { it.value })
        assertEquals(1, factory.requested.size)
    }

    @Test fun acceptedEnglishLanguageWithConflictingVoiceMetadataStillSpeaksWithANotice() {
        val conflicting = voice("opaque-chinese", Locale.CHINESE)
        val speech = FakeSpeech(packageName = "vendor.engine").apply {
            listedVoices = emptySet()
            currentVoice = conflicting
            languageAnswers = { locale ->
                if (locale == Locale.US) TextToSpeech.LANG_COUNTRY_AVAILABLE
                else TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        val engine = newEngine(listOf(speech))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "English text still has to be read.", null)
        speech.answerInit(TextToSpeech.SUCCESS)
        idle()
        speech.start()
        idle()
        speech.done()
        idle()

        assertEquals(listOf("English text still has to be read."), speech.spoken)
        assertNotNull("accepted English with unconfirmed metadata must be labelled", notices.last())
        assertEquals(listOf("r1"), listener.starts.map { it.value })
        assertEquals(listOf("r1"), listener.dones.map { it.value })
        assertTrue(
            "the report names the engine that answered",
            diagnostics.any { it.contains("vendor.engine") }
        )
        assertTrue(
            "capability diagnostics never include the spoken passage",
            diagnostics.none { it.contains("English text still has to be read.") }
        )
        assertNull("a successful preparation shows no diagnostic report", reports.last())
    }

    @Test fun aNumericSynthesisErrorIsReportedAndNoOtherEngineIsTried() {
        val speech = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(speech), alternatives = listOf("spare.engine"))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "This sentence reaches synthesis.", null)
        speech.answerInit(TextToSpeech.SUCCESS)
        idle()
        speech.start()
        idle()
        speech.error(TextToSpeech.ERROR_NETWORK)
        idle()

        val error = listener.errors.single()
        assertEquals(
            TextToSpeech.ERROR_NETWORK,
            (error.cause as AndroidSpeechFailure).code
        )
        assertTrue("a failed utterance is never reported as done", listener.dones.isEmpty())
        assertEquals("a real synthesis failure must not silently switch engines", listOf<String?>(null), factory.requested)
        assertTrue(diagnostics.any { it.contains("ERROR_NETWORK") })
        assertTrue(reports.last()?.contains("failed") == true)
    }

    @Test fun stopInterruptsAndLateCallbacksCannotResurrectAnUtterance() {
        val speech = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(speech))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("paused"), "The paused sentence restarts from its beginning.", null)
        speech.answerInit(TextToSpeech.SUCCESS)
        idle()
        speech.start()
        idle()

        engine.stop()
        assertEquals(listOf("paused"), listener.interrupted.map { it.value })
        speech.done()
        speech.start()
        idle()
        assertEquals("a stale done/start from the stopped utterance is ignored", 1, listener.starts.size)
        assertTrue(listener.dones.isEmpty())

        engine.speak(request("next"), "The next sentence is read once.", null)
        idle()
        speech.start()
        idle()
        speech.done()
        idle()
        assertEquals(listOf("next"), listener.dones.map { it.value })

        engine.close()
        speech.start()
        speech.done()
        idle()
        assertEquals("callbacks after close are ignored", 2, listener.starts.size)
        assertEquals(listOf("next"), listener.dones.map { it.value })
    }

    @Test fun initializationFailureReachesTheQueuedSentenceAndARetryPreparesAgain() {
        val first = FakeSpeech(packageName = "default.engine")
        val retried = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(first, retried))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "Initialization must fail visibly.", null)
        first.answerInit(TextToSpeech.ERROR)
        idle()

        assertEquals(1, listener.errors.size)
        assertTrue("a failed initialization cannot speak", first.spoken.isEmpty())
        assertTrue(reports.last()?.contains("did not start") == true)

        engine.speak(request("r2"), "A user retry prepares the engine once more.", null)
        retried.answerInit(TextToSpeech.SUCCESS)
        idle()

        assertEquals(listOf<String?>(null, null), factory.requested)
        assertEquals(listOf("A user retry prepares the engine once more."), retried.spoken)
        retried.start()
        idle()
        assertEquals(listOf("r2"), listener.starts.map { it.value })
    }

    @Test fun initializationIsBoundedAndAnAlternativeIsOnlyTriedAfterADecline() {
        val silent = FakeSpeech(packageName = "default.engine")
        val alternative = FakeSpeech(packageName = "spare.engine")
        val engine = newEngine(
            listOf(silent, alternative),
            alternatives = listOf("spare.engine"),
            timeoutMs = 10
        )
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "A silent engine cannot hold the request.", null)
        shadowOf(main).idleFor(Duration.ofMillis(15))

        assertEquals("the default engine is asked first, then an installed alternative", listOf<String?>(null, "spare.engine"), factory.requested)
        assertTrue("the timed-out engine was shut down", silent.shutdowns > 0)
        alternative.answerInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("A silent engine cannot hold the request."), alternative.spoken)
    }

    @Test fun declinedPreparationMovesToTheNextEngineAndIsNeverTreatedAsSuccess() {
        val declined = FakeSpeech(packageName = "default.engine").apply {
            languageAnswers = { TextToSpeech.LANG_NOT_SUPPORTED }
        }
        val alternative = FakeSpeech(packageName = "spare.engine")
        val engine = newEngine(listOf(declined, alternative), alternatives = listOf("spare.engine"))

        engine.speak(request("r1"), "The alternative engine speaks this sentence.", null)
        declined.answerInit(TextToSpeech.SUCCESS)
        idle()

        assertEquals(listOf<String?>(null, "spare.engine"), factory.requested)
        assertTrue(declined.spoken.isEmpty())

        alternative.answerInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("The alternative engine speaks this sentence."), alternative.spoken)
    }

    @Test fun speakRefusalGetsOneReconnectThenFailsWithoutChangingEngines() {
        val first = FakeSpeech(packageName = "default.engine").apply {
            speakResult = TextToSpeech.ERROR
        }
        val retried = FakeSpeech(packageName = "default.engine").apply {
            speakResult = TextToSpeech.ERROR
        }
        val engine = newEngine(listOf(first, retried), alternatives = listOf("spare.engine"))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "A refused utterance is not success.", null)
        first.answerInit(TextToSpeech.SUCCESS)
        idle()
        retried.answerInit(TextToSpeech.SUCCESS)
        idle()

        assertEquals(1, listener.errors.size)
        assertTrue(listener.dones.isEmpty())
        assertEquals(
            "the default engine is retried once, never replaced by another provider",
            listOf<String?>(null, null),
            factory.requested
        )
        assertEquals(0, first.spoken.size + retried.spoken.size)
    }

    @Test fun aServiceErrorReinitializesTheSameSentenceOnceThenReports() {
        val first = FakeSpeech(packageName = "default.engine")
        val retried = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(first, retried))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "The service disconnects while reading.", null)
        first.answerInit(TextToSpeech.SUCCESS)
        idle()
        first.start()
        idle()
        first.error(TextToSpeech.ERROR_SERVICE)
        idle()

        assertTrue("a disconnected service is not an immediate failure", listener.errors.isEmpty())
        assertEquals(listOf<String?>(null, null), factory.requested)

        retried.answerInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("The service disconnects while reading."), retried.spoken)
        retried.start()
        idle()

        retried.error(TextToSpeech.ERROR_SERVICE)
        idle()
        assertEquals("recovery is bounded to one reconnect per utterance", 1, listener.errors.size)
        assertEquals(2, factory.requested.size)
    }

    @Test fun aDeadEngineAfterPauseIsReinitializedOnResume() {
        val first = FakeSpeech(packageName = "default.engine")
        val retried = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(first, retried))
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "Pause, background, then resume this sentence.", null)
        first.answerInit(TextToSpeech.SUCCESS)
        idle()
        first.start()
        idle()
        engine.stop()

        // The platform service died while narration was paused in the background.
        first.speakResult = TextToSpeech.ERROR
        engine.speak(request("r1"), "Pause, background, then resume this sentence.", null)
        idle()

        assertEquals("the same default engine is prepared again", listOf<String?>(null, null), factory.requested)
        retried.answerInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("Pause, background, then resume this sentence."), retried.spoken)
        assertTrue(listener.errors.isEmpty())
    }

    @Test fun speedIsClampedToThePlatformRangeAndBothTextBoundsFailExplicitly() {
        val speech = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(speech))
        engine.speak(request("r1"), "A sentence establishes the session.", null)
        speech.answerInit(TextToSpeech.SUCCESS)
        idle()

        engine.submitPreferences(NarrationPreferences(speed = 5f))
        assertTrue("speed is capped at the platform maximum", speech.rate == 2f)
        engine.submitPreferences(NarrationPreferences(speed = 0.1f))
        assertTrue("speed is floored at the platform minimum", speech.rate == 0.5f)

        val platformBound = newEngine(listOf(FakeSpeech(packageName = "default.engine")), maxInputLength = 10)
        val platformListener = RecordingListener()
        platformBound.setListener(platformListener)
        platformBound.speak(request("long"), "12345678901", null)
        assertTrue(platformListener.errors.single() is NarrationError.TooLong)
        assertTrue("a too-long platform input never reaches the engine", factory.requested.isEmpty())

        val chunkBound = newEngine(listOf(FakeSpeech(packageName = "default.engine")), maxInputLength = 100_000)
        val chunkListener = RecordingListener()
        chunkBound.setListener(chunkListener)
        val oversized = "word ".repeat(NARRATION_MAX_CHUNKS * 40).trim()
        chunkBound.speak(request("huge"), oversized, null)
        assertTrue(chunkListener.errors.single() is NarrationError.TooLong)
        assertTrue("the narration chunk bound is enforced before speech", factory.requested.isEmpty())
    }

    @Test fun anEngineThatAcceptsButNeverStartsIsReinitializedOnceThenFails() {
        val first = FakeSpeech(packageName = "default.engine")
        val retried = FakeSpeech(packageName = "default.engine")
        val engine = newEngine(listOf(first, retried), startTimeoutMs = 10)
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.speak(request("r1"), "A silent engine must not hang the reader.", null)
        first.answerInit(TextToSpeech.SUCCESS)
        idle()
        shadowOf(main).idleFor(Duration.ofMillis(15))

        assertTrue("the silent engine alone is not yet a failure", listener.errors.isEmpty())
        assertEquals(listOf<String?>(null, null), factory.requested)

        retried.answerInit(TextToSpeech.SUCCESS)
        idle()
        shadowOf(main).idleFor(Duration.ofMillis(15))

        assertEquals("after one reconnect the silence is a real error", 1, listener.errors.size)
        assertTrue(listener.dones.isEmpty())
        assertEquals(2, factory.requested.size)
    }

    private class RecordingListener : TtsEngine.Listener<NarrationError> {
        val starts = mutableListOf<TtsEngine.RequestId>()
        val ranges = mutableListOf<TtsEngine.RequestId>()
        val interrupted = mutableListOf<TtsEngine.RequestId>()
        val flushed = mutableListOf<TtsEngine.RequestId>()
        val dones = mutableListOf<TtsEngine.RequestId>()
        val errors = mutableListOf<NarrationError>()

        override fun onStart(requestId: TtsEngine.RequestId) {
            starts += requestId
        }

        override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) {
            ranges += requestId
        }

        override fun onInterrupted(requestId: TtsEngine.RequestId) {
            interrupted += requestId
        }

        override fun onFlushed(requestId: TtsEngine.RequestId) {
            flushed += requestId
        }

        override fun onDone(requestId: TtsEngine.RequestId) {
            dones += requestId
        }

        override fun onError(requestId: TtsEngine.RequestId, error: NarrationError) {
            errors += error
        }
    }

    private class FakeFactory(platforms: List<FakeSpeech>) : PlatformSpeechFactory {
        private val remaining = ArrayDeque(platforms)
        val requested = mutableListOf<String?>()

        override fun create(context: android.content.Context, engine: String?, onInit: (Int) -> Unit): PlatformSpeech {
            requested += engine
            val speech = remaining.removeFirstOrNull() ?: error("unexpected speech engine $engine")
            speech.onInit = onInit
            return speech
        }
    }

    private class FakeSpeech(
        private val packageName: String
    ) : PlatformSpeech {
        var listedVoices: Set<Voice>? = emptySet()
        var currentVoice: Voice? = null
        var languageAnswers: (Locale) -> Int = { TextToSpeech.LANG_COUNTRY_AVAILABLE }
        var voiceAnswers: (Voice) -> Int = { TextToSpeech.ERROR }
        var speakResult: Int = TextToSpeech.SUCCESS
        lateinit var onInit: (Int) -> Unit

        val spoken = mutableListOf<String>()
        val utteranceIds = mutableListOf<String>()
        var listener: UtteranceProgressListener? = null
        var rate: Float? = null
        var shutdowns = 0

        override val voices: Set<Voice>? get() = listedVoices
        override val voice: Voice? get() = currentVoice
        override val enginePackage: String? get() = packageName

        override fun setVoice(voice: Voice): Int {
            val result = voiceAnswers(voice)
            if (result == TextToSpeech.SUCCESS) currentVoice = voice
            return result
        }

        override fun setLanguage(locale: Locale): Int = languageAnswers(locale)

        override fun setSpeechRate(rate: Float): Int {
            this.rate = rate
            return TextToSpeech.SUCCESS
        }

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
            this.listener = listener
        }

        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
            if (speakResult == TextToSpeech.SUCCESS) {
                spoken += text
                utteranceIds += utteranceId
            }
            return speakResult
        }

        override fun stop(): Int = TextToSpeech.SUCCESS

        override fun shutdown() {
            shutdowns++
        }

        fun answerInit(status: Int = TextToSpeech.SUCCESS) {
            onInit(status)
        }

        fun start() {
            listener?.onStart(utteranceIds.last())
        }

        fun done() {
            listener?.onDone(utteranceIds.last())
        }

        fun error(code: Int) {
            listener?.onError(utteranceIds.last(), code)
        }
    }
}
