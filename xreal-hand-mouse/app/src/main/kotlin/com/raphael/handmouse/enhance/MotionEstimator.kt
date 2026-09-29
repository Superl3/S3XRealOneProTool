package com.raphael.handmouse.enhance

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Global frame-to-frame motion of the image content from two luma frames (the enhancer's first
 * pass, 2026-09-29): block matching on a two-level pyramid, then a robust similarity fit.
 *
 * The result uses the model [com.raphael.handmouse.imu.ImuCameraCalibration] and the stabilizer
 * share: `p_i = R(dθ)·(p_{i-1} − c) + c + (dx, dy)` about the image centre `c`, `dx`/`dy` in
 * image widths. A scale is fitted too (walking forward zooms the scene) but only reported.
 *
 * Not thread-safe (scratch buffers); one instance per worker. Pure — JVM-tested (MotionEstimatorTest).
 */
class MotionEstimator(val width: Int, val height: Int) {

    companion object {
        const val BLOCK = 16
        /** Search radius at the coarse level (half resolution): ±20 px at the input scale. */
        const val COARSE_RADIUS = 10
        const val FINE_RADIUS = 2
        const val FINE_STRIDE = 24
        /** Mean |gradient| per pixel (0..255) a block needs to be matched. */
        const val MIN_TEXTURE = 3
        const val MIN_INLIERS = 10
    }

    class Result(
        /** Image widths per frame. */
        val dx: Float,
        val dy: Float,
        /** rad per frame, positive = content turns clockwise on screen (y down). */
        val dTheta: Float,
        val scale: Float,
        val inliers: Int,
        val blocks: Int,
    )

    private val cw = width / 2
    private val ch = height / 2
    private val coarsePrev = ByteArray(cw * ch)
    private val coarseCur = ByteArray(cw * ch)
    private val cap = ((width / FINE_STRIDE) + 1) * ((height / FINE_STRIDE) + 1)
    private val px = FloatArray(cap)
    private val py = FloatArray(cap)
    private val qx = FloatArray(cap)
    private val qy = FloatArray(cap)
    private val residual = FloatArray(cap)
    private val inlier = BooleanArray(cap)
    private val fineCost = IntArray((2 * FINE_RADIUS + 3) * (2 * FINE_RADIUS + 3))

    /**
     * Motion from [prev] to [cur] (row-major luma, [width]×[height]). [predict] (usually the
     * previous frame's result) centres the coarse search. Null when too few blocks agree —
     * flat or dark scenes, heavy blur, or a scene cut.
     */
    fun estimate(prev: ByteArray, cur: ByteArray, predict: Result? = null): Result? {
        downsample(prev, coarsePrev)
        downsample(cur, coarseCur)
        val coarse = coarseModel(predict) ?: return null
        // fine level: blocks on a grid, searched around the coarse model's prediction
        val cx = width / 2f
        val cy = height / 2f
        var n = 0
        val margin = FINE_RADIUS + 1
        var y0 = margin + 2
        while (y0 + BLOCK + margin + 2 <= height) {
            var x0 = margin + 2
            while (x0 + BLOCK + margin + 2 <= width) {
                val bx = x0 + BLOCK / 2f
                val by = y0 + BLOCK / 2f
                val ex = apply(coarse, bx, by, cx, cy, 0) - bx
                val ey = apply(coarse, bx, by, cx, cy, 1) - by
                val m = matchFine(prev, cur, x0, y0, ex.roundToInt(), ey.roundToInt())
                if (m != null) {
                    px[n] = bx; py[n] = by
                    qx[n] = bx + m[0]; qy[n] = by + m[1]
                    n++
                }
                x0 += FINE_STRIDE
            }
            y0 += FINE_STRIDE
        }
        val fit = robustSimilarity(n, cx, cy, 0.6f) ?: return null
        return Result(
            fit[2] / width, fit[3] / width,
            atan2(fit[1], fit[0]), hypot(fit[0], fit[1]),
            fit[4].toInt(), n,
        )
    }

