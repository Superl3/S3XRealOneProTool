package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sign conventions, in the phone's sensor frame (+x right edge, +y top edge, +z out of the
 * screen). The gyro is right-handed: a positive rate about an axis turns counter-clockwise when
 * seen from that axis' tip. Gravity readings point up at rest.
 */
class AirMouseMathTest {
    private val g = 9.81f
    private val s30 = sin(Math.toRadians(30.0)).toFloat()
    private val c30 = cos(Math.toRadians(30.0)).toFloat()
    private val gain = AirMouseMath.BASE_GAIN_PX_PER_RAD
    private val dt = 0.01f

    /** Pixel motion of a 1 rad/s turn about the mapped axis for one [dt] sample. */
    private val oneRadPerSec = (1f - AirMouseMath.DEAD_ZONE_RAD_S) * gain * dt

    private fun delta(
        gyro: Vec3,
        gravity: Vec3,
        phys: Int = 0,
        dtSec: Float = dt,
        gainPx: Float = gain,
        headYaw: Float = 0f,
        headPitch: Float = 0f,
        deadZone: Float = AirMouseMath.DEAD_ZONE_RAD_S,
    ): Pair<Float, Float> =
        AirMouseMath.pointerDelta(gyro, gravity, phys, dtSec, gainPx, headYaw, headPitch, deadZone)

    // --- Remote-control pose: screen up, top edge raised 30° and aimed forward. ---

    private val remoteGravity = Vec3(0f, s30 * g, c30 * g)

    @Test
    fun remotePose_yawLeft_movesPointerLeftOnly() {
        val (dx, dy) = delta(remoteGravity.normalized(), remoteGravity)
        assertEquals(-oneRadPerSec, dx, 1e-3f)
        assertEquals(0f, dy, 1e-3f)
    }

    @Test
    fun remotePose_yawRight_movesPointerRight() {
        val (dx, dy) = delta(remoteGravity.normalized() * -1f, remoteGravity)
        assertEquals(oneRadPerSec, dx, 1e-3f)
        assertEquals(0f, dy, 1e-3f)
    }

    @Test
    fun remotePose_raisingTheTop_movesPointerUp() {
        // +x rotation turns +y (the top) toward +z, which is mostly up in this pose.
        val (dx, dy) = delta(Vec3(1f, 0f, 0f), remoteGravity)
        assertEquals(0f, dx, 1e-3f)
        assertEquals(-oneRadPerSec, dy, 1e-3f)
    }

    // --- Camera pose: upright, screen facing the user, aimed with the back (-z). ---

    private val cameraGravity = Vec3(0f, g, 0f)

    @Test
    fun cameraPose_yawLeft_movesPointerLeftOnly() {
        val (dx, dy) = delta(Vec3(0f, 1f, 0f), cameraGravity)
        assertEquals(-oneRadPerSec, dx, 1e-3f)
        assertEquals(0f, dy, 1e-3f)
    }

    @Test
    fun cameraPose_tiltingTheBackUp_movesPointerUp() {
        // +x rotation turns -z (the back, aimed forward) toward +y, which is up in this pose.
        val (dx, dy) = delta(Vec3(1f, 0f, 0f), cameraGravity)
        assertEquals(0f, dx, 1e-3f)
        assertEquals(-oneRadPerSec, dy, 1e-3f)
    }

    // --- Landscape hold (phys = 1): right edge forward, top edge to the user's left. ---

    private val landscapeGravity = Vec3(s30 * g, 0f, c30 * g)

    @Test
    fun landscapeHold_yawLeft_movesPointerLeftOnly() {
        val (dx, dy) = delta(landscapeGravity.normalized(), landscapeGravity, phys = 1)
        assertEquals(-oneRadPerSec, dx, 1e-3f)
        assertEquals(0f, dy, 1e-3f)
    }

    @Test
    fun landscapeHold_raisingTheForwardEdge_movesPointerUp() {
        // up = (½, 0, √3/2); p = horiz(-z) + horiz(+x) ∝ (√3/2, 0, -½); right = p̂ × up = (0, -1, 0),
        // the user's right (the top edge points left). A positive y rate turns +x toward -z
        // (lowers the forward edge), so raising it is ω = -y: ω·right = +1 → dy < 0.
        val (dx, dy) = delta(Vec3(0f, -1f, 0f), landscapeGravity, phys = 1)
        assertEquals(0f, dx, 1e-3f)
        assertEquals(-oneRadPerSec, dy, 1e-3f)
    }

