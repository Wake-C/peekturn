package app.margin.reader.narration

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService

/**
 * Reads the next sentences with Readium's own content service and the same tokenizer the TTS
 * navigator uses. The locator carries the source element's `cssSelector`, so the walk begins at the
 * visible paragraph rather than the chapter head; no publication text is reconstructed or scraped.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class NarrationContent(private val publication: Publication) {

    /**
     * The bounded lookahead after [currentText] starting at [locator]. Empty when the publication
     * has no content service, the locator cannot identify its source element, or the current
     * sentence is not found; prefetch then waits for the next on-demand request instead of guessing.
     */
    suspend fun following(
        locator: Locator,
        currentText: String,
        maxAhead: Int = NARRATION_PREFETCH_SENTENCES,
        wordTarget: Int = NARRATION_PREFETCH_WORDS
    ): List<String> {
        if (locator.locations.cssSelector == null) return emptyList()
        val contentService = publication.findService(ContentService::class) ?: return emptyList()
        val target = canonicalSentence(currentText)
        if (target.isEmpty()) return emptyList()

        val iterator = contentService.content(locator).iterator()
        val collected = mutableListOf<String>()
        var targetIndex = -1
        var elements = 0
        while (targetIndex < 0 || collected.size <= targetIndex + maxAhead) {
            if (elements >= MAX_LOOKAHEAD_ELEMENTS) break
            val element = iterator.nextOrNull() ?: break
            elements++
            collected += sentencesOf(element)
            targetIndex = collected.indexOf(target)
            if (targetIndex >= 0 && collected.size > targetIndex + maxAhead) break
        }
        if (targetIndex < 0) return emptyList()
        return nextNarrationSentences(collected, targetIndex, maxAhead, wordTarget)
    }

    private fun sentencesOf(element: Content.Element): List<String> = when (element) {
        is Content.TextElement -> element.segments.flatMap { segment ->
            SentenceTextTokenizer(segment.language).tokenize(segment.text).map { range ->
                canonicalSentence(segment.text.substring(range))
            }
        }
        is Content.TextualElement -> element.text?.let { text ->
            SentenceTextTokenizer(element.language).tokenize(text).map { range ->
                canonicalSentence(text.substring(range))
            }
        }.orEmpty()
        else -> emptyList()
    }.filter { it.isNotEmpty() }

    private companion object {
        /** A malformed locator must not make prefetch walk an entire chapter. */
        const val MAX_LOOKAHEAD_ELEMENTS = 32
    }
}
