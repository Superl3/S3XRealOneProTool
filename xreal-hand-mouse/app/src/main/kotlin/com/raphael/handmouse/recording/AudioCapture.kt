package com.raphael.handmouse.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log

/**
 * Phone-microphone capture for the Eye recorder: `AudioRecord` (PCM 16-bit mono 48 kHz) → AAC-LC
 * `MediaCodec` encoder, on its own thread. Encoded frames and the output format are handed to
 * [Sink] (called on the capture thread — the recorder re-posts them to its own thread). The format
 * is fixed (AAC-LC, [SAMPLE_RATE], [CHANNELS]) so the container never waits for the encoder.
 *
 * ## Timestamps
 * Same clock as the video chunks (`System.nanoTime()/1000`). PTS are derived from the sample
 * count (jitter-free) anchored at the first read, and re-anchored whenever the count drifts more
 * than [MAX_DRIFT_US] from the wall clock — the mic crystal and the system clock disagree by tens
 * of ppm, which would otherwise pile up to a visible A/V offset over a multi-hour ride.
 */
class AudioCapture(private val sink: Sink) {

    interface Sink {
        fun onAudioFrame(data: ByteArray, ptsUs: Long)
        fun onAudioError(message: String)
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        private const val BIT_RATE = 128_000
        private const val MAX_DRIFT_US = 40_000L
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before start()
    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "EyeRecorder-Audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            sink.onAudioError("AudioRecord unavailable (minBuf=$minBuf)")
            return
        }
        val bufBytes = maxOf(minBuf * 2, 4096)
        val record = openRecord(bufBytes) ?: run {
            sink.onAudioError("Could not open the microphone (permission denied or mic busy)")
            return
        }

        val codec = try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufBytes)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            record.release()
            sink.onAudioError("Could not start the AAC encoder: ${e.message}")
            return
        }

        val pcm = ByteArray(bufBytes / 2)
        val info = MediaCodec.BufferInfo()
        var anchorUs = -1L
        var framesSinceAnchor = 0L
        var lastPtsUs = -1L

        try {
            record.startRecording()
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "AudioRecord.read=$n")
                    continue
                }
                val samples = n / 2L
                val chunkUs = samples * 1_000_000L / SAMPLE_RATE
                val observedUs = System.nanoTime() / 1000 - chunkUs
                if (anchorUs < 0) {
                    anchorUs = observedUs
                    framesSinceAnchor = 0
                }
                var ptsUs = anchorUs + framesSinceAnchor * 1_000_000L / SAMPLE_RATE
                if (kotlin.math.abs(observedUs - ptsUs) > MAX_DRIFT_US && observedUs > lastPtsUs) {
                    anchorUs = observedUs
                    framesSinceAnchor = 0
                    ptsUs = observedUs
                }
                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                lastPtsUs = ptsUs
                framesSinceAnchor += samples

                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    inBuf.clear()
                    inBuf.put(pcm, 0, n)
                    codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                }
                drain(codec, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error", e)
            sink.onAudioError("Audio capture stopped: ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(out)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        sink.onAudioFrame(bytes, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(out, false)
                }
                else -> return
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(bufBytes: Int): AudioRecord? {
        // CAMCORDER is tuned for video recording (wider pickup, device noise suppression where
        // available); fall back to the plain MIC source.
        for (source in intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord(source=$source) failed: ${e.message}")
            }
        }
        return null
    }
}
