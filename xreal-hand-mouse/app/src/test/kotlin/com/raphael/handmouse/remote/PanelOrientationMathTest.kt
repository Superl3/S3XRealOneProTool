package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.RemoteDockPrefs.Corner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelOrientationMathTest {
    private val unknown = -1 // OrientationEventListener.ORIENTATION_UNKNOWN

    @Test
    fun physicalQuarterTurns_mapsAnglesToSurfaceRotation() {
        // Start from the opposite quarter so hysteresis never holds the previous value.
        val cases = listOf(0 to 0, 90 to 3, 180 to 2, 270 to 1, 359 to 0, 44 to 0)
        for ((angle, expected) in cases) {
            val previous = (expected + 2) % 4
            assertEquals("angle $angle", expected, PanelOrientationMath.physicalQuarterTurns(angle, previous))
        }
    }

    @Test
    fun physicalQuarterTurns_rightSideUpIsRotation90() {
        // Angle 270 (right side up, device turned counter-clockwise) is Surface.ROTATION_90.
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(270, 0))
        // Angle 90 (left side up) is Surface.ROTATION_270.
        assertEquals(3, PanelOrientationMath.physicalQuarterTurns(90, 0))
    }

    @Test
    fun physicalQuarterTurns_hysteresisHoldsNearTheSectorEdge() {
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(44, 0))
        // 46 is nearer to 90 (quarter 3) but only 46 degrees from the center of 0.
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(46, 0))
        assertEquals(3, PanelOrientationMath.physicalQuarterTurns(120, 0))
        // 300 is 60 degrees from 0: held. 300 from another quarter: 1.
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(300, 0))
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(300, 3))
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(300, 2))
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(250, 1))
        // 200 is 70 degrees from 270 (center of 1): more than 45 + 20.
        assertEquals(2, PanelOrientationMath.physicalQuarterTurns(200, 1))
    }

    @Test
    fun physicalQuarterTurns_hysteresisBoundaryIsExclusive() {
        val limit = 45 + PanelOrientationMath.HYSTERESIS_DEG
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(limit, 0))
        assertEquals(3, PanelOrientationMath.physicalQuarterTurns(limit + 1, 0))
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(360 - limit, 0))
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(360 - limit - 1, 0))
    }

    @Test
    fun physicalQuarterTurns_unknownKeepsPrevious() {
        for (previous in 0..3) {
            assertEquals(previous, PanelOrientationMath.physicalQuarterTurns(unknown, previous))
        }
    }

    @Test
    fun physicalQuarterTurns_invalidPreviousMeansNoPrevious() {
        assertEquals(3, PanelOrientationMath.physicalQuarterTurns(46, -5))
        assertEquals(3, PanelOrientationMath.physicalQuarterTurns(46, 7))
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(300, -5))
        assertEquals(1, PanelOrientationMath.physicalQuarterTurns(300, 7))
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(10, 7))
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(unknown, -5))
        assertEquals(0, PanelOrientationMath.physicalQuarterTurns(unknown, 7))
    }

    @Test
    fun contentQuarterTurns_subtractsDisplayRotation() {
        assertEquals(0, PanelOrientationMath.contentQuarterTurns(0, 0))
        assertEquals(1, PanelOrientationMath.contentQuarterTurns(1, 0))
        assertEquals(3, PanelOrientationMath.contentQuarterTurns(3, 0))
        assertEquals(0, PanelOrientationMath.contentQuarterTurns(1, 1))
        assertEquals(0, PanelOrientationMath.contentQuarterTurns(3, 3))
        assertEquals(2, PanelOrientationMath.contentQuarterTurns(3, 1))
    }

    @Test
    fun contentQuarterTurns_wrapsNegativeDifference() {
        assertEquals(3, PanelOrientationMath.contentQuarterTurns(0, 1))
        assertEquals(1, PanelOrientationMath.contentQuarterTurns(0, 3))
        assertEquals(2, PanelOrientationMath.contentQuarterTurns(1, 3))
        assertEquals(2, PanelOrientationMath.contentQuarterTurns(0, 2))
    }

    @Test
    fun screenCorner_movesClockwiseByQuarterTurns() {
        assertEquals(Corner.TOP_LEFT, PanelOrientationMath.screenCorner(Corner.TOP_LEFT, 0))
        assertEquals(Corner.TOP_RIGHT, PanelOrientationMath.screenCorner(Corner.TOP_LEFT, 1))
        assertEquals(Corner.BOTTOM_RIGHT, PanelOrientationMath.screenCorner(Corner.TOP_LEFT, 2))
        assertEquals(Corner.BOTTOM_LEFT, PanelOrientationMath.screenCorner(Corner.TOP_LEFT, 3))

        assertEquals(Corner.BOTTOM_RIGHT, PanelOrientationMath.screenCorner(Corner.BOTTOM_RIGHT, 0))
        assertEquals(Corner.BOTTOM_LEFT, PanelOrientationMath.screenCorner(Corner.BOTTOM_RIGHT, 1))
        assertEquals(Corner.TOP_LEFT, PanelOrientationMath.screenCorner(Corner.BOTTOM_RIGHT, 2))
        assertEquals(Corner.TOP_RIGHT, PanelOrientationMath.screenCorner(Corner.BOTTOM_RIGHT, 3))
    }

    @Test
    fun isLandscapeHold_followsTheHoldAlone() {
        // A phone (natural portrait): odd holds are landscape.
        assertFalse(PanelOrientationMath.isLandscapeHold(0, naturalLandscape = false))
        assertTrue(PanelOrientationMath.isLandscapeHold(1, naturalLandscape = false))
        assertFalse(PanelOrientationMath.isLandscapeHold(2, naturalLandscape = false))
        assertTrue(PanelOrientationMath.isLandscapeHold(3, naturalLandscape = false))
        // A tablet (natural landscape): the reverse.
        assertTrue(PanelOrientationMath.isLandscapeHold(0, naturalLandscape = true))
        assertFalse(PanelOrientationMath.isLandscapeHold(1, naturalLandscape = true))
    }

    @Test
    fun aDisplayRotationLeavesThePanelAtTheSamePhysicalCorner() {
        // The display frame is turned `rotation` clockwise from the natural panel, the content
        // `contentQuarterTurns` from the display: the natural-panel corner depends on the hold alone.
        for (hold in 0..3) {
            for (corner in Corner.values()) {
                val physical = PanelOrientationMath.screenCorner(corner, hold)
                for (rotation in 0..3) {
                    val onScreen = PanelOrientationMath.screenCorner(
                        corner,
                        PanelOrientationMath.contentQuarterTurns(hold, rotation),
                    )
                    assertEquals(
                        "hold $hold corner $corner rotation $rotation",
                        physical,
                        PanelOrientationMath.screenCorner(onScreen, rotation),
                    )
                }
            }
        }
    }

    @Test
    fun nextHold_cyclesClockwise() {
        assertEquals(1, PanelOrientationMath.nextHold(0))
        assertEquals(2, PanelOrientationMath.nextHold(1))
        assertEquals(3, PanelOrientationMath.nextHold(2))
        assertEquals(0, PanelOrientationMath.nextHold(3))
        assertEquals(0, PanelOrientationMath.nextHold(-1))
    }

    @Test
    fun suggestionTurn_isTheClockwiseStepToTheTarget() {
        assertEquals(1, PanelOrientationMath.suggestionTurn(0, 1))
        assertEquals(3, PanelOrientationMath.suggestionTurn(0, 3))
        assertEquals(2, PanelOrientationMath.suggestionTurn(1, 3))
        assertEquals(1, PanelOrientationMath.suggestionTurn(3, 0))
        assertEquals(3, PanelOrientationMath.suggestionTurn(1, 0))
    }

    @Test
    fun manualHold_adoptsTheSensedHoldElseTurnsClockwise() {
        assertEquals(3, PanelOrientationMath.manualHold(0, 3))
        // Reverse portrait too: the tap is explicit.
        assertEquals(2, PanelOrientationMath.manualHold(0, 2))
        assertEquals(1, PanelOrientationMath.manualHold(0, null))
        assertEquals(1, PanelOrientationMath.manualHold(0, 0))
        assertEquals(0, PanelOrientationMath.manualHold(3, null))
        assertEquals(2, PanelOrientationMath.manualHold(1, 5))
    }
}
