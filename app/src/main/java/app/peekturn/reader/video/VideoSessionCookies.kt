package app.peekturn.reader.video

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Anonymous source-session cookies, never browser/account cookies and never persisted or logged.
 * Redirects can issue a cookie needed by their next request. Discarding it repeats the same
 * Google/YouTube challenge redirect until OkHttp's follow-up limit, before media can resolve.
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
