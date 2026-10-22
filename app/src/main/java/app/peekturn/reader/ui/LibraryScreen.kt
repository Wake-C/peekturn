package app.peekturn.reader.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.peekturn.reader.R
import app.peekturn.reader.model.Book
import kotlin.math.roundToInt

private enum class ShelfFilter(@StringRes val label: Int) {
    All(R.string.shell_filter_all),
    Reading(R.string.shell_filter_reading),
    Unread(R.string.shell_filter_unread),
    Finished(R.string.shell_filter_finished)
}

@Composable
fun LibraryScreen(
    books: List<Book>,
    onOpen: (Book) -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit,
    onDelete: (Book) -> Unit
) {
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
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 48.dp)
    ) {
        item {
            PeekturnScreenHeader(stringResource(R.string.shell_library_title)) {
                ImportSourceMenu(onImportFile, onImportUrl) { openMenu ->
                    IconButton(onClick = openMenu, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.shell_add_book))
                    }
                }
            }
        }
        if (books.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.shell_empty_library), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    ImportSourceMenu(onImportFile, onImportUrl) { openMenu ->
                        Button(onClick = openMenu, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onBackground,
                                contentColor = MaterialTheme.colorScheme.background
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp)) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.shell_add_book), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        } else {
            if (focus != null) item(key = "focus-${focus.id}") {
                // A turns the featured book into a compact continue row: a cover column and one text
                // column, flat on the page with a hairline beneath instead of an elevated card.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(focus, Modifier.width(92.dp).clickable(role = Role.Button,
                        onClickLabel = stringResource(R.string.shell_continue_book, focus.title)) { onOpen(focus) })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(focus.title, Modifier.weight(1f).padding(top = 6.dp),
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 27.sp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            BookActions(focus, onRemove = { deletingId = focus.id })
                        }
                        // Author and reading status form one metadata block; the status is named
                        // ("Progress: Reading") so it cannot read as an unlabelled chip.
                        Text(focus.author.ifBlank { stringResource(R.string.shell_unknown_author) },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.shell_progress_label, bookProgressText(focus)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(5.dp))
                        Button(onClick = { onOpen(focus) }, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onBackground,
                                contentColor = MaterialTheme.colorScheme.background
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp)) {
                            Text(stringResource(R.string.shell_continue), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item {
                if (focus != null) HorizontalDivider(Modifier.padding(bottom = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.shell_shelf_heading), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
            item {
                TextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp),
                    placeholder = { Text(stringResource(R.string.shell_search_books_hint), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true, shape = RoundedCornerShape(10.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.shell_search_books_description)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ))
            }
            item {
                // A filters are quiet text tabs with an ink underline, not filled chips; selection,
                // scroll behaviour and the filter predicate are unchanged.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ShelfFilter.entries.forEach { option ->
                        val selected = filter == option
                        Box(
                            Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                                .selectable(selected = selected, onClick = { filterName = option.name }, role = Role.RadioButton),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.fillMaxWidth().height(2.dp)
                                    .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent))
                            }
                        }
                    }
                }
            }
            if (shelf.isEmpty() && focus == null) item {
                Text(stringResource(R.string.shell_no_matching_books), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shelf, key = { it.id }) { book ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = stringResource(R.string.shell_open_book, book.title)) { onOpen(book) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book, Modifier.width(54.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(book.author.ifBlank { stringResource(R.string.shell_unknown_author) }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(bookProgressText(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    BookActions(book, onRemove = { deletingId = book.id })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    books.firstOrNull { it.id == deletingId }?.let { book ->
        AlertDialog(onDismissRequest = { deletingId = null }, shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.shell_remove_book_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.shell_remove_book_message, book.title),
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { deletingId = null; onDelete(book) }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.shell_remove)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.shell_keep_book)) } })
    }
}

/**
 * The source chooser is a menu belonging to whichever add control opened it, so it opens under that
 * control instead of covering the screen. Both choices close it before they act: From a file hands
 * straight to the system picker, From a link to the URL entry dialog.
 */
@Composable
private fun ImportSourceMenu(
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit,
    anchor: @Composable (openMenu: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 208.dp)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shell_import_from_file), maxLines = 1) },
                onClick = { expanded = false; onImportFile() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shell_import_from_url), maxLines = 1) },
                onClick = { expanded = false; onImportUrl() }
            )
        }
    }
}

@Composable
internal fun PeekturnScreenHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.shell_app_name), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        action()
    }
}

@Composable
private fun BookActions(book: Book, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(book.id) { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.shell_book_options, book.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
