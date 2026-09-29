package com.raphael.handmouse.enhance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.raphael.handmouse.MainActivity
import com.raphael.handmouse.R
import com.raphael.handmouse.recording.RecordingOutput
import com.raphael.handmouse.service.EyeCaptureService
import com.raphael.handmouse.util.Prefs
import java.util.Locale

/**
 * Foreground service that runs [VideoEnhancer] over the recordings picked on the main screen,
 * one after the other (2026-09-29). Type mediaProcessing on Android 15+, dataSync on 14.
 *
 * Stops — leaving the file being processed untouched — on the notification's Cancel, on the
 * system's foreground-service time limit ([onTimeout]) and when a recording starts: both would
 * compete for the encoder and the CPU, and the recording matters more. It does not start while
 * one is running.
 */
class EnhanceService : Service() {

    companion object {
        private const val TAG = "EnhanceService"
        private const val CHANNEL_ID = "enhance"
        private const val NOTIF_PROGRESS = 41
        private const val NOTIF_DONE = 42
        private const val ACTION_START = "com.raphael.handmouse.enhance.START"
        private const val ACTION_CANCEL = "com.raphael.handmouse.enhance.CANCEL"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_STORAGES = "storages"
        private const val NOTIFY_INTERVAL_MS = 1000L

        /** Main-thread callbacks for the main screen; set in its onResume, cleared in onPause. */
        @Volatile
        var listener: Listener? = null

        @Volatile
        var status: Status = Status()
            private set

        /** Starts enhancing [recordings] in order; false (nothing started) while a recording runs. */
        fun start(context: Context, recordings: List<RecordingOutput.Recording>): Boolean {
            if (recordings.isEmpty() || EyeCaptureService.getInstance()?.isRecording == true) return false
            val intent = Intent(context, EnhanceService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_NAMES, recordings.map { it.displayName }.toTypedArray())
                .putExtra(EXTRA_STORAGES, recordings.map { it.storage.name }.toTypedArray())
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun cancel(context: Context) {
            if (!status.running) return
            context.startService(Intent(context, EnhanceService::class.java).setAction(ACTION_CANCEL))
        }
    }

    class Status(
        val running: Boolean = false,
        /** 1-based position of the file being processed. */
        val index: Int = 0,
        val total: Int = 0,
        val name: String? = null,
        /** 0..1 within the current file. */
        val fraction: Float = 0f,
        val analyzing: Boolean = false,
        /** Summary of the last finished run. */
        val lastResult: String? = null,
    )

    interface Listener {
        fun onEnhanceStatus(status: Status)
        fun onEnhanceLog(line: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var cancelRequested = false
    @Volatile private var stoppedBy: String? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_enhance), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                requestStop(getString(R.string.enhance_stop_cancelled))
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                if (worker == null) stopSelf()
                return START_NOT_STICKY
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            startForeground(NOTIF_PROGRESS, progressNotification(Status(running = true)), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY // one run at a time; the main screen offers Cancel meanwhile
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        val storages = intent.getStringArrayExtra(EXTRA_STORAGES) ?: emptyArray()
        val wanted = names.indices.map { storages.getOrNull(it) to names[it] }
        cancelRequested = false
        stoppedBy = null
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XrealEyeTools:enhance")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        RecordingOutput.enhancerRunning = true
        worker = Thread({ runAll(wanted) }, "enhance").apply { start() }
        return START_NOT_STICKY
    }

    /** Android 15+: the daily foreground-service budget ran out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        requestStop(getString(R.string.enhance_stop_timeout))
        // the worker stops within a frame and then stops the service; the system allows a few seconds
        if (worker == null) stopSelf() else main.postDelayed({ if (worker != null) stopSelf() }, 3000)
    }

    override fun onDestroy() {
        requestStop(getString(R.string.enhance_stop_cancelled))
        super.onDestroy()
    }

    private fun requestStop(reason: String) {
        if (stoppedBy == null) stoppedBy = reason
        cancelRequested = true
    }

    private fun runAll(wanted: List<Pair<String?, String>>) {
        val output = RecordingOutput(this)
        val all = try { output.listRecordings() } catch (_: Exception) { emptyList() }
        val queue = wanted.mapNotNull { (storage, name) -> all.firstOrNull { it.storage.name == storage && it.displayName == name } }
        val enhancer = VideoEnhancer(this)
        val deleteOriginal = Prefs(this).enhanceDeleteOriginal
        var done = 0
        var skipped = 0
        var failed = 0
        var savedBytes = 0L
        val isCancelled = {
            if (!cancelRequested && EyeCaptureService.getInstance()?.isRecording == true) {
                requestStop(getString(R.string.enhance_stop_recording))
            }
            cancelRequested
        }
        log("Enhancing ${queue.size} recording(s)" + if (deleteOriginal) ", originals deleted when verified" else ", originals kept")
        for ((i, rec) in queue.withIndex()) {
            if (isCancelled()) break
            publish(Status(true, i + 1, queue.size, rec.displayName, 0f, true), force = true)
            if (output.exists(rec.storage, VideoEnhancer.outputName(rec.displayName))) {
                skipped++
                log("${rec.displayName}: skipped (already enhanced)")
                continue
            }
            val outcome = try {
                enhancer.enhance(rec, deleteOriginal, isCancelled) { fraction, analyzing ->
                    publish(Status(true, i + 1, queue.size, rec.displayName, fraction, analyzing))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "${rec.displayName} crashed", e)
                VideoEnhancer.Outcome.Failed(e.toString())
            }
            when (outcome) {
                is VideoEnhancer.Outcome.Done -> {
                    done++
                    if (outcome.deletedOriginal) savedBytes += rec.sizeBytes - outcome.sizeBytes
                    log(describe(rec, outcome))
                }
                is VideoEnhancer.Outcome.Skipped -> { skipped++; log("${rec.displayName}: skipped (${outcome.reason})") }
                is VideoEnhancer.Outcome.Failed -> { failed++; log("${rec.displayName}: failed (${outcome.reason}) — original kept") }
                VideoEnhancer.Outcome.Cancelled -> log("${rec.displayName}: stopped — original kept")
            }
        }
        val stop = stoppedBy?.takeIf { cancelRequested }
        var summary = getString(R.string.enhance_summary, done, skipped, failed)
        if (savedBytes > 0) summary += " · " + getString(R.string.enhance_summary_saved, Formatter.formatShortFileSize(this, savedBytes))
        if (stop != null) summary += " · $stop"
        log(summary)
        main.post {
            RecordingOutput.enhancerRunning = false
            worker = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            status = Status(lastResult = summary)
            listener?.onEnhanceStatus(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifications.notify(NOTIF_DONE, doneNotification(summary))
            stopSelf()
        }
    }

    private fun describe(rec: RecordingOutput.Recording, o: VideoEnhancer.Outcome.Done): String = buildString {
        append("${rec.displayName} → ${o.location}: ")
        append(Formatter.formatShortFileSize(this@EnhanceService, rec.sizeBytes))
        append(" → ")
        append(Formatter.formatShortFileSize(this@EnhanceService, o.sizeBytes))
        append(", ${o.frames} frames")
        if (o.droppedFrames > 0) append(" (${o.droppedFrames} undecodable)")
        append(", ${o.codec}, stabilization ${o.stabilization}")
        append(", %.0f s".format(Locale.US, o.seconds))
        o.calibration?.let {
            append(", IMU fit %d ms R² %.2f".format(Locale.US, it.latencyMs, it.r2))
            if (o.calibrationStored) append(" (stored for the cursor)")
        }
        if (o.deletedOriginal) append(", original deleted")
    }

    private fun publish(s: Status, force: Boolean = false) {
        status = s
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyMs < NOTIFY_INTERVAL_MS) return
        lastNotifyMs = now
        notifications.notify(NOTIF_PROGRESS, progressNotification(s))
        main.post { listener?.onEnhanceStatus(s) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        main.post { listener?.onEnhanceLog(line) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(s: Status): Notification {
        val percent = (s.fraction * 100).toInt()
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, EnhanceService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle(getString(R.string.enhance_notif_title, s.index, s.total))
            .setContentText(
                s.name?.let { getString(if (s.analyzing) R.string.enhance_notif_analyzing else R.string.enhance_notif_encoding, it, percent) },
            )
            .setProgress(100, percent, s.name == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.enhance_cancel), cancel)
            .build()
    }

    private fun doneNotification(summary: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle(getString(R.string.enhance_done_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
}
