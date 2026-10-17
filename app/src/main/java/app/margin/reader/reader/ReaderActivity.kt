@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.margin.reader.reader

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import app.margin.reader.MarginApp
import app.margin.reader.data.ReadingTracker
import app.margin.reader.learning.LearningPopup
import app.margin.reader.model.*
import app.margin.reader.ui.MarginTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search

class ReaderActivity : AppCompatActivity() {
    private val graph get() = (application as MarginApp).graph
    private var book by mutableStateOf<Book?>(null)
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var tracker: ReadingTracker? = null
    private var initialLocator: Locator? = null
    private var lastLocator: Locator? = null
    private var positionLabel by mutableStateOf("")
    private var panel by mutableStateOf<String?>(null)
    private var lookup by mutableStateOf<LookupRequest?>(null)
    private var quickJob: Job? = null
    private val selectionSession = SelectionSession()
    private var selectionProbeJob: Job? = null
    private var searchJob: Job? = null
    private var searchIterator: SearchIterator? = null
    private var searchResults by mutableStateOf<List<Locator>>(emptyList())
    private var searchMessage by mutableStateOf("")
    private var searchBusy by mutableStateOf(false)
    private var searchEnded by mutableStateOf(false)
    private var latestQueued: String? = null
    private val positions = Channel<Locator>(Channel.UNLIMITED)
    private var writer: Job? = null
    private var resumed = false
    private var mode: ActionMode? = null
    private var selectionFocus: android.view.View? = null
    private var openedMarked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragment snapshots contain runtime page indexes. Recreate only from the durable locator,
        // not Android's stale navigator tree, including after process death.
        super.onCreate(null)
        setContent {
            MarginTheme {
                val settings by graph.store.settings.collectAsState()
                LaunchedEffect(settings.reading) { navigator?.submitPreferences(settings.reading.toEpub()) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { finish() }) { Text("Library") }
                            TextButton(onClick = { dismissLookup(true); panel = "contents" }) { Text("Chapters") }
                            TextButton(onClick = { dismissLookup(true); panel = "search" }) { Text("Search") }
                            TextButton(onClick = { dismissLookup(true); panel = "type" }) { Text("Aa") }
                        }
                        if (book == null) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { CircularProgressIndicator(Modifier.padding(24.dp)) }
                        } else {
                            AndroidView(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                factory = { context ->
                                    FragmentContainerView(context).apply {
                                        id = NAVIGATOR_ID
                                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                                        post { if (!isFinishing && !isDestroyed) attachNavigator() }
                                    }
                                }
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { navigate { it.goBackward() } }) { Text("Previous") }
                            Text(positionLabel, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { navigate { it.goForward() } }) { Text("Next") }
                        }
                    }
                    when (panel) {
                        "contents" -> ContentsDialog()
                        "search" -> SearchDialog()
                        "type" -> TypographyDialog(settings)
                    }
                    lookup?.let { request ->
                        key(request) {
                            LearningPopup(
                                context = request.context, anchor = request.anchor, settings = settings,
                                client = graph.explanations, dictionary = graph.dictionary, speech = graph.speech,
                                initialDictionary = request.dictionary,
                                onDismiss = { dismissLookup(true) },
                                onAdjust = { dismissLookup(false) }
                            )
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            try {
                graph.store.load()
                val loaded = graph.store.findBook(intent.getLongExtra(EXTRA_BOOK, -1))
                    ?: error("This book is no longer in the library.")
                publication = graph.engine.open(File(loaded.filePath))
                val json = savedInstanceState?.getString(STATE_LOCATOR) ?: loaded.locatorJson
                initialLocator = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                if (initialLocator?.let(::isSafeReaderLocator) == false) error("Unsafe saved fragment identifier.")
                lastLocator = initialLocator
                positionLabel = initialLocator?.locations?.totalProgression?.let { "${(it * 100).toInt()}%" } ?: ""
                tracker = ReadingTracker(graph.store, loaded.id, graph.scope).also { if (resumed) it.resume() }
                writer = graph.scope.launch {
                    for (locator in positions) {
                        try {
                            graph.store.savePosition(loaded.id, locator.toJSON().toString(), locator.locations.totalProgression ?: loaded.progression)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            runOnUiThread { Toast.makeText(this@ReaderActivity, "Reading position could not be saved.", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                book = loaded
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                graph.store.clearLastBook()
                Toast.makeText(this@ReaderActivity, "This book cannot be opened. Please import a readable EPUB again.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun attachNavigator() {
        if (navigator != null || !resumed || isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        if (findViewById<android.view.View>(NAVIGATOR_ID) == null) return
        val pub = publication ?: return
        val spineHtmlHrefs = pub.readingOrder.filter { it.mediaType?.isHtml == true }
            .map { it.url().removeQuery().removeFragment().toString() }.toSet()
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = graph.store.settings.value.reading.toEpub(),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                    val uri = android.net.Uri.parse(url.toString())
                    if (uri.scheme !in setOf("http", "https")) return
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    catch (e: android.content.ActivityNotFoundException) {
                        android.util.Log.w("MarginReader", "No external browser available")
                        Toast.makeText(this@ReaderActivity, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResourceLoadFailed(href: org.readium.r2.shared.util.Url, error: org.readium.r2.shared.util.data.ReadError) {
                    if (!shouldExitForResourceFailure(href.removeQuery().removeFragment().toString(), spineHtmlHrefs)) return
                    lifecycleScope.launch {
                        graph.store.clearLastBook()
                        Toast.makeText(this@ReaderActivity, "A chapter could not be loaded. Please import a readable EPUB again.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageLoaded() {
                    if (openedMarked) return
                    val id = book?.id ?: return
                    openedMarked = true
                    graph.scope.launch { graph.store.markOpened(id) }
                }
            },
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionActions)
        )
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        supportFragmentManager.beginTransaction().replace(NAVIGATOR_ID, fragment).commitNow()
        navigator = fragment
        // Readium 3.1 starts this StateFlow with initialLocator and emits subsequent locations
        // only after State.Ready and the requested resource is loaded (notifyCurrentLocation).
        lifecycleScope.launch {
            fragment.currentLocator.drop(1).collect { locator ->
                if (lastLocator != locator) dismissLookup(true)
                lastLocator = locator
                queuePosition(locator)
                tracker?.interact()
                positionLabel = locator.locations.totalProgression?.let { "${(it * 100).toInt()}%" } ?: ""
            }
        }
    }

    private fun queuePosition(locator: Locator) {
        val json = locator.toJSON().toString()
        if (latestQueued != json) {
            latestQueued = json
            positions.trySend(locator)
        }
    }

    private fun navigate(action: (EpubNavigatorFragment) -> Unit) {
        dismissLookup(true)
        tracker?.interact()
        navigator?.let(action)
    }

    private fun goSafely(link: Link) {
        if (!isSafeNavigationLink(link)) {
            Toast.makeText(this, "This link has an unsafe location identifier.", Toast.LENGTH_SHORT).show()
            return
        }
        navigate { it.go(link) }
    }

    private fun goSafely(locator: Locator) {
        if (!isSafeReaderLocator(locator)) {
            Toast.makeText(this, "This location has an unsafe identifier.", Toast.LENGTH_SHORT).show()
            return
        }
        navigate { it.go(locator) }
    }

    private val selectionActions = object : ActionMode.Callback {
        override fun onCreateActionMode(actionMode: ActionMode, menu: Menu): Boolean {
            mode = actionMode
            val generation = selectionSession.beginNativeMode()
            menu.clear()
            menu.add(0, EXPLAIN, 0, "Explain")
            menu.add(0, DICTIONARY, 1, "Dictionary")
            menu.add(0, android.R.id.copy, 2, "Copy")
            quickJob?.cancel()
            if (graph.store.settings.value.quickExplain) {
                quickJob = lifecycleScope.launch {
                    // Poll only for asynchronous native locator readiness. Gesture/session
                    // identity is owned by SelectionSession, never by this interval or ActionMode.
                    repeat(10) {
                        delay(80)
                        if (!selectionSession.isCurrent(generation)) return@launch
                        val selected = navigator?.currentSelection()
                        if (!selectionSession.isCurrent(generation)) return@launch
                        if (selected?.locator?.text?.highlight?.isNotBlank() == true) {
                            if (selectionSession.consumeQuick(generation)) showLookup(false, generation, selected)
                            return@launch
                        }
                    }
                }
            }
            return true
        }
        override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(actionMode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            EXPLAIN, DICTIONARY -> {
                quickJob?.cancel()
                val generation = selectionSession.generation
                lifecycleScope.launch { showLookup(item.itemId == DICTIONARY, generation) }
                true
            }
            android.R.id.copy -> {
                lifecycleScope.launch {
                    navigator?.currentSelection()?.locator?.text?.highlight?.let { selected ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Book selection", selected))
                        dismissLookup(true)
                    }
                }
                true
            }
            else -> false
        }
        override fun onDestroyActionMode(actionMode: ActionMode) { if (mode === actionMode) mode = null }
    }

    private suspend fun showLookup(
        dictionary: Boolean,
        generation: Long,
        captured: org.readium.r2.navigator.Selection? = null
    ) {
        val nav = navigator ?: return
        if (!selectionSession.isCurrent(generation)) return
        val selection = captured ?: nav.currentSelection() ?: return
        if (!selectionSession.isCurrent(generation)) return
        val locator = selection.locator
        val text = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return
        selectionSession.lookupOpened()
        selectionFocus = nav.view?.findFocus()
        val offset = IntArray(2)
        nav.view?.getLocationInWindow(offset)
        val anchor = selection.rect?.let {
            val bounds = readium31SelectionAnchor(it.left, it.top, it.right, it.bottom, offset[0], offset[1], currentPageTopPadding(nav))
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        lookup = LookupRequest(
            LookupContext(text, locator.text.before.orEmpty(), locator.text.after.orEmpty(), book?.title.orEmpty(), locator.title ?: nav.currentLocator.value.title.orEmpty()),
            anchor, dictionary
        )
        tracker?.interact()
    }

    private fun currentPageTopPadding(nav: EpubNavigatorFragment): Int {
        val visibleBounds = android.graphics.Rect()
        var largestArea = 0L
        var padding = 0
        // Readium 3.1's current page is a child Fragment. Its public root view has the
        // exact padding used by adjustedToViewport(), including page/scroll and cutout insets.
        // Adjacent preloaded pages are clipped by the pager, not selected by list order.
        for (fragment in nav.childFragmentManager.fragments) {
            val view = fragment.view ?: continue
            if (!view.getGlobalVisibleRect(visibleBounds)) continue
            val area = visibleBounds.width().toLong() * visibleBounds.height()
            if (area > largestArea) {
                largestArea = area
                padding = view.paddingTop
            }
        }
        return padding
    }

    private fun dismissLookup(clearSelection: Boolean) {
        quickJob?.cancel()
        selectionProbeJob?.cancel()
        lookup = null
        graph.speech.stop()
        if (clearSelection) {
            selectionSession.clear()
            navigator?.clearSelection()
            mode?.finish()
            mode = null
            selectionFocus = null
        } else {
            selectionSession.adjust()
            selectionFocus?.requestFocus()
        }
    }

    private fun observeSourceSelectionClear() {
        val nav = navigator ?: return
        if (lookup != null || nav.view == null) return
        val generation = selectionSession.generation
        selectionProbeJob?.cancel()
        selectionProbeJob = lifecycleScope.launch {
            val selected = nav.currentSelection()?.locator?.text?.highlight?.isNotBlank() == true
            // A toolbar's destruction alone is not a clear. Require the public native
            // selection to be absent after source input, with no replacement mode/popup.
            if (selectionSession.isCurrent(generation) && !selected && mode == null && lookup == null) {
                selectionSession.clear()
                quickJob?.cancel()
            }
        }
    }

    @Composable private fun ContentsDialog() {
        val chapters = remember(publication) { flattenContents(publication?.tableOfContents.orEmpty()) }
        AlertDialog(onDismissRequest = { panel = null }, title = { Text("Chapters") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    if (chapters.isEmpty()) item { Text("This book has no table of contents.") }
                    items(chapters) { (depth, link) ->
                        TextButton(onClick = { panel = null; goSafely(link) }, modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(5) * 12).dp)) {
                            Text(link.title ?: "Untitled chapter", Modifier.fillMaxWidth())
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { panel = null }) { Text("Close") } })
    }

    @Composable private fun SearchDialog() {
        var query by remember { mutableStateOf("") }
        DisposableEffect(Unit) { onDispose { closeSearch() } }
        AlertDialog(onDismissRequest = { panel = null }, title = { Text("Search book") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Word or phrase") }, singleLine = true)
                    TextButton(onClick = { startSearch(query) }, enabled = query.isNotBlank()) { Text("Search") }
                    if (searchBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(searchMessage, style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(searchResults) { locator ->
                            TextButton(onClick = { panel = null; goSafely(locator) }) {
                                Text(listOfNotNull(locator.title, locator.text.before, locator.text.highlight, locator.text.after).joinToString(" "))
                            }
                        }
                        if (!searchEnded && searchIterator != null) item {
                            TextButton(onClick = { nextSearchPage() }, enabled = !searchBusy) { Text("More results") }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { panel = null }) { Text("Close") } })
    }

    private fun startSearch(query: String) {
        closeSearch()
        searchResults = emptyList()
        searchMessage = ""
        searchEnded = false
        searchBusy = true
        searchJob = lifecycleScope.launch {
            try {
                searchIterator = publication?.search(query.trim()) ?: error("Search unavailable")
                readSearchPage()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchMessage = "This book could not be searched." }
            finally { searchBusy = false }
        }
    }

    private fun nextSearchPage() {
        if (searchBusy) return
        searchBusy = true
        searchJob = lifecycleScope.launch {
            try { readSearchPage() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchMessage = "Search failed. Submit the query to try again." }
            finally { searchBusy = false }
        }
    }

    private suspend fun readSearchPage() {
        val page = searchIterator?.next()?.getOrElse { throw IllegalStateException("Search failed") }
        searchEnded = page == null
        if (page != null) searchResults = searchResults + page.locators
        searchMessage = if (searchResults.isEmpty() && searchEnded) "No matches." else "${searchResults.size} matches${if (searchEnded) "" else " loaded"}"
    }

    private fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        searchIterator?.close()
        searchIterator = null
        searchBusy = false
    }

    @Composable private fun TypographyDialog(settings: AppSettings) {
        var preferences by remember { mutableStateOf(settings.reading) }
        AlertDialog(onDismissRequest = { panel = null }, title = { Text("Reading appearance") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Typeface")
                Row {
                    TextButton(onClick = { preferences = preferences.copy(fontFamily = "serif") }) { Text(if (preferences.fontFamily == "serif") "✓ Serif" else "Serif") }
                    TextButton(onClick = { preferences = preferences.copy(fontFamily = "sans-serif") }) { Text(if (preferences.fontFamily != "serif") "✓ Sans" else "Sans") }
                }
                PreferenceSlider("Font size", preferences.fontSize, 70f..180f) { preferences = preferences.copy(fontSize = it) }
                PreferenceSlider("Line height", preferences.lineHeight, 1f..2.4f) { preferences = preferences.copy(lineHeight = it) }
                PreferenceSlider("Paragraph spacing", preferences.paragraphSpacing, 0f..2f) { preferences = preferences.copy(paragraphSpacing = it) }
                PreferenceSlider("Margins", preferences.pageMargins, 0.3f..2.5f) { preferences = preferences.copy(pageMargins = it) }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Continuous scrolling", Modifier.padding(top = 12.dp))
                    Switch(checked = preferences.scroll, onCheckedChange = { preferences = preferences.copy(scroll = it) })
                }
                Row {
                    listOf("dark" to "Dark", "warm" to "Warm", "light" to "Light").forEach { (value, label) ->
                        TextButton(onClick = { preferences = preferences.copy(theme = value) }) { Text(if (preferences.theme == value) "✓ $label" else label) }
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val changed = preferences
                navigator?.submitPreferences(changed.toEpub())
                graph.scope.launch { graph.store.updateSettings { latest -> latest.copy(reading = changed) } }
                panel = null
            }) { Text("Apply") }
        }, dismissButton = { TextButton(onClick = { panel = null }) { Text("Cancel") } })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) tracker?.interact()
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) observeSourceSelectionClear()
        return handled
    }
    override fun onUserInteraction() { super.onUserInteraction(); tracker?.interact() }
    override fun onResume() {
        super.onResume()
        resumed = true
        tracker?.resume()
        window.decorView.post { attachNavigator() }
    }
    override fun onPause() {
        resumed = false
        dismissLookup(true)
        tracker?.pause()
        lastLocator?.let(::queuePosition)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        lastLocator?.let { outState.putString(STATE_LOCATOR, it.toJSON().toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        quickJob?.cancel()
        closeSearch()
        positions.close()
        val readingTracker = tracker
        graph.scope.launch { writer?.join(); readingTracker?.flush() }
        // Fragment view teardown must complete before closing its resources.
        super.onDestroy()
        publication?.close()
    }

    private class LookupRequest(val context: LookupContext, val anchor: RectF?, val dictionary: Boolean)
    companion object {
        private const val EXTRA_BOOK = "book_id"
        private const val STATE_LOCATOR = "reader_locator"
        private const val NAVIGATOR_ID = 0x4D4152
        private const val EXPLAIN = 0x4D01
        private const val DICTIONARY = 0x4D02
        fun intent(context: Context, bookId: Long): Intent = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK, bookId)
    }
}

@Composable private fun PreferenceSlider(title: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Text(title)
    Slider(value = value.toFloat().coerceIn(range), onValueChange = { onChange(it.toDouble()) }, valueRange = range)
}

internal fun flattenContents(links: List<Link>): List<Pair<Int, Link>> = buildList {
    fun append(children: List<Link>, depth: Int) {
        children.forEach { link -> add(depth to link); append(link.children, depth + 1) }
    }
    append(links, 0)
}

private fun ReaderPreferences.toEpub() = EpubPreferences(
    fontSize = fontSize, fontFamily = FontFamily(fontFamily), lineHeight = lineHeight,
    pageMargins = pageMargins, paragraphSpacing = paragraphSpacing, scroll = scroll,
    publisherStyles = false,
    backgroundColor = org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor(
        when (theme) { "light" -> "#FAF9F4"; "warm" -> "#EEE5D3"; else -> "#171E23" }
    )),
    textColor = org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor(
        if (theme == "dark") "#E9E8DF" else "#262B2C"
    )),
    theme = when (theme) { "light" -> Theme.LIGHT; "warm" -> Theme.SEPIA; else -> Theme.DARK }
)
