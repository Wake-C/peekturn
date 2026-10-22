package app.peekturn.reader.narration

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.media.AudioAttributes
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.speech.refreshSpeechConfiguration
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Persistent file synthesis goes through the real system engine connection and stores one WAV per
 * sentence and voice identity. A changed engine-reported voice gets a different cache key; unknown
 * voice metadata still speaks but its reuse is confined to one coherent session, never shared
 * across configurations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SystemNarrationSynthesizerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var cacheDirectory: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        cacheDirectory = Files.createTempDirectory("system-narration").toFile()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
            null
        )
        prefs().edit().clear().commit()
    }

    @After fun tearDown() {
        scope.cancel()
        cacheDirectory.deleteRecursively()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
            null
        )
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("narration-system-speech", Context.MODE_PRIVATE)

    private fun chooseEngine(packageName: String) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
            packageName
        )
        val info = PackageInfo().apply {
            this.packageName = packageName
            versionName = "1.0"
            versionCode = 1
            lastUpdateTime = 0L
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun voice(name: String): Voice =
        Voice(name, java.util.Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, setOf("alpha"))

    private fun newSynthesizer(
        factory: SystemSpeechConnectionFactory,
        gate: Semaphore = Semaphore(2)
    ): SystemNarrationSynthesizer {
        val renderer = SystemSpeechRenderer(
            context = context,
            gate = gate,
            onDiagnostic = {},
            platformFactory = factory,
            initializationTimeoutMs = 2_000,
            synthesisTimeoutMs = 2_000
        )
        return SystemNarrationSynthesizer(
            context = context,
            cache = SentenceAudioCache(cacheDirectory),
            scope = scope,
            gate = gate,
            onDiagnostic = {},
            renderer = renderer
        )
    }

    @Test fun aVoiceChangeDuringDemandResolvesTheNewVoiceUnderTheSameRequest() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection("engine.one", voice("first.voice"), manualCompletion = true)
        val second = FakeConnection("engine.one", voice("second.voice"))
        val factory = FakeFactory(listOf(first, second))
        val synthesizer = newSynthesizer(factory)
        try {
            val firstProfile = synthesizer.configuration()

            val demand = async(Dispatchers.Default) {
                synthesizer.audioFor("A sentence whose voice changes mid-render.")
            }
            kotlinx.coroutines.withTimeout(5_000) { first.started.await() }

            refreshSpeechConfiguration()
            first.finishInflight()

            val result = kotlinx.coroutines.withTimeout(10_000) { demand.await() }
            assertTrue("the waiting request must resolve under the new voice: $result", result.isSuccess)
            assertFalse("the new voice had to render its own audio", result.getOrThrow().fromCache)
            assertEquals("only the replacement voice synthesized", 1, second.synthesized.size)

            val secondProfile = synthesizer.configuration()
            assertNotEquals(firstProfile, secondProfile)

            val repeat = synthesizer.audioFor("A sentence whose voice changes mid-render.").getOrThrow()
            assertTrue("the retried render was stored under the new voice", repeat.fromCache)
            assertEquals(result.getOrThrow().file, repeat.file)
        } finally {
            synthesizer.close()
        }
    }

    @Test fun aRestartedProcessReusesTheCachedVoiceAudio() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection("engine.one", voice("engine.voice"))
        val firstSynthesizer = newSynthesizer(FakeFactory(listOf(first)))
        val firstAudio = try {
            firstSynthesizer.audioFor("Across a process restart.").getOrThrow()
        } finally {
            firstSynthesizer.close()
        }
        assertFalse("the first process synthesizes", firstAudio.fromCache)
        assertEquals(1, first.synthesized.size)

        // A new process has no in-memory state. Identity comes only from engine/version/voice
        // metadata, so the same public voice finds the persisted clip.
        val second = FakeConnection("engine.one", voice("engine.voice"))
        val secondSynthesizer = newSynthesizer(FakeFactory(listOf(second)))
        try {
            val cached = secondSynthesizer.audioFor("Across a process restart.").getOrThrow()
            assertTrue("the complete clip must survive the restart", cached.fromCache)
            assertEquals(firstAudio.file, cached.file)
            assertTrue("the restarted process must not synthesize again", second.synthesized.isEmpty())
        } finally {
            secondSynthesizer.close()
        }
    }

    @Test fun distinctVoiceMetadataSplitsDoNotShareOneCacheIdentity() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection(
            "engine.one",
            Voice(
                "model|en-US",
                java.util.Locale.US,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                emptySet()
            )
        )
        val firstSynthesizer = newSynthesizer(FakeFactory(listOf(first)))
        val a = try {
            firstSynthesizer.audioFor("Identity collision sentence.").getOrThrow()
        } finally {
            firstSynthesizer.close()
        }
        assertFalse("the first voice identity synthesizes", a.fromCache)

        // The same public engine key, no revision refresh. A naive pipe join encodes both voices
        // as "model|en-US|en-US"; the cache keys must still be distinct.
        val second = FakeConnection(
            "engine.one",
            Voice(
                "model",
                java.util.Locale.US,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                setOf("en-US")
            )
        )
        val secondSynthesizer = newSynthesizer(FakeFactory(listOf(second)))
        try {
            val b = secondSynthesizer.audioFor("Identity collision sentence.").getOrThrow()
            assertFalse("distinct voice metadata must not hit the first voice's cache", b.fromCache)
            assertNotEquals("the two identities own different files", a.file, b.file)
            assertEquals("the second identity had to synthesize", 1, second.synthesized.size)
        } finally {
            secondSynthesizer.close()
        }
    }

    @Test fun foregroundRefreshRebindsButReusesTheSameReportedVoiceAudio() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection("engine.one", voice("engine.voice"))
        val firstSynthesizer = newSynthesizer(FakeFactory(listOf(first)))
        val firstAudio = try {
            firstSynthesizer.audioFor("A reserve must survive a lock screen.").getOrThrow()
        } finally {
            firstSynthesizer.close()
        }
        assertFalse("the first request synthesizes", firstAudio.fromCache)
        assertEquals(1, first.synthesized.size)

        // Returning from lock/provider UI advances the revision; the public voice is unchanged.
        refreshSpeechConfiguration()

        val second = FakeConnection("engine.one", voice("engine.voice"))
        val factory = FakeFactory(listOf(second))
        val secondSynthesizer = newSynthesizer(factory)
        try {
            secondSynthesizer.configuration()
            assertEquals("the foreground refresh rebinds one client", 1, factory.requested.size)

            val reused = secondSynthesizer
                .audioFor("A reserve must survive a lock screen.")
                .getOrThrow()
            assertTrue(
                "an unchanged reported voice must reuse the old clip after a refresh",
                reused.fromCache
            )
            assertEquals("the cached file is unchanged", firstAudio.file, reused.file)
            assertTrue(
                "the refreshed client must not synthesize again",
                second.synthesized.isEmpty()
            )
        } finally {
            secondSynthesizer.close()
        }
    }

    @Test fun cacheEntriesFollowTheReportedVoiceIdentity() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection("engine.one", voice("first.voice"))
        val second = FakeConnection("engine.one", voice("second.voice"))
        val factory = FakeFactory(listOf(first, second))
        val synthesizer = newSynthesizer(factory)
        try {
            val a = synthesizer.audioFor("The same sentence.").getOrThrow()
            assertFalse("a new voice identity synthesizes", a.fromCache)
            assertTrue(a.file.isFile)

            val b = synthesizer.audioFor("The same sentence.").getOrThrow()
            assertTrue("an unchanged voice identity hits the cache", b.fromCache)
            assertEquals(a.file, b.file)
            assertEquals("no duplicate synthesis on a hit", 1, first.synthesized.size)

            val before = synthesizer.configuration()
            refreshSpeechConfiguration()
            val after = synthesizer.configuration()
            assertNotEquals("a refreshed configuration produces a new profile hash", before, after)

            val c = synthesizer.audioFor("The same sentence.").getOrThrow()
            assertFalse("the new voice must not read the old voice's clip", c.fromCache)
            assertNotEquals(a.file, c.file)
            assertEquals("only the replacement connection synthesized", 1, second.synthesized.size)
            assertTrue("the old identity's clip remains independently cached", a.file.isFile)
        } finally {
            synthesizer.close()
        }
    }

    @Test fun unknownVoiceMetadataStaysCoherentOnlyWithinTheSession() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection("engine.one", null)
        val second = FakeConnection("engine.one", null)
        val factory = FakeFactory(listOf(first, second))
        val synthesizer = newSynthesizer(factory)
        try {
            val firstProfile = synthesizer.configuration()
            val a = synthesizer.audioFor("Speech still works without voice metadata.").getOrThrow()
            assertFalse(a.fromCache)

            val b = synthesizer.audioFor("Speech still works without voice metadata.").getOrThrow()
            assertTrue("one coherent session nonce allows a local cache hit", b.fromCache)
            assertEquals(a.file, b.file)
            assertEquals(1, first.synthesized.size)

            refreshSpeechConfiguration()
            val secondProfile = synthesizer.configuration()
            assertNotEquals("a new session never reuses unknown-voice audio", firstProfile, secondProfile)

            val c = synthesizer.audioFor("Speech still works without voice metadata.").getOrThrow()
            assertFalse(c.fromCache)
            assertNotEquals(a.file, c.file)
            assertEquals(1, second.synthesized.size)
        } finally {
            synthesizer.close()
        }
    }

    private inner class FakeFactory(
        connections: List<FakeConnection>
    ) : SystemSpeechConnectionFactory {
        private val remaining = ArrayDeque(connections)
        val requested = mutableListOf<String?>()

        @Synchronized
        override fun create(
            context: Context,
            engine: String?,
            onInit: (Int) -> Unit
        ): SystemSpeechConnection {
            requested += engine
            val connection = remaining.removeFirstOrNull()
                ?: error("unexpected speech connection for ${engine ?: "default"}")
            connection.onInit = onInit
            onInit(connection.initStatus)
            return connection
        }
    }

    private inner class FakeConnection(
        private val packageName: String?,
        private val reportedVoice: Voice?,
        private val manualCompletion: Boolean = false
    ) : SystemSpeechConnection {
        lateinit var onInit: (Int) -> Unit
        var initStatus: Int = TextToSpeech.SUCCESS
        val synthesized = mutableListOf<String>()
        var listener: UtteranceProgressListener? = null
        val started = kotlinx.coroutines.CompletableDeferred<String>()
        private var pending: (() -> Unit)? = null

        override val voice: Voice?
            get() = reportedVoice
        override val enginePackage: String?
            get() = packageName

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
            this.listener = listener
        }

        override fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS

        override fun synthesizeToFile(
            text: String,
            params: Bundle?,
            file: File,
            utteranceId: String
        ): Int {
            synthesized += text
            if (manualCompletion) {
                pending = {
                    writeWave(file)
                    listener?.onDone(utteranceId)
                }
            } else {
                writeWave(file)
                listener?.onDone(utteranceId)
            }
            started.complete(text)
            return TextToSpeech.SUCCESS
        }

        override fun stop(): Int = TextToSpeech.SUCCESS

        override fun shutdown() = Unit

        fun finishInflight() {
            pending?.invoke()
        }
    }

    private fun writeWave(file: File, samples: Int = 800) {
        val sampleRate = 16_000
        val channels = 1
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val dataBytes = samples * blockAlign
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * blockAlign)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        file.writeBytes(buffer.array())
    }
}
