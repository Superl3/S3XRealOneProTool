package com.raphael.handmouse.enhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import com.raphael.handmouse.imu.GyroSeries
import com.raphael.handmouse.imu.ImuCameraCalibration
import com.raphael.handmouse.recording.MkvReader
import com.raphael.handmouse.recording.MkvWriter
import com.raphael.handmouse.recording.RecordingOutput
import com.raphael.handmouse.util.Prefs
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns an MJPEG recording (`XrealEye_….mkv`) into `XrealEye_…_enhanced.mp4` next to it
 * (2026-09-29, started by hand from the main screen, [EnhanceService]).
 *
 * 1. **Analysis** (CPU, parallel): every frame decoded at 1/4 size → brightness percentiles and
 *    the global frame-to-frame motion ([MotionEstimator]).
 * 2. **Plans**: with the recording's `.gcsv`, the IMU→camera fit ([ImuCameraCalibration.fit]) —
 *    stored for the cursor's head compensation when it beats the stored one — and, when it is
 *    good enough, the stabilization follows the gyro instead of the image motion;
 *    [StabilizationPath], [TonePlan]; timestamps snapped onto a grid at the recording's mean
 *    frame rate (the MKV's are USB arrival times, in ms).
 * 3. **Render**: full-size decode (2 threads, a few frames ahead) → [EnhanceRenderer] → HEVC
 *    5 Mbit/s VBR (AVC 8 Mbit/s where there is no HEVC encoder) → MP4, the AAC track copied.
 * 4. **Check**: the MP4 is read back (video track, size, duration, sample count); only then is it
 *    published and — when [deleteOriginal] — the MKV and its `.gcsv` deleted.
 *
 * HEVC recordings are skipped (their frames would need a decoder pass of their own; the JPEG
 * artefacts this works on are also specific to MJPEG). Frames that do not decode are left out.
 *
 * Blocking; runs on one thread for its whole length (the GL context lives on it).
 */
class VideoEnhancer(private val context: Context) {

    companion object {
        private const val TAG = "VideoEnhancer"
        const val HEVC_BITRATE = 5_000_000
        const val AVC_BITRATE = 8_000_000
        private const val ANALYSIS_SAMPLE = 4
        private const val DECODE_AHEAD = 3
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val EOS_TIMEOUT_MS = 10_000L
        /** Share of the progress bar the analysis takes (it is the faster half). */
        const val ANALYSIS_SHARE = 0.3f

        fun outputName(recordingName: String): String =
            recordingName.substringBeforeLast('.') + RecordingOutput.ENHANCED_SUFFIX + ".mp4"

        /**
         * The frame interval of the output grid, µs: the mean of the regular intervals (stalls —
         * over twice the median — left out). Not the median: the MKV's times are whole ms, a
         * 29.6 fps recording's median came out as 34 ms = 29 fps, and every frame after that was
         * pushed onto the next free slot — the S25 Edge outputs ran 0.7–1.9 % long (2026-09-29).
         */
        internal fun frameIntervalUs(timesUs: LongArray): Double {
            val d = LongArray(maxOf(timesUs.size - 1, 0)) { timesUs[it + 1] - timesUs[it] }.filter { it > 0 }.sorted()
            if (d.isEmpty()) return 1e6 / 30
            val median = d[d.size / 2]
            return d.filter { it <= 2 * median }.average().coerceIn(1e6 / 120, 1e6)
        }

        /** [timesUs] moved onto the [stepUs] grid, strictly increasing (one frame per slot). */
        internal fun snapTimes(timesUs: LongArray, stepUs: Double): LongArray {
            val out = LongArray(timesUs.size)
            var lastSlot = -1L
            for (i in timesUs.indices) {
                val slot = maxOf((timesUs[i] / stepUs).roundToLong(), lastSlot + 1)
                out[i] = (slot * stepUs).roundToLong()
                lastSlot = slot
            }
            return out
        }
    }

    sealed class Outcome {
        class Done(
            val location: String,
            val frames: Int,
            val droppedFrames: Int,
            val sizeBytes: Long,
            val seconds: Float,
            val codec: String,
            /** "gyro", "image" or "off". */
            val stabilization: String,
            val calibration: ImuCameraCalibration?,
            val calibrationStored: Boolean,
            val deletedOriginal: Boolean,
        ) : Outcome()

        class Skipped(val reason: String) : Outcome()
        class Failed(val reason: String) : Outcome()
        object Cancelled : Outcome()
    }

    /** [fraction] 0..1 over the whole file. */
    fun interface Progress {
        fun onProgress(fraction: Float, analyzing: Boolean)
    }

    private class CancelledException : Exception()

    private val output = RecordingOutput(context)

    fun enhance(
        recording: RecordingOutput.Recording,
        deleteOriginal: Boolean,
        isCancelled: () -> Boolean,
        progress: Progress,
    ): Outcome {
        val started = SystemClock.elapsedRealtime()
        val pfd = try {
            output.openRead(recording)
        } catch (e: Exception) {
            return Outcome.Failed("cannot open: ${e.message}")
        }
        pfd.use {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val reader = try {
                MkvReader(channel)
            } catch (e: Exception) {
                return Outcome.Failed("not a readable MKV: ${e.message}")
            }
            val video = reader.videoTrack() ?: return Outcome.Failed("no video track")
            if (video.codecId != MkvWriter.CODEC_MJPEG) return Outcome.Skipped("HEVC recording (only MJPEG ones are enhanced)")
            val frames = reader.blocks.filter { it.track == video.number }
            if (frames.size < 2) return Outcome.Failed("fewer than 2 frames")
            val audio = reader.audioTrack()?.takeIf { it.codecId == "A_AAC" && it.codecPrivate != null }
            val audioBlocks = audio?.let { a -> reader.blocks.filter { it.track == a.number } } ?: emptyList()

            val name = outputName(recording.displayName)
            val durationUs = frames.last().timeUs - frames.first().timeUs
            val needBytes = durationUs / 1_000_000.0 * AVC_BITRATE / 8 * 1.2 + 200e6
            if (output.freeBytes(recording.storage) < needBytes) return Outcome.Failed("not enough free space")

            return try {
                run(recording, reader, video, frames, audio, audioBlocks, name, deleteOriginal, isCancelled, progress, started)
            } catch (_: CancelledException) {
                Outcome.Cancelled
            }
        }
    }

    private fun run(
        recording: RecordingOutput.Recording,
        reader: MkvReader,
        video: MkvReader.Track,
        frames: List<MkvReader.Block>,
        audio: MkvReader.Track?,
        audioBlocks: List<MkvReader.Block>,
        name: String,
        deleteOriginal: Boolean,
        isCancelled: () -> Boolean,
        progress: Progress,
        started: Long,
    ): Outcome {
        val n = frames.size
        val srcW = video.width
        val srcH = video.height
        val usableH = (srcH - video.pixelCropBottom).coerceIn(2, srcH)
        val outW = srcW and 1.inv()
        val outH = srcH and 1.inv()
        val timesUs = LongArray(n) { frames[it].timeUs - frames[0].timeUs }

        // ---- 1. analysis ----
        val stats = arrayOfNulls<FloatArray>(n)
        val motion = arrayOfNulls<FloatArray>(n)
        analyze(reader, frames, srcW, usableH, stats, motion, isCancelled) { done ->
            progress.onProgress(ANALYSIS_SHARE * done / n, true)
        }
        val analysed = stats.count { it != null }
        if (analysed < 2) return Outcome.Failed("no frame could be decoded")
        Log.i(TAG, "$name: analysed $analysed/$n frames, motion for ${motion.count { it != null }}")

        // ---- 2. plans ----
        val gyro: GyroSeries? = try {
            output.openSidecar(recording.displayName)?.use { GcsvReader.read(it) }
        } catch (e: Exception) {
            Log.w(TAG, "gyro log unreadable: ${e.message}")
            null
        }
        var calibration: ImuCameraCalibration? = null
        var stored = false
        if (gyro != null) {
            calibration = ImuCameraCalibration.fit(timesUs, motion, gyro)
            if (calibration == null) {
                Log.i(TAG, "$name: no IMU fit (${ImuCameraCalibration.lastFailure})")
            } else {
                Log.i(TAG, "$name: IMU fit $calibration")
                val prefs = Prefs(context)
                val current = prefs.headCalibration
                if (calibration.r2 >= ImuCameraCalibration.MIN_R2_STORE && (current == null || calibration.r2 > current.r2)) {
                    prefs.headCalibration = calibration
                    stored = true
                }
            }
        }
        val gyroMotion: Array<FloatArray?>? = calibration?.takeIf { it.r2 >= ImuCameraCalibration.MIN_R2_STABILIZE }?.let { cal ->
            val lUs = cal.latencyMs * 1000L
            Array(n) { i ->
                if (i == 0) null
                else gyro!!.integrate(timesUs[i - 1] - lUs, timesUs[i] - lUs)
                    ?.let { cal.motion(it, (timesUs[i] - timesUs[i - 1]) / 1e6f, withBias = true) }
            }
        }
        val pathMotion = if (gyroMotion != null) Array(n) { gyroMotion[it] ?: motion[it] } else motion
        val path = StabilizationPath.plan(pathMotion, srcW, usableH, outH)
        val stabilization = when {
            !path.active -> "off"
            gyroMotion != null -> "gyro"
            else -> "image"
        }
        val tone = TonePlan.build(stats)
        val stepUs = frameIntervalUs(timesUs)
        val fps = (1e6 / stepUs).roundToInt().coerceIn(1, 120)
        val ptsUs = snapTimes(timesUs, stepUs)

        // ---- 3. render ----
        val encoder = createEncoder(outW, outH, fps)
        val out = output.create(
            recording.storage,
            if (recording.storage == RecordingOutput.Storage.APP) name + RecordingOutput.PART_EXT else name,
            "video/mp4",
        )
        var muxer: MediaMuxer? = null
        var renderer: EnhanceRenderer? = null
        val decodePool = Executors.newFixedThreadPool(2)
        var ok = false
        try {
            muxer = MediaMuxer(out.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val inputSurface = encoder.codec.createInputSurface()
            encoder.codec.start()
            renderer = EnhanceRenderer(inputSurface, srcW, srcH, usableH, outW, outH)
            val sink = Sink(muxer, encoder.codec, reader, audio, audioBlocks)
            val bitmaps = ConcurrentLinkedQueue<Bitmap>()
            val readBuf = ThreadLocal<ByteArray>()
            fun decode(i: Int): Future<Bitmap?> = decodePool.submit<Bitmap?> {
                val block = frames[i]
                val buf = reader.read(block, readBuf.get()).also { readBuf.set(it) }
                val opts = BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inBitmap = bitmaps.poll()
                }
                val bmp = try {
                    BitmapFactory.decodeByteArray(buf, 0, block.size, opts)
                } catch (_: IllegalArgumentException) {
                    opts.inBitmap?.let { bitmaps.add(it) }
                    null
                }
                bmp?.takeIf { it.width == srcW && it.height == srcH }
            }
            val pending = ArrayDeque<Future<Bitmap?>>()
            var next = 0
            var lastRendered = -1
            var rendered = 0
            for (i in 0 until n) {
                if (isCancelled()) throw CancelledException()
                while (next < n && next <= i + DECODE_AHEAD) pending.addLast(decode(next++))
                val bmp = pending.removeFirst().get()
                if (bmp == null) {
                    lastRendered = -1
                    continue
                }
                val m = if (lastRendered == i - 1) (motion[i] ?: gyroMotion?.get(i)) else null
                renderer.render(
                    bmp,
                    m?.let { floatArrayOf(it[0] * srcW, it[1] * srcW, it[2]) },
                    floatArrayOf(path.ux[i], path.uy[i], path.phi[i]),
                    path.zoom,
                    floatArrayOf(tone.black[i], tone.white[i], tone.gamma[i]),
                    ptsUs[i] * 1000,
                )
                bitmaps.add(bmp)
                lastRendered = i
                rendered++
                sink.drain(false)
                if (i % 15 == 0) progress.onProgress(ANALYSIS_SHARE + (1 - ANALYSIS_SHARE) * i / n, false)
            }
            if (rendered == 0) return Outcome.Failed("no frame could be decoded")
            encoder.codec.signalEndOfInputStream()
            sink.drain(true)
            sink.finishAudio()
            muxer.stop()
            muxer.release()
            muxer = null

            // ---- 4. check ----
            // against the recording's own span, not the snapped times: a grid slower than the
            // frames stretches the output and would pass a check against itself
            val expectedUs = timesUs.last() - timesUs.first()
            val problem = verify(out.fileDescriptor, outW, outH, expectedUs, sink.videoSamples)
            if (problem != null) return Outcome.Failed("output check failed: $problem")
            ok = true
            out.finish()
            var location = out.location
            var size = sink.bytes
            if (recording.storage == RecordingOutput.Storage.APP) {
                val part = out.file!!
                val final = File(part.parentFile, name)
                if (!part.renameTo(final)) return Outcome.Failed("could not rename ${part.name}").also { part.delete() }
                location = final.absolutePath
                size = final.length()
            }
            val deleted = deleteOriginal && output.delete(recording)
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000f
            Log.i(TAG, "$name: $rendered/$n frames, ${encoder.label}, stabilization $stabilization, ${seconds}s" +
                (if (deleted) ", original deleted" else ""))
            return Outcome.Done(location, rendered, n - rendered, size, seconds, encoder.label, stabilization, calibration, stored, deleted)
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            decodePool.shutdownNow()
            try { renderer?.close() } catch (_: Exception) {}
            try { encoder.codec.stop() } catch (_: Exception) {}
            try { encoder.codec.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            if (!ok) out.abort()
        }
    }

    /** Pass 1: [stats] and [motion] for every frame, split over a few threads (each decodes the
     * frame before its range too, for that range's first motion). */
    private fun analyze(
        reader: MkvReader,
        frames: List<MkvReader.Block>,
        srcW: Int,
        usableH: Int,
        stats: Array<FloatArray?>,
        motion: Array<FloatArray?>,
        isCancelled: () -> Boolean,
        onDone: (Int) -> Unit,
    ) {
        val n = frames.size
        val aw = srcW / ANALYSIS_SAMPLE
        val ah = (usableH / ANALYSIS_SAMPLE) and 1.inv()
        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(workers)
        val done = AtomicInteger(0)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val jobs = (0 until workers).map { w ->
                val start = n * w / workers
                val end = n * (w + 1) / workers
                pool.submit {
                    val estimator = MotionEstimator(aw, ah)
                    var prev = ByteArray(aw * ah)
                    var cur = ByteArray(aw * ah)
                    val hist = IntArray(256)
                    val pixels = IntArray(aw * ah)
                    var prevOk = false
                    var predict: MotionEstimator.Result? = null
                    var buf: ByteArray? = null
                    var bmp: Bitmap? = null
                    for (i in maxOf(0, start - 1) until end) {
                        if (cancelled.get()) return@submit
                        val block = frames[i]
                        var ok = false
                        try {
                            buf = reader.read(block, buf)
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = ANALYSIS_SAMPLE
                                inMutable = true
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inBitmap = bmp
                            }
                            val b = BitmapFactory.decodeByteArray(buf, 0, block.size, opts)
                            if (b != null && b.width >= aw && b.height >= ah) {
                                bmp = b
                                b.getPixels(pixels, 0, aw, 0, 0, aw, ah)
                                Luma.fromArgb(pixels, aw, ah, cur, hist)
                                ok = true
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "frame $i: ${e.message}")
                        }
                        if (i >= start) {
                            if (ok) {
                                stats[i] = Luma.percentiles(hist, 0.01f, 0.5f, 0.99f)
                                if (prevOk) {
                                    val r = estimator.estimate(prev, cur, predict)
                                    predict = r
                                    motion[i] = r?.let { floatArrayOf(it.dx, it.dy, it.dTheta) }
                                }
                            }
                            onDone(done.incrementAndGet())
                        }
                        if (!ok) predict = null
                        prevOk = ok
                        val t = prev; prev = cur; cur = t
                    }
                }
            }
            for (job in jobs) {
                while (true) {
                    if (isCancelled()) {
                        cancelled.set(true)
                        throw CancelledException()
                    }
                    try {
                        job.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        break
                    } catch (_: java.util.concurrent.TimeoutException) {
                    }
                }
            }
        } finally {
            cancelled.set(true)
            pool.shutdownNow()
        }
    }

    private class Encoder(val codec: MediaCodec, val label: String)

    private fun createEncoder(width: Int, height: Int, fps: Int): Encoder {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for ((mime, bitrate) in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC to HEVC_BITRATE, MediaFormat.MIMETYPE_VIDEO_AVC to AVC_BITRATE)) {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val codecName = list.findEncoderForFormat(format) ?: continue
            val vbr = list.codecInfos.firstOrNull { it.name == codecName }
                ?.getCapabilitiesForType(mime)?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
            if (vbr) format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            val codec = MediaCodec.createByCodecName(codecName)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "$codecName refused $format: ${e.message}")
                codec.release()
                continue
            }
            val label = "${if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "AVC"} ${bitrate / 1_000_000} Mbit/s ($codecName)"
            return Encoder(codec, label)
        }
        throw IllegalStateException("no usable HEVC/AVC encoder for ${width}x$height")
    }

    /** Encoder output → muxer; the audio blocks are interleaved by time as the video advances. */
    private inner class Sink(
        private val muxer: MediaMuxer,
        private val codec: MediaCodec,
        private val reader: MkvReader,
        private val audio: MkvReader.Track?,
        private val audioBlocks: List<MkvReader.Block>,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var videoTrack = -1
        private var audioTrack = -1
        private var nextAudio = 0
        private var audioBuf: ByteArray? = null
        private val audioInfo = MediaCodec.BufferInfo()
        var videoSamples = 0
            private set
        var bytes = 0L
            private set

        fun drain(endOfStream: Boolean) {
            var idleSince = SystemClock.elapsedRealtime()
            while (true) {
                val index = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (SystemClock.elapsedRealtime() - idleSince > EOS_TIMEOUT_MS) throw IllegalStateException("encoder did not finish")
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(videoTrack < 0) { "encoder format changed twice" }
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        if (audio != null) {
                            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate.roundToInt(), audio.channels)
                            f.setByteBuffer("csd-0", ByteBuffer.wrap(audio.codecPrivate!!))
                            audioTrack = muxer.addTrack(f)
                        }
                        muxer.start()
                    }
                    index >= 0 -> {
                        idleSince = SystemClock.elapsedRealtime()
                        val buf = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            check(videoTrack >= 0) { "encoder output before its format" }
                            writeAudioUntil(info.presentationTimeUs)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                            videoSamples++
                            bytes += info.size
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finishAudio() = writeAudioUntil(Long.MAX_VALUE)

        private fun writeAudioUntil(timeUs: Long) {
            if (audioTrack < 0) return
            while (nextAudio < audioBlocks.size && audioBlocks[nextAudio].timeUs <= timeUs) {
                val block = audioBlocks[nextAudio++]
                if (block.timeUs < 0) continue
                val data = reader.read(block, audioBuf).also { audioBuf = it }
                audioInfo.set(0, block.size, block.timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(data, 0, block.size), audioInfo)
                bytes += block.size
            }
        }
    }

    /** Null when the written MP4 reads back as expected, else what is wrong. */
    private fun verify(fd: java.io.FileDescriptor, width: Int, height: Int, expectedUs: Long, samplesWritten: Int): String? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(fd)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return "no video track"
            val f = ex.getTrackFormat(track)
            if (f.getInteger(MediaFormat.KEY_WIDTH) != width || f.getInteger(MediaFormat.KEY_HEIGHT) != height) {
                return "size ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}"
            }
            if (f.containsKey(MediaFormat.KEY_DURATION)) {
                val d = f.getLong(MediaFormat.KEY_DURATION)
                if (abs(d - expectedUs) > 1_000_000) return "duration ${d / 1000} ms, expected ${expectedUs / 1000} ms"
            }
            ex.selectTrack(track)
            var samples = 0
            while (ex.sampleTime >= 0) {
                samples++
                ex.advance()
            }
            if (samples < samplesWritten * 0.99) return "$samples of $samplesWritten frames readable"
            return null
        } catch (e: Exception) {
            return "unreadable: ${e.message}"
        } finally {
            ex.release()
        }
    }
}
