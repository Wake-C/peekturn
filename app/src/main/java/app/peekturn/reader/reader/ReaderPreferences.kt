@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.peekturn.reader.reader

import kotlin.math.roundToInt

import androidx.compose.ui.graphics.toArgb
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.ui.peekturnColorScheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

/**
 * Persistence uses percentages; Readium's public API uses multipliers. Paper and ink come from the
 * same scheme the chrome renders, so a theme added to Theme.kt cannot leave the book page behind.
 */
internal fun ReaderPreferences.toEpub(appTheme: String) = EpubPreferences(
    fontSize = fontSize / 100.0,
    fontFamily = FontFamily(readerFontFor(fontFamily).familyName),
    // Readium 3.1 maps its spacing preference to rem by dividing by two.
    letterSpacing = letterSpacing.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.0, 1.0)?.times(2.0),
    lineHeight = lineHeight,
    pageMargins = pageMargins,
    paragraphSpacing = paragraphSpacing,
    scroll = scroll,
    publisherStyles = false,
    typeScale = 1.2,
    backgroundColor = Color(peekturnColorScheme(appTheme).background.toArgb()),
    textColor = Color(peekturnColorScheme(appTheme).onBackground.toArgb()),
    theme = when (appTheme) {
        "warm" -> Theme.SEPIA
        "dark", "oled" -> Theme.DARK
        else -> Theme.LIGHT
    }
)

internal data class ReaderTextInsets(val topDp: Double, val bottomDp: Double)

/**
 * Everything that changes how much text a page holds, including letter spacing. Page-density
 * measurements are keyed by this, so a spacing or typeface change cannot reuse a stale page count.
 * Legacy ids resolve to the drawn family, so "serif" and "literata" share one measurement.
 */
internal fun readerLayoutSignature(preferences: ReaderPreferences, width: Int, height: Int): String = listOf(
    readerFontFor(preferences.fontFamily).id, preferences.fontSize, preferences.letterSpacing,
    preferences.lineHeight, preferences.paragraphSpacing, preferences.pageMargins,
    preferences.topPaddingDp, preferences.bottomPaddingDp, width, height
).joinToString("|")

// These are actual viewport distances, not multipliers or additions to Readium's default padding.
internal fun readerTextInsets(preferences: ReaderPreferences) = ReaderTextInsets(
    preferences.topPaddingDp.coerceAtLeast(16.0), preferences.bottomPaddingDp.coerceAtLeast(24.0)
)

/** A chapter needs at least this many pages before its density represents a full page. */
internal const val PAGE_DENSITY_MIN_PAGES = 5

/**
 * Book-wide page position for the current layout. The reader only ever paginates one resource, so the
 * position counts the text before this resource at the measured characters-per-page density and adds
 * the exact page within it; the total applies the same density to the book's text.
 */
internal fun bookPageCounter(
    charsBefore: Int,
    page: Int,
    totalChars: Int,
    charsPerPage: Double
): Pair<Int, Int>? {
    if (page < 1 || totalChars < 1 || !charsPerPage.isFinite() || charsPerPage <= 0) return null
    val position = (charsBefore / charsPerPage).roundToInt() + page
    val total = (totalChars / charsPerPage).roundToInt().coerceAtLeast(position)
    return position to total
}

internal fun chapterPositionLabel(scroll: Boolean, page: Int, total: Int, progression: Double?): String =
    if (scroll) progression?.let { "Chapter ${(it.coerceIn(0.0, 1.0) * 100).toInt()}%" } ?: "Chapter"
    else if (page in 1..total) "$page / $total" else ""

private val markupBlocks = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val markupTags = Regex("<[^>]*>")

/** Non-whitespace book text characters in an (X)HTML resource, without markup or whitespace. */
internal fun textCharacters(bytes: ByteArray): Int =
    markupTags.replace(markupBlocks.replace(String(bytes, Charsets.UTF_8), " "), " ").count { !it.isWhitespace() }
