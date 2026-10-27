package app.peekturn.reader.video

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Anonymous source-session cookies, never account cookies and never persisted or logged.
 * Redirects can issue a cookie needed by their next request. Discarding it repeats the same
 * Google/YouTube challenge redirect until OkHttp's follow-up limit, before media can resolve.
 * An explicit verification action may import a restricted set of anonymous WebView cookies.
 * This jar is owned by the source HTTP client; media fragments and model requests do not use it.
 */
internal class VideoSessionCookies(
    private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 128
) : CookieJar {
    private data class Key(val name: String, val domain: String, val path: String)
    private data class Entry(val cookie: Cookie, val expiresAt: Long)
    private val entries = LinkedHashMap<Key, Entry>()

    init { require(capacity > 0) }

    /**
     * NewPipe's CAPTCHA UI passes browser cookies to its downloader. Peekturn only accepts the
     * non-login cookie names used for anonymous challenges. Cookies remain memory-only, scoped to
     * the issuing domain, and expire locally after 30 minutes even if the WebView retains them.
     */
    @Synchronized
    fun importVerificationCookies(youtubeHeader: String?, googleHeader: String?): Boolean {
        val time = now()
        val grouped = listOf("www.youtube.com" to youtubeHeader,
                             "www.google.com" to googleHeader).map { (host, header) ->
            host to verificationCookies(host, header, time)
        }
        // VISITOR_INFO1_LIVE is common even without verification. It alone proves nothing.
        if (grouped.none { (_, cookies) -> cookies.any { it.name != "VISITOR_INFO1_LIVE" } }) {
            return false
        }
        for ((host, cookies) in grouped) {
            if (cookies.isNotEmpty()) saveFromResponse("https://$host/".toHttpUrl(), cookies)
        }
        return true
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val time = now()
        prune(time)
        if (!sourceDomain(url.host)) return
        for (cookie in cookies) {
            if (!sourceDomain(cookie.domain)) continue
            if (url.host != cookie.domain && (cookie.hostOnly || !url.host.endsWith(".${cookie.domain}"))) continue
            val key = Key(cookie.name, cookie.domain, cookie.path)
            // Replacements and server deletions apply to exactly the same name/domain/path.
            entries.remove(key)
            if (cookie.expiresAt <= time) continue
            if (cookie.name.length + cookie.value.length + cookie.domain.length + cookie.path.length > 4096) continue
            entries[key] = Entry(cookie, minOf(cookie.expiresAt, time + MAX_SESSION_MILLIS))
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        prune(now())
        if (!sourceDomain(url.host)) return emptyList()
        return entries.values.map { it.cookie }.filter { it.matches(url) }
            .sortedByDescending { it.path.length }
    }

    private fun prune(time: Long) { entries.entries.removeAll { it.value.expiresAt <= time } }

    private fun sourceDomain(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "google.com" || host.endsWith(".google.com")

    private companion object {
        const val MAX_SESSION_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Only verification-related names, never SAPISID, SID, HSID, or other login cookies. */
internal fun verificationCookies(host: String, header: String?, now: Long): List<Cookie> {
    if (host != "www.youtube.com" && host != "www.google.com") return emptyList()
    if (header.isNullOrBlank()) return emptyList()
    val permitted = setOf("GOOGLE_ABUSE_EXEMPTION", "s_gl", "goojf", "VISITOR_INFO1_LIVE")
    return header.split(';').mapNotNull { pair ->
        val name = pair.substringBefore('=', "").trim()
        val value = pair.substringAfter('=', "").trim()
        if (name !in permitted || value.isEmpty() || value.length > 2048) return@mapNotNull null
        runCatching {
            Cookie.Builder().name(name).value(value)
                .domain(host.substringAfter('.')).path("/")
                .expiresAt(now + 30L * 60 * 1000).secure().build()
        }.getOrNull()
    }
}
