package com.raphael.handmouse.capture

import android.os.SystemClock

/** Prevents concurrent USB permission dialogs for the same enumeration of a device. */
object UsbPermissionRequests {
    private const val STALE_AFTER_MS = 20_000L
    private val pending = mutableMapOf<String, Long>()

    @Synchronized
    fun begin(deviceName: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = pending[deviceName]
        if (previous != null && now - previous < STALE_AFTER_MS) return false
        pending[deviceName] = now
        return true
    }

    @Synchronized
    fun finish(deviceName: String?) {
        if (deviceName != null) pending.remove(deviceName)
    }
}
