package com.raphael.handmouse.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseSourceTest {
    private val move = intArrayOf(MouseEventPolicy.EV_REL, MouseEventPolicy.REL_X, 3, MouseEventPolicy.EV_SYN, 0, 0)
    private val press = intArrayOf(MouseEventPolicy.EV_KEY, MouseEventPolicy.BTN_LEFT, 1, MouseEventPolicy.EV_SYN, 0, 0)
    private val dragMove = intArrayOf(
        MouseEventPolicy.EV_REL, MouseEventPolicy.REL_Y, -2,
        MouseEventPolicy.EV_KEY, MouseEventPolicy.BTN_LEFT, 0,
        MouseEventPolicy.EV_SYN, 0, 0,
    )

    @Test
    fun airMotionIsHeldBackWhileTouched() {
        assertTrue(MouseSource.heldBackWhileTouched(MouseSource.AIR, move))
    }

    @Test
    fun anAirBatchWithAButtonEdgeAlwaysGoesOut() {
        assertFalse(MouseSource.heldBackWhileTouched(MouseSource.AIR, press))
        assertFalse(MouseSource.heldBackWhileTouched(MouseSource.AIR, dragMove))
    }

    @Test
    fun padAndOtherBatchesAreNeverHeldBack() {
        for (source in listOf(MouseSource.PAD, MouseSource.OTHER, 7)) {
            assertFalse(MouseSource.heldBackWhileTouched(source, move))
        }
    }
}
