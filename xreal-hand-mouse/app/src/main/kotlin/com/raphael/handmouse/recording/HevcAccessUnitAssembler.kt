package com.raphael.handmouse.recording

/**
 * Reassembles complete HEVC access units (one picture = one sample) from arbitrary chunks of the
 * Annex-B byte stream delivered by the UVC endpoint. Pure Kotlin (no Android) — JVM-testable.
 *
 * ## Why the recorder needs its own assembler
 * The decoder path ([com.raphael.handmouse.capture.HevcDecoder]) feeds MediaCodec NAL-by-NAL and
 * tolerates chunks that start mid-NAL (the codec conceals the damage). A muxer does not: every
 * sample must be exactly one access unit, and a keyframe flag on the wrong sample produces a file
 * that cannot be seeked. The [com.raphael.handmouse.capture.FrameAssembler] chunks follow the UVC
 * FID/EOF framing, which does not reliably line up with picture boundaries (large I-frames span
 * several bulk reads). So this class ignores the transport framing completely: it treats the
 * chunks as one continuous byte stream, cuts NAL units at start codes (`00 00 01`, optionally
 * preceded by a zero byte), and groups NALs into access units using the HEVC rules (spec 7.4.2.4.4):
 * a new AU starts at an AUD/VPS/SPS/PPS/prefix-SEI after a VCL NAL of the current AU, or at a VCL
 * NAL whose `first_slice_segment_in_pic_flag` is set.
 *
 * An AU is emitted when the first NAL of the NEXT AU is seen (one frame of latency, ~16 ms).
 * NALs are returned WITH their start codes.
 *
 * Single-threaded by contract (the recorder thread).
 */
class HevcAccessUnitAssembler(private val maxAuBytes: Int = DEFAULT_MAX_AU_BYTES) {

    companion object {
        const val DEFAULT_MAX_AU_BYTES = 8 * 1024 * 1024

        const val NAL_VPS = 32
        const val NAL_SPS = 33
        const val NAL_PPS = 34
        const val NAL_AUD = 35
        const val NAL_PREFIX_SEI = 39

        /** NAL type (6 bits) of a NAL that starts with a 3- or 4-byte start code, or -1. */
        fun nalType(nal: ByteArray): Int {
            val h = headerOffset(nal)
            if (h < 0 || h >= nal.size) return -1
            return (nal[h].toInt() shr 1) and 0x3F
        }

        /** Offset of the first NAL header byte (just after the start code), or -1. */
        fun headerOffset(nal: ByteArray): Int = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() -> 3
            nal.size >= 5 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() &&
                nal[3] == 1.toByte() -> 4
            else -> -1
        }

        fun isVcl(type: Int) = type in 0..31

        /** IRAP pictures (BLA/IDR/CRA, types 16..23) are random access points = sync samples. */
        fun isIrap(type: Int) = type in 16..23

