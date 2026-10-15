package app.margin.reader.reader

/** Physical pixel bounds passed from the Readium selection adapter to the popup. */
internal data class WindowSelectionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

// Readium 3.1 adjustedToViewport() adds page-root padding only to top, not bottom.
// Repair only the missing edge before translating the whole rectangle to window pixels.
internal fun readium31SelectionAnchor(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    navigatorWindowX: Int,
    navigatorWindowY: Int,
    pageTopPadding: Int
): WindowSelectionBounds = WindowSelectionBounds(
    left + navigatorWindowX,
    top + navigatorWindowY,
    right + navigatorWindowX,
    bottom + pageTopPadding + navigatorWindowY
)
