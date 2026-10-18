package app.margin.reader.webimport

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.margin.reader.MarginApp
import app.margin.reader.R
import app.margin.reader.reader.ReaderActivity
import app.margin.reader.ui.MarginTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The one entry for content another app sends. It exists as its own activity because a share while
 * the reader is on top is otherwise delivered to that task and never reaches the library screen.
 * Links go to the import browser; EPUB streams go straight to the importer.
 */
class ImportShareActivity : AppCompatActivity() {
    private val graph get() = (application as MarginApp).graph
    private var status by mutableStateOf(R.string.import_share_opening)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            MarginTheme(theme = settings.appTheme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(status), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        val target = when (val incoming = extract(intent)) {
            is Incoming.Link -> {
                startActivity(WebImportActivity.intent(this, incoming.url, direct = true))
                finish()
                return
            }
            is Incoming.Epub -> incoming.uri
            null -> {
                finish()
                return
            }
        }
        importEpub(target)
    }

    private fun importEpub(uri: Uri) {
        lifecycleScope.launch {
            try {
                val book = graph.importer.import(uri)
                startActivity(ReaderActivity.intent(this@ImportShareActivity, book.id))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MarginImport", "Shared EPUB failed: ${e.javaClass.simpleName}")
                Toast.makeText(this@ImportShareActivity, R.string.import_share_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private sealed interface Incoming {
        data class Link(val url: String) : Incoming
        data class Epub(val uri: Uri) : Incoming
    }

    /**
     * A share carries either text (a link plus surrounding words) or a stream (an EPUB). Only
     * content URIs are read, and only https links reach the browser, because the app's network
     * policy refuses cleartext and the import browser would otherwise show nothing.
     */
    private fun extract(intent: Intent?): Incoming? = try {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    firstHttpUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(Incoming::Link)
                } else {
                    streamUri(intent)?.takeIf { it.scheme == "content" }?.let(Incoming::Epub)
                }
            }
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" }?.let(Incoming::Epub)
            else -> null
        }
    } catch (e: RuntimeException) {
        Log.w("MarginImport", "Unreadable incoming attachment: ${e.javaClass.simpleName}")
        null
    }

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
}
