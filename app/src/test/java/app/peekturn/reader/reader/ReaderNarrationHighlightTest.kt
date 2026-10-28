package app.peekturn.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderNarrationHighlightTest {
    private val locator = Locator(Url("chapter.xhtml")!!, MediaType.XHTML,
        text = Locator.Text(highlight = "The spoken sentence."))

    @Test fun `narration keeps amber but renders at thirty five percent opacity`() {
        val template = requireNotNull(readerNarrationDecorationTemplates()[Decoration.Style.Highlight::class])
        val decoration = Decoration("narration", locator, Decoration.Style.Highlight(NARRATION_HIGHLIGHT_TINT))
        val html = template.element(decoration)
        assertTrue(html, html.contains("background-color: rgba(255, 193, 7, 0.35)"))
        assertEquals(HtmlDecorationTemplate.Layout.BOXES, template.layout)
        assertEquals(HtmlDecorationTemplate.Width.WRAP, template.width)
    }

    @Test fun `underline style retains the default opacity`() {
        val template = readerNarrationDecorationTemplates()[Decoration.Style.Underline::class]
        assertNotNull(template)
        val decoration = Decoration("underline", locator,
            Decoration.Style.Underline(NARRATION_HIGHLIGHT_TINT, isActive = true))
        val html = requireNotNull(template).element(decoration)
        assertTrue(html, html.contains("background-color: rgba(255, 193, 7, 0.3)"))
        assertEquals(HtmlDecorationTemplates.defaultTemplates()[Decoration.Style.Underline::class]?.layout, template.layout)
    }
}
