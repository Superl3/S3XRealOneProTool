package com.raphael.handmouse.imu

/**
 * The last [capacity] gyro samples on the local clock (1000 Hz → 2 s by default), for
 * integrating the head rotation between two camera frames. Written by the IMU thread, read by the
 * tracker thread — every method is synchronized (the critical sections are a few array reads).
 */
class GyroHistory(private val capacity: Int = 2048) {
    private val t = LongArray(capacity)
    private val g = FloatArray(capacity * 3)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(localNs: Long, gx: Float, gy: Float, gz: Float) {
        if (size > 0 && localNs <= t[(head - 1 + capacity) % capacity]) return // keep time increasing
        t[head] = localNs
        g[head * 3] = gx
        g[head * 3 + 1] = gy
        g[head * 3 + 2] = gz
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun clear() {
        size = 0
    }

    /** Newest sample time, or null when empty. */
    @Synchronized
    fun latestNs(): Long? = if (size == 0) null else t[(head - 1 + capacity) % capacity]

    /**
     * Rotation (rad per axis) accumulated over [fromNs, toNs]: each sample's rate held until the
     * next sample (the samples are 1 ms apart, so the hold error is negligible). Null when the
     * history does not cover the whole interval — no rotation is made up for a gap.
     */
    @Synchronized
    fun integrate(fromNs: Long, toNs: Long): FloatArray? {
        if (size < 2 || toNs <= fromNs) return null
        val oldest = (head - size + capacity) % capacity
        if (t[oldest] > fromNs || t[(head - 1 + capacity) % capacity] < toNs) return null
        val out = FloatArray(3)
        for (k in 0 until size - 1) {
            val i = (oldest + k) % capacity
            val j = (i + 1) % capacity
            val a = maxOf(t[i], fromNs)
            val b = minOf(t[j], toNs)
            if (b <= a) continue
            val dt = (b - a) / 1e9f
            out[0] += g[i * 3] * dt
            out[1] += g[i * 3 + 1] * dt
            out[2] += g[i * 3 + 2] * dt
        }
        return out
    }
}
