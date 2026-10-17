@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.margin.reader.reader

import app.margin.reader.model.ReaderPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

/** Persistence uses percentages; Readium's public API uses multipliers. */
internal fun ReaderPreferences.toEpub(appTheme: String) = EpubPreferences(
    fontSize = fontSize / 100.0,
    fontFamily = FontFamily(fontFamily),
    lineHeight = lineHeight,
    pageMargins = pageMargins,
    paragraphSpacing = paragraphSpacing,
    scroll = scroll,
    publisherStyles = false,
    typeScale = 1.2,
    backgroundColor = Color(android.graphics.Color.parseColor(
        when (appTheme) { "light" -> "#FAF9F4"; "warm" -> "#EEE5D3"; else -> "#171E23" }
    )),
    textColor = Color(android.graphics.Color.parseColor(if (appTheme == "dark") "#E9E8DF" else "#262B2C")),
    theme = when (appTheme) { "light" -> Theme.LIGHT; "warm" -> Theme.SEPIA; else -> Theme.DARK }
)

internal data class ReaderTextInsets(val topDp: Double, val bottomDp: Double)

// These are actual viewport distances, not multipliers or additions to Readium's default padding.
internal fun readerTextInsets(preferences: ReaderPreferences) = ReaderTextInsets(
    preferences.topPaddingDp.coerceAtLeast(16.0), preferences.bottomPaddingDp.coerceAtLeast(24.0)
)

internal fun chapterPositionLabel(scroll: Boolean, page: Int, total: Int, progression: Double?): String =
    if (scroll) progression?.let { "Chapter ${(it.coerceIn(0.0, 1.0) * 100).toInt()}%" } ?: "Chapter"
    else if (page in 1..total) "$page / $total" else ""
