package com.raphael.handmouse.tracking

/** Engagement zone (Eye Tools fork): hands low in the camera frame are ignored — e.g. hands on
 * the handlebars while riding, or resting on a desk. Pure — JVM-testable. */
object HandZone {
    private val PALM_POINTS = intArrayOf(0, 5, 9, 13, 17)

    /** true when the palm centre lies in the bottom [bottomFraction] of the image (0 = off). */
    fun isInBottomZone(points: List<HandPoint>, bottomFraction: Float): Boolean {
        if (bottomFraction <= 0f || points.size < 21) return false
        var y = 0f
        for (i in PALM_POINTS) y += points[i].y
        return y / PALM_POINTS.size > 1f - bottomFraction
    }
}
