package com.raphael.handmouse.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JpegInfoTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun readsSizeFromSof0AfterApp0() {
        val jpeg = b(0xFF, 0xD8) +
            b(0xFF, 0xE0, 0x00, 0x06, 1, 2, 3, 4) + // APP0 (len 6)
            b(0xFF, 0xDB, 0x00, 0x03, 9) +          // DQT (len 3)
            b(0xFF, 0xC0, 0x00, 0x11, 0x08, 0x04, 0x38, 0x07, 0x80) + ByteArray(12)
        assertEquals(JpegInfo.Size(1920, 1080), JpegInfo.size(jpeg))
    }

    @Test
    fun rejectsNonJpeg() {
        assertNull(JpegInfo.size(b(0, 0, 0, 1, 0x40)))
    }
}
