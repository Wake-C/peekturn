package app.peekturn.reader.narration

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService
import org.readium.r2.shared.publication.services.content.DefaultContentService
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.publication.services.content.contentServiceFactory
import org.readium.r2.shared.publication.services.content.iterators.HtmlResourceContentIterator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.InMemoryResource
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner

/**
 * Lookahead runs on real Readium content blocks and the official sentence tokenizer. The tests
 * anchor on segments produced by that tokenizer, as the TTS navigator does, and assert the exact
 * sentences and paragraph boundaries the buffer will receive.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
class NarrationContentTest {

    @Test fun `cold reserve excludes current block remainder and waits for two following blocks`() = runTest {
        val publication = publication(
            "chapter.xhtml" to """
                <html><body>
                <p id="current">Current sentence. Current tail.</p>
                <p id="next">First following.</p>
                <p id="later">Second following.</p>
                </body></html>
            """
        )
        val anchor = segment(publication, "chapter.xhtml", "Current sentence.")
        val file = Files.createTempFile("reserve-identity-", ".wav").toFile()
        val source = object : NarrationAudioResolver {
            override suspend fun configuration() = "voice"
            override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
                delay(if (sentence == "Second following.") 20_000 else 100)
                return Result.success(NarrationAudio(file, false, 5_000))
            }
            override fun cancelPending() = Unit
            override fun close() = Unit
        }
        val content = NarrationContent(publication)
        val states = mutableListOf<NarrationBufferState>()
        val buffer = BufferedNarrationResolver(
            source, backgroundScope, { content.following(anchor.locator, it) },
            { 1f }, { 0L }, onState = { states += it }
        )
        try {
            val pending = async { buffer.audioFor(anchor.text) }
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse("the current block tail was counted as a following paragraph", pending.isCompleted)
            assertEquals(1, states.last().readyParagraphs)
            assertTrue(pending.await().isSuccess)
            assertEquals(2, states.last().readyParagraphs)
        } finally {
            buffer.close()
            file.delete()
        }
    }

    @Test fun `lookahead continues the current paragraph then crosses resources`() = runTest {
        val publication = publication(
            "chapter-1.xhtml" to """
                <html><body>
                <h1 id="heading">Chapter</h1>
                <p id="first">First sentence. Second sentence. Third sentence.</p>
                </body></html>
            """,
            "chapter-2.xhtml" to """
                <html><body>
                <p id="second">Fourth sentence. Fifth sentence.</p>
                </body></html>
            """
        )
        val anchor = segment(publication, "chapter-1.xhtml", "Second sentence.")

        val paragraphs = NarrationContent(publication)
            .following(anchor.locator, anchor.text)
            .toList()
            .map { it.sentences }

        assertEquals(
            listOf(
                listOf("Third sentence."),
                listOf("Fourth sentence.", "Fifth sentence.")
            ),
            paragraphs
        )
    }
    @Test fun `next paragraph starts at the following real block after an exact repeated sentence anchor`() = runTest {
        val publication = publication(
            "chapter.xhtml" to """
                <html><body>
                <p id="before">Repeated phrase. Earlier tail.</p>
                <p id="current">Repeated phrase. Current tail.</p>
                <p id="next">Inline <em>content</em> stays in this paragraph. Next sentence.</p>
                <p id="later">Later paragraph.</p>
                </body></html>
            """
        )
        val current = segment(publication, "chapter.xhtml", "Repeated phrase.", block = "current")
        val realNextStart = segment(publication, "chapter.xhtml", "Inline content stays in this paragraph.")

        val next = NarrationContent(publication)
            .nextParagraph(current.locator, current.text)

        assertEquals(realNextStart.locator, next)
        assertEquals("next", next?.locations?.cssSelector?.removePrefix("#"))
    }


    @Test fun `a repeated sentence anchors by its locator context not its first occurrence`() = runTest {
        val publication = publication(
            "chapter.xhtml" to """
                <html><body>
                <p id="first">Same line. First tail.</p>
                <p id="second">Same line. Second tail.</p>
                </body></html>
            """
        )
        val anchor = segment(publication, "chapter.xhtml", "Same line.", block = "second")

        val paragraphs = NarrationContent(publication)
            .following(anchor.locator, anchor.text)
            .toList()
            .map { it.sentences }

        assertEquals(listOf(listOf("Second tail.")), paragraphs)
    }
    @Test fun `one sentence behind locator bridges only to the immediate next block sentence`() = runTest {
        val publication = publication(
            "chapter.xhtml" to """
                <html><body>
                <p id="earlier">Anchor sentence. Earlier tail.</p>
                <p id="current">Anchor sentence.</p>
                <p id="next">Current sentence. Current tail.</p>
                <p id="later">Farther requested sentence. Later tail.</p>
                </body></html>
            """
        )
        val oneBehind = segment(publication, "chapter.xhtml", "Anchor sentence.", block = "current")
        val content = NarrationContent(publication)

        val afterImmediateSentence = content
            .following(oneBehind.locator, "Current sentence.")
            .toList()
            .map { it.sentences }
        val afterFartherSentence = content
            .following(oneBehind.locator, "Farther requested sentence.")
            .toList()

        assertEquals(
            listOf(
                listOf("Current tail."),
                listOf("Farther requested sentence.", "Later tail.")
            ),
            afterImmediateSentence
        )
        assertTrue("a farther matching sentence is not a one-sentence locator bridge", afterFartherSentence.isEmpty())
    }


    @Test fun `an ambiguous locator yields no lookahead instead of the wrong text`() = runTest {
        val publication = publication(
            "chapter.xhtml" to """
                <html><body>
                <p id="dup">Same line. Same line. Tail.</p>
                </body></html>
            """
        )
        val repeated = segment(publication, "chapter.xhtml", "Same line.", block = "dup")
        // Drop the before snippet so the two identical sentences cannot be told apart.
        val ambiguous = repeated.locator.copy(
            text = repeated.locator.text.copy(before = null, after = null)
        )

        val paragraphs = NarrationContent(publication)
            .following(ambiguous, "Same line.")
            .toList()

        assertTrue("ambiguous position must not return a guessed paragraph", paragraphs.isEmpty())
    }

    @Test fun `a locator without a source selector yields no lookahead`() = runTest {
        val publication = publication(
            "chapter.xhtml" to "<html><body><p id=\"only\">One. Two.</p></body></html>"
        )
        val anchor = segment(publication, "chapter.xhtml", "One.")
        val withoutSelector = anchor.locator.copy(locations = Locator.Locations())

        val paragraphs = NarrationContent(publication)
            .following(withoutSelector, anchor.text)
            .toList()

        assertTrue(paragraphs.isEmpty())
    }

    @Test fun `a missing sentence yields no lookahead`() = runTest {
        val publication = publication(
            "chapter.xhtml" to "<html><body><p id=\"only\">One. Two.</p></body></html>"
        )
        val anchor = segment(publication, "chapter.xhtml", "One.")

        val paragraphs = NarrationContent(publication)
            .following(anchor.locator, "Not in this book.")
            .toList()

        assertTrue(paragraphs.isEmpty())
    }

    private suspend fun segment(
        publication: Publication,
        href: String,
        text: String,
        block: String? = null
    ): Content.TextElement.Segment {
        val service = checkNotNull(publication.findService(ContentService::class))
        val start = Locator(
            href = checkNotNull(Url(href)),
            mediaType = MediaType.XHTML
        )
        val iterator = service.content(start).iterator()
        while (true) {
            val element = iterator.nextOrNull() ?: error("Missing segment: $text")
            val tokenizer = TextContentTokenizer(
                language = element.language,
                textTokenizerFactory = { language -> SentenceTextTokenizer(language) }
            )
            val segments = (tokenizer.tokenize(element).firstOrNull() as? Content.TextElement)
                ?.segments
                .orEmpty()
            segments.firstOrNull { segment ->
                canonicalSentence(segment.text) == text &&
                    (block == null || segment.locator.locations.cssSelector?.contains(block) == true)
            }?.let { return it }
        }
    }

    private fun publication(vararg chapters: Pair<String, String>): Publication {
        val manifest = Manifest(
            metadata = Metadata(
                localizedTitle = LocalizedString("Narration", "en"),
                belongsToCollections = emptyList()
            ),
            readingOrder = chapters.map { (href, _) ->
                Link(href = checkNotNull(Url(href)), mediaType = MediaType.XHTML)
            }
        )
        val resources = chapters.associate { (href, html) ->
            checkNotNull(Url(href)) to InMemoryResource(html.toByteArray())
        }
        val container = object : Container<Resource> {
            override val entries: Set<Url> get() = resources.keys
            override fun get(url: Url): Resource? = resources[url]
            override fun close() = Unit
        }
        val services = Publication.ServicesBuilder().apply {
            contentServiceFactory = DefaultContentService.createFactory(
                listOf(HtmlResourceContentIterator.Factory())
            )
        }
        return Publication(manifest, container, services)
    }
}
