package com.raphael.handmouse.recording

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Minimal streaming Matroska (MKV) muxer — pure Kotlin over a [FileChannel], JVM-testable.
 *
 * ## Why Matroska
 * - **Crash-safe.** The segment and every cluster are first written with the EBML "unknown size"
 *   marker and patched to their real size when closed. If the process dies mid-recording (thermal
 *   kill, cable yank, OOM) the file on disk is still a valid, playable Matroska stream up to the
 *   last flushed cluster (≤ ~1 s lost). An MP4 from `MediaMuxer` is unplayable without its
 *   `moov` box, which is only written on a clean `stop()`.
 * - **Carries MJPEG as-is.** The Eye's most stable stream is MJPEG, which `MediaMuxer` cannot
 *   store; Matroska has `V_MJPEG`, so JPEG frames are written untouched (zero decode/encode).
 * - **Carries HEVC as-is** (`V_MPEGH/ISO/HEVC` + hvcC CodecPrivate, length-prefixed NALs).
 *
 * Layout: EBML header · Segment{ Void(reserved for SeekHead) · Info · Tracks · Cluster* · Cues }.
 * On [close] the reserved space becomes a SeekHead pointing at Info/Tracks/Cues, Duration is
 * patched, and the Segment size is fixed.
 *
 * Timestamps are in µs on any monotonic clock; the first VIDEO frame becomes t=0 (audio that
 * arrives before it is dropped). TimecodeScale = 1 ms.
 *
 * Not thread-safe (the recorder thread owns it).
 */
