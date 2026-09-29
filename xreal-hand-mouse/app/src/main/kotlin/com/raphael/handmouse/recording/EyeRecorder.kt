package com.raphael.handmouse.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.raphael.handmouse.capture.MjpegStreamAssembler
import com.raphael.handmouse.imu.ImuSample
import java.nio.channels.Channels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Long-duration Eye recorder — the "second branch" off the single camera stream:
 *
 * ```
 *                    ┌→ decoder → MediaPipe → gestures   (EyeCaptureService, unchanged)
 * Eye UVC stream ────┤
 *                    └→ EyeRecorder → MKV segments (this class, no decode/encode)
 * ```
 *
 * The camera is opened once; the recorder receives the SAME byte chunks the decoders get
 * ([onChunk], called on the USB thread — it only enqueues) and remuxes them on its own thread:
 * - HEVC stream → [HevcAccessUnitAssembler] → [MkvWriter] (hvcC, length-prefixed NALs).
 * - MJPEG stream → [MjpegStreamAssembler] → [MkvWriter] (`V_MJPEG`, JPEGs stored untouched).
 * Nothing is decoded or re-encoded, so recording adds almost no heat on top of tracking.
 *
 * ## Robustness for multi-hour rides
 * - **Segments**: a new file every N minutes (HEVC: at the next keyframe after the deadline).
 * - **Stream restarts / format changes**: a restart resets the assemblers and waits for the next
 *   HEVC keyframe; a format or parameter-set change starts a new segment. After [IDLE_FINALIZE_MS]
 *   without data (glasses unplugged) the segment is closed so it is safe on disk; recording
 *   resumes in a new segment when the stream returns.
 * - **Back-pressure**: the USB thread never blocks. Past [MAX_QUEUED_BYTES] chunks are dropped and
 *   the stream is re-synchronized (next keyframe).
 * - **Storage**: below [MIN_FREE_BYTES] the oldest finished recordings are deleted (loop
 *   recording, like a dash cam); recording only stops if nothing is left to delete.
 * - **Write errors**: [MAX_FAILURES] failed segments within [FAILURE_WINDOW_MS] stop recording
 *   instead of filling the gallery with broken files.
 * - Idle (not recording) the thread blocks on the queue: no wake-ups, no status traffic.
 */
class EyeRecorder(context: Context, private val listener: Listener) {

    companion object {
        private const val TAG = "EyeRecorder"
        const val FORMAT_MJPEG = 0x06
        const val FORMAT_HEVC = 0x10

        private const val MAX_QUEUED_BYTES = 48L * 1024 * 1024
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val IDLE_FINALIZE_MS = 20_000L
        private const val STATUS_INTERVAL_MS = 1000L
        private const val STORAGE_CHECK_INTERVAL_MS = 10_000L
        private const val MAX_FAILURES = 3
        private const val FAILURE_WINDOW_MS = 60_000L

        /** AudioSpecificConfig of [AudioCapture]'s fixed format: AAC-LC, 48 kHz, mono. */
        private val AAC_LC_48K_MONO = byteArrayOf(0x11, 0x88.toByte())
    }

    data class Config(
        val splitMinutes: Int,
        val audio: Boolean,
        val storage: RecordingOutput.Storage,
        /** MJPEG frames are dropped above this rate (30 halves the size: ~6.5 GB/h at 1080p). */
        val maxFps: Int = 30,
        /** Write the glasses' IMU next to each segment as a Gyroflow `.gcsv` ([GyroLog]). */
        val gyroLog: Boolean = false,
    )

    data class Status(
        val recording: Boolean = false,
        val sessionStartElapsedMs: Long = 0,
        val segmentIndex: Int = 0,
        val currentFile: String? = null,
        val lastFinishedFile: String? = null,
        val lastFinishedUri: android.net.Uri? = null,
        val sessionBytes: Long = 0,
        val droppedChunks: Long = 0,
        val codecLabel: String = "",
        val waitingForStream: Boolean = false,
        val audioActive: Boolean = false,
        val error: String? = null,
    )

    fun interface Listener {
        /** Called on the recorder thread. */
        fun onRecorderStatus(status: Status)
    }

    // ---- queue (USB/audio/control threads → recorder thread) ----
    private sealed class Item {
        class Chunk(val data: ByteArray, val tsUs: Long) : Item()
        class StreamStart(val format: Int, val width: Int, val height: Int) : Item()
        object Discontinuity : Item()
        class Start(val config: Config) : Item()
        object Stop : Item()
        class AudioFrame(val data: ByteArray, val ptsUs: Long) : Item()
        class AudioErr(val message: String) : Item()
        object Recover : Item()
        object Quit : Item()
    }

