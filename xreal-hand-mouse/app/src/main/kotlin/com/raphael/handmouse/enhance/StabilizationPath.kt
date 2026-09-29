package com.raphael.handmouse.enhance

import kotlin.math.abs

/**
 * Stabilizing crop of the enhanced video. The output pixel `o` (output size [width]×[outHeight])
 * samples the source at
 *
 *     s = c + R(phi)·(o − oc) / zoom + (ux, uy)
 *
 * with `c` the centre of the usable source ([width]×[usableHeight] — the MJPEG stream's bottom rows
 * are cropped) and `oc` the output centre.
 *
 * The camera path is the running sum of the per-frame motions (image widths, rad — see
 * [MotionEstimator.Result]), smoothed with a Gaussian (σ = [SIGMA_FRAMES]); the correction is
 * path − smoothed path, clamped so the crop never leaves the source. Only the part the clamp cut
 * off is smoothed (σ = [SIGMA_CORRECTION]) and taken out of the correction before clamping again:
 * the crop eases into the border instead of stopping dead, and the shake the correction exists
 * for is left alone (smoothing the correction itself would remove exactly that — a 2 Hz walking
 * bob at 30 fps would keep 75 %). Frames without a motion count as no motion; with more than
 * [MAX_MISSING] of them the plan is a plain crop ([active] = false, [zoom] just enough to fill
 * [outHeight]).
 *
 * Pure — JVM-tested (StabilizationPathTest).
 */
class StabilizationPath private constructor(
    val zoom: Float,
    val ux: FloatArray,
    val uy: FloatArray,
    val phi: FloatArray,
    val active: Boolean,
) {
    val size get() = ux.size

    companion object {
        const val ZOOM = 1.08f
        const val SIGMA_FRAMES = 15f
        const val SIGMA_CORRECTION = 4f
        const val MAX_MISSING = 0.3f

        fun plan(motion: Array<FloatArray?>, width: Int, usableHeight: Int, outHeight: Int): StabilizationPath {
            val n = motion.size
            val missing = motion.count { it == null } - if (n > 0 && motion[0] == null) 1 else 0 // frame 0 has no predecessor
            if (n < 2 || missing > MAX_MISSING * (n - 1)) {
                val z = outHeight.toFloat() / usableHeight
                return StabilizationPath(maxOf(z, 1f), FloatArray(n), FloatArray(n), FloatArray(n), false)
            }
            val px = FloatArray(n)
            val py = FloatArray(n)
            val th = FloatArray(n)
            for (i in 1 until n) {
                val m = motion[i]
                px[i] = px[i - 1] + (m?.get(0) ?: 0f) * width
                py[i] = py[i - 1] + (m?.get(1) ?: 0f) * width
                th[i] = th[i - 1] + (m?.get(2) ?: 0f)
            }
            val sx = Signal.gaussian(px, SIGMA_FRAMES)
            val sy = Signal.gaussian(py, SIGMA_FRAMES)
            val st = Signal.gaussian(th, SIGMA_FRAMES)
            val ux = FloatArray(n) { px[it] - sx[it] }
            val uy = FloatArray(n) { py[it] - sy[it] }
            val phi = FloatArray(n) { th[it] - st[it] }
            val a = width / (2 * ZOOM)          // half-extent of the crop in the source, x
            val b = outHeight / (2 * ZOOM)      // … y
            val slackX = width / 2f - a
            val slackY = usableHeight / 2f - b
            val maxPhi = 0.5f * minOf(slackX / b, slackY / a)
            fun clamp() {
                for (i in 0 until n) {
                    phi[i] = phi[i].coerceIn(-maxPhi, maxPhi)
                    val limX = slackX - b * abs(phi[i])
                    val limY = slackY - a * abs(phi[i])
                    ux[i] = ux[i].coerceIn(-limX, limX)
                    uy[i] = uy[i].coerceIn(-limY, limY)
                }
            }
            val rawX = ux.copyOf()
            val rawY = uy.copyOf()
            val rawPhi = phi.copyOf()
            clamp()
            val cutX = Signal.gaussian(FloatArray(n) { rawX[it] - ux[it] }, SIGMA_CORRECTION)
            val cutY = Signal.gaussian(FloatArray(n) { rawY[it] - uy[it] }, SIGMA_CORRECTION)
            val cutPhi = Signal.gaussian(FloatArray(n) { rawPhi[it] - phi[it] }, SIGMA_CORRECTION)
            for (i in 0 until n) {
                ux[i] = rawX[i] - cutX[i]
                uy[i] = rawY[i] - cutY[i]
                phi[i] = rawPhi[i] - cutPhi[i]
            }
            clamp()
            return StabilizationPath(ZOOM, ux, uy, phi, true)
        }
    }
}
