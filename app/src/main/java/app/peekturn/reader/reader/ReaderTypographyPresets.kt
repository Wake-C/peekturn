package app.peekturn.reader.reader

import app.peekturn.reader.model.ReaderPreferences
import kotlin.math.abs

/** UI presets map onto the existing Readium preferences; stored custom values remain untouched. */
internal enum class ReaderTypographyPreset(private val values: List<Double>) {
    FONT_SIZE(listOf(90.0, 100.0, 120.0, 150.0)),
    LINE_HEIGHT(listOf(1.4, 1.6, 1.9)),
    SIDE_MARGINS(listOf(0.6, 1.0, 1.5)),
    PARAGRAPH_SPACING(listOf(0.4, 0.7, 1.1)),
    LETTER_SPACING(listOf(0.0, 0.05, 0.12)),
    VERTICAL_PADDING(listOf(16.0, 32.0, 56.0));

    val count: Int get() = values.size

    fun selected(preferences: ReaderPreferences): Int? {
        val value = when (this) {
            FONT_SIZE -> preferences.fontSize
            LINE_HEIGHT -> preferences.lineHeight
            SIDE_MARGINS -> preferences.pageMargins
            PARAGRAPH_SPACING -> preferences.paragraphSpacing
            LETTER_SPACING -> preferences.letterSpacing
            VERTICAL_PADDING -> preferences.topPaddingDp
        }
        // Old slider values were floats serialized as doubles. Ignore only that rounding noise.
        return values.indices.firstOrNull { index ->
            abs(value - values[index]) < 0.000001 &&
                (this != VERTICAL_PADDING || abs(preferences.bottomPaddingDp - bottomPadding[index]) < 0.000001)
        }
    }

    fun apply(preferences: ReaderPreferences, index: Int): ReaderPreferences {
        require(index in values.indices)
        val value = values[index]
        return when (this) {
            FONT_SIZE -> preferences.copy(fontSize = value)
            LINE_HEIGHT -> preferences.copy(lineHeight = value)
            SIDE_MARGINS -> preferences.copy(pageMargins = value)
            PARAGRAPH_SPACING -> preferences.copy(paragraphSpacing = value)
            LETTER_SPACING -> preferences.copy(letterSpacing = value)
            VERTICAL_PADDING -> preferences.copy(topPaddingDp = value, bottomPaddingDp = bottomPadding[index])
        }
    }

    companion object {
        private val bottomPadding = listOf(24.0, 48.0, 80.0)
    }
}
