package app.peekturn.reader.reader

import android.view.KeyEvent
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
 * Quick Explain keeps the native range and handles without offering a redundant action toolbar.
 * Narration is started from the reader menu.
 */
internal enum class ReaderSelectionAction { EXPLAIN, DICTIONARY, COPY }

internal fun readerSelectionActions(quickExplain: Boolean): List<ReaderSelectionAction> =
    if (quickExplain) emptyList()
    else listOf(ReaderSelectionAction.EXPLAIN, ReaderSelectionAction.DICTIONARY, ReaderSelectionAction.COPY)

/** Reading behaviour owned by the options sheet, never by the typography draft. */
internal data class ReaderOptions(
    val immersive: Boolean,
    val scroll: Boolean,
    val quickExplain: Boolean,
    val autoPronounce: Boolean,
    val volumeKeysNarration: Boolean
)

internal fun AppSettings.readerOptions() = ReaderOptions(
    immersive = reading.immersive,
    scroll = reading.scroll,
    quickExplain = quickExplain,
    autoPronounce = autoPronounce,
    volumeKeysNarration = volumeKeysNarration
)

/** One options-sheet switch; only the named field changes. */
internal enum class ReaderOption {
    IMMERSIVE,
    SCROLL,
    QUICK_EXPLAIN,
    AUTO_PRONOUNCE,
    VOLUME_KEYS_NARRATION
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
        ReaderOption.VOLUME_KEYS_NARRATION -> copy(volumeKeysNarration = enabled)
    }

/**
 * A typography draft carries stale switch values while the user chooses appearance presets. Applying it must
 * keep the behaviour settings authoritative: only type and layout fields come from [reading].
 */
internal fun AppSettings.withTypography(reading: ReaderPreferences): AppSettings = copy(
    reading = reading.copy(immersive = this.reading.immersive, scroll = this.reading.scroll)
)

internal enum class NarrationVolumeKeyAction { PREVIOUS, NEXT, CONSUME }

internal fun narrationVolumeKeyAction(
    enabled: Boolean,
    narrationActive: Boolean,
    keyCode: Int,
    eventAction: Int,
    repeatCount: Int
): NarrationVolumeKeyAction? {
    if (!enabled || !narrationActive) return null
    val direction = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> NarrationVolumeKeyAction.PREVIOUS
        KeyEvent.KEYCODE_VOLUME_DOWN -> NarrationVolumeKeyAction.NEXT
        else -> return null
    }
    // Consume key-up and held-key repeats too, without navigating more than once per press.
    return if (eventAction == KeyEvent.ACTION_DOWN && repeatCount == 0) direction
    else NarrationVolumeKeyAction.CONSUME
}
