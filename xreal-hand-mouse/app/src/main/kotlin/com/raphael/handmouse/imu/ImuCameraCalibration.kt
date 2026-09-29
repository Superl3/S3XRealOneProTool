package com.raphael.handmouse.imu

import java.util.Locale
import kotlin.math.abs

/**
 * How the glasses' gyro moves the camera image (2026-09-29). The IMU axes, their sign and the
 * camera↔IMU delay are not documented, so nothing is assumed: the enhancer fits them from a
 * recording (video motion vs. its `.gcsv`), see [fit].
 *
 * Per frame interval, the image content moves by `[dx, dy, dθ] = m · ∫gyro dt`, the integral
 * taken over the interval shifted back by [latencyMs] (frame timestamps are USB arrival times,
 * the exposure was earlier). `dx`/`dy` are in image widths (the cursor's isotropic units), `dθ`
 * in rad, with the content model `p_i = R(dθ)·(p_{i-1} − c) + c + (dx, dy)` around the image
 * centre `c`.
 *
 * Pure — JVM-tested (ImuCameraCalibrationTest).
 */
class ImuCameraCalibration(
    /** 3×3, row-major: rows dx, dy, dθ; columns gyro x, y, z (rad). */
    val m: FloatArray,
    val latencyMs: Int,
    /** Coefficient of determination of the translation rows on the inlier frames. */
    val r2: Float,
    /** Inlier frame intervals the fit used. */
    val frames: Int,
    /** Per-recording gyro bias as fitted (image units per second); not persisted. */
    val biasPerSecond: FloatArray = FloatArray(3),
) {
    /** Image motion for a gyro integral [g] (rad) over [dtS] seconds, bias included. */
    fun motion(g: FloatArray, dtS: Float = 0f, withBias: Boolean = false): FloatArray = FloatArray(3) { r ->
        m[r * 3] * g[0] + m[r * 3 + 1] * g[1] + m[r * 3 + 2] * g[2] + if (withBias) biasPerSecond[r] * dtS else 0f
    }

    /** Stored in the preferences: `v1;latency;r2;frames;m0,…,m8`. */
    fun encode(): String = buildString {
        append("v1;").append(latencyMs).append(';').append(String.format(Locale.US, "%.4f", r2)).append(';').append(frames).append(';')
        m.forEachIndexed { i, v -> if (i > 0) append(','); append(String.format(Locale.US, "%.6g", v)) }
    }

    override fun toString(): String = "ImuCameraCalibration(latency=${latencyMs}ms, r2=${"%.3f".format(Locale.US, r2)}, frames=$frames)"

    companion object {
        /** Stored and used for the cursor's head compensation from this fit quality on. */
        const val MIN_R2_STORE = 0.5f
        /** The enhancer stabilizes on the gyro (instead of the image motion) from this quality on. */
        const val MIN_R2_STABILIZE = 0.7f
        const val MIN_FRAMES = 300
        const val MAX_LATENCY_MS = 100
        const val LATENCY_STEP_MS = 5
        /** Below this spread of the image motion (widths per frame) the head barely moved: no fit. */
        const val MIN_MOTION_STD = 0.0015f
        /** Share of the frames that must agree with the fit (the rest: hands, moving objects). */
        const val MIN_INLIER_FRACTION = 0.6f

        fun decode(s: String?): ImuCameraCalibration? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(';')
            if (parts.size != 5 || parts[0] != "v1") return null
            val m = parts[4].split(',').mapNotNull { it.toFloatOrNull() }
            if (m.size != 9) return null
            return ImuCameraCalibration(
                m.toFloatArray(),
                parts[1].toIntOrNull() ?: return null,
                parts[2].toFloatOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null,
            )
        }

        /** Why [fit] returned null, for the enhancer's log. */
        @Volatile var lastFailure: String? = null
            private set

        /**
         * Least-squares fit over a latency grid (0..[MAX_LATENCY_MS] ms): for every frame
         * interval with a valid image motion, regress `[dx, dy, dθ]` on `[∫gx, ∫gy, ∫gz, dt]`
         * (the `dt` column absorbs a constant gyro bias), drop intervals whose translation
         * residual is over 3× the median (hands, passing cars), refit, and keep the latency with
         * the best translation R². Null (and [lastFailure]) when the recording cannot tell.
         *
         * @param frameTimesUs frame `i`'s time, same clock/base as [gyro]
         * @param motion motion from frame `i-1` to `i` (`motion[0]` unused), null = no estimate
         */
        fun fit(frameTimesUs: LongArray, motion: Array<FloatArray?>, gyro: GyroSeries): ImuCameraCalibration? {
            var best: ImuCameraCalibration? = null
            var failure = "no frame interval with both image motion and IMU samples"
            var latency = 0
            while (latency <= MAX_LATENCY_MS) {
                val rows = ArrayList<DoubleArray>(frameTimesUs.size)
                val targets = ArrayList<DoubleArray>(frameTimesUs.size)
                val lUs = latency * 1000L
                for (i in 1 until frameTimesUs.size) {
                    val v = motion[i] ?: continue
                    val a = frameTimesUs[i - 1] - lUs
                    val b = frameTimesUs[i] - lUs
                    val g = gyro.integrate(a, b) ?: continue
                    rows += doubleArrayOf(g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), (b - a) / 1e6)
                    targets += doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                }
                val result = fitOnce(rows, targets, latency)
                if (result.first != null) {
                    if (best == null || result.first!!.r2 > best.r2) best = result.first
                } else {
                    failure = result.second
                }
                latency += LATENCY_STEP_MS
            }
            lastFailure = if (best == null) failure else null
            return best
        }

        private fun fitOnce(rows: List<DoubleArray>, targets: List<DoubleArray>, latency: Int): Pair<ImuCameraCalibration?, String> {
            if (rows.size < MIN_FRAMES) return null to "only ${rows.size} usable frame intervals (need $MIN_FRAMES)"
            val std = kotlin.math.sqrt((variance(targets, 0) + variance(targets, 1)) / 2)
            if (std < MIN_MOTION_STD) return null to "the head barely moved (image motion std ${"%.4f".format(Locale.US, std)} widths/frame)"
            var coef = solve(rows, targets, BooleanArray(rows.size) { true }) ?: return null to "singular fit"
            // One robust pass: residuals over 3× the median are other motion than the head's.
            val res = DoubleArray(rows.size) { k -> translationResidual(rows[k], targets[k], coef) }
            val med = res.sorted()[res.size / 2]
            val inlier = BooleanArray(rows.size) { res[it] <= 3 * med + 1e-9 }
            val n = inlier.count { it }
            if (n < MIN_FRAMES || n < rows.size * MIN_INLIER_FRACTION) return null to "too few frames agree with one fit ($n of ${rows.size})"
            coef = solve(rows, targets, inlier) ?: return null to "singular fit"
            var ssRes = 0.0
            var ssTot = 0.0
            for (t in 0..1) {
                var mean = 0.0
                for (k in rows.indices) if (inlier[k]) mean += targets[k][t]
                mean /= n
                for (k in rows.indices) if (inlier[k]) {
                    val e = targets[k][t] - predict(rows[k], coef, t)
                    ssRes += e * e
                    val d = targets[k][t] - mean
                    ssTot += d * d
                }
            }
            val r2 = if (ssTot > 0) (1 - ssRes / ssTot).toFloat() else 0f
            val m = FloatArray(9) { coef[it / 3][it % 3].toFloat() }
            val bias = FloatArray(3) { coef[it][3].toFloat() }
            return ImuCameraCalibration(m, latency, r2, n, bias) to ""
        }

        private fun variance(targets: List<DoubleArray>, t: Int): Double {
            val mean = targets.sumOf { it[t] } / targets.size
            return targets.sumOf { (it[t] - mean) * (it[t] - mean) } / targets.size
        }

        private fun predict(row: DoubleArray, coef: Array<DoubleArray>, t: Int): Double =
            coef[t][0] * row[0] + coef[t][1] * row[1] + coef[t][2] * row[2] + coef[t][3] * row[3]

        private fun translationResidual(row: DoubleArray, target: DoubleArray, coef: Array<DoubleArray>): Double {
            val ex = target[0] - predict(row, coef, 0)
            val ey = target[1] - predict(row, coef, 1)
            return kotlin.math.sqrt(ex * ex + ey * ey)
        }

        /** Normal equations, one 4-coefficient row per target (a tiny ridge keeps them regular). */
        private fun solve(rows: List<DoubleArray>, targets: List<DoubleArray>, use: BooleanArray): Array<DoubleArray>? {
            val ata = Array(4) { DoubleArray(4) }
            val aty = Array(3) { DoubleArray(4) }
            for (k in rows.indices) {
                if (!use[k]) continue
                val r = rows[k]
                for (i in 0 until 4) {
                    for (j in 0 until 4) ata[i][j] += r[i] * r[j]
                    for (t in 0 until 3) aty[t][i] += r[i] * targets[k][t]
                }
            }
            val trace = (0 until 4).sumOf { ata[it][it] }
            for (i in 0 until 4) ata[i][i] += trace * 1e-9
            return Array(3) { t -> gauss(ata.map { it.copyOf() }.toTypedArray(), aty[t].copyOf()) ?: return null }
        }

        private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
                if (abs(a[p][c]) < 1e-18) return null
                if (p != c) {
                    val tmp = a[p]; a[p] = a[c]; a[c] = tmp
                    val tb = b[p]; b[p] = b[c]; b[c] = tb
                }
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    for (k in c until n) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (k in r + 1 until n) s -= a[r][k] * x[k]
                x[r] = s / a[r][r]
            }
            return x
        }
    }
}

/**
 * A recorded gyro series (µs, rad/s) — the `.gcsv` read back ([com.raphael.handmouse.enhance.GcsvReader]).
 * [integrate] holds each sample's rate until the next one; null when the interval is not covered
 * or has a hole over [maxGapUs].
 */
class GyroSeries(val tUs: LongArray, val g: FloatArray, private val maxGapUs: Long = 20_000L) {
    val size: Int get() = tUs.size

    fun integrate(fromUs: Long, toUs: Long): FloatArray? {
        if (tUs.size < 2 || toUs <= fromUs || fromUs < tUs[0] || toUs > tUs[tUs.size - 1]) return null
        var i = java.util.Arrays.binarySearch(tUs, fromUs).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val out = FloatArray(3)
        while (i < tUs.size - 1 && tUs[i] < toUs) {
            if (tUs[i + 1] - tUs[i] > maxGapUs) return null
            val a = maxOf(tUs[i], fromUs)
            val b = minOf(tUs[i + 1], toUs)
            if (b > a) {
                val dt = (b - a) / 1e6f
                out[0] += g[i * 3] * dt
                out[1] += g[i * 3 + 1] * dt
                out[2] += g[i * 3 + 2] * dt
            }
            i++
        }
        return out
    }
}
