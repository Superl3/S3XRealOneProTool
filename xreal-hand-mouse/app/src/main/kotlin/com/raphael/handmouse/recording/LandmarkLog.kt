package com.raphael.handmouse.recording

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.raphael.handmouse.tracking.HandPoint
import com.raphael.handmouse.tracking.HandTracker
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-frame hand-tracking log (2026-09-28): one JSON line per MediaPipe result, so gesture and
 * cursor tuning can be checked against recorded data instead of by feel. Written next to nothing
 * the user sees — `Android/data/<package>/files/landmarks/landmarks-<start>.jsonl` — one file per
 * tracking session while the setting is on. `wall` (epoch ms) lines the log up with Eye MKV
 * recordings of the same session.
 *
 * Lines:
 * - `{"t":…,"wall":…,"w":960,"h":540,"fps":59.8,"lat":24,"hand":"Right","score":0.97,"lm":[x,y,z×21],"world":[x,y,z×21]}`
 *   — `t` is the frame timestamp given to MediaPipe (uptime ms), `lm` the normalized image
 *   landmarks, `world` the metric ones (empty when absent), `hand`/`score` MediaPipe's handedness.
 * - `{"t":…,"wall":…,"lost":true}` — MediaPipe found no hand in that frame.
 * - `"label"` (2026-09-29): the dataset label chosen in the settings, when one is.
 *
 * Formatting and IO run on a dedicated thread; the tracker thread only posts.
 */
class LandmarkLog private constructor(private val file: File) {

    companion object {
        private const val TAG = "LandmarkLog"

        /** Opens a new log file, or returns `null` (logged) when storage is unavailable. */
        fun open(context: Context): LandmarkLog? {
            val dir = context.getExternalFilesDir("landmarks") ?: return null.also {
                Log.w(TAG, "External files dir unavailable — landmark log off")
            }
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Cannot create $dir — landmark log off")
                return null
            }
            val name = "landmarks-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
            return LandmarkLog(File(dir, name)).takeIf { it.start() }
        }

        private fun appendPoints(sb: StringBuilder, points: List<HandPoint>) {
            sb.append('[')
            points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append(p.x).append(',').append(p.y).append(',').append(p.z)
            }
            sb.append(']')
        }
    }

    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null

    /** Dataset label in effect ("hm_dataset_label"), written as `"label"` on every result line. */
    @Volatile var label: String? = null
    private var writer: BufferedWriter? = null

    private fun start(): Boolean {
        val w = try {
            file.bufferedWriter()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $file — landmark log off", e)
            return false
        }
        writer = w
        val t = HandlerThread("LandmarkLog").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        Log.d(TAG, "Logging hand landmarks to $file")
        return true
    }

    fun logResult(result: HandTracker.Result) {
        val h = handler ?: return
        val wall = System.currentTimeMillis()
        h.post {
            val hand = result.handedness.firstOrNull()
            val sb = StringBuilder(2048)
            sb.append("{\"t\":").append(result.timestampMs)
            label?.let { sb.append(",\"label\":\"").append(it).append('"') }
            sb.append(",\"wall\":").append(wall)
                .append(",\"w\":").append(result.imageWidth)
                .append(",\"h\":").append(result.imageHeight)
                .append(",\"fps\":").append("%.1f".format(Locale.US, result.inferenceFps))
                .append(",\"lat\":").append(result.latencyMs)
            if (hand != null) {
                sb.append(",\"hand\":\"").append(hand.categoryName()).append('"')
                    .append(",\"score\":").append(hand.score())
            }
            sb.append(",\"lm\":")
            appendPoints(sb, result.points)
            sb.append(",\"world\":")
            appendPoints(sb, result.worldLandmarks)
            sb.append('}')
            write(sb.toString())
        }
    }

    fun logLost(timestampMs: Long) {
        val h = handler ?: return
        val wall = System.currentTimeMillis()
        h.post { write("{\"t\":$timestampMs,\"wall\":$wall,\"lost\":true}") }
    }

    private fun write(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.newLine()
        } catch (e: Exception) {
            Log.w(TAG, "Write failed — landmark log off", e)
            writer = null
            try { w.close() } catch (_: Exception) {}
        }
    }

    /** Flushes and closes the file; queued lines are written first. Safe to call twice. */
    fun close() {
        val h = handler ?: return
        handler = null
        h.post {
            try { writer?.close() } catch (e: Exception) { Log.w(TAG, "Close failed", e) }
            writer = null
        }
        thread?.quitSafely()
        thread = null
    }
}
