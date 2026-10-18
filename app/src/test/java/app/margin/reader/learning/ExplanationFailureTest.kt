package app.margin.reader.learning

import app.margin.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationFailureTest {
    @Test fun everyFailureCategoryMapsToItsOwnLocalizedMessage() {
        val expected = mapOf(
            ExplanationFailureCategory.Configuration to R.string.reader_lookup_explanation_failure_configuration,
            ExplanationFailureCategory.Authentication to R.string.reader_lookup_explanation_failure_authentication,
            ExplanationFailureCategory.Quota to R.string.reader_lookup_explanation_failure_quota,
            ExplanationFailureCategory.Connectivity to R.string.reader_lookup_explanation_failure_connectivity,
            ExplanationFailureCategory.MalformedResponse to R.string.reader_lookup_explanation_failure_malformed,
            ExplanationFailureCategory.IncompleteResponse to R.string.reader_lookup_explanation_failure_incomplete,
            ExplanationFailureCategory.Refusal to R.string.reader_lookup_explanation_failure_refusal,
            ExplanationFailureCategory.ProviderRejected to R.string.reader_lookup_explanation_failure_rejected,
            ExplanationFailureCategory.Unknown to R.string.reader_lookup_explanation_failure_unknown
        )
        assertEquals(ExplanationFailureCategory.entries.toSet(), expected.keys)
        expected.forEach { (category, message) ->
            assertEquals(message, explanationFailureMessage(category))
            assertTrue("Every category must keep a distinct message", message != 0)
        }
        assertEquals("Categories must not share one generic message", expected.size, expected.values.toSet().size)
    }
}
