package com.raphael.handmouse.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD da montagem pura de frames a partir de payloads UVC bulk (extraído de
 * UvcCameraHelper.readAndDeliverFrames() do repo Aloim).
 *
 * Header UVC (byte0=length, byte1=bitfield FID/EOF) — ver docs/camera-access-guide.md.
 *
 * NOTA DE FIDELIDADE (ver relatório task-2-report.md): o repo Aloim NÃO descarta payloads
 * com header inválido, nem finaliza um frame como "incompleto" no toggle de FID sem EOF —
 * em ambos os casos ele entrega/acumula os bytes normalmente (delivery best-effort; NALs
 * malformadas são ignoradas rio abaixo pelo parser HEVC). Os testes abaixo refletem o
 * comportamento real do repo, não a descrição literal do brief.
 */
class FrameAssemblerTest {

    private fun payload(headerLen: Int, fid: Int, eof: Boolean, body: ByteArray): ByteArray {
        val flags = fid or (if (eof) 0x02 else 0x00)
        val header = ByteArray(headerLen)
        header[0] = headerLen.toByte()
        header[1] = flags.toByte()
        return header + body
    }

    @Test
    fun `acumula payloads ate o bit EOF e entrega um frame`() {
        val assembler = FrameAssembler()

        val p1 = payload(headerLen = 2, fid = 0, eof = false, body = byteArrayOf(0x01, 0x02))
        val p2 = payload(headerLen = 2, fid = 0, eof = true, body = byteArrayOf(0x03, 0x04))

        val out1 = assembler.offerPayload(p1, p1.size)
        assertTrue("nao deve entregar frame antes do EOF", out1.isEmpty())

        val out2 = assembler.offerPayload(p2, p2.size)
        assertEquals(1, out2.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), out2[0])
    }

    @Test
    fun `toggle de FID sem EOF entrega o acumulado como frame (comportamento real do repo)`() {
        val assembler = FrameAssembler()

        val p1 = payload(headerLen = 2, fid = 0, eof = false, body = byteArrayOf(0x11, 0x22))
        assembler.offerPayload(p1, p1.size)

        // proximo payload muda o FID sem nunca ter visto EOF - repo entrega o que tinha acumulado
        val p2 = payload(headerLen = 2, fid = 1, eof = false, body = byteArrayOf(0x33))
        val out = assembler.offerPayload(p2, p2.size)

        assertEquals(1, out.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22), out[0])
    }

    @Test
    fun `header com length menor que 2 e tratado como continuacao (append raw, sem parse)`() {
        val assembler = FrameAssembler()

        val p1 = payload(headerLen = 2, fid = 0, eof = false, body = byteArrayOf(0x01))
        assembler.offerPayload(p1, p1.size)

        // payload de 1 byte: nao da pra ler header - vira continuacao bruta
        val raw = byteArrayOf(0x02)
        val out = assembler.offerPayload(raw, raw.size)
        assertTrue(out.isEmpty())

        val p3 = payload(headerLen = 2, fid = 0, eof = true, body = byteArrayOf(0x03))
        val out3 = assembler.offerPayload(p3, p3.size)

        assertEquals(1, out3.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), out3[0])
    }

    @Test
    fun `header com length maior que 12 e tratado como continuacao (append raw)`() {
        val assembler = FrameAssembler()
        val bogus = ByteArray(20)
        bogus[0] = 13 // fora do intervalo valido 2..12
        bogus[1] = 0x02 // seria EOF se fosse interpretado como header - mas nao e
        val out = assembler.offerPayload(bogus, bogus.size)
        assertTrue("header invalido nao deve gerar entrega espuria", out.isEmpty())
    }

    @Test
    fun `header com length maior que o tamanho do payload e tratado como continuacao`() {
        val assembler = FrameAssembler()
        val bogus = byteArrayOf(10, 0x02, 0x01) // headerLen=10 mas so temos 3 bytes
        val out = assembler.offerPayload(bogus, bogus.size)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `frame maior que maxFrameSize e descartado`() {
        val assembler = FrameAssembler(maxFrameSize = 8)

        val p1 = payload(headerLen = 2, fid = 0, eof = false, body = ByteArray(6) { 0x01 })
        val out1 = assembler.offerPayload(p1, p1.size) // acumulado = 6, ainda <= 8
        assertTrue(out1.isEmpty())

        val p2 = payload(headerLen = 2, fid = 0, eof = false, body = ByteArray(6) { 0x02 })
        val out2 = assembler.offerPayload(p2, p2.size) // acumulado passaria a 12 > 8 -> descarta
        assertTrue(out2.isEmpty())

        // o frame seguinte, dentro do limite, deve ser entregue normalmente (accum foi resetado)
        val p3 = payload(headerLen = 2, fid = 0, eof = true, body = ByteArray(4) { 0x03 })
        val out3 = assembler.offerPayload(p3, p3.size)
        assertEquals(1, out3.size)
        assertArrayEquals(ByteArray(4) { 0x03 }, out3[0])
    }

    @Test
    fun `payload vazio (length 0) nao gera entrega nem excecao`() {
        val assembler = FrameAssembler()
        val out = assembler.offerPayload(ByteArray(0), 0)
        assertTrue(out.isEmpty())
    }
}
