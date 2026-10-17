package app.margin.reader.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.margin.reader.R
import app.margin.reader.model.Book
import kotlin.math.roundToInt

private enum class ShelfFilter(@StringRes val label: Int) {
    All(R.string.shell_filter_all),
    Reading(R.string.shell_filter_reading),
    Unread(R.string.shell_filter_unread),
    Finished(R.string.shell_filter_finished)
}

@Composable
fun LibraryScreen(books: List<Book>, onOpen: (Book) -> Unit, onImport: () -> Unit, onDelete: (Book) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(ShelfFilter.All.name) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val filter = ShelfFilter.valueOf(filterName)
    val ordered = remember(books) { books.sortedWith(compareByDescending<Book> { it.lastReadAt }.thenBy { it.title.lowercase() }) }
    val matches = remember(ordered, query, filter) {
        val term = query.trim()
        ordered.filter { book ->
            (book.title.contains(term, ignoreCase = true) || book.author.contains(term, ignoreCase = true)) &&
                when (filter) {
                    ShelfFilter.All -> true
                    ShelfFilter.Reading -> book.lastReadAt > 0L && book.progression < 1.0
                    ShelfFilter.Unread -> book.lastReadAt == 0L && book.progression < 1.0
                    ShelfFilter.Finished -> book.progression >= 1.0
                }
        }
    }
    val focus = if (query.isBlank() && filter == ShelfFilter.All) ordered.firstOrNull { it.lastReadAt > 0L } else null
    val shelf = if (focus == null) matches else matches.filter { it.id != focus.id }
    LazyColumn(
        Modifier.fillMaxSize(),
        // The list viewport stops at the navigation container; a full touch target of clearance under the
        // last row keeps it, and the row's own options button, off that boundary at the end of the scroll.
        contentPadding = PaddingValues(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MarginScreenHeader(stringResource(R.string.shell_library_title)) {
                IconButton(onClick = onImport, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.shell_add_book))
                }
            }
        }
        if (books.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.shell_empty_library), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    FilledTonalButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shell_add_book))
                    }
                }
            }
        } else {
            if (focus != null) item(key = "focus-${focus.id}") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BookCover(focus, Modifier.width(144.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.shell_continue_book, focus.title)) { onOpen(focus) })
                    Box(Modifier.fillMaxWidth()) {
                        Text(focus.title, Modifier.fillMaxWidth().padding(horizontal = 44.dp).align(Alignment.Center),
                            style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
                        BookActions(focus, onRemove = { deletingId = focus.id }, modifier = Modifier.align(Alignment.TopEnd))
                    }
                    // Author and reading status form one metadata block, tighter inside than the cover,
                    // title and action around it; the status is named ("Progress: Reading") so it cannot
                    // read as an unlabelled chip floating over the action.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(focus.author.ifBlank { stringResource(R.string.shell_unknown_author) }, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.shell_progress_label, bookProgressText(focus)), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    Button(onClick = { onOpen(focus) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.shell_continue)) }
                }
            }
            item {
                if (focus != null) HorizontalDivider(Modifier.padding(bottom = 20.dp))
                Text(stringResource(R.string.shell_shelf_heading), style = MaterialTheme.typography.titleLarge)
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.shell_search_books_hint)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.shell_search_books_description)) })
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filterName = option.name },
                            label = { Text(stringResource(option.label)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            if (shelf.isEmpty() && focus == null) item {
                Text(stringResource(R.string.shell_no_matching_books), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shelf, key = { it.id }) { book ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.shell_open_book, book.title)) { onOpen(book) }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book, Modifier.width(56.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                            Text(book.author.ifBlank { stringResource(R.string.shell_unknown_author) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(bookProgressText(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    BookActions(book, onRemove = { deletingId = book.id })
                }
                HorizontalDivider()
            }
        }
    }
    books.firstOrNull { it.id == deletingId }?.let { book ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text(stringResource(R.string.shell_remove_book_title)) },
            text = { Text(stringResource(R.string.shell_remove_book_message, book.title),
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { deletingId = null; onDelete(book) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.shell_remove)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.shell_keep_book)) } })
    }
}

@Composable
internal fun MarginScreenHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.shell_app_name), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        action()
    }
}

@Composable
private fun BookActions(book: Book, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(book.id) { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.shell_book_options, book.title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.shell_remove)) }, onClick = { expanded = false; onRemove() })
        }
    }
}

private enum class BookProgressKind { Finished, NotStarted, Reading, Percent }

private data class BookProgress(val kind: BookProgressKind, val percent: Int = 0)

private fun bookProgress(book: Book): BookProgress = when {
    book.progression >= 1.0 -> BookProgress(BookProgressKind.Finished)
    book.lastReadAt <= 0L -> BookProgress(BookProgressKind.NotStarted)
    !book.progression.isFinite() || book.progression < 0.005 -> BookProgress(BookProgressKind.Reading)
    else -> BookProgress(BookProgressKind.Percent,
        (book.progression.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.times(100) ?: 0.0).roundToInt())
}

@Composable
private fun bookProgressText(book: Book): String {
    val progress = bookProgress(book)
    return when (progress.kind) {
        BookProgressKind.Finished -> stringResource(R.string.shell_progress_finished)
        BookProgressKind.NotStarted -> stringResource(R.string.shell_progress_not_started)
        BookProgressKind.Reading -> stringResource(R.string.shell_progress_reading)
        BookProgressKind.Percent -> stringResource(R.string.shell_progress_percent, progress.percent)
    }
}
