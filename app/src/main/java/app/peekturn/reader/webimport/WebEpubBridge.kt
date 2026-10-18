package app.peekturn.reader.webimport

import android.webkit.JavascriptInterface

/**
 * The JavaScript side of the import bridge. It only assembles what the page sends, reviews captured
 * text against the shared policy and reports through callbacks; no conversion logic or Android
 * state lives here. Interface methods are called on the WebView's JavaScript thread, so callbacks
 * are handed to the activity to marshal.
 */
internal class WebEpubBridge(
    private val assembler: EpubChunkAssembler,
    private val onReview: (String) -> String,
    private val onFullArticle: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onArchive: (ByteArray, String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    @Volatile private var settled = false
    @Volatile private var sessionToken = ""

    /**
     * A transfer is accepted only with the token minted for this attempt and installed in the page
     * just before it starts. It distinguishes attempts; it is not a trust boundary, because scripts
     * running on the page can read the same variable.
     */
    fun beginSession(token: String) {
        sessionToken = token
        assembler.reset()
        settled = false
    }

    private fun authorized(token: String): Boolean = token.isNotEmpty() && token == sessionToken

    /**
     * Answers whether the captured text is a full article or a preview/login/error shell. The page
     * keeps only the code; the text itself is never stored or logged.
     */
    @JavascriptInterface
    fun review(text: String): String = onReview(text)

    /** A page-discovered link that may lead to the full article; the activity validates and may follow it once. */
    @JavascriptInterface
    fun fullArticle(url: String) {
        if (!url.isBlank()) onFullArticle(url)
    }

    @JavascriptInterface
    fun pushChunk(index: Int, chunk: String, token: String) {
        if (!authorized(token) || settled) return
        if (assembler.push(index, chunk)) return
        fail("transfer-failed")
    }

    @JavascriptInterface
    fun finish(name: String, token: String) {
        if (!authorized(token) || settled) return
        val bytes = assembler.finish()
        if (bytes == null) {
            fail("no-article")
            return
        }
        settled = true
        onArchive(bytes, name.ifBlank { "article.epub" })
    }

    @JavascriptInterface
    fun status(code: String) {
        if (!settled) onStatus(code)
    }

    @JavascriptInterface
    fun fail(code: String) {
        if (settled) return
        settled = true
        onFailure(code)
    }
}
