package com.raphael.handmouse.tracking

/** A stable hand position based on the four knuckles, rather than a moving fingertip. */
class PalmCursorReference {
    private val recentX = ArrayDeque<Float>(3)
    private val recentY = ArrayDeque<Float>(3)
    private var smoothX: Float? = null
    private var smoothY: Float? = null
    private var lastTimestampMs: Long? = null

    fun update(points: List<HandPoint>, timestampMs: Long): HandPoint {
        val knuckles = intArrayOf(5, 9, 13, 17)
        val x = knuckles.sumOf { points[it].x.toDouble() }.toFloat() / knuckles.size
        val y = knuckles.sumOf { points[it].y.toDouble() }.toFloat() / knuckles.size
        recentX.addLast(x)
        recentY.addLast(y)
        if (recentX.size > 3) recentX.removeFirst()
        if (recentY.size > 3) recentY.removeFirst()
        fun median(values: ArrayDeque<Float>): Float {
            val ordered = values.sorted()
            val middle = ordered.size / 2
            return if (ordered.size % 2 == 0) (ordered[middle - 1] + ordered[middle]) / 2f
                else ordered[middle]
        }
        val medianX = median(recentX)
        val medianY = median(recentY)
        val previousX = smoothX
        val previousY = smoothY
        // Follow deliberate travel faster, but damp the small oscillations of hand detection.
        val delta = if (previousX == null || previousY == null) 0f else
            kotlin.math.hypot(medianX - previousX, medianY - previousY)
        val alpha = if (delta > 0.012f) 0.65f else 0.28f
        var resultX = if (previousX == null) medianX else previousX + alpha * (medianX - previousX)
        var resultY = if (previousY == null) medianY else previousY + alpha * (medianY - previousY)
        if (previousX != null && previousY != null) {
            val dt = (timestampMs - (lastTimestampMs ?: timestampMs)).coerceIn(1L, 80L)
            val maxStep = 0.01f * dt / 16f
            val dx = resultX - previousX
            val dy = resultY - previousY
            val travel = kotlin.math.hypot(dx, dy)
            if (travel > maxStep) {
                resultX = previousX + dx * maxStep / travel
                resultY = previousY + dy * maxStep / travel
            }
        }
        smoothX = resultX
        smoothY = resultY
        lastTimestampMs = timestampMs
        return HandPoint(resultX, resultY, 0f)
    }

    fun reset() {
        recentX.clear()
        recentY.clear()
        smoothX = null
        smoothY = null
        lastTimestampMs = null
    }
}
