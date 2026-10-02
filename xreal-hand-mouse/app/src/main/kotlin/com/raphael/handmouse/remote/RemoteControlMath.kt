package com.raphael.handmouse.remote

data class RemotePoint(val x: Float, val y: Float)

data class RemoteSwipe(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

object RemoteControlMath {
    /** A touch shorter than this is a tap; a finger held this long starts a drag. */
    const val TAP_MAX_MS = 340L

    /**
     * Whether a finished touch is a tap. [lifted] is false when the system cancelled the touch
     * (edge swipe, the panel redrawn under the finger): that must never click the DeX display.
     */
    fun isTap(lifted: Boolean, moved: Boolean, twoFinger: Boolean, elapsedMs: Long): Boolean =
        lifted && !moved && !twoFinger && elapsedMs < TAP_MAX_MS

    fun movePointer(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        width: Int,
        height: Int,
    ): RemotePoint {
        val maxX = (width - 1).coerceAtLeast(0).toFloat()
        val maxY = (height - 1).coerceAtLeast(0).toFloat()
        return RemotePoint(
            (x + dx).coerceIn(0f, maxX),
            (y + dy).coerceIn(0f, maxY),
        )
    }
    fun activeSwipe(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        up: Boolean,
    ): RemoteSwipe {
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val x = left + width / 2f
        val yTop = top + height * 0.26f
        val yBottom = top + height * 0.74f
        return if (up) {
            RemoteSwipe(x, yBottom, x, yTop)
        } else {
            RemoteSwipe(x, yTop, x, yBottom)
        }
    }
}
