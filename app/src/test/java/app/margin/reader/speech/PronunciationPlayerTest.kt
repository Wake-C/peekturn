package app.margin.reader.speech

import android.app.Application
import android.content.Context
import android.media.AudioManager
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

    /** The speech service, the second online route: same recording contract as the dictionary. */
    private class FakeSpeechSource : SentenceAudioSource {
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
        sentence: SentenceAudioSource = FakeSpeechSource(),
        engines: List<String> = emptyList()
    ) = PronunciationPlayer(ApplicationProvider.getApplicationContext<Context>(), source, sentence) { engines }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Audio whose bytes never came from the network, so a leaked playback attempt stays local. */
    private fun wordAudio() = AudioClip(
        ByteArray(64) { it.toByte() },
        AudioCredit("Youdao Dictionary", "https://dict.youdao.com")
    )

    /** The speech service's own answer for a phrase, sentence or example. */
    private fun sentenceAudio() = AudioClip(
        ByteArray(64) { (it + 1).toByte() },
        AudioCredit("Google Translate", "https://translate.google.com")
    )

    /** Lets the shadow decoder actually start a provider clip, so playback can be asserted. */
    private fun playableClips() {
        ShadowMediaPlayer.resetStaticState()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("MediaDataSource"), ShadowMediaPlayer.MediaInfo(20_000, 0))
    }

    @Test fun examplesUseTheSpeechServiceAndNeverTheDictionary() {
        val source = FakeSource()
        val sentence = FakeSpeechSource()
        val player = player(source, sentence)
        player.speakExample("A little further, one page at a time.", "UK")
        assertTrue(source.calls.isEmpty())
        assertEquals(listOf("A little further, one page at a time." to "UK"), sentence.calls)
        assertEquals("A little further, one page at a time.", player.state.value.text)
        player.shutdown()
    }

    @Test fun phrasesAreNeverSentToTheDictionary() {
        val source = FakeSource()
        val sentence = FakeSpeechSource()
        val player = player(source, sentence)
        player.speakWord("A sentence from the book", "US")
        assertTrue(source.calls.isEmpty())
        assertEquals(listOf("A sentence from the book" to "US"), sentence.calls)
        assertEquals("A sentence from the book", player.state.value.text)
        player.shutdown()
    }

    /**
     * A word the dictionary cannot voice still gets online audio: the speech service voices it, and
     * device speech is never asked at all.
     */
    @Test fun aWordWithoutDictionaryAudioIsVoicedByTheSpeechService() {
        playableClips()
        ShadowTextToSpeech.reset()
        val word = FakeSource().apply { clip = null }
        val sentence = FakeSpeechSource().apply { clip = sentenceAudio() }
        val player = player(word, sentence)

        player.speakWord("hello", "UK")
        settle()

        assertEquals(listOf("hello" to "UK"), word.calls)
        assertEquals("The dictionary's gap is covered by the speech service",
            listOf("hello" to "UK"), sentence.calls)
        assertEquals(AudioCredit("Google Translate", "https://translate.google.com"), player.state.value.credit)
        assertTrue("The service's own bytes are what plays", player.state.value.playing)
        assertNull(player.state.value.error)
        assertNull("Online audio needs no device speech", player.state.value.notice)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
    }

    /** The speech service is asked before device speech, and its silence is announced as the fallback. */
    @Test fun deviceSpeechWaitsForTheSpeechServiceAndIsAnnouncedAsTheFallback() {
        ShadowTextToSpeech.reset()
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val word = FakeSource().apply { clip = null }
        val sentence = FakeSpeechSource()
        val gate = CompletableDeferred<AudioClip?>()
        sentence.gate = gate
        val player = player(word, sentence)

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
        val sentence = FakeSpeechSource()
        val gate = CompletableDeferred<AudioClip?>()
        sentence.gate = gate
        val player = player(word, sentence)

        player.speakExample("A little further, one page at a time.", "UK")
        assertEquals(listOf("A little further, one page at a time." to "UK"), sentence.calls)
        assertTrue(player.state.value.playing)

        player.stop()
        settle()
        assertFalse(player.state.value.playing)
        assertEquals(1, sentence.cancelled)

        gate.complete(sentenceAudio())
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
        val sentence = FakeSpeechSource()
        val player = player(source, sentence)
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

    /** Every installed engine is tried before a request fails, so the failure is about English, not one engine. */
    @Test fun enginesWithoutEnglishAreAllTriedBeforeTheRequestFails() {
        ShadowTextToSpeech.reset()
        val player = player(FakeSource(), engines = listOf("com.example.other-engine"))
        player.speakExample("A little further, one page at a time.", "US")
        val firstEngine = lastEngine()

        engineInitialization(TextToSpeech.SUCCESS)

        val alternative = lastEngine()
        assertTrue("An English-less engine gives way to the next one", firstEngine.isShutdown)
        assertTrue("The next installed engine was tried", alternative !== firstEngine)
        alternative.onInitListener.onInit(TextToSpeech.SUCCESS)
        settle()

        assertNotNull("No installed engine could speak English", player.state.value.error)
        assertFalse(player.state.value.playing)
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
