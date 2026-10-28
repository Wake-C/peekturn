package app.peekturn.reader.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.reader.ReaderFonts
import app.peekturn.reader.ui.PeekturnTheme
import app.peekturn.reader.ui.PeekturnIcons
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoReadingAppearanceTest {
    @get:Rule val compose = createComposeRule()
    private val cue = CaptionCue(1, 0, 12_000, "Alice watched the rabbit take a watch from its pocket.")
    private fun layout(text: String = cue.text): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test fun savedBookTypefaceSizeLineHeightAndLetterSpacingDriveTheParagraph() {
        var preferences by mutableStateOf(ReaderPreferences())
        compose.setContent {
            PeekturnTheme(theme = "warm") {
                Box(Modifier.width(320.dp)) {
                    VideoReadingParagraph(cue, current = true, onLookup = {}, preferences = preferences)
                }
            }
        }
        val before = layout()
        assertEquals(16f, before.layoutInput.style.fontSize.value, 0f)
        assertEquals(FontFamily.SansSerif, before.layoutInput.style.fontFamily)
        compose.runOnIdle {
            preferences = preferences.copy(fontFamily = "literata", fontSize = 150.0,
                lineHeight = 1.9, letterSpacing = 0.05)
        }
        val after = layout()
        assertEquals(24f, after.layoutInput.style.fontSize.value, 0f)
        assertEquals(45.6f, after.layoutInput.style.lineHeight.value, 0.001f)
        assertEquals(0.05f, after.layoutInput.style.letterSpacing.value, 0f)
        assertNotEquals(before.layoutInput.style.fontFamily, after.layoutInput.style.fontFamily)
        assertTrue(after.size.height > before.size.height)
        assertFalse(after.hasVisualOverflow)
    }

    @Test fun allSixBookFontsRenderAtNarrowWidthAndLargeSystemType() {
        var font by mutableStateOf("system")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PeekturnTheme(theme = "dark") {
                    Box(Modifier.width(280.dp)) {
                        VideoReadingParagraph(cue, current = false, onLookup = {},
                            preferences = ReaderPreferences(fontFamily = font, fontSize = 120.0))
                    }
                }
            }
        }
        ReaderFonts.all.forEach { family ->
            compose.runOnIdle { font = family.id }
            val result = layout()
            assertFalse("Clipped ${family.id}", result.hasVisualOverflow)
            assertEquals(19.2f, result.layoutInput.style.fontSize.value, 0.001f)
            if (family.id != "system") assertNotEquals(FontFamily.SansSerif, result.layoutInput.style.fontFamily)
        }
    }

    @Test fun returnToVideoUsesAnAccessibleIconWithoutAWatchTextButton() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        var density = 1f
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.video_ui_watch_video)
        compose.setContent {
            density = LocalDensity.current.density
            PeekturnTheme {
                Box(Modifier.width(280.dp)) {
                    VideoTopBar("Reading sample", {}, {}, true, true, false,
                        PeekturnIcons.Watch, label, { clicks++ }, trailingEnabled = enabled)
                }
            }
        }
        val icon = compose.onNodeWithContentDescription(label)
        icon.assertIsDisplayed().performClick()
        compose.onNodeWithText("Watch").assertDoesNotExist()
        val bounds = icon.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f * density - 1)
        assertTrue(bounds.height >= 48f * density - 1)
        compose.runOnIdle { assertEquals(1, clicks); enabled = false }
        icon.assertIsNotEnabled()
    }

    @Test fun reflowedWordLookupStillSelectsTheOriginalSourceOffsets() {
        val original = "Alice\n  watched\t the rabbit."
        val displayed = "Alice watched the rabbit."
        var selection: CaptionSelection? = null
        compose.setContent {
            PeekturnTheme {
                Box(Modifier.width(320.dp)) {
                    VideoReadingParagraph(cue.copy(text = original), current = true, onLookup = { selection = it },
                        preferences = ReaderPreferences(fontFamily = "source-serif-4", fontSize = 120.0))
                }
            }
        }
        val hit = layout(displayed).getBoundingBox(displayed.indexOf("watched") + 2).center
        compose.onNodeWithText(displayed).performTouchInput { click(hit) }
        compose.runOnIdle {
            assertEquals("watched", selection?.term)
            assertEquals(original.indexOf("watched"), selection?.start)
            assertEquals(original.indexOf("watched") + "watched".length, selection?.end)
        }
    }
}
