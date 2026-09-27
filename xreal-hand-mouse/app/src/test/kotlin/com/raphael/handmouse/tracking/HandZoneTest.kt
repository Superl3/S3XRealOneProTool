package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandZoneTest {
    private fun hand(y: Float) = List(21) { HandPoint(0.5f, y, 0f) }

    @Test
    fun bottomZone() {
        assertFalse(HandZone.isInBottomZone(hand(0.9f), 0f))
        assertTrue(HandZone.isInBottomZone(hand(0.8f), 0.3f))
        assertFalse(HandZone.isInBottomZone(hand(0.5f), 0.3f))
    }
}
