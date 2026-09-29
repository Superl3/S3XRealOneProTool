package com.raphael.handmouse.tracking

import com.raphael.handmouse.imu.GyroHistory
import com.raphael.handmouse.imu.ImuCameraCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HeadMotionCompensatorTest {

    private val aspect = 1072f / 1920f
    private val k = 0.8f
    private val start = listOf(HandPoint(0.3f, 0.2f, 0f), HandPoint(0.6f, 0.4f, -0.1f))

    /** dx = k·∫gy, dθ = ∫gz, no delay. */
    private fun compensator(): Pair<HeadMotionCompensator, GyroHistory> {
        val history = GyroHistory()
        val c = HeadMotionCompensator()
        c.calibration = ImuCameraCalibration(floatArrayOf(0f, k, 0f, 0f, 0f, 0f, 0f, 0f, 1f), 0, 0.9f, 1000)
        c.gyro = history
        return c to history
    }

    /** Feeds 1 kHz samples of a constant rate up to [untilMs]. */
    private class Feed(val history: GyroHistory, val rate: FloatArray) {
        var ms = 0L
        fun until(untilMs: Long) {
            while (ms <= untilMs) {
                history.add(ms * 1_000_000L, rate[0], rate[1], rate[2])
                ms++
            }
        }
    }

    /** One frame of a world-fixed point: `p' = R(dθ)·(p − c) + c + (dx, 0)`. */
    private fun move(p: HandPoint, dx: Double, dTheta: Double): HandPoint {
        val cx = 0.5
        val cy = 0.5 * aspect
        val c = cos(dTheta)
        val s = sin(dTheta)
        val x = p.x - cx
        val y = p.y - cy
        return HandPoint((c * x - s * y + cx + dx).toFloat(), (s * x + c * y + cy).toFloat(), p.z)
    }

    private fun assertPoints(expected: List<HandPoint>, actual: List<HandPoint>, tol: Float) {
        for (i in expected.indices) {
            assertEquals("x$i", expected[i].x, actual[i].x, tol)
            assertEquals("y$i", expected[i].y, actual[i].y, tol)
            assertEquals("z$i", expected[i].z, actual[i].z, 0f)
        }
    }

    @Test
    fun headTurnAndRollAreRemovedFromAStillHand() {
        val (comp, history) = compensator()
        val gy = 0.4f
        val gz = 0.2f
        val feed = Feed(history, floatArrayOf(0f, gy, gz))
        var seen = start
        var t = 1000L
        feed.until(t)
        assertPoints(start, comp.compensate(seen, t, aspect), 0f) // first frame: nothing to chain yet
        repeat(40) {
            t += 33
            feed.until(t)
            seen = seen.map { move(it, k * gy * 0.033, gz * 0.033.toDouble()) }
            assertPoints(start, comp.compensate(seen, t, aspect), 2e-4f)
        }
        // the hand really did travel across the image
        assertTrue(abs(seen[0].x - start[0].x) > 0.3f)
    }

    @Test
    fun chainRestartsAfterAGap() {
        val (comp, history) = compensator()
        val feed = Feed(history, floatArrayOf(0f, 0.5f, 0f))
        feed.until(1000)
        comp.compensate(start, 1000, aspect)
        feed.until(1033)
        val moved = start.map { move(it, k * 0.5 * 0.033, 0.0) }
        assertPoints(start, comp.compensate(moved, 1033, aspect), 2e-4f)
        feed.until(2500)
        // 1.4 s later: a new chain starts from what is seen now
        assertPoints(moved, comp.compensate(moved, 2500, aspect), 0f)
    }

    @Test
    fun gyroBiasIsLearnedWhileTheHeadIsStill() {
        val (comp, history) = compensator()
        val bias = 0.01f // rad/s, about 0.6°/s
        val feed = Feed(history, floatArrayOf(0f, bias, 0f))
        var t = 1000L
        val out = LinkedHashMap<Long, List<HandPoint>>()
        while (t <= 21_000L) {
            feed.until(t)
            out[t] = comp.compensate(start, t, aspect)
            t += 33
        }
        val at15 = out.entries.first { it.key >= 16_000 }.value[0].x
        val at20 = out.entries.last().value[0].x
        val uncompensated = k * bias * 5f
        assertTrue("drift ${abs(at20 - at15)} vs $uncompensated", abs(at20 - at15) < 0.05f * uncompensated)
    }

    @Test
    fun inactiveWithoutCalibrationOrGyro() {
        val comp = HeadMotionCompensator()
        assertSame(start, comp.compensate(start, 1000, aspect))
        comp.gyro = GyroHistory()
        assertSame(start, comp.compensate(start, 1033, aspect))
        assertEquals(false, comp.isActive)
    }
}
