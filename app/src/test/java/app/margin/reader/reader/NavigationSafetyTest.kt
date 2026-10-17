package app.margin.reader.reader

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationSafetyTest {
    @Test fun `decoded quote backslash and control IDs cannot reach native JavaScript navigation`() {
        val ordinary = Link(Url("chapter.xhtml#chapter%20one-%E9%98%85%E8%AF%BB")!!, MediaType.XHTML)
        assertTrue(isSafeNavigationLink(ordinary))
        val dangerous = Link(Url("chapter.xhtml#%22%29%3BAndroid.onSelectionStart%28%29%3B%2F%2F")!!, MediaType.XHTML)
        assertFalse(isSafeNavigationLink(dangerous))
        assertFalse(isSafeHtmlFragment("bad\\id"))
        assertFalse(isSafeHtmlFragment("bad\nid"))
        assertFalse(isSafeHtmlFragment("bad\u2028id"))
    }

    @Test fun `restored and search locators reject executable fragment IDs but preserve normal IDs`() {
        val ordinary = Locator(Url("chapter.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(fragments = listOf("chapter one-阅读")))
        assertTrue(isSafeReaderLocator(ordinary))
        assertFalse(isSafeReaderLocator(ordinary.copy(locations = Locator.Locations(fragments = listOf("id=bad\"id")))))
        assertFalse(isSafeReaderLocator(ordinary.copy(href = Url("chapter.xhtml#bad%5Cid")!!)))
    }

    @Test fun `served internal links and IDs cannot bypass guard while static text and ordinary anchors remain`() {
        val source = """<html><head><title>Book</title></head><body>
            <h2 id="chapter one-阅读">Unchanged chapter</h2><p id="bad&quot;id">Unchanged passage</p>
            <a id="unsafe" href="chapter.xhtml#%22%29%3BAndroid.onSelectionStart%28%29%3B%2F%2F">Unsafe link label</a>
            <a id="normal" href="chapter.xhtml#chapter%20one-%E9%98%85%E8%AF%BB">Normal link label</a>
            </body></html>"""
        val document = Jsoup.parse(securePublicationHtml(source))
        assertFalse(document.selectFirst("#unsafe")!!.hasAttr("href"))
        assertFalse(document.selectFirst("p")!!.hasAttr("id"))
        assertEquals("Unchanged passage", document.selectFirst("p")!!.text())
        assertEquals("chapter one-阅读", document.selectFirst("h2")!!.id())
        assertEquals("chapter.xhtml#chapter%20one-%E9%98%85%E8%AF%BB", document.selectFirst("#normal")!!.attr("href"))
    }
}
