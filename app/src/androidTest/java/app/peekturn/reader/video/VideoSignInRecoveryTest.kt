package app.peekturn.reader.video

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoSignInRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun signInOpensTheSameVideoExternallyWhileAnAnonymousChallengeKeepsItsOwnAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val link = VideoLink("SZhUit28l9o", 314_800)
        var failure by mutableStateOf(VideoFailureKind.SignInRequired)
        var retries = 0
        var outgoing: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                outgoing = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        compose.setContent {
            PeekturnTheme { Column { FailureMessage(failure, link) { retries++ } } }
        }
        instrumentation.addMonitor(monitor)
        try {
            val external = compose.onNodeWithText(context.getString(R.string.video_ui_open_youtube))
            val anonymous = compose.onNodeWithText(context.getString(R.string.video_ui_verify_youtube))
            anonymous.assertDoesNotExist()
            external.performClick()
            compose.runOnIdle {
                val intent = requireNotNull(outgoing)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https://www.youtube.com/watch?v=SZhUit28l9o&t=314s", intent.dataString)
                assertNull(intent.component)
                assertEquals(0, retries)
            }
            compose.onNodeWithText(context.getString(R.string.video_ui_retry)).performClick()
            compose.runOnIdle {
                assertEquals(1, retries)
                failure = VideoFailureKind.Verification
            }
            external.assertDoesNotExist()
            anonymous.assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(VideoVerificationActivity::class.java.name, outgoing?.component?.className)
                assertEquals(1, retries)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
