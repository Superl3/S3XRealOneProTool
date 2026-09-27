package com.raphael.handmouse.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class MkvWriterTest {

    /** Tiny EBML reader: returns (id, dataStart, dataSize) of consecutive elements. */
    private data class El(val id: Long, val start: Long, val size: Long)

    private fun readVint(f: RandomAccessFile, keepMarker: Boolean): Pair<Long, Int> {
        val first = f.read()
        var len = 1
        while (len <= 8 && (first and (0x80 shr (len - 1))) == 0) len++
        var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
        repeat(len - 1) { v = (v shl 8) or f.read().toLong() }
        return v to len
    }

    private fun children(f: RandomAccessFile, from: Long, to: Long): List<El> {
        val out = ArrayList<El>()
        var p = from
        while (p < to) {
            f.seek(p)
            val (id, _) = readVint(f, keepMarker = true)
            val (size, _) = readVint(f, keepMarker = false)
            val start = f.filePointer
            out += El(id, start, size)
            p = start + size
        }
        return out
    }

    private fun jpeg(i: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(500 + i) { 7 } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun writesCompleteStructureWithPatchedSizesAndCues() {
        val file = File.createTempFile("mkvtest", ".mkv")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val w = MkvWriter(
                    raf.channel,
                    MkvWriter.VideoTrack(MkvWriter.CODEC_MJPEG, 1920, 1080, pixelCropBottom = 8),
                    MkvWriter.AudioTrack(48000, 1, byteArrayOf(0x11, 0x88.toByte())),
                )
                var t = 5_000_000L
                repeat(180) { i -> // 3 s at 60 fps
                    w.writeVideo(jpeg(i), t, keyframe = true)
                    if (i % 3 == 0) w.writeAudio(ByteArray(100), t + 2000)
                    t += 16_667
                }
                w.close()
                assertEquals(180L, w.videoFrames)
            }

            RandomAccessFile(file, "r").use { f ->
                val top = children(f, 0, f.length())
                assertEquals(listOf(0x1A45DFA3L, 0x18538067L), top.map { it.id })
                val segment = top[1]
                assertEquals("segment size must be patched", f.length(), segment.start + segment.size)

                val seg = children(f, segment.start, segment.start + segment.size)
                val ids = seg.map { it.id }
                assertEquals(0x114D9B74L, ids[0]) // SeekHead replaced the reserved Void
                assertEquals(0xECL, ids[1])        // padding Void
                assertTrue(0x1549A966L in ids && 0x1654AE6BL in ids && 0x1C53BB6BL in ids)
                val tracks = seg.first { it.id == 0x1654AE6BL }
                val videoTrack = children(f, tracks.start, tracks.start + tracks.size).first()
                val video = children(f, videoTrack.start, videoTrack.start + videoTrack.size)
                    .first { it.id == 0xE0L }
                val crop = children(f, video.start, video.start + video.size)
                    .first { it.id == 0x54AAL }
                f.seek(crop.start)
                assertEquals(8, f.read())
                val clusters = seg.filter { it.id == 0x1F43B675L }
                assertEquals(3, clusters.size) // one per second

                var videoBlocks = 0
                var audioBlocks = 0
                for (c in clusters) {
                    for (b in children(f, c.start, c.start + c.size)) {
                        if (b.id != 0xA3L) continue
                        f.seek(b.start)
                        when (f.read()) {
                            0x81 -> videoBlocks++
                            0x82 -> audioBlocks++
                        }
                    }
                }
                assertEquals(180, videoBlocks)
                assertEquals(60, audioBlocks)

                val cues = seg.first { it.id == 0x1C53BB6BL }
                assertEquals(3, children(f, cues.start, cues.start + cues.size).size)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun vintEncoding() {
        assertEquals(listOf(0x81), MkvWriter.sizeVint(1).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x40, 0x7F), MkvWriter.sizeVint(127).map { it.toInt() and 0xFF }) // 127 is reserved in 1 byte
        assertEquals(8, MkvWriter.sizeVint8(5).size)
        assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), MkvWriter.idBytes(0x1A45DFA3).map { it.toInt() and 0xFF })
    }
}
