package com.raphael.handmouse.glasses

import java.util.zip.CRC32

/**
 * Codec do quadro de controle dos óculos XREAL.
 *
 * Formato (vale nas duas direções — ver docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md):
 *   [0]      0xfd magic
 *   [1..4]   CRC-32 de bytes[5 : 5+len], little-endian
 *   [5]      len = total - 5
 *   [6..14]  zeros nos comandos (nas respostas variam; campo não decodificado)
 *   [15]     código do comando (a resposta ecoa o mesmo)
 *   [16..21] zeros
 *   [22..]   payload
 *
 * Origem: captura black-box do nosso próprio processo (spike 2026-07-23). Nada aqui vem de
 * decompilar a .so proprietária.
 */
object GlassesFrame {

    const val MAGIC = 0xfd
    const val HEADER_SIZE = 22
    private const val LEN_OFFSET = 5
    private const val CMD_OFFSET = 15

    data class Parsed(val cmd: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Parsed && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
    }

    private fun crc32Of(buf: ByteArray, offset: Int, length: Int): Long =
        CRC32().apply { update(buf, offset, length) }.value

    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = HEADER_SIZE + payload.size
        val buf = ByteArray(total)
        buf[0] = MAGIC.toByte()
        val len = total - 5
        buf[LEN_OFFSET] = len.toByte()
        buf[CMD_OFFSET] = cmd.toByte()
        payload.copyInto(buf, HEADER_SIZE)
        val crc = crc32Of(buf, LEN_OFFSET, len)
        buf[1] = (crc and 0xff).toByte()
        buf[2] = ((crc shr 8) and 0xff).toByte()
        buf[3] = ((crc shr 16) and 0xff).toByte()
        buf[4] = ((crc shr 24) and 0xff).toByte()
        return buf
    }

    fun parse(buf: ByteArray, length: Int): Parsed {
        require(length >= HEADER_SIZE) { "resposta curta demais: $length" }
        require(buf[0].toInt() and 0xff == MAGIC) {
            "magic inválido: 0x%02x".format(buf[0].toInt() and 0xff)
        }
        val len = buf[LEN_OFFSET].toInt() and 0xff
        val total = len + 5
        require(total in HEADER_SIZE..length) { "campo de comprimento inconsistente: len=$len" }
        val stored = ((buf[1].toInt() and 0xff).toLong()) or
            ((buf[2].toInt() and 0xff).toLong() shl 8) or
            ((buf[3].toInt() and 0xff).toLong() shl 16) or
            ((buf[4].toInt() and 0xff).toLong() shl 24)
        val actual = crc32Of(buf, LEN_OFFSET, len)
        require(stored == actual) {
            "CRC não confere: esperado 0x%08x, calculado 0x%08x".format(stored, actual)
        }
        return Parsed(buf[CMD_OFFSET].toInt() and 0xff, buf.copyOfRange(HEADER_SIZE, total))
    }
}