    private val output = RecordingOutput(context.applicationContext)
    private val queue = LinkedBlockingQueue<Item>()
    private val queuedBytes = AtomicLong(0)
    private val droppedChunks = AtomicLong(0)

    @Volatile
    private var dropping = false

    /** True between [start] and [stop] — read on the USB thread to skip work when idle. */
    @Volatile
    var isRecording = false
        private set

    // ================= public API (any thread) =================

    fun start(config: Config) {
        isRecording = true
        queue.put(Item.Start(config))
    }

    fun stop() {
        isRecording = false
        queue.put(Item.Stop)
    }

    /** USB thread: a new stream was negotiated. */
    fun onStreamStarted(format: Int, width: Int, height: Int) {
        queue.offer(Item.StreamStart(format, width, height))
    }

    /** USB thread: one chunk of the stream (same array the decoders get — never mutated). */
    fun onChunk(data: ByteArray) {
        if (!isRecording) return
        if (queuedBytes.get() + data.size > MAX_QUEUED_BYTES) {
            droppedChunks.incrementAndGet()
            if (!dropping) {
                dropping = true
                Log.w(TAG, "Recorder queue full — dropping chunks until storage catches up")
            }
            return
        }
        if (dropping) {
            dropping = false
            queue.offer(Item.Discontinuity)
        }
        queuedBytes.addAndGet(data.size.toLong())
        // offer, not put: the USB thread is interrupted on stream switches (put would throw)
        queue.offer(Item.Chunk(data, System.nanoTime() / 1000))
    }

    /** IMU thread: one glasses IMU sample ([localNs] on `System.nanoTime()`'s clock). Goes
     * straight into the open segment's [GyroLog] (synchronized), not through the queue. */
    fun onImuSample(sample: ImuSample, localNs: Long) {
        gyroLog?.add(localNs, sample.gx, sample.gy, sample.gz, sample.ax, sample.ay, sample.az)
    }

    /** Publishes recordings left pending by a previous crash. Runs on the recorder thread before
     * anything queued later, so it can never touch a segment being written. */
    fun recoverPending() {
        queue.put(Item.Recover)
    }

    fun release() {
        isRecording = false
        queue.put(Item.Stop)
        queue.put(Item.Quit)
        thread.join(3000)
    }

    // ================= recorder thread =================

    private var config: Config? = null
    private var sessionStartElapsed = 0L
    private var segmentIndex = 0
    private var sessionBytesClosed = 0L
    private var lastError: String? = null
    private var lastFinished: RecordingOutput.OutputFile? = null
    private val failureTimes = ArrayDeque<Long>()

    private var streamFormat = -1
    private var streamWidth = 0
    private var streamHeight = 0
    private val hevcAssembler = HevcAccessUnitAssembler()
    private val mjpegAssembler = MjpegStreamAssembler(maxBufferBytes = 4 * 1024 * 1024)
    private val paramSets = HevcParamSetTracker()
    /** After any discontinuity, P-frames reference pictures we never saw: skip to a keyframe. */
    private var hevcNeedKeyframe = true

    private var writer: MkvWriter? = null
    @Volatile private var gyroLog: GyroLog? = null
    private var gyroOut: RecordingOutput.OutputFile? = null
    private var writerFormat = -1
    private var writerCsd: ByteArray? = null
    private var outputFile: RecordingOutput.OutputFile? = null
    private var segmentStartElapsed = 0L
    private var lastChunkElapsed = 0L

    private var audio: AudioCapture? = null
    private var audioFailed = false

    private var lastStatusElapsed = 0L
    private var lastStorageCheckElapsed = 0L

    // Diagnostics: parser stats are logged while an HEVC stream has not opened a segment yet.
    private var auCount = 0L
    private var irapCount = 0L
    private var lastDiagElapsed = 0L
    private var lastJpegTsUs = 0L

