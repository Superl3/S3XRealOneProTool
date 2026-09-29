package com.raphael.handmouse.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StabilizationPathTest {

    private val width = 1920
    private val usable = 1072
    private val outH = 1080

    /** Per-frame motions from a camera path in px (x, y) and rad. */
    private fun motions(n: Int, path: (Int) -> FloatArray): Array<FloatArray?> = Array(n) { i ->
        if (i == 0) null else {
            val a = path(i - 1)
            val b = path(i)
            floatArrayOf((b[0] - a[0]) / width, (b[1] - a[1]) / width, b[2] - a[2])
        }
    }

    private fun plan(m: Array<FloatArray?>) = StabilizationPath.plan(m, width, usable, outH)

    @Test
    fun frameJitterIsCancelled() {
        val n = 300
        val path = { i: Int -> floatArrayOf(if (i % 2 == 0) 0f else 5f, 0f, 0f) }
        val p = plan(motions(n, path))
        assertTrue(p.active)
        // what the viewer sees moves by path − correction
        for (i in 50 until 250) {
            val a = path(i - 1)[0] - p.ux[i - 1]
            val b = path(i)[0] - p.ux[i]
            assertTrue("frame $i moves ${b - a} px", abs(b - a) < 0.3f)
        }
    }

    @Test
    fun walkingBobIsCancelledNotAttenuated() {
        // 2 Hz at 30 fps, ±20 px vertically and ±0.01 rad: inside the crop's slack
        val n = 300
        val path = { i: Int ->
            val s = sin(2 * PI * i / 15).toFloat()
            floatArrayOf(0f, 20f * s, 0.01f * s)
        }
        val p = plan(motions(n, path))
        for (i in 50 until 250) {
            assertEquals("y at $i", 0f, path(i)[1] - p.uy[i], 1f)
            assertEquals("θ at $i", 0f, path(i)[2] - p.phi[i], 0.001f)
        }
    }

    @Test
    fun steadyPanIsKept() {
        val p = plan(motions(300) { i -> floatArrayOf(3f * i, 0f, 0f) })
        for (i in 60 until 240) assertEquals("ux at $i", 0f, p.ux[i], 0.5f)
    }

    @Test
    fun correctionNeverLeavesTheSource() {
        // a 200 px jump (the camera knocked) and a steady roll
        val p = plan(motions(300) { i -> floatArrayOf(if (i < 150) 0f else 200f, if (i < 150) 0f else -120f, 0.002f * i) })
        val a = width / (2 * StabilizationPath.ZOOM)
        val b = outH / (2 * StabilizationPath.ZOOM)
        for (i in 0 until p.size) {
            val phi = abs(p.phi[i])
            assertTrue("x at $i", abs(p.ux[i]) <= width / 2f - a - b * phi + 1e-3f)
            assertTrue("y at $i", abs(p.uy[i]) <= usable / 2f - b - a * phi + 1e-3f)
        }
    }

    @Test
    fun plainCropWhenTooManyMotionsAreMissing() {
        val m = motions(100) { floatArrayOf(0f, 0f, 0f) }
        for (i in 1 until 100 step 2) m[i] = null
        val p = plan(m)
        assertFalse(p.active)
        assertEquals(outH.toFloat() / usable, p.zoom, 1e-6f)
        assertTrue(p.ux.all { it == 0f } && p.uy.all { it == 0f } && p.phi.all { it == 0f })
        assertFalse(plan(arrayOf<FloatArray?>(null)).active)
    }
}
