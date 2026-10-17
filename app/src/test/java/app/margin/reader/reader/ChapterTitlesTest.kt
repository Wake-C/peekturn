package app.margin.reader.reader

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChapterTitlesTest {
    private fun chapter(href: String, title: String? = null, children: List<Link> = emptyList()) =
        Link(Url(href)!!, MediaType.XHTML, title = title, children = children)

    private fun chapterTitleFor(locator: Locator, tableOfContents: List<Link>, readingOrder: List<Link> = emptyList()) =
        ChapterTitles.of(tableOfContents, readingOrder).titleFor(locator)

    private fun position(href: String, title: String? = null) =
        Locator(href = Url(href)!!, mediaType = MediaType.XHTML, title = title)

    @Test fun `the navigator's own chapter title wins`() {
        val title = chapterTitleFor(
            position("chapter1.xhtml", title = "The Reluctant Traveller"),
            listOf(chapter("chapter1.xhtml", "Table of contents title")),
            listOf(chapter("chapter1.xhtml", "Spine title"))
        )
        assertEquals("The Reluctant Traveller", title)
    }

    @Test fun `a position without a title uses the publication table of contents`() {
        val title = chapterTitleFor(
            position("OEBPS/chapter1.xhtml"),
            listOf(chapter("OEBPS/chapter1.xhtml", "Chapter 1"), chapter("OEBPS/chapter2.xhtml", "Chapter 2")),
            listOf(chapter("OEBPS/chapter1.xhtml"))
        )
        assertEquals("Chapter 1", title)
    }

    @Test fun `a table of contents entry with a fragment still names its chapter`() {
        val title = chapterTitleFor(
            position("OEBPS/chapter1.xhtml#top"),
            listOf(chapter("OEBPS/chapter1.xhtml#top", "Chapter 1")),
            emptyList()
        )
        assertEquals("Chapter 1", title)
    }

    @Test fun `a deeper table of contents entry wins for the same chapter`() {
        val title = chapterTitleFor(
            position("chapter1.xhtml"),
            listOf(chapter("chapter1.xhtml", "Part One", children = listOf(chapter("chapter1.xhtml", "Arrival")))),
            emptyList()
        )
        assertEquals("Arrival", title)
    }

    @Test fun `differently written hrefs resolve by resource identity`() {
        // The table of contents omits the directory the position carries, and vice versa.
        assertEquals(
            "Chapter 1",
            chapterTitleFor(position("chapter1.xhtml"), listOf(chapter("OEBPS/chapter1.xhtml", "Chapter 1")), listOf(chapter("OEBPS/chapter1.xhtml")))
        )
        assertEquals(
            "Chapter 1",
            chapterTitleFor(position("OEBPS/chapter1.xhtml"), listOf(chapter("chapter1.xhtml", "Chapter 1")), listOf(chapter("chapter1.xhtml")))
        )
    }

    @Test fun `a shared file name in another directory cannot label an unrelated chapter`() {
        val toc = listOf(chapter("part1/chapter.xhtml", "Part One"))
        val spine = listOf(chapter("part1/chapter.xhtml"), chapter("part2/chapter.xhtml"))
        // Only the resource the table of contents actually names may take its title.
        assertEquals("", chapterTitleFor(position("part2/chapter.xhtml"), toc, spine))
        assertEquals("Part One", chapterTitleFor(position("part1/chapter.xhtml"), toc, spine))
    }

    @Test fun `a shortened table of contents entry cannot claim several resources`() {
        val toc = listOf(chapter("chapter.xhtml", "Part One"))
        val spine = listOf(chapter("part1/chapter.xhtml"), chapter("part2/chapter.xhtml"))
        // The entry names a file that exists twice, so it identifies neither chapter.
        assertEquals("", chapterTitleFor(position("part1/chapter.xhtml"), toc, spine))
        assertEquals("", chapterTitleFor(position("part2/chapter.xhtml"), toc, spine))
        // With only one real resource, the same shortened entry does identify it.
        assertEquals("Part One", chapterTitleFor(position("part1/chapter.xhtml"), toc, listOf(chapter("part1/chapter.xhtml"))))
    }

    @Test fun `an unknown chapter falls back to the reading order title and never invents one`() {
        assertEquals(
            "Spine title",
            chapterTitleFor(position("chapter9.xhtml"), emptyList(), listOf(chapter("chapter9.xhtml", "Spine title")))
        )
        assertEquals("", chapterTitleFor(position("chapter9.xhtml"), emptyList(), listOf(chapter("chapter9.xhtml"))))
    }

    @Test fun `a file name shared by two chapters cannot produce a wrong chapter title`() {
        val titles = listOf(chapter("part1/chapter1.xhtml", "Part One"), chapter("part2/chapter1.xhtml", "Part Two"))
        val spine = listOf(chapter("part1/chapter1.xhtml"), chapter("part2/chapter1.xhtml"))
        // A bare file name cannot pick between two real resources, so no title is applied.
        assertEquals("", chapterTitleFor(position("chapter1.xhtml"), titles, spine))
        assertEquals("Part One", chapterTitleFor(position("part1/chapter1.xhtml"), titles, spine))
        assertEquals("Part Two", chapterTitleFor(position("part2/chapter1.xhtml"), titles, spine))
    }

    @Test fun `authored whitespace in a chapter title is normalized for the passive label`() {
        val title = chapterTitleFor(
            position("chapter1.xhtml"),
            listOf(chapter("chapter1.xhtml", "  The   Reluctant\nTraveller ")),
            emptyList()
        )
        assertEquals("The Reluctant Traveller", title)
    }

    @Test fun `an untitled table of contents entry is not treated as a chapter name`() {
        assertEquals(
            "",
            chapterTitleFor(position("chapter1.xhtml"), listOf(chapter("chapter1.xhtml", "   ")), emptyList())
        )
    }
}