    private fun loop() {
        while (true) {
            // idle: block until something arrives; recording: wake up for housekeeping
            val item = if (config == null) queue.take() else queue.poll(250, TimeUnit.MILLISECONDS)
            try {
                when (item) {
                    null -> {}
                    is Item.Chunk -> {
                        queuedBytes.addAndGet(-item.data.size.toLong())
                        onChunkInternal(item.data, item.tsUs)
                    }
                    is Item.StreamStart -> onStreamStartInternal(item)
                    Item.Discontinuity -> resync("queue overflow")
                    is Item.Start -> startInternal(item.config)
                    Item.Stop -> stopInternal(null)
                    is Item.AudioFrame -> writer?.let { if (it.acceptsAudio) it.writeAudio(item.data, item.ptsUs) }
                    is Item.AudioErr -> {
                        Log.w(TAG, "Audio error: ${item.message}")
                        audioFailed = true
                        lastError = item.message
                        audio?.stop()
                        audio = null
                    }
                    Item.Recover -> output.recoverPending().let {
                        if (it > 0) Log.i(TAG, "Recovered $it recording(s) left pending by a previous crash")
                    }
                    Item.Quit -> return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recorder error", e)
                failSegment("Recorder error: ${e.message}")
            }
            housekeeping()
        }
    }

    private fun startInternal(cfg: Config) {
        if (config != null) return
        config = cfg
        sessionStartElapsed = SystemClock.elapsedRealtime()
        segmentIndex = 0
        sessionBytesClosed = 0
        lastError = null
        audioFailed = false
        failureTimes.clear()
        resync("start")
        if (cfg.audio) {
            audio = AudioCapture(object : AudioCapture.Sink {
                override fun onAudioFrame(data: ByteArray, ptsUs: Long) {
                    if (isRecording) queue.put(Item.AudioFrame(data, ptsUs))
                }
                override fun onAudioError(message: String) { queue.put(Item.AudioErr(message)) }
            }).also { it.start() }
        }
        if (!ensureFreeSpace(cfg)) {
            stopInternal("Not enough free storage to record")
            return
        }
        Log.i(TAG, "Recording started: $cfg")
        publishStatus()
    }

    private fun stopInternal(error: String?) {
        if (config == null) return
        finalizeSegment()
        audio?.stop()
        audio = null
        config = null
        isRecording = false
        if (error != null) lastError = error
        Log.i(TAG, "Recording stopped${error?.let { " ($it)" } ?: ""}")
        publishStatus()
    }

    private fun onStreamStartInternal(s: Item.StreamStart) {
        if (streamFormat != -1 && s.format != streamFormat) finalizeSegment() // other codec → other file
        streamFormat = s.format
        streamWidth = s.width
        streamHeight = s.height
        resync("stream (re)start")
        auCount = 0
        irapCount = 0
    }

    /** Drops partial data; HEVC waits for the next keyframe (checked in [onHevcAu]). */
    private fun resync(reason: String) {
        hevcAssembler.reset()
        mjpegAssembler.reset()
        hevcNeedKeyframe = true
        Log.d(TAG, "Resync ($reason)")
    }

    private fun onChunkInternal(data: ByteArray, tsUs: Long) {
        if (config == null) return
        lastChunkElapsed = SystemClock.elapsedRealtime()
        when (streamFormat) {
            FORMAT_MJPEG -> for (jpeg in mjpegAssembler.feed(data)) onJpeg(jpeg, tsUs)
            FORMAT_HEVC -> {
                for (au in hevcAssembler.feed(data, tsUs)) {
                    auCount++
                    if (au.isIrap) irapCount++
                    onHevcAu(au)
                }
            }
            else -> {} // no stream announced yet
        }
    }

    private fun onJpeg(jpeg: ByteArray, tsUs: Long) {
        val cfg = config ?: return
        // frame-rate cap (the Eye sends ~60 fps MJPEG); 4 ms slack absorbs arrival jitter
        if (tsUs - lastJpegTsUs < 1_000_000L / cfg.maxFps - 4_000) return
        lastJpegTsUs = tsUs
        if (writer == null || writerFormat != FORMAT_MJPEG || segmentDue(cfg)) {
            finalizeSegment()
            val size = JpegInfo.size(jpeg) ?: JpegInfo.Size(streamWidth, streamHeight)
            // The Eye's 1920x1080 USB JPEGs have eight sensor-noise rows at the bottom.
            // Matroska crop metadata hides them without decoding or re-encoding the stream.
            val cropBottom = if (size.width == 1920 && size.height == 1080) 8 else 0
            if (!openSegment(cfg, FORMAT_MJPEG, null, MkvWriter.VideoTrack(
                    MkvWriter.CODEC_MJPEG, size.width, size.height, pixelCropBottom = cropBottom,
                ))) return
        }
        writer?.writeVideo(jpeg, tsUs, keyframe = true)
        gyroLog?.setBase(tsUs) // the segment's t=0, same as the MKV's
    }

    private fun onHevcAu(au: HevcAccessUnitAssembler.AccessUnit) {
        val cfg = config ?: return
        if (hevcNeedKeyframe) {
            if (!au.isIrap) return
            hevcNeedKeyframe = false
        }
        paramSets.update(au)
        val csd = paramSets.csd ?: return

        // csd is a new object only when a parameter set really changed (see HevcParamSetTracker)
        val needNew = writer == null || writerFormat != FORMAT_HEVC || csd !== writerCsd || segmentDue(cfg)
        if (needNew) {
            if (!au.isIrap) {
                // A file can only start on a keyframe; keep writing the current one meanwhile.
                if (writer == null || writerFormat != FORMAT_HEVC) return
            } else {
                finalizeSegment()
                if (!openHevcSegment(cfg, csd)) return
            }
        }
        writer?.writeHevcAu(au.nals, au.timestampUs, au.isIrap)
        gyroLog?.setBase(au.timestampUs)
    }

    private fun openHevcSegment(cfg: Config, csd: ByteArray): Boolean {
        val sps = paramSets.sps ?: return false
        val info = HevcParameterSets.parseSps(sps)
        if (info != null && info.maxNumReorderPics > 0) {
            // Timestamps are taken from arrival (decode) order, which is only right without
            // reordering. Flag it loudly — the Eye encoder is not expected to use B-frames.
            lastError = "HEVC stream uses frame reordering (B-frames: ${info.maxNumReorderPics}) — playback timing may stutter; prefer MJPEG"
            Log.w(TAG, lastError!!)
        }
        val hvcc = HevcParameterSets.buildHvcC(paramSets.vps!!, sps, paramSets.pps!!) ?: run {
            lastError = "Could not parse the HEVC SPS"
            return false
        }
        val track = MkvWriter.VideoTrack(MkvWriter.CODEC_HEVC, info?.width ?: streamWidth, info?.height ?: streamHeight, hvcc)
        return openSegment(cfg, FORMAT_HEVC, csd, track)
    }

    private fun openSegment(cfg: Config, format: Int, csd: ByteArray?, video: MkvWriter.VideoTrack): Boolean {
        segmentIndex++
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "XrealEye_${stamp}_%03d.mkv".format(segmentIndex)
        val out = try {
            output.create(cfg.storage, name, "video/x-matroska")
        } catch (e: Exception) {
            Log.e(TAG, "Could not create output file", e)
            stopInternal("Could not create output file: ${e.message}")
            return false
        }
        // AudioCapture's format is fixed, so the audio track never waits for the encoder.
        val audioTrack = if (cfg.audio && !audioFailed) {
            MkvWriter.AudioTrack(AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS, AAC_LC_48K_MONO)
        } else {
            null
        }
        writer = MkvWriter(out.channel, video, audioTrack)
        writerFormat = format
        writerCsd = csd
        outputFile = out
        segmentStartElapsed = SystemClock.elapsedRealtime()
        Log.i(TAG, "Segment #$segmentIndex → ${out.location} (${video.width}x${video.height})")
        if (cfg.gyroLog) openGyroLog(cfg, name)
        return true
    }

    /** The segment's `.gcsv`; a failure only costs the log, never the video. */
    private fun openGyroLog(cfg: Config, videoName: String) {
        try {
            val side = output.createSidecar(cfg.storage, videoName)
            gyroOut = side
            gyroLog = GyroLog(Channels.newOutputStream(side.channel), videoName, System.currentTimeMillis() / 1000)
            Log.i(TAG, "Gyro log → ${side.location}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not create the gyro log: ${e.message}")
            gyroOut?.abort()
            gyroOut = null
        }
    }

    /** Closes the segment's gyro log: published when it has samples and [keep], else removed. */
    private fun closeGyroLog(keep: Boolean) {
        val g = gyroLog
        val out = gyroOut
        gyroLog = null
        gyroOut = null
        val any = g?.close() ?: false
        if (keep && any) {
            out?.finish()
            Log.i(TAG, "Gyro log closed: ${out?.location} (${g?.lines} samples)")
        } else {
            out?.abort()
            if (g != null) Log.i(TAG, "Gyro log dropped (no IMU samples — glasses IMU not streaming?)")
        }
    }

    private fun segmentDue(cfg: Config): Boolean =
        SystemClock.elapsedRealtime() - segmentStartElapsed >= cfg.splitMinutes * 60_000L

    private fun finalizeSegment() {
        val w = writer ?: return
        val out = outputFile
        writer = null
        outputFile = null
        writerFormat = -1
        writerCsd = null
        sessionBytesClosed += w.bytesWritten
        closeGyroLog(keep = w.videoFrames > 0)
        try {
            w.close()
            if (w.videoFrames == 0L) out?.abort() else {
                out?.finish()
                lastFinished = out
            }
            Log.i(TAG, "Segment closed: ${out?.location} (${w.videoFrames} frames, ${w.bytesWritten} B)")
        } catch (e: Exception) {
            Log.e(TAG, "Segment close failed", e)
            if (w.videoFrames == 0L) out?.abort() else out?.finish()
            lastError = "Segment close failed: ${e.message}"
        }
        publishStatus()
    }

    private fun failSegment(message: String) {
        lastError = message
        writer?.let { w ->
            sessionBytesClosed += w.bytesWritten
            try { w.close() } catch (_: Exception) {}
        }
        closeGyroLog(keep = true)
        writer = null
        writerFormat = -1
        writerCsd = null
        outputFile?.finish() // keep whatever reached the disk (MKV stays playable)
        outputFile = null
        resync("error")
        // a persistent error (e.g. storage gone) must not produce a new broken file every GOP
        val now = SystemClock.elapsedRealtime()
        failureTimes.addLast(now)
        while (failureTimes.isNotEmpty() && now - failureTimes.first() > FAILURE_WINDOW_MS) failureTimes.removeFirst()
        if (failureTimes.size >= MAX_FAILURES) stopInternal("Recording keeps failing — stopped ($message)") else publishStatus()
    }

    /** Frees space by deleting the oldest finished recordings (loop recording). */
    private fun ensureFreeSpace(cfg: Config): Boolean {
        while (output.freeBytes(cfg.storage) < MIN_FREE_BYTES) {
            if (!output.deleteOldest(cfg.storage, keep = outputFile)) return false
        }
        return true
    }

    private fun housekeeping() {
        val cfg = config ?: return
        val now = SystemClock.elapsedRealtime()
        if (writer != null && now - lastChunkElapsed > IDLE_FINALIZE_MS) {
            Log.i(TAG, "No stream for ${IDLE_FINALIZE_MS / 1000}s — closing the current segment")
            finalizeSegment()
        }
        if (now - lastStorageCheckElapsed >= STORAGE_CHECK_INTERVAL_MS) {
            lastStorageCheckElapsed = now
            if (!ensureFreeSpace(cfg)) {
                stopInternal("Storage full and nothing left to overwrite — recording stopped")
                return
            }
        }
        if (now - lastStatusElapsed >= STATUS_INTERVAL_MS) publishStatus()
        if (writer == null && streamFormat == FORMAT_HEVC && now - lastDiagElapsed >= 3000) {
            lastDiagElapsed = now
            Log.i(TAG, "HEVC diag: AUs=$auCount IRAP=$irapCount discarded=${hevcAssembler.discardedBytes} " +
                "paramSets=${paramSets.vps != null}/${paramSets.sps != null}/${paramSets.pps != null}")
        }
    }

    private fun publishStatus() {
        lastStatusElapsed = SystemClock.elapsedRealtime()
        val w = writer
        val cfg = config
        val status = Status(
            recording = cfg != null,
            sessionStartElapsedMs = sessionStartElapsed,
            segmentIndex = segmentIndex,
            currentFile = outputFile?.location,
            lastFinishedFile = lastFinished?.location,
            lastFinishedUri = lastFinished?.uri,
            sessionBytes = sessionBytesClosed + (w?.bytesWritten ?: 0),
            droppedChunks = droppedChunks.get(),
            codecLabel = when {
                cfg == null -> ""
                streamFormat == FORMAT_MJPEG -> "MJPEG → MKV"
                streamFormat == FORMAT_HEVC -> "HEVC → MKV"
                else -> ""
            },
            waitingForStream = cfg != null && w == null,
            audioActive = cfg?.audio == true && !audioFailed,
            error = lastError,
        )
        try {
            listener.onRecorderStatus(status)
        } catch (e: Exception) {
            Log.w(TAG, "listener failed: ${e.message}")
        }
    }

    // Started last, after every field above is initialized.
    private val thread = Thread({ loop() }, "EyeRecorder").apply { start() }
}
