package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPressCaptureTest {
    private val capture = AirPressCapture()

    @Test
    fun withoutACapture_samplesAreTheCallers() {
        assertFalse(capture.offer(10, 5f, 5f))
        assertNull(capture.end())
    }

    @Test
    fun joltIsDropped_laterMotionHeld_asANetVector() {
        capture.begin(startMs = 1000, joltUntilMs = 1080, expiresAtMs = 2000, shadowPx = 1000f)
        assertTrue(capture.offer(1020, 12f, -3f))
        assertTrue(capture.offer(1079, 2f, 1f))
        assertTrue(capture.offer(1080, 30f, 0f))
        assertTrue(capture.offer(1100, -10f, 4f))
        val result = capture.end()!!
        assertEquals(20f, result.dx, 0.001f)
        assertEquals(4f, result.dy, 0.001f)
        assertEquals(14f, result.joltX, 0.001f)
        assertEquals(-2f, result.joltY, 0.001f)
        assertFalse(capture.active)
        assertNull(capture.end())
    }

    @Test
    fun anotherFingersJolt_extendsTheDrop_butNeverShortensIt() {
        capture.begin(startMs = 0, joltUntilMs = 80, expiresAtMs = 1000, shadowPx = 1000f)
        capture.offer(100, 10f, 0f)
        capture.extendJolt(200)
        capture.offer(150, 7f, 0f)
        capture.extendJolt(120)
        capture.offer(199, 3f, 0f)
        capture.offer(200, 5f, 0f)
        val result = capture.end()!!
        assertEquals(15f, result.dx, 0.001f)
        assertEquals(10f, result.joltX, 0.001f)
    }

    @Test
    fun aCaptureNobodyEnds_lapses_andHandsTheSampleBack() {
        capture.begin(startMs = 0, joltUntilMs = 80, expiresAtMs = 1000, shadowPx = 1000f)
        assertTrue(capture.offer(999, 1f, 1f))
        assertFalse(capture.offer(1000, 4f, 4f))
        assertFalse(capture.active)
        assertNull(capture.end())
    }

    @Test
    fun shadowTime_isWhenTheHeldMotionFirstReachedTheThreshold() {
        capture.begin(startMs = 500, joltUntilMs = 580, expiresAtMs = 1500, shadowPx = 50f)
        capture.offer(560, 100f, 0f)
        capture.offer(600, 30f, 0f)
        capture.offer(640, 0f, 39f)
        capture.offer(680, 30f, 40f)
        capture.offer(720, -200f, 0f)
        // (30, 39) is just short of 50 px; (60, 79) at 680 ms is past it. The jolt never counts.
        assertEquals(180L, capture.end()!!.shadowAfterMs)

        capture.begin(startMs = 2000, joltUntilMs = 2080, expiresAtMs = 3000, shadowPx = 50f)
        capture.offer(2100, 30f, 0f)
        capture.offer(2120, -30f, 0f)
        capture.offer(2140, 30f, 0f)
        assertNull(capture.end()!!.shadowAfterMs)
    }

    @Test
    fun begin_replacesACaptureUnderWay() {
        capture.begin(startMs = 0, joltUntilMs = 0, expiresAtMs = 1000, shadowPx = 1000f)
        capture.offer(10, 40f, 0f)
        capture.begin(startMs = 100, joltUntilMs = 100, expiresAtMs = 1100, shadowPx = 1000f)
        capture.offer(120, 5f, 0f)
        assertEquals(5f, capture.end()!!.dx, 0.001f)
    }
}
