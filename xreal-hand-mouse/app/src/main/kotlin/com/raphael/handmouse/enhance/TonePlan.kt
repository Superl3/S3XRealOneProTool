package com.raphael.handmouse.enhance

import kotlin.math.ln

/**
 * Per-frame tone curve of the enhanced video: `out = ((x − black) / (white − black))^gamma`,
 * clamped, on luma; [EnhanceRenderer] scales all three RGB channels by the same `out / x`. The levels follow the scene slowly (Gaussian, σ = [SIGMA_FRAMES])
 * so the auto-exposure of the camera does not pump more than it already does.
 *
 * - black = 1st percentile, at most [MAX_BLACK] (keeps the night sky from being crushed further);
 * - white = 99th percentile, at least [MIN_WHITE]; the gain `1/(white − black)` at most [MAX_GAIN];
 * - gamma puts the median at [TARGET_MID], within [MIN_GAMMA]..[MAX_GAMMA].
 *
 * Pure — JVM-tested (TonePlanTest).
 */
class TonePlan private constructor(val black: FloatArray, val white: FloatArray, val gamma: FloatArray) {

    val size get() = black.size

    companion object {
        const val SIGMA_FRAMES = 30f
        const val MAX_BLACK = 0.1f
        const val MIN_WHITE = 0.6f
        const val MAX_GAIN = 2.5f
        const val TARGET_MID = 0.45f
        const val MIN_GAMMA = 0.6f
        const val MAX_GAMMA = 1.4f

        /** [stats]: per frame `[p1, p50, p99]` in 0..1, or null for a frame that could not be analysed. */
        fun build(stats: Array<FloatArray?>): TonePlan {
            val n = stats.size
            val valid = BooleanArray(n) { stats[it] != null }
            val b = FloatArray(n)
            val w = FloatArray(n)
            val g = FloatArray(n)
            for (i in 0 until n) {
                val s = stats[i] ?: continue
                val black = s[0].coerceAtMost(MAX_BLACK)
                var white = s[2].coerceAtLeast(MIN_WHITE)
                if (1f / (white - black) > MAX_GAIN) white = black + 1f / MAX_GAIN
                val mid = ((s[1] - black) / (white - black)).coerceIn(0.01f, 0.99f)
                b[i] = black
                w[i] = white
                g[i] = (ln(TARGET_MID) / ln(mid)).coerceIn(MIN_GAMMA, MAX_GAMMA)
            }
            return TonePlan(
                Signal.gaussian(Signal.fillGaps(b, valid, 0f), SIGMA_FRAMES),
                Signal.gaussian(Signal.fillGaps(w, valid, 1f), SIGMA_FRAMES),
                Signal.gaussian(Signal.fillGaps(g, valid, 1f), SIGMA_FRAMES),
            )
        }
    }
}
