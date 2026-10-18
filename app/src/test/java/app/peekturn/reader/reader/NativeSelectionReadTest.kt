package app.peekturn.reader.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Selection
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

/**
 * The four answers of a live selection read are not interchangeable. Only reads that agree confirm a
 * range, and "nothing is selected" is a different answer from "this could not be read", because the
 * reader reports a different message for each.
 */
@RunWith(RobolectricTestRunner::class)
class NativeSelectionReadTest {
    private fun selection(highlight: String) = Selection(
        Locator(
            href = Url("chapter1.xhtml")!!,
            mediaType = MediaType.XHTML,
            text = Locator.Text(highlight = highlight, before = "She was ", after = " to leave")
        ),
        null
    )

    /** One bounded read with a settled budget, so each case exercises the answer, not the timing. */
    private fun outcome(
        timeoutMs: Long = 500,
        current: () -> Boolean = { true },
        read: suspend () -> Selection?
    ) = runBlocking {
        readLiveSelection(budget = 3, timeoutMs = timeoutMs, settleMs = 1L,
            isCurrent = current, read = read, wait = { })
    }

    @Test fun `an answered read without a selection means nothing is selected`() {
        assertEquals("A readable empty state is not an unreadable one", NativeSelectionRead.Empty, outcome { null })
    }

    @Test fun `a selection without usable text is nothing selected`() {
        assertEquals(NativeSelectionRead.Empty, outcome { selection("   ") })
    }

    @Test fun `a read that runs out of time cannot be answered`() {
        val result = outcome(timeoutMs = 20) { delay(5_000); null }
        assertEquals(NativeSelectionRead.Unavailable, result)
    }

    @Test fun `a read that stopped being current cannot be answered`() {
        assertEquals(NativeSelectionRead.Unavailable, outcome(current = { false }) { selection("reluctant") })
    }

    @Test fun `agreeing reads confirm the released range`() {
        val result = outcome { selection("reluctant to leave") }
        assertTrue("Repeated identical reads are the confirmed answer", result is NativeSelectionRead.Confirmed)
        assertEquals("reluctant to leave", (result as NativeSelectionRead.Confirmed).selection.locator.text.highlight)
    }

    @Test fun `a range that keeps moving is unconfirmed when the budget ends`() {
        var reads = 0
        val result = outcome { selection("reluctant to leav" + "e".repeat(++reads)) }
        assertTrue("A moving native range is reported unconfirmed, never confirmed", result is NativeSelectionRead.Unconfirmed)
    }
}
