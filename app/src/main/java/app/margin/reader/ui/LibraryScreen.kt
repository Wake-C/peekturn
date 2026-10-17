package app.margin.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.margin.reader.model.Book
import kotlin.math.roundToInt

private enum class ShelfFilter(val label: String) { All("All"), Reading("Reading"), Unread("Unread"), Finished("Finished") }

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
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import EPUB") }
            }
        }
        if (books.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Your first page starts here.", style = MaterialTheme.typography.headlineMedium)
                    Text("Import an EPUB to begin. Your books and reading position stay on this device.", color = FocusColors.Muted)
                    Button(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import your first book") }
                }
            }
        } else {
            if (focus != null) item(key = "focus-${focus.id}") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CONTINUE READING", style = MaterialTheme.typography.labelSmall, color = FocusColors.Accent)
                    BookCover(focus, Modifier.width(156.dp).clickable(role = Role.Button, onClickLabel = "Continue ${focus.title}") { onOpen(focus) })
                    Text(focus.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(focus.author.ifBlank { "Unknown author" }, color = FocusColors.Muted, textAlign = TextAlign.Center)
                    Text(bookProgress(focus), style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
                    Button(onClick = { onOpen(focus) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Return to your book") }
                    TextButton(onClick = { deletingId = focus.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove book") }
                }
            }
            item {
                HorizontalDivider()
                Text(if (focus != null) "The rest of your shelf" else "Your books", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Find a book or author") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) })
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filterName = option.name },
                            label = { Text(option.label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            if (shelf.isEmpty()) item {
                Text(if (focus != null && matches.size == 1) "Your other books will appear here." else "No books match. Try another title or filter.",
                    Modifier.padding(vertical = 20.dp), color = FocusColors.Muted)
            }
            items(shelf, key = { it.id }) { book ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open ${book.title}") { onOpen(book) }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book, Modifier.width(64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                            Text(book.author.ifBlank { "Unknown author" }, style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
                            Text(bookProgress(book), style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
                        }
                    }
                    IconButton(onClick = { deletingId = book.id }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove ${book.title}")
                    }
                }
                HorizontalDivider()
            }
        }
    }
    books.firstOrNull { it.id == deletingId }?.let { book ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Remove book?") },
            text = { Text("Remove “${book.title}”, its reading position, and its reading-time records from Margin? Your original EPUB is not changed.",
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { deletingId = null; onDelete(book) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deletingId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep book") } })
    }
}

private fun bookProgress(book: Book): String = when {
    book.progression >= 1.0 -> "Finished"
    book.lastReadAt <= 0L -> "Not started"
    else -> "${(book.progression.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.times(100) ?: 0.0).roundToInt()}% read"
}
