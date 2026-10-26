package app.peekturn.reader.reader

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import app.peekturn.reader.model.ReaderPreferences

/** Native text and font previews use the same packaged face declarations as Readium. */
@Composable
internal fun rememberReaderFontFamily(id: String): FontFamily {
    val assets = LocalContext.current.assets
    val font = readerFontFor(id)
    return remember(assets, font.id) {
        if (font.faces.isEmpty()) FontFamily.SansSerif else {
            val face = font.faces.first { !it.italic && 400 in it.weights }
            val builder = Typeface.Builder(assets, face.path).setWeight(400).setFallback(font.cjkFallback)
            if (face.weights.first != face.weights.last) builder.setFontVariationSettings("'wght' 400")
            FontFamily(builder.build())
        }
    }
}

internal data class ReaderTextMetrics(
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val letterSpacingEm: Float,
    val paragraphGapSp: Float,
    val sidePaddingDp: Float,
    val topPaddingDp: Float,
    val bottomPaddingDp: Float,
    val maximumWidthSp: Float
)

/**
 * Readium's shipped reflow CSS uses a 16px root, 1rem body text, rem paragraph spacing,
 * a 40rem maximum line box, and 20/30/40/50px gutters at 0/35/45/75em viewport widths.
 * This is the native adapter for the same saved preferences, not another preset system.
 * EPUB pagination, publisher structure and visible-word anchoring remain entirely Readium-owned.
 */
internal fun readerTextMetrics(preferences: ReaderPreferences, viewportWidthDp: Float): ReaderTextMetrics {
    val size = 16f * (preferences.fontSize.takeIf { it.isFinite() && it > 0 } ?: 100.0).toFloat() / 100f
    val gutter = when {
        viewportWidthDp >= 1200f -> 50f
        viewportWidthDp >= 720f -> 40f
        viewportWidthDp >= 560f -> 30f
        else -> 20f
    }
    val insets = readerTextInsets(preferences)
    return ReaderTextMetrics(
        fontSizeSp = size,
        lineHeightSp = size * (preferences.lineHeight.takeIf { it.isFinite() && it > 0 } ?: 1.6).toFloat(),
        letterSpacingEm = (preferences.letterSpacing.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
        paragraphGapSp = size * (preferences.paragraphSpacing.takeIf { it.isFinite() && it >= 0 } ?: 0.7).toFloat(),
        sidePaddingDp = gutter * (preferences.pageMargins.takeIf { it.isFinite() && it >= 0 } ?: 1.0).toFloat(),
        topPaddingDp = insets.topDp.toFloat(),
        bottomPaddingDp = insets.bottomDp.toFloat(),
        maximumWidthSp = size * 40f
    )
}
