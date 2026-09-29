package com.raphael.handmouse.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {

    @Test
    fun gaussianKeepsConstantsAndSpreadsAnImpulseSymmetrically() {
        assertArrayEquals(FloatArray(50) { 3f }, Signal.gaussian(FloatArray(50) { 3f }, 5f), 1e-5f)
        val impulse = FloatArray(61).also { it[30] = 1f }
        val out = Signal.gaussian(impulse, 4f)
        assertEquals(1f, out.sum(), 1e-5f)
        for (d in 1..12) assertEquals(out[30 - d], out[30 + d], 1e-7f)
        assertTrue(out[30] > out[31] && out[31] > out[36])
        assertArrayEquals(impulse, Signal.gaussian(impulse, 0f), 0f)
        assertEquals(0, Signal.gaussian(FloatArray(0), 3f).size)
    }

    @Test
    fun fillGapsTakesTheNearestValidValue() {
        val x = floatArrayOf(1f, 0f, 0f, 4f, 0f)
        val valid = booleanArrayOf(true, false, false, true, false)
        assertArrayEquals(floatArrayOf(1f, 1f, 4f, 4f, 4f), Signal.fillGaps(x, valid, 9f), 0f)
        // a tie goes to the earlier one; nothing valid → the fallback
        assertArrayEquals(floatArrayOf(1f, 1f, 3f), Signal.fillGaps(floatArrayOf(1f, 0f, 3f), booleanArrayOf(true, false, true), 9f), 0f)
        assertArrayEquals(floatArrayOf(9f, 9f), Signal.fillGaps(floatArrayOf(1f, 2f), booleanArrayOf(false, false), 9f), 0f)
    }

    @Test
    fun lumaAndPercentiles() {
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        val y = ByteArray(4)
        val hist = IntArray(256)
        Luma.fromArgb(pixels, 2, 2, y, hist)
        assertArrayEquals(intArrayOf(255, 0, 76, 149), y.map { it.toInt() and 0xFF }.toIntArray())
        assertEquals(4, hist.sum())

        val h = IntArray(256).also { it[10] = 100; it[200] = 100 }
        val p = Luma.percentiles(h, 0.01f, 0.5f, 0.99f)
        assertArrayEquals(floatArrayOf(10 / 255f, 10 / 255f, 200 / 255f), p, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), Luma.percentiles(IntArray(256), 0.5f, 0.9f), 0f)
    }
}
