package com.raphael.handmouse.recording

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reader for the MKV files [MkvWriter] produces — pure Kotlin over a [FileChannel], JVM-testable.
 * Used by the post-recording enhancer (2026-09-29) to walk the stored frames.
 *
 * It indexes every block on construction (payloads stay on disk; [read] fetches one). Files cut
 * short by a crash are read up to the last complete block: a Segment or Cluster still carrying
 * the "unknown size" marker runs to the end of the file, or — for a cluster — to the next
 * top-level element.
 *
 * Only what [MkvWriter] writes is supported: SimpleBlocks (and plain Blocks inside a BlockGroup)
 * without lacing; laced blocks are counted in [skippedLacedBlocks] and left out.
 */
class MkvReader(private val channel: FileChannel) {

    class Track(
        val number: Int,
        /** 1 = video, 2 = audio. */
        val type: Int,
        val codecId: String,
        val codecPrivate: ByteArray?,
        val width: Int,
        val height: Int,
        val pixelCropBottom: Int,
        val sampleRate: Double,
        val channels: Int,
    )

    /** One stored frame; its payload is [size] bytes at absolute file offset [offset]. */
    class Block(val track: Int, val timeUs: Long, val keyframe: Boolean, val offset: Long, val size: Int)

    companion object {
        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEKHEAD = 0x114D9B74L
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMECODESCALE = 0x2AD7B1L
        private const val ID_DURATION = 0x4489L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACKENTRY = 0xAEL
        private const val ID_TRACKNUMBER = 0xD7L
        private const val ID_TRACKTYPE = 0x83L
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
        private const val ID_BLOCKGROUP = 0xA0L
        private const val ID_BLOCK = 0xA1L
        private const val ID_CUES = 0x1C53BB6BL
        private const val ID_TAGS = 0x1254C367L
        private const val ID_CHAPTERS = 0x1043A770L
        private const val ID_ATTACHMENTS = 0x1941A469L

        /** Elements that can only appear directly in the Segment: they end an unknown-size cluster. */
        private val TOP_LEVEL = setOf(
            ID_CLUSTER, ID_CUES, ID_SEEKHEAD, ID_INFO, ID_TRACKS, ID_TAGS, ID_CHAPTERS, ID_ATTACHMENTS,
            ID_EBML, ID_SEGMENT,
        )

        private const val UNKNOWN = -1L
    }

    private val fileSize = channel.size()
    private val cursor = Cursor(channel)

    val tracks: List<Track>
    /** Nanoseconds per timecode tick (MkvWriter: 1 ms). */
    var timecodeScaleNs: Long = 1_000_000L
        private set
    /** Info/Duration in ticks × scale, as ms; null or 0 in a file that was never closed. */
    var durationMs: Double? = null
        private set
    val blocks: List<Block>
    var skippedLacedBlocks = 0
        private set

    init {
        val tr = ArrayList<Track>()
        val bl = ArrayList<Block>()
        parse(tr, bl)
        tracks = tr
        blocks = bl
    }

    fun videoTrack(): Track? = tracks.firstOrNull { it.type == 1 }
    fun audioTrack(): Track? = tracks.firstOrNull { it.type == 2 }

    /** The payload of [block] (into [reuse] when it is large enough; the result may be longer). */
    fun read(block: Block, reuse: ByteArray? = null): ByteArray {
        val out = if (reuse != null && reuse.size >= block.size) reuse else ByteArray(block.size)
        val b = ByteBuffer.wrap(out, 0, block.size)
        var p = block.offset
        while (b.hasRemaining()) {
            val n = channel.read(b, p)
            if (n < 0) throw EOFException("block at ${block.offset} runs past the end of the file")
            p += n
        }
        return out
    }

