package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandFeaturesTest {

    /** Wrist at the origin, PIPs at x = 1, so each finger's curl is its tip's x. */
    private fun hand(tips: FloatArray, thumb: HandPoint = HandPoint(1f, 0.1f, 0f)): List<HandPoint> {
        val points = MutableList(21) { HandPoint(0f, 0f, 0f) }
        points[5] = HandPoint(1f, 0f, 0f)
        for (pip in intArrayOf(6, 10, 14, 18)) points[pip] = HandPoint(1f, 0f, 0f)
        intArrayOf(8, 12, 16, 20).forEachIndexed { i, tip -> points[tip] = HandPoint(tips[i], 0f, 0f) }
        points[4] = thumb
        return points
    }

    @Test
    fun `curl is tip over PIP distance per finger and maxCurl the most extended`() {
        val f = HandFeatures.from(hand(floatArrayOf(1.3f, 0.9f, 0.8f, 1.1f)))
        assertEquals(1.3f, f.curl[0], 1e-5f)
        assertEquals(0.9f, f.curl[1], 1e-5f)
        assertEquals(0.8f, f.curl[2], 1e-5f)
        assertEquals(1.1f, f.curl[3], 1e-5f)
        assertEquals(1.3f, f.maxCurl, 1e-5f)
    }

    @Test
    fun `thumb extension and direction use the index MCP distance as scale`() {
        // Thumb 1.2 above the index MCP (image y grows downward, so "up" is negative y).
        val f = HandFeatures.from(hand(floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f), HandPoint(1f, -1.2f, 0f)))
        assertEquals(1.2f, f.thumbExtension, 1e-5f)
        assertEquals(1.2f, f.thumbUp, 1e-5f)
    }

    @Test
    fun `feature overloads give the same result as the landmark ones`() {
        val fist = hand(floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f))
        val a = FistDetector()
        val b = FistDetector()
        repeat(30) { i ->
            val t = i * 1000L / 60
            assertEquals(a.update(fist, t), b.update(HandFeatures.from(fist), t))
        }
        assertTrue(a.isFist)
    }

    @Test
    fun `allowEnter false keeps a new fist from starting`() {
        val fist = hand(floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f))
        val d = FistDetector()
        repeat(30) { i -> d.update(HandFeatures.from(fist), i * 1000L / 60, allowEnter = false) }
        assertFalse(d.isFist)
        assertEquals(0f, d.enterProgress, 0f)
    }
}