    @Test
    fun landscapeHold_reportedTurnKeepsFullPitch() {
        // Same pose with the hold not reported (phys = 0): the top (+y) points to the user's left,
        // pulls the aiming direction sideways and tilts the pitch axis, so raising the forward
        // edge moves the pointer less than with the turn reported.
        val (_, dyKnown) = delta(Vec3(0f, -1f, 0f), landscapeGravity, phys = 1)
        val (_, dyUnknown) = delta(Vec3(0f, -1f, 0f), landscapeGravity, phys = 0)
        assertTrue(abs(dyKnown) > abs(dyUnknown))
    }

    @Test
    fun landscapeCameraPose_tiltingTheBackUp_movesPointerUp() {
        // Upright, right edge up, screen facing the user: right = -y; raising the back is ω = -y.
        val gravity = Vec3(g, 0f, 0f)
        val (dx, dy) = delta(Vec3(0f, -1f, 0f), gravity, phys = 1)
        assertEquals(0f, dx, 1e-3f)
        assertEquals(-oneRadPerSec, dy, 1e-3f)
        val (yawDx, yawDy) = delta(Vec3(1f, 0f, 0f), gravity, phys = 1)
        assertEquals(-oneRadPerSec, yawDx, 1e-3f)
        assertEquals(0f, yawDy, 1e-3f)
    }

    @Test
    fun reverseLandscapeHold_raisingTheForwardEdge_movesPointerUp() {
        // phys = 3: left edge (-x) forward, top edge to the user's right; raising -x is ω = +y.
        val gravity = Vec3(-s30 * g, 0f, c30 * g)
        val (dx, dy) = delta(Vec3(0f, 1f, 0f), gravity, phys = 3)
        assertEquals(0f, dx, 1e-3f)
        assertEquals(-oneRadPerSec, dy, 1e-3f)
    }

    // --- Dead zone, gain, dt. ---

    @Test
    fun belowTheDeadZone_noMotion() {
        val (dx, dy) = delta(Vec3(0.029f, 0.029f, 0.029f), cameraGravity)
        assertEquals(0f, dx, 0f)
        assertEquals(0f, dy, 0f)
    }

    @Test
    fun justAboveTheDeadZone_smallMotionWithoutAJump() {
        val (dx, dy) = delta(Vec3(0.031f, 0.031f, 0f), cameraGravity)
        assertTrue(dx < 0f && dx > -0.05f)
        assertTrue(dy < 0f && dy > -0.05f)
        assertEquals(-0.001f * gain * dt, dx, 1e-4f)
    }

    @Test
    fun softDeadZone_subtractsTheThresholdKeepingTheSign() {
        assertEquals(0f, AirMouseMath.softDeadZone(0.02f), 0f)
        assertEquals(0f, AirMouseMath.softDeadZone(-0.03f), 0f)
        assertEquals(0.02f, AirMouseMath.softDeadZone(0.05f), 1e-6f)
        assertEquals(-0.02f, AirMouseMath.softDeadZone(-0.05f), 1e-6f)
        assertEquals(0.4f, AirMouseMath.softDeadZone(0.5f, dz = 0.1f), 1e-6f)
    }

    @Test
    fun motionScalesLinearlyWithGainAndDt() {
        val gyro = Vec3(0.4f, 0.7f, 0f)
        val (dx, dy) = delta(gyro, cameraGravity)
        val (dx2g, dy2g) = delta(gyro, cameraGravity, gainPx = gain * 2f)
        val (dx2t, dy2t) = delta(gyro, cameraGravity, dtSec = dt * 2f)
        assertEquals(dx * 2f, dx2g, 1e-4f)
        assertEquals(dy * 2f, dy2g, 1e-4f)
        assertEquals(dx * 2f, dx2t, 1e-4f)
        assertEquals(dy * 2f, dy2t, 1e-4f)
    }

    @Test
    fun dtIsClampedTo50Ms() {
        val gyro = Vec3(0.4f, 0.7f, 0f)
        val (dxMax, dyMax) = delta(gyro, cameraGravity, dtSec = 0.05f)
        val (dxLong, dyLong) = delta(gyro, cameraGravity, dtSec = 0.2f)
        assertEquals(dxMax, dxLong, 1e-6f)
        assertEquals(dyMax, dyLong, 1e-6f)
        val (dxNeg, dyNeg) = delta(gyro, cameraGravity, dtSec = -0.01f)
        assertEquals(0f, dxNeg, 0f)
        assertEquals(0f, dyNeg, 0f)
    }