    /** Coarse-level similarity (in full-resolution pixels), or null. */
    private fun coarseModel(predict: Result?): FloatArray? {
        val ccx = cw / 2f
        val ccy = ch / 2f
        val cols = 6
        val rows = 4
        val r = COARSE_RADIUS
        var n = 0
        for (j in 0 until rows) for (i in 0 until cols) {
            val x0 = r + (cw - 2 * r - BLOCK) * i / (cols - 1)
            val y0 = r + (ch - 2 * r - BLOCK) * j / (rows - 1)
            if (texture(coarsePrev, cw, x0, y0) < MIN_TEXTURE * BLOCK * BLOCK) continue
            val bx = x0 + BLOCK / 2f
            val by = y0 + BLOCK / 2f
            var pu = 0
            var pv = 0
            if (predict != null) {
                // predicted displacement at this block (rotation included), in coarse px
                val dxp = predict.dx * cw
                val dyp = predict.dy * cw
                val c = cos(predict.dTheta)
                val s = sin(predict.dTheta)
                val rx = bx - ccx
                val ry = by - ccy
                pu = (c * rx - s * ry + ccx + dxp - bx).roundToInt()
                pv = (s * rx + c * ry + ccy + dyp - by).roundToInt()
            }
            var best = Int.MAX_VALUE
            var bu = 0
            var bv = 0
            for (v in pv - r..pv + r) {
                val ty = y0 + v
                if (ty < 0 || ty + BLOCK > ch) continue
                for (u in pu - r..pu + r) {
                    val tx = x0 + u
                    if (tx < 0 || tx + BLOCK > cw) continue
                    val s = sad(coarsePrev, coarseCur, cw, x0, y0, tx, ty, best)
                    if (s < best) { best = s; bu = u; bv = v }
                }
            }
            if (best == Int.MAX_VALUE) continue
            // full-resolution coordinates for the fit
            px[n] = bx * 2; py[n] = by * 2
            qx[n] = (bx + bu) * 2; qy[n] = (by + bv) * 2
            n++
        }
        if (n < 6) return null
        return robustSimilarity(n, width / 2f, height / 2f, 2.5f, minInliers = 5)
    }

    /** Sub-pixel displacement of the fine block at ([x0], [y0]) near ([eu], [ev]), or null. */
    private fun matchFine(prev: ByteArray, cur: ByteArray, x0: Int, y0: Int, eu: Int, ev: Int): FloatArray? {
        if (texture(prev, width, x0, y0) < MIN_TEXTURE * BLOCK * BLOCK) return null
        val r = FINE_RADIUS
        if (x0 + eu - r - 1 < 0 || y0 + ev - r - 1 < 0 || x0 + eu + r + 1 + BLOCK > width || y0 + ev + r + 1 + BLOCK > height) return null
        val side = 2 * r + 3 // the window plus one ring for the parabola at its edge
        val s = fineCost
        var best = Int.MAX_VALUE
        var bi = 0
        var bj = 0
        for (j in 0 until side) for (i in 0 until side) {
            val v = sad(prev, cur, width, x0, y0, x0 + eu + i - r - 1, y0 + ev + j - r - 1, Int.MAX_VALUE)
            s[j * side + i] = v
            // the minimum must lie inside the search window, not on the extra ring
            if (i in 1 until side - 1 && j in 1 until side - 1 && v < best) { best = v; bi = i; bj = j }
        }
        val s0 = s[bj * side + bi]
        val l = s[bj * side + bi - 1]
        val rr = s[bj * side + bi + 1]
        val t = s[(bj - 1) * side + bi]
        val b = s[(bj + 1) * side + bi]
        if (l <= s0 || rr <= s0 || t <= s0 || b <= s0) return null // flat valley: aperture/texture problem
        val curvX = l + rr - 2 * s0
        val curvY = t + b - 2 * s0
        val floor = BLOCK * BLOCK // one level per pixel
        if (curvX < floor || curvY < floor) return null
        val fx = ((l - rr) / (2f * curvX)).coerceIn(-0.5f, 0.5f)
        val fy = ((t - b) / (2f * curvY)).coerceIn(-0.5f, 0.5f)
        return floatArrayOf(eu + bi - r - 1 + fx, ev + bj - r - 1 + fy)
    }

    private fun apply(m: FloatArray, x: Float, y: Float, cx: Float, cy: Float, axis: Int): Float {
        val rx = x - cx
        val ry = y - cy
        return if (axis == 0) m[0] * rx - m[1] * ry + cx + m[2] else m[1] * rx + m[0] * ry + cy + m[3]
    }

