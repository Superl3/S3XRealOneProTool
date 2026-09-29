package com.raphael.handmouse.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class MkvReaderTest {

    private fun jpeg(i: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(500 + i) { (i + it).toByte() } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun writeFile(frames: Int, close: Boolean): File {
        val file = File.createTempFile("mkvread", ".mkv")
        RandomAccessFile(file, "rw").use { raf ->
            val w = MkvWriter(
                raf.channel,
                MkvWriter.VideoTrack(MkvWriter.CODEC_MJPEG, 1920, 1080, pixelCropBottom = 8),
                MkvWriter.AudioTrack(48000, 1, byteArrayOf(0x11, 0x88.toByte())),
            )
            var t = 7_000_000L
            repeat(frames) { i ->
                w.writeVideo(jpeg(i), t, keyframe = true)
                if (i % 2 == 0) w.writeAudio(ByteArray(40) { 3 }, t + 5_000)
                t += 33_333
            }
            if (close) w.close() else w.flush()
        }
        return file
    }

    @Test
    fun readsBackTracksBlocksAndPayloads() {
        val file = writeFile(frames = 90, close = true) // 3 s: three clusters
        try {
            RandomAccessFile(file, "r").use { raf ->
                val r = MkvReader(raf.channel)
                val v = r.videoTrack()
                assertNotNull(v)
                assertEquals(MkvWriter.CODEC_MJPEG, v!!.codecId)
                assertEquals(1920, v.width)
                assertEquals(1080, v.height)
                assertEquals(8, v.pixelCropBottom)
                val a = r.audioTrack()!!
                assertEquals("A_AAC", a.codecId)
                assertArrayEquals(byteArrayOf(0x11, 0x88.toByte()), a.codecPrivate)
                assertEquals(48000.0, a.sampleRate, 0.0)
                assertEquals(1, a.channels)

                val video = r.blocks.filter { it.track == 1 }
                assertEquals(90, video.size)
                assertEquals(45, r.blocks.count { it.track == 2 })
                video.forEachIndexed { i, b ->
                    assertEquals(i * 33_333L / 1000, b.timeUs / 1000) // ms ticks, first frame = 0
                    assertTrue(b.keyframe)
                    assertArrayEquals(jpeg(i), r.read(b).copyOf(b.size))
                }
                assertTrue((r.durationMs ?: 0.0) > 2900.0)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsAFileThatWasNeverClosed() {
        // flush() without close(): Segment and the last Cluster keep the unknown-size marker.
        val file = writeFile(frames = 75, close = false)
        try {
            RandomAccessFile(file, "r").use { raf ->
                val r = MkvReader(raf.channel)
                val video = r.blocks.filter { it.track == 1 }
                assertEquals(75, video.size)
                assertArrayEquals(jpeg(74), r.read(video.last()).copyOf(video.last().size))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun dropsATruncatedLastBlock() {
        val file = writeFile(frames = 40, close = false)
        try {
            RandomAccessFile(file, "rw").use { it.setLength(it.length() - 100) } // cut into the last frame
            RandomAccessFile(file, "r").use { raf ->
                val r = MkvReader(raf.channel)
                val video = r.blocks.filter { it.track == 1 }
                assertTrue(video.size in 38..39)
                video.forEach { b -> assertTrue(b.offset + b.size <= file.length()) }
            }
        } finally {
            file.delete()
        }
    }
}
