package com.raphael.handmouse.audio

import android.media.AudioDeviceInfo

/**
 * Decides the secure setting [SETTING] for [AudioRoutingGuard] (spec 2026-11-17, section J item 5).
 * Pure, so it is tested on the JVM (the `AudioDeviceInfo.TYPE_*` values are compile-time constants).
 *
 * Android reads the setting when a USB audio device attaches: at [BLOCKED] the glasses
 * ("USB-Audio - XREAL One Pro", usb_headset) do not become an audio output, so the sound stays on
 * the earphones. The guard holds [BLOCKED] while its preference is on and an output other than the
 * phone's own and other than USB is connected, and puts back the value it replaced once that ends.
 * It only ever puts back a value it wrote itself: a [BLOCKED] someone else set (the developer
 * option) stays, and a value someone else changed while the guard held it becomes theirs.
 */
internal object AudioRoutingPolicy {
    /** `Settings.Secure.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED` (hidden in the SDK). */
    const val SETTING = "usb_audio_automatic_routing_disabled"

    /** The setting's value that keeps a newly attached USB audio device from taking the sound. */
    const val BLOCKED = 1

    /**
     * Outputs that are neither the phone's own nor USB: Bluetooth (A2DP, LE audio headset / speaker /
     * broadcast, hearing aid), wired headset / headphones and line out. Left out on purpose: SCO
     * (an HFP-only device plays no media), HDMI and docks (the glasses' own display link may show up
     * as one), and every phone-internal type.
     */
    val EXTERNAL_NON_USB_OUTPUTS: Set<Int> = setOf(
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

    /**
     * One decision. [write]: the value to put into the setting, or null to leave it alone.
     * [ownedOriginal]: the guard's bookkeeping afterwards (once [write] succeeded), the value it
     * replaced while the setting holds a [BLOCKED] the guard wrote, or null when it holds nothing
     * of the guard's.
     */
    data class Step(val write: Int?, val ownedOriginal: Int?)

    /** What an attached USB audio device becomes once Android routes to it. */
    val USB_OUTPUTS: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    fun hasExternalNonUsbOutput(types: Collection<Int>): Boolean = types.any { it in EXTERNAL_NON_USB_OUTPUTS }

    /**
     * Whether a USB playback device stays silent after the guard put [restored] back. Android reads
     * the setting only when a USB audio device attaches (UsbAlsaManager: "Does not yet handle the
     * case where the setting is changed after device connection"), so glasses plugged in while it
     * held [BLOCKED] become an output only after a replug, also once the setting is 0 again.
     *
     * @param restored the value just written back (0 lets the next attach take the sound)
     * @param outputTypes the `AudioDeviceInfo.type` of every connected output
     * @param usbPlaybackAttached a USB device with an audio playback interface is attached
     */
    fun needsReplug(restored: Int, outputTypes: Collection<Int>, usbPlaybackAttached: Boolean): Boolean =
        restored == 0 && usbPlaybackAttached && outputTypes.none { it in USB_OUTPUTS }

    /**
     * @param enabled the guard's preference
     * @param outputTypes the `AudioDeviceInfo.type` of every connected output
     * @param current the setting's value now
     * @param ownedOriginal the bookkeeping so far (see [Step.ownedOriginal])
     */
    fun next(enabled: Boolean, outputTypes: Collection<Int>, current: Int, ownedOriginal: Int?): Step {
        val block = enabled && hasExternalNonUsbOutput(outputTypes)
        return when {
            // Already blocked, by the guard or by someone else (then it is not the guard's to restore).
            block && current == BLOCKED -> Step(null, ownedOriginal)
            // The value before the guard's first write is the one to put back, also when someone
            // reset the setting while the guard held it.
            block -> Step(BLOCKED, ownedOriginal ?: current)
            ownedOriginal == null -> Step(null, null)
            current == BLOCKED -> Step(ownedOriginal, null)
            // Someone else changed it while the guard held it: theirs now, left as it is.
            else -> Step(null, null)
        }
    }

    /** A short name for an output type, for the log. */
    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker_safe"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
        else -> "type$type"
    }
}
