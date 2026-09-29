package com.raphael.handmouse.enhance

import kotlin.math.ceil
import kotlin.math.exp

/** Small numeric helpers for the enhancer's per-frame plans. Pure — JVM-tested. */
internal object Signal {

    /** Gaussian-smoothed copy of [x]; beyond the ends the first/last value is repeated. */
    fun gaussian(x: FloatArray, sigma: Float): FloatArray {
        val n = x.size
        if (n == 0 || sigma <= 0f) return x.copyOf()
        val r = ceil(3 * sigma).toInt()
        val w = FloatArray(2 * r + 1) { exp(-0.5f * ((it - r) / sigma).let { d -> d * d }) }
        val sum = w.sum()
        for (k in w.indices) w[k] /= sum
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (k in -r..r) acc += w[k + r] * x[(i + k).coerceIn(0, n - 1)]
            out[i] = acc
        }
        return out
    }

    /** [valid] entries of [x] kept, the others filled from the nearest valid neighbour (ties: the earlier one). */
    fun fillGaps(x: FloatArray, valid: BooleanArray, fallback: Float): FloatArray {
        val n = x.size
        val out = FloatArray(n) { fallback }
        var last = -1
        val prevValid = IntArray(n)
        for (i in 0 until n) { if (valid[i]) last = i; prevValid[i] = last }
        var next = -1
        for (i in n - 1 downTo 0) {
            if (valid[i]) next = i
            val p = prevValid[i]
            out[i] = when {
                valid[i] -> x[i]
                p < 0 && next < 0 -> fallback
                p < 0 -> x[next]
                next < 0 -> x[p]
                i - p <= next - i -> x[p]
                else -> x[next]
            }
        }
        return out
    }
}

/**
 * Luma and brightness statistics of one analysis frame (the enhancer's first pass): BT.601 luma
 * `(77R + 150G + 29B) >> 8` into a byte plane plus a 256-bin histogram.
 */
internal object Luma {

    fun fromArgb(pixels: IntArray, width: Int, height: Int, out: ByteArray, hist: IntArray) {
        hist.fill(0)
        for (i in 0 until width * height) {
            val c = pixels[i]
            val y = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
            out[i] = y.toByte()
            hist[y]++
        }
    }

    /** Levels (0..1) below which [q] of the pixels lie, for each of [qs]. */
    fun percentiles(hist: IntArray, vararg qs: Float): FloatArray {
        val total = hist.sum()
        val out = FloatArray(qs.size)
        if (total == 0) return out
        for ((k, q) in qs.withIndex()) {
            val target = q * total
            var acc = 0
            var level = 255
            for (v in 0 until 256) {
                acc += hist[v]
                if (acc >= target) { level = v; break }
            }
            out[k] = level / 255f
        }
        return out
    }
}
