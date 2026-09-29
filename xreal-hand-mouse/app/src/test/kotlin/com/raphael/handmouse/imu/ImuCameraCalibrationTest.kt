package com.raphael.handmouse.imu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class ImuCameraCalibrationTest {

    /** Rows dx, dy, dθ; columns gyro x, y, z — the axes swapped and flipped, as a real mount might be. */
    private val trueM = floatArrayOf(
        0f, 0.9f, 0f,
        -0.9f, 0f, 0.05f,
        0.02f, 0f, -1f,
    )

    /** 500 Hz gyro, a few sines per axis (rad/s), from −0.5 s to [durationS]. */
    private fun gyro(durationS: Double, scale: Double = 1.0): GyroSeries {
        val n = ((durationS + 0.5) * 500).toInt()
        val t = LongArray(n) { -500_000L + it * 2000L }
        val g = FloatArray(n * 3)
        for (i in 0 until n) {
            val s = t[i] / 1e6
            g[i * 3] = (scale * (0.6 * sin(2 * PI * 0.7 * s) + 0.3 * sin(2 * PI * 2.3 * s + 1))).toFloat()
            g[i * 3 + 1] = (scale * (0.5 * sin(2 * PI * 0.4 * s + 2) + 0.35 * sin(2 * PI * 3.1 * s))).toFloat()
            g[i * 3 + 2] = (scale * 0.2 * sin(2 * PI * 1.1 * s + 0.5)).toFloat()
        }
        return GyroSeries(t, g)
    }

    /** ~30 fps arrival times with a few ms of USB jitter. */
    private fun frameTimes(count: Int) = LongArray(count) { it * 33_333L + ((it * 7) % 5 - 2) * 1000L }

    /**
     * Image motion the camera would see: [trueM] · ∫gyro over the interval [latencyMs] earlier,
     * plus a bias, measurement noise, and every 20th frame disturbed (a hand crossing the view).
     */
    private fun motion(times: LongArray, series: GyroSeries, latencyMs: Int, noise: Float = 3e-4f, disturb: Boolean = true): Array<FloatArray?> {
        val rnd = Random(1)
        val cal = ImuCameraCalibration(trueM, latencyMs, 1f, 0, floatArrayOf(0.002f, -0.001f, 0f))
        return Array(times.size) { i ->
            if (i == 0) return@Array null
            val a = times[i - 1] - latencyMs * 1000L
            val b = times[i] - latencyMs * 1000L
            val v = cal.motion(series.integrate(a, b)!!, (b - a) / 1e6f, withBias = true)
            for (k in 0..2) v[k] += noise * rnd.nextFloat() * 2 - noise
            if (disturb && i % 20 == 0) v[0] += 0.03f
            v
        }
    }

    @Test
    fun fitRecoversAxesAndLatencyDespiteOutliers() {
        val times = frameTimes(600)
        val series = gyro(20.5)
        val cal = ImuCameraCalibration.fit(times, motion(times, series, latencyMs = 20), series)
        assertNotNull(ImuCameraCalibration.lastFailure ?: "ok", cal)
        cal!!
        assertEquals(20, cal.latencyMs)
        assertTrue("r2 ${cal.r2}", cal.r2 > 0.98f)
        for (k in 0 until 9) assertEquals("m[$k]", trueM[k], cal.m[k], 0.02f)
        // the disturbed 5 % are the ones left out
        assertTrue("frames ${cal.frames}", cal.frames in 540..570)
        assertEquals(0.002f, cal.biasPerSecond[0], 0.0015f)
        assertNull(ImuCameraCalibration.lastFailure)
    }

    @Test
    fun noFitWhenTheHeadBarelyMovedOrTheRecordingIsShort() {
        val times = frameTimes(600)
        val still = gyro(20.5, scale = 0.01)
        assertNull(ImuCameraCalibration.fit(times, motion(times, still, 20, noise = 0f, disturb = false), still))
        assertTrue(ImuCameraCalibration.lastFailure!!, ImuCameraCalibration.lastFailure!!.contains("barely moved"))

        val short = frameTimes(200)
        val series = gyro(7.0)
        assertNull(ImuCameraCalibration.fit(short, motion(short, series, 20), series))
        assertTrue(ImuCameraCalibration.lastFailure!!, ImuCameraCalibration.lastFailure!!.contains("usable frame intervals"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val cal = ImuCameraCalibration(trueM, 35, 0.8765f, 1234)
        val back = ImuCameraCalibration.decode(cal.encode())!!
        assertEquals(35, back.latencyMs)
        assertEquals(0.8765f, back.r2, 1e-4f)
        assertEquals(1234, back.frames)
        assertArrayEquals(trueM, back.m, 1e-6f)
        assertNull(ImuCameraCalibration.decode(null))
        assertNull(ImuCameraCalibration.decode(""))
        assertNull(ImuCameraCalibration.decode(cal.encode().replace("v1;", "v2;")))
        assertNull(ImuCameraCalibration.decode("v1;35;0.8;10;1,2,3"))
    }

    @Test
    fun seriesIntegratesWithSampleAndHoldAndRefusesGaps() {
        val t = LongArray(11) { it * 1000L }
        val g = FloatArray(33) { floatArrayOf(1f, 2f, -1f)[it % 3] }
        val series = GyroSeries(t, g)
        assertArrayEquals(floatArrayOf(0.005f, 0.01f, -0.005f), series.integrate(500, 5500)!!, 1e-7f)
        assertNull(series.integrate(-1, 500))    // before the first sample
        assertNull(series.integrate(500, 10_001)) // after the last
        assertNull(series.integrate(500, 500))

        val gappy = GyroSeries(longArrayOf(0, 1000, 30_000, 31_000), FloatArray(12) { 1f })
        assertNotNull(gappy.integrate(0, 1000))
        assertNull(gappy.integrate(0, 31_000)) // 29 ms without samples
    }
}
