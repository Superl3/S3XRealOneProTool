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
    fun toLocal(deviceTimeNs: Long, receivedLocalNs: Long): Long {
        val delta = receivedLocalNs - deviceTimeNs
        val cur = offsetNs
        val next = when {
            cur == null || delta < cur || delta - cur > RESET_JUMP_NS -> delta
            else -> cur + CREEP_NS_PER_SAMPLE
        }
        offsetNs = next
        return deviceTimeNs + next
    }

    fun reset() {
        offsetNs = null
    }
}
