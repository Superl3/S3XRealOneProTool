package com.raphael.handmouse.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic frames at the enhancer's analysis size (1920×1072 / 4). */
class MotionEstimatorTest {

    private val w = 480
    private val h = 268

    private val coarse = Random(7).let { r -> Array(80) { FloatArray(80) { r.nextFloat() } } }
    private val fine = Random(11).let { r -> Array(200) { FloatArray(200) { r.nextFloat() } } }

    private fun noise(g: Array<FloatArray>, x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun v(i: Int, j: Int) = g[Math.floorMod(j, g.size)][Math.floorMod(i, g[0].size)].toDouble()
        return (v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx) * (1 - fy) + (v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx) * fy
    }

    /** Value noise on 8 px and 3 px cells: texture everywhere, no repetition inside the frame. */
    private fun scene(x: Double, y: Double) = 170 * noise(coarse, x / 8, y / 8) + 60 * noise(fine, x / 3, y / 3) + 10

    /** Frame whose content moved by `p' = R(θ)·(p − c) + c + (dx, dy)` (px) from the scene's origin. */
    private fun frame(dx: Double = 0.0, dy: Double = 0.0, theta: Double = 0.0): ByteArray {
        val cx = w / 2.0
        val cy = h / 2.0
        val c = cos(theta)
        val s = sin(theta)
        return ByteArray(w * h) { i ->
            val rx = i % w - cx - dx
            val ry = i / w - cy - dy
            scene(c * rx + s * ry + cx, -s * rx + c * ry + cy).roundToInt().toByte()
        }
    }

    @Test
    fun subPixelTranslation() {
        val r = MotionEstimator(w, h).estimate(frame(), frame(3.4, -2.1))!!
        assertEquals(3.4f, r.dx * w, 0.3f)
        assertEquals(-2.1f, r.dy * w, 0.3f)
        assertEquals(0f, r.dTheta, 0.002f)
        assertEquals(1f, r.scale, 0.005f)
    }

    @Test
    fun rotationAboutTheCentre() {
        val r = MotionEstimator(w, h).estimate(frame(), frame(theta = 0.012))!!
        assertEquals(0.012f, r.dTheta, 0.002f)
        assertEquals(0f, r.dx * w, 0.3f)
        assertEquals(0f, r.dy * w, 0.3f)
    }

    @Test
    fun largePanWithinTheCoarseSearch() {
        val r = MotionEstimator(w, h).estimate(frame(), frame(14.0, -9.0, 0.005))!!
        assertEquals(14f, r.dx * w, 0.4f)
        assertEquals(-9f, r.dy * w, 0.4f)
        assertEquals(0.005f, r.dTheta, 0.002f)
    }

    @Test
    fun predictionExtendsTheSearch() {
        val prev = frame()
        val cur = frame(30.0, 4.0)
        val predict = MotionEstimator.Result(28f / w, 3f / w, 0f, 1f, 0, 0)
        val r = MotionEstimator(w, h).estimate(prev, cur, predict)
        assertNotNull(r)
        assertEquals(30f, r!!.dx * w, 0.4f)
        assertEquals(4f, r.dy * w, 0.4f)
    }

    @Test
    fun flatFramesHaveNoMotion() {
        val flat = ByteArray(w * h) { 128.toByte() }
        assertNull(MotionEstimator(w, h).estimate(flat, flat))
    }
}
