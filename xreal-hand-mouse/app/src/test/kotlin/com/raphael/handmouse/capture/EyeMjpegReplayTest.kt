package com.raphael.handmouse.capture

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays real Eye JPEGs (extracted losslessly from a recording: `ffmpeg -i rec.mkv -c:v copy
 * frames/%05d.jpg`) through the USB framing the camera uses — ONE UVC payload per frame, a 2-byte
 * header, bulk reads of 65536 bytes — and checks that FrameAssembler + MjpegStreamAssembler give
 * every JPEG back byte for byte. Skipped unless EYE_FRAMES_DIR points at such a directory.
 *
 * With readSize = Int.MAX_VALUE the assembler treats every read as a payload start, which is the
 * behaviour before 2026-09-29; that run is reported (not asserted) to show what it broke.
 */
class EyeMjpegReplayTest {

    private val dir = System.getenv("EYE_FRAMES_DIR")?.let { File(it) }

    /** Streams [files] through the camera's framing; returns how many JPEGs did not come back intact. */
    private fun replay(files: List<File>, readSize: Int): Int {
        val framer = FrameAssembler(readSize = readSize)
        val mjpeg = MjpegStreamAssembler()
        val expected = ArrayDeque<ByteArray>()
        var mismatches = 0
        fun collect(chunk: ByteArray) {
            for (frame in mjpeg.feed(chunk)) {
                // Outputs come in input order; one that differs from the oldest pending input is
                // that input, broken.
                val want = expected.removeFirstOrNull()
                if (want == null || !want.contentEquals(frame)) mismatches++
            }
        }
        var fid = 0
        for (file in files) {
            val jpeg = file.readBytes()
            expected.addLast(jpeg)
            val payload = byteArrayOf(0x02, (0x80 or 0x02 or fid).toByte()) + jpeg // EOH + EOF + FID
            fid = fid xor 1
            var i = 0
            while (i < payload.size) {
                val n = minOf(65_536, payload.size - i)
                for (chunk in framer.offerPayload(payload.copyOfRange(i, i + n), n)) collect(chunk)
                i += n
            }
            // A payload that is an exact multiple of the read size ends with a zero-length read.
            if (payload.size % 65_536 == 0) for (chunk in framer.offerPayload(ByteArray(0), 0)) collect(chunk)
        }
        return mismatches + expected.size
    }

    @Test
    fun realFramesSurviveUsbFraming() {
        val files = dir?.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }
        assumeTrue(!files.isNullOrEmpty())
        val before = replay(files!!, readSize = Int.MAX_VALUE)
        val after = replay(files, readSize = 65_536)
        println("EyeMjpegReplayTest: ${files.size} frames, ${files.count { it.length() > 65_534 }} span >1 read; " +
            "not intact before fix = $before, after fix = $after")
        assertEquals(0, after)
    }
}
