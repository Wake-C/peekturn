package app.peekturn.reader.narration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.util.Url

/** One completed sentence from an independent publication, with its exact Readium locator. */
internal data class NarrationBookSentence(val text: String, val locator: Locator)

/** A bounded, source-free reason the whole-book sentence iterator could not continue. */
internal class NarrationBookContentError(val code: String) : Exception(code)

/**
 * Uses Readium's content service and the same sentence tokenizer as the TTS navigator. Anchors
 * include the locator's selector, quote, and preceding context, so repeated text is never resolved
 * by a chapter-head text guess.
 *
 * [following] starts with the remainder of the current paragraph and emits one [NarrationParagraph]
 * per later content block. [nextParagraph] returns the first real sentence locator in the next
 * nonempty block. Both cross reading-order resources and report bounded, source-free diagnostics
 * when an anchor or content read cannot be resolved. A locator one sentence behind is accepted only
 * when its exact quote is immediately followed by the requested current sentence.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class NarrationContent(
    private val publication: Publication,
    private val onDiagnostic: (String) -> Unit = {}
) {

    /**
     * Every sentence of the whole publication in reading order, tokenized exactly as the TTS
     * navigator tokenizes it, with each sentence's own locator. The caller owns the publication
     * independently of any reader or playback session. A chapter that cannot be read fails the
     * flow with a fixed code instead of silently producing a partial book.
     */
    fun allSentences(): Flow<NarrationBookSentence> = flow {
        val contentService = publication.findService(ContentService::class)
            ?: throw NarrationBookContentError("content_unavailable")
        // A single PublicationContentIterator walks the whole reading order from its start and
        // crosses resource boundaries itself. Iterating one content() per reading-order link would
        // traverse every later chapter again and duplicate the book.
        val first = publication.readingOrder.firstOrNull()
            ?: throw NarrationBookContentError("content_unavailable")
        verifyReadingOrderReadable()
        val locator = (try {
            publication.locatorFromLink(first)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NarrationBookContentError("content_read_failed")
        }) ?: throw NarrationBookContentError("content_read_failed")
        val iterator = try {
            contentService.content(locator).iterator()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NarrationBookContentError("content_read_failed")
        }
        while (true) {
            val element = nextElement(iterator) ?: break
            for (sentence in tokenizedSentences(element)) {
                emit(NarrationBookSentence(sentence.text, sentence.locator))
            }
        }
    }.flowOn(Dispatchers.Default).buffer(0)

    private suspend fun nextElement(iterator: Content.Iterator): Content.Element? = try {
        iterator.nextOrNull()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw NarrationBookContentError("content_read_failed")
    }

    private fun tokenizedSentences(element: Content.Element): List<Sentence> = try {
        sentencesOf(element)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw NarrationBookContentError("content_read_failed")
    }

    /**
     * Readium's HTML content iterator logs an unreadable resource and yields no elements for it, so
     * the content iterator alone would silently produce a partial book. Read every textual
     * reading-order resource once before iterating; a failure fails the whole preparation.
     */
    private suspend fun verifyReadingOrderReadable() {
        for (link in publication.readingOrder) {
            if (link.mediaType?.isHtml == false) continue
            val resource = publication.get(link)
                ?: throw NarrationBookContentError("content_unavailable")
            val length = try {
                resource.length().getOrNull()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: throw NarrationBookContentError("content_read_failed")
            if (length <= 0L) continue
            try {
                resource.read(0L..(length - 1)).getOrNull()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: throw NarrationBookContentError("content_read_failed")
        }
    }

    suspend fun nextParagraph(locator: Locator, currentText: String): Locator? =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val contentService = publication.findService(ContentService::class) ?: run {
                onDiagnostic("content_unavailable")
                return@withContext null
            }
            val anchor = anchorOf(locator, currentText) ?: run {
                onDiagnostic("anchor_missing")
                return@withContext null
            }
            val quotedAnchor = anchorOf(locator, locator.text.highlight.orEmpty())
            val oneSentenceBehind = quotedAnchor != null && quotedAnchor.text != anchor.text
            val iterator = try {
                contentService.content(locator).iterator()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDiagnostic("content_read_failed")
                return@withContext null
            }

            var anchored = false
            var pendingBridge = false
            var scanned = 0
            while (!anchored && scanned < MAX_ANCHOR_BLOCKS) {
                val element = try {
                    iterator.nextOrNull()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onDiagnostic("content_read_failed")
                    return@withContext null
                } ?: break
                scanned++
                val sentences = try {
                    sentencesOf(element)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onDiagnostic("content_read_failed")
                    return@withContext null
                }
                if (sentences.isEmpty()) continue

                if (oneSentenceBehind) {
                    if (pendingBridge) {
                        if (sentences.first().text != anchor.text) {
                            onDiagnostic("anchor_missing")
                            return@withContext null
                        }
                        anchored = true
                    } else {
                        val oldIndex = quotedAnchor?.let { anchorIndex(sentences, it) } ?: -1
                        if (oldIndex < 0) continue
                        val immediate = oldIndex + 1
                        if (immediate < sentences.size) {
                            if (sentences[immediate].text != anchor.text) {
                                onDiagnostic("anchor_missing")
                                return@withContext null
                            }
                            anchored = true
                        } else {
                            pendingBridge = true
                        }
                    }
                } else if (anchorIndex(sentences, anchor) >= 0) {
                    anchored = true
                }
            }
            if (!anchored) {
                onDiagnostic("anchor_missing")
                return@withContext null
            }

            while (scanned < MAX_ANCHOR_BLOCKS) {
                val element = try {
                    iterator.nextOrNull()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onDiagnostic("content_read_failed")
                    return@withContext null
                } ?: return@withContext null
                scanned++
                val next = try {
                    sentencesOf(element).firstOrNull()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onDiagnostic("content_read_failed")
                    return@withContext null
                } ?: continue
                return@withContext next.locator
            }
            null
        }

    fun following(locator: Locator, currentText: String): Flow<NarrationParagraph> = flow {
        val contentService = publication.findService(ContentService::class) ?: run {
            onDiagnostic("content_unavailable")
            return@flow
        }
        val anchor = anchorOf(locator, currentText) ?: run {
            onDiagnostic("anchor_missing")
            return@flow
        }
        val quotedAnchor = anchorOf(locator, locator.text.highlight.orEmpty())
        val oneSentenceBehind = quotedAnchor != null && quotedAnchor.text != anchor.text
        val iterator = try {
            contentService.content(locator).iterator()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onDiagnostic("content_read_failed")
            return@flow
        }

        // A correct paragraph selector starts the iterator at the anchor's block. A heading, an
        var anchored = false
        var pendingBridge = false
        var scanned = 0
        while (!anchored && scanned < MAX_ANCHOR_BLOCKS) {
            val element = try {
                iterator.nextOrNull()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDiagnostic("content_read_failed")
                return@flow
            } ?: break
            scanned++
            val sentences = try {
                sentencesOf(element)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDiagnostic("content_read_failed")
                return@flow
            }
            if (sentences.isEmpty()) continue

            val index = if (oneSentenceBehind) {
                when {
                    pendingBridge -> {
                        if (sentences.first().text != anchor.text) {
                            onDiagnostic("anchor_missing")
                            return@flow
                        }
                        0
                    }
                    else -> {
                        val oldIndex = quotedAnchor?.let { anchorIndex(sentences, it) } ?: -1
                        if (oldIndex < 0) continue
                        val immediate = oldIndex + 1
                        if (immediate < sentences.size) {
                            if (sentences[immediate].text != anchor.text) {
                                onDiagnostic("anchor_missing")
                                return@flow
                            }
                            immediate
                        } else {
                            pendingBridge = true
                            continue
                        }
                    }
                }
            } else {
                anchorIndex(sentences, anchor).takeIf { it >= 0 } ?: continue
            }
            anchored = true
            val remainder = sentences.drop(index + 1).map { it.text }
            if (remainder.isNotEmpty()) {
                emit(NarrationParagraph(remainder, continuesCurrentParagraph = true))
            }
        }
        if (!anchored) {
            onDiagnostic("anchor_missing")
            return@flow
        }

        // The same iterator advances through the reading order across resource boundaries. The
        // buffer decides how far ahead to consume; this source never flattens or reconstructs text.
        while (true) {
            val element = try {
                iterator.nextOrNull()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDiagnostic("content_read_failed")
                return@flow
            } ?: break
            val sentences = try {
                sentencesOf(element).map { it.text }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDiagnostic("content_read_failed")
                return@flow
            }
            if (sentences.isNotEmpty()) emit(NarrationParagraph(sentences))
        }
    }.flowOn(Dispatchers.Default).buffer(0)

    private fun anchorOf(locator: Locator, currentText: String): Anchor? {
        // Without a source selector the iterator would start at the chapter head, which is a guess.
        val selector = locator.locations.cssSelector ?: return null
        val highlight = canonicalSentence(locator.text.highlight.orEmpty())
        val text = canonicalSentence(currentText).ifEmpty { highlight }
        if (text.isEmpty()) return null
        return Anchor(
            text = text,
            before = canonicalSentence(locator.text.before.orEmpty()),
            href = locator.href,
            selector = selector
        )
    }

    /**
     * The tokenizer mirrors Readium's TTS unit: one utterance per sentence segment, so lookahead
     * requests exactly the strings the navigator will speak next.
     */
    private fun sentencesOf(element: Content.Element): List<Sentence> {
        val tokenizer = TextContentTokenizer(
            language = element.language,
            textTokenizerFactory = { language -> SentenceTextTokenizer(language) }
        )
        return tokenizer.tokenize(element).flatMap { tokenized ->
            when (tokenized) {
                is Content.TextElement -> tokenized.segments.mapNotNull { segment ->
                    val text = canonicalSentence(segment.text)
                    if (text.isEmpty()) null else Sentence(text, segment.locator)
                }
                is Content.TextualElement -> canonicalSentence(tokenized.text.orEmpty())
                    .takeIf { it.isNotEmpty() }
                    ?.let { listOf(Sentence(it, tokenized.locator)) }
                    .orEmpty()
                else -> emptyList()
            }
        }
    }

    /**
     * The occurrence of the anchor in [sentences]. A text that appears more than once is accepted
     * only when exactly one occurrence carries the same `before` snippet. Without a unique `before`,
     * the block itself must be the one the locator pointed at; anything else is ambiguous and the
     * caller must not guess.
     */
    private fun anchorIndex(sentences: List<Sentence>, anchor: Anchor): Int {
        val candidates = sentences.indices.filter { sentences[it].text == anchor.text }
        if (candidates.isEmpty()) return -1
        if (candidates.size == 1) {
            val sentence = sentences[candidates.single()]
            if (sentence.inBlockOf(anchor)) return candidates.single()
            if (anchor.before.isEmpty()) return -1
            return if (beforeOf(sentence).endsWith(anchor.before)) candidates.single() else -1
        }
        if (anchor.before.isEmpty()) return -1
        return candidates
            .filter { beforeOf(sentences[it]).endsWith(anchor.before) }
            .singleOrNull() ?: -1
    }

    private fun Sentence.inBlockOf(anchor: Anchor): Boolean =
        locator.href == anchor.href && locator.locations.cssSelector == anchor.selector

    private fun beforeOf(sentence: Sentence): String =
        canonicalSentence(sentence.locator.text.before.orEmpty())

    private data class Sentence(val text: String, val locator: Locator)

    private data class Anchor(
        val text: String,
        val before: String,
        val href: Url,
        val selector: String
    )

    private companion object {
        /** A malformed locator must not make lookahead walk an entire chapter. */
        const val MAX_ANCHOR_BLOCKS = 32
    }
}
