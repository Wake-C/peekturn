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
    minimumHeight: Int = 420
): PopupGeometry {
    val availableWidth = (windowWidth - 2 * inset).coerceAtLeast(1)
    val availableHeight = (windowHeight - 2 * inset).coerceAtLeast(1)
    val width = requestedWidth.coerceIn(1, availableWidth)
    val above = anchor?.let { (it.top - 2 * inset).coerceIn(0, availableHeight) } ?: 0
    val below = anchor?.let { (windowHeight - it.bottom - 2 * inset).coerceIn(0, availableHeight) } ?: 0
    // Prefer the source side only when it leaves a genuinely useful answer body.
    val placeBelow = below >= above
    val sideCapacity = if (placeBelow) below else above
    val fitsSide = anchor != null && sideCapacity >= minimumHeight
    val heightLimit = if (fitsSide) sideCapacity else availableHeight
    val height = requestedHeight.coerceIn(1, heightLimit.coerceAtLeast(1))
    val maxX = (windowWidth - width - inset).coerceAtLeast(inset)
    val maxY = (windowHeight - height - inset).coerceAtLeast(inset)
    val x = anchor?.let { (it.left + it.right) / 2 - width / 2 } ?: ((windowWidth - width) / 2)
    val y = when {
        !fitsSide || anchor == null -> (windowHeight - height) / 2
        placeBelow -> anchor.bottom + inset
        else -> anchor.top - inset - height
    }
    // Short sides intentionally overlap the source: the answer is the primary content.
    return PopupGeometry(x.coerceIn(inset, maxX), y.coerceIn(inset, maxY), width, height)
}
