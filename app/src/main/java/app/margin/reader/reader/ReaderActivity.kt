@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, org.readium.r2.shared.DelicateReadiumApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Alignment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.input.Key
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
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
import app.margin.reader.learning.LearningSession
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
    private var chapterLabel by mutableStateOf("")
    private var chrome by mutableStateOf(ReaderChromeState())
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
    private val learningSession = LearningSession()
    private var locatorJob: Job? = null
    private var renderedPreferences: Pair<ReaderPreferences, String>? = null
    private val readerViewport = ReaderViewport { readerTextInsets(graph.store.settings.value.reading) }
    private var paddingGestureJob: Job? = null
    private var readerInputEpoch = 0L
    private var handledPaddingGesture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragment snapshots contain runtime page indexes. Recreate only from the durable locator,
        // not Android's stale navigator tree, including after process death.
        super.onCreate(null)
        android.webkit.WebView.setWebContentsDebuggingEnabled(app.margin.reader.BuildConfig.DEBUG)
        supportFragmentManager.registerFragmentLifecycleCallbacks(readerViewport, true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (chrome.back(lookup != null || mode != null, panel != null)) {
                    ReaderBackAction.DismissLookup -> dismissLookup(true)
                    ReaderBackAction.DismissPanel -> panel = null
                    ReaderBackAction.HideChrome -> chrome = ReaderChromeState()
                    ReaderBackAction.Exit -> finish()
                }
            }
        })
        setContent {
            val settings by graph.store.settings.collectAsState()
            MarginTheme(theme = settings.appTheme) {
                LaunchedEffect(settings.reading, settings.appTheme) {
                    refreshPreferences(settings)
                    applySystemBars()
                }
                Surface(Modifier.fillMaxSize(), color = readerPaper(settings.appTheme)) {
                    Box(Modifier.fillMaxSize()) {
                        if (book == null) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else {
                            // Only stable cutout / explicitly enabled bars reserve space. Chrome and
                            // transient system bars cannot resize this viewport or repaginate Readium.
                            val stableInsets = WindowInsets.displayCutout
                                .union(if (settings.reading.immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility)
                            AndroidView(
                                modifier = Modifier.fillMaxSize().windowInsetsPadding(stableInsets),
                                factory = { context ->
                                    ReaderHostView(context,
                                        onTap = { x, width ->
                                            handlePaddingGesture { nav ->
                                                if (isReaderCenterTap(x, width)) chrome = chrome.tap(x, width)
                                                else navigate { if (x < width / 2) nav.goBackward() else nav.goForward() }
                                            }
                                        },
                                        onSwipe = { forward ->
                                            handlePaddingGesture { nav -> navigate { if (forward) nav.goForward() else nav.goBackward() } }
                                        }
                                    ).apply {
                                        addView(FragmentContainerView(context).apply {
                                            id = NAVIGATOR_ID
                                            layoutParams = FrameLayout.LayoutParams(-1, -1)
                                            post { if (!isFinishing && !isDestroyed) attachNavigator() }
                                        })
                                    }
                                }
                            )
                        }
                        ReaderPosition(chapterLabel, positionLabel, settings.appTheme, settings.reading.immersive)
                        if (chrome.visible) ReaderChrome(
                            theme = settings.appTheme,
                            onLibrary = { finish() },
                            onContents = { dismissLookup(true); panel = "contents" },
                            onSearch = { dismissLookup(true); panel = "search" },
                            onAppearance = { dismissLookup(true); panel = "type" },
                            onHide = { chrome = ReaderChromeState() }
                        )
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
                                session = learningSession, dictionary = graph.dictionary, speech = graph.speech,
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
                positionLabel = ""
                chapterLabel = initialLocator?.title.orEmpty()
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
            initialPreferences = graph.store.settings.value.let { it.reading.toEpub(it.appTheme) },
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
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    positionLabel = chapterPositionLabel(graph.store.settings.value.reading.scroll, pageIndex + 1, totalPages, locator.locations.progression)
                    chapterLabel = locator.title.orEmpty()
                }
                override fun onPageLoaded() {
                    if (openedMarked) return
                    val id = book?.id ?: return
                    openedMarked = true
                    graph.scope.launch { graph.store.markOpened(id) }
                }
            },
            configuration = EpubNavigatorFragment.Configuration(
                // Readium 3.1 quotes generic families in --USER__fontFamily. An unquoted
                // root inline family keeps its normal descendant inheritance working.
                readiumCssRsProperties = org.readium.r2.navigator.epub.css.RsProperties(
                    overrides = mapOf("font-family" to if (graph.store.settings.value.reading.fontFamily == "sans-serif") "sans-serif" else "serif")
                ),
                selectionActionModeCallback = selectionActions,
                shouldApplyInsetsPadding = false
            ).apply { useReadiumCssFontSize = true }
        )
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        supportFragmentManager.beginTransaction().replace(NAVIGATOR_ID, fragment).commitNow()
        navigator = fragment
        renderedPreferences = graph.store.settings.value.let { it.reading to it.appTheme }
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                // Readium excludes links, handled gestures and active selection before this callback.
                val width = fragment.publicationView.width.toFloat()
                if (!isReaderCenterTap(event.point.x, width)) return false
                chrome = chrome.tap(event.point.x, width)
                tracker?.interact()
                return true
            }

            override fun onKey(event: KeyEvent): Boolean {
                if (event.type != KeyEvent.Type.Down || event.modifiers.isNotEmpty()) return false
                // Reveal controls without stealing Tab traversal or arrow/space navigation.
                if (event.key == Key.Tab || event.key == Key.ContextMenu) {
                    chrome = ReaderChromeState(visible = true)
                    return event.key == Key.ContextMenu
                }
                return false
            }
        })
        fragment.addInputListener(DirectionalNavigationAdapter(fragment))
        // Readium 3.1 starts this StateFlow with initialLocator and emits subsequent locations
        // only after State.Ready and the requested resource is loaded (notifyCurrentLocation).
        locatorJob = lifecycleScope.launch {
            fragment.currentLocator.drop(1).collect { locator ->
                if (lastLocator != locator) dismissLookup(true)
                lastLocator = locator
                queuePosition(locator)
                tracker?.interact()
                if (graph.store.settings.value.reading.scroll) {
                    positionLabel = chapterPositionLabel(true, 0, 0, locator.locations.progression)
                }
                chapterLabel = locator.title.orEmpty()
                // A requested appearance change can be deferred while the WebView has
                // entered a chapter whose debounced native locator is not ready yet.
                if (renderedPreferences != graph.store.settings.value.let { it.reading to it.appTheme }) {
                    lifecycleScope.launch { refreshPreferences(graph.store.settings.value) }
                }
            }
        }
    }

    private suspend fun refreshPreferences(settings: AppSettings) {
        val fragment = navigator ?: return
        if (renderedPreferences == (settings.reading to settings.appTheme)) return
        // Readium's firstVisibleElementLocator quotes the entire block, which can
        // span many pages. Capture the visible intra-block words without selection.
        val current = fragment.currentLocator.value
        val visible = fragment.evaluateJavascript(visibleReflowAnchorScript)?.let { result ->
            runCatching { JSONObject(result) }.getOrNull()
        }
        val anchor = reflowLocator(current, visible) ?: return
        if (navigator !== fragment || supportFragmentManager.isStateSaved || !resumed) return
        dismissLookup(true)
        locatorJob?.cancel()
        initialLocator = anchor
        lastLocator = anchor
        navigator = null
        positionLabel = ""
        supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        attachNavigator()
    }

    private fun queuePosition(locator: Locator) {
        val json = locator.toJSON().toString()
        if (latestQueued != json) {
            latestQueued = json
            positions.trySend(locator)
        }
    }

    private fun handlePaddingGesture(action: (EpubNavigatorFragment) -> Unit) {
        handledPaddingGesture = true
        val nav = navigator ?: return
        val generation = selectionSession.generation
        val input = readerInputEpoch
        // Adjust can remove the toolbar while keeping selection, so absence of
        // ActionMode is not proof that a padding gesture may turn the page.
        if (mode != null || lookup != null) {
            dismissLookup(true)
            return
        }
        selectionProbeJob?.cancel()
        paddingGestureJob?.cancel()
        paddingGestureJob = lifecycleScope.launch {
            val selected = nav.currentSelection()?.locator?.text?.highlight?.isNotBlank() == true
            if (navigator !== nav || !resumed) return@launch
            when (selectionSession.paddingAction(generation, selected || mode != null || lookup != null, input, readerInputEpoch)) {
                ReaderPaddingAction.Ignore -> Unit
                ReaderPaddingAction.ClearSelection -> dismissLookup(true)
                ReaderPaddingAction.Navigate -> action(nav)
            }
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
                PreferenceSlider("Font size", "${preferences.fontSize.toInt()}%", preferences.fontSize, 100f..180f) { preferences = preferences.copy(fontSize = it) }
                PreferenceSlider("Line height", String.format(java.util.Locale.US, "%.2f×", preferences.lineHeight), preferences.lineHeight, 1f..2.4f) { preferences = preferences.copy(lineHeight = it) }
                PreferenceSlider("Paragraph spacing", String.format(java.util.Locale.US, "%.2f em", preferences.paragraphSpacing), preferences.paragraphSpacing, 0f..2f) { preferences = preferences.copy(paragraphSpacing = it) }
                PreferenceSlider("Side margins", String.format(java.util.Locale.US, "%.2f×", preferences.pageMargins), preferences.pageMargins, 0.3f..2.5f) { preferences = preferences.copy(pageMargins = it) }
                PreferenceSlider("Top text distance", "${preferences.topPaddingDp.toInt()} dp", preferences.topPaddingDp, 16f..120f) { preferences = preferences.copy(topPaddingDp = it) }
                PreferenceSlider("Bottom text distance", "${preferences.bottomPaddingDp.toInt()} dp", preferences.bottomPaddingDp, 24f..140f) { preferences = preferences.copy(bottomPaddingDp = it) }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Continuous scrolling", Modifier.padding(top = 12.dp))
                    Switch(checked = preferences.scroll, onCheckedChange = { preferences = preferences.copy(scroll = it) })
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Immersive reading", Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = preferences.immersive, onCheckedChange = { preferences = preferences.copy(immersive = it) })
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val changed = preferences
                // The settings observer applies typography and viewport geometry together.
                graph.scope.launch { graph.store.updateSettings { latest -> latest.copy(reading = changed) } }
                panel = null
            }) { Text("Apply") }
        }, dismissButton = { TextButton(onClick = { panel = null }) { Text("Cancel") } })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handledPaddingGesture = false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            readerInputEpoch++
            paddingGestureJob?.cancel()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) tracker?.interact()
        val handled = super.dispatchTouchEvent(event)
        if (!handledPaddingGesture && (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL)) observeSourceSelectionClear()
        return handled
    }
    override fun onUserInteraction() { super.onUserInteraction(); tracker?.interact() }
    override fun onResume() {
        super.onResume()
        resumed = true
        tracker?.resume()
        window.decorView.post {
            attachNavigator()
            lifecycleScope.launch { refreshPreferences(graph.store.settings.value) }
        }
        applySystemBars()
    }
    override fun onPause() {
        resumed = false
        paddingGestureJob?.cancel()
        dismissLookup(true)
        tracker?.pause()
        lastLocator?.let(::queuePosition)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    private fun applySystemBars() {
        val bars = graph.store.settings.value.let { readerSystemBars(it.reading, it.appTheme) }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = bars.darkIcons
            isAppearanceLightNavigationBars = bars.darkIcons
            if (bars.statusVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
            if (bars.navigationVisible) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        lastLocator?.let { outState.putString(STATE_LOCATOR, it.toJSON().toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        learningSession.close()
        locatorJob?.cancel()
        paddingGestureJob?.cancel()
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(readerViewport)
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

@Composable private fun PreferenceSlider(title: String, label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    Slider(value = value.toFloat().coerceIn(range), onValueChange = { onChange(it.toDouble()) }, valueRange = range)
}

internal fun flattenContents(links: List<Link>): List<Pair<Int, Link>> = buildList {
    fun append(children: List<Link>, depth: Int) {
        children.forEach { link -> add(depth to link); append(link.children, depth + 1) }
    }
    append(links, 0)
}

