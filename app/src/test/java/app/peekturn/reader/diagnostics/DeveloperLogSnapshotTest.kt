package app.peekturn.reader.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The export header describes the environment and speech engines without touching device ids. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DeveloperLogSnapshotTest {
    @Test fun snapshotContainsSafeEnvironmentAndSpeechEngineMetadata() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val snapshot = developerLogSnapshot(context)

        assertTrue(snapshot.contains("App: "))
        assertTrue(snapshot.contains("Device: "))
        assertTrue(snapshot.contains("Android: "))
        assertTrue(snapshot.contains("Default TTS engine: "))
        assertTrue(snapshot.contains("Installed TTS engines: "))
        assertTrue(snapshot.contains("Recent app exits: unsupported before Android 11"))
        assertFalse(snapshot.contains("Android ID"))
    }

    @Test
    @Config(sdk = [30])
    fun newerAndroidReportsAnExitSectionHonestly() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val snapshot = developerLogSnapshot(context)

        assertTrue(snapshot.contains("Recent app exits:"))
        assertFalse(snapshot.contains("traceInputStream"))
        assertFalse(snapshot.contains("description="))
    }
}
