package app.margin.reader

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.margin.reader.model.AppSettings
import app.margin.reader.reader.ReaderActivity
import app.margin.reader.ui.*

class MainActivity : ComponentActivity() {
    private val graph get() = (application as MarginApp).graph
    private val vm: MainViewModel by viewModels { MainViewModel.Factory(graph) }
    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBook) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppTheme(graph.store.settings.value.appTheme)
        val incoming = extractIncoming(intent)
        intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        vm.initialize(reopen = savedInstanceState == null, incoming = incoming)
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            DisposableEffect(settings.appTheme) {
                applyAppTheme(settings.appTheme)
                onDispose { }
            }
            MarginTheme(theme = settings.appTheme) {
                MarginHome(vm, settings,
                    onImport = { pickEpub.launch(arrayOf("application/epub+zip", "application/zip", "application/octet-stream")) },
                    onOpen = { id -> startActivity(ReaderActivity.intent(this, id)) }
                )
            }
        }
    }
    private fun applyAppTheme(theme: String) {
        val background = marginColorScheme(theme).background.toArgb()
        window.setBackgroundDrawable(ColorDrawable(background))
        val style = if (theme == "light" || theme == "warm") {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractIncoming(intent)?.let(vm::importBook)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }
    override fun onPause() { graph.speech.stop(); super.onPause() }
    private fun extractIncoming(intent: Intent?): Uri? = try {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }?.takeIf { it.scheme == "content" }
    } catch (e: RuntimeException) {
        android.util.Log.w("MarginImport", "Unreadable incoming attachment: ${e.javaClass.simpleName}")
        null
    }
}

@Composable
private fun MarginHome(vm: MainViewModel, settings: AppSettings, onImport: () -> Unit, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val books by vm.graph.store.books.collectAsStateWithLifecycle()
    val stats by vm.graph.store.stats.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val snackbar = remember { SnackbarHostState() }
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
                    listOf("Library", "Statistics", "Settings").forEachIndexed { index, label ->
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
                0 -> LibraryScreen(books, vm::open, onImport, vm::delete)
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
}
