package app.peekturn.reader.diagnostics

import java.nio.charset.StandardCharsets

/** The explicit result for a runtime identity field that is not identifier-shaped. */
internal const val DIAGNOSTIC_INVALID_IDENTIFIER = "[invalid-identifier]"

private const val MAX_IDENTIFIER_CHARS = 256
private const val TRUNCATED_SUFFIX = " [truncated]"

private val urlPattern = Regex("(?i)\\b(?:https?|ftp|file)://\\S+")
private val webPattern = Regex("(?i)\\bwww\\.\\S+")
private val quotedPathPattern = Regex("\"[^\"]*[\\\\/][^\"]*\"")
private val posixPathPattern = Regex(
    "(^|[\\s=:\"'(\\[])(?:~[/\\\\][^\\s,;\"')\\]]+|/[^\\s,;\"')\\]]+/[^\\s,;\"')\\]]+)"
)
private val windowsPathPattern = Regex("(?i)(^|[\\s=:\"'(\\[])[a-z]:[\\\\/][^\\s,;\"')\\]]+")
private val uncPathPattern = Regex("(^|[\\s=:\"'(\\[])\\\\\\\\[^\\s,;\"')\\]]+")
private val bearerPattern = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+")
private val credentialPattern = Regex(
    "(?i)\\b(api[_-]?key|access[_-]?key|secret[_-]?key|private[_-]?key|client[_-]?secret|secret|token|password|passwd|credential|authorization|auth|key)\\b\\s*[=:]\\s*\\S+"
)
private val javaIdentifierPattern = Regex("[A-Za-z_\u0024][A-Za-z0-9_\u0024]*")

/**
 * Canonical, field-level metadata for support logs. Line breaks and controls collapse, obvious
 * URLs, paths and credential-bearing values are redacted, and the result is bounded by UTF-8 bytes.
 * Ordinary engine, voice and version punctuation is preserved, so callers can apply this to each
 * individual field instead of discarding an otherwise useful structured report.
 */
internal fun diagnosticMetadata(value: String, limit: Int = 160): String {
    var sanitized = normalizeText(value)
    sanitized = urlPattern.replace(sanitized, "[redacted-url]")
    sanitized = webPattern.replace(sanitized, "[redacted-url]")
    sanitized = quotedPathPattern.replace(sanitized, "[redacted-path]")
    sanitized = posixPathPattern.replace(sanitized, "\$1[redacted-path]")
    sanitized = windowsPathPattern.replace(sanitized, "\$1[redacted-path]")
    sanitized = uncPathPattern.replace(sanitized, "\$1[redacted-path]")
    sanitized = bearerPattern.replace(sanitized, "[redacted]")
    sanitized = credentialPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}=[redacted]"
    }
    return boundedUtf8(sanitized, limit.coerceAtLeast(1))
}

/**
 * Validates a Java-shaped runtime identity such as a stack frame's declaring class or method.
 * Dot-separated ASCII identifier segments and `$` are accepted, as are the two special method names.
 * Anything else, including paths, URLs, whitespace or credential text, becomes an explicit marker
 * rather than being echoed. Bounded to 256 characters.
 */
internal fun diagnosticIdentifier(value: String): String {
    val trimmed = value.trim()
    if (trimmed == "<init>" || trimmed == "<clinit>") return trimmed
    if (trimmed.isEmpty() || trimmed.length > MAX_IDENTIFIER_CHARS) return DIAGNOSTIC_INVALID_IDENTIFIER
    val segments = trimmed.split('.')
    return if (segments.all { javaIdentifierPattern.matches(it) }) trimmed else DIAGNOSTIC_INVALID_IDENTIFIER
}

/** One persisted record can never exceed this module's per-record bound. */
internal fun boundedUtf8(text: String, maxBytes: Int): String {
    if (text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return text
    val suffixBytes = TRUNCATED_SUFFIX.toByteArray(StandardCharsets.UTF_8).size
    val budget = (maxBytes - suffixBytes).coerceAtLeast(0)
    val builder = StringBuilder(text.length.coerceAtMost(maxBytes))
    var used = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (used + size > budget) break
        builder.appendCodePoint(codePoint)
        used += size
        index += Character.charCount(codePoint)
    }
    return builder.append(TRUNCATED_SUFFIX).toString()
}

/** Collapses every control character and line break into single spaces. */
internal fun normalizeText(value: String): String {
    val builder = StringBuilder(value.length)
    var spaced = false
    for (character in value) {
        when {
            character.isWhitespace() -> {
                if (!spaced && builder.isNotEmpty()) builder.append(' ')
                spaced = true
            }
            Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt() -> Unit
            else -> {
                builder.append(character)
                spaced = false
            }
        }
    }
    return builder.toString().trim()
}
