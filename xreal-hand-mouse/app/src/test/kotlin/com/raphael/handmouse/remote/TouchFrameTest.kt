package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchFrameTest {

    @Test
    fun theCodecKeepsEveryField() {
        val frame = TouchFrame(
            uptimeMs = 216_080_361_746L,
            contacts = listOf(TouchContact(7, 0.25f, 0.999f), TouchContact(0x10008, Float.NaN, 0f)),
        )
        val decoded = TouchFrameCodec.decode(TouchFrameCodec.encode(frame))!!
        assertEquals(frame.uptimeMs, decoded.uptimeMs)
        assertEquals(frame.contacts[0], decoded.contacts[0])
        assertEquals(0x10008, decoded.contacts[1].id)
        assertTrue(decoded.contacts[1].x.isNaN())
        assertFalse(decoded.contacts[1].hasPosition)
        assertFalse(decoded.cancelLifts)
    }

    @Test
    fun theCodecCarriesAnEmptyFrameAndLargeTimes() {
        val frame = TouchFrame(Long.MAX_VALUE - 3, emptyList())
        assertEquals(frame, TouchFrameCodec.decode(TouchFrameCodec.encode(frame)))
        assertEquals(3, TouchFrameCodec.encode(frame).size)
        assertFalse(frame.anyDown)
    }

    @Test
    fun theCodecCarriesTheCancelFlag() {
        val cancelled = TouchFrame(5L, emptyList(), cancelLifts = true)
        assertEquals(cancelled, TouchFrameCodec.decode(TouchFrameCodec.encode(cancelled)))
        val withContact = TouchFrame(6L, listOf(TouchContact(1, 0.5f, 0.5f)), cancelLifts = true)
        assertEquals(withContact, TouchFrameCodec.decode(TouchFrameCodec.encode(withContact)))
    }

    @Test
    fun theCodecRefusesWhatIsNotAFrame() {
        assertNull(TouchFrameCodec.decode(null))
        assertNull(TouchFrameCodec.decode(intArrayOf(0, 1)))
        assertNull(TouchFrameCodec.decode(intArrayOf(0, 1, 0, 3)))
        assertNull(TouchFrameCodec.decode(IntArray(3 + 3 * (TouchFrame.MAX_CONTACTS + 1))))
    }

    @Test
    fun airHold_startsAtEachLanding_keepsThroughMoves_andEndsAtTheLastLift() {
        val hold = MouseSource.AIR_HOLD_AFTER_LANDING_MS
        val a = TouchContact(1, 0.1f, 0.1f)
        val b = TouchContact(2, 0.2f, 0.2f)
        assertEquals(100 + hold, MouseSource.airHoldUntil(emptyList(), TouchFrame(100, listOf(a)), 0L))
        // A move keeps the hold as it was, also once it ran out: a resting thumb does not renew it.
        assertEquals(100 + hold, MouseSource.airHoldUntil(listOf(a), TouchFrame(400, listOf(a.copy(x = 0.3f))), 100 + hold))
        // A second finger renews it from its own landing.
        assertEquals(900 + hold, MouseSource.airHoldUntil(listOf(a), TouchFrame(900, listOf(a, b)), 100 + hold))
        // One finger lifts: the other keeps the hold; the last lift ends it.
        assertEquals(900 + hold, MouseSource.airHoldUntil(listOf(a, b), TouchFrame(950, listOf(b)), 900 + hold))
        assertEquals(0L, MouseSource.airHoldUntil(listOf(b), TouchFrame(960, emptyList()), 900 + hold))
    }

    @Test
    fun theCodecSendsAtMostMaxContacts() {
        val many = TouchFrame(1L, List(TouchFrame.MAX_CONTACTS + 4) { TouchContact(it, 0f, 0f) })
        assertEquals(TouchFrame.MAX_CONTACTS, TouchFrameCodec.decode(TouchFrameCodec.encode(many))!!.contacts.size)
    }

    /** A 1080x2340 phone, a point a quarter across and a tenth down in portrait. */
    private fun rotated(rotation: Int) = naturalToDisplay(0.25f, 0.1f, rotation, 1080, 2340)

    @Test
    fun naturalPointsFollowTheDisplayRotation() {
        assertEquals(270f to 234f, rotated(0))
        // ROTATION_90: turned counter-clockwise, the natural top-left is now bottom-left.
        assertEquals(234f to 810f, rotated(1))
        assertEquals(810f to 2106f, rotated(2))
        assertEquals(2106f to 270f, rotated(3))
    }
}
