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

    @Test
    fun tooFewFramesFallBackTo30Fps() {
        assertEquals(1e6 / 30, VideoEnhancer.frameIntervalUs(longArrayOf()), 1e-6)
        assertEquals(1e6 / 30, VideoEnhancer.frameIntervalUs(longArrayOf(0)), 1e-6)
        assertEquals(1e6 / 30, VideoEnhancer.frameIntervalUs(longArrayOf(5_000, 5_000)), 1e-6)
    }
}
