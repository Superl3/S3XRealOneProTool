package com.raphael.handmouse.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToLong

/** [VideoEnhancer.frameIntervalUs] and [VideoEnhancer.snapTimes]: the output keeps the recording's length. */
class FrameTimingTest {

    /** Like the Eye's MKV times: ~29.57 fps, ±2 ms USB jitter, whole ms, starting at 0. */
    private fun recorded(n: Int, intervalUs: Double = 33_814.0, seed: Long = 1): LongArray {
        val rnd = Random(seed)
        val t = LongArray(n) { ((it * intervalUs + rnd.nextInt(4001) - 2000) / 1000).roundToLong() * 1000 }
        val t0 = t[0]
        return LongArray(n) { t[it] - t0 }
    }

    private fun assertOnGrid(pts: LongArray, stepUs: Double) {
        for (i in 1 until pts.size) {
            val slots = (pts[i] - pts[i - 1]) / stepUs
            assertTrue("frame $i: ${pts[i] - pts[i - 1]} µs", slots > 0.99 && abs(slots - Math.round(slots)) < 0.01)
        }
    }

    @Test
    fun jitteredMillisecondTimesKeepTheirLength() {
        val t = recorded(3000)
        // the case that stretched the device outputs: the median interval is a whole 34 ms
        val d = LongArray(t.size - 1) { t[it + 1] - t[it] }.sorted()
        assertEquals(34_000L, d[d.size / 2])

        val step = VideoEnhancer.frameIntervalUs(t)
        assertEquals(33_814.0, step, 5.0)
        val pts = VideoEnhancer.snapTimes(t, step)
        assertOnGrid(pts, step)
        assertTrue("span ${pts.last()} vs ${t.last()}", abs(pts.last() - t.last()) < step)
        for (i in t.indices) assertTrue("frame $i off by ${pts[i] - t[i]} µs", abs(pts[i] - t[i]) < step + 2_500)
    }

    @Test
    fun aStallStaysAGapAndDoesNotChangeTheRate() {
        val a = recorded(600)
        val b = recorded(600, seed = 2)
        val gapUs = 2_000_000L
        val t = LongArray(1200) { if (it < 600) a[it] else a.last() + gapUs + b[it - 600] }

        val step = VideoEnhancer.frameIntervalUs(t)
        assertEquals(33_814.0, step, 10.0)
        val pts = VideoEnhancer.snapTimes(t, step)
        assertOnGrid(pts, step)
        assertTrue(abs(pts[600] - t[600]) < step)
        assertTrue(pts[600] - pts[599] > gapUs - step)
        assertTrue(abs(pts.last() - t.last()) < step)
    }

    @Test
    fun framesCloserThanAStepAreSpreadNotMerged() {
        val t = longArrayOf(0, 33_000, 34_000, 35_000, 100_000, 133_000)
        val pts = VideoEnhancer.snapTimes(t, 33_333.3)
        // slots 0, 1, 2, 3, 4, 5: the burst pushes the rest back one slot each
        assertEquals(listOf(0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L), pts.toList())
    }

    /** 41 min like the Eye's: the rate wanders by ±1.7 % over 5 min blocks, ±2 ms jitter, a 2.2 s stall every 5 min. */
    private fun wandering(blockMeansMs: DoubleArray = doubleArrayOf(34.6, 33.4), minutes: Int = 41, seed: Long = 7): LongArray {
        val rnd = Random(seed)
        val out = ArrayList<Long>()
        var t = 0.0
        var nextStallMs = 300_000.0
        out += 0L
        while (t < minutes * 60_000.0) {
            val block = ((t / 300_000.0).toInt()) % blockMeansMs.size
            t += blockMeansMs[block] + rnd.nextInt(5) - 2
            if (t >= nextStallMs) { t += 2_200.0; nextStallMs += 300_000.0 }
            out += t.roundToLong() * 1000
        }
        return out.toLongArray()
    }

    @Test
    fun aDriftingRateStretchesAnUnlimitedGridPastTheCheck() {
        // the failure of 2026-09-30: [VideoEnhancer.verify] allows 1 s and the grid gave 2.5 s on a 41 min file
        val t = wandering()
        val step = VideoEnhancer.frameIntervalUs(t)
        val pts = VideoEnhancer.snapTimes(t, step)
        val stretch = (pts.last() - pts.first()) - (t.last() - t.first())
        assertTrue("stretch $stretch µs", stretch > VideoEnhancer.DURATION_TOLERANCE_US)
    }

    @Test
    fun aLimitedGridKeepsALongRecordingWithinTwoStepsAndTheCheck() {
        for (seed in 1L..4L) {
            for (means in listOf(doubleArrayOf(34.6, 33.4), doubleArrayOf(34.4, 33.6), doubleArrayOf(34.5, 33.5, 34.2, 33.8))) {
                val t = wandering(means, seed = seed)
                val step = VideoEnhancer.frameIntervalUs(t)
                val maxLag = (2 * step).toLong()
                val pts = VideoEnhancer.snapTimes(t, step, maxLag)
                assertEquals(t.size, pts.size)
                for (i in 1 until pts.size) assertTrue("frame $i not increasing", pts[i] > pts[i - 1])
                for (i in t.indices) assertTrue("frame $i is ${pts[i] - t[i]} µs late", pts[i] - t[i] <= maxLag + step / 2)
                val stretch = abs((pts.last() - pts.first()) - (t.last() - t.first()))
                assertTrue("stretch $stretch µs (seed $seed)", stretch < maxLag + step)
            }
        }
    }

    @Test
    fun aLimitedGridStillSpreadsABurstAtLeastHalfAStepApart() {
        val t = LongArray(40) { 1_000_000L + it * 1_000L } + LongArray(40) { 2_000_000L + it * 34_000L }
        val step = 34_000.0
        val pts = VideoEnhancer.snapTimes(t, step, (2 * step).toLong())
        for (i in 1 until pts.size) assertTrue("frame $i gap ${pts[i] - pts[i - 1]} µs", pts[i] - pts[i - 1] >= step / 2 - 1)
    }

    @Test
    fun tooFewFramesFallBackTo30Fps() {
        assertEquals(1e6 / 30, VideoEnhancer.frameIntervalUs(longArrayOf()), 1e-6)
        assertEquals(1e6 / 30, VideoEnhancer.frameIntervalUs(longArrayOf(0)), 1e-6)
        assertEquals(1e6 / 30, VideoEnhancer.frameIntervalUs(longArrayOf(5_000, 5_000)), 1e-6)
    }
}