    private fun parse(tracks: MutableList<Track>, blocks: MutableList<Block>) {
        val c = cursor
        c.seek(0)
        val ebmlId = c.readId()
        require(ebmlId == ID_EBML) { "not an EBML file (id 0x${ebmlId.toString(16)})" }
        c.skip(c.readSize().also { require(it != UNKNOWN) })
        val segId = c.readId()
        require(segId == ID_SEGMENT) { "no Segment (id 0x${segId.toString(16)})" }
        val segSize = c.readSize()
        val segEnd = if (segSize == UNKNOWN) fileSize else minOf(fileSize, c.pos + segSize)

        try {
            while (c.pos < segEnd) {
                val id = c.readId()
                val size = c.readSize()
                val start = c.pos
                if (id == ID_CLUSTER) {
                    parseCluster(start, if (size == UNKNOWN) segEnd else minOf(segEnd, start + size), size == UNKNOWN, blocks)
                    continue // parseCluster leaves the cursor at the next element
                }
                if (size == UNKNOWN) return // only clusters are written open-ended
                when (id) {
                    ID_INFO -> parseInfo(start, minOf(segEnd, start + size))
                    ID_TRACKS -> parseTracks(start, minOf(segEnd, start + size), tracks)
                }
                c.seek(start + size)
            }
        } catch (_: EOFException) {
            // truncated file: keep what was complete
        }
    }

    private fun parseInfo(from: Long, to: Long) {
        val c = cursor
        c.seek(from)
        var durationTicks: Double? = null
        while (c.pos < to) {
            val id = c.readId()
            val size = c.readSize()
            val start = c.pos
            when (id) {
                ID_TIMECODESCALE -> timecodeScaleNs = c.uint(size.toInt())
                ID_DURATION -> durationTicks = c.float(size.toInt())
            }
            c.seek(start + size)
        }
        durationMs = durationTicks?.let { it * timecodeScaleNs / 1e6 }
    }

    private fun parseTracks(from: Long, to: Long, out: MutableList<Track>) {
        val c = cursor
        c.seek(from)
        while (c.pos < to) {
            val id = c.readId()
            val size = c.readSize()
            val start = c.pos
            if (id == ID_TRACKENTRY) parseTrackEntry(start, start + size)?.let { out += it }
            c.seek(start + size)
        }
    }