        private fun startsNewAuWhenNonVcl(type: Int) =
            type == NAL_AUD || type == NAL_VPS || type == NAL_SPS || type == NAL_PPS ||
                type == NAL_PREFIX_SEI || type in 41..44 || type in 48..55
    }

    class AccessUnit(
        /** NAL units of this picture, each WITH its start code, in stream order. */
        val nals: List<ByteArray>,
        /** true when the picture is an IRAP (BLA/IDR/CRA) — a keyframe / sync sample. */
        val isIrap: Boolean,
        /** Arrival time (µs, caller clock) of the chunk that carried this AU's first NAL. */
        val timestampUs: Long,
    ) {
        val totalBytes: Int get() = nals.sumOf { it.size }
    }

    // --- byte stream -> NAL units ---
    private var buf = ByteArray(256 * 1024)
    private var len = 0
    private var nalStart = -1          // index in buf of the current (incomplete) NAL's start code
    private var nalTimestampUs = 0L    // arrival time of the chunk where the current NAL started
    private var scanPos = 0

    // --- NAL units -> access units ---
    private val auNals = ArrayList<ByteArray>()
    private var auHasVcl = false
    private var auIsIrap = false
    private var auBytes = 0
    private var auTimestampUs = 0L

    /** Bytes thrown away (garbage before the first start code, oversize AUs) — diagnostics. */
    var discardedBytes = 0L
        private set

    /**
     * Feeds one chunk (any boundaries) and returns the access units completed by it, in order.
     * [timestampUs] is the arrival time of this chunk.
     */
    fun feed(chunk: ByteArray, timestampUs: Long): List<AccessUnit> {
        if (chunk.isEmpty()) return emptyList()
        append(chunk)
        val out = ArrayList<AccessUnit>(2)

        var i = scanPos
        while (i + 2 < len) {
            if ((buf[i + 2].toInt() and 0xFF) > 1) {
                // Fast skip: no start code can begin at i, i+1 or i+2 unless buf[i+2] is 0 or 1.
                i += 3
                continue
            }
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                // 4-byte form: a zero right before "00 00 01" belongs to this start code — as long
                // as it is not the header of the NAL we are currently inside.
                val zeroBefore = i > 0 && buf[i - 1] == 0.toByte() && (nalStart < 0 || i - 1 > nalStart + 3)
                val scStart = if (zeroBefore) i - 1 else i
                if (nalStart >= 0) {
                    onNal(buf.copyOfRange(nalStart, scStart), nalTimestampUs, out)
                } else if (scStart > 0) {
                    discardedBytes += scStart // garbage before the very first start code
                }
                nalStart = scStart
                nalTimestampUs = timestampUs
                i += 3
                continue
            }
            i++
        }
        scanPos = i
        compact()
        return out
    }

    /**
     * End of stream: the NAL still pending in the buffer is complete — push it and emit the
     * current AU (if it has a picture). Returns null when there is nothing complete.
     */
    fun flush(): List<AccessUnit> {
        val out = ArrayList<AccessUnit>(2)
        if (nalStart >= 0 && len - nalStart > 4) {
            onNal(buf.copyOfRange(nalStart, len), nalTimestampUs, out)
        }
        nalStart = -1
        len = 0
        scanPos = 0
        takeAu()?.let { out.add(it) }
        return out
    }

    /** Drops everything (stream discontinuity: restart, overflow). */
    fun reset() {
        len = 0
        nalStart = -1
        scanPos = 0
        auNals.clear()
        auHasVcl = false
        auIsIrap = false
        auBytes = 0
        skippingOversizeAu = false
    }

    // After an oversize AU was dropped, its remaining slices must not leak into a bogus AU.
    private var skippingOversizeAu = false

    private fun onNal(nal: ByteArray, tsUs: Long, out: MutableList<AccessUnit>) {
        val h = headerOffset(nal)
        if (h < 0 || nal.size < h + 2) return // start code with no header: ignore
        val type = (nal[h].toInt() shr 1) and 0x3F
        // first_slice_segment_in_pic_flag = MSB of the byte after the 2-byte NAL header
        val firstSlice = isVcl(type) && nal.size > h + 2 && (nal[h + 2].toInt() and 0x80) != 0
        val auBoundaryType = firstSlice || startsNewAuWhenNonVcl(type)

        if (skippingOversizeAu) {
            if (!auBoundaryType) {
                discardedBytes += nal.size
                return
            }
            skippingOversizeAu = false
        }

        if (auBoundaryType && auHasVcl) takeAu()?.let { out.add(it) }

        if (auNals.isEmpty()) auTimestampUs = tsUs
        auNals.add(nal)
        auBytes += nal.size
        if (isVcl(type)) {
            auHasVcl = true
            if (isIrap(type)) auIsIrap = true
        }
        if (auBytes > maxAuBytes) {
            discardedBytes += auBytes
            auNals.clear()
            auHasVcl = false
            auIsIrap = false
            auBytes = 0
            skippingOversizeAu = true
        }
    }

    private fun takeAu(): AccessUnit? {
        if (auNals.isEmpty()) return null
        val au = if (auHasVcl) AccessUnit(ArrayList(auNals), auIsIrap, auTimestampUs) else null
        if (au == null) {
            // parameter sets / SEI without a picture (e.g. the stream ended): keep them for the
            // next picture instead of dropping — they belong to it.
            return null
        }
        auNals.clear()
        auHasVcl = false
        auIsIrap = false
        auBytes = 0
        return au
    }

    private fun append(chunk: ByteArray) {
        if (len + chunk.size > buf.size) {
            var cap = buf.size
            while (cap < len + chunk.size) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(chunk, 0, buf, len, chunk.size)
        len += chunk.size
    }

    /** Drops bytes that can no longer be part of a NAL (everything before the current start
     * code, or all but the last 3 bytes when no start code has been seen yet). */
    private fun compact() {
        val keepFrom = if (nalStart >= 0) nalStart else maxOf(0, len - 3)
        if (nalStart < 0) discardedBytes += keepFrom
        if (keepFrom == 0) return
        System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
        len -= keepFrom
        scanPos = maxOf(0, scanPos - keepFrom)
        if (nalStart >= 0) nalStart -= keepFrom
    }
}
