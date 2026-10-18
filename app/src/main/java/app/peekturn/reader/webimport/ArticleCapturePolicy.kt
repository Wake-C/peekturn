package app.peekturn.reader.webimport

import java.net.URI
import java.util.Locale

/**
 * Minimum normalized text accepted by this article importer. This guards the observed preview
 * and error shells; it is not proof that an arbitrary site's entire article was captured.
 * Very short legitimate articles are refused rather than being silently mistaken for full text.
 */
internal const val MIN_ARTICLE_TEXT_CHARS = 500

/** Why a capture was refused, in the vocabulary the page-side bridge and the UI share. */
internal enum class CaptureRefusal(val code: String) {
    ERROR_PAGE("error-page"),
    SIGN_IN_REQUIRED("sign-in"),
    PREVIEW_ONLY("preview"),
    NO_ARTICLE("no-article")
}

internal sealed interface CaptureDecision {
    data object Import : CaptureDecision
    data class Refuse(val reason: CaptureRefusal) : CaptureDecision
}

// A short page carrying one of these did not expose its content. They are checked only below the
// article threshold, so a full article that happens to discuss a missing page is still imported.
private val errorMarkers = listOf(
    "post not found",
    "page not found",
    "this page doesn't exist",
    "this page does not exist",
    "we're unable to show this content",
    "we are unable to show this content",
    "content may be private",
    "content isn't available",
    "content is not available",
    "页面不存在",
    "内容不可用",
    "无法显示此内容"
)

private val signInMarkers = listOf(
    "sign in to continue",
    "log in to continue",
    "sign in to read",
    "log in to read",
    "sign in to view",
    "log in to view",
    "请登录",
    "登录后查看",
    "登录以查看",
    "登录后继续"
)

// Explicit calls to action that say the rest of the article lives elsewhere. A rendered preview
// can already be long, so these are checked even above the length gate; otherwise a preview would
// be imported merely for its size. Generic footer and reading-time labels ("read more", "min read")
// stay below the gate: a complete article may carry them without withholding anything. Word
// boundaries keep prose such as "read fully" out of the match.
private val withheldArticleCta = Regex(
    "\\bcontinue\\s+reading\\b" +
        "|\\b(?:read|view|open)\\s+(?:the\\s+)?full(?:\\s+(?:article|story|post|text))?\\b" +
        "|\\bfull\\s+article\\b" +
        "|阅读全文|展开全文|查看全文"
)

private val previewMarkers = listOf(
    "read more",
    "show more",
    "min read"
)

private val whitespaceRun = Regex("\\s+")

/**
 * Applies the same short-capture guard before conversion and before the EPUB is built.
 * The DOM capture adapter remains responsible for preserving the actual article content.
 */
internal fun reviewArticleCapture(text: String): CaptureDecision {
    val normalized = text.lowercase(Locale.ROOT).replace('\u2019', '\'')
    val compact = normalized.replace(whitespaceRun, " ").trim()
    if (compact.length >= MIN_ARTICLE_TEXT_CHARS) {
        if (withheldArticleCta.containsMatchIn(compact)) {
            return CaptureDecision.Refuse(CaptureRefusal.PREVIEW_ONLY)
        }
        return CaptureDecision.Import
    }
    if (errorMarkers.any { compact.contains(it) }) {
        return CaptureDecision.Refuse(CaptureRefusal.ERROR_PAGE)
    }
    if (signInMarkers.any { compact.contains(it) }) {
        return CaptureDecision.Refuse(CaptureRefusal.SIGN_IN_REQUIRED)
    }
    if (withheldArticleCta.containsMatchIn(compact) || previewMarkers.any { compact.contains(it) }) {
        return CaptureDecision.Refuse(CaptureRefusal.PREVIEW_ONLY)
    }
    return CaptureDecision.Refuse(CaptureRefusal.NO_ARTICLE)
}

/**
 * A page's own "read the full article" link is followed at most once, and only when it stays on the
 * same https origin at a different address. The app never guesses a corrected article id.
 */
internal fun sameOriginArticleTarget(currentUrl: String?, candidateUrl: String?): String? {
    val current = currentUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val candidate = candidateUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (current.scheme?.lowercase(Locale.ROOT) != "https") return null
    if (candidate.scheme?.lowercase(Locale.ROOT) != "https") return null
    val currentHost = current.host ?: return null
    val candidateHost = candidate.host ?: return null
    if (currentHost.isBlank() || candidateHost.isBlank()) return null
    if (!currentHost.equals(candidateHost, ignoreCase = true)) return null
    if (port(current) != port(candidate)) return null
    if (!candidate.userInfo.isNullOrEmpty()) return null
    if (current.toString().substringBefore('#') == candidate.toString().substringBefore('#')) return null
    return candidate.toString()
}

private fun port(uri: URI): Int = if (uri.port == -1) 443 else uri.port