    private fun parseTrackEntry(from: Long, to: Long): Track? {
        val c = cursor
        c.seek(from)
        var number = 0
        var type = 0
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var width = 0
        var height = 0
        var cropBottom = 0
        var rate = 0.0
        var channels = 1
        while (c.pos < to) {
            val id = c.readId()
            val size = c.readSize()
            val start = c.pos
            when (id) {
                ID_TRACKNUMBER -> number = c.uint(size.toInt()).toInt()
                ID_TRACKTYPE -> type = c.uint(size.toInt()).toInt()
                ID_CODECID -> codecId = String(c.bytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
                ID_CODECPRIVATE -> codecPrivate = c.bytes(size.toInt())
                ID_VIDEO -> {
                    val vEnd = start + size
                    while (c.pos < vEnd) {
                        val vid = c.readId()
                        val vs = c.readSize()
                        val vStart = c.pos
                        when (vid) {
                            ID_PIXELWIDTH -> width = c.uint(vs.toInt()).toInt()
                            ID_PIXELHEIGHT -> height = c.uint(vs.toInt()).toInt()
                            ID_PIXELCROPBOTTOM -> cropBottom = c.uint(vs.toInt()).toInt()
                        }
                        c.seek(vStart + vs)
                    }
                }
                ID_AUDIO -> {
                    val aEnd = start + size
                    while (c.pos < aEnd) {
                        val aid = c.readId()
                        val asz = c.readSize()
                        val aStart = c.pos
                        when (aid) {
                            ID_SAMPLINGFREQ -> rate = c.float(asz.toInt())
                            ID_CHANNELS -> channels = c.uint(asz.toInt()).toInt()
                        }
                        c.seek(aStart + asz)
                    }
                }
            }
            c.seek(start + size)
        }
        if (number == 0) return null
        return Track(number, type, codecId, codecPrivate, width, height, cropBottom, rate, channels)
    }

    /** Leaves the cursor at the first byte after the cluster (or at the element that ended it). */
    private fun parseCluster(from: Long, to: Long, unknownSize: Boolean, out: MutableList<Block>) {
        val c = cursor
        c.seek(from)
        var clusterTc = 0L
        while (c.pos < to) {
            val idPos = c.pos
            val id = c.readId()
            if (unknownSize && id in TOP_LEVEL) {
                c.seek(idPos)
                return
            }
            val size = c.readSize()
            if (size == UNKNOWN) throw EOFException("unknown-size element inside a cluster")
            val start = c.pos
            if (start + size > fileSize) throw EOFException("block runs past the end of the file")
            when (id) {
                ID_TIMECODE -> clusterTc = c.uint(size.toInt())
                ID_SIMPLEBLOCK -> readBlock(start, size, clusterTc, simple = true)?.let { out += it }
                ID_BLOCKGROUP -> {
                    val gEnd = start + size
                    while (c.pos < gEnd) {
                        val gid = c.readId()
                        val gs = c.readSize()
                        val gStart = c.pos
                        if (gid == ID_BLOCK) readBlock(gStart, gs, clusterTc, simple = false)?.let { out += it }
                        c.seek(gStart + gs)
                    }
                }
            }
            c.seek(start + size)
        }
        c.seek(to)
    }

    private fun readBlock(start: Long, size: Long, clusterTc: Long, simple: Boolean): Block? {
        val c = cursor
        c.seek(start)
        val track = c.readSize().toInt() // track number is a size-style vint
        val rel = ((c.u8() shl 8) or c.u8()).toShort().toInt()
        val flags = c.u8()
        if (flags and 0x06 != 0) {
            skippedLacedBlocks++
            return null
        }
        val headerLen = (c.pos - start).toInt()
        val timeNs = (clusterTc + rel) * timecodeScaleNs
        val keyframe = if (simple) flags and 0x80 != 0 else true
        return Block(track, timeNs / 1000, keyframe, start + headerLen, (size - headerLen).toInt())
    }

    /** Buffered forward reader with absolute positioning. Small buffer: indexing jumps over
     * every frame payload, so each block header costs one refill. */
    private class Cursor(private val channel: FileChannel) {
        private val buf = ByteBuffer.allocate(8 * 1024)
        private var bufStart = 0L
        private var bufLen = 0
        var pos = 0L
            private set

        fun seek(p: Long) {
            pos = p
        }

        fun skip(n: Long) {
            pos += n
        }

        fun u8(): Int {
            if (pos < bufStart || pos >= bufStart + bufLen) fill()
            val v = buf.get((pos - bufStart).toInt()).toInt() and 0xFF
            pos++
            return v
        }

        private fun fill() {
            buf.clear()
            bufStart = pos
            var n = 0
            while (buf.hasRemaining()) {
                val r = channel.read(buf, bufStart + n)
                if (r <= 0) break
                n += r
            }
            bufLen = n
            if (bufLen == 0) throw EOFException("end of file at $pos")
        }

        /** EBML element ID, marker bits kept. */
        fun readId(): Long {
            val first = u8()
            val len = vintLength(first)
            if (len > 4) throw EOFException("bad element id at ${pos - 1}")
            var v = first.toLong()
            repeat(len - 1) { v = (v shl 8) or u8().toLong() }
            return v
        }

        /** EBML size, marker stripped; [UNKNOWN] for the all-ones "unknown size" value. */
        fun readSize(): Long {
            val first = u8()
            val len = vintLength(first)
            if (len > 8) throw EOFException("bad size at ${pos - 1}")
            var v = (first and (0xFF shr len)).toLong()
            var allOnes = v == (0xFF shr len).toLong()
            repeat(len - 1) {
                val b = u8()
                if (b != 0xFF) allOnes = false
                v = (v shl 8) or b.toLong()
            }
            return if (allOnes) UNKNOWN else v
        }

        fun uint(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun float(n: Int): Double = when (n) {
            4 -> java.lang.Float.intBitsToFloat(uint(4).toInt()).toDouble()
            8 -> java.lang.Double.longBitsToDouble(uint(8))
            else -> { skip(n.toLong()); 0.0 }
        }

        fun bytes(n: Int): ByteArray = ByteArray(n) { u8().toByte() }

        private fun vintLength(first: Int): Int {
            var len = 1
            while (len <= 8 && (first and (0x80 shr (len - 1))) == 0) len++
            return len
        }
    }
}
