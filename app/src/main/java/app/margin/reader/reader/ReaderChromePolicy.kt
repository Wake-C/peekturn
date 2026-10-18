package app.margin.reader.reader

import app.margin.reader.model.ReaderPreferences

/** Viewport coordinates use the same 30% horizontal edge zones as Readium's adapter. */
internal fun isReaderCenterTap(x: Float, width: Float): Boolean =
    width > 0 && x > maxOf(80f, width * 0.3f) && x < width - maxOf(80f, width * 0.3f)

/** The center zone only toggles controls; page turns stay in the native edge zones. */
internal enum class ReaderTapAction { Ignore, ToggleChrome, PreviousPage, NextPage }

internal fun readerTapAction(x: Float, width: Float): ReaderTapAction = when {
    isReaderCenterTap(x, width) -> ReaderTapAction.ToggleChrome
    width <= 0 || !x.isFinite() || x < 0 || x > width -> ReaderTapAction.Ignore
    x < width / 2 -> ReaderTapAction.PreviousPage
    else -> ReaderTapAction.NextPage
}

internal data class ReaderChromeState(val visible: Boolean = false) {
    fun back(hasLookup: Boolean, hasPanel: Boolean): ReaderBackAction = when {
        hasLookup -> ReaderBackAction.DismissLookup
        hasPanel -> ReaderBackAction.DismissPanel
        visible -> ReaderBackAction.HideChrome
        else -> ReaderBackAction.Exit
    }
}

internal enum class ReaderBackAction { DismissLookup, DismissPanel, HideChrome, Exit }

/**
 * Search is reached from the contents sheet, so Back and Close return there instead of dropping
 * the whole panel layer. Any other panel has no intermediate step.
 */
internal fun readerPanelBackTarget(panel: String?): String? =
    if (panel == "search") "contents" else null

/**
 * Controls are overlays, not layout participants: all three surfaces can be derived from the
 * chrome flag and the narration state without changing the reading viewport.
 */
internal fun readerMenuVisible(chrome: ReaderChromeState) = chrome.visible

internal fun readerNarrationCardVisible(chrome: ReaderChromeState, narrationActive: Boolean) =
    chrome.visible && narrationActive

internal fun readerCompactTransportVisible(chrome: ReaderChromeState, narrationActive: Boolean) =
    !chrome.visible && narrationActive

internal data class ReaderSystemBars(val statusVisible: Boolean, val navigationVisible: Boolean, val darkIcons: Boolean)

// Controls and focusable overlays never alter the system-bar preference. Chrome is an overlay in
// every state, so no control visibility change resizes the publication viewport.
internal fun readerSystemBars(preferences: ReaderPreferences, appTheme: String) = ReaderSystemBars(
    statusVisible = !preferences.immersive,
    navigationVisible = !preferences.immersive,
    // Light system-bar icons are needed on any dark canvas, including true black.
    darkIcons = appTheme != "dark" && appTheme != "oled"
)
