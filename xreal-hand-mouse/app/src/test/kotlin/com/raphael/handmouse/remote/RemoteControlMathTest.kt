package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlMathTest {
    @Test
    fun movePointer_clampsToDisplayEdges() {
        val p = RemoteControlMath.movePointer(
            x = 1910f,
            y = 1070f,
            dx = 100f,
            dy = 100f,
            width = 1920,
            height = 1080,
        )

        assertEquals(1919f, p.x, 0.001f)
        assertEquals(1079f, p.y, 0.001f)
    }

    @Test
    fun movePointer_clampsNegativeCoordinates() {
        val p = RemoteControlMath.movePointer(
            x = 4f,
            y = 6f,
            dx = -20f,
            dy = -30f,
            width = 1920,
            height = 1080,
        )

        assertEquals(0f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
    }

    @Test
    fun activeSwipe_usesWindowCenterAndCorrectDirection() {
        val up = RemoteControlMath.activeSwipe(
            left = 100,
            top = 200,
            right = 1100,
            bottom = 800,
            up = true,
        )
        val down = RemoteControlMath.activeSwipe(
            left = 100,
            top = 200,
            right = 1100,
            bottom = 800,
            up = false,
        )
        assertEquals(600f, up.x1, 0.001f)
        assertEquals(up.x1, up.x2, 0.001f)
        assertTrue(up.y1 > up.y2)

        assertEquals(600f, down.x1, 0.001f)
        assertEquals(down.x1, down.x2, 0.001f)
        assertTrue(down.y1 < down.y2)

        assertEquals(up.y1, down.y2, 0.001f)
        assertEquals(up.y2, down.y1, 0.001f)
    }

    @Test
    fun isTap_requiresALiftedShortStillSingleFingerTouch() {
        assertTrue(RemoteControlMath.isTap(lifted = true, moved = false, twoFinger = false, elapsedMs = 120L))
        assertFalse(RemoteControlMath.isTap(lifted = true, moved = true, twoFinger = false, elapsedMs = 120L))
        assertFalse(RemoteControlMath.isTap(lifted = true, moved = false, twoFinger = true, elapsedMs = 120L))
        assertFalse(RemoteControlMath.isTap(lifted = true, moved = false, twoFinger = false, elapsedMs = RemoteControlMath.TAP_MAX_MS))
    }

    @Test
    fun isTap_isFalseWhenTheSystemCancelledTheTouch() {
        // ACTION_CANCEL: the finger did not lift, so no click may reach the external display
        assertFalse(RemoteControlMath.isTap(lifted = false, moved = false, twoFinger = false, elapsedMs = 40L))
    }

    @Test
    fun activeSwipe_handlesDegenerateBounds() {
        val swipe = RemoteControlMath.activeSwipe(
            left = 10,
            top = 20,
            right = 10,
            bottom = 20,
            up = true,
        )

        assertTrue(swipe.x1.isFinite())
        assertTrue(swipe.y1.isFinite())
        assertTrue(swipe.y2.isFinite())
    }
}
