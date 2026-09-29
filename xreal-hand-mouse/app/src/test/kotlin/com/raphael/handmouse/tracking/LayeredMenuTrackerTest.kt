package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LayeredMenuTrackerTest {

    /** Frame timestamps 60fps apart — the frame counts in these tests are 60fps frames (the
     * detectors are time-based since 2026-09-28, see [FrameTiming]). */
    private var frame = 0
    private fun ts(): Long = frame++ * 1000L / 60
    private fun menu() = LayeredMenuTracker(step = 0.05f)

    @Test
    fun leftUpAndRightNeedOneShortMove() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false, ts())
        assertEquals(MenuAction.BACK, m.onPalmOpen(0.44f, 0.50f, false, ts()).selected)
        assertEquals(MenuAction.HOME, m.onPalmOpen(0.50f, 0.41f, false, ts()).selected)
        assertEquals(MenuAction.CLOSE_APP, m.onPalmOpen(0.56f, 0.50f, false, ts()).selected)
    }

    @Test
    fun pinchConfirmsSelectedDirectionOnlyOnce() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false, ts())
        m.onPalmOpen(0.56f, 0.50f, false, ts())
        assertEquals(MenuAction.CLOSE_APP, m.onPalmOpen(0.56f, 0.50f, true, ts()).fired)
        assertFalse(m.isActive)
        assertNull(m.onPalmOpen(0.60f, 0.50f, true, ts()).fired)
    }

    @Test
    fun centerAndDiagonalAreNeutral() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false, ts())
        assertNull(m.onPalmOpen(0.50f, 0.50f, true, ts()).fired)
        // Isotropic input: equal travel right and up is a true diagonal.
        assertNull(m.onPalmOpen(0.57f, 0.43f, false, ts()).selected)
    }

    @Test
    fun returningToCenterCancelsSelection() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false, ts())
        m.onPalmOpen(0.44f, 0.50f, false, ts())
        assertNull(m.onPalmOpen(0.50f, 0.50f, false, ts()).selected)
        assertNull(m.onPalmOpen(0.50f, 0.50f, true, ts()).fired)
    }

    @Test
    fun pinchJustAfterPalmClosesStillConfirms() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false, ts())
        m.onPalmOpen(0.44f, 0.50f, false, ts())
        assertEquals(MenuAction.BACK, m.onPalmClosed(true, 120).fired)
    }

    @Test
    fun confirmWindowExpires() {
        val m = menu()
        m.onPalmOpen(0.50f, 0.50f, false, ts())
        m.onPalmOpen(0.44f, 0.50f, false, ts())
        m.onPalmClosed(false, 0)
        assertNull(m.onPalmClosed(true, 1000).fired)
    }
}
