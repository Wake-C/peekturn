package app.margin.reader.webimport

import android.webkit.JavascriptInterface

/**
 * The JavaScript side of the import bridge. It only assembles what the page sends and reports
 * through callbacks; no conversion logic or Android state lives here. Interface methods are called
 * on the WebView's JavaScript thread, so callbacks are handed to the activity to marshal.
 */
internal class WebEpubBridge(
    private val assembler: EpubChunkAssembler,
    private val onStatus: (String) -> Unit,
    private val onArchive: (ByteArray, String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    @Volatile private var settled = false
    @Volatile private var sessionToken = ""

    /**
     * A transfer is accepted only with the token minted for this attempt and installed in the page
     * just before it starts. Cross-origin frames cannot read that variable, so an embedded frame
     * cannot inject an archive or cancel the reader's own transfer.
     */
    fun beginSession(token: String) {
        sessionToken = token
        assembler.reset()
        settled = false
    }

    private fun authorized(token: String): Boolean = token.isNotEmpty() && token == sessionToken

    @JavascriptInterface
    fun pushChunk(index: Int, chunk: String, token: String) {
        if (!authorized(token) || settled) return
        if (assembler.push(index, chunk)) return
        fail("The article transfer was interrupted.")
    }

    @JavascriptInterface
    fun finish(name: String, token: String) {
        if (!authorized(token) || settled) return
        val bytes = assembler.finish()
        if (bytes == null) {
            fail("The article produced no readable book.")
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
    fun fail(message: String) {
        if (settled) return
        settled = true
        onFailure(message)
    }
}
