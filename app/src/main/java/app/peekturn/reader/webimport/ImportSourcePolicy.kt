package app.peekturn.reader.webimport

import java.net.URI

private val httpUrlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

/**
 * Link decision rules for the URL import, kept free of Android so they are unit-testable.
 * Only http(s) reaches a browser: share sheets also carry text, and a page is fetched directly
 * only when it looks like the article rather than a bot-check interstitial.
 */

/**
 * A normalized https link, or null for anything a browser should not be asked to open. Cleartext is
 * refused because the app's network security config forbids it, so an http link could never load in
 * the import browser; accepting it would promise a page the app cannot show.
 */
internal fun normalizeHttpUrl(raw: String?): String? {
    val value = raw?.trim()?.trimEnd('.', ',', ')', ']', '"', '\'', '。', '，', '、', '；', '：', '！', '？')
        ?: return null
    if (!value.startsWith("https://", ignoreCase = true)) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return uri.toString()
}

/**
 * The first https link in shared text, which browsers and X wrap in a title or a sentence. Chinese
 * text often follows the link without a space, so the match stops at the first non-ASCII character
 * before normalizing; a URL's non-ASCII content would be percent-encoded, never raw.
 */
internal fun firstHttpUrl(text: String?): String? =
    text?.let { httpUrlPattern.find(it)?.value?.takeWhile { character -> character.code in 33..126 } }
        ?.let(::normalizeHttpUrl)

/**
 * True when a directly fetched response is the article itself. Bot protection and account walls
 * answer 200 with a challenge or sign-in page, so status and content type alone are not enough; the
 * caller falls back to the interactive browser when this is false.
 */
internal fun acceptsDirectHtml(status: Int, contentType: String?, length: Int, head: String): Boolean {
    if (status != 200) return false
    if (contentType?.contains("text/html", ignoreCase = true) != true) return false
    if (length < MIN_DIRECT_HTML_CHARS) return false
    val marker = head.lowercase()
    return "just a moment" !in marker &&
        "cf-challenge" !in marker &&
        "enable javascript and cookies" !in marker &&
        "sign in to continue" !in marker &&
        "log in to continue" !in marker &&
        "sign in to read" !in marker &&
        "log in to read" !in marker &&
        "sign in to view" !in marker &&
        "log in to view" !in marker
}

internal const val MIN_DIRECT_HTML_CHARS = 4_000
