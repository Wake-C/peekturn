package app.peekturn.reader.webimport

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
import app.peekturn.reader.reader.ReaderActivity
import app.peekturn.reader.ui.PeekturnTheme
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * inside the page, and this activity only decides how the page is loaded, waits for the article to
 * be rendered, forwards the converter's verdict, and hands the produced archive to the existing
 * EPUB importer.
 */
class WebImportActivity : AppCompatActivity() {
    private val graph get() = (application as PeekturnApp).graph
    private var ui by mutableStateOf(ImportUi())
    private var webView: WebView? = null
    private var bridge: WebEpubBridge? = null
    private var originalUrl: String? = null
    private var autoStartOnLoad = false
    private var directLoaded = false
    private var reloadedForInteraction = false
    private var followedFullArticle = false
    @Volatile private var offeredFullArticle: String? = null
    private var converterReady = false
    private var injecting = false
    private var pageGeneration = 0
    private val converterWaiters = mutableListOf<() -> Unit>()
    private var readinessJob: Job? = null
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
        originalUrl = url
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        autoStartOnLoad = direct
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }
        })
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            PeekturnTheme(theme = settings.appTheme) {
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
        readinessJob?.cancel()
        webView?.run {
            stopLoading()
            removeJavascriptInterface("PeekturnBridge")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, url: String, direct: Boolean): WebView {
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
            onReview = { text ->
                when (val decision = reviewArticleCapture(text)) {
                    CaptureDecision.Import -> "import"
                    is CaptureDecision.Refuse -> decision.reason.code
                }
            },
            onFullArticle = { target -> offeredFullArticle = target },
            onStatus = { code -> runOnUiThread { showStatus(code) } },
            onArchive = { bytes, name -> runOnUiThread { saveAndImport(bytes, name) } },
            onFailure = { code -> runOnUiThread { showFailure(code) } }
        )
        bridge = epubBridge
        view.addJavascriptInterface(epubBridge, "PeekturnBridge")
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                readinessJob?.cancel()
                pageGeneration++
                converterReady = false
                injecting = false
                converterWaiters.clear()
                offeredFullArticle = null
                ui = ui.copy(status = R.string.web_import_loading, canImport = false, working = false)
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                if (pageUrl == null || pageUrl == "about:blank") return
                val shouldAutoStart = autoStartOnLoad
                autoStartOnLoad = false
                ui = ui.copy(status = R.string.web_import_loading, canImport = false, working = false)
                val generation = pageGeneration
                injectConverter(generation) {
                    if (generation != pageGeneration) return@injectConverter
                    ui = ui.copy(canImport = true, working = false, status = R.string.web_import_ready)
                    if (shouldAutoStart) awaitArticleAndImport(automatic = true)
                }
            }

            /** A page that never renders is a WebView failure; the URL is never recorded. */
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    graph.developerLog.recordFailure("web-import", "webview-${error.errorCode}")
                }
            }

            /** An error page is a failed import target; only the status code is recorded. */
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    graph.developerLog.recordFailure("web-import", "http-${errorResponse.statusCode}")
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) ui = ui.copy(title = title)
            }

            // The converter reports failures through alert(); the app states them in its own bar.
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult?): Boolean {
                Log.w("PeekturnWebImport", "Converter alert")
                result?.cancel()
                return true
            }
        }
        webView = view
        lifecycleScope.launch {
            val fetched = if (direct) withContext(Dispatchers.IO) { fetchDirect(url) } else null
            if (fetched != null) {
                // A redirect can change the directory or the host; relative resources resolve against it.
                directLoaded = true
                view.loadDataWithBaseURL(fetched.url, fetched.html, "text/html", "utf-8", fetched.url)
            } else {
                directLoaded = false
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
        Log.i("PeekturnWebImport", "Direct fetch declined: ${e.javaClass.simpleName}")
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
    private fun injectConverter(generation: Int, onReady: () -> Unit) {
        val view = webView ?: return
        if (converterReady) {
            onReady()
            return
        }
        converterWaiters += onReady
        if (injecting) return
        injecting = true
        view.evaluateJavascript("window.__peekturnConverterLoaded === true", { loaded ->
            if (generation != pageGeneration) return@evaluateJavascript
            if (loaded == "true") {
                converterReady = true
                finishInjection()
            } else {
                view.evaluateJavascript("window.__peekturnConverterLoaded = true", null)
                injectScript(view, 0, generation)
            }
        })
    }

    private fun injectScript(view: WebView, index: Int, generation: Int) {
        if (generation != pageGeneration) return
        if (index >= CONVERTER_SCRIPTS.size) {
            converterReady = true
            finishInjection()
            return
        }
        val source = try {
            assets.open("webimport/${CONVERTER_SCRIPTS[index]}").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w("PeekturnWebImport", "Converter asset missing: ${CONVERTER_SCRIPTS[index]}")
            injecting = false
            converterWaiters.clear()
            showFailure("failed", e)
            return
        }
        view.evaluateJavascript(source) {
            if (generation == pageGeneration) injectScript(view, index + 1, generation)
        }
    }

    private fun finishInjection() {
        injecting = false
        val callbacks = converterWaiters.toList()
        converterWaiters.clear()
        callbacks.forEach { it() }
    }

    /**
     * A rendered article is not the same as a finished page: single-page sites finish loading their
     * shell first. The probe waits for article-like text, gives the browser time to settle, and only
     * then starts conversion. A short shell is handed to the converter anyway so it can classify the
     * capture and offer a discoverable full-article link; the bridge refuses to build a preview.
     */
    private fun awaitArticleAndImport(automatic: Boolean) {
        readinessJob?.cancel()
        readinessJob = lifecycleScope.launch {
            ui = ui.copy(working = true, canImport = false, status = R.string.web_import_extracting)
            val started = SystemClock.elapsedRealtime()
            val deadline = started + if (automatic) AUTO_READY_TIMEOUT_MS else MANUAL_READY_TIMEOUT_MS
            var readySince = 0L
            var lastDecision: CaptureDecision? = null
            var lastLength = -1
            var stableRefusals = 0
            while (isActive) {
                val probe = readPageProbe()
                if (probe != null) {
                    val decision = reviewArticleCapture(probe.sample)
                    lastDecision = decision
                    val now = SystemClock.elapsedRealtime()
                    if (decision == CaptureDecision.Import) {
                        stableRefusals = 0
                        if (readySince == 0L) readySince = now
                        if (now - readySince >= ARTICLE_SETTLE_MS) {
                            startConversion()
                            return@launch
                        }
                    } else {
                        readySince = 0
                        if (probe.length == lastLength) stableRefusals++ else stableRefusals = 0
                        lastLength = probe.length
                        val reason = (decision as? CaptureDecision.Refuse)?.reason
                        val settled = now - started >= REFUSAL_SETTLE_MS && stableRefusals >= 1
                        if (settled && (reason == CaptureRefusal.ERROR_PAGE || reason == CaptureRefusal.SIGN_IN_REQUIRED)) {
                            showCaptureFailure(decision)
                            return@launch
                        }
                        if (settled) {
                            // The converter reviews the capture and may hand back a full-article link.
                            startConversion()
                            return@launch
                        }
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
                delay(READY_POLL_MS)
            }
            showCaptureFailure(lastDecision)
        }
    }

    private suspend fun readPageProbe(): PageProbe? {
        val view = webView ?: return null
        return suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(PAGE_PROBE_SCRIPT) { value ->
                if (continuation.isActive) continuation.resume(parseProbe(value))
            }
        }
    }

    private fun parseProbe(value: String?): PageProbe? {
        if (value.isNullOrBlank() || value == "null") return null
        return runCatching {
            val json = JsonParser.parseString(value).asJsonObject
            PageProbe(
                length = json.get("length")?.asInt ?: 0,
                sample = json.get("sample")?.asString.orEmpty()
            )
        }.getOrNull()
    }

    private fun startConversion() {
        // A new attempt gets fresh transfer state and its own token, so a retry after a failure works.
        val token = UUID.randomUUID().toString()
        bridge?.beginSession(token)
        ui = ui.copy(working = true, status = R.string.web_import_extracting, canImport = false)
        webView?.evaluateJavascript("window.__peekturnToken = '$token'", null)
        webView?.evaluateJavascript("window.PeekturnImport && window.PeekturnImport.start()", null)
    }

    private fun importPage() {
        if (ui.working) return
        readinessJob?.cancel()
        ui = ui.copy(working = true, status = R.string.web_import_extracting, canImport = false)
        injectConverter(pageGeneration) { awaitArticleAndImport(automatic = false) }
    }

    private fun showStatus(code: String) {
        val status = when (code) {
            "extracting" -> R.string.web_import_extracting
            "building" -> R.string.web_import_building
            "sending" -> R.string.web_import_saving
            else -> return
        }
        Log.i("PeekturnWebImport", "status=$code")
        ui = ui.copy(status = status, working = true, canImport = false)
    }

    private fun showCaptureFailure(decision: CaptureDecision?) {
        val reason = (decision as? CaptureDecision.Refuse)?.reason
        if (reason != null &&
            reason != CaptureRefusal.ERROR_PAGE &&
            fallBackToInteractivePage()
        ) {
            return
        }
        val status = when (reason) {
            CaptureRefusal.ERROR_PAGE -> R.string.web_import_error_page
            CaptureRefusal.SIGN_IN_REQUIRED -> R.string.web_import_sign_in
            CaptureRefusal.PREVIEW_ONLY -> R.string.web_import_preview_only
            CaptureRefusal.NO_ARTICLE -> R.string.web_import_no_article
            null -> R.string.web_import_not_ready
        }
        graph.developerLog.recordFailure("web-import", "convert")
        ui = ui.copy(status = status, working = false, canImport = true)
    }

    /**
     * A direct fetch has no login session. Hand the same address back to the interactive browser
     * once, so an account wall can be passed by hand instead of ending in a dead end.
     */
    private fun fallBackToInteractivePage(): Boolean {
        if (!directLoaded || reloadedForInteraction) return false
        val page = originalUrl ?: return false
        reloadedForInteraction = true
        directLoaded = false
        autoStartOnLoad = false
        ui = ui.copy(status = R.string.web_import_loading, working = false, canImport = false)
        webView?.loadUrl(page)
        return true
    }

    private fun showFailure(code: String, cause: Throwable? = null) {
        val knownCode = CaptureRefusal.entries.firstOrNull { it.code == code }?.code ?: "failed"
        Log.w("PeekturnWebImport", "Conversion declined: $knownCode")
        readinessJob?.cancel()
        val offer = offeredFullArticle
        offeredFullArticle = null
        val needsFullPage = knownCode == CaptureRefusal.PREVIEW_ONLY.code ||
            knownCode == CaptureRefusal.NO_ARTICLE.code ||
            knownCode == CaptureRefusal.SIGN_IN_REQUIRED.code
        if (needsFullPage && !followedFullArticle) {
            val target = sameOriginArticleTarget(webView?.url ?: originalUrl, offer)
            if (target != null) {
                followedFullArticle = true
                directLoaded = false
                autoStartOnLoad = true
                ui = ui.copy(status = R.string.web_import_loading, working = false, canImport = false)
                webView?.loadUrl(target)
                return
            }
        }
        // A direct fetch has no login session. Hand the same address back to the interactive browser
        // once, so an account wall can be passed by hand instead of ending in a dead end.
        if (needsFullPage && fallBackToInteractivePage()) return
        val status = when (knownCode) {
            CaptureRefusal.ERROR_PAGE.code -> R.string.web_import_error_page
            CaptureRefusal.SIGN_IN_REQUIRED.code -> R.string.web_import_sign_in
            CaptureRefusal.PREVIEW_ONLY.code -> R.string.web_import_preview_only
            CaptureRefusal.NO_ARTICLE.code -> R.string.web_import_no_article
            else -> R.string.web_import_failed
        }
        graph.developerLog.recordFailure("web-import", "convert", cause)
        ui = ui.copy(status = status, working = false, canImport = true)
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
                Log.w("PeekturnWebImport", "Generated EPUB was not importable: ${e.javaClass.simpleName}")
                if (e !is CancellationException) {
                    graph.developerLog.recordFailure("web-import", "convert", e)
                }
                file.delete()
                ui = ui.copy(status = R.string.web_import_failed, working = false, canImport = true)
            }
        }
    }

    private data class PageProbe(
        val length: Int,
        val sample: String
    )

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_DIRECT = "direct"
        private const val MAX_HTML_BYTES = 12L * 1024 * 1024
        private const val READY_POLL_MS = 400L
        private const val ARTICLE_SETTLE_MS = 600L
        private const val REFUSAL_SETTLE_MS = 3_000L
        private const val AUTO_READY_TIMEOUT_MS = 15_000L
        private const val MANUAL_READY_TIMEOUT_MS = 6_000L
        private const val PAGE_PROBE_SCRIPT =
            "window.PeekturnImport && window.PeekturnImport.probe ? window.PeekturnImport.probe() : null"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
        private val CONVERTER_SCRIPTS = listOf(
            "peekturn-shim.js",
            "jszip.js",
            "readability.js",
            "utils.js",
            "cssSanitizer.js",
            "sanitizeHtml.js",
            "extractHtml.js",
            "saveEbook.js",
            "peekturn-article-adapters.js",
            "peekturn-import.js"
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
                        Icon(PeekturnIcons.Close, contentDescription = stringResource(R.string.web_import_close))
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
