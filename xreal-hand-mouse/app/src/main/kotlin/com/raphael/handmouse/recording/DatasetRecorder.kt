package com.raphael.handmouse.recording

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Labelled gesture images for retraining a MediaPipe Gesture Recognizer (2026-09-29, setting
 * "hm_dataset_label", off by default). While a label is chosen, the tracking frames (960×536,
 * the MJPEG decoder's half-size output minus the noisy bottom rows) are saved as JPEG q90 at
 * most every [INTERVAL_MS], only while a hand is tracked, to
 * `Android/data/<package>/files/dataset/<label>/` — the folder-per-label layout
 * `mediapipe_model_maker.gesture_recognizer.Dataset.from_folder` reads (see
 * `tools/train_gesture_recognizer.py`). The HEVC tracking path is not covered.
 *
 * [offer] runs on the decoder thread and only copies; encoding and IO run on a dedicated thread.
 */
class DatasetRecorder private constructor(private val dir: File, val label: String) {

    companion object {
        private const val TAG = "DatasetRecorder"
        const val INTERVAL_MS = 200L
        private const val MAX_PENDING = 2

        fun open(context: Context, label: String): DatasetRecorder? {
            val root = context.getExternalFilesDir("dataset") ?: return null.also {
                Log.w(TAG, "External files dir unavailable — dataset recording off")
            }
            val dir = File(root, label)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Cannot create $dir — dataset recording off")
                return null
            }
            return DatasetRecorder(dir, label)
        }
    }

    private val thread = HandlerThread("Dataset").also { it.start() }
    private val handler = Handler(thread.looper)
    private val pending = AtomicInteger(0)
    private val session = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private var lastOfferMs = 0L
    private var index = 0
    @Volatile private var closed = false

    @Volatile var saved = 0
        private set

    init {
        Log.i(TAG, "Saving '$label' images to $dir")
    }

    /** Decoder thread. [bitmap] is only valid during the call (it is copied). */
    fun offer(bitmap: Bitmap, timestampMs: Long, handTracked: Boolean) {
        if (closed || !handTracked || timestampMs - lastOfferMs < INTERVAL_MS) return
        if (pending.get() >= MAX_PENDING) return // storage slower than 5 fps: skip, never queue up
        lastOfferMs = timestampMs
        // always a new bitmap (copy() never hands back the decoder's ring bitmap, which we recycle)
        val copy = if (bitmap.width == 960 && bitmap.height == 540) Bitmap.createBitmap(bitmap, 0, 0, 960, 536)
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val name = String.format(Locale.US, "%s_%s_%05d.jpg", label, session, index++)
        pending.incrementAndGet()
        handler.post {
            try {
                File(dir, name).outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                saved++
                if (saved % 50 == 0) Log.i(TAG, "'$label': $saved images")
            } catch (e: Exception) {
                Log.w(TAG, "Could not save $name: ${e.message}")
            } finally {
                copy.recycle()
                pending.decrementAndGet()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post { Log.i(TAG, "'$label' session done: $saved images in $dir") }
        thread.quitSafely()
    }
}
