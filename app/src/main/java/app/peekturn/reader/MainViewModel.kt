package app.peekturn.reader

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.Book
import app.peekturn.reader.reader.BuiltinBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal data class MainState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingBookId: Long? = null,
    val tab: Int = 0
)

internal class MainViewModel(val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()
    private var initialized = false

    fun initialize(reopen: Boolean) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                graph.store.load()
                try {
                    seedBuiltinBook()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Log.w("PeekturnStartup", "Included book import failed: ${e.javaClass.simpleName}")
                }
                _state.update { it.copy(ready = true) }
                if (reopen) {
                    val id = graph.store.lastBookId()
                    if (id != null) {
                        val book = graph.store.findBook(id)
                        if (book != null && File(book.filePath).isFile) open(book)
                        else {
                            graph.store.clearLastBook()
                            message("Your last book is no longer available. Choose another book from the library.")
                        }
                    }
                }
                graph.store.error.value?.let(::message)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("PeekturnStartup", "Library load failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(ready = true, message = "Could not open the local library. Restart the app to retry.") }
            }
        }
    }
    private suspend fun seedBuiltinBook() {
        if (!graph.seedBuiltinBook || graph.store.builtinBookSeeded()) return
        val alreadyPresent = graph.store.books.value.any {
            it.title == BuiltinBook.TITLE && it.author == BuiltinBook.AUTHOR
        }
        if (!alreadyPresent) graph.importer.importAsset(BuiltinBook.ASSET_PATH)
        graph.store.markBuiltinBookSeeded()
    }

    fun importBook(uri: Uri) {
        if (_state.value.busy) return
        if (uri.scheme != "content") { message("Choose a readable EPUB from the document picker."); return }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val book = graph.importer.import(uri)
                _state.update { it.copy(busy = false, pendingBookId = book.id, tab = 0) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("PeekturnImport", "EPUB import failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(busy = false, message = "Could not import this EPUB. Check that it is a readable, unprotected EPUB file.") }
            }
        }
    }
    fun open(book: Book) { _state.update { it.copy(pendingBookId = book.id) } }
    fun consumeOpen(id: Long) { _state.update { if (it.pendingBookId == id) it.copy(pendingBookId = null) else it } }
    fun tab(index: Int) { _state.update { it.copy(tab = index) } }
    fun message(value: String) { _state.update { it.copy(message = value) } }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    fun delete(book: Book) {
        viewModelScope.launch {
            try { graph.store.deleteBook(book.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("PeekturnLibrary", "Delete failed: ${e.javaClass.simpleName}"); message("Could not remove this book. Please try again.") }
        }
    }
    fun save(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try { graph.store.updateSettings(transform) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("PeekturnSettings", "Save failed: ${e.javaClass.simpleName}"); message("Could not save settings. Please try again.") }
        }
    }
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(graph) as T
    }
}
