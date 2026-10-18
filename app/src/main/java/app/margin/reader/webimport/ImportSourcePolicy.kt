package app.margin.reader.webimport

import java.net.URI

private val httpUrlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

/**
 * Link decision rules for the URL import, kept free of Android so they are unit-testable.
 * Only http(s) reaches a browser: share sheets also carry text, and a page is fetched directly
 * only when it looks like the article rather than a bot-check interstitial.
 */

/** A normalized http(s) link, or null for anything a browser should not be asked to open. */
internal fun normalizeHttpUrl(raw: String?): String? {
    val value = raw?.trim()?.trimEnd('.', ',', ')', ']', '"', '\'') ?: return null
    if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    if (uri.host.isNullOrBlank()) return null
    return uri.toString()
}

/** The first http(s) link in shared text, which browsers and X wrap in a title or a sentence. */
internal fun firstHttpUrl(text: String?): String? =
    text?.let { httpUrlPattern.find(it)?.value }?.let(::normalizeHttpUrl)

/**
 * True when a directly fetched response is the article itself. Bot protection answers 200 with a
 * challenge page, so status and content type alone are not enough; the caller falls back to the
 * interactive browser when this is false.
 */
internal fun acceptsDirectHtml(status: Int, contentType: String?, length: Int, head: String): Boolean {
    if (status != 200) return false
    if (contentType?.contains("text/html", ignoreCase = true) != true) return false
    if (length < MIN_DIRECT_HTML_CHARS) return false
    val marker = head.lowercase()
    return "just a moment" !in marker &&
        "cf-challenge" !in marker &&
        "enable javascript and cookies" !in marker
}

internal const val MIN_DIRECT_HTML_CHARS = 4_000
