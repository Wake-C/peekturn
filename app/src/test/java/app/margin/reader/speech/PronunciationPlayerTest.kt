package app.margin.reader.speech

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
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
    private class FakeSentenceSource : SentenceAudioSource {
        val calls = mutableListOf<Pair<String, String>>()
        var cancelled = 0
        var gate: CompletableDeferred<AudioClip?>? = null
        var clip: AudioClip? = null

        override suspend fun lookup(text: String, accent: String): AudioClip? {
            calls += text to accent
            val open = gate ?: return clip
            return try {
                open.await()
            } catch (failure: CancellationException) {
                cancelled++
                throw failure
            }
        }
    }

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

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

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

    @Test fun examplesUseTheOnlineSpeechRoutesAndNeverTheDictionary() {
        val source = FakeSource()
        val sentence = FakeSentenceSource()
        val player = player(source, listOf(sentence))
        player.speakExample("A little further, one page at a time.", "UK")
        assertTrue(source.calls.isEmpty())
        assertEquals(listOf("A little further, one page at a time." to "UK"), sentence.calls)
        assertEquals("A little further, one page at a time.", player.state.value.text)
        player.shutdown()
    }

    @Test fun phrasesAreNeverSentToTheDictionary() {
        val source = FakeSource()
        val sentence = FakeSentenceSource()
        val player = player(source, listOf(sentence))
        player.speakWord("A sentence from the book", "US")
        assertTrue(source.calls.isEmpty())
        assertEquals(listOf("A sentence from the book" to "US"), sentence.calls)
        assertEquals("A sentence from the book", player.state.value.text)
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

    /** The first online route is asked before the second, and the second's clip keeps its own credit. */
    @Test fun aSentenceTheReadAloudServiceCannotVoiceFallsBackToTheSecondRoute() {
        playableClips()
        ShadowTextToSpeech.reset()
        val first = FakeSentenceSource().apply { clip = null }
        val second = FakeSentenceSource().apply { clip = baiduAudio() }
        val player = player(FakeSource(), listOf(first, second))

        player.speakExample("A little further, one page at a time.", "UK")
        settle()

        assertEquals(listOf("A little further, one page at a time." to "UK"), first.calls)
        assertEquals("The route with nothing hands the text on", first.calls, second.calls)
        assertEquals(AudioCredit("Baidu Translate", "https://fanyi.baidu.com"), player.state.value.credit)
        assertEquals("The fallback's single accent is disclosed with its audio", BAIDU_ACCENT_NOTICE, player.state.value.notice)
        assertTrue(player.state.value.playing)
        assertNull(player.state.value.error)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
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

        player.speakExample("A little further, one page at a time.", "UK")
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
        val source = FakeSource()
        val player = player(source)
        player.speakExample("A little further, one page at a time.", "US")
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

        player.speakExample("A little further, one page at a time.", "US")
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
        player.speakExample("A little further, one page at a time.", "US")
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
        player.speakExample("A little further, one page at a time.", "US")
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
        player.speakExample("你好，世界", "US")
        val firstEngine = lastEngine()

        engineInitialization(TextToSpeech.SUCCESS)

        val alternative = lastEngine()
        assertTrue("An engine that may not substitute gives way", firstEngine.isShutdown)
        assertTrue("The next installed engine is asked", alternative !== firstEngine)
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertNull("Another script is never spoken by a voice the app cannot name", alternative.lastSpokenText)
        assertEquals(NO_ENGLISH_VOICE, player.state.value.error)
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
        player.speakExample("A little further, one page at a time.", "US")

        engineInitialization(TextToSpeech.ERROR)
        lastEngine().onInitListener.onInit(TextToSpeech.ERROR)
        settle()

        assertEquals(NO_SPEECH_ENGINE, player.state.value.error)
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
        player.speakExample("A little further, one page at a time.", "US")

        engineInitialization(TextToSpeech.SUCCESS)

        val engine = lastEngine()
        assertEquals("A little further, one page at a time.", engine.lastSpokenText)
        assertNotNull("The English voice that was offered is the voice that is set", engine.getCurrentVoice())
        assertFalse("An offered English voice carries no substitute notice",
            player.state.value.notice?.contains(DEVICE_DEFAULT_VOICE_NOTICE) == true)
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
        player.speakExample("A little further, one page at a time.", "US")
        val defaultEngine = lastEngine()

        engineInitialization(TextToSpeech.SUCCESS)

        assertFalse("A substitute is not used while another engine may still have English",
            player.state.value.notice?.contains(DEVICE_DEFAULT_VOICE_NOTICE) == true)
        val alternative = lastEngine()
        assertTrue("The other installed engine is asked first", alternative !== defaultEngine)

        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        val substituting = lastEngine()
        assertTrue("The final pass starts with the engine the reader chose",
            substituting !== alternative && substituting !== defaultEngine)
        substituting.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertEquals("A little further, one page at a time.", substituting.lastSpokenText)
        assertNull("Nothing about the engine's own voice is changed", substituting.getCurrentVoice())
        assertNull("A voice the engine could speak with is not an installation error", player.state.value.error)
        assertTrue("A substituted voice is labelled as one",
            player.state.value.notice?.contains(DEVICE_DEFAULT_VOICE_NOTICE) == true)
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
        player.speakExample("A little further, one page at a time.", "US")
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

        // Only the end of the final pass reports, and the report keeps what every engine answered.
        secondSubstitute.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(secondSubstitute, TextToSpeech.ERROR_SYNTHESIS)
        settle()

        assertEquals("Only the end of the pass reports", NO_ENGLISH_VOICE, player.state.value.error)
        assertNotNull("That report carries what the engines answered", player.state.value.diagnostic)
        assertFalse(player.state.value.playing)
        player.shutdown()
        ShadowTextToSpeech.reset()
    }

    /** An engine that cannot speak its own voice has its own words end the report. */
    @Test fun anEngineThatCannotSpeakItsOwnVoiceReportsWhatItSaid() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource())
        player.speakExample("A little further, one page at a time.", "US")
        // The only engine declines the voice pass, and the final pass asks it again.
        engineInitialization(TextToSpeech.SUCCESS)

        val substituting = lastEngine()
        substituting.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(substituting, TextToSpeech.ERROR_NOT_INSTALLED_YET)
        settle()

        assertEquals(MISSING_ENGLISH_DATA, player.state.value.error)
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
        player.speakExample("A little further, one page at a time.", "US")
        val engine = lastEngine()

        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        failUtterance(engine, TextToSpeech.ERROR_SYNTHESIS)
        settle()

        assertNotNull(player.state.value.error)
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
}
