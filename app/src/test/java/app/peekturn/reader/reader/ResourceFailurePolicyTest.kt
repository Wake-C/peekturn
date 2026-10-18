package app.peekturn.reader.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceFailurePolicyTest {
    @Test fun `missing optional book image does not close the reader or forget last book`() {
        val spine = setOf("OEBPS/chapter.xhtml")
        assertTrue(shouldExitForResourceFailure("OEBPS/chapter.xhtml", spine))
        assertFalse(shouldExitForResourceFailure("OEBPS/missing-image.jpg", spine))
    }
}
