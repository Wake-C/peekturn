package app.margin.reader

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.margin.reader.reader.ReaderActivity
import app.margin.reader.ui.*

class MainActivity : ComponentActivity() {
    private val graph get() = (application as MarginApp).graph
    private val vm: MainViewModel by viewModels { MainViewModel.Factory(graph) }
    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBook) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val incoming = extractIncoming(intent)
        intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        vm.initialize(reopen = savedInstanceState == null, incoming = incoming)
        setContent {
            MarginTheme {
                MarginHome(vm,
                    onImport = { pickEpub.launch(arrayOf("application/epub+zip", "application/zip", "application/octet-stream")) },
                    onOpen = { id -> startActivity(ReaderActivity.intent(this, id)) }
                )
            }
        }
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
private fun MarginHome(vm: MainViewModel, onImport: () -> Unit, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val books by vm.graph.store.books.collectAsStateWithLifecycle()
    val settings by vm.graph.store.settings.collectAsStateWithLifecycle()
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
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf("Library", "Statistics", "Settings").forEachIndexed { index, label ->
                    NavigationBarItem(selected = state.tab == index, onClick = { vm.tab(index) },
                        icon = { Icon(when(index) { 0 -> Icons.Outlined.AutoStories; 1 -> Icons.Outlined.BarChart; else -> Icons.Outlined.Settings }, label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
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
