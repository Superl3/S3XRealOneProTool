package com.raphael.handmouse.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesTransportTest {

    @Test
    fun `endpoint de controle OUT e o 0x01`() {
        assertTrue(GlassesTransport.isControlOut(0x01))
        assertFalse(GlassesTransport.isControlOut(0x81))
        assertFalse(GlassesTransport.isControlOut(0x89))
    }

    @Test
    fun `endpoint de controle IN e o 0x81`() {
        assertTrue(GlassesTransport.isControlIn(0x81))
        assertFalse(GlassesTransport.isControlIn(0x01))
    }

    @Test
    fun `o endpoint de video 0x89 nunca e confundido com controle`() {
        // 0x89 é o stream MJPEG — reivindicá-lo derrubaria a câmera.
        assertFalse(GlassesTransport.isControlIn(0x89))
        assertFalse(GlassesTransport.isControlOut(0x89))
    }
}
