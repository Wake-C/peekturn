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
    /** Records what the player asks for and can hold one lookup open to test superseded requests. */
    private class FakeSource : WordAudioSource {
        val calls = mutableListOf<Pair<String, String>>()
        var cancelled = 0
        var gate: CompletableDeferred<RecordedWordAudio?>? = null
        var recording: RecordedWordAudio? = null

        override suspend fun lookup(term: String, accent: String): RecordedWordAudio? {
            calls += term to accent
            val open = gate ?: return recording
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

    private fun player(source: WordAudioSource) =
        PronunciationPlayer(ApplicationProvider.getApplicationContext<Context>(), source)

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Audio whose bytes never came from the network, so a leaked playback attempt stays local. */
    private fun recorded() = RecordedWordAudio(
        ByteArray(64) { it.toByte() },
        RecordingCredit("Dvortygirl", "Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:En-us-word.ogg", "Public domain", "")
    )

    @Test fun examplesNeverUseTheRecordedSource() {
        val source = FakeSource()
        val player = player(source)
        player.speakExample("A little further, one page at a time.", "UK")
        assertTrue(source.calls.isEmpty())
        assertEquals("A little further, one page at a time.", player.state.value.text)
        player.shutdown()
    }

    @Test fun phrasesAreNeverSentToTheRecordedSource() {
        val source = FakeSource()
        val player = player(source)
        player.speakWord("A sentence from the book", "US")
        assertTrue(source.calls.isEmpty())
        assertEquals("A sentence from the book", player.state.value.text)
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
        val player = player(source)
        player.speakWord("hello", "AU")
        assertNotNull(player.state.value.error)
        assertFalse(player.state.value.playing)
        assertTrue(source.calls.isEmpty())
        player.shutdown()
    }

    @Test fun stopCancelsTheLookupInFlightAndKeepsLateAudioSilent() {
        val source = FakeSource()
        val gate = CompletableDeferred<RecordedWordAudio?>()
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

        gate.complete(recorded())
        settle()
        assertFalse("A stopped lookup must not play later", player.state.value.playing)
        assertNull(player.state.value.credit)
        player.shutdown()
    }

    @Test fun aNewWordSupersedesTheEarlierLookup() {
        val source = FakeSource()
        val first = CompletableDeferred<RecordedWordAudio?>()
        source.gate = first
        val player = player(source)
        player.speakWord("hello", "UK")

        val second = CompletableDeferred<RecordedWordAudio?>()
        source.gate = second
        player.speakWord("water", "UK")
        assertEquals(listOf("hello" to "UK", "water" to "UK"), source.calls)
        assertEquals(1, source.cancelled)

        first.complete(recorded())
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
        val gate = CompletableDeferred<RecordedWordAudio?>()
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
        val source = FakeSource().apply { recording = recorded() }
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
        val source = FakeSource().apply { recording = recorded() }
        val player = player(source)
        player.speakWord("hello", "UK")
        assertTrue(player.state.value.playing)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_000))

        assertNull("A recording that never started must not claim credit", player.state.value.credit)
        assertEquals("hello", player.state.value.text)
        player.shutdown()
        ShadowMediaPlayer.resetStaticState()
    }

    /** An engine failure for a superseded sentence must not kill the word that replaced it. */
    @Test fun aSupersededEngineFailureCannotKillTheWordThatReplacedSystemSpeech() {
        ShadowTextToSpeech.reset()
        val source = FakeSource()
        val gate = CompletableDeferred<RecordedWordAudio?>()
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

    /** Falling back from a recording to speech speaks with the focus the request already holds. */
    @Test fun aFallbackReusesTheFocusItAlreadyHoldsForTheSameRequest() {
        ShadowMediaPlayer.resetStaticState()
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val source = FakeSource().apply { recording = recorded() }
        val manager = audioManager()
        val player = player(source)

        player.speakWord("hello", "US")
        assertNotNull("The recording takes audio focus", shadowOf(manager).lastAudioFocusRequest)
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
