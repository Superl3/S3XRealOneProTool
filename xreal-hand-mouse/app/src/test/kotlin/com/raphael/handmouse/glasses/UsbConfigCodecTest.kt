package com.raphael.handmouse.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbConfigCodecTest {

    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Payload real da resposta ao 0xd2, correlacionado NA MESMA execução com o log do app
     * ("Atual: ncm=1 ecm=1 hid=1 uvc0=1 uvc1=2" — 2026-07-23 21:02:01).
     */
    @Test
    fun `decodifica o payload real capturado`() {
        val st = UsbConfigCodec.decode(hex("00 55 9a 00 00"))
        assertEquals(1, st.ncm)
        assertEquals(1, st.ecm)
        assertEquals(1, st.hidCtrl)
        assertEquals(1, st.uvc0)
        assertEquals(2, st.uvc1)
    }

    @Test
    fun `payload curto demais vira estado zerado em vez de crashar`() {
        val st = UsbConfigCodec.decode(hex("00"))
        assertEquals(0, st.ncm)
        assertEquals(0, st.uvc1)
    }

    @Test
    fun `a constante de ativacao da uvc0 e a capturada`() {
        assertArrayEquals(hex("45 10 01 00"), UsbConfigCodec.SET_UVC0_PAYLOAD)
    }
}
