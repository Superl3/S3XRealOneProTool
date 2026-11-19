package com.raphael.handmouse.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.raphael.handmouse.R
import com.raphael.handmouse.remote.RemoteShellBridge
import com.raphael.handmouse.util.KEY_BLOCK_USB_AUDIO
import com.raphael.handmouse.util.Prefs
import rikka.shizuku.Shizuku

/**
 * Keeps the sound on earphones when the glasses are plugged in (spec 2026-11-17, section J item 5).
 * While its preference ([Prefs.blockUsbAudioWhenExternal]) is on and an output other than the
 * phone's own and other than USB is connected (Bluetooth, wired, line: [AudioRoutingPolicy]), it
 * holds the secure setting [AudioRoutingPolicy.SETTING] at 1. Android reads that setting when a USB
 * audio device attaches, so the glasses ("USB-Audio - XREAL One Pro") then do not take the sound.
 * Owned by the accessibility service: [start] in onServiceConnected, [stop] in onDestroy.
 *
 * Putting the value back does not give the sound to glasses plugged in while it was 1: Android
 * reads the setting only when a USB audio device attaches. So a restore that leaves an attached
 * USB playback device without an output tells the user to replug it (a toast,
 * [AudioRoutingPolicy.needsReplug]).
 *
 * ## Restore bookkeeping
 * The guard only ever puts back a value it wrote. While the setting holds a 1 the guard wrote, the
 * value that 1 replaced is kept twice: in a private preferences file ([KEY_OWNED_ORIGINAL]) and in
 * secure settings beside the setting ([SECURE_OWNED_ORIGINAL]). The secure copy outlives the app's
 * data, so clearing it or reinstalling the app does not turn the guard's 1 into one it takes for
 * the developer option's and leaves in place for good. The private copy covers a provider that
 * refuses to read the secure one (a hidden key may need the permission to be read).
 * - Blocking: the record is saved (synchronously) before the 1 is written, and taken back if the
 *   write fails. A crash in between leaves the record with the setting not at 1, which the next
 *   pass clears, never a 1 nobody would put back.
 * - Restoring (preference off, no such output any more, or [stop]): the remembered value is
 *   written back only while the setting is still 1, then the record is removed. Someone who
 *   changed the setting meanwhile owns it: the record is dropped and nothing is written.
 * - A 1 the guard finds already there without a record (the developer option) is never touched.
 * - The record survives the process, so [start] puts back a 1 a previous run left (killed before it
 *   could restore) once no such output is connected.
 * - Nothing runs on an uninstall: a 1 in place then stays until the app is installed again and
 *   puts it back, so the preference's summary asks to turn it off first.
 *
 * ## Permission
 * Writing needs WRITE_SECURE_SETTINGS. Without it the guard grants it once through Shizuku
 * (`pm grant`), in a shell service of its own ([SHELL_SERVICE_NAME]) so the grant never shuts down
 * the Remote's (its virtual mouse). It asks for Shizuku's own permission at most once (again only
 * after the preference is turned back on); a failure is logged once and retried only when Shizuku
 * starts, its permission is granted, the outputs change or the preference does.
 *
 * Main thread only: [start], [stop] and every callback run there.
 */
class AudioRoutingGuard(context: Context) {

