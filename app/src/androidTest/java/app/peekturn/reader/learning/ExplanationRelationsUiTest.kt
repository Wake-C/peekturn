package app.peekturn.reader.learning

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.data.OfflineDictionary
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.Explanation
import app.peekturn.reader.model.ExplanationExample
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.speech.PronunciationPlayer
import app.peekturn.reader.ui.PeekturnTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExplanationRelationsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int, value: String) = context.getString(id, value)

    @Test fun completedRelationsAreTwoSeparateLinesAtNarrowWidth() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PeekturnTheme {
                    Box(Modifier.width(320.dp)) {
                        ExplanationRelations(Explanation(synonyms = "refuse, reject", antonyms = "accept"), true)
                    }
                }
            }
        }
        val synonyms = compose.onNodeWithText(label(R.string.reader_lookup_synonyms, "refuse, reject"))
        val antonyms = compose.onNodeWithText(label(R.string.reader_lookup_antonyms, "accept"))
        synonyms.assertIsDisplayed()
        antonyms.assertIsDisplayed()
        assertTrue(synonyms.fetchSemanticsNode().boundsInRoot.bottom <= antonyms.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun untranslatedExamplesDoNotReserveTheTranslationRows() {
        var language by mutableStateOf(ExplanationLanguage.English)
        var density = 1f
        val example = ExplanationExample("She refused.", "她拒绝了这个邀请，因为她当天已经有别的安排，也不想改变计划。")
        compose.setContent {
            density = LocalDensity.current.density
            PeekturnTheme {
                Box(Modifier.width(320.dp).testTag("example")) {
                    ExplanationExampleRow(example, language, false) {}
                }
            }
        }
        compose.onNodeWithText(example.translation).assertDoesNotExist()
        val englishHeight = compose.onNodeWithTag("example").fetchSemanticsNode().boundsInRoot.height
        org.junit.Assert.assertEquals(44f * density, englishHeight, 1f)
        compose.runOnIdle { language = ExplanationLanguage.Chinese }
        compose.onNodeWithText(example.translation).assertIsDisplayed()
        val chineseHeight = compose.onNodeWithTag("example").fetchSemanticsNode().boundsInRoot.height
        assertTrue("English=$englishHeight Chinese=$chineseHeight", chineseHeight > englishHeight)
    }

    @Test fun longEnglishExamplesExpandWithoutClippingAndSpeechStaysOnFirstLine() {
        var speaks = 0
        val example = ExplanationExample("She refused the invitation because she had already promised to spend the afternoon helping her family.")
        compose.setContent {
            PeekturnTheme {
                Box(Modifier.width(240.dp).testTag("example")) {
                    ExplanationExampleRow(example, ExplanationLanguage.English, false) { speaks++ }
                }
            }
        }
        compose.onNodeWithText(example.text).assertIsDisplayed()
        val text = compose.onNodeWithText(example.text).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithContentDescription(context.getString(R.string.reader_lookup_pronounce))
        assertTrue(text.height > button.fetchSemanticsNode().boundsInRoot.height)
        button.performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, speaks) }
    }

    @Test fun partialAnswerDoesNotInventAnAbsentRelation() {
        var complete by mutableStateOf(false)
        compose.setContent { PeekturnTheme { ExplanationRelations(Explanation(), complete) } }
        val empty = context.getString(R.string.reader_lookup_no_relation)
        compose.onNodeWithText(label(R.string.reader_lookup_synonyms, empty)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_lookup_antonyms, empty)).assertDoesNotExist()
        compose.runOnIdle { complete = true }
        compose.onNodeWithText(label(R.string.reader_lookup_synonyms, empty)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.reader_lookup_antonyms, empty)).assertIsDisplayed()
    }

    @Test fun explanationPopupPlacesRelationsAfterExamples() {
        val answer = Explanation(term = "refuse", partOfSpeech = "verb", meaning = "Say no.",
            context = "She says no to the offer.", examples = listOf(
                ExplanationExample("She refused the offer."), ExplanationExample("He refused to help.")),
            synonyms = "reject", antonyms = "accept")
        val session = LearningSession(source = ExplanationSource { _, _, _ -> flowOf(ExplanationEvent.Complete(answer)) })
        val speech = PronunciationPlayer(context)
        try {
            compose.setContent {
                PeekturnTheme {
                    LearningPopup(LookupContext("refuse", before = "She will ", after = " the offer."),
                        AppSettings(autoPronounce = false), session, OfflineDictionary(context), speech,
                        language = ExplanationLanguage.English, onClose = {}, onCancel = {})
                }
            }
            compose.waitUntil(5_000) { session.state.value.complete }
            val synonym = compose.onNodeWithText(label(R.string.reader_lookup_synonyms, "reject"))
            synonym.performScrollTo().assertIsDisplayed()
            val example = compose.onNodeWithText("He refused to help.").fetchSemanticsNode().boundsInRoot
            assertTrue(example.bottom <= synonym.fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithText(label(R.string.reader_lookup_antonyms, "accept")).performScrollTo().assertIsDisplayed()
            val speechButtons = compose.onAllNodesWithContentDescription(context.getString(R.string.reader_lookup_pronounce))
                .fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }.takeLast(2)
            assertTrue(speechButtons[1].center.y - speechButtons[0].center.y >= 48f * context.resources.displayMetrics.density)
            if (InstrumentationRegistry.getArguments().getString("captureUi") == "true") {
                val file = java.io.File(context.getExternalFilesDir("ui-checks"), "explanation-relations.png")
                file.outputStream().use { output ->
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
            }
        } finally {
            compose.runOnIdle { session.close(); speech.shutdown() }
        }
    }
}
