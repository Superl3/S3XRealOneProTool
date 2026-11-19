package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTouchProbeTest {

    /**
     * `getevent -pl` as the SM-S937N prints it (node layout and ranges from its dumpsys input of
     * 2026-11-18): the touchscreen, Samsung's DeX touchpad node of the same panel (not DIRECT), the
     * glasses' joystick, the Remote's virtual mouse and a key device, plus an error line.
     */
    private val s937n = """
        add device 1: /dev/input/event12
          name:     "XrealRemote Mouse"
          events:
            KEY (0001): BTN_MOUSE             BTN_RIGHT             BTN_MIDDLE
            REL (0002): REL_X                 REL_Y                 REL_HWHEEL            REL_WHEEL
          input props:
            <none>
        add device 2: /dev/input/event8
          name:     "sec_touchpad"
          events:
            KEY (0001): KEY_WAKEUP            BTN_LEFT              BTN_RIGHT             BTN_TOOL_FINGER
                        BTN_TOUCH             BTN_TOOL_DOUBLETAP    BTN_TOOL_TRIPLETAP    BTN_TOOL_QUADTAP
            ABS (0003): ABS_X                 : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 15
                        ABS_Y                 : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 15
                        ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                        ABS_MT_TOUCH_MINOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 15
                        ABS_MT_POSITION_Y     : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 15
                        ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_POINTER
            INPUT_PROP_BUTTONPAD
        could not open /dev/input/event9, Permission denied
        add device 3: /dev/input/event11
          name:     "XREAL XREAL One Pro"
          events:
            KEY (0001): KEY_ESC               KEY_ENTER             KEY_VOLUMEDOWN        KEY_VOLUMEUP
            ABS (0003): ABS_MISC              : value 1, min 1, max 652, fuzz 0, flat 0, resolution 0
          input props:
            <none>
        add device 4: /dev/input/event7
          name:     "sec_touchscreen"
          events:
            KEY (0001): KEY_WAKEUP            BTN_TOOL_FINGER       BTN_TOUCH             0226
                        02be
            ABS (0003): ABS_X                 : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
                        ABS_Y                 : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
                        ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_TOUCH_MAJOR    : value 7, min 0, max 255, fuzz 0, flat 0, resolution 0
                        ABS_MT_TOUCH_MINOR    : value 5, min 0, max 255, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 478, min 0, max 4095, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 359, min 0, max 4095, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID    : value -1, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
        add device 5: /dev/input/event3
          name:     "gpio-keys"
          events:
            KEY (0001): KEY_VOLUMEDOWN        KEY_VOLUMEUP          KEY_POWER
          input props:
            <none>
    """.trimIndent()

    @Test
    fun findsOnlyTheDirectMultiTouchScreen() {
        assertEquals(
            listOf(TouchNode("/dev/input/event7", "sec_touchscreen", 0, 4095, 0, 4095, slots = 10)),
            RawTouchProbe.parse(s937n),
        )
    }

    @Test
    fun readsTheHexCodesGeteventPrintsWithoutLabels() {
        val hex = """
            add device 1: /dev/input/event2
              name:     "touch_dev"
              events:
                type 0001: 014a
                type 0003: 002f  : value 0, min 0, max 4, fuzz 0, flat 0, resolution 0
                           0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                           0036  : value 0, min 0, max 2339, fuzz 0, flat 0, resolution 0
                           0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
              input props:
                0001
        """.trimIndent()
        assertEquals(listOf(TouchNode("/dev/input/event2", "touch_dev", 0, 1079, 0, 2339, slots = 5)), RawTouchProbe.parse(hex))
    }

    private fun screen(index: Int, path: String, name: String, maxX: Int, maxY: Int) = """
        add device $index: $path
          name:     "$name"
          events:
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max $maxX, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max $maxY, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
    """.trimIndent()

    @Test
    fun keepsEveryTouchscreenInOrderAndHandlesCrLf() {
        // A foldable: one node per screen.
        val fold = screen(1, "/dev/input/event4", "sec_touchscreen", 4095, 4095) + "\n" +
            screen(2, "/dev/input/event5", "sec_touchscreen2", 2159, 4095)
        assertEquals(
            listOf(
                TouchNode("/dev/input/event4", "sec_touchscreen", 0, 4095, 0, 4095, slots = 10),
                TouchNode("/dev/input/event5", "sec_touchscreen2", 0, 2159, 0, 4095, slots = 10),
            ),
            RawTouchProbe.parse(fold.replace("\n", "\r\n")),
        )
    }

    @Test
    fun aDirectDeviceWithoutTrackingIdsOrWithAnEmptyRangeIsNotATouchscreen() {
        val protocolA = s937n.replace("ABS_MT_TRACKING_ID    : value -1, min 0, max 65535", "ABS_MT_PRESSURE       : value 0, min 0, max 63")
        assertTrue(RawTouchProbe.parse(protocolA).isEmpty())
        val flat = s937n.replace("ABS_MT_POSITION_X     : value 478, min 0, max 4095", "ABS_MT_POSITION_X     : value 0, min 0, max 0")
        assertTrue(RawTouchProbe.parse(flat).isEmpty())
    }

    @Test
    fun aSlotlessDeviceHasOneSlot() {
        val slotless = s937n.replace(Regex(" *ABS_MT_SLOT +: value 0, min 0, max 9[^\n]*\n"), "")
        assertEquals(listOf(1), RawTouchProbe.parse(slotless).map { it.slots })
    }

    @Test
    fun noOutputMeansNoTouchscreen() {
        assertTrue(RawTouchProbe.parse("").isEmpty())
        assertTrue(RawTouchProbe.parse("could not open /dev/input, Permission denied").isEmpty())
    }

    @Test
    fun aRunThatListsNoDevice_isProbedAgain_butAListWithoutATouchscreenIsKept() {
        // RemoteCommandRunner's exit 0 with no text: the output thread missed its wait.
        assertNull(RawTouchProbe.result(0, ""))
        assertNull(RawTouchProbe.result(0, "   \n"))
        assertNull(RawTouchProbe.result(1, "add device 1: /dev/input/event3\n  name:     \"pmic_resin\"\n"))
        assertEquals(emptyList<TouchNode>(), RawTouchProbe.result(0, "add device 1: /dev/input/event3\n  name:     \"pmic_resin\"\n"))
        assertEquals(1, RawTouchProbe.result(1, s937n)!!.size)
    }
}
