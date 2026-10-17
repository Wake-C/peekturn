package app.margin.reader.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryQueryTest {
    @Test fun whitespaceAndCaseAreNormalizedWithoutChangingWords() {
        assertEquals("in spite of", normalizeDictionaryQuery("  IN\tspite\nOF\u00a0"))
        assertEquals("reader's", normalizeDictionaryQuery(" Reader's "))
    }

    @Test fun normalizationDoesNotGuessInflectionsOrStripPunctuation() {
        assertEquals("reading", normalizeDictionaryQuery("Reading"))
        assertEquals("read!", normalizeDictionaryQuery("read!"))
        assertEquals("", normalizeDictionaryQuery("\n\u00a0\t"))
    }

    @Test fun englishCaseFoldingDoesNotDependOnDeviceLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("inside", normalizeDictionaryQuery("INSIDE"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
