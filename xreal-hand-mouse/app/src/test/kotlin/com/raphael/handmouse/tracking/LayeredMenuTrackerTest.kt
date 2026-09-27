package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LayeredMenuTrackerTest {
    private fun menu() = LayeredMenuTracker(step = 0.05f)

    @Test
    fun leftUpAndRightNeedOneShortMove() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false)
        assertEquals(MenuAction.BACK, m.onPalmOpen(0.44f, 0.50f, false).selected)
        assertEquals(MenuAction.HOME, m.onPalmOpen(0.50f, 0.41f, false).selected)
        assertEquals(MenuAction.CLOSE_APP, m.onPalmOpen(0.56f, 0.50f, false).selected)
    }

    @Test
    fun pinchConfirmsSelectedDirectionOnlyOnce() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false)
        m.onPalmOpen(0.56f, 0.50f, false)
        assertEquals(MenuAction.CLOSE_APP, m.onPalmOpen(0.56f, 0.50f, true).fired)
        assertFalse(m.isActive)
        assertNull(m.onPalmOpen(0.60f, 0.50f, true).fired)
    }

    @Test
    fun centerAndDiagonalAreNeutral() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false)
        assertNull(m.onPalmOpen(0.50f, 0.50f, true).fired)
        assertNull(m.onPalmOpen(0.57f, 0.40f, false).selected)
    }

    @Test
    fun returningToCenterCancelsSelection() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false)
        m.onPalmOpen(0.44f, 0.50f, false)
        assertNull(m.onPalmOpen(0.50f, 0.50f, false).selected)
        assertNull(m.onPalmOpen(0.50f, 0.50f, true).fired)
    }

    @Test
    fun pinchJustAfterPalmClosesStillConfirms() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false)
        m.onPalmOpen(0.44f, 0.50f, false)
        assertEquals(MenuAction.BACK, m.onPalmClosed(true, 120).fired)
    }

    @Test
    fun confirmWindowExpires() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false)
        m.onPalmOpen(0.44f, 0.50f, false)
        m.onPalmClosed(false, 0)
        assertNull(m.onPalmClosed(true, 1000).fired)
    }
}
