package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Glasses IMU frame as measured (see [HeadRateEstimator]): +x the wearer's right, the
 * accelerometer reads +g along up (mostly −y when worn), right-handed gyro. A positive rotation
 * about up turns the head left; about the right axis it raises the nose.
 */
class HeadRateEstimatorTest {
    private val g = 9.81f
    private val stepNs = 1_000_000L
    private val stepSec = 0.001f

    private val levelUp = Vec3(0f, -1f, 0f)

    /** As worn in the recordings: the IMU pitched about 35° in the frame. */
    private val tiltedUp = Vec3(0f, -cos(35.0 * PI / 180).toFloat(), sin(35.0 * PI / 180).toFloat())

    /**
     * Simulated head: [up] is the true up in the glasses frame; each step turns the head at the
     * given rate for 1 ms and feeds the IMU sample taken at its end.
     */
    private inner class Head(var up: Vec3) {
        val est = HeadRateEstimator()
        var tNs = 5_000_000_000L
        var lastResult = false

        fun run(samples: Int, omega: (Int) -> Vec3, extraAccel: (Int) -> Vec3 = { Vec3(0f, 0f, 0f) }) {
            repeat(samples) { i ->
                val w = omega(i)
                up = (up - (w cross up) * stepSec).normalized()
                tNs += stepNs
                lastResult = est.update(w, up * g + extraAccel(i), tNs)
            }
        }

        fun upErrorDeg(): Double {
            val e = est.up ?: return 180.0
            return acos((e dot up).toDouble().coerceIn(-1.0, 1.0)) * 180 / PI
        }
    }

    private fun horizontalRight(up: Vec3): Vec3 =
        (HeadRateEstimator.GLASSES_RIGHT - up * (HeadRateEstimator.GLASSES_RIGHT dot up)).normalized()

    @Test
    fun levelHead_turningLeft_isPositiveYaw() {
        val head = Head(levelUp)
        head.run(200, { levelUp * 1f })
        assertTrue(head.lastResult)
        assertEquals(1f, head.est.yaw, 1e-3f)
        assertEquals(0f, head.est.pitch, 1e-3f)
    }

    @Test
    fun levelHead_turningRight_isNegativeYaw() {
        val head = Head(levelUp)
        head.run(200, { levelUp * -0.7f })
        assertEquals(-0.7f, head.est.yaw, 1e-3f)
        assertEquals(0f, head.est.pitch, 1e-3f)
    }

    @Test
    fun levelHead_noseUp_isPositivePitch() {
        // +x is the wearer's right: a positive x rotation raises the nose (up tilts toward +z).
        val head = Head(levelUp)
        head.run(200, { Vec3(1f, 0f, 0f) })
        assertEquals(0f, head.est.yaw, 2e-3f)
        assertEquals(1f, head.est.pitch, 2e-3f)
        assertTrue(head.up.z > 0f)
    }

    @Test
    fun tiltedImu_bodyTurnIsYawOnly_nodIsPitchOnly() {
        val turn = Head(tiltedUp)
        turn.run(200, { tiltedUp * 1f })
        assertEquals(1f, turn.est.yaw, 1e-3f)
        assertEquals(0f, turn.est.pitch, 1e-3f)

        val nod = Head(tiltedUp)
        nod.run(200, { Vec3(-0.8f, 0f, 0f) })
        assertEquals(0f, nod.est.yaw, 2e-3f)
        assertEquals(-0.8f, nod.est.pitch, 2e-3f)
    }

    @Test
    fun rolledHead_usesTheHorizontalRightAxis() {
        val up = Vec3(0.34f, -0.94f, 0f).normalized()
        val turn = Head(up)
        turn.run(200, { up * 1f })
        assertEquals(1f, turn.est.yaw, 1e-3f)
        assertEquals(0f, turn.est.pitch, 1e-3f)

        val right = horizontalRight(up)
        val nod = Head(up)
        nod.run(200, { right * 1f })
        assertEquals(0f, nod.est.yaw, 5e-3f)
        assertEquals(1f, nod.est.pitch, 1e-2f)
    }

    @Test
    fun turnAfterAQuickNod_noCrossTalk() {
        // Nose up 30° quickly, then turn left about world up: the gyro carries "up" through the
        // nod, so the turn is not read as pitch while the accelerometer low-pass catches up.
        val head = Head(levelUp)
        head.run(524, { Vec3(1f, 0f, 0f) })
        assertTrue(head.upErrorDeg() < 0.5)
        head.run(150, { head.up * 1f })
        assertEquals(1f, head.est.yaw, 5e-3f)
        assertEquals(0f, head.est.pitch, 5e-3f)
    }

