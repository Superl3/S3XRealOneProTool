package com.raphael.handmouse.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesCommandsTest {

    /** Ordem capturada do tráfego USB emitido pela .so nativa (captura black-box, captures/run1.txt, 20 comandos OUT). */
    private val EXPECTED_ORDER = listOf(
        0x26, 0xd4,
        0x26, 0xd4, 0xd6,
        0x26, 0xd4,
        0x26, 0xd4, 0xd2,
        0x26, 0xd4,
        0x26, 0xd4, 0xd3,
        0x26, 0xd4,
        0x26, 0xd4, 0xd5,
    )

    @Test
    fun `a sequencia tem exatamente 20 mensagens`() {
        assertEquals(20, GlassesCommands.enableCameraSequence().size)
    }

    @Test
    fun `a ordem dos codigos de comando bate com a captura`() {
        val actual = GlassesCommands.enableCameraSequence().map { it[15].toInt() and 0xff }
        assertEquals(EXPECTED_ORDER, actual)
    }

    @Test
    fun `o comando de propriedade carrega a string correta`() {
        val msg = GlassesCommands.enableCameraSequence()[4]
        assertEquals(0xd6, msg[15].toInt() and 0xff)
        val payload = msg.copyOfRange(22, msg.size).toString(Charsets.US_ASCII)
        assertEquals("ro.bsp.app_prepare_done", payload)
    }

    @Test
    fun `o comando de set config carrega a constante capturada`() {
        val msg = GlassesCommands.enableCameraSequence()[14]
        assertEquals(0xd3, msg[15].toInt() and 0xff)
        assertEquals(26, msg.size)
        assertArrayEquals(UsbConfigCodec.SET_UVC0_PAYLOAD, msg.copyOfRange(22, msg.size))
    }

    @Test
    fun `helpers de operacao carregam preambulo + comando corretos`() {
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd6),
            GlassesCommands.waitPilotReadyMessages().map { it[15].toInt() and 0xff })
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd2),
            GlassesCommands.getUsbConfigMessages().map { it[15].toInt() and 0xff })
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd3),
            GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD).map { it[15].toInt() and 0xff })
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd5),
            GlassesCommands.getCameraStatusMessages().map { it[15].toInt() and 0xff })
    }

    @Test
    fun `waitPilotReady carrega a propriedade e setUsbConfig o payload`() {
        val prop = GlassesCommands.waitPilotReadyMessages()[4]
        assertEquals("ro.bsp.app_prepare_done", prop.copyOfRange(22, prop.size).toString(Charsets.US_ASCII))
        val set = GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD)[4]
        assertArrayEquals(UsbConfigCodec.SET_UVC0_PAYLOAD, set.copyOfRange(22, set.size))
    }

    @Test
    fun `enableCameraSequence e a concatenacao dos 4 helpers`() {
        val expected = GlassesCommands.waitPilotReadyMessages() +
            GlassesCommands.getUsbConfigMessages() +
            GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD) +
            GlassesCommands.getCameraStatusMessages()
        val actual = GlassesCommands.enableCameraSequence()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a) }
    }
}
