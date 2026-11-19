package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.PanelTouchRouter.Area
import com.raphael.handmouse.remote.PanelTouchRouter.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTouchRouterTest {
    /** Records every output call as a short string. */
    private class Recorder : PanelTouchRouter.Output {
        val calls = ArrayList<String>()

        override fun padDown(atMs: Long, id: Int, x: Float, y: Float) {
            calls += "down($id@$atMs ${x.toInt()},${y.toInt()})"
        }

        override fun padPlace(id: Int, x: Float, y: Float) {
            calls += "place($id ${x.toInt()},${y.toInt()})"
        }

        override fun padMoved(atMs: Long) {
            calls += "moved@$atMs"
        }

        override fun padUp(atMs: Long, id: Int) {
            calls += "up($id@$atMs)"
        }

        override fun padCancel(atMs: Long, id: Int) {
            calls += "cancel($id@$atMs)"
        }

        override fun padCancelAll(atMs: Long) {
            calls += "cancelAll@$atMs"
        }

        override fun otherTouch() {
            calls += "otherTouch"
        }

        override fun touchStarted(atMs: Long) {
            calls += "started@$atMs"
        }

        override fun holdAir() {
            calls += "holdAir"
        }

        override fun releaseAir() {
            calls += "releaseAir"
        }

        override fun touchEnded() {
            calls += "ended"
        }

        fun take(): List<String> = calls.toList().also { calls.clear() }
    }

    private val out = Recorder()
    private val router = PanelTouchRouter(out)

    /**
     * Test placement, with a contact's x and y taken as touchpad pixels: the touchpad is x 0..200,
     * y 0..100, the rest of the panel reaches y 160, everything else is outside.
     */
    private var placeable = true
    private val locate: (TouchContact) -> Spot? = { c ->
        if (!placeable) {
            null
        } else {
            val area = when {
                c.x < 0f || c.x >= 200f || c.y < 0f || c.y >= 160f -> Area.OUTSIDE
                c.y < 100f -> Area.PAD
                else -> Area.PANEL
            }
            Spot(c.x, c.y, area)
        }
    }

    private fun frame(atMs: Long, vararg contacts: TouchContact) = router.rawFrame(TouchFrame(atMs, contacts.toList()), locate)

    private fun at(id: Int, x: Float, y: Float) = TouchContact(id, x, y)

    @Test
    fun withoutRawFrames_theWindowFeedsTheTouchpad_asBefore() {
        router.windowPadDown(10, 0, 50f, 50f, first = true)
        router.panelDown(10, first = true)
        assertEquals(listOf("cancelAll@10", "down(0@10 50,50)", "started@10", "holdAir"), out.take())
        router.windowPadMove(0, 60f, 50f)
        router.windowPadMoved(20)
        router.windowPadUp(30, 0, last = true)
        router.panelEnd()
        assertEquals(listOf("place(0 60,50)", "moved@20", "up(0@30)", "releaseAir", "ended"), out.take())
        assertFalse(router.touched)
    }

    @Test
    fun aWindowMoveOfSeveralPointers_isOneEvaluation() {
        router.windowPadDown(10, 0, 30f, 30f, first = true)
        router.windowPadDown(12, 1, 90f, 30f, first = false)
        out.take()
        router.windowPadMove(0, 25f, 30f)
        router.windowPadMove(1, 95f, 30f)
        router.windowPadMoved(20)
        assertEquals(listOf("place(0 25,30)", "place(1 95,30)", "moved@20"), out.take())
    }

    @Test
    fun rawFramesFeedTheTouchpad_andTheWindowsOwnEventsAreOnlyConsumed() {
        router.rawStatus(true, 0)
        router.windowPadDown(10, 0, 50f, 50f, first = true)
        router.panelDown(10, first = true)
        // The window's touch marks the panel touched, but its touchpad finger feeds nothing and
        // holds nothing: that comes with the raw contact.
        assertEquals(listOf("started@10"), out.take())
        frame(9, at(7, 50f, 50f))
        assertEquals(listOf("down(7@9 50,50)", "holdAir"), out.take())
        // The input filter cancels the window's touch once the virtual mouse sends.
        router.windowPadMove(0, 55f, 50f)
        router.windowPadMoved(20)
        router.windowPadCancel(30)
        router.panelEnd()
        assertEquals(emptyList<String>(), out.take())
        assertTrue(router.touched)
        frame(40, at(7, 80f, 52f))
        frame(50)
        assertEquals(listOf("place(7 80,52)", "moved@40", "up(7@50)", "releaseAir", "ended"), out.take())
        assertFalse(router.touched)
    }

    @Test
    fun aRawFrameBeforeTheWindowsDown_holdsAndReleasesOnce() {
        router.rawStatus(true, 0)
        frame(5, at(3, 20f, 20f))
        assertEquals(listOf("down(3@5 20,20)", "started@5", "holdAir"), out.take())
        router.windowPadDown(8, 0, 20f, 20f, first = true)
        router.panelDown(8, first = true)
        assertEquals(emptyList<String>(), out.take())
        // The window's lift first: the raw contact still holds the panel.
        router.windowPadUp(60, 0, last = true)
        router.panelEnd()
        assertEquals(emptyList<String>(), out.take())
        frame(61)
        assertEquals(listOf("up(3@61)", "releaseAir", "ended"), out.take())
    }

    @Test
    fun aContactLandingOffTheTouchpad_neverFeedsIt_evenWhenItSlidesOnto() {
        router.rawStatus(true, 0)
        frame(10, at(1, 50f, 130f))
        assertEquals(listOf("otherTouch"), out.take())
        frame(20, at(1, 50f, 50f))
        frame(30, at(1, 60f, 40f))
        frame(40)
        assertEquals(emptyList<String>(), out.take())
        // Off the panel altogether: not even a disarm.
        frame(50, at(2, 500f, 500f))
        frame(60, at(2, 50f, 50f))
        frame(70)
        assertEquals(emptyList<String>(), out.take())
        assertFalse(router.touched)
    }

    @Test
    fun aContactWithoutAPositionWaits_andItsFirstPlacedFrameDecides() {
        router.rawStatus(true, 0)
        frame(10, TouchContact(4, Float.NaN, 50f))
        assertEquals(emptyList<String>(), out.take())
        frame(20, at(4, 30f, 50f))
        assertEquals(listOf("down(4@20 30,50)", "started@20", "holdAir"), out.take())
    }

    @Test
    fun aPanelThatCannotBePlaced_letsANewContactWait_andSkipsAMoveFrame() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f))
        out.take()
        placeable = false
        // A landing now (a layout under way) waits; the touchpad contact's frame is skipped.
        frame(20, at(1, 40f, 30f), at(2, 60f, 60f))
        assertEquals(emptyList<String>(), out.take())
        placeable = true
        // The window does not feed that finger (raw frames do): its first placed frame decides.
        frame(30, at(1, 45f, 30f), at(2, 61f, 60f))
        assertEquals(listOf("place(1 45,30)", "moved@30", "down(2@30 61,60)", "holdAir"), out.take())
    }

    @Test
    fun aTapLandingDuringALayout_isATapOnceTheNextFrameCanBePlaced() {
        router.rawStatus(true, 0)
        placeable = false
        frame(10, at(1, 30f, 30f))
        router.windowPadDown(11, 0, 30f, 30f, first = true)
        router.panelDown(11, first = true)
        assertEquals(listOf("started@11"), out.take())
        placeable = true
        frame(20, at(1, 31f, 30f))
        frame(90)
        router.windowPadUp(91, 0, last = true)
        router.panelEnd()
        assertEquals(listOf("down(1@20 31,30)", "holdAir", "up(1@90)", "releaseAir", "ended"), out.take())
    }

    @Test
    fun anUnchangedPosition_movesNothing() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f), at(2, 100f, 30f))
        out.take()
        frame(20, at(1, 30f, 30f), at(2, 110f, 30f))
        assertEquals(listOf("place(2 110,30)", "moved@20"), out.take())
    }

    @Test
    fun severalContactsMovingInOneFrame_areOneEvaluation() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f), at(2, 100f, 30f))
        out.take()
        frame(20, at(1, 20f, 30f), at(2, 110f, 30f))
        assertEquals(listOf("place(1 20,30)", "place(2 110,30)", "moved@20"), out.take())
    }

    @Test
    fun aFrameThatCancelsItsLifts_cancelsTheTouchpadContacts_insteadOfLiftingThem() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f), at(2, 50f, 130f))
        out.take()
        // SYN_DROPPED, or the touchscreen driver's own cancel: no lift, so no tap.
        router.rawFrame(TouchFrame(20, emptyList(), cancelLifts = true), locate)
        assertEquals(listOf("cancel(1@20)", "releaseAir", "ended"), out.take())
        assertFalse(router.touched)
        frame(30, at(3, 30f, 30f))
        assertEquals(listOf("down(3@30 30,30)", "started@30", "holdAir"), out.take())
    }

    @Test
    fun aFrameAppliesLifts_thenMoves_thenLandings() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f), at(2, 100f, 30f))
        assertEquals(listOf("down(1@10 30,30)", "started@10", "holdAir", "down(2@10 100,30)", "holdAir"), out.take())
        frame(20, at(2, 120f, 40f), at(3, 10f, 10f))
        assertEquals(listOf("up(1@20)", "place(2 120,40)", "moved@20", "down(3@20 10,10)", "holdAir"), out.take())
        // The panel stays touched while any touchpad contact remains.
        frame(30, at(3, 10f, 10f))
        assertEquals(listOf("up(2@30)"), out.take())
        frame(40)
        assertEquals(listOf("up(3@40)", "releaseAir", "ended"), out.take())
    }

    @Test
    fun rawFramesStopping_cancelTheTouchpadContacts_andAFingerStillDownIsNoNewTouch() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f), at(2, 50f, 130f))
        out.take()
        router.rawStatus(false, 25)
        assertEquals(listOf("cancel(1@25)", "releaseAir", "ended"), out.take())
        // Frames that still arrive are ignored.
        frame(30, at(1, 40f, 30f))
        assertEquals(emptyList<String>(), out.take())
        // The frames return with the same finger down: it stays alone; a new finger is a press.
        router.rawStatus(true, 40)
        frame(50, at(1, 45f, 30f), at(5, 20f, 20f))
        assertEquals(listOf("down(5@50 20,20)", "started@50", "holdAir"), out.take())
        frame(60, at(5, 20f, 20f))
        frame(70)
        assertEquals(listOf("up(5@70)", "releaseAir", "ended"), out.take())
        // Its next touch is new again.
        frame(80, at(1, 30f, 30f))
        assertEquals(listOf("down(1@80 30,30)", "started@80", "holdAir"), out.take())
    }

    @Test
    fun rawFramesStopping_whileTheWindowsTouchIsDown_releaseAtTheWindowsLift() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f))
        router.windowPadDown(11, 0, 30f, 30f, first = true)
        router.panelDown(11, first = true)
        out.take()
        router.rawStatus(false, 20)
        assertEquals(listOf("cancel(1@20)"), out.take())
        // The window's touch began on raw frames: its later events still feed nothing.
        router.windowPadMove(0, 40f, 30f)
        router.windowPadMoved(30)
        router.windowPadUp(40, 0, last = true)
        router.panelEnd()
        assertEquals(listOf("releaseAir", "ended"), out.take())
        // The next touch is the window's again.
        router.windowPadDown(50, 0, 10f, 10f, first = true)
        assertEquals(listOf("cancelAll@50", "down(0@50 10,10)"), out.take())
    }

    @Test
    fun rawFramesStartingMidTouch_leaveThatTouchToTheWindow() {
        router.windowPadDown(10, 0, 30f, 30f, first = true)
        router.panelDown(10, first = true)
        out.take()
        router.rawStatus(true, 15)
        // The finger already on the touchpad, and a second one landing now, are not raw contacts.
        frame(20, at(1, 30f, 30f), at(2, 60f, 30f))
        router.windowPadMove(0, 35f, 30f)
        router.windowPadMoved(21)
        assertEquals(listOf("place(0 35,30)", "moved@21"), out.take())
        router.windowPadUp(40, 0, last = true)
        router.panelEnd()
        assertEquals(listOf("up(0@40)", "releaseAir", "ended"), out.take())
        frame(50, at(2, 60f, 30f))
        assertEquals(emptyList<String>(), out.take())
        // Both lift; the next touch is raw.
        frame(60)
        frame(70, at(3, 50f, 50f))
        router.windowPadDown(71, 0, 50f, 50f, first = true)
        router.panelDown(71, first = true)
        assertEquals(listOf("down(3@70 50,50)", "started@70", "holdAir"), out.take())
    }

    @Test
    fun aWindowFirstDown_cancelsLostWindowContacts_butNeverRawOnes() {
        router.windowPadDown(10, 0, 30f, 30f, first = true)
        router.windowPadDown(500, 0, 30f, 30f, first = true)
        assertEquals(listOf("cancelAll@10", "down(0@10 30,30)", "cancelAll@500", "down(0@500 30,30)"), out.take())
        router.windowPadCancel(510)
        out.take()
        router.rawStatus(true, 600)
        frame(700, at(1, 30f, 30f))
        router.windowPadDown(701, 0, 30f, 30f, first = true)
        router.windowPadDown(702, 1, 90f, 30f, first = false)
        assertEquals(listOf("down(1@700 30,30)", "started@700", "holdAir"), out.take())
    }

    @Test
    fun aButtonPress_holdsOnceFromTheWindow_andDisarmsTapDrag() {
        router.rawStatus(true, 0)
        // The button's raw contact may arrive first or second; the hold comes from the window.
        frame(10, at(1, 50f, 130f))
        router.panelDown(12, first = true)
        assertEquals(listOf("otherTouch", "started@12", "otherTouch", "holdAir"), out.take())
        router.panelEnd()
        frame(80)
        assertEquals(listOf("releaseAir", "ended"), out.take())
    }

    @Test
    fun aButtonFingerJoiningARawTouchpadTouch_holdsFromTheWindow_andTheLastLiftReleases() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f))
        router.windowPadDown(11, 0, 30f, 30f, first = true)
        router.panelDown(11, first = true)
        out.take()
        router.panelDown(40, first = false)
        assertEquals(listOf("otherTouch", "holdAir"), out.take())
        // The button finger lifts with the window's touch: the touchpad finger still holds the panel.
        router.panelEnd()
        assertEquals(emptyList<String>(), out.take())
        frame(90)
        assertEquals(listOf("up(1@90)", "releaseAir", "ended"), out.take())
    }

    @Test
    fun reset_forgetsTheTouch_andLeavesRawTouchpadContactsAloneUntilTheyLift() {
        router.rawStatus(true, 0)
        frame(10, at(1, 30f, 30f))
        router.panelDown(11, first = true)
        out.take()
        router.reset()
        assertFalse(router.touched)
        frame(20, at(1, 40f, 30f))
        frame(30)
        assertEquals(emptyList<String>(), out.take())
        frame(40, at(2, 40f, 30f))
        assertEquals(listOf("down(2@40 40,30)", "started@40", "holdAir"), out.take())
    }

    @Test
    fun repeatedStatus_changesNothing() {
        router.rawStatus(false, 0)
        router.rawStatus(true, 1)
        router.rawStatus(true, 2)
        assertTrue(router.rawActive)
        assertEquals(emptyList<String>(), out.take())
        router.rawStatus(false, 3)
        router.rawStatus(false, 4)
        assertFalse(router.rawActive)
        assertEquals(emptyList<String>(), out.take())
    }
}
