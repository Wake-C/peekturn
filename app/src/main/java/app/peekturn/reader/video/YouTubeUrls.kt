package app.peekturn.reader.video

import java.net.URI
import java.util.Locale

/**
 * Recognizes the YouTube link forms the app imports and reduces them to an identity that never
 * carries `si`, `is`, `list` or any other tracking parameter.
 *
 * Share sheets send a title plus a link, and Chinese text often follows the link without a space,
 * so parsing looks for the first link in the text instead of requiring the whole string to be one.
 * Only http(s) is accepted; a bare `youtu.be/...` token is read as its https form because it names
 * no other protocol.
 */
object YouTubeUrls {
    private const val VIDEO_ID_LENGTH = 11

    private val anySchemePattern = Regex("""[A-Za-z][A-Za-z0-9+.-]*://""")
    private val digitsPattern = Regex("""^\d+$""")
    private val clockPattern = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""", RegexOption.IGNORE_CASE)

    private const val TOKEN_TERMINATORS = "\"'<>"
    private val trailingPunctuation = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}',
        '。', '，', '、', '；', '：', '！', '？', '）', '】', '》', '」', '』', '…'
    )
    private val bareLinkPattern = Regex(
        """(?<![A-Za-z0-9._:-])(?:youtu\.be/|(?:www\.|m\.)?youtube\.com/)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The supported video identity, or null when the text does not lead with a supported public
     * YouTube video link. A link that carries an explicit start time returns it in milliseconds.
     */
    fun parse(text: String): VideoLink? {
        val candidate = firstLinkToken(text) ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!isHttpScheme(uri)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        val parameters = rawQueryParameters(uri.rawQuery)
        val id = extractVideoId(host, uri, parameters) ?: return null
        if (!isVideoId(id)) return null
        val timeValue = parameters.firstOrNull { it.first == "t" }?.second?.takeIf { it.isNotBlank() }
            ?: parameters.firstOrNull { it.first == "start" }?.second
        if (timeValue == null || timeValue.isBlank()) return VideoLink(id)
        val startMs = parseStartMs(timeValue) ?: return null
        return VideoLink(id, startMs)
    }

    /**
     * True when the first link in the text is a YouTube link, even when it is not an importable
     * video (a channel, a playlist or an invalid id). The app must not fall back to article capture
     * for these; the video path reports the precise refusal instead.
     */
    fun isYouTubeUrl(text: String): Boolean {
        val candidate = firstLinkToken(text) ?: return false
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (!isHttpScheme(uri) || uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return isYouTubeHost(host)
    }

    /**
     * Any youtube.com subdomain belongs to YouTube for routing, so a music or studio link reports
     * an explicit unsupported-link error instead of being handed to article capture. Parsing still
     * accepts only the documented watch, short and embed forms.
     */
    private fun isYouTubeHost(host: String): Boolean =
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")

    internal fun isVideoId(value: String): Boolean {
        if (value.length != VIDEO_ID_LENGTH) return false
        for (character in value) {
            val valid = character in 'A'..'Z' || character in 'a'..'z' ||
                character in '0'..'9' || character == '_' || character == '-'
            if (!valid) return false
        }
        return true
    }

    /** Raw query pairs, without percent-decoding: video ids and start times are plain ASCII. */
    internal fun rawQueryParameters(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery.isNullOrEmpty()) return emptyList()
        val parameters = ArrayList<Pair<String, String>>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val separator = part.indexOf('=')
            if (separator < 0) {
                parameters.add(part to "")
            } else {
                parameters.add(part.substring(0, separator) to part.substring(separator + 1))
            }
        }
        return parameters
    }

    /** `90`, `90s` or `1h2m3s` to milliseconds; null for malformed, negative or overflowing values. */
    internal fun parseStartMs(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (digitsPattern.matches(value)) {
            val seconds = value.toLongOrNull() ?: return null
            return secondsToMillis(seconds)
        }
        val match = clockPattern.matchEntire(value) ?: return null
        if (match.groups[1] == null && match.groups[2] == null && match.groups[3] == null) return null
        val hours = clockComponent(match.groups[1]) ?: return null
        val minutes = clockComponent(match.groups[2]) ?: return null
        val seconds = clockComponent(match.groups[3]) ?: return null
        val totalSeconds = try {
            Math.addExact(
                Math.addExact(Math.multiplyExact(hours, 3_600L), Math.multiplyExact(minutes, 60L)),
                seconds
            )
        } catch (_: ArithmeticException) {
            return null
        }
        return secondsToMillis(totalSeconds)
    }

    private fun extractVideoId(host: String, uri: URI, parameters: List<Pair<String, String>>): String? {
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        return when {
            host == "youtu.be" -> segments.singleOrNull()
            host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" -> when {
                segments.size == 1 && segments[0].equals("watch", ignoreCase = true) ->
                    parameters.firstOrNull { it.first == "v" }?.second
                segments.size == 2 && segments[0].equals("shorts", ignoreCase = true) -> segments[1]
                segments.size == 2 && segments[0].equals("embed", ignoreCase = true) -> segments[1]
                else -> null
            }
            else -> null
        }
    }

    private fun isHttpScheme(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme == "http" || scheme == "https"
    }

    /**
     * A captured clock component holds only its digits; the unit letter is outside the capture
     * group, so the value is parsed as-is (`1m30s` captures `1` and `30`).
     */
    private fun clockComponent(group: MatchGroup?): Long? {
        if (group == null) return 0L
        return group.value.toLongOrNull()
    }

    private fun secondsToMillis(seconds: Long): Long? {
        if (seconds < 0) return null
        return try {
            Math.multiplyExact(seconds, 1_000L)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /**
     * The first link token in the text: any scheme token or a bare token that starts with a
     * YouTube host, whichever occurs first. A link is allowed to be followed by closing
     * punctuation or Chinese prose; the token itself is ASCII only. A non-http scheme is returned
     * as written so the caller refuses it instead of silently re-reading it as https.
     */
    private fun firstLinkToken(text: String): String? {
        val scheme = anySchemePattern.find(text)
        val bare = bareLinkPattern.find(text)
        return when {
            scheme != null && (bare == null || scheme.range.first <= bare.range.first) ->
                readToken(text, scheme.range.first, prependScheme = false)
            bare != null -> readToken(text, bare.range.first, prependScheme = true)
            else -> null
        }
    }

    private fun readToken(text: String, start: Int, prependScheme: Boolean): String {
        var end = start
        while (end < text.length) {
            val character = text[end]
            if (character.isWhitespace() || character in TOKEN_TERMINATORS ||
                character.code !in 33..126
            ) {
                break
            }
            end++
        }
        val token = text.substring(start, end).trimEnd(*trailingPunctuation)
        return if (prependScheme) "https://$token" else token
    }
}
