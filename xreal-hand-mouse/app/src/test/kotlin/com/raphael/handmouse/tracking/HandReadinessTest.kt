package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandReadinessTest {

    private fun hand(x: Float = 0.5f, y: Float = 0.5f) = List(21) { HandPoint(x, y, 0f) }

    @Test
    fun `hand inside the image with a clear score is ready`() {
        assertNull(HandReadiness.notReadyReason(hand(), 0.95f))
    }

    @Test
    fun `missing handedness does not count against the hand`() {
        assertNull(HandReadiness.notReadyReason(hand(), null))
    }

    @Test
    fun `a landmark within the edge margin is not ready`() {
        val points = hand().toMutableList().apply { this[8] = HandPoint(0.99f, 0.5f, 0f) }
        assertEquals(HandReadiness.Reason.EDGE, HandReadiness.notReadyReason(points, 0.95f))
        val top = hand().toMutableList().apply { this[12] = HandPoint(0.5f, 0.01f, 0f) }
        assertEquals(HandReadiness.Reason.EDGE, HandReadiness.notReadyReason(top, 0.95f))
        val outside = hand().toMutableList().apply { this[20] = HandPoint(-0.1f, 0.5f, 0f) }
        assertEquals(HandReadiness.Reason.EDGE, HandReadiness.notReadyReason(outside, 0.95f))
    }

    @Test
    fun `a low handedness score is not ready`() {
        assertEquals(HandReadiness.Reason.LOW_SCORE, HandReadiness.notReadyReason(hand(), 0.55f))
        assertNull(HandReadiness.notReadyReason(hand(), 0.6f))
    }
}
