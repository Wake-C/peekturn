package app.peekturn.reader

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.peekturn.reader.data.LastContent
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.Book
import app.peekturn.reader.reader.BuiltinBook
import app.peekturn.reader.video.VideoFailure
import app.peekturn.reader.video.VideoFailureKind
import app.peekturn.reader.video.YouTubePlaylist
import app.peekturn.reader.video.YouTubeUrls
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal sealed interface PlaylistImport {
    data class Choose(val url: String, val id: String) : PlaylistImport
    data class Loading(val id: String) : PlaylistImport
    data class Preview(val playlist: YouTubePlaylist) : PlaylistImport
    data object Saving : PlaylistImport
    data class Failed(val id: String, val messageRes: Int) : PlaylistImport
}

internal data class MainState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingBookId: Long? = null,
    val pendingVideoId: Long? = null,
    val playlistImport: PlaylistImport? = null,
    val resumePlaylistId: String? = null,
    val savedPlaylistTitle: String? = null,
    val tab: Int = 0
)

internal class MainViewModel(val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()
    private var initialized = false

    fun initialize(reopen: Boolean) {
        if (initialized) return
        initialized = true
        graph.developerLog.recordEvent("library", "startup")
        viewModelScope.launch {
            try {
                graph.store.load()
                try {
                    seedBuiltinBook()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Log.w("PeekturnStartup", "Included book import failed: ${e.javaClass.simpleName}")
                    graph.developerLog.recordFailure("library", "seed", e)
                }
                _state.update { it.copy(ready = true) }
                if (reopen && _state.value.tab == 0) {
                    when (val last = graph.store.lastContent()) {
                        is LastContent.Book -> {
                            val book = graph.store.findBook(last.id)
                            if (book != null && File(book.filePath).isFile) {
                                _state.update { if (it.tab == 0) it.copy(pendingBookId = book.id) else it }
                            } else {
                                graph.store.clearLastContent(last)
                                message("Your last book is no longer available. Choose another book from the library.")
                            }
                        }
                        is LastContent.Video -> {
                            val video = graph.store.findVideo(last.id)
                            val memberships = graph.store.playlists.value.filter { last.id in it.videoIds }
                            val playlist = memberships.find { it.id == graph.store.lastVideoPlaylistId() }
                                ?: memberships.firstOrNull()?.takeUnless { video?.isStandalone == true }
                            if (video != null && (video.isStandalone || playlist != null)) {
                                _state.update { if (it.tab == 0) it.copy(pendingVideoId = video.id, resumePlaylistId = playlist?.id, tab = 1) else it }
                            } else {
                                graph.store.clearLastContent(last)
                                _state.update { if (it.tab == 0) it.copy(tab = 1) else it }
                                message("Your last video is no longer available. Choose another video.")
                            }
                        }
                        null -> Unit
                    }
                }
                graph.store.error.value?.let {
                    graph.developerLog.recordFailure("settings", "restore")
                    message(it)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("PeekturnStartup", "Library load failed: ${e.javaClass.simpleName}")
                graph.developerLog.recordFailure("library", "startup", e)
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
        graph.developerLog.recordEvent("import", "start")
        viewModelScope.launch {
            try {
                val book = graph.importer.import(uri)
                graph.developerLog.recordEvent("import", "complete")
                _state.update { it.copy(busy = false, pendingBookId = book.id, tab = 0) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("PeekturnImport", "EPUB import failed: ${e.javaClass.simpleName}")
                graph.developerLog.recordFailure("import", "epub", e)
                _state.update { it.copy(busy = false, message = "Could not import this EPUB. Check that it is a readable, unprotected EPUB file.") }
            }
        }
    }
    private var playlistJob: Job? = null

    fun importPlaylistLink(url: String) {
        val id = YouTubeUrls.playlistId(url) ?: return
        if (_state.value.playlistImport == PlaylistImport.Saving) return
        cancelPlaylistImport()
        showVideos()
        if (YouTubeUrls.parse(url) != null) {
            _state.update { it.copy(playlistImport = PlaylistImport.Choose(url, id)) }
        } else loadPlaylist(id)
    }

    fun cancelPlaylistImport() {
        if (_state.value.playlistImport == PlaylistImport.Saving) return
        playlistJob?.cancel()
        _state.update { it.copy(playlistImport = null) }
    }

    fun loadPlaylist(id: String) {
        playlistJob?.cancel()
        _state.update { it.copy(playlistImport = PlaylistImport.Loading(id)) }
        playlistJob = viewModelScope.launch {
            try {
                val playlist = graph.videoSource.playlist(id)
                val next = if (playlist.videos.isEmpty()) PlaylistImport.Failed(id, R.string.playlist_no_videos)
                    else PlaylistImport.Preview(playlist)
                _state.update { it.copy(playlistImport = next) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = when ((e as? VideoFailure)?.kind) {
                    VideoFailureKind.Network -> R.string.video_ui_error_network
                    VideoFailureKind.Unavailable, VideoFailureKind.Restricted -> R.string.playlist_unavailable
                    else -> R.string.playlist_load_failed
                }
                _state.update { it.copy(playlistImport = PlaylistImport.Failed(id, reason)) }
            }
        }
    }

    fun savePlaylist() {
        val playlist = (_state.value.playlistImport as? PlaylistImport.Preview)?.playlist ?: return
        _state.update { it.copy(playlistImport = PlaylistImport.Saving) }
        viewModelScope.launch {
            try {
                graph.store.savePlaylist(playlist)
                _state.update { it.copy(playlistImport = null, tab = 1, savedPlaylistTitle = playlist.title) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _state.update { it.copy(playlistImport = PlaylistImport.Failed(playlist.id, R.string.playlist_save_failed)) }
            }
        }
    }

    fun open(book: Book) { _state.update { it.copy(pendingBookId = book.id) } }
    fun consumeOpen(id: Long) { _state.update { if (it.pendingBookId == id) it.copy(pendingBookId = null) else it } }
    fun consumeVideoOpen(id: Long) { _state.update { if (it.pendingVideoId == id) it.copy(pendingVideoId = null) else it } }
    fun tab(index: Int) { _state.update { it.copy(tab = index, pendingBookId = null, pendingVideoId = null) } }
    fun showVideos() { _state.update { it.copy(tab = 1, pendingBookId = null, pendingVideoId = null) } }
    fun message(value: String) { _state.update { it.copy(message = value) } }
    fun consumePlaylistSaved() { _state.update { it.copy(savedPlaylistTitle = null) } }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    fun delete(book: Book) {
        viewModelScope.launch {
            try { graph.store.deleteBook(book.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("PeekturnLibrary", "Delete failed: ${e.javaClass.simpleName}")
                graph.developerLog.recordFailure("library", "book-delete", e)
                message("Could not remove this book. Please try again.")
            }
        }
    }
    fun save(transform: (AppSettings) -> AppSettings) {
        graph.developerLog.recordEvent("settings", "save-start")
        viewModelScope.launch {
            try {
                graph.store.updateSettings(transform)
                graph.developerLog.recordEvent("settings", "save-complete")
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("PeekturnSettings", "Save failed: ${e.javaClass.simpleName}")
                graph.developerLog.recordFailure("settings", "save", e)
                message("Could not save settings. Please try again.")
            }
        }
    }
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(graph) as T
    }
}
