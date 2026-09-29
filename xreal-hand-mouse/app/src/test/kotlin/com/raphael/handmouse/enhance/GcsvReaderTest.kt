package com.raphael.handmouse.enhance

import com.raphael.handmouse.recording.GyroLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GcsvReaderTest {

    private fun read(text: String) = GcsvReader.read(ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII)))

    @Test
    fun readsGyroLogOutputBackAveragedTo500Hz() {
        val bytes = ByteArrayOutputStream()
        val log = GyroLog(bytes, "XrealEye_20260929_120000_001.mkv", 1_790_000_000L)
        val baseNs = 5_000_000_000L
        log.add(baseNs, 9f, 9f, 9f, 0f, 0f, 0f) // no base yet: dropped
        log.setBase(baseNs / 1000)
        log.setBase(baseNs / 1000 + 777) // only the first call counts
        log.add(baseNs - 1_000_000L, 9f, 9f, 9f, 0f, 0f, 0f) // before the first frame: dropped
        for (k in 0 until 10) log.add(baseNs + k * 1_000_000L, 0.5f, -0.25f, 0.125f * k, 0f, 9.80665f, 0f)
        assertTrue(log.close())
        assertEquals(5L, log.lines)

        val text = bytes.toString(Charsets.US_ASCII.name())
        assertTrue(text.startsWith("GYROFLOW IMU LOG\nversion,1.3\n"))
        assertTrue(text.contains("\ngscale,0.000010000000\n"))

        val series = read(text)!!
        assertArrayEquals(longArrayOf(500, 2500, 4500, 6500, 8500), series.tUs)
        for (j in 0 until 5) {
            assertEquals(0.5f, series.g[j * 3], 1e-5f)
            assertEquals(-0.25f, series.g[j * 3 + 1], 1e-5f)
            // pairs (0,1), (2,3), …: z = 0.125 · mean(2j, 2j+1)
            assertEquals(0.125f * (2 * j + 0.5f), series.g[j * 3 + 2], 1e-5f)
        }
    }

    @Test
    fun readsDecimalGyroflowLogsWithDefaultMillisecondTimescale() {
        val series = read(
            """
            GYROFLOW IMU LOG
            version,1.3
            gscale,2
            t,gx,gy,gz,ax,ay,az
            0,0.1,0.2,0.3,0,0,1
            1.5,1e-1,-0.2,+0.3,0,0,1
            1.5,9,9,9,0,0,1
            garbage
            3,0.1,0.2,0.3
            """.trimIndent(),
        )!!
        // tscale missing: t in ms; the repeated t and the unparsable row are skipped
        assertArrayEquals(longArrayOf(0, 1500, 3000), series.tUs)
        assertEquals(0.2f, series.g[3], 1e-6f)
        assertEquals(-0.4f, series.g[4], 1e-6f)
        assertEquals(0.6f, series.g[5], 1e-6f)
    }

    @Test
    fun rejectsLogsWithoutGyroColumnsOrSamples() {
        assertNull(read("GYROFLOW IMU LOG\nt,gx,gy,ax\n0,1,2,3\n1,1,2,3\n"))
        assertNull(read("GYROFLOW IMU LOG\nversion,1.3\n"))
        assertNull(read("GYROFLOW IMU LOG\nt,gx,gy,gz\n0,1,2,3\n")) // one sample cannot be integrated
    }
}
