package app.margin.reader.webimport

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.margin.reader.MarginApp
import app.margin.reader.R
import app.margin.reader.reader.ReaderActivity
import app.margin.reader.ui.MarginTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class ImportUi(
    val title: String = "",
    val status: Int = R.string.web_import_loading,
    val canImport: Boolean = false,
    val working: Boolean = false
)

/**
 * Hosts the interactive import browser. It owns no conversion logic: the vendored converter runs
 * inside the page, and this activity only decides how the page is loaded, forwards status, and
 * hands the produced archive to the existing EPUB importer.
 */
class WebImportActivity : ComponentActivity() {
    private val graph get() = (application as MarginApp).graph
    private var ui by mutableStateOf(ImportUi())
    private var webView: WebView? = null
    private var bridge: WebEpubBridge? = null
    private var directFirstLoad = false
    private val assembler = EpubChunkAssembler()
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = normalizeHttpUrl(intent.getStringExtra(EXTRA_URL))
        if (url == null) {
            finish()
            return
        }
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }
        })
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            MarginTheme(theme = settings.appTheme) {
                WebImportScreen(
                    ui = ui,
                    onClose = { finish() },
                    onImport = { importPage() },
                    createWebView = { context -> createWebView(context, url, direct) }
                )
            }
        }
    }

    override fun onDestroy() {
        webView?.run {
            stopLoading()
            removeJavascriptInterface("MarginBridge")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, url: String, direct: Boolean): WebView {
        directFirstLoad = direct
        val view = WebView(context)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            // WebView identifies itself with "; wv"; the sites this exists for treat that as a bot.
            userAgentString = userAgentString.replace("; wv", "")
        }
        val epubBridge = WebEpubBridge(
            assembler = assembler,
            onStatus = { code -> runOnUiThread { showStatus(code) } },
            onArchive = { bytes, name -> runOnUiThread { saveAndImport(bytes, name) } },
            onFailure = { message -> runOnUiThread { showFailure(message) } }
        )
        bridge = epubBridge
        view.addJavascriptInterface(epubBridge, "MarginBridge")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, pageUrl: String?) {
                if (pageUrl == null || pageUrl == "about:blank") return
                ui = ui.copy(canImport = true, working = false, status = R.string.web_import_ready)
                val startNow = directFirstLoad
                directFirstLoad = false
                injectConverter(startWhenReady = startNow, auto = true)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) ui = ui.copy(title = title)
            }

            // The converter reports failures through alert(); the app states them in its own bar.
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult?): Boolean {
                Log.w("MarginWebImport", "Converter alert: $message")
                result?.cancel()
                return true
            }
        }
        webView = view
        lifecycleScope.launch {
            val fetched = if (direct) withContext(Dispatchers.IO) { fetchDirect(url) } else null
            if (fetched != null) {
                // A redirect can change the directory or the host; relative resources resolve against it.
                view.loadDataWithBaseURL(fetched.url, fetched.html, "text/html", "utf-8", fetched.url)
            } else {
                view.loadUrl(url)
            }
        }
        return view
    }

    private data class Fetched(val html: String, val url: String)

    /** Direct fetch is the fast path; anything that is not clearly the article goes to the browser. */
    private fun fetchDirect(url: String): Fetched? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        http.newCall(request).execute().use { response ->
            val contentType = response.header("Content-Type")
            if (contentType?.contains("text/html", ignoreCase = true) != true) {
                null
            } else {
                // The declared length can be absent or lie (chunked or decompressed responses), so the
                // limit is enforced while reading, not trusted from a header.
                val bytes = response.body?.byteStream()?.use { readBounded(it, MAX_HTML_BYTES) }
                val body = bytes?.toString(Charsets.UTF_8)
                if (body != null && acceptsDirectHtml(response.code, contentType, body.length, body.take(4096))) {
                    Fetched(body, response.request.url.toString())
                } else {
                    null
                }
            }
        }
    } catch (e: IOException) {
        Log.i("MarginWebImport", "Direct fetch declined: ${e.javaClass.simpleName}")
        null
    }

    private fun readBounded(stream: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /**
     * Injects the vendored converter in the order its own background script uses. The scripts are
     * evaluated inline rather than referenced by URL: articles commonly send a Content-Security-Policy
     * that would refuse a script from the app's asset origin, while inline evaluation is not subject
     * to it. Chaining on each callback keeps the order the converter requires.
     */
    private fun injectConverter(startWhenReady: Boolean, auto: Boolean) {
        val view = webView ?: return
        view.evaluateJavascript("window.__marginConverterLoaded === true", { loaded ->
            if (loaded == "true") {
                if (startWhenReady) startConversion()
                return@evaluateJavascript
            }
            view.evaluateJavascript("window.__marginConverterLoaded = true", null)
            injectScript(view, 0, startWhenReady, auto)
        })
    }

    private fun injectScript(view: WebView, index: Int, startWhenReady: Boolean, auto: Boolean) {
        if (index >= CONVERTER_SCRIPTS.size) {
            if (!startWhenReady) return
            if (auto) startIfArticle() else startConversion()
            return
        }
        val source = try {
            assets.open("webimport/${CONVERTER_SCRIPTS[index]}").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w("MarginWebImport", "Converter asset missing: ${CONVERTER_SCRIPTS[index]}")
            showFailure("Could not load ${CONVERTER_SCRIPTS[index]}")
            return
        }
        view.evaluateJavascript(source, { injectScript(view, index + 1, startWhenReady, auto) })
    }

    /**
     * Shared links import without a tap, but an intermediate page, an error page or a challenge must
     * not become a book: auto-start waits until the document carries article-like text.
     */
    private fun startIfArticle() {
        webView?.evaluateJavascript(ARTICLE_READINESS_SCRIPT) { ready ->
            if (ready == "true") {
                startConversion()
            } else {
                ui = ui.copy(canImport = true, working = false, status = R.string.web_import_ready)
            }
        }
    }

    private fun startConversion() {
        // A new attempt gets fresh transfer state and its own token, so a retry after a failure works.
        val token = UUID.randomUUID().toString()
        bridge?.beginSession(token)
        webView?.evaluateJavascript("window.__marginToken = '$token'", null)
        webView?.evaluateJavascript("window.MarginImport && window.MarginImport.start()", null)
    }

    private fun importPage() {
        if (ui.working) return
        ui = ui.copy(working = true, status = R.string.web_import_extracting)
        injectConverter(startWhenReady = true, auto = false)
    }

    private fun showStatus(code: String) {
        Log.i("MarginWebImport", "status=$code")
        val status = when (code) {
            "extracting" -> R.string.web_import_extracting
            "building" -> R.string.web_import_building
            "sending" -> R.string.web_import_saving
            else -> return
        }
        ui = ui.copy(status = status, working = true)
    }

    private fun showFailure(message: String) {
        Log.w("MarginWebImport", "Conversion declined: $message")
        ui = ui.copy(status = R.string.web_import_failed, working = false, canImport = true)
    }

    private fun saveAndImport(bytes: ByteArray, name: String) {
        ui = ui.copy(working = true, status = R.string.web_import_saving, canImport = false)
        lifecycleScope.launch {
            val file = File(cacheDir, "webimport-${System.currentTimeMillis()}.epub")
            try {
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                val book = graph.importer.importFile(file)
                startActivity(ReaderActivity.intent(this@WebImportActivity, book.id))
                finish()
            } catch (e: Exception) {
                Log.w("MarginWebImport", "Generated EPUB was not importable: ${e.javaClass.simpleName}")
                file.delete()
                ui = ui.copy(status = R.string.web_import_failed, working = false, canImport = true)
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_DIRECT = "direct"
        private const val MAX_HTML_BYTES = 12L * 1024 * 1024
        private const val ARTICLE_READINESS_SCRIPT =
            "(function(){var t=((document.body&&document.body.innerText)||'').trim();" +
                "var s=((document.title||'')+' '+t).toLowerCase();" +
                "return t.length>=400 && !/just a moment|attention required|verify you are human/.test(s);})()"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
        private val CONVERTER_SCRIPTS = listOf(
            "margin-shim.js",
            "jszip.js",
            "readability.js",
            "utils.js",
            "cssSanitizer.js",
            "sanitizeHtml.js",
            "extractHtml.js",
            "saveEbook.js",
            "margin-import.js"
        )

        fun intent(context: Context, url: String, direct: Boolean = false): Intent =
            Intent(context, WebImportActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DIRECT, direct)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebImportScreen(
    ui: ImportUi,
    onClose: () -> Unit,
    onImport: () -> Unit,
    createWebView: (Context) -> WebView
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title.ifBlank { stringResource(R.string.web_import_title) }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.web_import_close))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(ui.status),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onImport, enabled = ui.canImport && !ui.working) {
                        Text(stringResource(R.string.web_import_action))
                    }
                }
            }
        }
    ) { padding ->
        AndroidView(
            factory = { context -> createWebView(context) },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
