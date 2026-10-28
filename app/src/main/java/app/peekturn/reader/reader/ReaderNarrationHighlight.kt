package app.peekturn.reader.reader

import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates

internal val NARRATION_HIGHLIGHT_TINT = 0xFFFFC107.toInt()
internal const val NARRATION_HIGHLIGHT_ALPHA = 0.35

/** Readium's template alpha overrides the tint's alpha; changing the color alone has no effect. */
internal fun readerNarrationDecorationTemplates(): HtmlDecorationTemplates =
    HtmlDecorationTemplates.defaultTemplates().apply {
        this[Decoration.Style.Highlight::class] = HtmlDecorationTemplate.highlight(
            defaultTint = NARRATION_HIGHLIGHT_TINT,
            lineWeight = 2,
            cornerRadius = 3,
            alpha = NARRATION_HIGHLIGHT_ALPHA
        )
    }