class MkvWriter(
    private val channel: FileChannel,
    private val video: VideoTrack,
    private val audio: AudioTrack? = null,
    private val writingApp: String = "XREAL Eye Tools",
) {

    data class VideoTrack(
        val codecId: String,
        val width: Int,
        val height: Int,
        val codecPrivate: ByteArray? = null,
        val pixelCropBottom: Int = 0,
    ) {
        init {
            require(pixelCropBottom in 0 until height)
        }
    }
    data class AudioTrack(val sampleRate: Int, val channels: Int, val codecPrivate: ByteArray, val codecId: String = "A_AAC")

    companion object {
        const val CODEC_MJPEG = "V_MJPEG"
        const val CODEC_HEVC = "V_MPEGH/ISO/HEVC"

        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEKHEAD = 0x114D9B74L
        private const val ID_SEEK = 0x4DBBL
        private const val ID_SEEKID = 0x53ABL
        private const val ID_SEEKPOSITION = 0x53ACL
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMECODESCALE = 0x2AD7B1L
        private const val ID_DURATION = 0x4489L
        private const val ID_MUXINGAPP = 0x4D80L
        private const val ID_WRITINGAPP = 0x5741L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACKENTRY = 0xAEL
        private const val ID_TRACKNUMBER = 0xD7L
        private const val ID_TRACKUID = 0x73C5L
        private const val ID_TRACKTYPE = 0x83L
        private const val ID_FLAGLACING = 0x9CL
        private const val ID_CODECID = 0x86L
        private const val ID_CODECPRIVATE = 0x63A2L
        private const val ID_VIDEO = 0xE0L
        private const val ID_PIXELWIDTH = 0xB0L
        private const val ID_PIXELHEIGHT = 0xBAL
        private const val ID_PIXELCROPBOTTOM = 0x54AAL
        private const val ID_AUDIO = 0xE1L
        private const val ID_SAMPLINGFREQ = 0xB5L
        private const val ID_CHANNELS = 0x9FL
        private const val ID_CLUSTER = 0x1F43B675L
        private const val ID_TIMECODE = 0xE7L
        private const val ID_SIMPLEBLOCK = 0xA3L
        private const val ID_CUES = 0x1C53BB6BL
        private const val ID_CUEPOINT = 0xBBL
        private const val ID_CUETIME = 0xB3L
        private const val ID_CUETRACKPOSITIONS = 0xB7L
        private const val ID_CUETRACK = 0xF7L
        private const val ID_CUECLUSTERPOSITION = 0xF1L
        private const val ID_VOID = 0xECL

        private const val SEEKHEAD_RESERVE = 128
        private const val UNKNOWN_SIZE_8 = 0x01FFFFFFFFFFFFFFL

        /** Start a new cluster at the first keyframe after this much time. */
        private const val CLUSTER_TARGET_MS = 1000L
        /** Hard limits: SimpleBlock relative timecode is int16. */
        private const val CLUSTER_MAX_SPAN_MS = 30_000L
        private const val CLUSTER_MAX_BYTES = 32L * 1024 * 1024

        private const val WRITE_BUFFER_BYTES = 512 * 1024

        // ---- EBML encoding helpers (internal for tests) ----

        internal fun idBytes(id: Long): ByteArray {
            val n = when {
                id <= 0xFFL -> 1
                id <= 0xFFFFL -> 2
                id <= 0xFFFFFFL -> 3
                else -> 4
            }
            return ByteArray(n) { i -> (id shr (8 * (n - 1 - i))).toByte() }
        }

        internal fun sizeVint(size: Long): ByteArray {
            var n = 1
            while (n < 8 && size >= (1L shl (7 * n)) - 1) n++
            val v = size or (1L shl (7 * n))
            return ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() }
        }

        /** Fixed 8-byte size vint (patchable in place). */
        internal fun sizeVint8(size: Long): ByteArray {
            val v = size or (1L shl 56)
            return ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() }
        }

        internal fun uintBytes(v: Long): ByteArray {
            var n = 1
            while (n < 8 && (v ushr (8 * n)) != 0L) n++
            return ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() }
        }

        internal fun element(id: Long, payload: ByteArray): ByteArray =
            idBytes(id) + sizeVint(payload.size.toLong()) + payload

        internal fun uintElement(id: Long, v: Long) = element(id, uintBytes(v))
        internal fun uint8Element(id: Long, v: Long) =
            element(id, ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() })
        internal fun floatElement(id: Long, v: Double) =
            element(id, ByteBuffer.allocate(8).putDouble(v).array())
        internal fun stringElement(id: Long, s: String) = element(id, s.toByteArray(Charsets.UTF_8))

        private fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) {
                System.arraycopy(p, 0, out, o, p.size)
                o += p.size
            }
            return out
        }
    }

    // ---- output with a small write buffer; `pos` = logical file position ----
    private val wbuf = ByteArray(WRITE_BUFFER_BYTES)
    private var wlen = 0
    private var pos: Long = channel.position()

    private var segmentSizePos = 0L
    private var segmentDataStart = 0L
    private var seekHeadPos = 0L
    private var infoPos = 0L
    private var durationPayloadPos = 0L
    private var tracksPos = 0L

    private var clusterPos = -1L
    private var clusterSizePos = 0L
    private var clusterTcMs = 0L
    private var clusterBytes = 0L
    private val cues = ArrayList<LongArray>() // [timeMs, clusterPosRelative]

    private var baseUs: Long? = null
    private var maxTcMs = 0L
    private var lastVideoTcMs = -1L
    private var lastAudioTcMs = -1L
    private var closed = false

    val bytesWritten: Long get() = pos
    var videoFrames: Long = 0
        private set

    init {
        writeHeader()
    }

    private fun writeHeader() {
        write(element(ID_EBML, concat(
            uintElement(0x4286, 1), // EBMLVersion
            uintElement(0x42F7, 1), // EBMLReadVersion
            uintElement(0x42F2, 4), // EBMLMaxIDLength
            uintElement(0x42F3, 8), // EBMLMaxSizeLength
            stringElement(0x4282, "matroska"), // DocType
            uintElement(0x4287, 4), // DocTypeVersion
            uintElement(0x4285, 2), // DocTypeReadVersion
        )))

        write(idBytes(ID_SEGMENT))
        segmentSizePos = pos
        write(sizeVint8(UNKNOWN_SIZE_8 and 0x00FFFFFFFFFFFFFFL)) // "unknown" until close
        segmentDataStart = pos

        seekHeadPos = pos
        write(voidElement(SEEKHEAD_RESERVE))

        infoPos = pos
        val info = element(ID_INFO, concat(
            uintElement(ID_TIMECODESCALE, 1_000_000),
            stringElement(ID_MUXINGAPP, writingApp),
            stringElement(ID_WRITINGAPP, writingApp),
            floatElement(ID_DURATION, 0.0), // LAST: its 8-byte payload is patched on close
        ))
        durationPayloadPos = pos + info.size - 8
        write(info)

        tracksPos = pos
        val entries = ArrayList<ByteArray>()
        entries += element(ID_TRACKENTRY, concat(
            uintElement(ID_TRACKNUMBER, 1),
            uintElement(ID_TRACKUID, 1),
            uintElement(ID_TRACKTYPE, 1),
            uintElement(ID_FLAGLACING, 0),
            stringElement(ID_CODECID, video.codecId),
            video.codecPrivate?.let { element(ID_CODECPRIVATE, it) } ?: ByteArray(0),
            element(ID_VIDEO, concat(
                uintElement(ID_PIXELWIDTH, video.width.toLong()),
                uintElement(ID_PIXELHEIGHT, video.height.toLong()),
                if (video.pixelCropBottom > 0) uintElement(ID_PIXELCROPBOTTOM, video.pixelCropBottom.toLong()) else ByteArray(0),
            )),
        ))
        if (audio != null) {
            entries += element(ID_TRACKENTRY, concat(
                uintElement(ID_TRACKNUMBER, 2),
                uintElement(ID_TRACKUID, 2),
                uintElement(ID_TRACKTYPE, 2),
                uintElement(ID_FLAGLACING, 0),
                stringElement(ID_CODECID, audio.codecId),
                element(ID_CODECPRIVATE, audio.codecPrivate),
                element(ID_AUDIO, concat(
                    floatElement(ID_SAMPLINGFREQ, audio.sampleRate.toDouble()),
                    uintElement(ID_CHANNELS, audio.channels.toLong()),
                )),
            ))
        }
        write(element(ID_TRACKS, concat(*entries.toTypedArray())))
        flush()
    }

    val acceptsAudio: Boolean get() = audio != null

    fun writeVideo(data: ByteArray, timestampUs: Long, keyframe: Boolean) {
        val tc = videoTimecode(timestampUs, keyframe) ?: return
        writeBlockHeader(1, data.size, tc, keyframe)
        write(data)
        clusterBytes += data.size
    }

    /**
     * One HEVC access unit (NALs with start codes) as a sample: 4-byte length-prefixed NALs,
     * parameter sets and AUDs dropped (they live in the hvcC CodecPrivate). The NAL bytes go
     * straight into the write buffer — no per-frame sample array.
     */
    fun writeHevcAu(nals: List<ByteArray>, timestampUs: Long, keyframe: Boolean) {
        var size = 0
        for (nal in nals) if (keepHevcNal(nal)) size += 4 + nal.size - HevcAccessUnitAssembler.headerOffset(nal)
        if (size == 0) return
        val tc = videoTimecode(timestampUs, keyframe) ?: return
        writeBlockHeader(1, size, tc, keyframe)
        val len = ByteArray(4)
        for (nal in nals) {
            if (!keepHevcNal(nal)) continue
            val h = HevcAccessUnitAssembler.headerOffset(nal)
            val n = nal.size - h
            len[0] = (n ushr 24).toByte(); len[1] = (n ushr 16).toByte(); len[2] = (n ushr 8).toByte(); len[3] = n.toByte()
            write(len)
            write(nal, h, n)
        }
        clusterBytes += size
    }

    private fun keepHevcNal(nal: ByteArray): Boolean =
        HevcAccessUnitAssembler.nalType(nal) !in HevcAccessUnitAssembler.NAL_VPS..HevcAccessUnitAssembler.NAL_AUD

    /** Timecode for the next video frame (opening a cluster if needed), or null when closed. */
    private fun videoTimecode(timestampUs: Long, keyframe: Boolean): Long? {
        if (closed) return null
        val base = baseUs ?: timestampUs.also { baseUs = it }
        var tc = (timestampUs - base) / 1000
        if (tc <= lastVideoTcMs) tc = lastVideoTcMs + 1 // strictly increasing per track
        lastVideoTcMs = tc
        val needCluster = clusterPos < 0 ||
            (keyframe && tc - clusterTcMs >= CLUSTER_TARGET_MS) ||
            tc - clusterTcMs >= CLUSTER_MAX_SPAN_MS ||
            clusterBytes >= CLUSTER_MAX_BYTES
        if (needCluster) openCluster(tc, cueable = keyframe)
        videoFrames++
        return tc
    }

    fun writeAudio(data: ByteArray, timestampUs: Long) {
        if (closed || audio == null) return
        val base = baseUs ?: return // no video yet: nothing to align against
        var tc = (timestampUs - base) / 1000
        if (tc < 0) return
        if (tc < lastAudioTcMs) tc = lastAudioTcMs
        lastAudioTcMs = tc
        if (clusterPos < 0 || tc - clusterTcMs >= CLUSTER_MAX_SPAN_MS) openCluster(tc, cueable = false)
        if (tc - clusterTcMs < Short.MIN_VALUE) return
        writeBlockHeader(2, data.size, tc, true)
        write(data)
        clusterBytes += data.size
    }

    private fun openCluster(tcMs: Long, cueable: Boolean) {
        closeCluster()
        clusterPos = pos
        clusterTcMs = tcMs
        clusterBytes = 0
        write(idBytes(ID_CLUSTER))
        clusterSizePos = pos
        write(sizeVint8(UNKNOWN_SIZE_8 and 0x00FFFFFFFFFFFFFFL))
        write(uintElement(ID_TIMECODE, tcMs))
        if (cueable) cues += longArrayOf(tcMs, clusterPos - segmentDataStart)
    }

    private fun closeCluster() {
        if (clusterPos < 0) return
        val dataStart = clusterSizePos + 8
        flush()
        patch(clusterSizePos, sizeVint8(pos - dataStart))
        clusterPos = -1
    }

    private fun writeBlockHeader(track: Int, dataSize: Int, tcMs: Long, keyframe: Boolean) {
        val rel = (tcMs - clusterTcMs).toInt()
        val header = concat(
            idBytes(ID_SIMPLEBLOCK),
            sizeVint(4L + dataSize),
            byteArrayOf(
                (0x80 or track).toByte(),
                (rel shr 8).toByte(),
                rel.toByte(),
                if (keyframe) 0x80.toByte() else 0,
            ),
        )
        write(header)
        clusterBytes += header.size
        if (tcMs > maxTcMs) maxTcMs = tcMs
    }

    /** Finalizes the file (cues, seek head, duration, sizes). The channel is NOT closed. */
    fun close() {
        if (closed) return
        closed = true
        closeCluster()

        val cuesPos = pos
        if (cues.isNotEmpty()) {
            val points = cues.map { (t, cp) ->
                element(ID_CUEPOINT, concat(
                    uintElement(ID_CUETIME, t),
                    element(ID_CUETRACKPOSITIONS, concat(
                        uintElement(ID_CUETRACK, 1),
                        uintElement(ID_CUECLUSTERPOSITION, cp),
                    )),
                ))
            }
            write(element(ID_CUES, concat(*points.toTypedArray())))
        }
        flush()
        val end = pos

        // Duration: last timestamp + one nominal frame so players show the full length.
        val durationMs = if (videoFrames > 1 && lastVideoTcMs > 0) {
            maxTcMs + lastVideoTcMs / (videoFrames - 1)
        } else {
            maxTcMs
        }
        patch(durationPayloadPos, ByteBuffer.allocate(8).putDouble(durationMs.toDouble()).array())

        val seeks = ArrayList<ByteArray>()
        seeks += seekEntry(ID_INFO, infoPos)
        seeks += seekEntry(ID_TRACKS, tracksPos)
        if (cues.isNotEmpty()) seeks += seekEntry(ID_CUES, cuesPos)
        val seekHead = element(ID_SEEKHEAD, concat(*seeks.toTypedArray()))
        patch(seekHeadPos, seekHead + voidElement(SEEKHEAD_RESERVE - seekHead.size))

        patch(segmentSizePos, sizeVint8(end - segmentDataStart))
        channel.force(false)
    }

    private fun seekEntry(id: Long, absPos: Long) = element(ID_SEEK, concat(
        element(ID_SEEKID, idBytes(id)),
        uint8Element(ID_SEEKPOSITION, absPos - segmentDataStart),
    ))

    /** A Void element of exactly [total] bytes (≥ 2). */
    private fun voidElement(total: Int): ByteArray {
        require(total in 2..128) { "void size $total" } // 1-byte size: up to 126 data bytes
        val out = ByteArray(total)
        out[0] = ID_VOID.toByte()
        out[1] = (0x80 or (total - 2)).toByte()
        return out
    }

    // ---- low-level I/O ----

    private fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

    private fun write(bytes: ByteArray, off: Int, n: Int) {
        if (n >= WRITE_BUFFER_BYTES) {
            flush()
            writeFully(ByteBuffer.wrap(bytes, off, n))
        } else {
            if (wlen + n > WRITE_BUFFER_BYTES) flush()
            System.arraycopy(bytes, off, wbuf, wlen, n)
            wlen += n
        }
        pos += n
    }

    /** Pushes buffered bytes to the channel (called at every cluster boundary ≈ 1 s). */
    fun flush() {
        if (wlen == 0) return
        writeFully(ByteBuffer.wrap(wbuf, 0, wlen))
        wlen = 0
    }

    private fun writeFully(b: ByteBuffer) {
        while (b.hasRemaining()) channel.write(b)
    }

    private fun patch(at: Long, bytes: ByteArray) {
        val b = ByteBuffer.wrap(bytes)
        var p = at
        while (b.hasRemaining()) p += channel.write(b, p)
    }
}
