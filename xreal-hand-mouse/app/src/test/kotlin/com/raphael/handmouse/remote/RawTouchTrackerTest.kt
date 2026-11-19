package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.Evdev.ABS_MT_POSITION_X
import com.raphael.handmouse.remote.Evdev.ABS_MT_POSITION_Y
import com.raphael.handmouse.remote.Evdev.ABS_MT_SLOT
import com.raphael.handmouse.remote.Evdev.ABS_MT_TRACKING_ID
import com.raphael.handmouse.remote.Evdev.EV_ABS
import com.raphael.handmouse.remote.Evdev.EV_KEY
import com.raphael.handmouse.remote.Evdev.EV_SYN
import com.raphael.handmouse.remote.Evdev.KEY_INT_CANCEL
import com.raphael.handmouse.remote.Evdev.SYN_DROPPED
import com.raphael.handmouse.remote.Evdev.SYN_REPORT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTouchTrackerTest {

    /** The SM-S937N's sec_touchscreen: 0..4095 on both axes, ten slots. */
    private val screen = TouchNode("/dev/input/event7", "sec_touchscreen", 0, 4095, 0, 4095, slots = 10)
    private val tracker = MtSlotTracker(screen)

    private val ABS_MT_TOUCH_MAJOR = 0x30

    /** Feeds (code, value) ABS events, then SYN_REPORT; returns what the report produced. */
    private fun report(vararg abs: Int): List<TouchContact>? {
        for (i in abs.indices step 2) assertNull(tracker.onEvent(EV_ABS, abs[i], abs[i + 1]))
        return tracker.onEvent(EV_SYN, SYN_REPORT, 0)
    }

    private fun x(raw: Int) = raw / 4096f

    @Test
    fun oneFingerLandsMovesAndLifts() {
        assertEquals(
            listOf(TouchContact(100, x(2048), x(1024))),
            report(ABS_MT_TRACKING_ID, 100, ABS_MT_POSITION_X, 2048, ABS_MT_POSITION_Y, 1024),
        )
        assertEquals(listOf(TouchContact(100, x(2100), x(1024))), report(ABS_MT_POSITION_X, 2100))
        assertEquals(emptyList<TouchContact>(), report(ABS_MT_TRACKING_ID, -1))
        assertTrue(tracker.contacts.isEmpty())
        assertFalse("a real lift", tracker.lastCancelLifts)
    }

    @Test
    fun aReportThatMovesNothingIsNoFrame() {
        report(ABS_MT_TRACKING_ID, 7, ABS_MT_POSITION_X, 10, ABS_MT_POSITION_Y, 20)
        assertNull(report(ABS_MT_TOUCH_MAJOR, 9))
        assertNull(tracker.onEvent(EV_SYN, SYN_REPORT, 0))
        // A lift of a slot that has no contact changes nothing either.
        assertNull(report(ABS_MT_SLOT, 3, ABS_MT_TRACKING_ID, -1))
    }

    @Test
    fun twoFingersInTwoSlotsKeepTheirIds() {
        report(ABS_MT_TRACKING_ID, 1, ABS_MT_POSITION_X, 100, ABS_MT_POSITION_Y, 200)
        assertEquals(
            listOf(TouchContact(1, x(100), x(200)), TouchContact(2, x(300), x(400))),
            report(ABS_MT_SLOT, 1, ABS_MT_TRACKING_ID, 2, ABS_MT_POSITION_X, 300, ABS_MT_POSITION_Y, 400),
        )
        // The current slot stays 1 until the kernel names another one.
        assertEquals(
            listOf(TouchContact(1, x(100), x(200)), TouchContact(2, x(310), x(400))),
            report(ABS_MT_POSITION_X, 310),
        )
        assertEquals(listOf(TouchContact(2, x(310), x(400))), report(ABS_MT_SLOT, 0, ABS_MT_TRACKING_ID, -1))
    }

    @Test
    fun aSlotKeepsItsLastPositionForTheNextFinger() {
        report(ABS_MT_TRACKING_ID, 1, ABS_MT_POSITION_X, 100, ABS_MT_POSITION_Y, 200)
        report(ABS_MT_TRACKING_ID, -1)
        // The kernel sends no POSITION_X for a new finger at the same x.
        assertEquals(listOf(TouchContact(2, x(100), x(250))), report(ABS_MT_TRACKING_ID, 2, ABS_MT_POSITION_Y, 250))
    }

    @Test
    fun anAxisNeverSeenIsNaN() {
        val contacts = report(ABS_MT_TRACKING_ID, 5, ABS_MT_POSITION_Y, 250)!!
        assertTrue(contacts.single().x.isNaN())
        assertEquals(x(250), contacts.single().y)
        assertFalse(contacts.single().hasPosition)
        assertTrue(report(ABS_MT_POSITION_X, 1)!!.single().hasPosition)
    }

    @Test
    fun synDroppedCancelsEveryContactAndSkipsToTheNextReport() {
        report(ABS_MT_TRACKING_ID, 1, ABS_MT_POSITION_X, 100, ABS_MT_POSITION_Y, 200)
        assertEquals(emptyList<TouchContact>(), tracker.onEvent(EV_SYN, SYN_DROPPED, 0))
        assertTrue("taken away, not lifted", tracker.lastCancelLifts)
        // The rest of the broken report is ignored, new contacts included.
        assertNull(report(ABS_MT_SLOT, 1, ABS_MT_TRACKING_ID, 2, ABS_MT_POSITION_X, 500))
        // A finger still down after the drop sends no new tracking id: it stays unseen.
        assertNull(report(ABS_MT_POSITION_X, 600))
        assertEquals(listOf(TouchContact(3, x(700), x(800))), report(ABS_MT_TRACKING_ID, 3, ABS_MT_POSITION_X, 700, ABS_MT_POSITION_Y, 800))
        assertFalse(tracker.lastCancelLifts)
    }

    @Test
    fun aSlotNamedInTheIgnoredPartOfADropStaysTheCurrentSlot() {
        report(ABS_MT_TRACKING_ID, 1, ABS_MT_POSITION_X, 100, ABS_MT_POSITION_Y, 200)
        report(ABS_MT_SLOT, 1, ABS_MT_TRACKING_ID, 2, ABS_MT_POSITION_X, 300, ABS_MT_POSITION_Y, 400)
        report(ABS_MT_SLOT, 0, ABS_MT_POSITION_X, 110)
        tracker.onEvent(EV_SYN, SYN_DROPPED, 0)
        // Only the slot counts in the ignored part; the lift there is ignored like the rest.
        assertNull(report(ABS_MT_SLOT, 1, ABS_MT_TRACKING_ID, -1))
        // The kernel does not repeat the slot: the next finger is in slot 1 (y 400 kept there), not slot 0.
        assertEquals(listOf(TouchContact(4, x(310), x(400))), report(ABS_MT_TRACKING_ID, 4, ABS_MT_POSITION_X, 310))
    }

    @Test
    fun keyIntCancel_makesTheDriversReleaseACancel_untilNothingIsDown() {
        report(ABS_MT_TRACKING_ID, 1, ABS_MT_POSITION_X, 100, ABS_MT_POSITION_Y, 200)
        report(ABS_MT_SLOT, 1, ABS_MT_TRACKING_ID, 2, ABS_MT_POSITION_X, 300, ABS_MT_POSITION_Y, 400)
        // sec_input: KEY_INT_CANCEL 1, sync, 0, sync, then every slot released in one report.
        assertNull(tracker.onEvent(EV_KEY, KEY_INT_CANCEL, 1))
        assertNull(tracker.onEvent(EV_SYN, SYN_REPORT, 0))
        assertNull(tracker.onEvent(EV_KEY, KEY_INT_CANCEL, 0))
        assertNull(tracker.onEvent(EV_SYN, SYN_REPORT, 0))
        assertEquals(listOf(TouchContact(1, x(100), x(200))), report(ABS_MT_TRACKING_ID, -1))
        assertTrue(tracker.lastCancelLifts)
        assertEquals(emptyList<TouchContact>(), report(ABS_MT_SLOT, 0, ABS_MT_TRACKING_ID, -1))
        assertTrue(tracker.lastCancelLifts)
        // Over once nothing is down: the next touch lifts normally.
        report(ABS_MT_TRACKING_ID, 3, ABS_MT_POSITION_X, 10)
        assertFalse(tracker.lastCancelLifts)
        assertEquals(emptyList<TouchContact>(), report(ABS_MT_TRACKING_ID, -1))
        assertFalse(tracker.lastCancelLifts)
    }

    @Test
    fun keyIntCancelWithNothingDown_cancelsNothingLater() {
        tracker.onEvent(EV_KEY, KEY_INT_CANCEL, 1)
        tracker.onEvent(EV_SYN, SYN_REPORT, 0)
        report(ABS_MT_TRACKING_ID, 5, ABS_MT_POSITION_X, 10, ABS_MT_POSITION_Y, 10)
        report(ABS_MT_TRACKING_ID, -1)
        assertFalse(tracker.lastCancelLifts)
    }

    @Test
    fun synDroppedWithNothingDownIsNoFrame() {
        assertNull(tracker.onEvent(EV_SYN, SYN_DROPPED, 0))
        assertNull(tracker.onEvent(EV_SYN, SYN_REPORT, 0))
        assertEquals(listOf(TouchContact(4, x(1), x(2))), report(ABS_MT_TRACKING_ID, 4, ABS_MT_POSITION_X, 1, ABS_MT_POSITION_Y, 2))
    }

    @Test
    fun aSlotBeyondTheProbedOnesIsIgnored() {
        assertNull(report(ABS_MT_SLOT, 10, ABS_MT_TRACKING_ID, 9, ABS_MT_POSITION_X, 1))
        assertNull(report(ABS_MT_SLOT, -1, ABS_MT_TRACKING_ID, 9))
    }

    @Test
    fun positionsAreClampedToTheRangeAndUseInputReadersScale() {
        val small = MtSlotTracker(TouchNode("p", "n", 100, 199, 0, 9, slots = 1))
        small.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 1)
        small.onEvent(EV_ABS, ABS_MT_POSITION_X, 150)
        small.onEvent(EV_ABS, ABS_MT_POSITION_Y, 50)
        assertEquals(listOf(TouchContact(1, 0.5f, 1f)), small.onEvent(EV_SYN, SYN_REPORT, 0))
        small.onEvent(EV_ABS, ABS_MT_POSITION_X, 0)
        assertEquals(listOf(TouchContact(1, 0f, 1f)), small.onEvent(EV_SYN, SYN_REPORT, 0))
    }

    @Test
    fun trackingIdsAreKeptToSixteenBitsPlusTheNodeBase() {
        val second = MtSlotTracker(screen, idBase = 1 shl 16)
        second.onEvent(EV_ABS, ABS_MT_TRACKING_ID, 65535)
        assertEquals(listOf(0x1ffff), second.onEvent(EV_SYN, SYN_REPORT, 0)!!.map { it.id })
    }

    // --- decoding ---

    /** `struct input_event` records as read(2) returns them: little-endian, timeval first. */
    private fun records(recordSize: Int, vararg events: Int): ByteArray {
        val buf = ByteBuffer.allocate(events.size / 3 * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in events.indices step 3) {
            repeat(recordSize - 8) { buf.put(0x5a) } // timeval bytes, never read
            buf.putShort(events[i].toShort())
            buf.putShort(events[i + 1].toShort())
            buf.putInt(events[i + 2])
        }
        return buf.array()
    }

    private fun decodeAll(bytes: ByteArray, length: Int, recordSize: Int): Pair<Int, List<Triple<Int, Int, Int>>> {
        val seen = ArrayList<Triple<Int, Int, Int>>()
        val used = Evdev.decode(bytes, length, recordSize) { type, code, value -> seen += Triple(type, code, value) }
        return used to seen
    }

    @Test
    fun decodesSixtyFourBitRecordsWithNegativeValues() {
        val bytes = records(24, EV_ABS, ABS_MT_TRACKING_ID, -1, EV_ABS, ABS_MT_POSITION_X, 4095, EV_SYN, SYN_REPORT, 0)
        assertEquals(
            72 to listOf(Triple(EV_ABS, ABS_MT_TRACKING_ID, -1), Triple(EV_ABS, ABS_MT_POSITION_X, 4095), Triple(EV_SYN, SYN_REPORT, 0)),
            decodeAll(bytes, bytes.size, 24),
        )
        assertEquals(24, Evdev.recordSize(is64Bit = true))
        assertEquals(16, Evdev.recordSize(is64Bit = false))
    }

    @Test
    fun decodesThirtyTwoBitRecordsAndLeavesAPartialTail() {
        val bytes = records(16, EV_ABS, ABS_MT_SLOT, 1, EV_ABS, ABS_MT_POSITION_Y, 70000)
        val (used, seen) = decodeAll(bytes, bytes.size - 5, 16)
        assertEquals(16, used)
        assertEquals(listOf(Triple(EV_ABS, ABS_MT_SLOT, 1)), seen)
    }

    @Test
    fun framesMergeEveryNodeAndCarryTheNodeInTheId() {
        val frames = RawTouchFrames(listOf(screen, screen.copy(path = "/dev/input/event5")), recordSize = 24)
        val seen = ArrayList<List<TouchContact>>()
        val down = records(
            24,
            EV_ABS, ABS_MT_TRACKING_ID, 8, EV_ABS, ABS_MT_POSITION_X, 2048, EV_ABS, ABS_MT_POSITION_Y, 2048, EV_SYN, SYN_REPORT, 0,
        )
        assertEquals(down.size, frames.feed(0, down, down.size) { contacts, _ -> seen += contacts })
        assertEquals(down.size, frames.feed(1, down, down.size) { contacts, _ -> seen += contacts })
        val lift = records(24, EV_ABS, ABS_MT_TRACKING_ID, -1, EV_SYN, SYN_REPORT, 0)
        frames.feed(0, lift, lift.size) { contacts, _ -> seen += contacts }
        assertEquals(
            listOf(
                listOf(TouchContact(8, 0.5f, 0.5f)),
                listOf(TouchContact(8, 0.5f, 0.5f), TouchContact(0x10008, 0.5f, 0.5f)),
                listOf(TouchContact(0x10008, 0.5f, 0.5f)),
            ),
            seen,
        )
        assertEquals(seen.last(), frames.contacts)
    }

    @Test
    fun severalReportsInOneReadGiveOneFrameEach() {
        val frames = RawTouchFrames(listOf(screen), recordSize = 24)
        val bytes = records(
            24,
            EV_ABS, ABS_MT_TRACKING_ID, 1, EV_ABS, ABS_MT_POSITION_X, 0, EV_ABS, ABS_MT_POSITION_Y, 0, EV_SYN, SYN_REPORT, 0,
            EV_ABS, ABS_MT_POSITION_X, 4096 / 4, EV_SYN, SYN_REPORT, 0,
            EV_ABS, ABS_MT_TOUCH_MAJOR, 3, EV_SYN, SYN_REPORT, 0,
            EV_ABS, ABS_MT_TRACKING_ID, -1, EV_SYN, SYN_REPORT, 0,
        )
        val seen = ArrayList<List<TouchContact>>()
        frames.feed(0, bytes, bytes.size) { contacts, _ -> seen += contacts }
        assertEquals(
            listOf(listOf(TouchContact(1, 0f, 0f)), listOf(TouchContact(1, 0.25f, 0f)), emptyList()),
            seen,
        )
    }

    @Test
    fun aDropReachesTheFramesAsACancel() {
        val frames = RawTouchFrames(listOf(screen), recordSize = 24)
        val bytes = records(
            24,
            EV_ABS, ABS_MT_TRACKING_ID, 1, EV_ABS, ABS_MT_POSITION_X, 0, EV_ABS, ABS_MT_POSITION_Y, 0, EV_SYN, SYN_REPORT, 0,
            EV_SYN, SYN_DROPPED, 0,
        )
        val seen = ArrayList<Pair<Int, Boolean>>()
        frames.feed(0, bytes, bytes.size) { contacts, cancelLifts -> seen += contacts.size to cancelLifts }
        assertEquals(listOf(1 to false, 0 to true), seen)
    }
}
