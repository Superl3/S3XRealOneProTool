package com.raphael.handmouse.recording

import com.raphael.handmouse.capture.MjpegStreamAssembler
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * End-to-end check of the recorder's remux path against REAL encoder output, without a device:
 * an ffmpeg-generated HEVC Annex-B stream / MJPEG stream is cut into random-size chunks (like the
 * UVC bulk reads), reassembled and written to MKV exactly as [EyeRecorder] does. The resulting
 * files are then validated with ffprobe/ffmpeg outside the test.
 *
 * Opt-in: runs only when EYE_E2E_DIR points to a folder containing `in.hevc` and/or `in.mjpeg`.
 */
class RecorderPipelineE2ETest {

    private val dir = System.getenv("EYE_E2E_DIR")?.let { File(it) }

    private fun chunks(data: ByteArray, rnd: Random): Sequence<ByteArray> = sequence {
        var i = 0
        while (i < data.size) {
            val n = minOf(1 + rnd.nextInt(65534), data.size - i)
            yield(data.copyOfRange(i, i + n))
            i += n
        }
    }

    @Test
    fun hevcAnnexBToMkv() {
        val input = dir?.let { File(it, "in.hevc") }
        assumeTrue(input?.exists() == true)
        val data = input!!.readBytes()
        val asm = HevcAccessUnitAssembler()
        val aus = ArrayList<HevcAccessUnitAssembler.AccessUnit>()
        var t = 0L
        for (c in chunks(data, Random(1))) aus += asm.feed(c, t.also { t += 5000 })
        aus += asm.flush()

        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in aus.first().nals) when (HevcAccessUnitAssembler.nalType(nal)) {
            32 -> vps = nal
            33 -> sps = nal
            34 -> pps = nal
        }
        val info = HevcParameterSets.parseSps(sps!!)!!
        val hvcc = HevcParameterSets.buildHvcC(vps!!, sps, pps!!)!!
        val out = File(dir, "out_hevc.mkv")
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val w = MkvWriter(raf.channel, MkvWriter.VideoTrack(MkvWriter.CODEC_HEVC, info.width, info.height, hvcc))
            aus.forEachIndexed { i, au -> w.writeHevcAu(au.nals, i * 16_667L, au.isIrap) }
            w.close()
        }
        println("HEVC: ${aus.size} AUs, ${aus.count { it.isIrap }} IRAP, ${info.width}x${info.height} reorder=${info.maxNumReorderPics} -> $out")
    }

    @Test
    fun mjpegToMkv() {
        val input = dir?.let { File(it, "in.mjpeg") }
        assumeTrue(input?.exists() == true)
        val data = input!!.readBytes()
        val asm = MjpegStreamAssembler(maxBufferBytes = 4 * 1024 * 1024)
        val frames = ArrayList<ByteArray>()
        for (c in chunks(data, Random(2))) frames += asm.feed(c)
        val size = JpegInfo.size(frames.first())!!
        val out = File(dir, "out_mjpeg.mkv")
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val w = MkvWriter(raf.channel, MkvWriter.VideoTrack(MkvWriter.CODEC_MJPEG, size.width, size.height))
            frames.forEachIndexed { i, f -> w.writeVideo(f, i * 16_667L, true) }
            w.close()
        }
        println("MJPEG: ${frames.size} frames ${size.width}x${size.height} -> $out")

        // Crash simulation: the process "dies" after 200 frames — no close(), only the periodic
        // flush the writer does at cluster boundaries. The file must still be playable.
        val crash = File(dir, "out_crash.mkv")
        RandomAccessFile(crash, "rw").use { raf ->
            raf.setLength(0)
            val w = MkvWriter(raf.channel, MkvWriter.VideoTrack(MkvWriter.CODEC_MJPEG, size.width, size.height))
            frames.take(200).forEachIndexed { i, f -> w.writeVideo(f, i * 16_667L, true) }
            w.flush()
        }
        println("CRASH: 200 frames written without close -> $crash")
    }
}
