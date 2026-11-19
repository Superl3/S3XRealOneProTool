package com.raphael.handmouse.remote

/**
 * Where the Remote's collapsed (mini) button sits and which corner the expanded panel then opens at
 * (spec 2026-11-17 section S-7). Pure (no Android).
 *
 * The button's position is kept as its centre in fractions (0..1) of display 0 in its natural
 * orientation (rotation 0), so it stays at the same spot on the glass whatever the display rotation
 * or the panel's hold. [naturalToDisplay] places it on the screen as it is turned now; the corner the
 * panel opens at is chosen in the panel's own upright frame (the hold), the space
 * [RemoteDockPrefs.corner] lives in.
 */
internal object MiniButtonMath {
    /**
     * The fractions of the natural orientation for display pixel ([x], [y]) at [rotation]
     * (`Surface.ROTATION_*` as 0..3) with the display [naturalWidth] x [naturalHeight] at rotation 0:
     * the inverse of [naturalToDisplay].
     */
    fun displayToNatural(x: Float, y: Float, rotation: Int, naturalWidth: Int, naturalHeight: Int): Pair<Float, Float> {
        val w = naturalWidth.toFloat()
        val h = naturalHeight.toFloat()
        return when (rotation and 3) {
            1 -> (w - y) / w to x / h
            2 -> (w - x) / w to (h - y) / h
            3 -> y / w to (h - x) / h
            else -> x / w to y / h
        }
    }

    /**
     * The upright corner (as the user holding the phone at [panelHold] sees it) nearest to the
     * natural-orientation fractions ([fx], [fy]): left or right half, top or bottom half.
     */
    fun uprightCorner(fx: Float, fy: Float, panelHold: Int): RemoteDockPrefs.Corner {
        // The panel is upright as if the display were turned to its hold.
        val (ux, uy) = naturalToDisplay(fx, fy, panelHold, 1, 1)
        val left = ux < 0.5f
        val top = uy < 0.5f
        return when {
            top && left -> RemoteDockPrefs.Corner.TOP_LEFT
            top -> RemoteDockPrefs.Corner.TOP_RIGHT
            left -> RemoteDockPrefs.Corner.BOTTOM_LEFT
            else -> RemoteDockPrefs.Corner.BOTTOM_RIGHT
        }
    }

    /**
     * The top-left of a [size] x [size] button whose centre is ([centerX], [centerY]) on a
     * [screenWidth] x [screenHeight] screen, kept on the screen and out of the side strips
     * [gapLeft] / [gapRight] left for the system edge gestures.
     */
    fun clampedTopLeft(
        centerX: Float,
        centerY: Float,
        size: Int,
        screenWidth: Int,
        screenHeight: Int,
        gapLeft: Int,
        gapRight: Int,
        gapBottom: Int = 0,
    ): Pair<Int, Int> {
        val maxX = maxOf(gapLeft, screenWidth - gapRight - size)
        val maxY = maxOf(0, screenHeight - gapBottom - size)
        val x = (centerX - size / 2f).toInt().coerceIn(gapLeft, maxX)
        val y = (centerY - size / 2f).toInt().coerceIn(0, maxY)
        return x to y
    }
}
