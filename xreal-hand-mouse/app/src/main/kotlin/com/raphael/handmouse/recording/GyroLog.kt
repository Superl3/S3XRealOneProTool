package com.raphael.handmouse.recording

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Gyroflow IMU log (`.gcsv`, format version 1.3) for one recording segment (2026-09-29): the
 * glasses' gyro and accelerometer, so the video can be stabilized in Gyroflow and so the in-app
 * enhancer can fit the IMU↔camera axes (see `imu/ImuCameraCalibration`).
 *
 * - `t` is in µs from the segment's first video frame ([setBase], same clock as the frame
 *   timestamps: `System.nanoTime()`); samples before it are dropped.
 * - The 1000 Hz stream is averaged in pairs to 500 Hz (~110 MB/h instead of ~220).
 * - Values are stored as integers with `gscale`/`ascale` (rad/s and g per unit). The axes are
 *   the glasses' raw IMU axes (`orientation,XYZ`) — how they map onto the camera is not known,
 *   so it has to be set in Gyroflow (or is fitted by the enhancer).
 *
 * [add] runs on the IMU thread, [setBase]/[close] on the recorder thread: all synchronized.
 */
class GyroLog(stream: OutputStream, videoFileName: String, epochSeconds: Long) {

    companion object {
        /** rad/s per stored unit. */
        const val GYRO_UNIT = 1e-5
        /** m/s² per stored unit. */
        const val ACCEL_UNIT = 1e-4
        private const val STANDARD_GRAVITY = 9.80665

        fun header(videoFileName: String, epochSeconds: Long): String = buildString {
            append("GYROFLOW IMU LOG\n")
            append("version,1.3\n")
            append("id,xreal_eye_tools\n")
            append("orientation,XYZ\n")
            append("vendor,XREAL\n")
            append("videofilename,").append(videoFileName).append('\n')
            append("timestamp,").append(epochSeconds).append('\n')
            append("tscale,0.000001\n")
            // plain decimals rather than Double.toString's exponent form
            append("gscale,").append(String.format(Locale.US, "%.12f", GYRO_UNIT)).append('\n')
            append("ascale,").append(String.format(Locale.US, "%.12f", ACCEL_UNIT / STANDARD_GRAVITY)).append('\n')
            append("t,gx,gy,gz,ax,ay,az\n")
        }
    }

    private var writer: BufferedWriter? = BufferedWriter(OutputStreamWriter(stream, Charsets.US_ASCII), 64 * 1024)
    private var baseUs: Long? = null
    private var pairT = 0L
    private val pair = DoubleArray(6)
    private var pairN = 0
    private val line = StringBuilder(96)

    var lines = 0L
        private set

    init {
        writer!!.write(header(videoFileName, epochSeconds))
    }

    /** Time of the segment's first video frame (µs); only the first call counts. */
    @Synchronized
    fun setBase(timeUs: Long) {
        if (baseUs == null) baseUs = timeUs
    }

    /** One IMU sample: [localNs] on `System.nanoTime()`'s clock, gyro rad/s, accel m/s². */
    @Synchronized
    fun add(localNs: Long, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        val w = writer ?: return
        val base = baseUs ?: return
        val tUs = localNs / 1000 - base
        if (tUs < 0) return
        pairT += tUs
        pair[0] += gx.toDouble(); pair[1] += gy.toDouble(); pair[2] += gz.toDouble()
        pair[3] += ax.toDouble(); pair[4] += ay.toDouble(); pair[5] += az.toDouble()
        if (++pairN < 2) return
        line.setLength(0)
        line.append(pairT / 2)
        for (k in 0 until 6) {
            val unit = if (k < 3) GYRO_UNIT else ACCEL_UNIT
            line.append(',').append((pair[k] / 2 / unit).roundToLong())
        }
        line.append('\n')
        pairT = 0
        pair.fill(0.0)
        pairN = 0
        try {
            w.append(line)
            lines++
        } catch (_: Exception) {
            writer = null // storage gone: the video keeps recording without the log
        }
    }

    /** Flushes and closes; returns whether anything was logged. */
    @Synchronized
    fun close(): Boolean {
        val w = writer ?: return lines > 0
        writer = null
        try { w.close() } catch (_: Exception) {}
        return lines > 0
    }
}
