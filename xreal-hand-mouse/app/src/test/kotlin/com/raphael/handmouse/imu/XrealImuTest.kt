package com.raphael.handmouse.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealImuTest {

    private fun frame(type: Int, timeNs: Long, gyro: FloatArray, accel: FloatArray = floatArrayOf(0f, 9.8f, 0f)): ByteArray {
        val body = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        body.putLong(0x08, timeNs)
        body.putInt(0x18, type)
        body.putFloat(0x1C, gyro[0]); body.putFloat(0x20, gyro[1]); body.putFloat(0x24, gyro[2])
        body.putFloat(0x28, accel[0]); body.putFloat(0x2C, accel[1]); body.putFloat(0x30, accel[2])
        body.putFloat(0x40, 31.5f)
        val head = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).put(0x28).put(0x36).putInt(128)
        return head.array() + body.array()
    }

    @Test
    fun parsesImuFramesSplitAcrossReadsAndSkipsMagnetometer() {
        val stream = byteArrayOf(1, 2, 0x28) + // junk, including a lone magic byte
            frame(0x0B, 1_000_000L, floatArrayOf(0.1f, -0.2f, 0.3f)) +
            frame(0x04, 1_500_000L, floatArrayOf(Float.NaN, Float.NaN, Float.NaN)) +
            frame(0x0B, 2_000_000L, floatArrayOf(0.4f, 0.5f, 0.6f))
        val p = XrealImuParser()
        val out = ArrayList<ImuSample>()
        var i = 0
        while (i < stream.size) { // 7-byte reads split every header and body
            val n = minOf(7, stream.size - i)
            out += p.feed(stream.copyOfRange(i, i + n), n)
            i += n
        }
        assertEquals(2, out.size)
        assertEquals(1_000_000L, out[0].deviceTimeNs)
        assertEquals(-0.2f, out[0].gy, 0f)
        assertEquals(9.8f, out[0].ay, 0f)
        assertEquals(31.5f, out[0].temperatureC, 0f)
        assertEquals(0.6f, out[1].gz, 0f)
    }

    @Test
    fun rejectsAMagicWithTheWrongLength() {
        val bad = byteArrayOf(0x28, 0x36, 0, 0, 0, 5) + ByteArray(10)
        val good = frame(0x0B, 5L, floatArrayOf(1f, 2f, 3f))
        val out = XrealImuParser().feed(bad + good)
        assertEquals(1, out.size)
        assertEquals(5L, out[0].deviceTimeNs)
    }

    @Test
    fun clockKeepsTheSmallestDelayAndFollowsAJump() {
        val c = ImuClock()
        // true offset 1 ms; transport delays 3 ms, 1 ms, 5 ms
        assertEquals(14_000_000L, c.toLocal(10_000_000L, 10_000_000L + 1_000_000L + 3_000_000L))
        val second = c.toLocal(11_000_000L, 11_000_000L + 1_000_000L + 1_000_000L)
        assertEquals(11_000_000L + 2_000_000L, second) // 1 ms delay is the new minimum
        val third = c.toLocal(12_000_000L, 12_000_000L + 1_000_000L + 5_000_000L)
        assertEquals(12_000_000L + 2_000_000L + ImuClock.CREEP_NS_PER_SAMPLE, third) // keeps the minimum, creeps
        // device clock restarts (glasses reboot): offset jumps by far more than a second
        val after = c.toLocal(1_000L, 50_000_000_000L)
        assertEquals(50_000_000_000L, after)
    }

    @Test
    fun aVpnRefusalSaysToExcludeTheApp() {
        // the S25 Edge's message under AdGuard, 2026-09-29
        val vpn = XrealImuClient.failureMessage("Binding socket to network 156 failed: EPERM (Operation not permitted)")
        assertTrue(vpn, "VPN" in vpn)
        assertEquals("Glasses IMU: connect timed out", XrealImuClient.failureMessage("connect timed out"))
    }

    @Test
    fun gyroHistoryIntegratesOnlyCoveredIntervals() {
        val h = GyroHistory(capacity = 16)
        for (k in 0..10) h.add(k * 1_000_000L, 1f, -2f, 0.5f) // 1 ms apart, 0..10 ms
        val r = h.integrate(2_000_000L, 7_500_000L)!!
        assertEquals(0.0055f, r[0], 1e-6f)
        assertEquals(-0.011f, r[1], 1e-6f)
        assertEquals(0.00275f, r[2], 1e-6f)
        assertNull(h.integrate(-1L, 3_000_000L)) // before the oldest sample
        assertNull(h.integrate(5_000_000L, 11_000_000L)) // after the newest
        for (k in 11..30) h.add(k * 1_000_000L, 0f, 0f, 0f) // ring wraps: 0..14 ms are gone
        assertNull(h.integrate(2_000_000L, 20_000_000L))
        assertTrue(h.integrate(20_000_000L, 25_000_000L)!!.all { it == 0f })
    }
}
