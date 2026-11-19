package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCursorQueryTest {
    // Shape of the 2026-11-18 device dump (SM-S937N, SDK 37), shortened.
    private val cursorSection = """
  CursorStatesByDisplay:
    LogicalDisplayId 63:
        0: name='1bed02 app.morphe.android.youtube/app.morphe.android.youtube.morphe_original_1', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates=
          DeviceId(41):[touchingPointers=[], downTimeInTarget=<not set>, hoveringPointers=[Pointer(id=0, MOUSE) at (1298.1, 662.489)], pilferingPointerIds=<none>]
        1: name='PointerEventDispatcherOverlay63', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates=
          DeviceId(41):[touchingPointers=[], downTimeInTarget=<not set>, hoveringPointers=[Pointer(id=0, MOUSE) at (1298.1, 662.489)], pilferingPointerIds=<none>]
    LogicalDisplayId 0:
      TouchedWindows: <none>
    LogicalDisplayId 57:
      TouchedWindows: <none>
  Display: 63
"""

    @Test
    fun `reads the mouse position on the asked display`() {
        assertEquals(1298.1f to 662.489f, SystemCursorQuery.parseCursor(cursorSection, 63))
    }

    @Test
    fun `a display without a hovering mouse gives null`() {
        assertNull(SystemCursorQuery.parseCursor(cursorSection, 0))
        assertNull(SystemCursorQuery.parseCursor(cursorSection, 57))
    }

    @Test
    fun `missing section or empty output gives null`() {
        assertNull(SystemCursorQuery.parseCursor("", 63))
        assertNull(SystemCursorQuery.parseCursor("  CursorStatesByDisplay:\n    LogicalDisplayId 63:\n", 63))
    }

    @Test
    fun `the stale copy under the last ANR is not read`() {
        val current = "  CursorStatesByDisplay:\n    LogicalDisplayId 63:\n      TouchedWindows: <none>\n  Display: 63\n"
        val anrCopy = "Input Dispatcher State at time of last ANR:\n  CursorStatesByDisplay:\n    LogicalDisplayId 63:\n" +
            "          DeviceId(41):[hoveringPointers=[Pointer(id=0, MOUSE) at (300, 900)], pilferingPointerIds=<none>]\n"
        assertNull(SystemCursorQuery.parseCursor(current + anrCopy, 63))
    }

    @Test
    fun `the virtual mouse wins over another mouse`() {
        val text = "  CursorStatesByDisplay:\n    LogicalDisplayId 63:\n" +
            "          DeviceId(7):[hoveringPointers=[Pointer(id=0, MOUSE) at (10, 20)], pilferingPointerIds=<none>]\n" +
            "          DeviceId(41):[hoveringPointers=[Pointer(id=0, MOUSE) at (1919, 1079)], pilferingPointerIds=<none>]\n"
        assertEquals(1919f to 1079f, SystemCursorQuery.parseCursor(text, 63, setOf(41)))
        assertEquals(10f to 20f, SystemCursorQuery.parseCursor(text, 63))
    }

    @Test
    fun `decoration frames are read per display and empty ones skipped`() {
        val output = cursorSection + SystemCursorQuery.DECOR_MARKER + "\n" +
            "      7: name=Embedded{View root of ReusableWindowDecorViewHost#32}, id=62601, displayId=63, inputConfig=NOT_FOCUSABLE, frame=[0,0][1920,60], globalScale=1\n" +
            "      8: name=Embedded{View root of ReusableWindowDecorViewHost#33}, id=62602, displayId=63, inputConfig=NOT_FOCUSABLE, frame=[0,0][0,0], globalScale=1\n" +
            "      9: name=CaptionWindow, id=1, displayId=0, frame=[0,0][100,40]\n"
        val snapshot = SystemCursorQuery.parse(output, 63)
        assertEquals(1298.1f to 662.489f, snapshot.cursor)
        assertEquals(listOf(SystemCursorQuery.Frame(63, 0, 0, 1920, 60)), snapshot.decorFrames)
    }

    @Test
    fun `no marker means no decoration evidence`() {
        assertTrue(SystemCursorQuery.parse(cursorSection, 63).decorFrames.isEmpty())
    }
}
