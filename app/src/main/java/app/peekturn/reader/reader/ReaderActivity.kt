@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, org.readium.r2.shared.DelicateReadiumApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.peekturn.reader.reader

import android.content.ComponentName
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import app.peekturn.reader.data.ReadingTracker
import app.peekturn.reader.learning.LearningPopup
import app.peekturn.reader.learning.LearningSession
import app.peekturn.reader.narration.canonicalSentence
import app.peekturn.reader.narration.sentenceContaining
import app.peekturn.reader.model.*
import app.peekturn.reader.narration.NarrationPlayback
import app.peekturn.reader.narration.NarrationService
import app.peekturn.reader.narration.NarrationState
import app.peekturn.reader.ui.BookSearchDialog
import app.peekturn.reader.ui.BookSearchState
import app.peekturn.reader.ui.BookSearchStatus
import app.peekturn.reader.ui.PeekturnTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.readium.r2.navigator.Decoration
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
    private val graph get() = (application as PeekturnApp).graph
    private var book by mutableStateOf<Book?>(null)
    private var publication: Publication? = null
    private var chapterTitles: ChapterTitles? = null
    private var navigator: EpubNavigatorFragment? = null
    private var tracker: ReadingTracker? = null
    private var initialLocator: Locator? = null
    private var lastLocator: Locator? = null
    private var positionLabel by mutableStateOf("")
    /**
     * Non-whitespace characters per resource and for the whole book. Readium's positions have a floor
     * of one per resource, so a ten-character title page counts as a whole chunk and inflates a
     * position-based estimate; actual text length does not.
     */
    private var charsByResource by mutableStateOf<Map<String, Int>>(emptyMap())
    private var totalBookChars by mutableStateOf(0)
    /** Measured characters per page per layout signature, so the counter survives a restart. */
    private var densities by mutableStateOf<Map<String, Double>>(emptyMap())
    /** Latest pagination, so a label can be rebuilt once positions finish loading. */
    private var lastPagination: Triple<Int, Int, Locator>? = null
    private var chapterLabel by mutableStateOf("")
    private var chrome by mutableStateOf(ReaderChromeState())
    /** True only while a lookup's own pause owns the narration session. */
    private var lookupPausedNarration = false
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
    private val learningSession by lazy {
        LearningSession(cache = graph.explanationCache) { category, cause ->
            graph.developerLog.recordFailure("learning", category, cause)
        }
    }
    private var locatorJob: Job? = null
    private var renderedPreferences: Pair<ReaderPreferences, String>? = null
    private val readerViewport = ReaderViewport { readerTextInsets(graph.store.settings.value.reading) }
    private var paddingGestureJob: Job? = null
    private var readerInputEpoch = 0L
    private var handledPaddingGesture = false
    private var narrationBinder: NarrationService.Binder? = null
    private var narrationPlayback: NarrationPlayback? = null
    private var narrationUi by mutableStateOf(NarrationState())
    private var narrationCacheBytes by mutableStateOf(0L)
    private var narrationObserveJob: Job? = null
    private var narrationFailureJob: Job? = null
    private var narrationSyncJob: Job? = null
    private var narrationBound = false
    private var pendingNarration: PendingNarration? = null

    private val narrationConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NarrationService.Binder ?: return
            narrationBinder = binder
            pendingNarration?.let {
                pendingNarration = null
                binder.start(it.bookId, it.locatorJson, it.speed, it.startSentence)
            }
            observeNarration(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearNarrationBinding()
        }

        override fun onNullBinding(name: ComponentName?) {
            clearNarrationBinding()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val action = narrationVolumeKeyAction(
            enabled = graph.store.settings.value.volumeKeysNarration,
            narrationActive = narrationPlayback?.state?.value?.active == true,
            keyCode = event.keyCode,
            eventAction = event.action,
            repeatCount = event.repeatCount
        ) ?: return super.dispatchKeyEvent(event)
        when (action) {
            NarrationVolumeKeyAction.PREVIOUS -> narrationPlayback?.previous()
            NarrationVolumeKeyAction.NEXT -> narrationPlayback?.next()
            NarrationVolumeKeyAction.CONSUME -> Unit
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragment snapshots contain runtime page indexes. Recreate only from the durable locator,
        // not Android's stale navigator tree, including after process death.
        super.onCreate(null)
        android.webkit.WebView.setWebContentsDebuggingEnabled(app.peekturn.reader.BuildConfig.DEBUG)
        supportFragmentManager.registerFragmentLifecycleCallbacks(readerViewport, true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (narrationUi.active && panel == null && lookup == null && mode == null) {
                    stopNarration(showChrome = true)
                    return
                }
                readerPanelBackTarget(panel)?.let {
                    panel = it
                    return
                }
                when (chrome.back(lookup != null || mode != null, panel != null)) {
                    ReaderBackAction.DismissLookup -> dismissLookup(true, "back")
                    ReaderBackAction.DismissPanel -> panel = null
                    ReaderBackAction.HideChrome -> requestChromeVisibility(false)
                    ReaderBackAction.Exit -> finish()
                }
            }
        })
        setContent {
            val settings by graph.store.settings.collectAsState()
            PeekturnTheme(theme = settings.appTheme) {
                LaunchedEffect(settings.reading, settings.appTheme) {
                    refreshPreferences(settings)
                    applySystemBars()
                }
                Surface(Modifier.fillMaxSize(), color = readerPaper(settings.appTheme)) {
                    Box(Modifier.fillMaxSize()) {
                        if (book == null) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else {
                            val stableInsets = WindowInsets.displayCutout
                                .union(if (settings.reading.immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBarsIgnoringVisibility)
                            // The publication viewport is stable in all four states: system
                            // insets only, never the chrome or narration surfaces.
                            Box(Modifier.fillMaxSize()) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize().windowInsetsPadding(stableInsets),
                                    factory = { context ->
                                        ReaderHostView(context,
                                            onTap = { x, width ->
                                                when (readerTapAction(x, width)) {
                                                    ReaderTapAction.ToggleChrome -> handlePaddingGesture { requestChromeToggle() }
                                                    ReaderTapAction.PreviousPage -> handlePaddingGesture { nav -> navigate { nav.goBackward() } }
                                                    ReaderTapAction.NextPage -> handlePaddingGesture { nav -> navigate { nav.goForward() } }
                                                    ReaderTapAction.Ignore -> Unit
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
                                ReaderPosition(chapterLabel, positionLabel, settings.appTheme, stableInsets)
                                if (chrome.visible) {
                                    ReaderChrome(
                                        theme = settings.appTheme,
                                        immersive = settings.reading.immersive,
                                        narration = narrationUi.takeIf { it.active },
                                        onContents = { dismissLookup(true, "chrome contents"); panel = "contents" },
                                        onTypography = { dismissLookup(true, "chrome typography"); panel = "typography" },
                                        onOptions = {
                                            dismissLookup(true, "chrome options")
                                            refreshNarrationCacheSize()
                                            panel = "options"
                                        },
                                        onReadAloud = { if (!narrationUi.active) startNarration() },
                                        onPlayPause = {
                                            if (narrationUi.playing) narrationPlayback?.pause() else narrationPlayback?.play()
                                        },
                                        onPrevious = { narrationPlayback?.previous() },
                                        onNext = { narrationPlayback?.next() },
                                        onNextParagraph = { narrationPlayback?.nextParagraph() },
                                        onSpeed = { narrationPlayback?.setSpeed(nextNarrationSpeed(narrationUi.speed)) },
                                        onStop = { stopNarration(showChrome = true) },
                                        onRetry = { narrationPlayback?.retry() },
                                    )
                                } else if (narrationUi.active) {
                                    ReaderCompactNarration(
                                        theme = settings.appTheme,
                                        immersive = settings.reading.immersive,
                                        state = narrationUi,
                                        onPlayPause = {
                                            if (narrationUi.playing) narrationPlayback?.pause() else narrationPlayback?.play()
                                        },
                                        onPrevious = { narrationPlayback?.previous() },
                                        onNext = { narrationPlayback?.next() }
                                    )
                                }
                            }
                        }
                    }
                    when (panel) {
                        "contents" -> ReaderContentsSheet(
                            chapters = remember(publication) {
                                flattenContents(publication?.tableOfContents.orEmpty())
                            },
                            currentChapter = chapterLabel,
                            onOpen = { link -> panel = null; goSafely(link) },
                            onSearch = { panel = "search" },
                            onDismiss = { panel = null }
                        )
                        "search" -> SearchDialog()
                        "typography" -> ReaderTypographySheet(
                            settings = settings,
                            onApply = { reading, theme ->
                                // Apply appearance together, but retain the latest behaviour switches.
                                graph.scope.launch { graph.store.updateSettings { it.withTypography(reading).copy(appTheme = theme) } }
                                panel = null
                            },
                            onDismiss = { panel = null }
                        )
                        "options" -> ReaderOptionsSheet(
                            options = settings.readerOptions(),
                            onChange = { option, enabled ->
                                graph.scope.launch {
                                    graph.store.updateSettings { it.withReaderOption(option, enabled) }
                                }
                            },
                            cacheBytes = narrationCacheBytes,
                            onClearCache = { clearNarrationCache() },
                            onDismiss = { panel = null }
                        )
                    }
                    lookup?.let { request ->
                        key(request) {
                            LearningPopup(
                                context = request.context, settings = settings,
                                session = learningSession, dictionary = graph.dictionary, speech = graph.speech,
                                initialDictionary = request.dictionary,
                                onClose = { dismissLookup(true, "popup close") },
                                onCancel = { dismissLookup(true, "popup outside dismissal") },
                                onAdjust = { dismissLookup(false, "popup adjust") },
                                onDictionaryFailure = { cause ->
                                    graph.developerLog.recordFailure("dictionary", "lookup", cause)
                                }
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
                densities = graph.store.cachedPageDensity()
                val opened = graph.engine.open(File(loaded.filePath))
                publication = opened
                chapterTitles = ChapterTitles.of(opened.tableOfContents, opened.readingOrder)
                lifecycleScope.launch {
                    // Readium paginates a single resource, so a whole-book count is derived from the
                    // publication's text: the layout's characters-per-page measured on real chapters
                    // divides the book's own character total.
                    val perResource = withContext(Dispatchers.IO) {
                        val counts = linkedMapOf<String, Int>()
                        for (link in opened.readingOrder) {
                            val resource = opened.get(link) ?: continue
                            val length = runCatching { resource.length().getOrNull() }.getOrNull() ?: continue
                            if (length < 1) continue
                            val bytes = runCatching { resource.read(0L..(length - 1)).getOrNull() }
                                .getOrNull() ?: continue
                            val key = link.href.toString().substringBefore('#').substringBefore('?')
                            counts[key] = textCharacters(bytes)
                        }
                        counts
                    }
                    if (perResource.isEmpty()) return@launch
                    charsByResource = perResource
                    totalBookChars = perResource.values.sum()
                    // Text arrives after the first page; rebuild the label when it does.
                    lastPagination?.let { (page, total, locator) ->
                        positionLabel = pageLabel(page, total, locator, graph.store.settings.value.reading)
                    }
                }
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
                        catch (e: Exception) {
                            graph.developerLog.recordFailure("reader", "position", e)
                            runOnUiThread { Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_position_not_saved), Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                book = loaded
                // The service may have connected before the library lookup finished; re-evaluate
                // its playback now that this Activity knows which book it is showing.
                narrationBinder?.let(::observeNarration)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                graph.developerLog.recordFailure("reader", "open", e)
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
                        android.util.Log.w("PeekturnReader", "No external browser available")
                        Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResourceLoadFailed(href: org.readium.r2.shared.util.Url, error: org.readium.r2.shared.util.data.ReadError) {
                    if (!shouldExitForResourceFailure(href.removeQuery().removeFragment().toString(), spineHtmlHrefs)) return
                    graph.developerLog.recordFailure("reader", "chapter")
                    lifecycleScope.launch {
                        graph.store.clearLastBook()
                        Toast.makeText(this@ReaderActivity, getString(R.string.reader_toast_chapter_load_failed), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    lastPagination = Triple(pageIndex + 1, totalPages, locator)
                    positionLabel = pageLabel(pageIndex + 1, totalPages, locator, graph.store.settings.value.reading)
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
                // Packaged face files are served at https://readium/assets/fonts/...
                servedAssets = ReaderFonts.servedAssetPatterns,
                // Readium 3.1 quotes every family in --USER__fontFamily, including generic
                // "serif"/"sans-serif", which would ask for fonts literally named that. The
                // resolved bundled family plus an unquoted generic fallback is applied to the root
                // element instead; the declared @font-face still makes the real face load, and CJK
                // text keeps the platform serif/sans fallback.
                //
                // Readium CSS paints the native selection from --RS__selectionBackgroundColor, and
                // its stock #b4d8fe is the platform blue that fights every Peekturn theme: the theme
                // accent is injected here instead, in the same inline root style, per chapter.
                readiumCssRsProperties = org.readium.r2.navigator.epub.css.RsProperties(
                    overrides = mapOf(
                        "font-family" to readerFontFor(settings.reading.fontFamily).cssStack,
                        "--RS__selectionBackgroundColor" to readerSelectionBackground(settings.appTheme)
                    )
                ),
                selectionActionModeCallback = selectionActions,
                shouldApplyInsetsPadding = false
            ).apply {
                useReadiumCssFontSize = true
                registerReaderFonts()
            }
        )
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        supportFragmentManager.beginTransaction().replace(NAVIGATOR_ID, fragment).commitNow()
        navigator = fragment
        renderedPreferences = graph.store.settings.value.let { it.reading to it.appTheme }
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                // Readium excludes links, handled gestures and active selection before this callback.
                val width = fragment.publicationView.width.toFloat()
                if (readerTapAction(event.point.x, width) != ReaderTapAction.ToggleChrome) return false
                requestChromeToggle()
                tracker?.interact()
                return true
            }

            override fun onKey(event: KeyEvent): Boolean {
                if (event.type != KeyEvent.Type.Down || event.modifiers.isNotEmpty()) return false
                // Reveal controls without stealing Tab traversal or arrow/space navigation.
                if (event.key == Key.Tab || event.key == Key.ContextMenu) {
                    requestChromeVisibility(true)
                    return event.key == Key.ContextMenu
                }
                return false
            }
        })
        fragment.addInputListener(DirectionalNavigationAdapter(fragment))
        narrationPlayback?.location?.value?.let(::syncNarration)
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

    private fun bindNarration() {
        if (narrationBound) return
        narrationBound = true
        NarrationService.bind(this, narrationConnection)
    }

    private fun unbindNarration() {
        if (narrationBound) {
            narrationBound = false
            NarrationService.unbind(this, narrationConnection)
        }
        clearNarrationBinding()
    }

    /**
     * Narration surfaces are overlays, so their state changes never alter the publication
     * viewport and need no viewport transaction.
     */
    private fun applyNarrationState(next: NarrationState) {
        narrationUi = next
        if (!next.active) lookupPausedNarration = false
    }

    private fun clearNarrationBinding() {
        narrationObserveJob?.cancel()
        narrationObserveJob = null
        narrationSyncJob?.cancel()
        narrationSyncJob = null
        narrationFailureJob?.cancel()
        narrationFailureJob = null
        narrationBinder = null
        narrationPlayback = null
        pendingNarration = null
        narrationBound = false
        applyNarrationState(NarrationState())
    }

    private fun observeNarration(binder: NarrationService.Binder) {
        narrationObserveJob?.cancel()
        narrationObserveJob = lifecycleScope.launch {
            binder.playback.collectLatest { playback ->
                narrationPlayback = playback
                if (playback == null || playback.bookId != book?.id) {
                    applyNarrationState(NarrationState())
                    clearNarrationHighlight()
                    return@collectLatest
                }
                launch {
                    playback.state.collect { state ->
                        applyNarrationState(state)
                        if (!state.active) clearNarrationHighlight()
                    }
                }
                launch {
                    playback.location.collect { locator -> locator?.let(::syncNarration) }
                }
            }
        }
        narrationFailureJob?.cancel()
        narrationFailureJob = lifecycleScope.launch {
            binder.failure.collect { message ->
                if (message != null) Toast.makeText(this@ReaderActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Options starts at the first visible word of the current page. A stale search match or an
     * earlier selection is never substituted for the page the reader is actually looking at.
     */
    private fun startNarration() {
        val nav = navigator ?: return
        val id = book?.id ?: return
        lifecycleScope.launch {
            // Capture the page and intra-block offset before narration follows it.
            val base = runCatching { nav.firstVisibleElementLocator() }.getOrNull()
                ?: nav.currentLocator.value
            if (!isSafeReaderLocator(base)) return@launch
            val source = base.text.highlight.orEmpty()
            val offset = runCatching { nav.evaluateJavascript(narrationVisibleOffsetScript) }
                .getOrNull()
                ?.let { runCatching { JSONObject(it).optInt("offset", -1) }.getOrDefault(-1) }
                ?: -1
            val range = if (source.isNotEmpty() && offset in source.indices) {
                sentenceContaining(source, offset)
            } else {
                null
            }
            val sentenceRange = range?.takeUnless { it.isEmpty() }
            val startSentence = sentenceRange?.let { canonicalSentence(source.substring(it)) }
            // The initial locator must keep its block cssSelector; the sentence skipper moves
            // Readium's first utterance to the containing sentence.
            val locator = if (sentenceRange != null) {
                val target = startSentence ?: return@launch
                base.copy(text = Locator.Text(
                    before = source.substring(0, sentenceRange.first),
                    highlight = target,
                    after = source.substring(sentenceRange.last + 1)
                ))
            } else {
                base
            }
            requestChromeVisibility(true)
            beginNarration(id, locator, startSentence, "read aloud")
        }
    }

    private fun beginNarration(id: Long, locator: Locator, startSentence: String?, reason: String) {
        dismissLookup(true, reason)
        graph.speech.stop()
        val locatorJson = locator.toJSON().toString()
        val binder = narrationBinder
        if (binder != null) {
            binder.start(id, locatorJson, narrationUi.speed, startSentence)
        } else {
            pendingNarration = PendingNarration(id, locatorJson, narrationUi.speed, startSentence)
            bindNarration()
        }
    }

    private fun stopNarration(showChrome: Boolean = false) {
        narrationBinder?.stop()
        if (narrationBinder == null) stopService(Intent(this, NarrationService::class.java))
        pendingNarration = null
        lookupPausedNarration = false
        narrationUi = narrationUi.copy(active = false, playing = false, cached = false, error = null)
        if (showChrome) chrome = ReaderChromeState(visible = true)
        clearNarrationHighlight()
    }

    /**
     * The background service owns audio and publication lifetime. This only follows its current
     * sentence in the foreground reader and paints the optional highlight; it never starts audio.
     */
    private fun syncNarration(locator: Locator) {
        val nav = navigator ?: return
        if (!resumed || lookup != null || narrationPlayback?.bookId != book?.id) return
        narrationSyncJob?.cancel()
        narrationSyncJob = lifecycleScope.launch {
            if (navigator !== nav || !resumed || lookup != null) return@launch
            // Clear the previous group first: a page turn can repaint an old decoration while the
            // next apply is still in flight, which otherwise leaves two sentences highlighted.
            runCatching { nav.applyDecorations(emptyList(), NARRATION_DECORATION_GROUP) }
            runCatching { nav.go(locator, false) }
            runCatching {
                nav.applyDecorations(
                    listOf(
                        Decoration(
                            NARRATION_DECORATION_ID,
                            locator,
                            Decoration.Style.Highlight(NARRATION_HIGHLIGHT)
                        )
                    ),
                    NARRATION_DECORATION_GROUP
                )
            }
        }
    }

    private fun clearNarrationHighlight() {
        narrationSyncJob?.cancel()
        narrationSyncJob = null
        val nav = navigator ?: return
        lifecycleScope.launch {
            runCatching { nav.applyDecorations(emptyList(), NARRATION_DECORATION_GROUP) }
        }
    }

    private fun refreshNarrationCacheSize() {
        lifecycleScope.launch {
            narrationCacheBytes = graph.sentenceAudio.sizeBytes()
        }
    }

    /**
     * Stopping the service first cancels its current and queued synthesis. The provider gate then
     * waits for any write already in flight before deleting the cache, so a late prefetch cannot
     * repopulate it immediately after an explicit clear.
     */
    private fun clearNarrationCache() {
        stopNarration()
        lifecycleScope.launch {
            graph.narrationProviderGate.withLock { graph.sentenceAudio.clear() }
            narrationCacheBytes = graph.sentenceAudio.sizeBytes()
        }
    }

    /** Chrome is an overlay; toggling it never changes the publication viewport. */
    private fun requestChromeToggle() {
        chrome = ReaderChromeState(visible = !chrome.visible)
    }

    private fun requestChromeVisibility(target: Boolean) {
        if (chrome.visible != target) chrome = ReaderChromeState(visible = target)
    }

    private suspend fun refreshPreferences(settings: AppSettings) {
        val fragment = navigator ?: return
        if (renderedPreferences == (settings.reading to settings.appTheme)) return
        // Typography rebuilds the navigator, so capture the visible intra-block words before the
        // old view is removed and let the new navigator open at that precise anchor.
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
        // While narration owns this book, the service persists the spoken locator after each
        // utterance. A stale visual locator (especially the onPause flush) must not overwrite it.
        if (narrationUi.active && narrationPlayback?.bookId == book?.id) return
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
        // A deliberate page, contents or search move ends continuous reading; only the service's
        // own locator-follow path is allowed to turn pages without that decision.
        if (narrationUi.active) stopNarration()
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
            readerSelectionActions(graph.store.settings.value.quickExplain).forEachIndexed { index, action ->
                when (action) {
                    ReaderSelectionAction.EXPLAIN ->
                        menu.add(0, EXPLAIN, index, getString(R.string.reader_lookup_tab_explain))
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
                    ReaderSelectionAction.DICTIONARY ->
                        menu.add(0, DICTIONARY, index, getString(R.string.reader_lookup_tab_dictionary))
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
                    ReaderSelectionAction.COPY ->
                        menu.add(0, android.R.id.copy, index, getString(R.string.reader_selection_copy))
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
                }
            }
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
        graph.developerLog.recordFailure("reader", "selection")
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

    /**
     * Chapter pages are exact; the book total is derived once per layout and reused until the
     * typography or the viewport changes, which is the only moment the density can differ.
     */
    /**
     * Bottom-left position: one page pair for the whole book. A chapter counter beside it made the
     * reader reconcile two meanings in the same corner, so only book progress is shown; the chapter
     * name stays at the top-left and chapter pacing belongs to the contents.
     */
    private fun pageLabel(page: Int, chapterPages: Int, locator: Locator, reading: ReaderPreferences): String {
        if (reading.scroll) {
            return chapterPositionLabel(true, page, chapterPages, locator.locations.progression)
        }
        if (totalBookChars < 1) return ""
        val key = resourceKeyFor(locator.href.toString()) ?: return ""
        val chars = charsByResource[key] ?: return ""
        val signature = readerLayoutSignature(
            reading,
            navigator?.view?.width ?: 0,
            navigator?.view?.height ?: 0
        )
        // A title page or a one-page separator is mostly a partial page, so it cannot teach the
        // layout's density; a chapter that fills at least one page can.
        val measured = if (chapterPages >= PAGE_DENSITY_MIN_PAGES) chars.toDouble() / chapterPages else null
        val stored = densities[signature]
        val density = measured ?: stored ?: return ""
        if (measured != null && (stored == null || abs(measured - stored) / stored > 0.02)) {
            densities = densities + (signature to measured)
            lifecycleScope.launch { runCatching { graph.store.cachePageDensity(signature, measured) } }
        }
        val charsBefore = charsByResource.entries.takeWhile { it.key != key }.sumOf { it.value }
        val counter = bookPageCounter(charsBefore, page, totalBookChars, density) ?: return ""
        return getString(R.string.reader_position_pages, counter.first, counter.second)
    }

    /** The reading-order key for a locator, bridging directory prefixes and fragment forms. */
    private fun resourceKeyFor(href: String): String? {
        val key = href.substringBefore('#').substringBefore('?')
        if (key in charsByResource) return key
        return charsByResource.keys.firstOrNull { it.substringAfterLast('/') == key.substringAfterLast('/') }
    }

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
        // Narration and lookup pronunciation are separate owners. A lookup pauses only a playing
        // session, and the service records that this pause belongs to the lookup; a manual pause is
        // left exactly as the reader set it. Popup word/example audio stays latest-only.
        if (lookup == null && narrationUi.active) {
            // The service returns false for a session that was already manually paused, so only a
            // pause it actually caused can be resumed later.
            lookupPausedNarration = narrationBinder?.pauseForLookup() == true
        }
        selectionFocus = nav.view?.findFocus()
        lookup = LookupRequest(
            LookupContext(
                text,
                contextBeforeSelection(locator.text.before.orEmpty()),
                contextAfterSelection(locator.text.after.orEmpty()),
                book?.title.orEmpty(),
                chapterTitles?.titleFor(locator).orEmpty(),
                sourceId = "epub:${book?.id ?: 0L}",
                sectionKey = chapterTitles?.keyFor(locator).orEmpty()
            ),
            dictionary
        )
        tracker?.interact()
    }

    /**
     * Debug-gated selection trace. Selecting text is the user's own book content, so this records
     * lengths and decisions only, never the passage itself. It exists to make an interrupted
     * selection gesture diagnosable on a device; it is silent in release builds.
     */
    private fun selectionTrace(message: String) {
        if (app.peekturn.reader.BuildConfig.DEBUG) android.util.Log.d(SELECTION_LOG_TAG, message)
    }

    /**
     * Closes the current lookup. [reason] is diagnostic only: the trace must be able to tell a popup
     * close, an outside dismissal, a lifecycle clear and a navigation clear apart.
     */
    private fun dismissLookup(clearSelection: Boolean, reason: String) {
        releaseJob?.cancel()
        selectionProbeJob?.cancel()
        // Only the reader's own close of a lookup may undo the pause that lookup caused. Adjust,
        // navigation, lifecycle and reflow clears keep narration paused.
        val resumeNarration = clearSelection && lookupPausedNarration && reason in LOOKUP_RESUME_REASONS
        lookupPausedNarration = false
        lookup = null
        // Popup word/example pronunciation must be silent before the book resumes.
        graph.speech.stop()
        if (resumeNarration) narrationBinder?.resumeAfterLookup()
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
        lookupPausedNarration = false
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
            onOpen = { locator ->
                panel = null
                goSafely(locator)
            },
            onBack = { panel = readerPanelBackTarget("search") },
            onDismiss = { panel = readerPanelBackTarget("search") }
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
            catch (e: Exception) {
                graph.developerLog.recordFailure("reader", "search", e)
                searchStatus = BookSearchStatus.Failed
            }
            finally { searchBusy = false }
        }
    }

    private fun nextSearchPage() {
        if (searchBusy) return
        searchBusy = true
        searchJob = lifecycleScope.launch {
            try { readSearchPage() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                graph.developerLog.recordFailure("reader", "search", e)
                searchStatus = BookSearchStatus.Failed
            }
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
    override fun onStart() {
        super.onStart()
        bindNarration()
        refreshNarrationCacheSize()
    }
    override fun onStop() {
        clearNarrationHighlight()
        unbindNarration()
        super.onStop()
    }
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
        narrationObserveJob?.cancel()
        narrationFailureJob?.cancel()
        unbindNarration()
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

    private class LookupRequest(val context: LookupContext, val dictionary: Boolean)

    private data class PendingNarration(
        val bookId: Long,
        val locatorJson: String,
        val speed: Float,
        val startSentence: String?
    )
    companion object {
        private const val EXTRA_BOOK = "book_id"
        private const val STATE_LOCATOR = "reader_locator"
        private const val NAVIGATOR_ID = 0x4D4152
        private const val EXPLAIN = 0x4D01
        private const val DICTIONARY = 0x4D02
        private const val SELECTION_LOG_TAG = "PeekturnSelection"
        private val LOOKUP_RESUME_REASONS = setOf("popup close", "popup outside dismissal", "back")
        private const val NARRATION_DECORATION_ID = "narration-utterance"
        private const val NARRATION_DECORATION_GROUP = "narration"
        private val NARRATION_HIGHLIGHT = 0x2EFFC107.toInt()

        // Readium's release-time selection read is a JavaScript round trip into the WebView, so a
        // released selection is confirmed by agreeing reads instead of trusting one asynchronous value.
        private const val NATIVE_SELECTION_READS = 3
        private const val NATIVE_SELECTION_SETTLE_MS = 40L
        private const val NATIVE_SELECTION_READ_TIMEOUT_MS = 1500L

        fun intent(context: Context, bookId: Long): Intent = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK, bookId)
    }
}

internal fun flattenContents(links: List<Link>): List<Pair<Int, Link>> = buildList {
    fun append(children: List<Link>, depth: Int) {
        children.forEach { link -> add(depth to link); append(link.children, depth + 1) }
    }
    append(links, 0)
}

