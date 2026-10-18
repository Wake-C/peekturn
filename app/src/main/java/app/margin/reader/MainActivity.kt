package app.margin.reader

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.margin.reader.R
import app.margin.reader.model.AppSettings
import app.margin.reader.narration.NarrationService
import app.margin.reader.reader.ReaderActivity
import app.margin.reader.ui.*
import app.margin.reader.webimport.WebImportActivity
import app.margin.reader.webimport.normalizeHttpUrl

class MainActivity : AppCompatActivity() {
    private val graph get() = (application as MarginApp).graph
    private val vm: MainViewModel by viewModels { MainViewModel.Factory(graph) }
    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBook) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.apply(this, graph.store.settings.value.uiLanguage)
        applyAppTheme(graph.store.settings.value.appTheme)
        vm.initialize(reopen = savedInstanceState == null)
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.uiLanguage) { AppLanguage.apply(this@MainActivity, settings.uiLanguage) }
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
    override fun onPause() { graph.speech.stop(); super.onPause() }
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
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
                0 -> LibraryScreen(books, vm::open, onImportFile, { urlDialog = true }, vm::delete)
                1 -> StatisticsScreen(stats, books, vm::open)
                else -> SettingsScreen(
                    settings, vm::save, vm.graph.explanations::testConnection, vm.graph.speech,
                    vm.graph.explanationCache::sizeBytes, vm.graph.explanationCache::clear,
                    vm.graph.sentenceAudio::sizeBytes,
                    { NarrationService.clearCache(context) }
                )
            }
            if (state.busy) Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .96f), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.shell_opening_book), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.shell_original_preserved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        autoCorrectEnabled = false
                    ),
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
