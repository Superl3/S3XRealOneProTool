package com.raphael.handmouse.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do parsing puro de NAL units HEVC (extraído de CameraActivity.kt do repo Aloim:
 * splitNalUnits() + processHevcAccessUnit() + initDecoder()).
 */
class HevcNalTest {

    // --- nalType: tipo = (byte shr 1) and 0x3F ---

    @Test
    fun `nalType extrai VPS (32) do header byte`() {
        // header byte = forbidden(0) + type(32) + layerId hi bits -> 32 shl 1 = 0x40
        assertEquals(32, HevcNal.nalType(0x40.toByte()))
    }

    @Test
    fun `nalType extrai SPS (33)`() {
        assertEquals(33, HevcNal.nalType((33 shl 1).toByte()))
    }

    @Test
    fun `nalType extrai PPS (34)`() {
        assertEquals(34, HevcNal.nalType((34 shl 1).toByte()))
    }

    @Test
    fun `nalType extrai IDR_W_RADL (19) e TRAIL_N (0)`() {
        assertEquals(19, HevcNal.nalType((19 shl 1).toByte()))
        assertEquals(0, HevcNal.nalType(0x00.toByte()))
    }

    @Test
    fun `nalType ignora bit forbidden_zero e layer_id`() {
        // forbidden bit setado (bit7=1) + type=32 (bits 1-6) + layer_id lsb (bit0)=1 -> deve ignorar ambos
        val b = (0x80 or (32 shl 1) or 0x01).toByte()
        assertEquals(32, HevcNal.nalType(b))
    }

    // --- splitNalUnits: divide por start codes 00 00 01 / 00 00 00 01 ---

    @Test
    fun `splitNalUnits separa duas NALs com start code de 4 bytes`() {
        val nal1 = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02)
        val nal2 = byteArrayOf(0, 0, 0, 1, 0x42, 0x03, 0x04, 0x05)
        val data = nal1 + nal2

        val units = HevcNal.splitNalUnits(data)

        assertEquals(2, units.size)
        assertArrayEquals(nal1, units[0])
        assertArrayEquals(nal2, units[1])
    }

    @Test
    fun `splitNalUnits separa NALs com start code de 3 bytes`() {
        val nal1 = byteArrayOf(0, 0, 1, 0x40, 0x01)
        val nal2 = byteArrayOf(0, 0, 1, 0x42, 0x02)
        val data = nal1 + nal2

        val units = HevcNal.splitNalUnits(data)

        assertEquals(2, units.size)
        assertArrayEquals(nal1, units[0])
        assertArrayEquals(nal2, units[1])
    }

    @Test
    fun `splitNalUnits trata bitstream sem start code como uma unica NAL`() {
        val data = byteArrayOf(0x40, 0x01, 0x02, 0x03)
        val units = HevcNal.splitNalUnits(data)
        assertEquals(1, units.size)
        assertArrayEquals(data, units[0])
    }

    @Test
    fun `splitNalUnits retorna lista vazia para bitstream vazio`() {
        assertTrue(HevcNal.splitNalUnits(ByteArray(0)).isEmpty())
    }

    // --- csd-0 = VPS + SPS + PPS com start codes ---

    @Test
    fun `buildCsd0 concatena VPS mais SPS mais PPS na ordem, com start codes preservados`() {
        val vps = byteArrayOf(0, 0, 0, 1, 0x40, 0x01)
        val sps = byteArrayOf(0, 0, 0, 1, 0x42, 0x02)
        val pps = byteArrayOf(0, 0, 0, 1, 0x44, 0x03)

        val csd0 = HevcNal.buildCsd0(vps, sps, pps)

        assertArrayEquals(vps + sps + pps, csd0)
    }

    // --- estado: ignora NALs antes do primeiro VPS/SPS/PPS completo ---

    @Test
    fun `processAccessUnit descarta slice NAL antes de VPS+SPS+PPS completos`() {
        val parser = HevcNal()
        // IDR chega antes de qualquer parameter set
        val idr = byteArrayOf(0, 0, 0, 1) + (19 shl 1).toByte() + byteArrayOf(0x01, 0x02, 0x03)

        val out = parser.processAccessUnit(idr)

        assertTrue("NAL de slice antes dos parameter sets deve ser descartada", out.isEmpty())
        assertTrue(!parser.parameterSetsComplete)
    }

    @Test
    fun `processAccessUnit captura VPS SPS PPS e libera slices depois de completos`() {
        val parser = HevcNal()
        val vps = byteArrayOf(0, 0, 0, 1) + (32 shl 1).toByte() + byteArrayOf(0x01)
        val sps = byteArrayOf(0, 0, 0, 1) + (33 shl 1).toByte() + byteArrayOf(0x02)
        val pps = byteArrayOf(0, 0, 0, 1) + (34 shl 1).toByte() + byteArrayOf(0x03)
        val idr = byteArrayOf(0, 0, 0, 1) + (19 shl 1).toByte() + byteArrayOf(0x04, 0x05, 0x06)

        // primeira access unit: só os parameter sets - nenhuma slice a decodificar ainda
        val out1 = parser.processAccessUnit(vps + sps + pps)
        assertTrue(out1.isEmpty())
        assertTrue(parser.parameterSetsComplete)

        // segunda access unit: agora a IDR deve ser liberada
        val out2 = parser.processAccessUnit(idr)
        assertEquals(1, out2.size)
        assertArrayEquals(idr, out2[0])
    }
}
