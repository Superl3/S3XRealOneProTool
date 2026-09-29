package com.raphael.handmouse.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcDiagnosticsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val config: ByteArray =
        // VideoControl interface
        bytes(0x09, 0x04, 0x00, 0x00, 0x01, 0x0E, 0x01, 0x00, 0x00) +
            // Camera terminal id=1, bControlSize=3, controls = AE mode (bit1) + exposure abs (bit3)
            bytes(0x12, 0x24, 0x02, 0x01, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x0A, 0x00, 0x00) +
            // Processing unit id=2, bControlSize=2, controls = brightness (bit0) + gain (bit9)
            bytes(0x0B, 0x24, 0x05, 0x02, 0x01, 0x00, 0x00, 0x02, 0x01, 0x02, 0x00) +
            // VideoStreaming interface
            bytes(0x09, 0x04, 0x01, 0x00, 0x01, 0x0E, 0x02, 0x00, 0x00) +
            // MJPEG format fmt2 with 1 frame
            bytes(0x0B, 0x24, 0x06, 0x02, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00) +
            // MJPEG frame frm1 1920x1080, bitrate 1000..2000, buffer 300000, 60fps default
            bytes(0x1E, 0x24, 0x07, 0x01, 0x00, 0x80, 0x07, 0x38, 0x04,
                0xE8, 0x03, 0x00, 0x00, 0xD0, 0x07, 0x00, 0x00, 0xE0, 0x93, 0x04, 0x00,
                0x0A, 0x8B, 0x02, 0x00, 0x01, 0x0A, 0x8B, 0x02, 0x00)

    @Test
    fun `camera terminal and processing unit controls are decoded`() {
        val entities = UvcDiagnostics.entities(config)
        assertEquals(2, entities.size)
        val ct = entities.first { it.kind == UvcDiagnostics.Kind.CAMERA_TERMINAL }
        assertEquals(listOf("AutoExposureMode", "ExposureTimeAbsolute(100us)"), UvcDiagnostics.supportedControls(ct).map { it.name })
        val pu = entities.first { it.kind == UvcDiagnostics.Kind.PROCESSING_UNIT }
        assertEquals(2, pu.id)
        assertEquals(listOf("Brightness", "Gain"), UvcDiagnostics.supportedControls(pu).map { it.name })
    }

    @Test
    fun `frame descriptors report bitrate and buffer size`() {
        val lines = UvcDiagnostics.describe(config)
        assertTrue(lines.toString(), lines.any { it.contains("fmt2/frm1 1920x1080 bitrate=1000..2000 maxFrameBuf=300000 defaultFps=60.0") })
    }

    @Test
    fun `values are little-endian and signed for 2 bytes`() {
        assertEquals("65535(-1)", UvcDiagnostics.formatValue(bytes(0xFF, 0xFF)))
        assertEquals("300", UvcDiagnostics.formatValue(bytes(0x2C, 0x01)))
    }
}
