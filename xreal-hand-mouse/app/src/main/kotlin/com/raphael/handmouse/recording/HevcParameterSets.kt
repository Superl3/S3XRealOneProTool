package com.raphael.handmouse.recording

import java.io.ByteArrayOutputStream

/**
 * HEVC parameter-set helpers for the recorder — pure Kotlin, JVM-testable.
 *
 * - [parseSps]: picture size (after the conformance-window crop), chroma format and bit depths.
 *   The muxers need the real size: the UVC descriptor size is only a hint (the native stream is
 *   2048x1512 coded, cropped from 2048x1536-aligned macroblocks, etc.).
 * - [buildHvcC]: the `HEVCDecoderConfigurationRecord` (ISO/IEC 14496-15 §8.3.3) that Matroska
 *   stores as CodecPrivate. The general profile/tier/level fields are copied verbatim from the
 *   SPS: bytes 1..12 of the SPS RBSP ARE `general_profile_space..general_level_idc`, byte-aligned
 *   (byte 0 holds vps_id/max_sub_layers/temporal_id_nesting).
 */
object HevcParameterSets {

    data class SpsInfo(
        val width: Int,
        val height: Int,
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val maxSubLayersMinus1: Int,
        val temporalIdNesting: Boolean,
        /** RBSP bytes 1..12: general profile/tier/compat/constraint/level (12 bytes). */
        val generalPtl: ByteArray,
        /** sps_max_num_reorder_pics of the highest sub-layer: > 0 means B-frames / reordering
         * (arrival-order timestamps would then be wrong). -1 = could not be parsed. */
        val maxNumReorderPics: Int = -1,
    )

    /** NAL payload without the start code (header included), or null. */
    fun stripStartCode(nal: ByteArray): ByteArray? {
        val h = HevcAccessUnitAssembler.headerOffset(nal)
        return if (h < 0) null else nal.copyOfRange(h, nal.size)
    }