    @Test
    fun walkingSwayAndBob_barelyDisturbUp() {
        // 2 Hz steps: 2 m/s² sideways sway and 3 m/s² vertical bob, while turning left at 1 rad/s.
        val head = Head(levelUp)
        val f = 2.0 * 2 * PI / 1000
        head.run(
            3000,
            { levelUp * 1f },
            { i ->
                Vec3(2f * sin(f * i).toFloat(), 0f, 0f) + levelUp * (3f * cos(f * i).toFloat())
            },
        )
        assertTrue("up error ${head.upErrorDeg()}°", head.upErrorDeg() < 3.0)
        assertEquals(1f, head.est.yaw, 0.01f)
        assertEquals(0f, head.est.pitch, 0.06f)
    }

    @Test
    fun accelerationSpikesDoNotPullUp() {
        val head = Head(levelUp)
        head.run(100, { Vec3(0f, 0f, 0f) })
        // A hard knock: 2 g sideways for 50 ms (far from 1 g, so ignored).
        head.run(50, { Vec3(0f, 0f, 0f) }, { Vec3(2f * g, g, 0f) })
        assertTrue("up error ${head.upErrorDeg()}°", head.upErrorDeg() < 0.1)
    }

    @Test
    fun ratesAreSmoothedOverAboutTwentyMs() {
        val head = Head(levelUp)
        head.run(1, { Vec3(0f, 0f, 0f) })
        assertEquals(0f, head.est.yaw, 0f)
        head.run(20, { levelUp * 1f })
        // EMA with τ = 20 ms at 1 kHz: 1 − (20/21)^20 ≈ 0.62 after 20 ms.
        assertTrue("yaw ${head.est.yaw}", head.est.yaw in 0.58f..0.67f)
        head.run(100, { levelUp * 1f })
        assertTrue("yaw ${head.est.yaw}", head.est.yaw > 0.99f)
    }

    @Test
    fun firstSampleWithoutUsableAccel_givesNoRates() {
        val est = HeadRateEstimator()
        assertFalse(est.update(Vec3(0f, -1f, 0f), Vec3(0f, 0f, 0f), 1_000_000L))
        assertNull(est.up)
        assertFalse(est.update(Vec3(0f, -1f, 0f), Vec3(0f, -3f * g, 0f), 2_000_000L))
        assertTrue(est.update(Vec3(0f, -1f, 0f), Vec3(0f, -g, 0f), 3_000_000L))
        // The first rates are taken as they are (no ramp from zero).
        assertEquals(1f, est.yaw, 1e-6f)
    }

    @Test
    fun nonFiniteGyro_isIgnored() {
        val head = Head(levelUp)
        head.run(100, { levelUp * 0.5f })
        val yaw = head.est.yaw
        val up = head.est.up
        assertTrue(head.est.update(Vec3(Float.NaN, 0f, 0f), levelUp * g, head.tNs + stepNs))
        assertEquals(yaw, head.est.yaw, 0f)
        assertEquals(up, head.est.up)
    }

    @Test
    fun clockJumps_areNotIntegrated() {
        val head = Head(levelUp)
        head.run(100, { levelUp * 0.5f })
        val est = head.est
        // Glasses reboot: device time goes back. No interval, nothing changes.
        val yaw = est.yaw
        assertTrue(est.update(Vec3(3f, 3f, 3f), levelUp * g, 1_000L))
        assertEquals(yaw, est.yaw, 0f)
        // A 10 s gap is integrated as at most 50 ms.
        assertTrue(est.update(Vec3(0f, 0f, 2f), levelUp * g, 10_000_001_000L))
        val u = est.up!!
        assertEquals(1f, u.length, 1e-4f)
        val tiltDeg = acos((u dot levelUp).toDouble().coerceIn(-1.0, 1.0)) * 180 / PI
        assertTrue("tilt $tiltDeg°", tiltDeg < 0.05 * 2 * 180 / PI + 0.1)
        assertTrue(est.yaw.isFinite() && est.pitch.isFinite())
    }

    @Test
    fun reset_startsOver() {
        val head = Head(levelUp)
        head.run(100, { levelUp * 1f })
        head.est.reset()
        assertNull(head.est.up)
        assertEquals(0f, head.est.yaw, 0f)
        assertTrue(head.est.update(levelUp * -0.3f, levelUp * g, head.tNs + stepNs))
        assertEquals(-0.3f, head.est.yaw, 1e-6f)
    }
}
