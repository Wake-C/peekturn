package app.peekturn.reader.narration

import android.app.Application
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

/**
 * Readium implements pause as cancellation, which calls TtsEngine.stop(). These tests drive the
 * real engine and Robolectric's MediaPlayer to prove that the following speak() of the same
 * sentence seeks back to the sampled position, while a new reading intent starts at zero.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NarrationEnginePauseTest {

    private val directory: File = Files.createTempDirectory("peekturn-narration-pause").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val voice = checkNotNull(narrationVoice("US"))
    private val source = CountingSource()
    private val synthesizer = NarrationSynthesizer(
        cache = SentenceAudioCache(directory, maxBytes = 4L * 1024 * 1024),
        source = source,
        scope = scope,
        accent = "US",
        voice = voice
    )
    private val policy = NarrationResumePolicy()
    private val players = CopyOnWriteArrayList<Pair<MediaPlayer, ShadowMediaPlayer>>()
    private var serial = 0L

    private val engine by lazy {
        CachedNarrationEngine(
            application = ApplicationProvider.getApplicationContext<Application>(),
            accent = "US",
            resolver = synthesizer,
            onStarted = { _, _ -> },
            resumePolicy = policy
        )
    }

    @Before fun setUp() {
        ShadowMediaPlayer.resetStaticState()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(180_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, shadow ->
            players += player to shadow
        }
    }

    @After fun tearDown() {
        engine.close()
        ShadowMediaPlayer.resetStaticState()
        scope.cancel()
        directory.deleteRecursively()
    }

    private class CountingSource : NarrationChunkSource {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun lookup(text: String, accent: String): ByteArray? {
            calls += text
            return ByteArray(417).also {
                it[0] = 0xFF.toByte()
                it[1] = 0xFB.toByte()
                it[2] = 0x90.toByte()
                it[3] = 0x64
            }
        }
    }

    /**
     * A resolver whose synthesis configuration can change between two utterances, as Android's
     * system voice settings or an online provider selection can while a sentence is paused.
     */
    private class SwitchingResolver(private val directory: File) : NarrationAudioResolver {
        var configuration = "first"
        val audioRequests = CopyOnWriteArrayList<String>()

        override suspend fun configuration(): String = configuration

        override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
            audioRequests += sentence
            val file = File(directory, "clip-$configuration.mp3")
            if (!file.isFile) {
                file.writeBytes(ByteArray(417).also {
                    it[0] = 0xFF.toByte()
                    it[1] = 0xFB.toByte()
                    it[2] = 0x90.toByte()
                    it[3] = 0x64
                })
            }
            return Result.success(NarrationAudio(file, fromCache = false))
        }

        override fun cancelPending() = Unit
    }

    private fun speak(text: String) = speak(engine, text)

    private fun speak(engine: CachedNarrationEngine, text: String) {
        val latch = CountDownLatch(1)
        engine.setListener(object : TtsEngine.Listener<NarrationError> {
            override fun onStart(requestId: TtsEngine.RequestId) {
                latch.countDown()
            }

            override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) = Unit
            override fun onInterrupted(requestId: TtsEngine.RequestId) = Unit
            override fun onFlushed(requestId: TtsEngine.RequestId) = Unit
            override fun onDone(requestId: TtsEngine.RequestId) = Unit
            override fun onError(requestId: TtsEngine.RequestId, error: NarrationError) = Unit
        })
        engine.speak(TtsEngine.RequestId("request-${++serial}"), text, null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (latch.count > 0L) Thread.sleep(5)
        }
        assertTrue("the engine started the requested sentence", latch.await(50, TimeUnit.MILLISECONDS))
    }

    private fun lastPlayer(): ShadowMediaPlayer = players.last().second

    @Test fun pauseAt45SecondsResumesAt45SecondsFromTheCachedClip() {
        val sentence = "The forty fifth word is where the reader pauses to look it up."
        speak(sentence)
        lastPlayer().setCurrentPosition(45_000)

        engine.stop()
        speak(sentence)

        assertEquals("the paused sentence is replayed from cache, not synthesized again", 1, source.calls.size)
        assertTrue(
            "the resume starts at the sampled position, not at the first frame",
            lastPlayer().currentPositionRaw >= 45_000
        )
    }

    @Test fun aNewReadingIntentStartsAtZeroEvenForTheSameSentence() {
        val sentence = "The forty fifth word is where the reader pauses to look it up."
        speak(sentence)
        lastPlayer().setCurrentPosition(45_000)
        engine.stop()

        // This is what narration does for next, previous, a new locator, retry, stop or end.
        policy.invalidateCheckpoint()
        speak(sentence)

        assertTrue(
            "a checkpoint from the previous intent cannot leak into the next utterance",
            lastPlayer().currentPositionRaw < 1_000
        )
    }

    @Test fun aSpeedChangeKeepsThePausedPosition() {
        val sentence = "The forty fifth word is where the reader pauses to look it up."
        speak(sentence)
        lastPlayer().setCurrentPosition(9_000)
        engine.stop()

        engine.submitPreferences(NarrationPreferences(speed = 1.5f))
        speak(sentence)

        assertTrue("changing speed does not restart the sentence", lastPlayer().currentPositionRaw >= 9_000)
    }

    @Test fun aPausedClipDoesNotResumeAfterTheNarrationSourceConfigurationChanged() {
        val sentence = "The forty fifth word is where the reader pauses to look it up."
        val resolver = SwitchingResolver(directory)
        val switching = CachedNarrationEngine(
            application = ApplicationProvider.getApplicationContext<Application>(),
            accent = "US",
            resolver = resolver,
            onStarted = { _, _ -> },
            resumePolicy = NarrationResumePolicy()
        )
        try {
            speak(switching, sentence)
            lastPlayer().setCurrentPosition(45_000)
            switching.stop()

            // The system voice or provider changed while the sentence was paused. The next resume
            // must resolve the current configuration instead of replaying the old paused file.
            resolver.configuration = "second"
            speak(switching, sentence)

            assertEquals(
                "a resume resolves the sentence again instead of trusting the old paused file",
                2,
                resolver.audioRequests.size
            )
            assertTrue(
                "the old configuration's paused offset cannot carry into the new clip",
                lastPlayer().currentPositionRaw < 1_000
            )
        } finally {
            switching.close()
        }
    }
}
