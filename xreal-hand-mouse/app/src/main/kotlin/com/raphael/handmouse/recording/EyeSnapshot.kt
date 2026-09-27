package com.raphael.handmouse.recording

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.raphael.handmouse.capture.MjpegStreamAssembler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Still photo from the live Eye stream (Eye Tools fork) — no extra camera session, no UI on the
 * DeX screen; the result is reported through the foreground notification.
 *
 * - MJPEG stream: the next complete JPEG is saved byte-for-byte (zero processing).
 * - HEVC stream: the next keyframe (IRAP, intra-coded) is decoded once with a throw-away
 *   MediaCodec and JPEG-encoded.
 *
 * [onChunk] is called on the USB thread and costs one volatile read unless a photo is pending.
 */
class EyeSnapshot(context: Context, private val listener: Listener) {

    fun interface Listener {
        /** Called on the snapshot thread. [uri] null = failure ([error] set). */
        fun onPhoto(uri: Uri?, displayName: String?, error: String?)
    }

    companion object {
        private const val TAG = "EyeSnapshot"
        private const val TIMEOUT_MS = 6000L
        private const val JPEG_QUALITY = 92
        const val RELATIVE_DIR = "Pictures/XrealEye"
    }

    private val appContext = context.applicationContext
    private val queue = LinkedBlockingQueue<Any>()

    @Volatile
    private var pending = false
    @Volatile
    private var format = EyeRecorder.FORMAT_MJPEG

    private object Request
    private object Quit
    private class StreamStart(val format: Int)

    fun request() {
        queue.put(Request)
    }

    fun onStreamStarted(formatSubtype: Int) {
        format = formatSubtype
        queue.put(StreamStart(formatSubtype))
    }

    fun onChunk(data: ByteArray) {
        if (pending) queue.put(data)
    }

    fun release() {
        pending = false
        queue.put(Quit)
        thread.join(2000)
    }

    // ---------------- snapshot thread ----------------

    private val mjpeg = MjpegStreamAssembler(maxBufferBytes = 4 * 1024 * 1024)
    private val hevc = HevcAccessUnitAssembler()
    private val paramSets = HevcParamSetTracker()
    private var requestedAt = 0L

    private fun loop() {
        while (true) {
            // no photo pending: block; pending: wake up to enforce the timeout
            val item = if (pending) queue.poll(250, TimeUnit.MILLISECONDS) else queue.take()
            try {
                when (item) {
                    Quit -> return
                    Request -> if (!pending) {
                        mjpeg.reset()
                        hevc.reset()
                        requestedAt = System.currentTimeMillis()
                        pending = true
                    }
                    is StreamStart -> { mjpeg.reset(); hevc.reset() }
                    is ByteArray -> if (pending) onData(item)
                }
                if (pending && System.currentTimeMillis() - requestedAt > TIMEOUT_MS) {
                    finish(null, null, "No camera frame within ${TIMEOUT_MS / 1000}s (is capture streaming?)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot failed", e)
                finish(null, null, "Photo failed: ${e.message}")
            }
        }
    }

    private fun onData(chunk: ByteArray) {
        if (format == EyeRecorder.FORMAT_MJPEG) {
            val jpeg = mjpeg.feed(chunk).firstOrNull { JpegInfo.size(it) != null } ?: return
            save(jpeg)
            return
        }
        for (au in hevc.feed(chunk, 0)) {
            paramSets.update(au)
            val csd = paramSets.csd ?: continue
            if (!au.isIrap) continue
            val jpeg = decodeIrapToJpeg(csd, paramSets.sps!!, au) ?: throw IllegalStateException("HEVC keyframe decode failed")
            save(jpeg)
            return
        }
    }

    private fun decodeIrapToJpeg(csd: ByteArray, s: ByteArray, au: HevcAccessUnitAssembler.AccessUnit): ByteArray? {
        val info = HevcParameterSets.parseSps(s)
        val w = info?.width ?: 1920
        val h = info?.height ?: 1080
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val sample = ByteArrayOutputStream(au.totalBytes).apply { au.nals.forEach { write(it) } }.toByteArray()
            val deadline = System.currentTimeMillis() + 3000
            var queued = false
            var eosQueued = false
            val bi = MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < deadline) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        if (!queued) {
                            buf.put(sample)
                            codec.queueInputBuffer(inIdx, 0, sample.size, 0, 0)
                            queued = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bi, 10_000)
                if (outIdx >= 0) {
                    val image = codec.getOutputImage(outIdx)
                    val jpeg = image?.use { toJpeg(it) }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (jpeg != null) return jpeg
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return null
                }
            }
            return null
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    /** YUV_420_888 (any plane layout) → NV21 → JPEG, honouring the crop rect. Rows are bulk-read;
     * per-pixel work only where the plane layout requires it. */
    private fun toJpeg(image: Image): ByteArray {
        val crop = image.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        val nv21 = ByteArray(w * h * 3 / 2)
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val row = ByteArray(maxOf(yPlane.rowStride, image.planes[1].rowStride))
        for (r in 0 until h) {
            yBuf.position((r + crop.top) * yPlane.rowStride + crop.left * yPlane.pixelStride)
            if (yPlane.pixelStride == 1) {
                yBuf.get(nv21, r * w, w)
            } else {
                yBuf.get(row, 0, minOf(row.size, yBuf.remaining()))
                for (c in 0 until w) nv21[r * w + c] = row[c * yPlane.pixelStride]
            }
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val uRow = ByteArray(u.rowStride)
        val vRow = ByteArray(v.rowStride)
        var o = w * h
        for (r in 0 until h / 2) {
            val uStart = (r + crop.top / 2) * u.rowStride + (crop.left / 2) * u.pixelStride
            val vStart = (r + crop.top / 2) * v.rowStride + (crop.left / 2) * v.pixelStride
            u.buffer.position(uStart)
            u.buffer.get(uRow, 0, minOf(uRow.size, u.buffer.remaining()))
            v.buffer.position(vStart)
            v.buffer.get(vRow, 0, minOf(vRow.size, v.buffer.remaining()))
            for (c in 0 until w / 2) {
                nv21[o++] = vRow[c * v.pixelStride]
                nv21[o++] = uRow[c * u.pixelStride]
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun save(jpeg: ByteArray) {
        val name = "XrealEye_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { it.write(jpeg) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        Log.i(TAG, "Photo saved: $RELATIVE_DIR/$name (${jpeg.size} B)")
        finish(uri, name, null)
    }

    private fun finish(uri: Uri?, name: String?, error: String?) {
        pending = false
        queue.removeIf { it is ByteArray }
        mjpeg.reset()
        hevc.reset()
        listener.onPhoto(uri, name, error)
    }

    // Started last, after every field above is initialized.
    private val thread = Thread({ loop() }, "EyeSnapshot").apply { start() }
}
