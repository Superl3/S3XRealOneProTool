package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.PressClassifier.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressClassifierTest {
    private val press = PressClassifier(slopPx = 10f, tapMs = 340L, longPressMs = 500L)

    @Test
    fun `a short press taps at the lift, not at the landing`() {
        press.down(1_000L, 50f, 50f)
        assertEquals(Outcome.NONE, press.tick(1_100L))
        assertEquals(Outcome.TAP, press.up(1_150L))
        assertNull(press.nextDeadlineMs)
    }

    @Test
    fun `a held press fires the long action once and the lift does nothing`() {
        press.down(1_000L, 50f, 50f)
        assertEquals(1_500L, press.nextDeadlineMs)
        assertEquals(Outcome.NONE, press.tick(1_499L))
        assertEquals(Outcome.LONG, press.tick(1_500L))
        assertEquals(Outcome.NONE, press.tick(1_700L))
        assertNull(press.nextDeadlineMs)
        assertEquals(Outcome.NONE, press.up(2_000L))
    }

    @Test
    fun `a lift after a missed deadline is the long press`() {
        press.down(1_000L, 50f, 50f)
        assertEquals(Outcome.LONG, press.up(1_600L))
    }

    @Test
    fun `sliding out of the slop takes the press back`() {
        press.down(1_000L, 50f, 50f)
        press.move(56f, 50f)
        assertEquals(1_500L, press.nextDeadlineMs)
        press.move(70f, 50f)
        assertNull(press.nextDeadlineMs)
        assertEquals(Outcome.NONE, press.tick(1_600L))
        assertEquals(Outcome.NONE, press.up(1_700L))
    }

    @Test
    fun `a filter-made cancel while still a tap is the tap`() {
        press.down(1_000L, 50f, 50f)
        assertEquals(Outcome.TAP, press.cancel(1_200L, 52f, 50f, byFilter = true))
    }

    @Test
    fun `other cancels do nothing`() {
        press.down(1_000L, 50f, 50f)
        assertEquals(Outcome.NONE, press.cancel(1_200L, 50f, 50f, byFilter = false))
        press.down(2_000L, 50f, 50f)
        assertEquals(Outcome.NONE, press.cancel(2_400L, 50f, 50f, byFilter = true))
        press.down(3_000L, 50f, 50f)
        assertEquals(Outcome.NONE, press.cancel(3_100L, 80f, 50f, byFilter = true))
        press.down(4_000L, 50f, 50f)
        assertEquals(Outcome.LONG, press.tick(4_500L))
        assertEquals(Outcome.NONE, press.cancel(4_200L, 50f, 50f, byFilter = true))
    }

    @Test
    fun `nothing without a landing`() {
        assertEquals(Outcome.NONE, press.up(1_000L))
        assertEquals(Outcome.NONE, press.cancel(1_000L, 0f, 0f, byFilter = true))
        assertEquals(Outcome.NONE, press.tick(1_000L))
    }
}
