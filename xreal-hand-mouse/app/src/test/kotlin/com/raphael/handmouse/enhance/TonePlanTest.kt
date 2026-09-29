package com.raphael.handmouse.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class TonePlanTest {

    @Test
    fun steadySceneGetsItsLevelsAndAMidtoneGamma() {
        val p = TonePlan.build(Array(100) { floatArrayOf(0.05f, 0.3f, 0.8f) })
        val gamma = (ln(TonePlan.TARGET_MID) / ln(0.25f / 0.75f))
        for (i in 0 until p.size) {
            assertEquals(0.05f, p.black[i], 1e-5f)
            assertEquals(0.8f, p.white[i], 1e-5f)
            assertEquals(gamma, p.gamma[i], 1e-4f)
        }
    }

    @Test
    fun limitsKeepNightAndHazeScenesPlausible() {
        // night: white is not stretched past MIN_WHITE, gamma stops at MIN_GAMMA
        val night = TonePlan.build(Array(10) { floatArrayOf(0f, 0.05f, 0.2f) })
        assertEquals(TonePlan.MIN_WHITE, night.white[5], 1e-5f)
        assertEquals(TonePlan.MIN_GAMMA, night.gamma[5], 1e-5f)
        // haze: black is not lifted past MAX_BLACK
        val haze = TonePlan.build(Array(10) { floatArrayOf(0.3f, 0.6f, 0.95f) })
        assertEquals(TonePlan.MAX_BLACK, haze.black[5], 1e-5f)
    }

    @Test
    fun levelsFollowTheSceneSlowly() {
        val p = TonePlan.build(Array(400) { if (it < 200) floatArrayOf(0.02f, 0.2f, 0.7f) else floatArrayOf(0.08f, 0.6f, 0.95f) })
        assertEquals(0.02f, p.black[0], 1e-5f)
        assertEquals(0.08f, p.black[399], 1e-5f)
        // no jump at the cut: within one frame the level moves by a small part of the step
        assertTrue(p.black[200] > 0.03f && p.black[200] < 0.07f)
        for (i in 1 until 400) assertTrue(abs(p.black[i] - p.black[i - 1]) < 0.06f * 0.1f)
    }

    @Test
    fun unanalysedFramesBorrowFromNeighboursOrStayNeutral() {
        val stats = Array<FloatArray?>(20) { null }
        val none = TonePlan.build(stats)
        assertEquals(0f, none.black[10], 1e-5f)
        assertEquals(1f, none.white[10], 1e-5f)
        assertEquals(1f, none.gamma[10], 1e-5f)
        stats[0] = floatArrayOf(0.05f, 0.3f, 0.8f)
        val one = TonePlan.build(stats)
        assertEquals(0.05f, one.black[19], 1e-5f)
    }

}
