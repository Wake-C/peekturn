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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import app.margin.reader.MarginApp
import app.margin.reader.R
import app.margin.reader.data.ReadingTracker
import app.margin.reader.learning.LearningPopup
import app.margin.reader.learning.LearningSession
import app.margin.reader.model.*
import app.margin.reader.ui.BookSearchDialog
import app.margin.reader.ui.BookSearchState
import app.margin.reader.ui.BookSearchStatus
import app.margin.reader.ui.MarginTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Selection
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
    private var chapterTitles: ChapterTitles? = null
    private var navigator: EpubNavigatorFragment? = null
    private var tracker: ReadingTracker? = null
    private var initialLocator: Locator? = null
    private var lastLocator: Locator? = null
    private var positionLabel by mutableStateOf("")
    private var chapterLabel by mutableStateOf("")
    private var chrome by mutableStateOf(ReaderChromeState())
    private var panel by mutableStateOf<String?>(null)
    private var lookup by mutableStateOf<LookupRequest?>(null)
    private var releaseJob: Job? = null
    private val selectionSession = SelectionSession()
    private var selectionProbeJob: Job? = null
    private var searchJob: Job? = null
    private var searchIterator: SearchIterator? = null
    private var searchQuery by mutableStateOf("")
    private var searchResults by mutableStateOf<List<Locator>>(emptyList())
    private var searchStatus by mutableStateOf<BookSearchStatus>(BookSearchStatus.Idle)
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
                    ReaderBackAction.DismissLookup -> dismissLookup(true, "back")
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
                            onContents = { dismissLookup(true, "chrome contents"); panel = "contents" },
                            onSearch = { dismissLookup(true, "chrome search"); panel = "search" },
                            onAppearance = { dismissLookup(true, "chrome appearance"); panel = "type" }
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
                                onClose = { dismissLookup(true, "popup close") },
                                onCancel = { dismissLookup(true, "popup outside dismissal") },
                                onAdjust = { dismissLookup(false, "popup adjust") }
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
                val opened = graph.engine.open(File(loaded.filePath))
                publication = opened
                chapterTitles = ChapterTitles.of(opened.tableOfContents, opened.readingOrder)
                val json = savedInstanceState?.getString(STATE_LOCATOR) ?: loaded.locatorJson
                initialLocator = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                if (initialLocator?.let(::isSafeReaderLocator) == false) error("Unsafe saved fragment identifier.")
                lastLocator = initialLocator
                positionLabel = ""
                initialLocator?.let(::applyChapterLabel)
                tracker = ReadingTracker(graph.store, loaded.id, graph.scope).also { if (resumed) it.resume() }
                writer = graph.scope.launch {
                    for (locator in positions) {
                        try {
                            graph.store.savePosition(loaded.id, locator.toJSON().toString(), locator.locations.totalProgression ?: loaded.progression)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            runOnUiThread { Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_position_not_saved), Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                book = loaded
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                graph.store.clearLastBook()
                Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_book_cannot_open), Toast.LENGTH_LONG).show()
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
        val settings = graph.store.settings.value
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = settings.reading.toEpub(settings.appTheme),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                    val uri = android.net.Uri.parse(url.toString())
                    if (uri.scheme !in setOf("http", "https")) return
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    catch (e: android.content.ActivityNotFoundException) {
                        android.util.Log.w("MarginReader", "No external browser available")
                        Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResourceLoadFailed(href: org.readium.r2.shared.util.Url, error: org.readium.r2.shared.util.data.ReadError) {
                    if (!shouldExitForResourceFailure(href.removeQuery().removeFragment().toString(), spineHtmlHrefs)) return
                    lifecycleScope.launch {
                        graph.store.clearLastBook()
                        Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_chapter_load_failed), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    positionLabel = chapterPositionLabel(graph.store.settings.value.reading.scroll, pageIndex + 1, totalPages, locator.locations.progression)
                    applyChapterLabel(locator)
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
                //
                // Readium CSS paints the native selection from --RS__selectionBackgroundColor, and
                // its stock #b4d8fe is the platform blue that fights every Margin theme: the theme
                // accent is injected here instead, in the same inline root style, per chapter.
                readiumCssRsProperties = org.readium.r2.navigator.epub.css.RsProperties(
                    overrides = mapOf(
                        "font-family" to if (settings.reading.fontFamily == "sans-serif") "sans-serif" else "serif",
                        "--RS__selectionBackgroundColor" to readerSelectionBackground(settings.appTheme)
                    )
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
                if (lastLocator != locator) dismissLookupForLocationChange()
                lastLocator = locator
                queuePosition(locator)
                tracker?.interact()
                if (graph.store.settings.value.reading.scroll) {
                    positionLabel = chapterPositionLabel(true, 0, 0, locator.locations.progression)
                }
                applyChapterLabel(locator)
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
        dismissLookup(true, "reflow")
        locatorJob?.cancel()
        initialLocator = anchor
        lastLocator = anchor
        navigator = null
        positionLabel = ""
        supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        attachNavigator()
    }

    /** Passive chapter label: Readium's chapter title, else the publication's own TOC/spine title. */
    private fun applyChapterLabel(locator: Locator) {
        chapterLabel = chapterTitles?.titleFor(locator).orEmpty()
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
        if (selectionSession.hasNativeSelection || lookup != null) {
            dismissLookup(true, "padding gesture with a selection")
            return
        }
        selectionProbeJob?.cancel()
        paddingGestureJob?.cancel()
        paddingGestureJob = lifecycleScope.launch {
            val selected = nav.currentSelection()?.locator?.text?.highlight?.isNotBlank() == true
            if (navigator !== nav || !resumed) return@launch
            when (selectionSession.paddingAction(generation, selected || lookup != null, input, readerInputEpoch)) {
                ReaderPaddingAction.Ignore -> Unit
                ReaderPaddingAction.ClearSelection -> dismissLookup(true, "padding gesture found a selection")
                ReaderPaddingAction.Navigate -> action(nav)
            }
        }
    }

    private fun navigate(action: (EpubNavigatorFragment) -> Unit) {
        dismissLookup(true, "navigation")
        tracker?.interact()
        navigator?.let(action)
    }

    private fun goSafely(link: Link) {
        if (!isSafeNavigationLink(link)) {
            Toast.makeText(this, getString(R.string.reader_toast_unsafe_link), Toast.LENGTH_SHORT).show()
            return
        }
        navigate { it.go(link) }
    }

    private fun goSafely(locator: Locator) {
        if (!isSafeReaderLocator(locator)) {
            Toast.makeText(this, getString(R.string.reader_toast_unsafe_location), Toast.LENGTH_SHORT).show()
            return
        }
        navigate { it.go(locator) }
    }

    private val selectionActions = object : ActionMode.Callback {
        override fun onCreateActionMode(actionMode: ActionMode, menu: Menu): Boolean {
            // Android shows this toolbar as soon as a word is selected - while the finger is still
            // down - and Chromium destroys and re-creates the floating mode when a handle drag stops
            // or the selection menu is re-shown. So this is the native drag-stop signal, never proof
            // of a release on its own: the Activity's touch stream owns "the pointer left the screen",
            // and a held pointer or an unobserved release suppresses everything here.
            mode = actionMode
            selectionSession.beginNativeMode()
            selectionTrace("native selection toolbar created held=${selectionSession.pointerHeld}")
            menu.clear()
            menu.add(0, EXPLAIN, 0, "Explain")
            menu.add(0, DICTIONARY, 1, "Dictionary")
            menu.add(0, android.R.id.copy, 2, "Copy")
            if (!selectionSession.pointerHeld) {
                // A long press can end before the toolbar exists; the finger is already up.
                scheduleReleasedSelection()
            }
            return true
        }
        override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu): Boolean {
            // Chromium calls this as part of creating the mode, so onCreateActionMode already owns
            // that signal; the menu is left exactly as created.
            return false
        }
        override fun onActionItemClicked(actionMode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            EXPLAIN, DICTIONARY -> {
                val token = selectionSession.generation
                lifecycleScope.launch { showLookup(item.itemId == DICTIONARY, token) }
                true
            }
            android.R.id.copy -> {
                val token = selectionSession.generation
                lifecycleScope.launch {
                    val nav = navigator ?: return@launch
                    // The live native range decides, never a remembered one: a handle drag can revise
                    // the selection without any Activity touch event.
                    val selection = when (val read = readNativeSelection(nav, token, requireReleasedPointer = false)) {
                        is NativeSelectionRead.Confirmed -> read.selection
                        is NativeSelectionRead.Unconfirmed -> read.selection
                        NativeSelectionRead.Empty -> return@launch reportSelectionUnreadable("No text is selected.")
                        NativeSelectionRead.Unavailable ->
                            return@launch reportSelectionUnreadable("The selection could not be read. Try again.")
                    }
                    val selected = selection.locator.text.highlight?.takeIf { it.isNotBlank() } ?: return@launch
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Book selection", selected))
                    dismissLookup(true, "copy")
                }
                true
            }
            else -> false
        }
        override fun onDestroyActionMode(actionMode: ActionMode) { if (mode === actionMode) mode = null }
    }

    /** A native read that cannot answer leaves the selection intact and says so, instead of guessing. */
    private fun reportSelectionUnreadable(message: String) {
        selectionTrace("explicit action without a readable native selection")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * The only moment a released selection may be read and, with Quick Explain enabled, explained.
     * The signals are independent on purpose: the Activity's touch stream owns "the pointer is
     * still down / left the screen", while a native selection-menu creation is Chromium's
     * drag-stop and menu re-show signal. An automatic explanation additionally needs reads that
     * agreed, so a menu that re-shows mid-gesture can never explain a moving range.
     */
    private fun scheduleReleasedSelection() {
        // A native menu request is not a release: the gesture must be one whose pointer the app saw
        // leave the screen, with no pointer currently down.
        if (selectionSession.pointerHeld || !selectionSession.hasObservedRelease || !selectionSession.hasNativeSelection) return
        val nav = navigator ?: return
        if (lookup != null) return
        val token = selectionSession.generation
        releaseJob?.cancel()
        releaseJob = lifecycleScope.launch {
            val read = readNativeSelection(nav, token)
            // Only a range the native reader repeated may be explained automatically; an unconfirmed
            // or unreadable one is recorded as no released range at all.
            val selection = (read as? NativeSelectionRead.Confirmed)?.selection
            val identity = selection?.identity()
            selectionSession.releasedSelection(token, identity)
            val quick = graph.store.settings.value.quickExplain
            val explains = selectionSession.mayAutoExplain(token, quick, identity)
            selectionTrace(
                "released read=${read.traceName} chars=${identity?.highlight?.length ?: 0} " +
                    "before=${identity?.before?.length ?: 0} after=${identity?.after?.length ?: 0} " +
                    "quickExplain=$quick autoExplain=$explains"
            )
            if (!explains) return@launch
            showLookup(dictionary = false, token = token, captured = selection)
        }
    }

    /**
     * The reader's own read of the live native selection. The automatic release path additionally
     * requires that no pointer is down, because a native range may still be moving under a held
     * finger; an explicit action is the user's own command, so it may read while a finger rests on
     * the page or on a selection handle.
     */
    private suspend fun readNativeSelection(
        nav: EpubNavigatorFragment,
        token: Long,
        requireReleasedPointer: Boolean = true
    ): NativeSelectionRead = readLiveSelection(
        budget = NATIVE_SELECTION_READS,
        timeoutMs = NATIVE_SELECTION_READ_TIMEOUT_MS,
        settleMs = NATIVE_SELECTION_SETTLE_MS,
        isCurrent = { readIsCurrent(nav, token, requireReleasedPointer) },
        read = { nav.currentSelection() },
        wait = { delay(it) }
    )

    /** A read may only be used while it still belongs to this reader, session and gesture state. */
    private fun readIsCurrent(nav: EpubNavigatorFragment, token: Long, requireReleasedPointer: Boolean): Boolean =
        selectionSession.isCurrent(token) && (!requireReleasedPointer || !selectionSession.pointerHeld) &&
            navigator === nav && resumed && !isFinishing && !isDestroyed

    private suspend fun showLookup(dictionary: Boolean, token: Long, captured: Selection? = null) {
        val nav = navigator ?: return
        if (!selectionSession.isCurrent(token)) return
        // The automatic path passes the range its confirmed release read produced. An explicit action
        // reads the live native range here - Android can drag handles in their own native window, so
        // nothing is ever explained from a remembered range, and an unconfirmed live range is still
        // the user's current selection rather than an older one.
        val selection = captured ?: when (val read = readNativeSelection(nav, token, requireReleasedPointer = false)) {
            is NativeSelectionRead.Confirmed -> read.selection
            is NativeSelectionRead.Unconfirmed -> read.selection
            NativeSelectionRead.Empty -> return reportSelectionUnreadable("No text is selected.")
            NativeSelectionRead.Unavailable ->
                return reportSelectionUnreadable("The selection could not be read. Try again.")
        }
        if (!selectionSession.isCurrent(token)) return
        val locator = selection.locator
        val text = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return
        val identity = selection.identity()
        if (identity != null) selectionSession.releasedSelection(token, identity)
        selectionSession.lookupOpened(token, identity)
        selectionFocus = nav.view?.findFocus()
        val offset = IntArray(2)
        nav.view?.getLocationInWindow(offset)
        val anchor = selection.rect?.let {
            val bounds = readium31SelectionAnchor(it.left, it.top, it.right, it.bottom, offset[0], offset[1], currentPageTopPadding(nav))
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        lookup = LookupRequest(
            LookupContext(
                text,
                contextBeforeSelection(locator.text.before.orEmpty()),
                contextAfterSelection(locator.text.after.orEmpty()),
                book?.title.orEmpty(),
                chapterTitles?.titleFor(locator).orEmpty()
            ),
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

    /**
     * Debug-gated selection trace. Selecting text is the user's own book content, so this records
     * lengths and decisions only, never the passage itself. It exists to make an interrupted
     * selection gesture diagnosable on a device; it is silent in release builds.
     */
    private fun selectionTrace(message: String) {
        if (app.margin.reader.BuildConfig.DEBUG) android.util.Log.d(SELECTION_LOG_TAG, message)
    }

    /**
     * Closes the current lookup. [reason] is diagnostic only: the trace must be able to tell a popup
     * close, an outside dismissal, a lifecycle clear and a navigation clear apart.
     */
    private fun dismissLookup(clearSelection: Boolean, reason: String) {
        releaseJob?.cancel()
        selectionProbeJob?.cancel()
        lookup = null
        graph.speech.stop()
        if (clearSelection) {
            selectionTrace("cleared native selection reason=$reason")
            selectionSession.clear()
            navigator?.clearSelection()
            mode?.finish()
            mode = null
            selectionFocus = null
        } else {
            // Adjust only closes the popup: the session, its token and the native selection stand, so
            // an unchanged highlight cannot reopen while a revised released selection may.
            selectionTrace("kept native selection reason=$reason")
            selectionFocus?.requestFocus()
        }
    }

    /**
     * A moved publication location invalidates an open lookup. While a held gesture is still
     * selecting, the native selection belongs to that gesture: Readium's debounced location can
     * change as the WebView settles under the drag, and clearing there would end multi-word
     * selection in both Quick Explain modes.
     */
    private fun dismissLookupForLocationChange() {
        if (selectionSession.mayClearForLocationChange()) {
            selectionTrace("location change dismissed the lookup")
            dismissLookup(true, "location change")
            return
        }
        selectionTrace("location change during a held gesture kept the native selection")
        releaseJob?.cancel()
        selectionProbeJob?.cancel()
        lookup = null
        graph.speech.stop()
    }

    private fun observeSourceSelectionClear() {
        val nav = navigator ?: return
        // Reading a native selection while the finger is still down says nothing: before the long
        // press composes a selection it is legitimately empty. Only a released pointer can end the
        // selection session.
        if (lookup != null || nav.view == null || !selectionSession.mayClearForSourceInput()) return
        val generation = selectionSession.generation
        selectionProbeJob?.cancel()
        selectionProbeJob = lifecycleScope.launch {
            val selected = nav.currentSelection()?.locator?.text?.highlight?.isNotBlank() == true
            // A toolbar's destruction alone is not a clear. Require the public native
            // selection to be absent after source input, with no replacement mode/popup.
            if (selectionSession.isCurrent(generation) && !selected && mode == null && lookup == null &&
                selectionSession.mayClearForSourceInput()) {
                selectionSession.clear()
                releaseJob?.cancel()
            }
        }
    }

    @Composable private fun ContentsDialog() {
        val chapters = remember(publication) { flattenContents(publication?.tableOfContents.orEmpty()) }
        AlertDialog(onDismissRequest = { panel = null }, title = { Text(stringResource(R.string.reader_contents_title)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    if (chapters.isEmpty()) item { Text(stringResource(R.string.reader_contents_empty)) }
                    items(chapters) { (depth, link) ->
                        TextButton(onClick = { panel = null; goSafely(link) }, modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(5) * 12).dp)) {
                            Text(link.title ?: stringResource(R.string.reader_chapter_untitled), Modifier.fillMaxWidth())
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { panel = null }) { Text(stringResource(R.string.reader_close)) } })
    }

    @Composable private fun SearchDialog() {
        DisposableEffect(Unit) { onDispose { closeSearch() } }
        BookSearchDialog(
            state = BookSearchState(
                query = searchQuery,
                results = searchResults,
                status = searchStatus,
                hasMore = !searchEnded && searchIterator != null
            ),
            chapterTitle = { locator -> chapterTitles?.titleFor(locator).orEmpty() },
            onQueryChange = { searchQuery = it },
            onSearch = { startSearch(searchQuery) },
            onMoreResults = { nextSearchPage() },
            onOpen = { locator -> panel = null; goSafely(locator) },
            onDismiss = { panel = null }
        )
    }

    private fun startSearch(query: String) {
        closeSearch()
        searchResults = emptyList()
        searchEnded = false
        searchBusy = true
        searchStatus = BookSearchStatus.Busy
        searchJob = lifecycleScope.launch {
            try {
                searchIterator = publication?.search(query.trim()) ?: error("Search unavailable")
                readSearchPage()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchStatus = BookSearchStatus.Failed }
            finally { searchBusy = false }
        }
    }

    private fun nextSearchPage() {
        if (searchBusy) return
        searchBusy = true
        searchJob = lifecycleScope.launch {
            try { readSearchPage() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchStatus = BookSearchStatus.Failed }
            finally { searchBusy = false }
        }
    }

    private suspend fun readSearchPage() {
        val page = searchIterator?.next()?.getOrElse { throw IllegalStateException("Search failed") }
        searchEnded = page == null
        if (page != null) searchResults = searchResults + page.locators
        searchStatus = if (searchResults.isEmpty() && searchEnded) BookSearchStatus.NoMatches
            else BookSearchStatus.Loaded(searchResults.size, searchEnded)
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
        AlertDialog(onDismissRequest = { panel = null }, title = { Text(stringResource(R.string.reader_appearance_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.reader_appearance_typeface))
                Row {
                    TextButton(onClick = { preferences = preferences.copy(fontFamily = "serif") }) { val serif = stringResource(R.string.reader_appearance_typeface_serif); Text(if (preferences.fontFamily == "serif") stringResource(R.string.reader_appearance_typeface_selected, serif) else serif) }
                    TextButton(onClick = { preferences = preferences.copy(fontFamily = "sans-serif") }) { val sans = stringResource(R.string.reader_appearance_typeface_sans); Text(if (preferences.fontFamily != "serif") stringResource(R.string.reader_appearance_typeface_selected, sans) else sans) }
                }
                PreferenceSlider(stringResource(R.string.reader_appearance_font_size), stringResource(R.string.reader_appearance_font_size_value, preferences.fontSize.toInt()), preferences.fontSize, 100f..180f) { preferences = preferences.copy(fontSize = it) }
                PreferenceSlider(stringResource(R.string.reader_appearance_line_height), stringResource(R.string.reader_appearance_line_height_value, preferences.lineHeight), preferences.lineHeight, 1f..2.4f) { preferences = preferences.copy(lineHeight = it) }
                PreferenceSlider(stringResource(R.string.reader_appearance_paragraph_spacing), stringResource(R.string.reader_appearance_paragraph_spacing_value, preferences.paragraphSpacing), preferences.paragraphSpacing, 0f..2f) { preferences = preferences.copy(paragraphSpacing = it) }
                PreferenceSlider(stringResource(R.string.reader_appearance_side_margins), stringResource(R.string.reader_appearance_side_margins_value, preferences.pageMargins), preferences.pageMargins, 0.3f..2.5f) { preferences = preferences.copy(pageMargins = it) }
                PreferenceSlider(stringResource(R.string.reader_appearance_top_distance), stringResource(R.string.reader_appearance_distance_value, preferences.topPaddingDp.toInt()), preferences.topPaddingDp, 16f..120f) { preferences = preferences.copy(topPaddingDp = it) }
                PreferenceSlider(stringResource(R.string.reader_appearance_bottom_distance), stringResource(R.string.reader_appearance_distance_value, preferences.bottomPaddingDp.toInt()), preferences.bottomPaddingDp, 24f..140f) { preferences = preferences.copy(bottomPaddingDp = it) }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.reader_appearance_continuous), Modifier.padding(top = 12.dp))
                    Switch(checked = preferences.scroll, onCheckedChange = { preferences = preferences.copy(scroll = it) })
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.reader_appearance_immersive), Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = preferences.immersive, onCheckedChange = { preferences = preferences.copy(immersive = it) })
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val changed = preferences
                // The settings observer applies typography and viewport geometry together.
                graph.scope.launch { graph.store.updateSettings { latest -> latest.copy(reading = changed) } }
                panel = null
            }) { Text(stringResource(R.string.reader_apply)) }
        }, dismissButton = { TextButton(onClick = { panel = null }) { Text(stringResource(R.string.reader_cancel)) } })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handledPaddingGesture = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                readerInputEpoch++
                paddingGestureJob?.cancel()
                // Android creates the selection toolbar, and recreates it while handles are
                // dragged, all inside one held gesture. Ownership starts here.
                selectionSession.holdStart()
                selectionTrace("pointer down pointers=${event.pointerCount}")
            }
            else -> when (gestureEnd(event.actionMasked)) {
                // A system gesture or another window can cancel the stream while the finger is still
                // down, so only a real release may explain a range.
                GestureEnd.Release -> {
                    selectionSession.holdEnd()
                    selectionTrace("pointer released action=${event.actionMasked}")
                }
                GestureEnd.Cancelled -> {
                    selectionSession.cancelGesture()
                    selectionTrace("pointer gesture cancelled action=${event.actionMasked}")
                }
                null -> Unit
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) tracker?.interact()
        // The WebView applies the final native selection during this dispatch, so the released
        // read below observes a settled selection rather than an intermediate drag state.
        val handled = super.dispatchTouchEvent(event)
        if (!handledPaddingGesture && event.actionMasked == MotionEvent.ACTION_UP) {
            observeSourceSelectionClear()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            scheduleReleasedSelection()
        }
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
        selectionSession.cancelGesture()
        dismissLookup(true, "pause")
        tracker?.pause()
        lastLocator?.let(::queuePosition)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Window focus is not a selection signal: a handle drag does not change it, and a selection
        // menu that takes focus returns it without the native range necessarily changing.
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
        releaseJob?.cancel()
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
        private const val SELECTION_LOG_TAG = "MarginSelection"

        // Readium's release-time selection read is a JavaScript round trip into the WebView, so a
        // released selection is confirmed by agreeing reads instead of trusting one asynchronous value.
        private const val NATIVE_SELECTION_READS = 3
        private const val NATIVE_SELECTION_SETTLE_MS = 40L
        private const val NATIVE_SELECTION_READ_TIMEOUT_MS = 1500L

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