    /**
     * `q − c = [a −b; b a]·(p − c) + t` over the first [n] pairs, twice re-fitted on the pairs
     * within max(2.5 × median residual, [minThreshold]). Returns `[a, b, tx, ty, inliers]`.
     */
    private fun robustSimilarity(n: Int, cx: Float, cy: Float, minThreshold: Float, minInliers: Int = MIN_INLIERS): FloatArray? {
        if (n < minInliers) return null
        for (k in 0 until n) inlier[k] = true
        var m = fitSimilarity(n, cx, cy) ?: return null
        var count = n
        repeat(2) {
            for (k in 0 until n) {
                val ex = qx[k] - apply(m, px[k], py[k], cx, cy, 0)
                val ey = qy[k] - apply(m, px[k], py[k], cx, cy, 1)
                residual[k] = hypot(ex, ey)
            }
            val sorted = residual.copyOf(n).also { it.sort() }
            val thr = maxOf(2.5f * sorted[n / 2], minThreshold)
            count = 0
            for (k in 0 until n) {
                inlier[k] = residual[k] <= thr
                if (inlier[k]) count++
            }
            if (count < minInliers || count * 2 < n) return null
            m = fitSimilarity(n, cx, cy) ?: return null
        }
        return floatArrayOf(m[0], m[1], m[2], m[3], count.toFloat())
    }

    /** Closed-form least squares over the [inlier] pairs; translation only when the points are too close together. */
    private fun fitSimilarity(n: Int, cx: Float, cy: Float): FloatArray? {
        var cnt = 0
        var mpx = 0.0; var mpy = 0.0; var mqx = 0.0; var mqy = 0.0
        for (k in 0 until n) if (inlier[k]) {
            mpx += px[k] - cx; mpy += py[k] - cy; mqx += qx[k] - cx; mqy += qy[k] - cy
            cnt++
        }
        if (cnt == 0) return null
        mpx /= cnt; mpy /= cnt; mqx /= cnt; mqy /= cnt
        var spp = 0.0; var dot = 0.0; var cross = 0.0
        for (k in 0 until n) if (inlier[k]) {
            val ax = px[k] - cx - mpx
            val ay = py[k] - cy - mpy
            val bx = qx[k] - cx - mqx
            val by = qy[k] - cy - mqy
            spp += ax * ax + ay * ay
            dot += ax * bx + ay * by
            cross += ax * by - ay * bx
        }
        var a = 1.0
        var b = 0.0
        val spread = 0.1 * width
        if (spp > cnt * spread * spread) {
            a = dot / spp
            b = cross / spp
        }
        val tx = mqx - (a * mpx - b * mpy)
        val ty = mqy - (b * mpx + a * mpy)
        return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    private fun downsample(src: ByteArray, dst: ByteArray) {
        for (y in 0 until ch) {
            var i0 = 2 * y * width
            var i1 = i0 + width
            var o = y * cw
            for (x in 0 until cw) {
                val v = (src[i0].toInt() and 0xFF) + (src[i0 + 1].toInt() and 0xFF) +
                    (src[i1].toInt() and 0xFF) + (src[i1 + 1].toInt() and 0xFF)
                dst[o++] = ((v + 2) shr 2).toByte()
                i0 += 2
                i1 += 2
            }
        }
    }

    /** Sum of |horizontal| + |vertical| differences inside the block. */
    private fun texture(img: ByteArray, stride: Int, x0: Int, y0: Int): Int {
        var s = 0
        for (y in y0 until y0 + BLOCK - 1) {
            var i = y * stride + x0
            for (x in 0 until BLOCK - 1) {
                val v = img[i].toInt() and 0xFF
                s += abs(v - (img[i + 1].toInt() and 0xFF)) + abs(v - (img[i + stride].toInt() and 0xFF))
                i++
            }
        }
        return s
    }

    private fun sad(a: ByteArray, b: ByteArray, stride: Int, ax: Int, ay: Int, bx: Int, by: Int, limit: Int): Int {
        var s = 0
        for (y in 0 until BLOCK) {
            var ia = (ay + y) * stride + ax
            var ib = (by + y) * stride + bx
            for (x in 0 until BLOCK) {
                val d = (a[ia].toInt() and 0xFF) - (b[ib].toInt() and 0xFF)
                s += if (d < 0) -d else d
                ia++
                ib++
            }
            if (s >= limit) return s
        }
        return s
    }
}
