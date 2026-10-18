package app.margin.reader

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.margin.reader.R
import app.margin.reader.model.AppSettings
import app.margin.reader.reader.ReaderActivity
import app.margin.reader.ui.*
import app.margin.reader.webimport.WebImportActivity
import app.margin.reader.webimport.firstHttpUrl
import app.margin.reader.webimport.normalizeHttpUrl

class MainActivity : AppCompatActivity() {
    private val graph get() = (application as MarginApp).graph
    private val vm: MainViewModel by viewModels { MainViewModel.Factory(graph) }
    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBook) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The in-app choice is the source of truth, so it is pushed to the per-app locale API as the
        // first thing the screen does: Compose then resolves stringResource in that locale on this
        // first composition. Changing the language later recreates this activity (AppCompatDelegate
        // does it); the tab state survives in the retained ViewModel, settings live in MarginStore,
        // and the reader is not open while this screen is shown - it later reopens from its stored
        // Readium locator, so neither settings nor reading position are lost.
        applyAppLanguage(graph.store.settings.value.uiLanguage)
        applyAppTheme(graph.store.settings.value.appTheme)
        val incoming = extractIncoming(intent)
        intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        (incoming as? Incoming.Web)?.let { startActivity(WebImportActivity.intent(this, it.url, direct = true)) }
        vm.initialize(reopen = savedInstanceState == null, incoming = (incoming as? Incoming.Epub)?.uri)
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.uiLanguage) { applyAppLanguage(settings.uiLanguage) }
            DisposableEffect(settings.appTheme) {
                applyAppTheme(settings.appTheme)
                onDispose { }
            }
            MarginTheme(theme = settings.appTheme) {
                MarginHome(vm, settings,
                    onImportFile = { pickEpub.launch(arrayOf("application/epub+zip", "application/zip", "application/octet-stream")) },
                    onImportUrl = { url -> startActivity(WebImportActivity.intent(this, url)) },
                    onOpen = { id -> startActivity(ReaderActivity.intent(this, id)) }
                )
            }
        }
    }
    /** Empty list means "follow the system"; official per-app locale API, no custom locale plumbing. */
    private fun applyAppLanguage(language: String) {
        val locales = when (language) {
            "en", "zh" -> LocaleListCompat.forLanguageTags(language)
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
    private fun applyAppTheme(theme: String) {
        val background = marginColorScheme(theme).background.toArgb()
        window.setBackgroundDrawable(ColorDrawable(background))
        val style = if (!isDarkTheme(theme)) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (val incoming = extractIncoming(intent)) {
            is Incoming.Epub -> vm.importBook(incoming.uri)
            is Incoming.Web -> startActivity(WebImportActivity.intent(this, incoming.url, direct = true))
            null -> Unit
        }
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }
    override fun onPause() { graph.speech.stop(); super.onPause() }
    private sealed interface Incoming {
        data class Epub(val uri: Uri) : Incoming
        data class Web(val url: String) : Incoming
    }

    /**
     * Two kinds of share arrive here: a document (the EPUB picker or a share sheet attachment) and a
     * link (a browser or X share). A link only carries the address, never the rendered page, so it
     * opens the import browser, which tries a direct fetch first.
     */
    private fun extractIncoming(intent: Intent?): Incoming? = try {
        val source = intent
        when (source?.action) {
            Intent.ACTION_VIEW -> source.data
                ?.takeIf { it.scheme == "content" }
                ?.let(Incoming::Epub)
            Intent.ACTION_SEND -> {
                if (source.type?.startsWith("text/") == true) {
                    firstHttpUrl(source.getStringExtra(Intent.EXTRA_TEXT))?.let(Incoming::Web)
                } else {
                    val stream = if (Build.VERSION.SDK_INT >= 33) {
                        source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION") (source.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                    }
                    stream?.takeIf { it.scheme == "content" }?.let(Incoming::Epub)
                }
            }
            else -> null
        }
    } catch (e: RuntimeException) {
        android.util.Log.w("MarginImport", "Unreadable incoming attachment: ${e.javaClass.simpleName}")
        null
    }
}

@Composable
private fun MarginHome(
    vm: MainViewModel,
    settings: AppSettings,
    onImportFile: () -> Unit,
    onImportUrl: (String) -> Unit,
    onOpen: (Long) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val books by vm.graph.store.books.collectAsStateWithLifecycle()
    val stats by vm.graph.store.stats.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var addMenu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var urlInvalid by remember { mutableStateOf(false) }
    LaunchedEffect(state.pendingBookId, lifecycle) {
        val id = state.pendingBookId
        if (id != null && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            vm.consumeOpen(id)
            onOpen(id)
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // The bar is a plinth, not a shadow: a luminance step from the page plus one hairline marks
            // where the scrolling content ends, so a row clipped at the fold reads as "more below"
            // instead of being cut by an invisible edge. The selected pill keeps its theme role.
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf(
                        stringResource(R.string.shell_library_title),
                        stringResource(R.string.shell_statistics_title),
                        stringResource(R.string.settings_title)
                    ).forEachIndexed { index, label ->
                        NavigationBarItem(selected = state.tab == index, onClick = { vm.tab(index) },
                            icon = { Icon(when(index) { 0 -> Icons.Outlined.AutoStories; 1 -> Icons.Outlined.BarChart; else -> Icons.Outlined.Settings }, label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else when (state.tab) {
                0 -> LibraryScreen(books, vm::open, { addMenu = true }, vm::delete)
                1 -> StatisticsScreen(stats, books, vm::open)
                else -> SettingsScreen(settings, vm::save, vm.graph.explanations::testConnection, vm.graph.speech)
            }
            if (state.busy) Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .96f), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text("Opening your EPUB…", style = MaterialTheme.typography.titleLarge)
                    Text("Original content stays intact.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (addMenu) {
        AlertDialog(
            onDismissRequest = { addMenu = false },
            title = { Text(stringResource(R.string.shell_import_menu_title)) },
            text = {
                Column {
                    TextButton(onClick = { addMenu = false; onImportFile() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shell_import_from_file))
                    }
                    TextButton(onClick = { addMenu = false; urlDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shell_import_from_url))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addMenu = false }) { Text(stringResource(R.string.shell_cancel)) }
            }
        )
    }
    if (urlDialog) {
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text(stringResource(R.string.shell_import_from_url)) },
            text = {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it; urlInvalid = false },
                    singleLine = true,
                    label = { Text(stringResource(R.string.shell_import_url_hint)) },
                    isError = urlInvalid,
                    supportingText = if (urlInvalid) {
                        { Text(stringResource(R.string.shell_import_url_invalid)) }
                    } else null
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = normalizeHttpUrl(urlText)
                    if (url == null) urlInvalid = true
                    else { urlDialog = false; urlText = ""; onImportUrl(url) }
                }) { Text(stringResource(R.string.shell_import_open)) }
            },
            dismissButton = {
                TextButton(onClick = { urlDialog = false }) { Text(stringResource(R.string.shell_cancel)) }
            }
        )
    }
}
