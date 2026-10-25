package app.peekturn.reader.learning

internal data class PopupGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

/** Window-relative pixels. The answer grows upward from a stable bottom edge, not the selection. */
internal fun learningPopupGeometry(
    windowWidth: Int,
    windowHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
    inset: Int,
    bottomInset: Int = 0
): PopupGeometry {
    val windowW = windowWidth.coerceAtLeast(1)
    val windowH = windowHeight.coerceAtLeast(1)
    val margin = inset.coerceIn(0, (minOf(windowW, windowH) - 1) / 2)
    val bottom = bottomInset.coerceIn(0, (windowH - 2 * margin - 1).coerceAtLeast(0))
    val width = requestedWidth.coerceIn(1, (windowW - 2 * margin).coerceAtLeast(1))
    val height = requestedHeight.coerceIn(1, (windowH - 2 * margin - bottom).coerceAtLeast(1))
    return PopupGeometry(
        x = (windowW - width) / 2,
        y = windowH - bottom - margin - height,
        width = width,
        height = height
    )
}

/**
 * Window-relative pixels for a layer that belongs to the whole screen rather than to a text edge:
 * the answer is centered in the area that excludes the insets, so a video frame behind it never
 * decides its width or position.
 */
internal fun centeredLearningPopupGeometry(
    windowWidth: Int,
    windowHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
    inset: Int,
    bottomInset: Int = 0
): PopupGeometry {
    val windowW = windowWidth.coerceAtLeast(1)
    val windowH = windowHeight.coerceAtLeast(1)
    val margin = inset.coerceIn(0, (minOf(windowW, windowH) - 1) / 2)
    val bottom = bottomInset.coerceIn(0, (windowH - 2 * margin - 1).coerceAtLeast(0))
    val width = requestedWidth.coerceIn(1, (windowW - 2 * margin).coerceAtLeast(1))
    val available = (windowH - 2 * margin - bottom).coerceAtLeast(1)
    val height = requestedHeight.coerceIn(1, available)
    return PopupGeometry(
        x = (windowW - width) / 2,
        y = margin + (available - height) / 2,
        width = width,
        height = height
    )
}
