package com.raphael.handmouse.imu

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One IMU report from the glasses. Gyro in rad/s, accel in m/s², [deviceTimeNs] on the
 * glasses' own clock (see [ImuClock]). */
class ImuSample(
    val deviceTimeNs: Long,
    val gx: Float, val gy: Float, val gz: Float,
    val ax: Float, val ay: Float, val az: Float,
    val temperatureC: Float,
)

/**
 * Framer and decoder for the XREAL One IMU stream (TCP 169.254.2.1:52998, 2026-09-29 — layout
 * and resync rule from `Skarian/one-xr` `StreamFramer` and `0xcaff/xr-tools` `reports.rs`; see
 * docs/superpowers/specs/2026-09-29-xreal-imu-protocol.md).
 *
 * Frame: magic `28 36` (one-xr also accepts `27 36`), u32 big-endian body length, then a
 * 128-byte little-endian body. A header whose length is not 128 is a false match: drop one byte
 * and scan again. Magnetometer reports (type 0x04, NaN gyro/accel) arrive on the same socket
 * and are skipped.
 */
class XrealImuParser(private val maxBufferBytes: Int = 131_072) {

    companion object {
        const val HEADER_BYTES = 6
        const val BODY_BYTES = 128
        const val REPORT_IMU = 0x0B
    }

    private var buf = ByteArray(8192)
    private var len = 0

    /** Frames that were not IMU reports or failed the length check (diagnostics). */
    var skippedBytes = 0L
        private set

    fun feed(data: ByteArray, length: Int = data.size): List<ImuSample> {
        if (len + length > buf.size) {
            if (len + length > maxBufferBytes) {
                // Never resynchronized within the cap: the stream is not what we expect.
                skippedBytes += len
                len = 0
            }
            if (len + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + length))
        }
        System.arraycopy(data, 0, buf, len, length)
        len += length

        val out = ArrayList<ImuSample>()
        var pos = 0
        while (len - pos >= HEADER_BYTES) {
            val b0 = buf[pos].toInt() and 0xFF
            val b1 = buf[pos + 1].toInt() and 0xFF
            if ((b0 != 0x28 && b0 != 0x27) || b1 != 0x36) {
                pos++
                skippedBytes++
                continue
            }
            val bodyLen = ByteBuffer.wrap(buf, pos + 2, 4).order(ByteOrder.BIG_ENDIAN).int
            if (bodyLen != BODY_BYTES) {
                pos++
                skippedBytes++
                continue
            }
            if (len - pos < HEADER_BYTES + BODY_BYTES) break
            decode(buf, pos + HEADER_BYTES)?.let { out.add(it) }
            pos += HEADER_BYTES + BODY_BYTES
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
        return out
    }

    fun reset() {
        len = 0
    }

    private fun decode(b: ByteArray, off: Int): ImuSample? {
        val bb = ByteBuffer.wrap(b, off, BODY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(off + 0x18) != REPORT_IMU) return null
        val s = ImuSample(
            deviceTimeNs = bb.getLong(off + 0x08),
            gx = bb.getFloat(off + 0x1C), gy = bb.getFloat(off + 0x20), gz = bb.getFloat(off + 0x24),
            ax = bb.getFloat(off + 0x28), ay = bb.getFloat(off + 0x2C), az = bb.getFloat(off + 0x30),
            temperatureC = bb.getFloat(off + 0x40),
        )
        if (s.gx.isNaN() || s.gy.isNaN() || s.gz.isNaN()) return null
        return s
    }
}
