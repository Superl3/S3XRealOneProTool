package com.raphael.handmouse.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vetores golden capturados do tráfego USB emitido pela libnr_glasses_api.so (captura black-box, captures/run5_fulldump.txt). */
class GlassesFrameTest {

    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // --- comandos reais emitidos pela .so nativa ---
    private val CMD_26 = hex("fd 25 af 75 f5 11 00 00 00 00 00 00 00 00 00 26 00 00 00 00 00 00")
    private val CMD_D2 = hex("fd ef b1 44 65 11 00 00 00 00 00 00 00 00 00 d2 00 00 00 00 00 00")
    private val CMD_D4 = hex("fd d5 84 94 06 11 00 00 00 00 00 00 00 00 00 d4 00 00 00 00 00 00")
    private val CMD_D5 = hex("fd 61 8f e3 a0 11 00 00 00 00 00 00 00 00 00 d5 00 00 00 00 00 00")
    private val CMD_D3 = hex("fd c6 e4 89 b8 15 00 00 00 00 00 00 00 00 00 d3 00 00 00 00 00 00 45 10 01 00")
    private val CMD_D6 = hex(
        "fd ee 87 da 7c 28 00 00 00 00 00 00 00 00 00 d6 00 00 00 00 00 00 " +
            "72 6f 2e 62 73 70 2e 61 70 70 5f 70 72 65 70 61 72 65 5f 64 6f 6e 65"
    )

    @Test
    fun `build reproduz byte a byte os comandos sem payload`() {
        assertArrayEquals(CMD_26, GlassesFrame.build(0x26))
        assertArrayEquals(CMD_D2, GlassesFrame.build(0xd2))
        assertArrayEquals(CMD_D4, GlassesFrame.build(0xd4))
        assertArrayEquals(CMD_D5, GlassesFrame.build(0xd5))
    }

    @Test
    fun `build reproduz o comando com payload binario`() {
        assertArrayEquals(CMD_D3, GlassesFrame.build(0xd3, hex("45 10 01 00")))
    }

    @Test
    fun `build reproduz o comando com payload ASCII`() {
        val prop = "ro.bsp.app_prepare_done".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(CMD_D6, GlassesFrame.build(0xd6, prop))
    }

    @Test
    fun `campo de comprimento e total menos 5`() {
        assertEquals(0x11, GlassesFrame.build(0x26)[5].toInt() and 0xff)   // 22 - 5
        assertEquals(0x15, GlassesFrame.build(0xd3, hex("45 10 01 00"))[5].toInt() and 0xff) // 26 - 5
    }

    @Test
    fun `parse aceita uma resposta real e extrai payload`() {
        // resposta real ao 0xd2, em buffer de 1024 B como vem do bulkTransfer
        val resp = ByteArray(1024)
        hex("fd ed 94 a7 06 16 00 00 00 00 00 4a fb ed ff d2 00 00 00 00 00 00 00 55 9a 00 00")
            .copyInto(resp)
        val p = GlassesFrame.parse(resp, 1024)
        assertEquals(0xd2, p.cmd)
        assertArrayEquals(hex("00 55 9a 00 00"), p.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejeita magic invalido`() {
        val bad = ByteArray(1024).also { it[0] = 0x00 }
        GlassesFrame.parse(bad, 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejeita CRC corrompido`() {
        val resp = ByteArray(1024)
        hex("fd ed 94 a7 06 16 00 00 00 00 00 4a fb ed ff d2 00 00 00 00 00 00 00 55 9a 00 00")
            .copyInto(resp)
        resp[23] = 0x00 // corrompe o payload sem corrigir o CRC
        GlassesFrame.parse(resp, 1024)
    }
}