    // --- Head-relative pointing (glasses IMU rates, HeadRates conventions). ---

    @Test
    fun bodyTurn_phoneAndHeadTurnTogether_noMotion() {
        // Turning the whole body left at 0.8 rad/s with the phone held still in front.
        val (dx, dy) = delta(remoteGravity.normalized() * 0.8f, remoteGravity, headYaw = 0.8f)
        assertEquals(0f, dx, 1e-4f)
        assertEquals(0f, dy, 1e-4f)
        val (cdx, cdy) = delta(Vec3(0f, -1.2f, 0f), cameraGravity, headYaw = -1.2f)
        assertEquals(0f, cdx, 1e-4f)
        assertEquals(0f, cdy, 1e-4f)
    }

    @Test
    fun walkingBob_phoneAndHeadPitchTogether_noMotion() {
        // In the remote pose ω = +x raises the top (ω·right = 1); the head nods up with it.
        val (dx, dy) = delta(Vec3(0.5f, 0f, 0f), remoteGravity, headPitch = 0.5f)
        assertEquals(0f, dx, 1e-4f)
        assertEquals(0f, dy, 1e-4f)
    }

    @Test
    fun bodyTurnWhileAiming_onlyTheRelativeTurnMoves() {
        // Body turning left at 1 rad/s while the hand aims 0.2 rad/s further left.
        val (dx, dy) = delta(remoteGravity.normalized() * 1.2f, remoteGravity, headYaw = 1f)
        assertEquals(-(0.2f - AirMouseMath.DEAD_ZONE_RAD_S) * gain * dt, dx, 1e-3f)
        assertEquals(0f, dy, 1e-4f)
    }

    @Test
    fun headStill_sameAsThePhoneOnlyMapping() {
        val cases = listOf(
            Triple(Vec3(0.4f, 0.7f, -0.2f), remoteGravity, 0),
            Triple(Vec3(1f, 0f, 0f), cameraGravity, 0),
            Triple(Vec3(0f, -1f, 0.3f), landscapeGravity, 1),
        )
        for ((gyro, gravity, phys) in cases) {
            val before = AirMouseMath.pointerDelta(gyro, gravity, phys, dt, gain)
            val still = AirMouseMath.pointerDelta(gyro, gravity, phys, dt, gain, 0f, 0f)
            assertEquals(before, still)
        }
    }

    @Test
    fun oppositeRotationsAdd() {
        // Phone turns left 0.5 rad/s while the head turns right 0.5 rad/s: 1 rad/s relative.
        val (dx, dy) = delta(remoteGravity.normalized() * 0.5f, remoteGravity, headYaw = -0.5f)
        assertEquals(-oneRadPerSec, dx, 1e-3f)
        assertEquals(0f, dy, 1e-4f)
        // Phone front up 0.5 rad/s while the head nods down 0.5 rad/s.
        val (pdx, pdy) = delta(Vec3(0.5f, 0f, 0f), remoteGravity, headPitch = -0.5f)
        assertEquals(0f, pdx, 1e-4f)
        assertEquals(-oneRadPerSec, pdy, 1e-3f)
    }

    @Test
    fun headOnlyTurn_movesThePointerTheOtherWay() {
        // Phone still, head turns left: relative to the head the phone turned right.
        val (dx, dy) = delta(Vec3(0f, 0f, 0f), remoteGravity, headYaw = 1f)
        assertEquals(oneRadPerSec, dx, 1e-3f)
        assertEquals(0f, dy, 1e-4f)
        val (pdx, pdy) = delta(Vec3(0f, 0f, 0f), remoteGravity, headPitch = 1f)
        assertEquals(0f, pdx, 1e-4f)
        assertEquals(oneRadPerSec, pdy, 1e-3f)
    }

    @Test
    fun deadZoneAppliesAfterTheSubtraction() {
        // Both turn fast but differ by less than the dead zone: no motion.
        val (dx, _) = delta(remoteGravity.normalized() * 1f, remoteGravity, headYaw = 0.98f)
        assertEquals(0f, dx, 0f)
        // The phone alone is below the dead zone, but relative to the head it is above it.
        val (rdx, _) = delta(remoteGravity.normalized() * 0.02f, remoteGravity, headYaw = -0.04f)
        assertEquals(-(0.06f - AirMouseMath.DEAD_ZONE_RAD_S) * gain * dt, rdx, 1e-4f)
    }

