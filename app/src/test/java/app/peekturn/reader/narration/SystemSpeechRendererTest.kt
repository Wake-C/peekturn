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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * File-rendering behavior of the system speech source. The reader follows Android's preferred
 * engine and its default voice: the renderer never selects a voice or language, a stable public
 * identity keeps cache hits, an inflight render cannot land under a superseded voice, cancellation
 * leaves no partial file, and the engine input bound is a real failure rather than a truncation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SystemSpeechRendererTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
            null
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_PITCH,
            null
        )
        prefs().edit().clear().commit()
    }

    @After fun tearDown() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
            null
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_PITCH,
            null
        )
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("narration-system-speech", Context.MODE_PRIVATE)

    @Test fun aSlowVoiceMetadataQueryDoesNotBlockInitializationCallbackOrClose() = runBlocking {
        supervisorScope {
            chooseEngine("engine.one")
            val metadataEntered = CompletableDeferred<Unit>()
            val releaseMetadata = CountDownLatch(1)
            val callbackReturned = CompletableDeferred<Unit>()
            val callbackThread = Executors.newSingleThreadExecutor()
            val connection = FakeConnection("engine.one", voice("default.voice"), autoInit = false)
            connection.onVoiceRead = {
                metadataEntered.complete(Unit)
                releaseMetadata.await()
            }
            val renderer = newRenderer(FakeFactory(listOf(connection)), timeoutMs = 5_000)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { renderer.configuration() }
            try {
                withTimeout(5_000) { connection.initRequested.await() }
                callbackThread.execute {
                    connection.onInit(TextToSpeech.SUCCESS)
                    callbackReturned.complete(Unit)
                }
                withTimeout(5_000) { metadataEntered.await() }
                withTimeout(1_000) { callbackReturned.await() }
                assertFalse("metadata is still pending", pending.isCompleted)
                renderer.close()
                assertTrue("closing does not wait for the provider metadata call", connection.shutdowns > 0)
            } finally {
                releaseMetadata.countDown()
                pending.cancel()
                renderer.close()
                callbackThread.shutdownNow()
            }
        }
    }

    private fun chooseEngine(
        packageName: String,
        versionName: String = "1.0",
        versionCode: Int = 1,
        lastUpdateTime: Long = 0L
    ) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
            packageName
        )
        val info = PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            this.versionCode = versionCode
            this.lastUpdateTime = lastUpdateTime
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun voice(name: String): Voice =
        Voice(name, Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, setOf("alpha"))

    private fun newRenderer(
        factory: SystemSpeechConnectionFactory,
        maxInputLength: Int = 4_000,
        timeoutMs: Long = 2_000
    ) = SystemSpeechRenderer(
        context = context,
        gate = Semaphore(2),
        onDiagnostic = {},
        platformFactory = factory,
        initializationTimeoutMs = timeoutMs,
        synthesisTimeoutMs = timeoutMs,
        maxSpeechInputLength = maxInputLength
    )

    @Test fun anUnchangedSystemVoiceRendersOneCompleteWaveAndReusesTheConnection() = runBlocking {
        chooseEngine("engine.one")
        val connection = FakeConnection("engine.one", voice("engine.voice"))
        val factory = FakeFactory(listOf(connection))
        val renderer = newRenderer(factory)
        val target = File(context.cacheDir, "render-one.wav").apply { delete() }
        try {
            val expected = renderer.configuration()

            assertEquals("the system preference is followed exactly", listOf<String?>("engine.one"), factory.requested)
            assertTrue("the public engine identity is stable: ${expected.engine}", expected.engine.contains("engine.one"))
            assertTrue("the engine version is part of the identity: ${expected.engine}", expected.engine.contains("1.0"))
            assertTrue("the reported default voice is named: ${expected.voice}", expected.voice.contains("engine.voice"))
            assertTrue("the voice locale is retained: ${expected.voice}", expected.voice.contains("en-US"))
            assertTrue("voice features are retained: ${expected.voice}", expected.voice.contains("alpha"))
            assertEquals("fixed cache format", "wav-pcm-v1", expected.format)
            assertTrue("fixed synthesis rate stays outside playback speed", expected.prosody.contains("rate=1.0"))
            assertTrue("the engine's own pitch is preserved", expected.prosody.contains("pitch=default"))

            renderer.render("Hello there.", target, expected)

            assertTrue("a complete file is returned only after onDone", target.isFile)
            assertArrayEquals("RIFF".toByteArray(), target.readBytes().copyOfRange(0, 4))
            assertEquals(listOf("Hello there."), connection.synthesized)
            assertEquals("one preferred engine connection", 1, factory.created.size)
            assertEquals("no voice selection API exists", 1f, connection.rate ?: -1f, 0f)
            assertEquals("the stable identity survives a repeat request", expected, renderer.configuration())

            renderer.render("Hello again.", target, expected)
            assertEquals("the same connection is reused", 1, factory.created.size)
            assertEquals(2, connection.synthesized.size)
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun aForegroundVoiceChangeCannotCommitTheInflightFile() = runBlocking {
        chooseEngine("engine.one")
        val first = FakeConnection("engine.one", voice("first.voice"), manualCompletion = true)
        val second = FakeConnection("engine.one", voice("second.voice"))
        val factory = FakeFactory(listOf(first, second))
        val renderer = newRenderer(factory)
        val target = File(context.cacheDir, "render-stale.wav").apply { delete() }
        try {
            val expected = renderer.configuration()
            assertTrue(expected.voice.contains("first.voice"))

            // The render legitimately fails as a child coroutine. A supervisor scope lets this
            // test observe that typed failure instead of structured concurrency cancelling the test.
            val failure = supervisorScope {
                val job = async(Dispatchers.Default) {
                    renderer.render("This voice is about to change.", target, expected)
                }
                withTimeout(5_000) { first.started.await() }

                // Returning from provider settings refreshes the same public engine identity.
                refreshSpeechConfiguration()
                first.finishInflight()

                runCatching { job.await() }.exceptionOrNull()
            }
            assertTrue("stale synthesis must fail, not return audio: $failure", failure is SystemSpeechError.ConfigurationChanged)
            assertFalse("no file may be committed under the old voice", target.exists())

            val fresh = renderer.configuration()
            assertTrue("the replacement connection reports the new voice", fresh.voice.contains("second.voice"))
            assertNotEquals(expected, fresh)
            assertEquals(2, factory.requested.size)
            assertTrue("the superseded client is shut down", first.shutdowns > 0)
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun theIdentityIncludesPackageRevisionAndSystemPitch() = runBlocking {
        chooseEngine(
            "engine.one",
            versionName = "9.8",
            versionCode = 42,
            lastUpdateTime = 1_700_000_000_000L
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.TTS_DEFAULT_PITCH,
            "85"
        )
        val connection = FakeConnection("engine.one", voice("engine.voice"))
        val renderer = newRenderer(FakeFactory(listOf(connection)))
        try {
            val identity = renderer.configuration()

            assertTrue("engine version name: ${identity.engine}", identity.engine.contains("9.8"))
            assertTrue("engine version code: ${identity.engine}", identity.engine.contains("42"))
            assertTrue(
                "engine package update time: ${identity.engine}",
                identity.engine.contains("1700000000000")
            )
            assertTrue(
                "the system default pitch is part of the identity: ${identity.prosody}",
                identity.prosody.contains("pitch=85")
            )
        } finally {
            renderer.close()
        }
    }

    @Test fun anUnidentifiedBoundEngineGetsASessionNonceNotTheRequestedPackage() = runBlocking {
        chooseEngine("engine.one")
        val connection = FakeConnection(null, voice("engine.voice"))
        val renderer = newRenderer(FakeFactory(listOf(connection)))
        val target = File(context.cacheDir, "render-unidentified.wav").apply { delete() }
        try {
            val identity = renderer.configuration()

            assertTrue(
                "the identity must admit it is unidentified: ${identity.engine}",
                identity.engine.contains("unidentified")
            )
            assertFalse(
                "the requested package is not proof of the bound engine: ${identity.engine}",
                identity.engine.contains("engine.one")
            )

            renderer.render("An unidentified engine still speaks this.", target, identity)
            assertTrue("speech is never blocked by missing identity", target.isFile)
            assertEquals(1, connection.synthesized.size)
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun engineAndVoiceMetadataAreSanitizedInDiagnostics() = runBlocking {
        chooseEngine("engine.one")
        val diagnostics = mutableListOf<String>()
        val connection = FakeConnection(
            "engine.one",
            voice("https://secret.example/token?api_key=abcdef")
        )
        val renderer = SystemSpeechRenderer(
            context = context,
            gate = Semaphore(2),
            onDiagnostic = { diagnostics += it },
            platformFactory = FakeFactory(listOf(connection)),
            initializationTimeoutMs = 2_000,
            synthesisTimeoutMs = 2_000,
            maxSpeechInputLength = 4_000
        )
        try {
            renderer.configuration()
        } finally {
            renderer.close()
        }

        assertTrue("preparation is diagnosed", diagnostics.isNotEmpty())
        assertTrue(
            "voice metadata must not leak a URL: $diagnostics",
            diagnostics.none { it.contains("secret.example") }
        )
        assertTrue(
            "voice metadata must not leak credentials: $diagnostics",
            diagnostics.none { it.contains("api_key=abcdef") }
        )
    }

    @Test fun aSystemChangeDuringFailedInitializationPreparesTheNewChoice() = runBlocking {
        chooseEngine("engine.one")
        val old = FakeConnection("engine.one", voice("old.voice"), autoInit = false)
        val fresh = FakeConnection("engine.two", voice("new.voice"))
        val factory = FakeFactory(listOf(old, fresh))
        val renderer = newRenderer(factory, timeoutMs = 1_000)
        try {
            val identity = supervisorScope {
                val pending = async(Dispatchers.Default) { renderer.configuration() }
                withTimeout(5_000) { old.initRequested.await() }

                chooseEngine("engine.two")
                old.onInit(TextToSpeech.ERROR)

                withTimeout(5_000) { pending.await() }
            }

            assertTrue(
                "the newly chosen engine must satisfy the request: ${identity.engine}",
                identity.voice.contains("new.voice")
            )
            assertEquals(listOf<String?>("engine.one", "engine.two"), factory.requested)
            assertTrue("the failed old connection is shut down", old.shutdowns > 0)
        } finally {
            renderer.close()
        }
    }

    @Test fun aSystemChangeDuringInitializationTimeoutPreparesTheNewChoice() = runBlocking {
        chooseEngine("engine.one")
        val old = FakeConnection("engine.one", voice("old.voice"), autoInit = false)
        val fresh = FakeConnection("engine.two", voice("new.voice"))
        val factory = FakeFactory(listOf(old, fresh))
        val renderer = newRenderer(factory, timeoutMs = 100)
        try {
            val identity = supervisorScope {
                val pending = async(Dispatchers.Default) { renderer.configuration() }
                withTimeout(5_000) { old.initRequested.await() }

                chooseEngine("engine.two")

                withTimeout(5_000) { pending.await() }
            }

            assertTrue(
                "a timed-out old engine must not fail the new choice: ${identity.engine}",
                identity.voice.contains("new.voice")
            )
            assertEquals(listOf<String?>("engine.one", "engine.two"), factory.requested)
        } finally {
            renderer.close()
        }
    }

    @Test fun aSameKeyInitializationFailureIsNotRetried() = runBlocking {
        chooseEngine("engine.one")
        val failing = FakeConnection("engine.one", voice("old.voice")).apply {
            initStatus = TextToSpeech.ERROR
        }
        val factory = FakeFactory(listOf(failing))
        val renderer = newRenderer(factory)
        try {
            val failure = runCatching { renderer.configuration() }.exceptionOrNull()

            assertTrue(
                "an unchanged system choice stays an honest failure: $failure",
                failure is SystemSpeechError.Initialization
            )
            assertEquals("no arbitrary retry", 1, factory.requested.size)
        } finally {
            renderer.close()
        }
    }

    @Test fun aDeadServiceIsReinitializedOnceForTheSameEngine() = runBlocking {
        chooseEngine("engine.one")
        val dead = FakeConnection("engine.one", voice("engine.voice")).apply {
            synthesizeResult = TextToSpeech.ERROR
        }
        val fresh = FakeConnection("engine.one", voice("engine.voice"))
        val factory = FakeFactory(listOf(dead, fresh))
        val renderer = newRenderer(factory)
        val target = File(context.cacheDir, "render-recovered.wav").apply { delete() }
        try {
            val expected = renderer.configuration()

            renderer.render("A dead service must recover once.", target, expected)

            assertTrue("the retried render produced a complete file", target.isFile)
            assertEquals(
                "the same system engine is prepared again",
                listOf<String?>("engine.one", "engine.one"),
                factory.requested
            )
            assertTrue("the dead connection is shut down", dead.shutdowns > 0)
            assertEquals("the replacement connection synthesized", 1, fresh.synthesized.size)
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun aServiceErrorCallbackReinitializesOnceForTheSameEngine() = runBlocking {
        chooseEngine("engine.one")
        val dead = FakeConnection("engine.one", voice("engine.voice")).apply {
            callbackError = TextToSpeech.ERROR_SERVICE
        }
        val fresh = FakeConnection("engine.one", voice("engine.voice"))
        val factory = FakeFactory(listOf(dead, fresh))
        val renderer = newRenderer(factory)
        val target = File(context.cacheDir, "render-service-callback.wav").apply { delete() }
        try {
            val expected = renderer.configuration()

            renderer.render("A service callback error recovers once.", target, expected)

            assertTrue(target.isFile)
            assertEquals(
                listOf<String?>("engine.one", "engine.one"),
                factory.requested
            )
            assertEquals(1, fresh.synthesized.size)
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun missingVoiceDataIsNotRetriedAsADeadService() = runBlocking {
        chooseEngine("engine.one")
        val failing = FakeConnection("engine.one", voice("engine.voice")).apply {
            synthesizeResult = TextToSpeech.ERROR_NOT_INSTALLED_YET
        }
        val factory = FakeFactory(listOf(failing))
        val renderer = newRenderer(factory)
        val target = File(context.cacheDir, "render-missing-data.wav").apply { delete() }
        try {
            val expected = renderer.configuration()

            val failure = runCatching {
                renderer.render("Missing voice data is not a dead service.", target, expected)
            }.exceptionOrNull()

            assertTrue(
                "the platform's missing-data error stays an honest failure: $failure",
                failure is SystemSpeechError.Rejected
            )
            assertEquals("no alternate connection is created", 1, factory.requested.size)
            assertFalse(target.exists())
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun unknownIdentityServiceRecoveryIsBoundedAcrossNonceReResolution() = runBlocking {
        chooseEngine("engine.one")
        val old = FakeConnection(null, voice("engine.voice")).apply {
            synthesizeResult = TextToSpeech.ERROR
        }
        val fresh = FakeConnection(null, voice("engine.voice")).apply {
            synthesizeResult = TextToSpeech.ERROR
        }
        val factory = FakeFactory(listOf(old, fresh))
        val renderer = newRenderer(factory)
        val target = File(context.cacheDir, "render-unknown-recovery.wav").apply { delete() }
        try {
            val firstIdentity = renderer.configuration()
            val firstFailure = runCatching {
                renderer.render("Unknown identity recovery is bounded.", target, firstIdentity)
            }.exceptionOrNull()
            assertTrue(
                "the nonce-only rebind surfaces as a configuration change: $firstFailure",
                firstFailure is SystemSpeechError.ConfigurationChanged
            )
            assertEquals("one rebind is created", 2, factory.requested.size)

            val secondIdentity = renderer.configuration()
            assertNotEquals(
                "the unknown identity advances to the rebind's nonce",
                firstIdentity,
                secondIdentity
            )

            val secondFailure = runCatching {
                renderer.render("Unknown identity recovery is bounded.", target, secondIdentity)
            }.exceptionOrNull()
            assertTrue(
                "the consumed allowance surfaces the real service failure: $secondFailure",
                secondFailure is SystemSpeechError.Rejected
            )
            assertEquals("the failure chain must not rebind a third time", 2, factory.requested.size)
            assertFalse(target.exists())
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun aSentenceBeyondTheEngineInputBoundFailsWithoutTruncation() = runBlocking {
        chooseEngine("engine.one")
        val connection = FakeConnection("engine.one", voice("engine.voice"))
        val renderer = newRenderer(FakeFactory(listOf(connection)), maxInputLength = 10)
        val target = File(context.cacheDir, "render-too-long.wav").apply { delete() }
        try {
            val expected = renderer.configuration()

            val failure = runCatching {
                renderer.render("12345678901", target, expected)
            }.exceptionOrNull()

            assertTrue("over-bound text is refused explicitly: $failure", failure is SystemSpeechError.TooLong)
            assertTrue("nothing is truncated and synthesized", connection.synthesized.isEmpty())
            assertFalse(target.exists())
        } finally {
            renderer.close()
            target.delete()
        }
    }

    @Test fun cancelPendingStopsTheRequestAndLeavesNoPartialFile() = runBlocking {
        chooseEngine("engine.one")
        val connection = FakeConnection("engine.one", voice("engine.voice"), manualCompletion = true)
        val renderer = newRenderer(FakeFactory(listOf(connection)))
        val target = File(context.cacheDir, "render-cancelled.wav").apply { delete() }
        try {
            val expected = renderer.configuration()
            val job = async(Dispatchers.Default) {
                renderer.render("A cancelled sentence must not appear.", target, expected)
            }
            withTimeout(5_000) { connection.started.await() }

            renderer.cancelPending()

            val failure = runCatching { job.await() }.exceptionOrNull()
            assertTrue("cancellation surfaces as cancellation: $failure", failure is kotlinx.coroutines.CancellationException)
            assertFalse(target.exists())
            assertTrue("the engine is stopped for the cancelled request", connection.stops > 0)
        } finally {
            renderer.close()
            target.delete()
        }
    }

    private inner class FakeFactory(
        connections: List<FakeConnection>
    ) : SystemSpeechConnectionFactory {
        private val remaining = ArrayDeque(connections)
        val requested = mutableListOf<String?>()
        val created = mutableListOf<FakeConnection>()

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
            created += connection
            if (connection.autoInit) {
                onInit(connection.initStatus)
            } else {
                connection.initRequested.complete(Unit)
            }
            return connection
        }
    }

    private inner class FakeConnection(
        private val packageName: String?,
        var reportedVoice: Voice?,
        private val manualCompletion: Boolean = false,
        val autoInit: Boolean = true
    ) : SystemSpeechConnection {
        lateinit var onInit: (Int) -> Unit
        var initStatus: Int = TextToSpeech.SUCCESS
        var synthesizeResult: Int = TextToSpeech.SUCCESS
        var callbackError: Int? = null
        var onVoiceRead: (() -> Unit)? = null
        val synthesized = mutableListOf<String>()
        var listener: UtteranceProgressListener? = null
        var rate: Float? = null
        var stops = 0
        var shutdowns = 0
        val started = CompletableDeferred<String>()
        val initRequested = CompletableDeferred<Unit>()
        private var pending: (() -> Unit)? = null

        override val voice: Voice?
            get() {
                onVoiceRead?.invoke()
                return reportedVoice
            }
        override val enginePackage: String?
            get() = packageName

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
            this.listener = listener
        }

        override fun setSpeechRate(rate: Float): Int {
            this.rate = rate
            return TextToSpeech.SUCCESS
        }

        override fun synthesizeToFile(
            text: String,
            params: Bundle?,
            file: File,
            utteranceId: String
        ): Int {
            if (synthesizeResult != TextToSpeech.SUCCESS) return synthesizeResult
            synthesized += text
            val error = callbackError
            if (error != null) {
                listener?.onError(utteranceId, error)
                started.complete(text)
                return TextToSpeech.SUCCESS
            }
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

        override fun stop(): Int {
            stops++
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            shutdowns++
        }

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
