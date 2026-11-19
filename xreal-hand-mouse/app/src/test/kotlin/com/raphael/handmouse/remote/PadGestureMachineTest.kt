package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadGestureMachineTest {
    /** Records every output call as a short string; moves are summed per run of moves. */
    private class Recorder : PadGestureOutput {
        val calls = ArrayList<String>()
        override var systemCursorActive = true
        var dragWorks = true

        override fun pressStarted(atMs: Long) {
            calls += "press@$atMs"
        }

        override fun pressAbandoned(atMs: Long) {
            calls += "abandon@$atMs"
        }

        override fun move(dx: Float, dy: Float) {
            calls += "move(${dx.toInt()},${dy.toInt()})"
        }

        override fun tap() {
            calls += "tap"
        }

        override fun beginDrag(): Boolean {
            calls += "beginDrag"
            return dragWorks
        }

        override fun endDrag() {
            calls += "endDrag"
        }

        override fun scroll(notches: Int) {
            calls += "scroll($notches)"
        }

        override fun swipe(up: Boolean) {
            calls += "swipe(${if (up) "up" else "down"})"
        }

        override fun secondaryClick() {
            calls += "rightClick"
        }

        override fun back() {
            calls += "back"
        }

        override fun haptic(kind: PadHaptic) {
            calls += "haptic($kind)"
        }

        /** The calls since the last take. */
        fun take(): List<String> = calls.toList().also { calls.clear() }
    }

    private val out = Recorder()
    private var tapDrag = true
    private val config = PadGestureConfig(slopPx = 20f, scrollStepPx = 50f, swipePx = 100f)
    private val pad = PadGestureMachine(out, config) { tapDrag }

    private fun tapAt(downMs: Long, upMs: Long, x: Float = 100f, y: Float = 100f) {
        pad.down(downMs, 0, x, y)
        pad.up(upMs, 0)
    }

    @Test
    fun quickLift_clicks() {
        pad.down(0, 0, 100f, 100f)
        assertEquals(listOf("press@0"), out.take())
        assertEquals(340L, pad.nextDeadlineMs)
        pad.up(120, 0)
        assertEquals(listOf("tap"), out.take())
        assertNull(pad.nextDeadlineMs)
    }

    @Test
    fun motionInsideTheSlop_movesThePointerLive_andStillClicks() {
        pad.down(0, 0, 100f, 100f)
        pad.move(16, 0, 105f, 100f)
        pad.move(32, 0, 109f, 101f)
        assertEquals(listOf("press@0", "move(5,0)", "move(4,1)"), out.take())
        pad.up(110, 0)
        assertEquals(listOf("tap"), out.take())
    }

    @Test
    fun crossingTheSlop_isAStroke_neverAClick() {
        pad.down(0, 0, 100f, 100f)
        pad.move(16, 0, 110f, 100f)
        pad.move(32, 0, 125f, 100f)
        pad.move(48, 0, 130f, 100f)
        assertEquals(listOf("press@0", "move(10,0)", "abandon@32", "move(15,0)", "move(5,0)"), out.take())
        assertNull(pad.nextDeadlineMs)
        pad.up(120, 0)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun stillForTapMax_dragsUntilTheLift_withHaptics() {
        pad.down(0, 0, 100f, 100f)
        out.take()
        pad.tick(339)
        assertEquals(emptyList<String>(), out.take())
        pad.tick(340)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)"), out.take())
        assertTrue(pad.dragging)
        assertNull(pad.nextDeadlineMs)
        pad.move(400, 0, 160f, 100f)
        assertEquals(listOf("move(60,0)"), out.take())
        pad.up(500, 0)
        assertEquals(listOf("endDrag", "haptic(DRAG_END)"), out.take())
        assertFalse(pad.dragging)
    }

    @Test
    fun overdueDeadline_isRunByTheNextInput() {
        // No tick ran (a busy main thread): the lift at 400 first starts the drag it passed.
        pad.down(0, 0, 100f, 100f)
        out.take()
        pad.up(400, 0)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)", "endDrag", "haptic(DRAG_END)"), out.take())

        // A move past the deadline inside the slop: the drag starts, then the move drags.
        pad.down(1000, 0, 100f, 100f)
        out.take()
        pad.move(1350, 0, 105f, 100f)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)", "move(5,0)"), out.take())
    }

    @Test
    fun liftQueuedBehindAnEarlierTick_endsTheDragThatTickStarted() {
        // The window race: the tick ran at 360, the lift's event time is 338. The press already
        // became a drag, so the lift releases it (a press and release at one point).
        pad.down(0, 0, 100f, 100f)
        out.take()
        pad.tick(360)
        pad.up(338, 0)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)", "endDrag", "haptic(DRAG_END)"), out.take())
    }

    @Test
    fun cancel_neverClicks_andReleasesAHeldDrag() {
        pad.down(0, 0, 100f, 100f)
        pad.cancelAll(100)
        assertEquals(listOf("press@0", "abandon@100"), out.take())
        assertNull(pad.nextDeadlineMs)

        pad.down(1000, 0, 100f, 100f)
        pad.tick(1340)
        out.take()
        pad.cancel(1400, 0)
        assertEquals(listOf("endDrag", "haptic(DRAG_END)"), out.take())
        // A later lift of the cancelled contact does nothing.
        pad.up(1500, 0)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun cancelOfOneContact_makesTheOthersInert() {
        out.systemCursorActive = true
        pad.down(0, 0, 100f, 100f)
        pad.down(20, 1, 200f, 100f)
        out.take()
        pad.cancel(60, 0)
        pad.move(80, 1, 200f, 400f)
        pad.up(100, 1)
        assertEquals(emptyList<String>(), out.take())
        // The next touch is a fresh press.
        pad.down(500, 0, 100f, 100f)
        pad.up(560, 0)
        assertEquals(listOf("press@500", "tap"), out.take())
    }

    @Test
    fun twoFingerTap_rightClicksWithTheSystemCursorOnly() {
        out.systemCursorActive = true
        pad.down(0, 0, 100f, 100f)
        pad.down(40, 1, 200f, 100f)
        assertEquals(listOf("press@0", "abandon@40"), out.take())
        assertNull(pad.nextDeadlineMs)
        pad.up(150, 0)
        pad.up(200, 1)
        assertEquals(listOf("rightClick"), out.take())

        out.systemCursorActive = false
        pad.down(1000, 0, 100f, 100f)
        pad.down(1040, 1, 200f, 100f)
        pad.up(1150, 0)
        pad.up(1200, 1)
        assertEquals(listOf("press@1000", "abandon@1040"), out.take())
    }

    @Test
    fun twoFingerTap_tooSlowOrAfterAMove_doesNothing() {
        pad.down(0, 0, 100f, 100f)
        pad.down(40, 1, 200f, 100f)
        pad.up(300, 0)
        pad.up(380, 1)
        assertEquals(listOf("press@0", "abandon@40"), out.take())

        // The first finger had moved before the second landed.
        pad.down(1000, 0, 100f, 100f)
        pad.move(1016, 0, 140f, 100f)
        pad.down(1040, 1, 200f, 100f)
        pad.up(1100, 0)
        pad.up(1120, 1)
        assertFalse(out.take().contains("rightClick"))
    }

    @Test
    fun twoFingerTap_judgesTheMidpointOncePerEvent_soSpreadingFingersStillRightClick() {
        out.systemCursorActive = true
        pad.down(0, 0, 100f, 100f)
        pad.down(40, 1, 200f, 100f)
        out.take()
        // Each event spreads the fingers 12 px apart in x (and in y): the midpoint stays still.
        for ((i, t) in listOf(60L, 76L, 92L).withIndex()) {
            val spread = 12f * (i + 1)
            pad.place(0, 100f - spread, 100f - spread)
            pad.place(1, 200f + spread, 100f + spread)
            pad.moved(t)
        }
        pad.up(150, 0)
        pad.up(160, 1)
        assertEquals(listOf("rightClick"), out.take())

        // Fed one contact at a time, the same spread counts as travel through half-applied midpoints.
        pad.down(1000, 0, 100f, 100f)
        pad.down(1040, 1, 200f, 100f)
        for ((i, t) in listOf(1060L, 1076L, 1092L).withIndex()) {
            val spread = 12f * (i + 1)
            pad.move(t, 0, 100f - spread, 100f - spread)
            pad.move(t, 1, 200f + spread, 100f + spread)
        }
        pad.up(1150, 0)
        pad.up(1160, 1)
        assertFalse(out.take().contains("rightClick"))
    }

    @Test
    fun moved_withNothingPlaced_orAnUnknownContact_doesNothing() {
        pad.down(0, 0, 100f, 100f)
        out.take()
        pad.moved(10)
        pad.place(7, 300f, 300f)
        pad.moved(20)
        assertEquals(emptyList<String>(), out.take())
        pad.place(0, 105f, 100f)
        pad.moved(30)
        pad.moved(40)
        assertEquals(listOf("move(5,0)"), out.take())
    }

    @Test
    fun twoFingers_turnTheWheelPerStep_andThenDoNotRightClick() {
        out.systemCursorActive = true
        pad.down(0, 0, 100f, 100f)
        pad.down(10, 1, 200f, 100f)
        out.take()
        pad.move(30, 0, 100f, 160f)
        pad.move(30, 1, 200f, 160f)
        assertEquals(listOf("scroll(1)"), out.take())
        pad.move(46, 0, 100f, 90f)
        pad.move(46, 1, 200f, 90f)
        assertEquals(listOf("scroll(-1)"), out.take())
        pad.up(100, 0)
        pad.up(110, 1)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun wheel_stopsWhenTheSystemCursorIsLostMidGesture() {
        out.systemCursorActive = true
        pad.down(0, 0, 100f, 100f)
        pad.down(10, 1, 200f, 100f)
        out.take()
        out.systemCursorActive = false
        pad.move(30, 0, 100f, 260f)
        pad.move(30, 1, 200f, 260f)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun overlayCursor_swipesOncePerTwoFingerGesture() {
        out.systemCursorActive = false
        pad.down(0, 0, 100f, 300f)
        pad.down(10, 1, 200f, 300f)
        out.take()
        pad.move(30, 0, 100f, 150f)
        pad.move(30, 1, 200f, 150f)
        pad.move(46, 0, 100f, 50f)
        pad.move(46, 1, 200f, 50f)
        assertEquals(listOf("swipe(up)"), out.take())
    }

    @Test
    fun threeFingerTap_isBack_unlessTheFingersTravel() {
        pad.down(0, 0, 100f, 100f)
        pad.down(20, 1, 200f, 100f)
        pad.down(40, 2, 300f, 100f)
        pad.up(150, 0)
        pad.up(160, 1)
        pad.up(170, 2)
        assertEquals(listOf("press@0", "abandon@20", "back"), out.take())

        pad.down(1000, 0, 100f, 100f)
        pad.down(1020, 1, 200f, 100f)
        pad.down(1040, 2, 300f, 100f)
        pad.move(1060, 0, 160f, 100f)
        pad.move(1060, 1, 260f, 100f)
        pad.move(1060, 2, 360f, 100f)
        pad.up(1150, 0)
        pad.up(1160, 1)
        pad.up(1170, 2)
        assertFalse(out.take().contains("back"))
    }

    @Test
    fun secondFinger_releasesAHeldDrag_andIsNoRightClick() {
        pad.down(0, 0, 100f, 100f)
        pad.tick(340)
        out.take()
        pad.down(500, 1, 200f, 100f)
        assertEquals(listOf("endDrag", "haptic(DRAG_END)"), out.take())
        pad.up(550, 0)
        pad.up(560, 1)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun oneFingerLeftAfterTwo_movesThePointer_andItsLiftIsNoTap() {
        pad.down(0, 0, 100f, 100f)
        pad.down(20, 1, 200f, 100f)
        pad.up(60, 1)
        out.take()
        // Its travel also ends the two-finger tap it was part of.
        pad.move(80, 0, 130f, 100f)
        assertEquals(listOf("move(30,0)"), out.take())
        pad.up(100, 0)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun tapDrag_startsAtTheTouchDownPoint_withTheHeldMotionAfterTheButton() {
        tapAt(0, 100)
        out.take()
        // Lands 200 ms after the tap's lift: armed. Motion inside the slop is held back.
        pad.down(300, 0, 100f, 100f)
        pad.move(316, 0, 108f, 100f)
        assertEquals(listOf("press@300"), out.take())
        // Crosses the slop after the double-click guard (100 + 350): the drag starts now.
        pad.move(460, 0, 125f, 100f)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)", "move(25,0)"), out.take())
        pad.move(476, 0, 135f, 100f)
        assertEquals(listOf("move(10,0)"), out.take())
        pad.up(600, 0)
        assertEquals(listOf("endDrag", "haptic(DRAG_END)"), out.take())
    }

    @Test
    fun tapDrag_waitsForTheDoubleClickGuard_holdingTheMotion() {
        tapAt(0, 100)
        out.take()
        pad.down(150, 0, 100f, 100f)
        pad.move(200, 0, 130f, 100f)
        pad.move(300, 0, 150f, 100f)
        assertEquals(listOf("press@150"), out.take())
        assertEquals(450L, pad.nextDeadlineMs)
        pad.tick(449)
        assertEquals(emptyList<String>(), out.take())
        pad.tick(450)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)", "move(50,0)"), out.take())
        pad.up(500, 0)
        assertEquals(listOf("endDrag", "haptic(DRAG_END)"), out.take())
    }

    @Test
    fun tapDrag_liftedBeforeTheGuard_isAPointerMove_withoutAPress() {
        tapAt(0, 100)
        out.take()
        pad.down(150, 0, 100f, 100f)
        pad.move(200, 0, 140f, 100f)
        pad.up(260, 0)
        assertEquals(listOf("press@150", "move(40,0)", "abandon@260"), out.take())
    }

    @Test
    fun armedTouch_liftedInsideTheSlop_isADoubleClickAtTheTouchDownPoint() {
        tapAt(0, 100)
        pad.down(250, 0, 100f, 100f)
        pad.move(266, 0, 106f, 103f)
        pad.up(330, 0)
        assertEquals(listOf("press@0", "tap", "press@250", "tap"), out.take())
        // The second click of a double click does not arm: a slide now is a plain stroke.
        pad.down(450, 0, 100f, 100f)
        pad.move(466, 0, 130f, 100f)
        assertEquals(listOf("press@450", "abandon@466", "move(30,0)"), out.take())
    }

    @Test
    fun armedTouch_heldStill_isTheLongPressDrag_withItsHeldMotionAfterTheButton() {
        tapAt(0, 100)
        pad.down(200, 0, 100f, 100f)
        pad.move(216, 0, 106f, 100f)
        out.take()
        pad.tick(540)
        assertEquals(listOf("beginDrag", "haptic(DRAG_START)", "move(6,0)"), out.take())
    }

    @Test
    fun tapDrag_onlyWithinTheWindow_andWhileEnabled() {
        tapAt(0, 100)
        out.take()
        // 251 ms after the lift: too late, an ordinary stroke.
        pad.down(351, 0, 100f, 100f)
        pad.move(367, 0, 105f, 100f)
        assertEquals(listOf("press@351", "move(5,0)"), out.take())
        pad.up(380, 0)
        out.take()

        tapDrag = false
        tapAt(1000, 1100)
        out.take()
        pad.down(1200, 0, 100f, 100f)
        pad.move(1216, 0, 140f, 100f)
        assertEquals(listOf("press@1200", "abandon@1216", "move(40,0)"), out.take())
    }

    @Test
    fun aTouchElsewhereOnThePanel_disarmsTapDrag() {
        tapAt(0, 100)
        out.take()
        pad.otherTouch()
        pad.down(200, 0, 100f, 100f)
        pad.move(216, 0, 140f, 100f)
        assertEquals(listOf("press@200", "abandon@216", "move(40,0)"), out.take())
    }

    @Test
    fun armedTouch_joinedByASecondFinger_dropsItsHeldMotion_forTheRightClick() {
        out.systemCursorActive = true
        tapAt(0, 100)
        pad.down(200, 0, 100f, 100f)
        pad.move(216, 0, 108f, 100f)
        pad.down(230, 1, 200f, 100f)
        pad.up(300, 0)
        pad.up(310, 1)
        assertEquals(listOf("press@0", "tap", "press@200", "abandon@230", "rightClick"), out.take())
    }

    @Test
    fun aDragThatCannotStart_abandonsThePress_andTheMotionStaysAMove() {
        out.dragWorks = false
        tapAt(0, 100)
        pad.down(200, 0, 100f, 100f)
        pad.move(500, 0, 130f, 100f)
        assertEquals(listOf("press@0", "tap", "press@200", "beginDrag", "abandon@500", "move(30,0)"), out.take())
        assertFalse(pad.dragging)
        pad.up(600, 0)
        assertEquals(emptyList<String>(), out.take())
    }

    @Test
    fun aFirstDownAfterCancelAll_startsAFreshPress() {
        // The window adapter's answer to a lost lift: cancel what remains, then the new touch.
        pad.down(0, 0, 100f, 100f)
        pad.tick(340)
        out.take()
        pad.cancelAll(900)
        pad.down(900, 0, 50f, 50f)
        assertEquals(listOf("endDrag", "haptic(DRAG_END)", "press@900"), out.take())
        pad.up(950, 0)
        assertEquals(listOf("tap"), out.take())
    }

    @Test
    fun reset_releasesADrag_orAbandonsAPendingPress_withoutHaptics() {
        pad.down(0, 0, 100f, 100f)
        pad.tick(340)
        out.take()
        pad.reset(400)
        assertEquals(listOf("endDrag"), out.take())
        assertNull(pad.nextDeadlineMs)
        // Its contacts are forgotten: their late events do nothing.
        pad.move(420, 0, 200f, 100f)
        pad.up(430, 0)
        assertEquals(emptyList<String>(), out.take())

        pad.down(1000, 0, 100f, 100f)
        out.take()
        pad.reset(1100)
        assertEquals(listOf("abandon@1100"), out.take())
    }

    @Test
    fun reset_keepsTheLastTap_soARerenderDoesNotDisarm() {
        tapAt(0, 100)
        pad.reset(150)
        pad.down(250, 0, 100f, 100f)
        pad.move(266, 0, 108f, 100f)
        assertEquals(listOf("press@0", "tap", "press@250"), out.take())
    }

    @Test
    fun everyPress_endsInExactlyOneResolution() {
        val resolutions = setOf("tap", "beginDrag", "abandon")
        fun resolved(calls: List<String>) = calls.count { c -> resolutions.any { c.startsWith(it) } }

        tapAt(0, 100)
        assertEquals(1, resolved(out.take()))
        pad.down(1000, 0, 100f, 100f)
        pad.move(1016, 0, 150f, 100f)
        pad.move(1032, 0, 200f, 100f)
        pad.down(1040, 1, 300f, 100f)
        pad.up(1100, 0)
        pad.up(1110, 1)
        assertEquals(1, resolved(out.take()))
        pad.down(2000, 0, 100f, 100f)
        pad.tick(2340)
        pad.down(2400, 1, 300f, 100f)
        pad.cancelAll(2450)
        assertEquals(1, resolved(out.take()))
    }
}
