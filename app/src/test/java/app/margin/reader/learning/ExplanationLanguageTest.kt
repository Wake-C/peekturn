package app.margin.reader.learning

import app.margin.reader.model.ExplanationExample
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationLanguageTest {
    @Test fun effectiveActivityLocaleChoosesChineseOnlyWhereChineseIsSupported() {
        assertEquals(ExplanationLanguage.Chinese, ExplanationLanguage.forLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ExplanationLanguage.Chinese, ExplanationLanguage.forLocale(Locale("zh", "TW")))
        assertEquals(ExplanationLanguage.English, ExplanationLanguage.forLocale(Locale.US))
        assertEquals(ExplanationLanguage.English, ExplanationLanguage.forLocale(Locale.UK))
        // A device language without an in-app learning locale follows the supported English fallback.
        assertEquals(ExplanationLanguage.English, ExplanationLanguage.forLocale(Locale.FRANCE))
    }

    @Test fun chinesePresentationShowsEnglishExampleThenItsTranslationAndSpeaksEnglishOnly() {
        val example = ExplanationExample(
            text = "She turned down the offer.",
            translation = "她拒绝了那个提议。"
        )
        assertEquals(
            listOf("She turned down the offer.", "她拒绝了那个提议。"),
            example.displayLines(ExplanationLanguage.Chinese)
        )
        assertEquals("She turned down the offer.", example.spokenText)
    }

    @Test fun englishLearningExperienceNeverShowsATranslationEvenIfTheProviderReturnsOne() {
        val example = ExplanationExample(
            text = "She turned down the offer.",
            translation = "她拒绝了那个提议。"
        )
        assertEquals(listOf("She turned down the offer."), example.displayLines(ExplanationLanguage.English))
        assertEquals("She turned down the offer.", example.spokenText)
    }
}