    /** Removes emulation-prevention bytes (`00 00 03` → `00 00`). */
    fun toRbsp(data: ByteArray, from: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var zeros = 0
        var i = from
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                i++
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
            i++
        }
        return out.toByteArray()
    }

    /** Parses an SPS NAL (with or without start code). Returns null on malformed input. */
    fun parseSps(nal: ByteArray): SpsInfo? = try {
        val payload = stripStartCode(nal) ?: nal
        // skip the 2-byte NAL header
        val rbsp = toRbsp(payload, 2)
        val r = BitReader(rbsp)
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.u(3)
        val nesting = r.u(1) == 1
        val ptl = rbsp.copyOfRange(1, 13)
        // profile_tier_level(1, maxSubLayersMinus1)
        r.skip(96) // general part (profile 8 + compat 32 + flags 48 + level 8)
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.u(1) == 1
            subLevel[i] = r.u(1) == 1
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        var separateColourPlane = false
        if (chroma == 3) separateColourPlane = r.u(1) == 1
        var width = r.ue()
        var height = r.ue()
        if (r.u(1) == 1) { // conformance_window_flag
            val left = r.ue()
            val right = r.ue()
            val top = r.ue()
            val bottom = r.ue()
            val chromaArrayType = if (separateColourPlane) 0 else chroma
            val subW = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
            val subH = if (chromaArrayType == 1) 2 else 1
            width -= subW * (left + right)
            height -= subH * (top + bottom)
        }
        val bdl = r.ue()
        val bdc = r.ue()
        val reorder = try {
            r.ue() // log2_max_pic_order_cnt_lsb_minus4
            val orderingInfoAllLayers = r.u(1) == 1
            var last = 0
            for (i in (if (orderingInfoAllLayers) 0 else maxSubLayersMinus1)..maxSubLayersMinus1) {
                r.ue() // max_dec_pic_buffering_minus1
                last = r.ue() // max_num_reorder_pics
                r.ue() // max_latency_increase_plus1
            }
            last
        } catch (_: IndexOutOfBoundsException) {
            -1
        }
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) null
        else SpsInfo(width, height, chroma, bdl, bdc, maxSubLayersMinus1, nesting, ptl, reorder)
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /**
     * `HEVCDecoderConfigurationRecord` with one VPS, SPS and PPS array (NALs given with or
     * without start codes). lengthSizeMinusOne = 3 (4-byte NAL lengths in samples).
     */
    fun buildHvcC(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray? {
        val info = parseSps(sps) ?: return null
        val v = stripStartCode(vps) ?: vps
        val s = stripStartCode(sps) ?: sps
        val p = stripStartCode(pps) ?: pps
        val out = ByteArrayOutputStream(64 + v.size + s.size + p.size)
        out.write(1) // configurationVersion
        out.write(info.generalPtl) // profile_space/tier/idc, compat flags, constraint flags, level
        out.write(0xF0); out.write(0x00) // reserved 1111 + min_spatial_segmentation_idc = 0
        out.write(0xFC) // reserved 111111 + parallelismType = 0 (unknown)
        out.write(0xFC or (info.chromaFormatIdc and 0x03))
        out.write(0xF8 or (info.bitDepthLumaMinus8 and 0x07))
        out.write(0xF8 or (info.bitDepthChromaMinus8 and 0x07))
        out.write(0); out.write(0) // avgFrameRate = 0 (unspecified)
        // constantFrameRate(2)=0 | numTemporalLayers(3) | temporalIdNested(1) | lengthSizeMinusOne(2)=3
        val numTemporalLayers = (info.maxSubLayersMinus1 + 1) and 0x07
        out.write((numTemporalLayers shl 3) or ((if (info.temporalIdNesting) 1 else 0) shl 2) or 3)
        out.write(3) // numOfArrays
        for ((type, nal) in listOf(
            HevcAccessUnitAssembler.NAL_VPS to v,
            HevcAccessUnitAssembler.NAL_SPS to s,
            HevcAccessUnitAssembler.NAL_PPS to p,
        )) {
            out.write(0x80 or type) // array_completeness=1, reserved=0, NAL_unit_type
            out.write(0); out.write(1) // numNalus = 1
            out.write((nal.size shr 8) and 0xFF); out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Minimal MSB-first bit reader with Exp-Golomb support. */
    class BitReader(private val data: ByteArray) {
        private var pos = 0 // bit position

        fun u(bits: Int): Int {
            var v = 0
            repeat(bits) {
                val byte = data[pos ushr 3].toInt() and 0xFF
                val bit = (byte shr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }

        fun skip(bits: Int) {
            pos += bits
            if ((pos - 1) ushr 3 >= data.size) throw IndexOutOfBoundsException("skip past end")
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                if (zeros > 31) throw IndexOutOfBoundsException("bad exp-golomb")
            }
            return if (zeros == 0) 0 else ((1 shl zeros) - 1 + u(zeros))
        }
    }
}

/**
 * Latest VPS/SPS/PPS seen in the stream (shared by the recorder and the photo path). [csd] — the
 * three concatenated — is rebuilt only when one of them actually changes, so callers can detect
 * a change with a reference comparison instead of concatenating and comparing every frame.
 */
class HevcParamSetTracker {
    var vps: ByteArray? = null
        private set
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set
    var csd: ByteArray? = null
        private set

    /** Returns true when this access unit changed the parameter sets. */
    fun update(au: HevcAccessUnitAssembler.AccessUnit): Boolean {
        var changed = false
        for (nal in au.nals) {
            when (HevcAccessUnitAssembler.nalType(nal)) {
                HevcAccessUnitAssembler.NAL_VPS -> if (!nal.contentEquals(vps)) { vps = nal; changed = true }
                HevcAccessUnitAssembler.NAL_SPS -> if (!nal.contentEquals(sps)) { sps = nal; changed = true }
                HevcAccessUnitAssembler.NAL_PPS -> if (!nal.contentEquals(pps)) { pps = nal; changed = true }
            }
        }
        if (changed) {
            val v = vps
            val s = sps
            val p = pps
            csd = if (v != null && s != null && p != null) v + s + p else null
        }
        return changed
    }
}
