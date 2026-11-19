package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.RemoteDockPrefs.Corner
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniButtonMathTest {
    private val w = 1080
    private val h = 2340

    @Test
    fun `display to natural undoes natural to display at every rotation`() {
        for (rotation in 0..3) {
            val (dx, dy) = naturalToDisplay(0.2f, 0.7f, rotation, w, h)
            val (fx, fy) = MiniButtonMath.displayToNatural(dx, dy, rotation, w, h)
            assertEquals("rotation $rotation x", 0.2f, fx, 1e-4f)
            assertEquals("rotation $rotation y", 0.7f, fy, 1e-4f)
        }
    }

    @Test
    fun `the corner follows the half the button is in, upright for the hold`() {
        // Held upright (hold 0): natural top-left is upright top-left.
        assertEquals(Corner.TOP_LEFT, MiniButtonMath.uprightCorner(0.1f, 0.1f, 0))
        assertEquals(Corner.BOTTOM_RIGHT, MiniButtonMath.uprightCorner(0.9f, 0.8f, 0))
        // Held a quarter clockwise (hold 1): natural top-left lies at the user's bottom-left.
        assertEquals(Corner.BOTTOM_LEFT, MiniButtonMath.uprightCorner(0.1f, 0.1f, 1))
        // Held upside down: natural top-left is the user's bottom-right.
        assertEquals(Corner.BOTTOM_RIGHT, MiniButtonMath.uprightCorner(0.1f, 0.1f, 2))
    }

    @Test
    fun `the upright corner lands on the screen where the button is`() {
        // Hold 1 with the display at rotation 0 turns the content once; the button at the screen's
        // top-left must open the panel at the screen's top-left.
        val corner = MiniButtonMath.uprightCorner(0.1f, 0.1f, 1)
        assertEquals(Corner.TOP_LEFT, PanelOrientationMath.screenCorner(corner, PanelOrientationMath.contentQuarterTurns(1, 0)))
        // Hold and display both at 1 (auto-rotate, no turn): the screen's top-right is the upright top-right.
        val (fx, fy) = MiniButtonMath.displayToNatural(2200f, 100f, 1, w, h)
        assertEquals(Corner.TOP_RIGHT, MiniButtonMath.uprightCorner(fx, fy, 1))
    }

    @Test
    fun `the button stays on the screen and out of the side gaps`() {
        assertEquals(40 to 0, MiniButtonMath.clampedTopLeft(10f, -50f, 100, w, h, 40, 30))
        assertEquals(950 to 2240, MiniButtonMath.clampedTopLeft(5000f, 5000f, 100, w, h, 40, 30))
        assertEquals(450 to 950, MiniButtonMath.clampedTopLeft(500f, 1000f, 100, w, h, 40, 30))
    }
}
