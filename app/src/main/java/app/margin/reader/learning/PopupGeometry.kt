package app.margin.reader.learning

internal data class PopupSelectionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class PopupGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

/** Window-relative pixels. Shared by measuring and positioning the anchored learning surface. */
internal fun learningPopupGeometry(
    windowWidth: Int,
    windowHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
    anchor: PopupSelectionBounds?,
    inset: Int,
    minimumHeight: Int = 260
): PopupGeometry {
    val availableWidth = (windowWidth - 2 * inset).coerceAtLeast(1)
    val availableHeight = (windowHeight - 2 * inset).coerceAtLeast(1)
    val width = requestedWidth.coerceIn(1, availableWidth)
    val above = anchor?.let { (it.top - 2 * inset).coerceIn(0, availableHeight) } ?: 0
    val below = anchor?.let { (windowHeight - it.bottom - 2 * inset).coerceIn(0, availableHeight) } ?: 0
    // Select by available space, never by the current streamed content height.
    val placeBelow = below >= above
    val sideCapacity = if (placeBelow) below else above
    val heightLimit = if (anchor != null && sideCapacity >= minimumHeight) sideCapacity else availableHeight
    val height = requestedHeight.coerceIn(1, heightLimit.coerceAtLeast(1))
    val maxX = (windowWidth - width - inset).coerceAtLeast(inset)
    val maxY = (windowHeight - height - inset).coerceAtLeast(inset)
    val x = anchor?.let { (it.left + it.right) / 2 - width / 2 } ?: ((windowWidth - width) / 2)
    val y = when {
        anchor == null -> (windowHeight - height) / 2
        placeBelow -> anchor.bottom + inset
        else -> anchor.top - inset - height
    }
    // If neither side fits the fixed controls plus a useful body, bounded overlap is unavoidable.
    return PopupGeometry(x.coerceIn(inset, maxX), y.coerceIn(inset, maxY), width, height)
}
