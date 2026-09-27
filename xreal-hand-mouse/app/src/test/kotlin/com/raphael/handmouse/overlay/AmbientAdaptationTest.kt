package com.raphael.handmouse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientAdaptationTest {

    private fun LightAdaptation.run(lux: Float, ms: Long) {
        var t = 0L
        while (t < ms) { step(lux, 250); t += 250 }
    }

    @Test
    fun darkensFastBrightensSlowly() {
        val a = LightAdaptation()
        a.step(300f, 0)
        a.run(0f, 3000)
        assertTrue("dark after 3 s: ${a.lux}", a.lux < 5f)

        val b = LightAdaptation()
        b.step(0f, 0)
        b.run(300f, 3000)
        assertTrue("still dim after 3 s: ${b.lux}", b.lux < 5f)
        b.run(300f, 60_000)
        assertTrue("bright after a minute: ${b.lux}", b.lux > 250f)
    }

    @Test
    fun briefFlashBarelyMoves() {
        val a = LightAdaptation()
        a.step(2f, 0)
        a.run(1000f, 500)
        assertTrue(a.lux < 5f)
    }

    @Test
    fun dimMapping() {
        assertEquals(0f, dimForLux(50f, 20f, 0.6f), 1e-4f)
        assertEquals(0.6f, dimForLux(0f, 20f, 0.6f), 1e-4f)
    }

    @Test
    fun pocketNeedsDarkAndNotFaceUp() {
        val d = CoverDetector(enterMs = 1000, exitMs = 500)
        // dark room, phone lying face-up: not covered
        for (t in 0L..3000L step 250) d.update(t, 1f, 0.95f, false)
        assertFalse(d.covered)
        // into the pocket: upright and dark
        d.update(4000, 0f, 0.1f, false)
        assertTrue(d.pending)
        assertFalse(d.covered)
        d.update(5000, 0f, 0.1f, false)
        assertTrue(d.covered)
        // taken out into light
        d.update(6000, 200f, 0.6f, false)
        assertTrue(d.covered) // exit also needs to hold briefly
        d.update(6500, 200f, 0.6f, false)
        assertFalse(d.covered)
    }

    @Test
    fun faceDownOrProximityCovers() {
        val d = CoverDetector(enterMs = 1000, exitMs = 500)
        d.update(0, 0f, -0.98f, null)
        d.update(1000, 0f, -0.98f, null)
        assertTrue(d.covered)
        val p = CoverDetector(enterMs = 1000, exitMs = 500)
        p.update(0, 50f, 0.9f, true)
        p.update(1000, 50f, 0.9f, true)
        assertTrue(p.covered)
    }

    @Test
    fun shortDipDoesNotCover() {
        val d = CoverDetector(enterMs = 1000, exitMs = 500)
        d.update(0, 0f, 0.1f, false)
        d.update(500, 100f, 0.1f, false)
        d.update(1500, 0f, 0.1f, false)
        assertFalse(d.covered)
    }
}
