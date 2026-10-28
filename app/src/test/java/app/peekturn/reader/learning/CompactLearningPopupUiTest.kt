package app.peekturn.reader.learning

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.data.OfflineDictionary
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.Explanation
import app.peekturn.reader.model.ExplanationExample
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.speech.PronunciationPlayer
import app.peekturn.reader.speech.WordAudioSource
import app.peekturn.reader.ui.PeekturnTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Uses the real popup with deterministic content, not a network model or real audio service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w720dp-h320dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactLearningPopupUiTest {
    private val compose = createComposeRule()
    private val largeSystemFont = object : ExternalResource() {
        private var originalScale = 1f
        override fun before() {
            originalScale = RuntimeEnvironment.getFontScale()
            RuntimeEnvironment.setFontScale(2f)
        }
        override fun after() { RuntimeEnvironment.setFontScale(originalScale) }
    }
    // Popup owns a separate Android window and obtains density from its resources. Set the actual
    // system font scale before Compose creates its Activity, not just a parent CompositionLocal.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(largeSystemFont).around(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val lookup = LookupContext("refuse", before = "She will ", after = " the offer.")
    private val answer = Explanation(
        term = "refuse", partOfSpeech = "verb", ukIpa = "/rɪˈfjuːz/", usIpa = "/rɪˈfjuːz/",
        meaning = "To say that you will not accept or do something.",
        context = "The speaker does not accept the invitation and wants to make that choice clear.",
        examples = (1..8).map { ExplanationExample("Example $it: she refused the invitation and explained why.") },
        synonyms = "reject, decline", antonyms = "accept"
    )

    @Composable
    private fun ShortLandscape(content: @Composable () -> Unit) {
        val original = LocalWindowInfo.current
        val density = LocalDensity.current
        assertEquals("Host must use the actual large system font", 2f, density.fontScale, 0.001f)
        val size = with(density) { IntSize(720.dp.roundToPx(), 320.dp.roundToPx()) }
        val shortWindow = remember(original, size) {
            object : WindowInfo by original { override val containerSize: IntSize = size }
        }
        CompositionLocalProvider(LocalWindowInfo provides shortWindow) {
            PeekturnTheme(theme = "light", content = content)
        }
    }

    private fun assertLabelDoesNotClip(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout for $text", layouts.isNotEmpty())
        assertTextFits(text, layouts.single(), node)
    }

    private fun layoutDiagnostics(layout: TextLayoutResult, node: SemanticsNodeInteraction): String = buildString {
        append("size=${layout.size}, constraints=${layout.layoutInput.constraints}, ")
        append("fontSize=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}, ")
        append("density=${layout.layoutInput.density.density}, fontScale=${layout.layoutInput.density.fontScale}, ")
        append("maxLines=${layout.layoutInput.maxLines}, ")
        append("softWrap=${layout.layoutInput.softWrap}, overflow=${layout.layoutInput.overflow}, ")
        append("didOverflowWidth=${layout.didOverflowWidth}, didOverflowHeight=${layout.didOverflowHeight}, ")
        append("lineCount=${layout.lineCount}, multiParagraphWidth=${layout.multiParagraph.width}, ")
        append("multiParagraphHeight=${layout.multiParagraph.height}, ")
        if (layout.lineCount > 0) {
            append("line0=[top=${layout.getLineTop(0)}, bottom=${layout.getLineBottom(0)}, ")
            append("left=${layout.getLineLeft(0)}, right=${layout.getLineRight(0)}, ")
            append("baseline=${layout.getLineBaseline(0)}, ellipsized=${layout.isLineEllipsized(0)}], ")
        }
        append("bounds=${node.fetchSemanticsNode().boundsInRoot}")
    }

    private fun assertTextFits(text: String, layout: TextLayoutResult, node: SemanticsNodeInteraction) {
        val diagnostics = layoutDiagnostics(layout, node)
        assertEquals("Popup text must use 2x system font: $text $diagnostics",
            2f, layout.layoutInput.density.fontScale, 0.001f)
        assertFalse("Text height or maxLines clips content: $text $diagnostics", layout.didOverflowHeight)
        assertTrue("No lines rendered: $text $diagnostics", layout.lineCount > 0)
        assertEquals("Some text was not laid out: $text $diagnostics",
            layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        // didOverflowWidth compares a paragraph's allocated layout width to the smaller text-node
        // width. Native Robolectric can retain the larger allocation although every glyph fits.
        // Inspect each rendered line/glyph and the visible node instead; do not ignore clipping.
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Text viewport is vertically clipped: $text $diagnostics",
            visible.height + 1 >= layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(0))
        for (line in 0 until layout.lineCount) {
            assertFalse("Ellipsized line $line: $text $diagnostics", layout.isLineEllipsized(line))
            assertTrue("Line $line extends left of text viewport: $text $diagnostics", layout.getLineLeft(line) >= -1f)
            assertTrue("Line $line extends right of text viewport: $text $diagnostics",
                layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("Line $line is wider than its visible viewport: $text $diagnostics",
                layout.getLineRight(line) - layout.getLineLeft(line) <= visible.width + 1f)
        }
        for (offset in layout.layoutInput.text.indices) {
            val glyph = layout.getBoundingBox(offset)
            assertTrue("Glyph $offset is horizontally clipped: $text $diagnostics",
                glyph.left >= -1f && glyph.right <= layout.size.width + 1f &&
                    glyph.width <= visible.width + 1f)
        }
    }

    private fun assertTabLabelFits(text: String) {
        // Inspect the actual Text bounds, not the merged tab's larger touch-target bounds.
        val tabText = compose.onNodeWithText(text, useUnmergedTree = true)
        tabText.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        tabText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val diagnostics = layoutDiagnostics(layout, tabText)
        println("Tab layout: $text $diagnostics")
        assertTextFits(text, layout, tabText)
        val firstLine = layout.getLineBottom(0) - layout.getLineTop(0)
        assertTrue("Tab's first line is not fully visible: $text $diagnostics",
            tabText.fetchSemanticsNode().boundsInRoot.height + 1 >= firstLine)
    }

    private fun capturePopupIfRequested(name: String) {
        // Android's multi-window PixelCopy redraw fence can time out under Robolectric. Keep
        // screenshots explicitly opt-in; layout, clipping and interaction checks always run.
        if (!java.lang.Boolean.getBoolean("peekturn.captureUi")) {
            println("Screenshot check not run: set peekturn.captureUi=true only with a supported capture runtime.")
            return
        }
        val output = File("build/test-screenshots/$name.png")
        val directory = requireNotNull(output.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onNodeWithTag("learning-popup").captureToImage().asAndroidBitmap()
        output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
    }

    @Test fun largeTypeKeepsTabsLongContentAndPlaybackActionsReachableByScrolling() {
        val session = LearningSession(source = ExplanationSource { _, _, _ -> flowOf(ExplanationEvent.Complete(answer)) })
        val speech = PronunciationPlayer(context)
        var replay = 0
        var resume = 0
        var close = 0
        val visible = mutableStateOf(true)
        val readable = mutableListOf<Boolean>()
        try {
            compose.setContent {
                ShortLandscape {
                    if (visible.value) LearningPopup(
                        lookup, AppSettings(autoPronounce = false), session, OfflineDictionary(context), speech,
                        language = ExplanationLanguage.English,
                        onClose = { close++; visible.value = false }, onCancel = { visible.value = false },
                        onReplay = { replay++ }, onResume = { resume++ },
                        placement = LearningPopupPlacement.Center,
                        onReadableContent = { readable += it }
                    )
                }
            }
            compose.waitUntil(5_000) { session.state.value.complete }
            val popup = compose.onNodeWithTag("learning-popup").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertTrue("Popup wider than compact cap", popup.width <= 420f * density + 1)
            assertTrue("Popup fills the short window", popup.height < 320f * density)
            compose.onNodeWithContentDescription(label(R.string.reader_lookup_close_description)).assertIsDisplayed()
            capturePopupIfRequested("compact-popup-large-type")
            val selectionLayouts = mutableListOf<TextLayoutResult>()
            val selection = compose.onNodeWithText(lookup.selection)
            selection.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(selectionLayouts) }
            assertEquals("Selected word must use the actual large system font",
                2f, selectionLayouts.single().layoutInput.density.fontScale, 0.001f)
            val firstLine = selectionLayouts.single().getLineBottom(0) - selectionLayouts.single().getLineTop(0)
            assertTrue("Selected word is vertically clipped at large type",
                selection.fetchSemanticsNode().boundsInRoot.height + 1 >= firstLine)
            assertTabLabelFits(label(R.string.reader_lookup_tab_explain))
            assertTabLabelFits(label(R.string.reader_lookup_tab_dictionary))
            compose.onNodeWithText(answer.examples.last().text).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.reader_lookup_antonyms, answer.antonyms))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.learning_popup_replay_sentence)).performScrollTo().assertIsDisplayed()
                .performClick()
            compose.onNodeWithText(label(R.string.learning_popup_continue_watching)).performScrollTo().assertIsDisplayed()
            assertLabelDoesNotClip(label(R.string.learning_popup_continue_watching))
            capturePopupIfRequested("compact-popup-playback-actions")
            compose.onNodeWithText(label(R.string.learning_popup_continue_watching)).performClick()
            compose.runOnIdle { assertEquals(1, replay); assertEquals(1, resume); assertTrue(readable.last()) }

            // Both tabs remain reachable after scrolling to the bottom of a long answer.
            compose.onNodeWithText(label(R.string.reader_lookup_tab_dictionary)).performScrollTo().performClick()
            assertTabLabelFits(label(R.string.reader_lookup_tab_dictionary))
            compose.onNodeWithText(label(R.string.reader_lookup_tab_explain)).performScrollTo().performClick()
            assertTabLabelFits(label(R.string.reader_lookup_tab_explain))
            compose.onNodeWithText(answer.meaning).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.reader_lookup_close_description)).performClick()
            compose.runOnIdle { assertEquals(1, close); assertFalse(readable.last()) }
            compose.onNodeWithTag("learning-popup").assertDoesNotExist()
        } finally {
            compose.runOnIdle { session.close(); speech.shutdown() }
        }
    }

    @Test fun errorAndRetryRemainReachableWithoutRemovingThePlaybackFooter() {
        var requests = 0
        val session = LearningSession(source = ExplanationSource { _, _, _ -> flow {
            requests++
            if (requests == 1) emit(ExplanationEvent.Failure("fixture network failure", ExplanationFailureCategory.Connectivity))
            else emit(ExplanationEvent.Complete(answer))
        } })
        val speech = PronunciationPlayer(context)
        try {
            compose.setContent {
                ShortLandscape {
                    LearningPopup(lookup, AppSettings(), session, OfflineDictionary(context), speech,
                        language = ExplanationLanguage.English, onClose = {}, onCancel = {}, onReplay = {},
                        onResume = {}, placement = LearningPopupPlacement.Center)
                }
            }
            compose.waitUntil(5_000) { session.state.value.failure != null }
            compose.onNodeWithText(label(R.string.reader_lookup_retry)).performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { session.state.value.complete }
            compose.onNodeWithText(answer.meaning).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.learning_popup_replay_sentence)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.learning_popup_continue_watching)).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(2, requests) }
        } finally {
            compose.runOnIdle { session.close(); speech.shutdown() }
        }
    }

    @Test fun headerSpeechAndCloseStayUsableWhenTheAnswerIsScrolledToItsEnd() {
        val session = LearningSession(source = ExplanationSource { _, _, _ -> flowOf(ExplanationEvent.Complete(answer)) })
        // Stay in a cancellable fake lookup so this check cannot contact an audio service or TTS.
        val speech = PronunciationPlayer(context, wordAudio = WordAudioSource { _, _ -> awaitCancellation() })
        var closed = false
        try {
            compose.setContent {
                ShortLandscape {
                    LearningPopup(lookup, AppSettings(autoPronounce = false), session, OfflineDictionary(context), speech,
                        language = ExplanationLanguage.English, onClose = { closed = true }, onCancel = {},
                        onReplay = {}, placement = LearningPopupPlacement.Center)
                }
            }
            compose.waitUntil(5_000) { session.state.value.complete }
            compose.onNodeWithText(label(R.string.learning_popup_replay_sentence)).performScrollTo().assertIsDisplayed()
            val close = compose.onNodeWithContentDescription(label(R.string.reader_lookup_close_description))
            val pronounce = compose.onAllNodesWithContentDescription(label(R.string.reader_lookup_pronounce))[0]
            val density = context.resources.displayMetrics.density
            for (button in listOf(close, pronounce)) {
                button.assertIsDisplayed()
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48f * density - 1)
                assertTrue(bounds.height >= 48f * density - 1)
            }
            pronounce.performClick()
            compose.waitUntil(2_000) { speech.state.value.playing }
            compose.onNodeWithContentDescription(label(R.string.reader_lookup_stop_pronouncing))
                .assertIsDisplayed().performClick()
            compose.runOnIdle { assertFalse(speech.state.value.playing) }
            close.performClick()
            compose.runOnIdle { assertTrue(closed); assertFalse(speech.state.value.playing) }
        } finally {
            compose.runOnIdle { session.close(); speech.shutdown() }
        }
    }
}
