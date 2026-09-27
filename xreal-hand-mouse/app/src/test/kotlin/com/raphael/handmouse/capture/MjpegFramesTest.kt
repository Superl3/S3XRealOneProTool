package com.raphael.handmouse.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD da validação pura de payloads JPEG ([MjpegFrames.isLikelyJpeg]) — JVM puro, sem Android.
 * O decoder MJPEG usa essa triagem em [MjpegDecoder.onAccessUnit] pra descartar access units que
 * obviamente não são JPEG (ex.: um bitstream HEVC) antes de enfileirar.
 */
class MjpegFramesTest {

    /** Marcador SOI (Start Of Image) que todo JPEG carrega no início. */
    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    /** Marcador EOI (End Of Image). */
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `isLikelyJpeg aceita um JPEG minimo valido`() {
        // SOI + um byte de segmento + EOI = 6 bytes, prefixo FF D8: plausível.
        val jpeg = soi + byteArrayOf(0xFF.toByte(), 0xE0.toByte()) + eoi
        assertTrue(MjpegFrames.isLikelyJpeg(jpeg))
    }

    @Test
    fun `isLikelyJpeg rejeita array vazio`() {
        assertFalse(MjpegFrames.isLikelyJpeg(ByteArray(0)))
    }

    @Test
    fun `isLikelyJpeg rejeita array curto demais (menos de 4 bytes)`() {
        // Mesmo com o prefixo SOI correto, 2 bytes não são um frame plausível.
        assertFalse(MjpegFrames.isLikelyJpeg(soi))
        assertFalse(MjpegFrames.isLikelyJpeg(soi + byteArrayOf(0x00)))
    }

    @Test
    fun `isLikelyJpeg rejeita prefixo errado (bytes de start code HEVC)`() {
        // 00 00 00 01 = start code de NAL HEVC/H264, não um JPEG.
        val hevc = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x40, 0x01)
        assertFalse(MjpegFrames.isLikelyJpeg(hevc))
    }

    @Test
    fun `isLikelyJpeg aceita JPEG com bytes de padding apos o EOI (leniencia documentada)`() {
        // Payload USB bulk pode ter padding depois do EOI — a validação é leniente e olha só o
        // prefixo, então deve aceitar.
        val jpeg = soi + byteArrayOf(0xFF.toByte(), 0xE0.toByte()) + eoi + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertTrue(MjpegFrames.isLikelyJpeg(jpeg))
    }

    // ---- MjpegStreamAssembler (7ª rodada: remontagem por SOI/EOI, ignorando o framing UVC) ----

    /** Um JPEG sintético completo: SOI + corpo (sem FF D8/FF D9 internos) + EOI. */
    private fun jpeg(bodySize: Int, fill: Byte = 0x42): ByteArray =
        soi + ByteArray(bodySize) { fill } + eoi

    @Test
    fun `assembler extrai um frame completo de um unico chunk`() {
        val a = MjpegStreamAssembler()
        val frame = jpeg(100)
        val out = a.feed(frame)
        assertTrue(out.size == 1)
        assertTrue(out[0].contentEquals(frame))
        assertTrue(a.framesExtracted == 1L)
    }

    @Test
    fun `assembler remonta frame partido em varios chunks (o caso do truncamento em 65534b)`() {
        // Em hardware o FrameAssembler cortava o JPEG na fronteira do bulk read e soltava o resto
        // como fragmentos — o remontador precisa juntar tudo de volta.
        val a = MjpegStreamAssembler()
        val frame = jpeg(300)
        assertTrue(a.feed(frame.copyOfRange(0, 120)).isEmpty()) // só o começo (SOI, sem EOI)
        assertTrue(a.feed(frame.copyOfRange(120, 250)).isEmpty()) // meio
        val out = a.feed(frame.copyOfRange(250, frame.size)) // fim (com EOI)
        assertTrue(out.size == 1)
        assertTrue(out[0].contentEquals(frame))
    }

    @Test
    fun `assembler extrai multiplos frames de um chunk so`() {
        val a = MjpegStreamAssembler()
        val f1 = jpeg(50, fill = 0x11)
        val f2 = jpeg(80, fill = 0x22)
        val out = a.feed(f1 + f2)
        assertTrue(out.size == 2)
        assertTrue(out[0].contentEquals(f1))
        assertTrue(out[1].contentEquals(f2))
    }

    @Test
    fun `assembler descarta lixo antes do SOI e conta em discardedBytes`() {
        val a = MjpegStreamAssembler()
        val garbage = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x40, 0x01) // fragmento HEVC
        val frame = jpeg(60)
        val out = a.feed(garbage + frame)
        assertTrue(out.size == 1)
        assertTrue(out[0].contentEquals(frame))
        assertTrue(a.discardedBytes >= garbage.size.toLong())
    }

    @Test
    fun `assembler com marcador EOI partido entre chunks ainda remonta`() {
        // O FF do EOI num chunk e o D9 no seguinte — a fronteira mais traiçoeira.
        val a = MjpegStreamAssembler()
        val frame = jpeg(40)
        val cut = frame.size - 1 // corta entre FF e D9
        assertTrue(a.feed(frame.copyOfRange(0, cut)).isEmpty())
        val out = a.feed(frame.copyOfRange(cut, frame.size))
        assertTrue(out.size == 1)
        assertTrue(out[0].contentEquals(frame))
    }

    @Test
    fun `assembler estoura o teto sem achar EOI e descarta o acumulado`() {
        // Stream que nunca fecha um frame (não é JPEG de verdade): o buffer não pode crescer
        // pra sempre — estourou o teto, zera e contabiliza pro chamador decidir desistir.
        val a = MjpegStreamAssembler(maxBufferBytes = 1024)
        a.feed(soi) // abre um "frame" que nunca fecha
        repeat(3) { a.feed(ByteArray(512) { 0x42 }) }
        assertTrue(a.framesExtracted == 0L)
        assertTrue(a.discardedBytes >= 1024L)
    }

    @Test
    fun `assembler reset zera buffer e contadores`() {
        val a = MjpegStreamAssembler()
        a.feed(byteArrayOf(0x00, 0x01, 0x02)) // lixo
        a.feed(jpeg(30))
        a.reset()
        assertTrue(a.framesExtracted == 0L)
        assertTrue(a.discardedBytes == 0L)
        // Pós-reset: um frame novo sai limpo (nenhum resto do buffer antigo contamina).
        val frame = jpeg(25)
        val out = a.feed(frame)
        assertTrue(out.size == 1)
        assertTrue(out[0].contentEquals(frame))
    }

    @Test
    fun frameThatLostItsTailIsDroppedNotMerged() {
        val a = MjpegStreamAssembler()
        val full = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val headOnly = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9, 9) // EOI lost on the link
        val frames = a.feed(headOnly + full)
        org.junit.Assert.assertEquals(1, frames.size)
        org.junit.Assert.assertArrayEquals(full, frames[0])
    }
}
