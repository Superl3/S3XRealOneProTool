package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FistOpening]: a loose fist keeps dragging; an opening hand holds the touch where it is. */
class FistOpeningTest {

    private val enter = FistDetector.ENTER_THRESHOLD
    private val frameMs = 33L

    /** Feeds [curls] one frame apart; the result of each frame. */
    private fun run(o: FistOpening, vararg curls: Float): List<Boolean> {
        var t = 1000L
        return curls.map { o.update(it, t, enter).also { t += frameMs } }
    }

    @Test
    fun aLooseFistBetweenEntryAndExitKeepsFollowingTheHand() {
        // closed at 0.93, relaxes to 1.10 over five frames: 1–1.5 curl units per second
        assertTrue(run(FistOpening(), 0.93f, 0.97f, 1.02f, 1.06f, 1.10f, 1.10f, 1.09f).none { it })
    }

    @Test
    fun aFastRiseHoldsTheTouchThroughTheRestOfTheOpening() {
        // EMA of a hand opening: 0.90 → 1.125 → 1.24 → 1.30 (6.8, 3.5, 1.8 per second)
        val held = run(FistOpening(), 0.90f, 1.125f, 1.24f, 1.30f, 1.31f)
        assertEquals(listOf(false, true, true, true, true), held)
    }

    @Test
    fun theHoldEndsWhenTheHandClosesAgain() {
        val o = FistOpening()
        run(o, 0.90f, 1.125f, 1.24f)
        assertTrue(o.opening)
        assertFalse(run(o, 0.90f).single())
        // and a slow drift back up does not restart it
        assertTrue(run(o, 0.93f, 0.97f, 1.01f).none { it })
    }

    @Test
    fun theRateIsPerSecondNotPerFrame() {
        // 60 fps: the same 0.04 step per frame is twice the rate of 30 fps (2.5/s vs 1.2/s) and opens
        val o = FistOpening()
        var t = 1000L
        val r = listOf(0.93f, 0.97f, 1.01f).map { o.update(it, t, enter).also { t += 16 } }
        assertEquals(listOf(false, true, true), r)
    }

    @Test
    fun noCurlYetIsNotOpening() {
        assertFalse(FistOpening().update(Float.NaN, 1000, enter))
    }

    private fun assertEquals(expected: List<Boolean>, actual: List<Boolean>) = org.junit.Assert.assertEquals(expected, actual)
}
