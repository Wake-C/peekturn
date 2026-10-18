package app.peekturn.reader.reader

import androidx.annotation.StringRes
import app.peekturn.reader.R
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.ReaderPreferences

/**
 * Four equal entries: contents, typography, options and read aloud. Search belongs to the
 * contents sheet because a query always runs against the whole book, not the visible chapter.
 */
internal enum class ReaderToolbarEntry(@StringRes val labelRes: Int) {
    CONTENTS(R.string.reader_control_contents),
    TYPOGRAPHY(R.string.reader_control_typography),
    OPTIONS(R.string.reader_control_options),
    READ_ALOUD(R.string.reader_control_read_aloud)
}

internal val readerToolbarEntries: List<ReaderToolbarEntry> = ReaderToolbarEntry.entries.toList()

/**
 * The native selection menu. Quick Explain already owns automatic explanation of a release, so
 * the explicit actions stay the same in both modes; narration is started from the reader menu.
 */
internal enum class ReaderSelectionAction { EXPLAIN, DICTIONARY, COPY }

internal fun readerSelectionActions(): List<ReaderSelectionAction> =
    listOf(ReaderSelectionAction.EXPLAIN, ReaderSelectionAction.DICTIONARY, ReaderSelectionAction.COPY)

/** Reading behaviour owned by the options sheet, never by the typography draft. */
internal data class ReaderOptions(
    val immersive: Boolean,
    val scroll: Boolean,
    val quickExplain: Boolean,
    val autoPronounce: Boolean
)

internal fun AppSettings.readerOptions() = ReaderOptions(
    immersive = reading.immersive,
    scroll = reading.scroll,
    quickExplain = quickExplain,
    autoPronounce = autoPronounce
)

/** One options-sheet switch; only the named field changes. */
internal enum class ReaderOption {
    IMMERSIVE,
    SCROLL,
    QUICK_EXPLAIN,
    AUTO_PRONOUNCE
}

/**
 * Applies one switch to the latest settings inside the store transform. A toggle carries only its
 * own field, so a queued change cannot overwrite another toggle or a typography edit.
 */
internal fun AppSettings.withReaderOption(option: ReaderOption, enabled: Boolean): AppSettings =
    when (option) {
        ReaderOption.IMMERSIVE -> copy(reading = reading.copy(immersive = enabled))
        ReaderOption.SCROLL -> copy(reading = reading.copy(scroll = enabled))
        ReaderOption.QUICK_EXPLAIN -> copy(quickExplain = enabled)
        ReaderOption.AUTO_PRONOUNCE -> copy(autoPronounce = enabled)
    }

/**
 * A typography draft carries stale switch values while the user drags sliders. Applying it must
 * keep the behaviour settings authoritative: only type and layout fields come from [reading].
 */
internal fun AppSettings.withTypography(reading: ReaderPreferences): AppSettings = copy(
    reading = reading.copy(immersive = this.reading.immersive, scroll = this.reading.scroll)
)
