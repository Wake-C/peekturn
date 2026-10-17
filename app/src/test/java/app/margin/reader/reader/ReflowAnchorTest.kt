package app.margin.reader.reader

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReflowAnchorTest {
    private val current = Locator(href = Url("chapter.xhtml")!!, mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = 0.65, totalProgression = 0.42),
        text = Locator.Text(highlight = "A very long paragraph starting many pages before the visible words"))

    @Test fun `reflow restores visible words inside a paragraph instead of its beginning`() {
        val visible = JSONObject("""{"resourceHref":"https://readium/publication/chapter.xhtml","cssSelector":"html > body:nth-of-type(1) > p:nth-of-type(2)","text":{"before":"earlier words ","highlight":"the words on this page","after":" and later words"}}""")
        val anchor = requireNotNull(reflowLocator(current, visible))
        assertEquals("the words on this page", anchor.text.highlight)
        assertEquals("earlier words ", anchor.text.before)
        assertEquals(" and later words", anchor.text.after)
        assertEquals("html > body:nth-of-type(1) > p:nth-of-type(2)", anchor.locations.otherLocations["cssSelector"])
        assertEquals(current.href, anchor.href)
        assertEquals(0.65, anchor.locations.progression!!, 0.0)
        assertEquals(0.42, anchor.locations.totalProgression!!, 0.0)
    }

    @Test fun `missing visible range preserves existing locator rather than guessing a block start`() {
        assertSame(current, reflowLocator(current, null))
        assertSame(current, reflowLocator(current, JSONObject("""{"text":{"highlight":""}}""")))
    }

    @Test fun `chapter transition never binds a quote from another document to the previous native href`() {
        val differentDocument = JSONObject("""{"resourceHref":"https://readium/publication/next.xhtml","cssSelector":"html > body:nth-of-type(1) > p:nth-of-type(1)","text":{"highlight":"words from the new chapter"}}""")
        assertNull(reflowLocator(current, differentDocument))
        val next = current.copy(href = Url("next.xhtml")!!)
        val anchored = requireNotNull(reflowLocator(next, differentDocument))
        assertEquals(next.href, anchored.href)
        assertEquals("words from the new chapter", anchored.text.highlight)
    }

    @Test fun `encoded and decoded spellings of the same publication resource remain equivalent`() {
        val encoded = current.copy(href = Url("EPUB/chapter%20one.xhtml")!!)
        val quote = JSONObject("""{"resourceHref":"https://readium/publication/EPUB/chapter%20one.xhtml?view=1#section","cssSelector":"html > body:nth-of-type(1) > p:nth-of-type(1)","text":{"highlight":"visible encoded chapter words"}}""")
        assertEquals("visible encoded chapter words", requireNotNull(reflowLocator(encoded, quote)).text.highlight)
    }
}
