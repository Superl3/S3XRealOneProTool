package com.raphael.handmouse.tracking

import com.raphael.handmouse.imu.GyroHistory
import com.raphael.handmouse.imu.ImuCameraCalibration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Head-motion compensation for the cursor (2026-09-29, setting "hm_head_comp", off by default).
 * Turning the head moves a still hand across the camera image like hand travel would; with the
 * glasses' gyro and an [ImuCameraCalibration] (fitted by the enhancer from a recording) that
 * image motion is predicted and removed from the landmarks before [PalmCursorReference].
 *
 * The frame-to-frame content motions are chained into one similarity `A` (rotation about the
 * image centre + translation, [ImuCameraCalibration]'s model) and every landmark is mapped back
 * through `A⁻¹`. Only differences reach the relative cursor, so `A` restarts after a gap.
 *
 * Limits (why it is off by default): it assumes the hand stays put in the world while the head
 * turns. Turning the whole body (chair, walking) turns the hand with the head — the image does
 * not move, the gyro does, and the compensation then moves the cursor the other way.
 *
 * Gyro bias: while the head is nearly still (< [STILL_RAD_S]) the mean rate is tracked with a
 * [BIAS_TAU_S] time constant and subtracted — a raw bias of 0.3°/s would otherwise drift the
 * cursor by tens of pixels per second.
 */
class HeadMotionCompensator {

    companion object {
        const val STILL_RAD_S = 0.03f
        const val BIAS_TAU_S = 3f
        /** Longer gaps between hand frames restart the chain. */
        const val GAP_RESET_MS = 1000L
    }

    var calibration: ImuCameraCalibration? = null
    var gyro: GyroHistory? = null

    private var lastMs: Long? = null
    private var angle = 0.0
    private var tx = 0.0
    private var ty = 0.0
    private val bias = FloatArray(3)

    val isActive: Boolean get() = calibration != null && gyro != null

    fun reset() {
        lastMs = null
        angle = 0.0
        tx = 0.0
        ty = 0.0
    }

    /**
     * [points] in isotropic image units ([HandTracker.Result.isoPoints]), [aspect] = height /
     * width. Returns them unchanged while inactive or while the gyro does not cover the interval.
     */
    fun compensate(points: List<HandPoint>, timestampMs: Long, aspect: Float): List<HandPoint> {
        val cal = calibration ?: return points
        val history = gyro ?: return points
        val prev = lastMs
        lastMs = timestampMs
        if (prev == null || timestampMs - prev > GAP_RESET_MS || timestampMs <= prev) {
            angle = 0.0; tx = 0.0; ty = 0.0
            return points
        }
        val lNs = cal.latencyMs * 1_000_000L
        val g = history.integrate(prev * 1_000_000L - lNs, timestampMs * 1_000_000L - lNs)
        if (g != null) {
            val dtS = (timestampMs - prev) / 1000f
            val rate = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) / dtS
            if (rate < STILL_RAD_S) {
                val k = minOf(1f, dtS / BIAS_TAU_S)
                for (i in 0..2) bias[i] += (g[i] / dtS - bias[i]) * k
            }
            for (i in 0..2) g[i] -= bias[i] * dtS
            val v = cal.motion(g)
            // A_i = T ∘ A_{i-1}: θ += dθ, D = R(dθ)·D + d
            val c = cos(v[2].toDouble())
            val s = sin(v[2].toDouble())
            val nx = c * tx - s * ty + v[0]
            val ny = s * tx + c * ty + v[1]
            tx = nx; ty = ny
            angle += v[2]
        }
        if (angle == 0.0 && tx == 0.0 && ty == 0.0) return points
        val cx = 0.5
        val cy = 0.5 * aspect
        val c = cos(-angle)
        val s = sin(-angle)
        return points.map { p ->
            // p0 = R(−θ)·(p − c − D) + c
            val x = p.x - cx - tx
            val y = p.y - cy - ty
            HandPoint((c * x - s * y + cx).toFloat(), (s * x + c * y + cy).toFloat(), p.z)
        }
    }
}
