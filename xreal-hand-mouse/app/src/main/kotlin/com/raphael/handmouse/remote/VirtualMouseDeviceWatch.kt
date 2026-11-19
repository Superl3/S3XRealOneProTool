package com.raphael.handmouse.remote

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Follows the input device of the shell service's virtual mouse ([VirtualMouseProcess.DEVICE_NAME])
 * through [InputManager], so the Remote drives the system cursor only while that device exists.
 * The shell's open answers before `/system/bin/uinput` (an app_process JVM) has even read the
 * register line, uinput may then fail to create the device, and it may die later; the events are
 * oneway, so none of that reaches the app any other way.
 *
 * [onChange] is called on the main thread when [present] flips. Main thread only.
 */
class VirtualMouseDeviceWatch(
    context: Context,
    private val onChange: (present: Boolean) -> Unit,
) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Ids of the input devices that are the virtual mouse (normally one). */
    private val ids = HashSet<Int>()
    private var started = false

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = check(deviceId)

        override fun onInputDeviceChanged(deviceId: Int) = check(deviceId)

        override fun onInputDeviceRemoved(deviceId: Int) {
            update { ids.remove(deviceId) }
        }
    }

    /** True while an input device of the virtual mouse exists (only known between [start] and [stop]). */
    val present: Boolean
        get() = ids.isNotEmpty()

    /** The virtual mouse's input device ids now (a copy); empty when not followed. */
    val deviceIds: Set<Int>
        get() = ids.toSet()

    /** Starts following the devices (and looks at the current ones). Idempotent. */
    fun start() {
        if (started) return
        val im = inputManager ?: return
        started = true
        runCatching { im.registerInputDeviceListener(listener, handler) }
            .onFailure { Log.w(TAG, "input device listener not registered", it) }
        runCatching { im.inputDeviceIds }.getOrNull()?.forEach(::check)
    }

    /** Stops following and forgets the devices seen. Idempotent; [onChange] is not called. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { inputManager?.unregisterInputDeviceListener(listener) }
        ids.clear()
    }

    private fun check(deviceId: Int) {
        val name = runCatching { inputManager?.getInputDevice(deviceId)?.name }.getOrNull()
        update {
            if (VirtualMouseProcess.isDeviceName(name)) ids.add(deviceId) else ids.remove(deviceId)
        }
    }

    private fun update(change: () -> Unit) {
        if (!started) return
        val before = present
        change()
        if (present != before) {
            Log.i(TAG, if (present) "virtual mouse device present" else "virtual mouse device gone")
            onChange(present)
        }
    }

    private companion object {
        const val TAG = "VirtualMouseDevice"
    }
}
