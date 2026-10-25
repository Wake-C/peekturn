package app.peekturn.reader.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.ui.PeekturnTheme
import app.peekturn.reader.ui.ThemeChoices
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderAppearanceTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun noSliders() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)

    @Test fun fourSizeChoicesFitNarrowLargeTypeAndChangeOnlyFontSize() {
        val original = ReaderPreferences(fontSize = 113.7, pageMargins = 1.27, fontFamily = "literata")
        var draft by mutableStateOf(original)
        val title = text(R.string.reader_typography_size)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PeekturnTheme(theme = "dark") {
                    Box(Modifier.width(280.dp)) {
                        TypographyPresetRow(title, ReaderTypographyPreset.FONT_SIZE, draft) { draft = it }
                    }
                }
            }
        }
        val options = hasClickAction() and hasAnyAncestor(hasContentDescription(title))
        compose.onAllNodes(options).assertCountEquals(4)
        listOf(R.string.reader_size_small, R.string.reader_size_medium, R.string.reader_size_large, R.string.reader_size_extra_large)
            .forEach { compose.onNodeWithText(text(it)).assertIsDisplayed() }
        compose.onNodeWithText(text(R.string.reader_typography_custom)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_size_large)).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(original.copy(fontSize = 120.0), draft) }
        noSliders()
    }

    @Test fun spacingLabelsDoNotClipAtNarrowWidthOrLargeSystemType() {
        var scale by mutableStateOf(1.3f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                PeekturnTheme(theme = "dark") {
                    Box(Modifier.width(280.dp)) {
                        TypographyPresetRow(text(R.string.reader_typography_line_height), ReaderTypographyPreset.LINE_HEIGHT, ReaderPreferences()) {}
                    }
                }
            }
        }
        for (fontScale in listOf(1.3f, 2f)) {
            compose.runOnIdle { scale = fontScale }
            for (label in listOf(R.string.reader_preset_small, R.string.reader_preset_medium, R.string.reader_preset_large)) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(text(label), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertTrue(results.isNotEmpty())
                assertTrue("Clipped ${text(label)} at scale $fontScale: ${results.map { "${it.size}, lines=${it.lineCount}, height=${it.didOverflowHeight}, width=${it.didOverflowWidth}, paragraph=${it.multiParagraph.width}, lineEnd=${it.getLineRight(0)}" }}", results.none { it.hasVisualOverflow })
            }
        }
    }

    @Test fun allFiveThemesAreVisibleDirectlyAndUseTheExistingIds() {
        var selected by mutableStateOf("dark")
        val names = listOf(R.string.settings_theme_light, R.string.settings_theme_warm, R.string.settings_theme_sage,
            R.string.settings_theme_dark, R.string.settings_theme_oled)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PeekturnTheme(theme = "dark") {
                    Box(Modifier.width(280.dp)) { ThemeChoices(selected, { selected = it }) }
                }
            }
        }
        names.forEach { label ->
            compose.onNodeWithText(text(label)).assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text(label), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.isNotEmpty() && results.none { it.hasVisualOverflow })
        }
        compose.onNodeWithText(text(R.string.settings_theme_dark)).assertIsSelected()
        compose.onNodeWithText(text(R.string.settings_theme_oled)).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("oled", selected) }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun cancellingThemeAndTypographyDraftDoesNotApplyAnything() {
        var applied: Pair<ReaderPreferences, String>? = null
        var dismissed = false
        var showing by mutableStateOf(true)
        compose.setContent {
            PeekturnTheme(theme = "dark") {
                if (showing) ReaderTypographySheet(AppSettings(), { reading, theme -> applied = reading to theme }, {
                    dismissed = true; showing = false
                })
            }
        }
        compose.onNodeWithText(text(R.string.settings_theme_warm)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.reader_size_large)) and
            hasAnyAncestor(hasContentDescription(text(R.string.reader_typography_size)))).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.reader_typography_cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(applied) }
    }

    @Test fun applyingThemeAlonePreservesEveryLegacyCustomTypographyValue() {
        val custom = ReaderPreferences(fontSize = 113.7, lineHeight = 1.73, paragraphSpacing = 0.91,
            letterSpacing = 0.037, pageMargins = 1.27, topPaddingDp = 43.0, bottomPaddingDp = 67.0)
        var applied: Pair<ReaderPreferences, String>? = null
        compose.setContent {
            PeekturnTheme(theme = "dark") {
                ReaderTypographySheet(AppSettings(reading = custom), { reading, theme -> applied = reading to theme }, {})
            }
        }
        compose.onNodeWithText(text(R.string.settings_theme_warm)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.reader_typography_apply)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(custom to "warm", applied) }
    }

    @Test fun applyAndCancelStayVisibleWhenMoreOptionsAreExpandedAndScrolled() {
        compose.setContent {
            PeekturnTheme(theme = "dark") { ReaderTypographySheet(AppSettings(), { _, _ -> }, {}) }
        }
        noSliders()
        compose.onNodeWithText(text(R.string.reader_typography_more)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.reader_typography_vertical_padding)).performScrollTo()
        compose.onNodeWithText(text(R.string.reader_typography_apply)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_typography_cancel)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_typography_letter_spacing)).assertExists()
        noSliders()
    }
}
