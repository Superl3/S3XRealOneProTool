package com.raphael.handmouse.audio

import android.media.AudioDeviceInfo
import com.raphael.handmouse.audio.AudioRoutingPolicy.BLOCKED
import com.raphael.handmouse.audio.AudioRoutingPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingPolicyTest {

    // Phone outputs as the SM-S937N lists them, without and with the glasses.
    private val phoneOnly = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val withGlasses = phoneOnly + AudioDeviceInfo.TYPE_USB_HEADSET
    private val withEarbuds = phoneOnly + AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

    @Test
    fun everyExternalNonUsbOutputCounts() {
        val external = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )
        for (type in external) {
            assertTrue("type $type", AudioRoutingPolicy.hasExternalNonUsbOutput(phoneOnly + type))
        }
    }

    @Test
    fun phoneUsbAndDisplayOutputsDoNotCount() {
        assertFalse(AudioRoutingPolicy.hasExternalNonUsbOutput(emptyList()))
        assertFalse(AudioRoutingPolicy.hasExternalNonUsbOutput(phoneOnly))
        // The glasses themselves, and any other USB audio.
        assertFalse(AudioRoutingPolicy.hasExternalNonUsbOutput(withGlasses))
        assertFalse(
            AudioRoutingPolicy.hasExternalNonUsbOutput(
                listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY),
            ),
        )
        // HFP alone plays no media; HDMI / dock may be the display link.
        assertFalse(
            AudioRoutingPolicy.hasExternalNonUsbOutput(
                listOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_HDMI,
                    AudioDeviceInfo.TYPE_DOCK,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
                    AudioDeviceInfo.TYPE_TELEPHONY,
                    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                ),
            ),
        )
    }

    @Test
    fun earbudsConnectedBlocksAndRemembersTheOriginal() {
        assertEquals(Step(BLOCKED, 0), AudioRoutingPolicy.next(true, withEarbuds, current = 0, ownedOriginal = null))
        // Earbuds and glasses both attached (glasses came first): still blocks the next attach.
        assertEquals(
            Step(BLOCKED, 0),
            AudioRoutingPolicy.next(true, withGlasses + AudioDeviceInfo.TYPE_BLE_HEADSET, current = 0, ownedOriginal = null),
        )
    }

    @Test
    fun whileBlockedNothingIsWrittenAgain() {
        assertEquals(Step(null, 0), AudioRoutingPolicy.next(true, withEarbuds, current = BLOCKED, ownedOriginal = 0))
    }

    @Test
    fun earbudsGoneRestoresTheOriginal() {
        assertEquals(Step(0, null), AudioRoutingPolicy.next(true, phoneOnly, current = BLOCKED, ownedOriginal = 0))
        // Also the value a previous run left behind when it was killed before it could restore.
        assertEquals(Step(0, null), AudioRoutingPolicy.next(true, withGlasses, current = BLOCKED, ownedOriginal = 0))
    }

    @Test
    fun preferenceOffRestoresTheOriginal() {
        assertEquals(Step(0, null), AudioRoutingPolicy.next(false, withEarbuds, current = BLOCKED, ownedOriginal = 0))
    }

    @Test
    fun preferenceOffWithNothingOwnedLeavesTheSettingAlone() {
        assertEquals(Step(null, null), AudioRoutingPolicy.next(false, withEarbuds, current = 0, ownedOriginal = null))
        assertEquals(Step(null, null), AudioRoutingPolicy.next(false, phoneOnly, current = BLOCKED, ownedOriginal = null))
    }

    @Test
    fun aOneTheUserSetIsNeverRestored() {
        // The developer option was on before the earbuds came: nothing to write, nothing owned.
        assertEquals(Step(null, null), AudioRoutingPolicy.next(true, withEarbuds, current = BLOCKED, ownedOriginal = null))
        // Earbuds gone: still not the guard's to change.
        assertEquals(Step(null, null), AudioRoutingPolicy.next(true, phoneOnly, current = BLOCKED, ownedOriginal = null))
    }

    @Test
    fun aValueSomeoneElseChangedIsLeftToThem() {
        // Turned off by hand while the guard held it, earbuds gone: the bookkeeping is dropped.
        assertEquals(Step(null, null), AudioRoutingPolicy.next(true, phoneOnly, current = 0, ownedOriginal = 0))
        // A crash right after the bookkeeping was saved and before the 1 was written looks the same.
        assertEquals(Step(null, null), AudioRoutingPolicy.next(false, withEarbuds, current = 0, ownedOriginal = 0))
    }

    @Test
    fun resetWhileTheEarbudsStayBlocksAgainKeepingTheFirstOriginal() {
        assertEquals(Step(BLOCKED, 0), AudioRoutingPolicy.next(true, withEarbuds, current = 0, ownedOriginal = 0))
        assertEquals(Step(BLOCKED, 2), AudioRoutingPolicy.next(true, withEarbuds, current = 0, ownedOriginal = 2))
    }

    @Test
    fun anUnusualOriginalIsPutBackAsItWas() {
        assertEquals(Step(BLOCKED, 2), AudioRoutingPolicy.next(true, withEarbuds, current = 2, ownedOriginal = null))
        assertEquals(Step(2, null), AudioRoutingPolicy.next(true, phoneOnly, current = BLOCKED, ownedOriginal = 2))
    }

    @Test
    fun glassesPluggedInWhileBlockedNeedAReplugAfterTheRestore() {
        // Earbuds gone, 0 put back, the glasses attached meanwhile never became an output.
        assertTrue(AudioRoutingPolicy.needsReplug(0, phoneOnly, usbPlaybackAttached = true))
    }

    @Test
    fun noReplugWhenTheGlassesPlayOrNothingIsAttached() {
        // The glasses came before the block, so they are an output already.
        assertFalse(AudioRoutingPolicy.needsReplug(0, withGlasses, usbPlaybackAttached = true))
        assertFalse(AudioRoutingPolicy.needsReplug(0, phoneOnly + AudioDeviceInfo.TYPE_USB_DEVICE, usbPlaybackAttached = true))
        assertFalse(AudioRoutingPolicy.needsReplug(0, phoneOnly, usbPlaybackAttached = false))
        // An unusual original still keeps USB audio from attaching: a replug would not help.
        assertFalse(AudioRoutingPolicy.needsReplug(2, phoneOnly, usbPlaybackAttached = true))
        assertFalse(AudioRoutingPolicy.needsReplug(BLOCKED, phoneOnly, usbPlaybackAttached = true))
    }

    @Test
    fun typeNamesForTheLog() {
        assertEquals("bt_a2dp", AudioRoutingPolicy.typeName(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("usb_headset", AudioRoutingPolicy.typeName(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("type999", AudioRoutingPolicy.typeName(999))
    }
}
