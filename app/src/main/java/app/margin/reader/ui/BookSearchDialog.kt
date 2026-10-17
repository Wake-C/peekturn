package app.margin.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.readium.r2.shared.publication.Locator

/**
 * A result row renders exactly `before + match + after` of the publication's own characters: the
 * boundary whitespace and punctuation around the match are kept, never re-invented. [chapter] is the
 * reader-resolved chapter for that match, or null when the publication has none; the dialog never
 * invents a label and never reads a locator title on its own.
 */
internal data class BookSearchRow(val chapter: String?, val before: String, val match: String, val after: String)

private const val SNIPPET_CONTEXT = 42
private const val MAX_MATCH = 200
private const val ELLIPSIS = "…"

private val WHITESPACE = Regex("\\s+")

/** Reflowed publication text: runs of whitespace become one space, the text's own edges stay. */
internal fun normalizeWhitespace(text: String): String = text.replace(WHITESPACE, " ")

/** Labels and the match itself have no meaningful edge whitespace. */
internal fun collapseWhitespace(text: String): String = normalizeWhitespace(text).trim()

/**
 * Keeps the tail of the preceding context, cut at a word boundary and marked when text was dropped.
 * The edge next to the match is untouched, so "known as " stays attached to the match.
 */
internal fun snippetBefore(text: String, context: Int = SNIPPET_CONTEXT): String {
    val normalized = normalizeWhitespace(text).trimStart()
    if (normalized.isEmpty()) return ""
    if (normalized.length <= context) return normalized
    val window = normalized.substring(normalized.length - context)
    val boundary = window.indexOf(' ')
    val kept = if (boundary >= 0) window.substring(boundary + 1) else window
    return ELLIPSIS + kept
}

/**
 * Keeps the head of the following context, cut at a word boundary and marked when text was dropped.
 * A leading "s, and" stays glued to the match, so a suffix is never pushed away by a space.
 */
internal fun snippetAfter(text: String, context: Int = SNIPPET_CONTEXT): String {
    val normalized = normalizeWhitespace(text).trimEnd()
    if (normalized.isEmpty()) return ""
    if (normalized.length <= context) return normalized
    val window = normalized.substring(0, context)
    val boundary = window.lastIndexOf(' ')
    val kept = if (boundary > 0) window.substring(0, boundary) else window
    return kept + ELLIPSIS
}

internal fun bookSearchRow(chapter: String?, before: String?, match: String?, after: String?): BookSearchRow {
    val title = collapseWhitespace(chapter.orEmpty())
    val term = collapseWhitespace(match.orEmpty()).let {
        if (it.length > MAX_MATCH) it.substring(0, MAX_MATCH).trimEnd() + ELLIPSIS else it
    }
    return BookSearchRow(
        chapter = title.takeIf { it.isNotEmpty() },
        before = snippetBefore(before.orEmpty()),
        match = term,
        after = snippetAfter(after.orEmpty())
    )
}

/** Search state is owned by the reader Activity; this dialog only renders it and reports intent. */
internal data class BookSearchState(
    val query: String = "",
    val results: List<Locator> = emptyList(),
    val message: String = "",
    val busy: Boolean = false,
    val hasMore: Boolean = false
)

/**
 * Book search as a tall sheet: the query field and the status stay put while results scroll on their
 * own, so a keyboard, a short screen or large text cannot cover the field or the matches.
 *
 * [chapterTitle] resolves each match's real chapter with the reader's own title resolution (navigator
 * locator title with the publication TOC as fallback); a search locator's own title is often absent,
 * so the dialog asks the reader instead of showing a missing or invented label.
 */
@Composable
internal fun BookSearchDialog(
    state: BookSearchState,
    chapterTitle: (Locator) -> String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onMoreResults: () -> Unit,
    onOpen: (Locator) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp).fillMaxHeight().imePadding(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Search book", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Word or phrase") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // Both submit paths require a term: the disabled magnifier and the IME action agree.
                        keyboardActions = KeyboardActions(onSearch = { if (state.query.isNotBlank()) onSearch() }),
                        trailingIcon = {
                            IconButton(onClick = onSearch, enabled = state.query.isNotBlank()) {
                                Icon(Icons.Outlined.Search, contentDescription = "Search this book")
                            }
                        }
                    )
                    Text(state.message, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp))
                    // A constant slot keeps the field and the results from moving while a page loads.
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(state.results) { index, locator ->
                        // The reader resolves the chapter (TOC-aware); a search locator's own title is often absent.
                        SearchResultRow(
                            bookSearchRow(chapterTitle(locator), locator.text.before, locator.text.highlight, locator.text.after)
                        ) { onOpen(locator) }
                        if (index < state.results.lastIndex) HorizontalDivider()
                    }
                    if (state.hasMore) item {
                        TextButton(onClick = onMoreResults, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("More results")
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
            }
        }
    }
}

/** A full-width tap target for the whole result: no pill shape and no inner text clipping. */
@Composable
private fun SearchResultRow(row: BookSearchRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Open match", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        row.chapter?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(snippet(row), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun snippet(row: BookSearchRow): AnnotatedString {
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), fontWeight = FontWeight.Medium)
    return buildAnnotatedString {
        append(row.before)
        withStyle(highlight) { append(row.match) }
        append(row.after)
    }
}