    companion object {
        private const val TAG = "AudioRoutingGuard"
        private const val STATE_FILE = "audio_routing_guard"

        /** Present while the setting holds a 1 this guard wrote: the value that 1 replaced. */
        private const val KEY_OWNED_ORIGINAL = "owned_original"

        /**
         * [KEY_OWNED_ORIGINAL]'s copy in secure settings, which outlives the app's data. A value
         * that is not a number (empty) counts as absent.
         */
        private const val SECURE_OWNED_ORIGINAL = "com.raphael.handmouse.usb_audio_owned_original"

        /** USB audio class subclass of a streaming interface (no constant in [UsbConstants]). */
        private const val USB_SUBCLASS_AUDIOSTREAMING = 2

        /** The guard asked for the Shizuku permission; cleared when the preference is turned on. */
        private const val KEY_SHIZUKU_PROMPTED = "shizuku_prompted"

        /** The grant's own Shizuku user service ([RemoteShellBridge] serviceName). */
        private const val SHELL_SERVICE_NAME = "audio_guard"

        /** Longer than the bridge waits for an answer to the Shizuku prompt (30 s). */
        private const val GRANT_TIMEOUT_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)
    private val prefs = Prefs(appContext)
    private val state: SharedPreferences = appContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var grantBridge: RemoteShellBridge? = null
    private var grantFailureLogged = false
    private var readFailureLogged = false
    private var ownedReadFailureLogged = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            onDevicesChanged("added", addedDevices)

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            onDevicesChanged("removed", removedDevices)
    }

    // Registered on the shared preferences file, which holds it weakly: kept in this field.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key != KEY_BLOCK_USB_AUDIO) return@OnSharedPreferenceChangeListener
        val enabled = prefs.blockUsbAudioWhenExternal
        if (enabled) {
            // Turning it on is the user's go-ahead for one more Shizuku prompt (and failure log).
            state.edit().remove(KEY_SHIZUKU_PROMPTED).apply()
            grantFailureLogged = false
        }
        evaluate("preference ${if (enabled) "on" else "off"}", mayGrant = true)
    }

    private val shizukuStarted = Shizuku.OnBinderReceivedListener {
        evaluate("Shizuku started", mayGrant = true)
    }

    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) evaluate("Shizuku permission granted", mayGrant = true)
    }

    private val grantTimeout = Runnable {
        grantBridge?.let { onGrantResult(it, RemoteShellBridge.COMMAND_NOT_RUN, "no answer in $GRANT_TIMEOUT_MS ms") }
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
        Shizuku.addBinderReceivedListener(shizukuStarted, mainHandler)
        Shizuku.addRequestPermissionResultListener(shizukuPermission, mainHandler)
        // The outputs connected now; also puts back a 1 a previous run left behind.
        evaluate("start", mayGrant = true)
    }

    fun stop() {
        if (!started) return
        // Nobody would put the value back once the earphones go: do it now. The next start blocks
        // again if it still has to.
        evaluate("stop", mayGrant = false, enabled = false)
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        Shizuku.removeBinderReceivedListener(shizukuStarted)
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        endGrant()
    }

    private fun onDevicesChanged(change: String, devices: Array<out AudioDeviceInfo>?) {
        // Microphones come and go with the same devices; only outputs matter here.
        if (devices.orEmpty().none { it.isSink }) return
        evaluate("$change ${describe(devices)}", mayGrant = true)
    }

    /**
     * Brings the setting where [AudioRoutingPolicy] wants it. [mayGrant]: this pass may start a
     * Shizuku grant (every trigger may, the pass after a grant may not, so it cannot loop).
     */
    private fun evaluate(reason: String, mayGrant: Boolean, enabled: Boolean = prefs.blockUsbAudioWhenExternal) {
        if (!started) return
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = outputs.map { it.type }
        val current = readSetting()
        if (current == null) {
            // Reading a hidden setting may take the permission too: get it, and the grant tries again.
            if (enabled && mayGrant && !hasWritePermission()) {
                requestGrant(mayPrompt = AudioRoutingPolicy.hasExternalNonUsbOutput(types))
            }
            return
        }
        val owned = try {
            ownedOriginal()
        } catch (e: SecurityException) {
            if (!ownedReadFailureLogged) {
                ownedReadFailureLogged = true
                Log.w(TAG, "cannot read $SECURE_OWNED_ORIGINAL; only the private record counts", e)
            }
            val local = localOwnedOriginal()
            if (local == null && current == AudioRoutingPolicy.BLOCKED && !hasWritePermission()) {
                // A 1 with no record readable here may be one an earlier install wrote (data
                // cleared, or reinstalled): the permission may make the secure copy readable, and
                // the grant runs this pass again. Nothing would be written meanwhile anyway.
                if (mayGrant) requestGrant(mayPrompt = true)
                return
            }
            local
        }
        val step = AudioRoutingPolicy.next(enabled, types, current, owned)
        val value = step.write
        if (value == null) {
            if (step.ownedOriginal != owned) {
                saveOwned(step.ownedOriginal)
                Log.i(TAG, "${AudioRoutingPolicy.SETTING} changed by someone else to $current: left to them ($reason)")
            }
        } else if (hasWritePermission()) {
            write(value, current, owned, step.ownedOriginal, reason, outputs)
            return
        }
        when {
            // A write is due: may ask for Shizuku's permission (once).
            value != null && mayGrant -> requestGrant(mayPrompt = true)
            value != null -> Log.w(TAG, "${AudioRoutingPolicy.SETTING}=$value due but WRITE_SECURE_SETTINGS is missing ($reason)")
            // Ahead of need, only when that asks nothing of the user: a later earphone connection
            // then does not depend on Shizuku running at that moment.
            enabled && mayGrant && !hasWritePermission() -> requestGrant(mayPrompt = false)
        }
    }

    private fun write(value: Int, current: Int, ownedBefore: Int?, ownedAfter: Int?, reason: String, outputs: Array<AudioDeviceInfo>) {
        // A 1 is remembered before it is written (see the class comment).
        if (ownedAfter != null) saveOwned(ownedAfter)
        val written = try {
            Settings.Secure.putInt(appContext.contentResolver, AudioRoutingPolicy.SETTING, value)
        } catch (e: SecurityException) {
            Log.w(TAG, "writing ${AudioRoutingPolicy.SETTING} refused", e)
            false
        }
        if (written) {
            saveOwned(ownedAfter)
            Log.i(TAG, "${AudioRoutingPolicy.SETTING} $current -> $value ($reason); outputs: ${describe(outputs)}")
            if (AudioRoutingPolicy.needsReplug(value, outputs.map { it.type }, usbPlaybackAttached())) {
                // Glasses plugged in while it was 1 stay silent, and the sound is on the phone now.
                Log.i(TAG, "USB audio attached while blocked has no output until replugged: telling the user")
                Toast.makeText(appContext, R.string.audio_guard_replug_glasses, Toast.LENGTH_LONG).show()
            }
        } else {
            saveOwned(ownedBefore)
            Log.w(TAG, "${AudioRoutingPolicy.SETTING} $current -> $value failed ($reason)")
        }
    }

    private fun readSetting(): Int? = try {
        Settings.Secure.getInt(appContext.contentResolver, AudioRoutingPolicy.SETTING, 0)
    } catch (e: SecurityException) {
        if (!readFailureLogged) {
            readFailureLogged = true
            Log.w(TAG, "cannot read ${AudioRoutingPolicy.SETTING}", e)
        }
        null
    }

    /**
     * The value the guard's 1 replaced, or null: the secure copy, else the private one (see the
     * class comment). Throws [SecurityException] when the provider refuses to read the secure copy.
     */
    private fun ownedOriginal(): Int? =
        Settings.Secure.getString(appContext.contentResolver, SECURE_OWNED_ORIGINAL)?.toIntOrNull()
            ?: localOwnedOriginal()

    private fun localOwnedOriginal(): Int? =
        if (state.contains(KEY_OWNED_ORIGINAL)) state.getInt(KEY_OWNED_ORIGINAL, 0) else null

    /**
     * Saves or removes both copies of the record. Synchronous: the bookkeeping has to be on disk
     * around the setting's write, not after it.
     */
    @SuppressLint("ApplySharedPref")
    private fun saveOwned(original: Int?) {
        val editor = state.edit()
        if (original == null) editor.remove(KEY_OWNED_ORIGINAL) else editor.putInt(KEY_OWNED_ORIGINAL, original)
        editor.commit()
        val resolver = appContext.contentResolver
        try {
            if (original == null) {
                // Emptied first, which counts as absent even if the provider will not delete the key.
                Settings.Secure.putString(resolver, SECURE_OWNED_ORIGINAL, "")
                resolver.delete(Settings.Secure.getUriFor(SECURE_OWNED_ORIGINAL), null, null)
            } else {
                Settings.Secure.putString(resolver, SECURE_OWNED_ORIGINAL, original.toString())
            }
        } catch (e: RuntimeException) {
            // SecurityException without the permission, or a provider that refuses the key: the
            // private copy still holds the record for as long as the app's data lasts.
            Log.w(TAG, "cannot ${if (original == null) "remove" else "save"} $SECURE_OWNED_ORIGINAL", e)
        }
    }

    /**
     * A USB device with an audio playback interface (audio streaming with an OUT endpoint) is
     * attached: the glasses' "USB-Audio", or any USB DAC.
     */
    private fun usbPlaybackAttached(): Boolean {
        val usb = appContext.getSystemService(UsbManager::class.java) ?: return false
        val devices = runCatching { usb.deviceList.values.toList() }.getOrDefault(emptyList())
        return devices.any { device ->
            (0 until device.interfaceCount).any { i ->
                val intf = device.getInterface(i)
                intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    intf.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING &&
                    (0 until intf.endpointCount).any { intf.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT }
            }
        }
    }

    private fun hasWritePermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Grants WRITE_SECURE_SETTINGS through Shizuku, one attempt at a time. [mayPrompt]: it may ask
     * for Shizuku's own permission, if it never asked before.
     */
    private fun requestGrant(mayPrompt: Boolean) {
        if (grantBridge != null) return
        if (!Shizuku.pingBinder()) {
            logGrantFailureOnce("Shizuku is not running")
            return
        }
        val permitted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        if (!permitted) {
            if (!mayPrompt) return
            if (state.getBoolean(KEY_SHIZUKU_PROMPTED, false)) {
                logGrantFailureOnce("no Shizuku permission, and it was asked for once already")
                return
            }
            state.edit().putBoolean(KEY_SHIZUKU_PROMPTED, true).apply()
        }
        val bridge = RemoteShellBridge(appContext, serviceName = SHELL_SERVICE_NAME)
        grantBridge = bridge
        Log.i(TAG, "granting WRITE_SECURE_SETTINGS through Shizuku")
        mainHandler.postDelayed(grantTimeout, GRANT_TIMEOUT_MS)
        val argv = arrayOf("/system/bin/pm", "grant", appContext.packageName, Manifest.permission.WRITE_SECURE_SETTINGS)
        bridge.runCommand(argv) { code, output -> onGrantResult(bridge, code, output) }
    }

    private fun onGrantResult(bridge: RemoteShellBridge, code: Int, output: String) {
        if (bridge !== grantBridge) return
        endGrant()
        if (hasWritePermission()) {
            grantFailureLogged = false
            Log.i(TAG, "WRITE_SECURE_SETTINGS granted")
            evaluate("permission granted", mayGrant = false)
        } else {
            logGrantFailureOnce("pm grant failed ($code): ${output.trim()}")
        }
    }

    private fun endGrant() {
        mainHandler.removeCallbacks(grantTimeout)
        grantBridge?.destroy()
        grantBridge = null
    }

    private fun logGrantFailureOnce(message: String) {
        if (grantFailureLogged) return
        grantFailureLogged = true
        Log.w(TAG, "cannot get WRITE_SECURE_SETTINGS: $message")
    }

    private fun describe(devices: Array<out AudioDeviceInfo>?): String =
        devices.orEmpty().filter { it.isSink }.joinToString(prefix = "[", postfix = "]") {
            "${AudioRoutingPolicy.typeName(it.type)} '${it.productName}'"
        }
}
