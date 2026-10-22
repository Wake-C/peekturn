package app.peekturn.reader.speech

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.model.SentenceSpeechProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SystemSpeechLifecycleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lifecycle = SystemSpeechLifecycle()
    private val screens = mutableListOf<ActivityController<Activity>>()
    private lateinit var player: PronunciationPlayer

    @Before fun setUp() {
        ShadowTextToSpeech.reset()
        app.registerActivityLifecycleCallbacks(lifecycle)
        player = PronunciationPlayer(app)
    }

    @After fun tearDown() {
        player.shutdown()
        app.unregisterActivityLifecycleCallbacks(lifecycle)
        screens.forEach { it.destroy() }
        ShadowTextToSpeech.reset()
    }

    private fun screen(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).create().start().resume().also { screens += it }

    private fun speak(): TextToSpeech {
        player.speakExample("The same configured voice reads this sentence.", "US", SentenceSpeechProvider.ANDROID)
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The same configured voice reads this sentence.", shadowOf(engine).lastSpokenText)
        return engine
    }

    @Test fun internalNavigationReusesSpeechButReturningFromEngineSettingsRebinds() {
        val firstScreen = screen()
        val initial = speak()
        firstScreen.pause()
        val secondScreen = screen()
        firstScreen.stop()
        secondScreen.pause()
        firstScreen.start().resume()
        secondScreen.stop()

        val afterInternalNavigation = speak()
        assertSame("Ordinary app navigation must not invalidate the voice's cache configuration", initial, afterInternalNavigation)

        // The whole app leaves the foreground for system/provider settings, then returns.
        firstScreen.pause().stop().start().resume()
        val afterSettingsReturn = speak()
        assertNotSame("A settings return must reload the engine-managed default voice", initial, afterSettingsReturn)
        assertTrue(shadowOf(initial).isShutdown)
    }

    @Test fun regainingTheSameVisibleActivityRefreshesSpeechWithoutRequiringAStop() {
        val screen = screen()
        val initial = speak()
        // Multi-window or a system surface can pause the activity without stopping it.
        screen.pause().resume()
        assertNotSame(initial, speak())
    }
}
