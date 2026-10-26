package app.peekturn.reader.learning

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.narration.NarrationState
import app.peekturn.reader.reader.ReaderNarrationCard
import app.peekturn.reader.speech.SpeechState
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuietSpeechUiTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun successfulExamplePlaybackShowsNoEngineNoticeOrDiagnosticBeforeOrAfterPlaying() {
        var state by mutableStateOf(SpeechState(playing = true, notice = "Engine/source notice", diagnostic = "Route details"))
        compose.setContent {
            PeekturnTheme { Text("Example content"); LookupSpeechFailure(state) {} }
        }
        compose.onNodeWithText("Example content").assertIsDisplayed()
        compose.onNodeWithText("Engine/source notice").assertDoesNotExist()
        compose.onNodeWithText("Route details").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(playing = false) }
        compose.onNodeWithText("Engine/source notice").assertDoesNotExist()
        compose.onNodeWithText("Route details").assertDoesNotExist()
    }

    @Test fun aRealSpeechFailureKeepsItsMessageDetailsAndDismissAction() {
        var dismissed = 0
        var state by mutableStateOf(SpeechState(error = "Speech failed", notice = "Not needed", diagnostic = "Failure route details"))
        compose.setContent {
            PeekturnTheme { LookupSpeechFailure(state) { dismissed++; state = state.copy(error = null) } }
        }
        compose.onNodeWithText("Speech failed").assertIsDisplayed()
        compose.onNodeWithText("Failure route details").assertIsDisplayed()
        compose.onNodeWithText("Not needed").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reader_lookup_dismiss_failure)).performClick()
        compose.onNodeWithText("Speech failed").assertDoesNotExist()
        compose.onNodeWithText("Failure route details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun readingTransportHidesHealthyTechnicalCountersButKeepsFailureAndRetry() {
        var retry = 0
        var state by mutableStateOf(NarrationState(active = true, playing = true, cached = true,
            notice = "Engine/source notice", readyParagraphs = 8, readyDurationMs = 45_000))
        compose.setContent {
            PeekturnTheme { ReaderNarrationCard(state, {}, {}, {}, {}, {}, {}, { retry++ }) }
        }
        compose.onNodeWithText("Engine/source notice").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reader_narration_cached)).assertDoesNotExist()
        val reserve = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reader_narration_reserve, 8, 45)
        compose.onNodeWithText(reserve).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(playing = false, error = "Narration failed") }
        compose.onNodeWithText("Narration failed").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_narration_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retry) }
    }
}
