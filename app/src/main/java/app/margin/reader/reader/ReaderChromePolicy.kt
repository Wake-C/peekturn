package app.margin.reader.reader

import app.margin.reader.model.ReaderPreferences

/** Viewport coordinates use the same 30% horizontal edge zones as Readium's adapter. */
internal fun isReaderCenterTap(x: Float, width: Float): Boolean =
    width > 0 && x > maxOf(80f, width * 0.3f) && x < width - maxOf(80f, width * 0.3f)

internal data class ReaderChromeState(val visible: Boolean = false) {
    fun tap(x: Float, width: Float): ReaderChromeState =
        if (isReaderCenterTap(x, width)) copy(visible = !visible) else this

    fun back(hasLookup: Boolean, hasPanel: Boolean): ReaderBackAction = when {
        hasLookup -> ReaderBackAction.DismissLookup
        hasPanel -> ReaderBackAction.DismissPanel
        visible -> ReaderBackAction.HideChrome
        else -> ReaderBackAction.Exit
    }
}

internal enum class ReaderBackAction { DismissLookup, DismissPanel, HideChrome, Exit }

internal data class ReaderSystemBars(val statusVisible: Boolean, val navigationVisible: Boolean, val darkIcons: Boolean)

// Controls and focusable overlays never alter the system-bar preference or renderer dimensions.
internal fun readerSystemBars(preferences: ReaderPreferences, appTheme: String) = ReaderSystemBars(
    statusVisible = !preferences.immersive,
    navigationVisible = !preferences.immersive,
    darkIcons = appTheme != "dark"
)
