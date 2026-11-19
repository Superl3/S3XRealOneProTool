package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWindowTargetTest {
    private companion object {
        /** DeX display size used by the tests. */
        const val W = 1920
        const val H = 1080
    }

    private fun window(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        layer: Int,
        active: Boolean = false,
    ) = WindowCandidate(left, top, right, bottom, layer, active)

    @Test
    fun choose_pointInsideTwoOverlappingWindowsPicksTheHigherLayer() {
        val windows = listOf(
            window(0, 0, 1000, 800, layer = 3, active = true),
            window(400, 300, 1400, 1000, layer = 5),
            window(600, 500, 900, 700, layer = 9), // higher layer but does not contain the point
        )

        val choice = RemoteWindowTarget.choose(windows, 500f, 400f, W, H)

        assertEquals(RemoteWindowTarget.Choice(index = 1, underPoint = true), choice)
    }

    @Test
    fun choose_pointOutsideAllWindowsFallsBackToTheActiveOne() {
        val windows = listOf(
            window(0, 0, 500, 500, layer = 7),
            window(600, 0, 1100, 500, layer = 2, active = true),
        )

        val choice = RemoteWindowTarget.choose(windows, 1500f, 900f, W, H)

        assertEquals(RemoteWindowTarget.Choice(index = 1, underPoint = false), choice)
    }

    @Test
    fun choose_nullPointFallsBackToTheActiveOne() {
        val windows = listOf(
            window(0, 0, 1920, 1080, layer = 7),
            window(100, 100, 900, 700, layer = 2, active = true),
        )

        assertEquals(
            RemoteWindowTarget.Choice(index = 1, underPoint = false),
            RemoteWindowTarget.choose(windows, null, null, W, H),
        )
        // A half-known point is treated as unknown, not as a hit test on one axis.
        assertEquals(
            RemoteWindowTarget.Choice(index = 1, underPoint = false),
            RemoteWindowTarget.choose(windows, 500f, null, W, H),
        )
    }

    @Test
    fun choose_noActiveWindowPicksTheHighestLayer() {
        val windows = listOf(
            window(0, 0, 500, 500, layer = 4),
            window(600, 0, 1100, 500, layer = 8),
            window(0, 600, 500, 1000, layer = 1),
        )

        assertEquals(
            RemoteWindowTarget.Choice(index = 1, underPoint = false),
            RemoteWindowTarget.choose(windows, null, null, W, H),
        )
        assertEquals(
            RemoteWindowTarget.Choice(index = 1, underPoint = false),
            RemoteWindowTarget.choose(windows, 1800f, 1000f, W, H),
        )
    }

    @Test
    fun choose_emptyListIsNull() {
        assertNull(RemoteWindowTarget.choose(emptyList(), 10f, 10f, W, H))
        assertNull(RemoteWindowTarget.choose(emptyList(), null, null, W, H))
    }

    @Test
    fun choose_boundsAreHalfOpenLikeRect() {
        val windows = listOf(
            window(0, 0, 100, 100, layer = 1, active = true),
            window(100, 0, 200, 100, layer = 0),
        )

        // x = 100 is the right edge of the first window (exclusive) and the left edge of the second.
        assertEquals(
            RemoteWindowTarget.Choice(index = 1, underPoint = true),
            RemoteWindowTarget.choose(windows, 100f, 50f, W, H),
        )
    }

    @Test
    fun desktopIndex_isTheLowestWindowCoveringTheDisplay() {
        val windows = listOf(
            window(200, 100, 900, 700, layer = 6, active = true),
            window(0, 0, W, H, layer = 1),
            window(1000, 100, 1800, 700, layer = 4),
        )

        assertEquals(1, RemoteWindowTarget.desktopIndex(windows, W, H))
    }

    @Test
    fun desktopIndex_noneWhenTheLowestWindowDoesNotCoverTheDisplay() {
        // A maximized window stops above the taskbar, so it is not taken for the desktop.
        val windows = listOf(
            window(0, 0, W, H - 48, layer = 1),
            window(200, 100, 900, 700, layer = 6),
        )

        assertNull(RemoteWindowTarget.desktopIndex(windows, W, H))
    }

    @Test
    fun desktopIndex_noneForALoneFullScreenWindow() {
        // A full-screen app can hide the desktop; on its own it stays a target.
        val windows = listOf(window(0, 0, W, H, layer = 3, active = true))

        assertNull(RemoteWindowTarget.desktopIndex(windows, W, H))
        assertEquals(
            RemoteWindowTarget.Choice(index = 0, underPoint = true),
            RemoteWindowTarget.choose(windows, 500f, 500f, W, H),
        )
    }

    @Test
    fun choose_cursorOverEmptyDesktopFallsBackToTheActiveWindow() {
        val windows = listOf(
            window(0, 0, W, H, layer = 1),
            window(200, 100, 900, 700, layer = 4, active = true),
            window(1000, 100, 1800, 700, layer = 6),
        )

        // (950, 900) is only inside the desktop.
        assertEquals(
            RemoteWindowTarget.Choice(index = 1, underPoint = false),
            RemoteWindowTarget.choose(windows, 950f, 900f, W, H),
        )
        // A window over the desktop is still hit through it.
        assertEquals(
            RemoteWindowTarget.Choice(index = 2, underPoint = true),
            RemoteWindowTarget.choose(windows, 1200f, 300f, W, H),
        )
    }

    @Test
    fun choose_focusedDesktopIsNotTheFallback() {
        // The user clicked the empty desktop, so it is the focused window.
        val windows = listOf(
            window(0, 0, W, H, layer = 1, active = true),
            window(200, 100, 900, 700, layer = 4),
            window(1000, 100, 1800, 700, layer = 6),
        )

        assertEquals(
            RemoteWindowTarget.Choice(index = 2, underPoint = false),
            RemoteWindowTarget.choose(windows, 950f, 900f, W, H),
        )
        assertEquals(
            RemoteWindowTarget.Choice(index = 2, underPoint = false),
            RemoteWindowTarget.choose(windows, null, null, W, H),
        )
    }

    @Test
    fun uncoveredPoint_isTheCentreWhenNothingCoversIt() {
        val windows = listOf(
            window(0, 0, W, H, layer = 1),
            window(100, 100, 901, 701, layer = 4, active = true),
        )

        assertEquals(Pair(500f, 400f), RemoteWindowTarget.uncoveredPoint(windows, 1))
    }

    @Test
    fun uncoveredPoint_avoidsAHigherWindowOverTheCentre() {
        val windows = listOf(
            window(100, 100, 900, 700, layer = 4, active = true),
            // Covers the centre (500, 400) and everything right of x = 300 and below y = 200.
            window(300, 200, 1200, 900, layer = 7),
        )

        val point = RemoteWindowTarget.uncoveredPoint(windows, 0)!!
        assertTrue(windows[0].contains(point.first, point.second))
        assertFalse(windows[1].contains(point.first, point.second))
        // A lower window (the desktop) does not count as covering.
        val overDesktop = listOf(window(0, 0, W, H, layer = 0), windows[0])
        assertEquals(Pair(500f, 400f), RemoteWindowTarget.uncoveredPoint(overDesktop, 1))
    }

    @Test
    fun uncoveredPoint_nullWhenCoveredEverywhere() {
        val windows = listOf(
            window(100, 100, 900, 700, layer = 4, active = true),
            window(0, 0, 1000, 800, layer = 7),
        )

        assertNull(RemoteWindowTarget.uncoveredPoint(windows, 0))
    }

    private fun captionAllowed(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        decor: List<SystemCursorQuery.Frame>?,
        isChild: Boolean = false,
    ) = RemoteWindowTarget.captionTapAllowed(isChild, left, top, right, bottom, 1920, 1080, 60f, decor)

    @Test
    fun `a floating window gets the caption tap without decoration evidence`() {
        assertTrue(captionAllowed(200, 100, 1200, 800, null))
    }

    @Test
    fun `a display-sized window needs a decoration frame over its top strip`() {
        assertFalse(captionAllowed(0, 0, 1920, 1080, null))
        assertFalse(captionAllowed(0, 0, 1920, 1080, emptyList()))
        // A decoration elsewhere (another window's caption lower down) is no evidence.
        assertFalse(captionAllowed(0, 0, 1920, 1080, listOf(SystemCursorQuery.Frame(63, 200, 400, 900, 460))))
        assertTrue(captionAllowed(0, 0, 1920, 1080, listOf(SystemCursorQuery.Frame(63, 0, 0, 1920, 60))))
    }

    @Test
    fun `child windows and windows too small for a caption are never tapped`() {
        assertFalse(captionAllowed(200, 100, 1200, 800, null, isChild = true))
        assertFalse(captionAllowed(200, 100, 400, 800, null))
        assertFalse(captionAllowed(200, 100, 1200, 200, null))
    }
}
