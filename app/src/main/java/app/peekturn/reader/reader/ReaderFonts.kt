@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.peekturn.reader.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily

/** One packaged font file and the weights/styles it genuinely contains. */
internal data class ReaderFontFace(val path: String, val italic: Boolean, val weights: IntRange)

internal data class ReaderFont(
    val id: String,
    val familyName: String,
    val serif: Boolean,
    val faces: List<ReaderFontFace>
) {
    /**
     * The generic family after the bundled face. A Latin book with CJK text therefore falls back
     * through the platform's own serif/sans CJK font instead of showing missing glyphs.
     */
    val cjkFallback: String get() = if (serif) "serif" else "sans-serif"

    /**
     * Readium 3.1 quotes every family in `--USER__fontFamily`, which turns "serif" into a request
     * for a font literally named serif. The reader also sets this unquoted stack on the root
     * element, so the declared face applies and the generic fallback keeps working.
     */
    val cssStack: String get() = if (id == "system") "system-ui, sans-serif" else "\"$familyName\", $cjkFallback"
}

/**
 * System typography plus five shipped reading typefaces. Packaged entries have real regular and
 * italic faces; the system entry delegates to WebView's platform font without a bundled face.
 */
internal object ReaderFonts {
    /** Served through the navigator's `https://readium/assets/` handler. */
    const val SERVED_ASSET_PATTERN = "fonts/.*"

    val servedAssetPatterns: List<String> = listOf(SERVED_ASSET_PATTERN)

    private fun variable(path: String, italic: Boolean, weights: IntRange) =
        ReaderFontFace(path, italic, weights)

    private fun static(path: String, italic: Boolean, weight: Int) =
        ReaderFontFace(path, italic, weight..weight)

    val system = ReaderFont("system", "system-ui", serif = false, faces = emptyList())

    val literata = ReaderFont(
        id = "literata",
        familyName = "Literata",
        serif = true,
        faces = listOf(
            variable("fonts/literata-regular.ttf", italic = false, 200..900),
            variable("fonts/literata-italic.ttf", italic = true, 200..900)
        )
    )

    val sourceSerif4 = ReaderFont(
        id = "source-serif-4",
        familyName = "Source Serif 4",
        serif = true,
        faces = listOf(
            variable("fonts/source-serif-4-regular.ttf", italic = false, 200..900),
            variable("fonts/source-serif-4-italic.ttf", italic = true, 200..900)
        )
    )

    val notoSerif = ReaderFont(
        id = "noto-serif",
        familyName = "Noto Serif",
        serif = true,
        faces = listOf(
            variable("fonts/noto-serif-regular.ttf", italic = false, 100..900),
            variable("fonts/noto-serif-italic.ttf", italic = true, 100..900)
        )
    )

    val atkinsonHyperlegible = ReaderFont(
        id = "atkinson-hyperlegible",
        familyName = "Atkinson Hyperlegible",
        serif = false,
        faces = listOf(
            static("fonts/atkinson-hyperlegible-regular.ttf", italic = false, 400),
            static("fonts/atkinson-hyperlegible-italic.ttf", italic = true, 400),
            static("fonts/atkinson-hyperlegible-bold.ttf", italic = false, 700),
            static("fonts/atkinson-hyperlegible-bold-italic.ttf", italic = true, 700)
        )
    )

    val sourceSans3 = ReaderFont(
        id = "source-sans-3",
        familyName = "Source Sans 3",
        serif = false,
        faces = listOf(
            variable("fonts/source-sans-3-regular.ttf", italic = false, 200..900),
            variable("fonts/source-sans-3-italic.ttf", italic = true, 200..900)
        )
    )

    val all: List<ReaderFont> = listOf(system, literata, sourceSerif4, notoSerif, atkinsonHyperlegible, sourceSans3)

    private val byId = all.associateBy { it.id }

    /** Existing installs stored "serif"/"sans-serif"; both stay readable through a real family. */
    private val legacy = mapOf("serif" to literata, "sans-serif" to sourceSans3)

    fun resolve(id: String?): ReaderFont = byId[id] ?: legacy[id] ?: system
}

internal fun readerFontFor(id: String?): ReaderFont = ReaderFonts.resolve(id)

/** Registers the packaged faces; without this Readium only knows their family names. */
internal fun EpubNavigatorFragment.Configuration.registerReaderFonts() {
    ReaderFonts.all.forEach { font ->
        if (font.faces.isEmpty()) return@forEach
        addFontFamilyDeclaration(
            fontFamily = FontFamily(font.familyName),
            alternates = listOf(if (font.serif) FontFamily.SERIF else FontFamily.SANS_SERIF)
        ) {
            font.faces.forEach { face ->
                addFontFace {
                    addSource(face.path)
                    setFontStyle(if (face.italic) FontStyle.ITALIC else FontStyle.NORMAL)
                    if (face.weights.first == face.weights.last) {
                        setFontWeight(
                            when (face.weights.first) {
                                700 -> FontWeight.BOLD
                                else -> FontWeight.NORMAL
                            }
                        )
                    } else {
                        setFontWeight(face.weights)
                    }
                }
            }
        }
    }
}
