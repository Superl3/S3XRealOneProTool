package com.raphael.handmouse.imu

/**
 * Maps the glasses' IMU clock onto this phone's CLOCK_MONOTONIC (`System.nanoTime()`, which is
 * also what `SystemClock.uptimeMillis()` counts), the clock of the camera frames and recordings.
 *
 * The IMU timestamp is taken on the glasses and arrives over TCP some variable time later, so
 * `receive − device` = true offset + transport delay ≥ true offset. The smallest value seen is
 * the best estimate. It creeps up by [CREEP_NS_PER_SAMPLE] per sample so a clock drift or a
 * glasses reboot (device time jumps back) is followed instead of being pinned to an old minimum.
 */
class ImuClock {
    companion object {
        /** 1000 Hz × 100 ns = 0.1 ms/s: above crystal drift (tens of ppm), well below the delays. */
        const val CREEP_NS_PER_SAMPLE = 100L

        /** A delta this far above the estimate means the device clock jumped: start over. */
        const val RESET_JUMP_NS = 1_000_000_000L
    }

    private var offsetNs: Long? = null

    /** Feeds one sample's timestamps and returns its time on the local clock. */
    fun toLocal(deviceTimeNs: Long, receivedLocalNs: Long): Long =
        toLocal(longArrayOf(deviceTimeNs), receivedLocalNs)[0]

    /**
     * Feeds the samples of one socket read (oldest first, all received at [receivedLocalNs]) and
     * returns each one's time on the local clock. Only the newest sample, which has waited the
     * least, updates the estimate; the others sit before it at their device spacing. Fed one by
     * one, a read that starts or restarts the estimate gave every sample the arrival time.
     */
    fun toLocal(deviceTimesNs: LongArray, receivedLocalNs: Long): LongArray {
        if (deviceTimesNs.isEmpty()) return deviceTimesNs
        val newest = deviceTimesNs.last()
        val delta = receivedLocalNs - newest
        val cur = offsetNs
        val next = when {
            cur == null || delta < cur || delta - cur > RESET_JUMP_NS -> delta
            else -> cur + CREEP_NS_PER_SAMPLE * deviceTimesNs.size
        }
        offsetNs = next
        return LongArray(deviceTimesNs.size) { deviceTimesNs[it] + next }
    }

    fun reset() {
        offsetNs = null
    }
}
