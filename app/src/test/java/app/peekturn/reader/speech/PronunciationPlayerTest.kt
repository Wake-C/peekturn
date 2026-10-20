package app.peekturn.reader.speech

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.R
import app.peekturn.reader.model.SentenceSpeechProvider
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PronunciationPlayerTest {
    /** Records what the player asks the dictionary and can hold one lookup open to test supersession. */
    private class FakeSource : WordAudioSource {
        val calls = mutableListOf<Pair<String, String>>()
        var cancelled = 0
        var gate: CompletableDeferred<AudioClip?>? = null
        var clip: AudioClip? = null

        override suspend fun lookup(term: String, accent: String): AudioClip? {
            calls += term to accent
            val open = gate ?: return clip
            return try {
                open.await()
            } catch (failure: CancellationException) {
                cancelled++
                throw failure
            }
        }
    }

    /** One online sentence route — the read-aloud service, then the simple GET: same contract, holdable. */
    private class FakeSentenceSource(override val route: String = "bing") : SentenceAudioSource {
        val calls = mutableListOf<Pair<String, String>>()
        var cancelled = 0
        var gate: CompletableDeferred<AudioClip?>? = null
        var clip: AudioClip? = null

        /** The outcome the route reports when it has no clip, as a real client does. */
        var failure: RouteOutcome? = null

        override suspend fun lookup(
            text: String,
            accent: String,
            failed: (RouteOutcome) -> Unit
        ): AudioClip? {
            calls += text to accent
            failure?.let(failed)
            val open = gate ?: return clip
            return try {
                open.await()
            } catch (failure: CancellationException) {
                cancelled++
                throw failure
            }
        }
    }

    private val appContext = ApplicationProvider.getApplicationContext<Application>()
    private fun text(id: Int): String = appContext.getString(id)

    private fun loseAudioFocus() {
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    }

    private fun audioManager() = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)

    /** The engine the player created last, so a test can inspect or complete its initialization. */
    private fun lastEngine(): ShadowTextToSpeech = Shadow.extract(ShadowTextToSpeech.getLastTextToSpeechInstance())

    /** Reports the result the engine would deliver for the initialization the player started last. */
    private fun engineInitialization(status: Int) {
        lastEngine().onInitListener.onInit(status)
        settle()
    }

    private fun player(
        source: WordAudioSource,
        sentence: List<SentenceAudioSource> = listOf(FakeSentenceSource()),
        engines: List<String> = emptyList()
    ) = PronunciationPlayer(ApplicationProvider.getApplicationContext<Context>(), source, sentence) { engines }

    /** The same player with the production diagnostics hook captured, so log content is testable. */
    private fun diagnosticPlayer(
        diagnostics: MutableList<String>,
        sentence: List<SentenceAudioSource> = listOf(FakeSentenceSource()),
        alternativeEngines: List<String> = emptyList()
    ) = PronunciationPlayer(
        ApplicationProvider.getApplicationContext<Context>(),
        FakeSource(),
        sentence,
        onDiagnostic = { diagnostics += it },
        engines = { alternativeEngines }
    )

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Asserts the parts of a failure that matter: what the online routes did and what the device
     * answered. The composed sentence itself is production's job, so the test does not rebuild it.
     */
    private fun assertFailure(
        player: PronunciationPlayer,
        onlineLead: String?,
        deviceMessage: String?
    ) {
        val error = player.state.value.error
        assertNotNull(error)
        onlineLead?.let { assertTrue("The online half is stated", error!!.startsWith("$it, and ")) }
        deviceMessage?.let {
            assertTrue("The device's own answer is stated", error!!.contains(it.drop(1), ignoreCase = true))
        }
    }

    /**
     * The id the player passes to speak() for the first request of a fresh player. A test fails that
     * utterance by handing the engine's own listener an error, which is how a real engine reports one.
     */
    private val UTTERANCE_ID = "1"

    /**
     * Reports a failed utterance the way an engine does: while it is still in flight. Robolectric's
     * engine completes every utterance as soon as the looper runs, so the error is queued right behind
     * the initialization that makes the player speak, before that completion is delivered.
     */
    private fun failUtterance(engine: ShadowTextToSpeech, errorCode: Int) {
        Handler(Looper.getMainLooper()).post {
            engine.utteranceProgressListener.onError(UTTERANCE_ID, errorCode)
        }
    }

    /** Audio whose bytes never came from the network, so a leaked playback attempt stays local. */
    private fun wordAudio() = AudioClip(
        ByteArray(64) { it.toByte() },
        AudioCredit("Youdao Dictionary", "https://dict.youdao.com")
    )

    /** The read-aloud service's own answer for a phrase, sentence or example. */
    private fun bingAudio() = AudioClip(
        ByteArray(64) { (it + 1).toByte() },
        AudioCredit("Microsoft Read Aloud", "https://speech.platform.bing.com")
    )

    /** The fallback's answer: one English voice only, so its clip carries that caveat with it. */
    private fun baiduAudio() = AudioClip(
        ByteArray(64) { (it + 2).toByte() },
        AudioCredit("Baidu Translate", "https://fanyi.baidu.com"),
        BAIDU_ACCENT_NOTICE
    )

    /** Lets the shadow decoder actually start a provider clip, so playback can be asserted. */
    private fun playableClips() {
        ShadowMediaPlayer.resetStaticState()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("MediaDataSource"), ShadowMediaPlayer.MediaInfo(20_000, 0))
    }

    @Test fun examplesUseTheChosenOnlineSpeechRouteAndNeverTheDictionary() {
        val source = FakeSource()
        val sentence = FakeSentenceSource()
        val player = player(source, listOf(sentence))
        player.speakExample("A little further, one page at a time.", "UK", SentenceSpeechProvider.MICROSOFT)
        assertTrue(source.calls.isEmpty())
        assertEquals(listOf("A little further, one page at a time." to "UK"), sentence.calls)
        assertEquals("A little further, one page at a time.", player.state.value.text)
        player.shutdown()
    }

    @Test fun phrasesAreNeverSentToTheDictionary() {
        val source = FakeSource()
        val sentence = FakeSentenceSource()
        val player = player(source, listOf(sentence))
        player.speakSelection("A sentence from the book", "US", SentenceSpeechProvider.MICROSOFT)
        assertTrue(source.calls.isEmpty())
        assertEquals(listOf("A sentence from the book" to "US"), sentence.calls)
        assertEquals("A sentence from the book", player.state.value.text)
        player.shutdown()
    }

    /** A single word keeps its dictionary-first route even when the phrase provider is a different one. */
    @Test fun aSelectedWordKeepsItsDictionaryFirstRouteWhateverTheProviderChoice() {
        val word = FakeSource().apply { clip = null }
        val bing = FakeSentenceSource("bing")
        val baidu = FakeSentenceSource("baidu")
        val player = player(word, listOf(bing, baidu))

        player.speakSelection("hello", "US", SentenceSpeechProvider.BAIDU)
        settle()

        assertEquals(listOf("hello" to "US"), word.calls)
        assertEquals("A word still asks both speech services", word.calls, bing.calls)
        assertEquals(word.calls, baidu.calls)
        player.shutdown()
    }

    /**
     * A word the dictionary cannot voice still gets online audio: the read-aloud service voices it, and
     * device speech is never asked at all.
     */
    @Test fun aWordWithoutDictionaryAudioIsVoicedByTheFirstOnlineRoute() {
        playableClips()
        ShadowTextToSpeech.reset()
        val word = FakeSource().apply { clip = null }
        val sentence = FakeSentenceSource().apply { clip = bingAudio() }
        val player = player(word, listOf(sentence))

        player.speakWord("hello", "UK")
        settle()

        assertEquals(listOf("hello" to "UK"), word.calls)
        assertEquals("The dictionary's gap is covered by the read-aloud service",
            listOf("hello" to "UK"), sentence.calls)
        assertEquals(AudioCredit("Microsoft Read Aloud", "https://speech.platform.bing.com"), player.state.value.credit)
        assertTrue("The service's own bytes are what plays", player.state.value.playing)
        assertNull(player.state.value.error)
        assertNull("A service that honours the accent carries no caveat", player.state.value.notice)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /**
     * Microsoft and Baidu are separate choices, not a chain: the chosen service is the only one asked,
     * and when it has nothing the request fails instead of handing the text to the other route or to
     * installed speech.
     */
    @Test fun aChosenOnlineServiceIsTheOnlyRouteAndItsSilenceIsAnError() {
        playableClips()
        ShadowTextToSpeech.reset()
        val microsoft = FakeSentenceSource("bing").apply { clip = null }
        val baidu = FakeSentenceSource("baidu").apply { clip = baiduAudio() }
        val microsoftPlayer = player(FakeSource(), listOf(microsoft, baidu))

        microsoftPlayer.speakExample("A little further, one page at a time.", "UK", SentenceSpeechProvider.MICROSOFT)
        settle()

        assertEquals(listOf("A little further, one page at a time." to "UK"), microsoft.calls)
        assertTrue("The other online route is never tried", baidu.calls.isEmpty())
        assertNotNull(microsoftPlayer.state.value.error)
        assertFalse(microsoftPlayer.state.value.playing)
        assertNull("No other voice replaces the chosen one", microsoftPlayer.state.value.credit)
        assertNull("System speech is never started for a chosen online route",
            ShadowTextToSpeech.getLastTextToSpeechInstance())
        microsoftPlayer.shutdown()

        val selectedBing = FakeSentenceSource("bing")
        val selectedBaidu = player(FakeSource(), listOf(selectedBing, baidu))
        selectedBaidu.speakExample("A little further, one page at a time.", "UK", SentenceSpeechProvider.BAIDU)
        settle()

        assertEquals(listOf("A little further, one page at a time." to "UK"), baidu.calls)
        assertTrue("Microsoft is not asked when Baidu is chosen", selectedBing.calls.isEmpty())
        assertEquals(AudioCredit("Baidu Translate", "https://fanyi.baidu.com"), selectedBaidu.state.value.credit)
        assertEquals("The single English voice is disclosed with its audio", text(BAIDU_ACCENT_NOTICE),
            selectedBaidu.state.value.notice)
        assertTrue(selectedBaidu.state.value.playing)
        assertNull(selectedBaidu.state.value.error)
        selectedBaidu.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /** The explicitly chosen Android route uses installed speech and makes no online request at all. */
    @Test fun androidSystemSpeechIsChosenAndNeverContactsAnOnlineService() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val word = FakeSource()
        val microsoft = FakeSentenceSource("bing")
        val baidu = FakeSentenceSource("baidu")
        val player = player(word, listOf(microsoft, baidu))

        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        settle()
        engineInitialization(TextToSpeech.SUCCESS)

        assertTrue("Neither online route is contacted", microsoft.calls.isEmpty() && baidu.calls.isEmpty())
        assertEquals("A little further, one page at a time.", lastEngine().lastSpokenText)
        assertNull(player.state.value.error)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** A word tries the dictionary, then both online routes in turn, before any device speech. */
    @Test fun aWordTriesEveryOnlineRouteBeforeDeviceSpeech() {
        playableClips()
        ShadowTextToSpeech.reset()
        val word = FakeSource().apply { clip = null }
        val first = FakeSentenceSource().apply { clip = null }
        val second = FakeSentenceSource().apply { clip = baiduAudio() }
        val player = player(word, listOf(first, second))

        player.speakWord("hello", "US")
        settle()

        assertEquals(listOf("hello" to "US"), word.calls)
        assertEquals(listOf("hello" to "US"), first.calls)
        assertEquals(listOf("hello" to "US"), second.calls)
        assertEquals(AudioCredit("Baidu Translate", "https://fanyi.baidu.com"), player.state.value.credit)
        assertNull("Device speech was never needed", ShadowTextToSpeech.getLastTextToSpeechInstance())
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /** A dictionary recording that answers ends the request, so neither online route is asked at all. */
    @Test fun aDictionaryRecordingEndsTheRequestBeforeAnySpeechRoute() {
        playableClips()
        ShadowTextToSpeech.reset()
        val word = FakeSource().apply { clip = wordAudio() }
        val first = FakeSentenceSource().apply { clip = bingAudio() }
        val second = FakeSentenceSource().apply { clip = baiduAudio() }
        val player = player(word, listOf(first, second))

        player.speakWord("hello", "UK")
        settle()

        assertTrue("Only the dictionary is asked for a word it can voice", first.calls.isEmpty())
        assertTrue(second.calls.isEmpty())
        assertEquals(AudioCredit("Youdao Dictionary", "https://dict.youdao.com"), player.state.value.credit)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /** Every online route is asked before device speech, and their silence is announced as the fallback. */
    @Test fun deviceSpeechWaitsForEveryOnlineRouteAndIsAnnouncedAsTheFallback() {
        ShadowTextToSpeech.reset()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val word = FakeSource().apply { clip = null }
        val sentence = FakeSentenceSource()
        val gate = CompletableDeferred<AudioClip?>()
        sentence.gate = gate
        val player = player(word, listOf(sentence))

        player.speakWord("hello", "US")
        assertEquals(listOf("hello" to "US"), sentence.calls)
        assertTrue(player.state.value.playing)
        assertNull("Device speech waits for the online route", ShadowTextToSpeech.getLastTextToSpeechInstance())

        gate.complete(null)
        settle()
        engineInitialization(TextToSpeech.SUCCESS)

        assertNull("A service that has nothing is not a missing engine", player.state.value.error)
        assertNotNull("The fallback to device speech is announced", player.state.value.notice)
        assertEquals("hello", lastEngine().lastSpokenText)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** A stopped sentence request is cancelled with its coroutine, and its late audio stays silent. */
    @Test fun stopCancelsTheSentenceLookupAndKeepsLateAudioSilent() {
        val word = FakeSource()
        val sentence = FakeSentenceSource()
        val gate = CompletableDeferred<AudioClip?>()
        sentence.gate = gate
        val player = player(word, listOf(sentence))

        player.speakExample("A little further, one page at a time.", "UK", SentenceSpeechProvider.MICROSOFT)
        assertEquals(listOf("A little further, one page at a time." to "UK"), sentence.calls)
        assertTrue(player.state.value.playing)

        player.stop()
        settle()
        assertFalse(player.state.value.playing)
        assertEquals(1, sentence.cancelled)

        gate.complete(bingAudio())
        settle()
        assertFalse("A stopped lookup must not play later", player.state.value.playing)
        assertNull(player.state.value.credit)
        player.shutdown()
    }

    @Test fun singleWordsAreLookedUpWithTheRequestedAccent() {
        val source = FakeSource()
        val player = player(source)
        player.speakWord("Hello,", "UK")
        assertEquals(listOf("Hello" to "UK"), source.calls)
        player.speakWord("hello", "US")
        assertEquals(listOf("Hello" to "UK", "hello" to "US"), source.calls)
        player.shutdown()
    }

    @Test fun accentNamesTheAppDoesNotOfferAreRejectedWithoutAnyLookup() {
        val source = FakeSource()
        val sentence = FakeSentenceSource()
        val player = player(source, listOf(sentence))
        player.speakWord("hello", "AU")
        assertNotNull(player.state.value.error)
        assertFalse(player.state.value.playing)
        assertTrue(source.calls.isEmpty())
        assertTrue(sentence.calls.isEmpty())
        player.shutdown()
    }

    @Test fun stopCancelsTheLookupInFlightAndKeepsLateAudioSilent() {
        val source = FakeSource()
        val gate = CompletableDeferred<AudioClip?>()
        source.gate = gate
        val player = player(source)
        player.speakWord("hello", "UK")
        assertEquals(listOf("hello" to "UK"), source.calls)
        assertTrue(player.state.value.playing)

        player.stop()
        settle()
        assertFalse(player.state.value.playing)
        assertEquals(1, source.cancelled)
        assertNull(player.state.value.credit)
        assertNull(player.state.value.notice)
        assertNull(player.state.value.error)

        gate.complete(wordAudio())
        settle()
        assertFalse("A stopped lookup must not play later", player.state.value.playing)
        assertNull(player.state.value.credit)
        player.shutdown()
    }

    @Test fun aNewWordSupersedesTheEarlierLookup() {
        val source = FakeSource()
        val first = CompletableDeferred<AudioClip?>()
        source.gate = first
        val player = player(source)
        player.speakWord("hello", "UK")

        val second = CompletableDeferred<AudioClip?>()
        source.gate = second
        player.speakWord("water", "UK")
        assertEquals(listOf("hello" to "UK", "water" to "UK"), source.calls)
        assertEquals(1, source.cancelled)

        first.complete(wordAudio())
        settle()
        assertEquals("water", player.state.value.text)
        assertNull(player.state.value.credit)

        second.complete(null)
        settle()
        assertNull(player.state.value.credit)
        player.shutdown()
    }

    @Test fun shutdownStopsLookupsAndRefusesFurtherRequests() {
        val source = FakeSource()
        val gate = CompletableDeferred<AudioClip?>()
        source.gate = gate
        val player = player(source)
        player.speakWord("hello", "UK")

        player.shutdown()
        settle()
        assertFalse(player.state.value.playing)
        assertEquals(1, source.cancelled)

        player.speakWord("water", "UK")
        settle()
        assertEquals(listOf("hello" to "UK"), source.calls)
    }

    @Test fun stoppingClearsPlaybackStateButKeepsTheSpokenText() {
        val sentence = FakeSentenceSource().apply { gate = CompletableDeferred() }
        val player = player(FakeSource(), listOf(sentence))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.MICROSOFT)
        assertTrue("The route is still being asked", player.state.value.playing)
        player.stop()
        assertFalse(player.state.value.playing)
        assertNull(player.state.value.error)
        assertNull(player.state.value.notice)
        assertNull(player.state.value.credit)
        assertEquals("A little further, one page at a time.", player.state.value.text)
        player.shutdown()
    }

    @Test fun focusLossStopsTheCurrentPlayback() {
        val source = FakeSource().apply { clip = wordAudio() }
        val player = player(source)
        player.speakWord("hello", "UK")
        assertTrue("Speaking holds audio focus until it stops", player.state.value.playing)

        loseAudioFocus()
        assertFalse("Losing audio focus must stop playback", player.state.value.playing)
        assertNull(player.state.value.credit)
        assertNull(player.state.value.error)
        player.shutdown()
    }

    /** System speech clears its own pending request once it starts, so focus loss cannot use identity. */
    @Test fun focusLossSilencesOnlyTheNewestRequestThatIsStillPlaying() {
        assertTrue(focusLossSilences(playing = true, closed = false, requestId = 7, generation = 7))
        assertFalse("A finished request must not stop anything", focusLossSilences(playing = false, closed = false, requestId = 7, generation = 7))
        assertFalse("A superseded request must not stop the newest one", focusLossSilences(playing = true, closed = false, requestId = 6, generation = 7))
        assertFalse(focusLossSilences(playing = true, closed = true, requestId = 7, generation = 7))
    }

    /** A preparation that times out mid-queue must not let the released player claim credit or start. */
    @Test fun aTimedOutPreparationCannotStartAReleasedPlayerOrClaimCredit() {
        ShadowMediaPlayer.resetStaticState()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("MediaDataSource"), ShadowMediaPlayer.MediaInfo(20_000, 8_000))
        val source = FakeSource().apply { clip = wordAudio() }
        val player = player(source)
        player.speakWord("hello", "UK")
        assertTrue(player.state.value.playing)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_000))

        assertNull("Provider audio that never started must not claim credit", player.state.value.credit)
        assertEquals("hello", player.state.value.text)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
    }

    /** An engine failure for a superseded sentence must not kill the word that replaced it. */
    @Test fun aSupersededEngineFailureCannotKillTheWordThatReplacedSystemSpeech() {
        ShadowTextToSpeech.reset()
        val source = FakeSource()
        val gate = CompletableDeferred<AudioClip?>()
        val player = player(source)

        player.speakWord("A little further, one page at a time.", "US")
        source.gate = gate
        player.speakWord("hello", "UK")
        assertEquals(listOf("hello" to "UK"), source.calls)

        engineInitialization(TextToSpeech.ERROR)

        assertNull("A superseded engine failure is not this word's failure", player.state.value.error)
        assertTrue(player.state.value.playing)
        assertEquals("The new lookup must survive it", 0, source.cancelled)
        player.shutdown()
    }

    /** A broken default engine must not end the request while another installed engine can speak. */
    @Test fun aBrokenDefaultEngineGivesWayToAnotherInstalledEngine() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        val brokenDefault = lastEngine()

        engineInitialization(TextToSpeech.ERROR)

        assertTrue("The failed default engine is released", brokenDefault.isShutdown)
        val alternative = lastEngine()
        assertTrue("The other installed engine became the speaker", alternative !== brokenDefault)
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertNull(player.state.value.error)
        assertEquals("A little further, one page at a time.", alternative.lastSpokenText)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** An engine that never answers its initialization callback must not hold the request forever. */
    @Test fun anEngineThatNeverInitializesGivesWayToTheNextOne() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        val silent = lastEngine()
        assertTrue("The request waits while its engine initializes", player.state.value.playing)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_000))

        assertTrue("A silent engine is released when its initialization times out", silent.isShutdown)
        val alternative = lastEngine()
        assertTrue("The next installed engine is tried", alternative !== silent)
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertNull(player.state.value.error)
        assertEquals("A little further, one page at a time.", alternative.lastSpokenText)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * Text in another script never goes to the device's own voice, because the app cannot call that voice
     * English, so every installed engine is still asked before the failure reports what it saw.
     */
    @Test fun textInAnotherScriptNeverUsesTheDeviceVoiceAndEveryEngineIsStillAsked() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("你好，世界", "US", SentenceSpeechProvider.ANDROID)
        val firstEngine = lastEngine()

        engineInitialization(TextToSpeech.SUCCESS)

        val alternative = lastEngine()
        assertTrue("An engine that may not substitute gives way", firstEngine.isShutdown)
        assertTrue("The next installed engine is asked", alternative !== firstEngine)
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertNull("Another script is never spoken by a voice the app cannot name", alternative.lastSpokenText)
        assertFailure(player, null, text(R.string.speech_failure_no_english_voice))
        val report = player.state.value.diagnostic
        assertNotNull("The reader can send back what the engines answered", report)
        assertTrue("Every engine that was asked is named", report!!.contains("com.example.other-engine"))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** An engine that never starts is a different failure, and the report says which engines were asked. */
    @Test fun aDeviceWhoseEnginesNeverStartReportsTheEnginesItAsked() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)

        engineInitialization(TextToSpeech.ERROR)
        lastEngine().onInitListener.onInit(TextToSpeech.ERROR)
        settle()

        assertFailure(player, null, text(R.string.speech_failure_engine_not_started))
        val report = player.state.value.diagnostic
        assertNotNull(report)
        assertTrue("The engine that was asked is named", report!!.contains("com.example.other-engine"))
        assertTrue("An engine that never started gives no language answers to report",
            report.contains(DeviceSpeechFailure.NotStarted.verdict))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * An engine that does offer English keeps the request through the documented probes: the voice it was
     * asked for is the one that is set, and no substitute notice is attached.
     */
    @Test fun anOfferedEnglishVoiceKeepsBeingTheVoiceThatSpeaks() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val player = player(FakeSource())
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)

        engineInitialization(TextToSpeech.SUCCESS)

        val engine = lastEngine()
        assertEquals("A little further, one page at a time.", engine.lastSpokenText)
        assertNotNull("The English voice that was offered is the voice that is set", engine.getCurrentVoice())
        assertFalse("An offered English voice carries no substitute notice",
            player.state.value.notice?.contains(text(DEVICE_DEFAULT_VOICE_NOTICE)) == true)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * The final device attempt comes only after every installed engine has declined, and it uses the voice
     * the engine already holds, untouched and labelled: the reader's own engine choice, a vendor engine's
     * own voice, may be the only English-capable one on the device, but a real English voice found on any
     * engine was still worth more than a substituted one.
     */
    @Test fun everyEngineDeclinesBeforeTheDeviceOwnVoiceIsUsed() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakWord("hello", "US")
        val defaultEngine = lastEngine()

        engineInitialization(TextToSpeech.SUCCESS)

        assertFalse("A substitute is not used while another engine may still have English",
            player.state.value.notice?.contains(text(DEVICE_DEFAULT_VOICE_NOTICE)) == true)
        val alternative = lastEngine()
        assertTrue("The other installed engine is asked first", alternative !== defaultEngine)

        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        val substituting = lastEngine()
        assertTrue("The final pass starts with the engine the reader chose",
            substituting !== alternative && substituting !== defaultEngine)
        substituting.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertEquals("hello", substituting.lastSpokenText)
        assertNull("Nothing about the engine's own voice is changed", substituting.getCurrentVoice())
        assertNull("A voice the engine could speak with is not an installation error", player.state.value.error)
        assertTrue("A substituted voice is labelled as one",
            player.state.value.notice?.contains(text(DEVICE_DEFAULT_VOICE_NOTICE)) == true)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * The explicitly chosen Android route means installed English speech, not any voice the engine
     * happens to hold. With no English voice found it reports, matching narration's rejection of the
     * unverified default voice; the word route keeps its final pass above.
     */
    @Test fun explicitlyChosenAndroidSpeechNeverUsesTheDeviceOwnVoice() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        engineInitialization(TextToSpeech.SUCCESS)
        val alternative = lastEngine()
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertFailure(player, null, text(R.string.speech_failure_no_english_voice))
        assertSame("No unverified voice engine is started", alternative, lastEngine())
        assertNull("No voice the app did not choose reads the text", alternative.lastSpokenText)
        assertNull("No substitute notice is shown", player.state.value.notice)
        assertNotNull("The engines' answers are still reported", player.state.value.diagnostic)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * A substituted voice is not a promise. An engine that accepts the utterance and then fails it hands
     * the request to the next engine of the final pass, and only the end of that pass reports, with
     * everything the engines answered.
     */
    @Test fun aSubstitutedVoiceThatFailsHandsTheRequestToTheNextEngineOfTheFinalPass() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakWord("hello", "US")
        engineInitialization(TextToSpeech.SUCCESS)
        val alternative = lastEngine()
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        val firstSubstitute = lastEngine()
        firstSubstitute.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(firstSubstitute, TextToSpeech.ERROR_SYNTHESIS)
        settle()

        assertNull("A failed substituted voice is not the end of the story", player.state.value.error)
        val secondSubstitute = lastEngine()
        assertTrue("The next engine of the final pass was asked", secondSubstitute !== firstSubstitute)

        // Only the end of the final pass reports, and the report keeps what every engine answered. Both
        // engines accepted the text before failing it, so the honest verdict is a playback failure, not
        // a claim that no English voice exists.
        secondSubstitute.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(secondSubstitute, TextToSpeech.ERROR_SYNTHESIS)
        settle()

        assertFailure(player, null, text(DeviceSpeechFailure.PlaybackFailed.message))
        assertNotNull("That report carries what the engines answered", player.state.value.diagnostic)
        assertFalse(player.state.value.playing)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** An engine that cannot speak its own voice has its own words end the report. */
    @Test fun anEngineThatCannotSpeakItsOwnVoiceReportsWhatItSaid() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource())
        player.speakWord("hello", "US")
        // The only engine declines the voice pass, and the final pass asks it again.
        engineInitialization(TextToSpeech.SUCCESS)

        val substituting = lastEngine()
        substituting.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(substituting, TextToSpeech.ERROR_NOT_INSTALLED_YET)
        settle()

        assertFailure(player, null, text(R.string.speech_failure_missing_english_data))
        assertNotNull(player.state.value.diagnostic)
        assertFalse(player.state.value.playing)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * A voice the engine offered and set is a real answer: its playback failure is reported as one, and
     * the request is not handed to another engine behind the reader's back.
     */
    @Test fun anOfferedVoiceIsNotGivenAwayWhenItsPlaybackFails() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        val engine = lastEngine()

        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(engine, TextToSpeech.ERROR_SYNTHESIS)
        settle()

        assertFailure(player, null, text(DeviceSpeechFailure.PlaybackFailed.message))
        val report = player.state.value.diagnostic
        assertNotNull("A playback failure still carries the device evidence", report)
        assertTrue("An accepted utterance that failed is a playback verdict, not a missing voice",
            report!!.contains(DeviceSpeechFailure.PlaybackFailed.verdict))
        assertTrue(report.contains("Device speech report:"))
        assertFalse(player.state.value.playing)
        assertSame("An offered voice's failure is not handed to another engine", engine, lastEngine())
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** Falling back from provider audio to speech speaks with the focus the request already holds. */
    @Test fun aFallbackReusesTheFocusItAlreadyHoldsForTheSameRequest() {
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val source = FakeSource().apply { clip = wordAudio() }
        val manager = audioManager()
        val player = player(source)

        player.speakWord("hello", "US")
        assertNotNull("Provider audio takes audio focus", shadowOf(manager).lastAudioFocusRequest)
        // Identity must be compared on the framework request, not Robolectric's wrapper record.
        val grantedFocus = shadowOf(manager).lastAudioFocusRequest.audioFocusRequest
        assertNull(shadowOf(manager).lastAbandonedAudioFocusRequest)

        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        engineInitialization(TextToSpeech.SUCCESS)

        assertNull("The request keeps the focus it already holds", player.state.value.error)
        assertEquals("hello", lastEngine().lastSpokenText)
        assertSame("Completion releases the original focus instead of leaking it",
            grantedFocus, shadowOf(manager).lastAbandonedAudioFocusRequest)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /**
     * When every online route is silent and no device voice speaks, one copyable report names each
     * online route, the build and clock identity, and what every engine answered - and says plainly
     * that the online routes failed, so the device's own answer is not mistaken for the whole story.
     */
    @Test fun aTotalFailureReportsBothOnlineRoutesAndTheDeviceEvidence() {
        ShadowTextToSpeech.reset()
        val bing = FakeSentenceSource("bing")
        val baidu = FakeSentenceSource("baidu")
        val player = player(FakeSource(), listOf(bing, baidu))
        val spoken = "A little further, one page at a time."
        player.speakWord(spoken, "US")
        settle()
        engineInitialization(TextToSpeech.ERROR)

        val error = player.state.value.error
        assertNotNull(error)
        assertTrue("Both online failures are stated, not turned into a missing-voice verdict",
            error!!.startsWith("Both online audio routes failed"))
        val report = player.state.value.diagnostic
        assertNotNull("The reader can copy what every route answered", report)
        assertTrue(report!!.contains("online: bing="))
        assertTrue(report.contains("; baidu="))
        assertTrue("The device evidence is still the second half", report.contains("Device speech report:"))
        assertTrue("A wrong device clock is visible", report.contains("device clock:"))
        assertTrue("The build is visible", report.contains("app: "))
        assertFalse("The report never carries the selection", report.contains(spoken))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** Nothing about the online routes is surfaced when one of them answers. */
    @Test fun aSuccessfulOnlineRouteLeavesNoReport() {
        playableClips()
        ShadowTextToSpeech.reset()
        val bing = FakeSentenceSource("bing").apply { clip = bingAudio() }
        val player = player(FakeSource(), listOf(bing))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.MICROSOFT)
        settle()

        assertNull(player.state.value.error)
        assertNull("Success shows no report at all", player.state.value.diagnostic)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /**
     * A chosen route that never answers cannot hold the request: at its four-second bound it is
     * cancelled, the request fails, and the report says the route timed out instead of pretending it
     * had no audio or handing the text to the other service.
     */
    @Test fun aStalledChosenRouteIsCutOffAndNeverFallsBack() {
        ShadowTextToSpeech.reset()
        ShadowMediaPlayer.resetStaticState()
        val bing = FakeSentenceSource("bing")
        bing.gate = CompletableDeferred()
        val baidu = FakeSentenceSource("baidu")
        val player = player(FakeSource(), listOf(bing, baidu))
        val spoken = "A little further, one page at a time."
        player.speakExample(spoken, "US", SentenceSpeechProvider.MICROSOFT)
        settle()
        assertEquals(listOf(spoken to "US"), bing.calls)
        assertTrue("The other route is not tried", baidu.calls.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000))
        settle()

        assertEquals("The stalled route is cancelled at its budget", 1, bing.cancelled)
        assertTrue("The other route is still not tried", baidu.calls.isEmpty())
        assertNull("A chosen online route never falls back to system speech",
            ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNotNull(player.state.value.error)
        assertFalse(player.state.value.playing)
        val report = player.state.value.diagnostic
        assertNotNull(report)
        assertTrue("The line names the stalled route and its timeout", report!!.contains("bing=timeout"))
        assertTrue("The bound the reader waited is reported", report.contains("4000ms"))
        assertFalse("A route that was not tried is not reported", report.contains("baidu="))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** A stage a client reports is what the reader can send back, not a stage the player invents. */
    @Test fun aClientReportedOutcomeReachesTheCopyableDiagnostic() {
        ShadowTextToSpeech.reset()
        val bing = FakeSentenceSource("bing").apply {
            failure = RouteOutcome("bing", RouteStage.FirstByte, 5, detail = NO_AUDIO_BYTES)
        }
        val player = player(FakeSource(), listOf(bing, FakeSentenceSource("baidu")))
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.MICROSOFT)
        settle()

        assertNotNull(player.state.value.error)
        val report = player.state.value.diagnostic
        assertNotNull(report)
        assertTrue("The client's own outcome is kept", report!!.contains("bing=no audio bytes 5ms"))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** A word records its dictionary route, then both services, and the message counts all three. */
    @Test fun aWordFailureNamesTheDictionaryAndBothServicesInOrder() {
        ShadowTextToSpeech.reset()
        val word = FakeSource().apply { clip = null }
        val bing = FakeSentenceSource("bing")
        val baidu = FakeSentenceSource("baidu")
        val player = player(word, listOf(bing, baidu))
        player.speakWord("hello", "US")
        settle()
        engineInitialization(TextToSpeech.ERROR)

        assertFailure(player, "All 3 online audio routes failed", text(R.string.speech_failure_engine_not_started))
        val report = player.state.value.diagnostic
        assertNotNull(report)
        val dictionary = report!!.indexOf("online: dictionary=")
        val firstService = report.indexOf("; bing=")
        val secondService = report.indexOf("; baidu=")
        assertTrue("The dictionary recording is recorded", dictionary >= 0)
        assertTrue("The services follow it in the order they were tried", dictionary < firstService && firstService < secondService)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * Text past what the services will voice is declined before any request, so no route is reported
     * as failed, and the message names the real reason instead of inventing a silent service.
     */
    @Test fun textTooLongForOnlineAudioIsNotReportedAsAFailedRoute() {
        ShadowTextToSpeech.reset()
        val word = FakeSource()
        val bing = FakeSentenceSource("bing")
        val baidu = FakeSentenceSource("baidu")
        val player = player(word, listOf(bing, baidu))
        player.speakWord("a".repeat(MAX_SPEECH_TEXT_LENGTH + 1), "US")
        settle()

        assertTrue("No dictionary lookup was made", word.calls.isEmpty())
        assertTrue("No speech service was asked", bing.calls.isEmpty() && baidu.calls.isEmpty())
        engineInitialization(TextToSpeech.ERROR)

        assertFailure(player, text(R.string.speech_failure_too_long_online), text(R.string.speech_failure_engine_not_started))
        val report = player.state.value.diagnostic
        assertNotNull(report)
        assertFalse("A route that was not attempted is not a failure", report!!.contains("online:"))
        assertTrue("The device evidence is still there", report.contains("Device speech report:"))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** A chosen online route declines oversized text without handing it to system speech. */
    @Test fun tooLongTextOnAChosenRouteIsAnErrorWithoutSystemSpeech() {
        ShadowTextToSpeech.reset()
        val bing = FakeSentenceSource("bing")
        val player = player(FakeSource(), listOf(bing))

        player.speakExample("a".repeat(MAX_SPEECH_TEXT_LENGTH + 1), "US", SentenceSpeechProvider.MICROSOFT)
        settle()

        assertTrue("The online service is not asked", bing.calls.isEmpty())
        assertNull("No device voice is started", ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertEquals(text(R.string.speech_failure_too_long_online), player.state.value.error)
        assertNull("A route that was not attempted leaves no report", player.state.value.diagnostic)
        assertFalse(player.state.value.playing)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * A chosen clip that was obtained and then could not play is an error: the app reports it and
     * never hands the text to system speech behind the reader's choice.
     */
    @Test fun aChosenClipThatCannotPlayIsAnErrorWithoutSystemSpeech() {
        ShadowTextToSpeech.reset()
        val bing = FakeSentenceSource("bing")
        val baidu = FakeSentenceSource("baidu").apply { clip = baiduAudio() }
        val manager = audioManager()
        val player = player(FakeSource(), listOf(bing, baidu))
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.BAIDU)
        settle()

        assertEquals(listOf("A little further, one page at a time." to "US"), baidu.calls)
        assertTrue("The other online route is not tried", bing.calls.isEmpty())
        assertEquals(text(R.string.speech_failure_audio_unavailable), player.state.value.error)
        assertNull("Nothing plays when audio focus is refused", player.state.value.credit)
        assertNull("A chosen source never falls back to system speech",
            ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertFalse(player.state.value.playing)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** Acting on a failure must not erase it; only a new request or a deliberate dismissal does. */
    @Test fun aFailureReportSurvivesStopAndIsClearedByANewRequestOrDismissal() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource())
        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        settle()
        engineInitialization(TextToSpeech.ERROR)

        val error = player.state.value.error
        val report = player.state.value.diagnostic
        assertNotNull(error)
        assertNotNull(report)

        player.stop()
        settle()
        assertEquals("A stop must not erase the failure the reader is acting on", error, player.state.value.error)
        assertEquals("The report survives the stop too", report, player.state.value.diagnostic)

        player.dismissFailure()
        assertNull("A deliberate dismissal clears the message", player.state.value.error)
        assertNull("A deliberate dismissal clears the report", player.state.value.diagnostic)

        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        assertNull("A new request is a new question", player.state.value.diagnostic)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** The dictionary recording is bounded by the same per-route budget as the speech services. */
    @Test fun theDictionaryRouteHonoursTheSamePerRouteBound() {
        ShadowTextToSpeech.reset()
        val word = FakeSource().apply { gate = CompletableDeferred() }
        val player = player(word, listOf(FakeSentenceSource("bing")))
        player.speakWord("hello", "US")
        settle()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000))
        settle()
        assertEquals("The dictionary lookup is cancelled at its bound", 1, word.cancelled)
        engineInitialization(TextToSpeech.ERROR)

        val report = player.state.value.diagnostic
        assertNotNull(report)
        assertTrue("The bound the dictionary was given is reported",
            report!!.contains("dictionary=timeout at connect 4000ms"))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * A ready engine survives a stop, and the next request reuses it without initialize(); the evidence
     * is cleared per request, so the retained engine must be re-associated or every answer is lost.
     */
    @Test fun aReusedReadyEngineIsIdentifiedAgainInTheSecondRequestsEvidence() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val diagnostics = mutableListOf<String>()
        val player = diagnosticPlayer(diagnostics)

        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.ANDROID)
        engineInitialization(TextToSpeech.SUCCESS)
        settle()
        diagnostics.clear()

        player.speakExample("Another page, still English.", "US", SentenceSpeechProvider.ANDROID)
        settle()

        val prepared = diagnostics.firstOrNull { it.startsWith("system speech prepared") }
        assertNotNull("The reused engine must still be identified for the new request", prepared)
        assertFalse("The evidence must not claim no engine was asked",
            prepared!!.contains("engines tried: none found"))
        assertTrue("The engine's own language answer belongs to the new request",
            prepared.contains("US English:") || prepared.contains("voice"))
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /**
     * A strict provider failure never reaches system speech, so it has no device evidence at all; the
     * visible failure still has to reach the diagnostic log with its app-owned category and routes.
     */
    @Test fun aStrictOnlineFailureIsLoggedWithoutDeviceEvidence() {
        ShadowTextToSpeech.reset()
        val diagnostics = mutableListOf<String>()
        val player = diagnosticPlayer(diagnostics, sentence = listOf(FakeSentenceSource("bing")))

        player.speakExample("A little further, one page at a time.", "US", SentenceSpeechProvider.MICROSOFT)
        settle()

        assertFailure(player, null, text(R.string.speech_failure_microsoft_unavailable))
        val failed = diagnostics.firstOrNull { it.startsWith("pronunciation failed") }
        assertNotNull("A visible online failure reaches the diagnostics log", failed)
        assertTrue("The app-owned category is stated", failed!!.contains("[online]"))
        assertTrue("The route that ran is stated", failed.contains("bing="))
        assertFalse("The spoken text never reaches the log", diagnostics.any {
            it.contains("A little further") || it.contains("one page at a time")
        })
        player.shutdown()
        ShadowTextToSpeech.reset()
    }
}
