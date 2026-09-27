package com.raphael.handmouse.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class HevcParameterSetsTest {

    /** MSB-first bit writer with Exp-Golomb, used to synthesize an SPS. */
    private class BitWriter {
        private val bits = ArrayList<Int>()
        fun u(n: Int, v: Long) { for (i in n - 1 downTo 0) bits += ((v shr i) and 1).toInt() }
        fun ue(v: Int) {
            val x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            repeat(len - 1) { bits += 0 }
            u(len, x.toLong())
        }
        fun bytes(): ByteArray {
            u(1, 1) // rbsp_stop_one_bit
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { i ->
                var b = 0
                for (k in 0 until 8) b = (b shl 1) or bits[i * 8 + k]
                b.toByte()
            }
        }
    }

    /** Adds emulation-prevention bytes like an encoder would. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun sps(width: Int, height: Int, cropBottom: Int, maxSubLayersMinus1: Int = 0, reorder: Int = 0): ByteArray {
        val w = BitWriter()
        w.u(4, 0) // vps id
        w.u(3, maxSubLayersMinus1.toLong())
        w.u(1, 1) // temporal id nesting
        // general PTL: profile_space 0, tier 0, profile_idc 1 (Main)
        w.u(2, 0); w.u(1, 0); w.u(5, 1)
        w.u(32, 0x60000000L) // compat flags
        w.u(4, 0b1001)       // progressive, interlaced, non_packed, frame_only
        w.u(44, 0)           // constraint flags remainder
        w.u(8, 93)           // level 3.1
        for (i in 0 until maxSubLayersMinus1) { w.u(1, 0); w.u(1, 0) }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) w.u(2, 0)
        w.ue(0)       // sps id
        w.ue(1)       // chroma 4:2:0
        w.ue(width)
        w.ue(height)
        if (cropBottom > 0) {
            w.u(1, 1); w.ue(0); w.ue(0); w.ue(0); w.ue(cropBottom / 2)
        } else {
            w.u(1, 0)
        }
        w.ue(0) // bit depth luma - 8
        w.ue(0) // bit depth chroma - 8
        w.ue(4) // log2_max_pic_order_cnt_lsb_minus4
        w.u(1, 1) // sub_layer_ordering_info_present_flag
        for (i in 0..maxSubLayersMinus1) { w.ue(reorder + 1); w.ue(reorder); w.ue(0) }
        return byteArrayOf(0, 0, 0, 1, (33 shl 1).toByte(), 1) + escape(w.bytes())
    }

    @Test
    fun parsesSizeAfterConformanceCrop() {
        val info = HevcParameterSets.parseSps(sps(1920, 1088, cropBottom = 8))
        assertNotNull(info)
        assertEquals(1920, info!!.width)
        assertEquals(1080, info.height)
        assertEquals(1, info.chromaFormatIdc)
    }

    @Test
    fun parsesWithSubLayersAndNoCrop() {
        val info = HevcParameterSets.parseSps(sps(2048, 1512, cropBottom = 0, maxSubLayersMinus1 = 2))!!
        assertEquals(2048, info.width)
        assertEquals(1512, info.height)
        assertEquals(2, info.maxSubLayersMinus1)
    }

    @Test
    fun detectsFrameReordering() {
        assertEquals(0, HevcParameterSets.parseSps(sps(1920, 1088, 8))!!.maxNumReorderPics)
        assertEquals(2, HevcParameterSets.parseSps(sps(1920, 1088, 8, maxSubLayersMinus1 = 1, reorder = 2))!!.maxNumReorderPics)
    }

    @Test
    fun emulationPreventionIsRemoved() {
        assertArrayEquals(
            byteArrayOf(0, 0, 1, 0, 0, 0),
            HevcParameterSets.toRbsp(byteArrayOf(0, 0, 3, 1, 0, 0, 3, 0)),
        )
    }

    @Test
    fun hvcCHasProfileAndThreeArrays() {
        val s = sps(1920, 1088, cropBottom = 8)
        val vps = byteArrayOf(0, 0, 0, 1, (32 shl 1).toByte(), 1, 0x0C, 0x01)
        val pps = byteArrayOf(0, 0, 0, 1, (34 shl 1).toByte(), 1, 0xC1.toByte(), 0x73)
        val hvcc = HevcParameterSets.buildHvcC(vps, s, pps)!!
        assertEquals(1, hvcc[0].toInt())
        assertEquals(1, hvcc[1].toInt() and 0x1F) // profile_idc Main
        assertEquals(93, hvcc[12].toInt() and 0xFF) // level
        assertEquals(3, hvcc[21].toInt() and 0x03) // lengthSizeMinusOne
        assertEquals(3, hvcc[22].toInt()) // numOfArrays
        assertEquals(0x80 or 32, hvcc[23].toInt() and 0xFF)
        val vpsLen = ((hvcc[26].toInt() and 0xFF) shl 8) or (hvcc[27].toInt() and 0xFF)
        assertEquals(vps.size - 4, vpsLen)
    }
}