    @Test
    fun deadZoneIsConfigurable() {
        val yawLeft = remoteGravity.normalized()
        val (wide, _) = delta(yawLeft, remoteGravity, deadZone = 0.1f)
        assertEquals(-0.9f * gain * dt, wide, 1e-3f)
        val (none, _) = delta(yawLeft, remoteGravity, deadZone = 0f)
        assertEquals(-gain * dt, none, 1e-3f)
        val (negative, _) = delta(yawLeft, remoteGravity, deadZone = -0.5f)
        assertEquals(none, negative, 0f)
        val (nan, _) = delta(yawLeft, remoteGravity, deadZone = Float.NaN)
        assertEquals(none, nan, 0f)
    }

    @Test
    fun nonFiniteHeadRates_areIgnored() {
        val gyro = Vec3(0.4f, 0.7f, 0f)
        val plain = delta(gyro, remoteGravity)
        assertEquals(plain, delta(gyro, remoteGravity, headYaw = Float.NaN, headPitch = Float.NaN))
        assertEquals(
            plain,
            delta(gyro, remoteGravity, headYaw = Float.POSITIVE_INFINITY, headPitch = Float.NEGATIVE_INFINITY),
        )
    }

    @Test
    fun degeneratePose_staysStillEvenWithHeadRates() {
        val gravity = Vec3(0f, -0.70710677f * g, 0.70710677f * g)
        val (dx, dy) = delta(Vec3(0f, 0f, 0f), gravity, headYaw = 1f, headPitch = 1f)
        assertEquals(0f, dx, 0f)
        assertEquals(0f, dy, 0f)
    }

    // --- Degenerate poses. ---

    @Test
    fun zeroGravity_noMotion() {
        val (dx, dy) = delta(Vec3(1f, 1f, 1f), Vec3(0f, 0f, 0f))
        assertEquals(0f, dx, 0f)
        assertEquals(0f, dy, 0f)
    }

    @Test
    fun aimingDirectionCancels_noMotion() {
        // Screen up with the top edge lowered 45°: horiz(-z) points back, horiz(+y) forward.
        val gravity = Vec3(0f, -0.70710677f * g, 0.70710677f * g)
        val (dx, dy) = delta(Vec3(1f, 1f, 1f), gravity)
        assertEquals(0f, dx, 0f)
        assertEquals(0f, dy, 0f)
    }

    // --- topDirection. ---

    @Test
    fun topDirection_perQuarterTurn() {
        assertEquals(Vec3(0f, 1f, 0f), AirMouseMath.topDirection(0))
        assertEquals(Vec3(1f, 0f, 0f), AirMouseMath.topDirection(1))
        assertEquals(Vec3(0f, -1f, 0f), AirMouseMath.topDirection(2))
        assertEquals(Vec3(-1f, 0f, 0f), AirMouseMath.topDirection(3))
    }

    @Test
    fun topDirection_normalizesModFour() {
        assertEquals(AirMouseMath.topDirection(3), AirMouseMath.topDirection(-1))
        assertEquals(AirMouseMath.topDirection(2), AirMouseMath.topDirection(-2))
        assertEquals(AirMouseMath.topDirection(0), AirMouseMath.topDirection(-4))
        assertEquals(AirMouseMath.topDirection(1), AirMouseMath.topDirection(5))
        assertEquals(AirMouseMath.topDirection(2), AirMouseMath.topDirection(6))
        assertEquals(AirMouseMath.topDirection(3), AirMouseMath.topDirection(403))
    }

    // --- Vec3. ---

    @Test
    fun vec3_basicAlgebra() {
        val x = Vec3(1f, 0f, 0f)
        val y = Vec3(0f, 1f, 0f)
        assertEquals(Vec3(0f, 0f, 1f), x cross y)
        assertEquals(0f, x dot y, 0f)
        assertEquals(Vec3(1f, 1f, 0f), x + y)
        assertEquals(Vec3(1f, -1f, 0f), x - y)
        assertEquals(Vec3(3f, 0f, 0f), x * 3f)
        assertEquals(5f, Vec3(3f, 4f, 0f).length, 1e-6f)
        assertEquals(1f, Vec3(3f, 4f, 12f).normalized().length, 1e-6f)
        assertEquals(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f).normalized())
    }
}
