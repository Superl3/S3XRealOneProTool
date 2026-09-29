package com.raphael.handmouse.enhance

import com.raphael.handmouse.imu.GyroSeries
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.roundToLong

/**
 * Reads the gyro columns of a Gyroflow `.gcsv` ([com.raphael.handmouse.recording.GyroLog]'s
 * output, or any version-1.x log with `t,gx,gy,gz` columns) into a [GyroSeries]: µs on the
 * file's time axis (the recording's first video frame = 0), rad/s.
 *
 * Header keys used: `tscale` (seconds per `t` unit) and `gscale` (rad/s per gyro unit). Rows that
 * do not parse, or do not move forward in time, are skipped. Pure — JVM-tested (GcsvReaderTest).
 */
object GcsvReader {

    fun read(input: InputStream): GyroSeries? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII), 256 * 1024)
        var tscale = 0.001 // Gyroflow's default when the key is missing: ms
        var gscale = 1.0
        var cols: IntArray? = null
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.startsWith("t,")) {
                val names = line.split(',').map { it.trim() }
                val idx = intArrayOf(names.indexOf("t"), names.indexOf("gx"), names.indexOf("gy"), names.indexOf("gz"))
                if (idx.any { it < 0 }) return null
                cols = idx
                break
            }
            val comma = line.indexOf(',')
            if (comma <= 0) continue
            val value = line.substring(comma + 1).trim().toDoubleOrNull() ?: continue
            when (line.substring(0, comma).trim()) {
                "tscale" -> tscale = value
                "gscale" -> gscale = value
            }
        }
        val c = cols ?: return null
        val need = c.max() + 1
        var t = LongArray(1 shl 16)
        var g = FloatArray(3 shl 16)
        var n = 0
        val fields = DoubleArray(need)
        val usPerUnit = tscale * 1e6
        var last = Long.MIN_VALUE
        while (true) {
            val line = reader.readLine() ?: break
            if (!parseRow(line, fields, need)) continue
            val tUs = (fields[c[0]] * usPerUnit).roundToLong()
            if (tUs <= last) continue
            last = tUs
            if (n == t.size) {
                t = t.copyOf(n * 2)
                g = g.copyOf(n * 6)
            }
            t[n] = tUs
            g[n * 3] = (fields[c[1]] * gscale).toFloat()
            g[n * 3 + 1] = (fields[c[2]] * gscale).toFloat()
            g[n * 3 + 2] = (fields[c[3]] * gscale).toFloat()
            n++
        }
        if (n < 2) return null
        return GyroSeries(t.copyOf(n), g.copyOf(n * 3))
    }

    /** The first [count] comma-separated numbers of [line] into [out]; integers without allocation. */
    private fun parseRow(line: String, out: DoubleArray, count: Int): Boolean {
        var i = 0
        var field = 0
        val len = line.length
        while (field < count) {
            if (i >= len) return false
            val start = i
            var neg = false
            if (line[i] == '-') { neg = true; i++ } else if (line[i] == '+') i++
            var v = 0L
            var digits = 0
            while (i < len && line[i] in '0'..'9') {
                v = v * 10 + (line[i] - '0')
                i++
                digits++
            }
            if (i < len && line[i] != ',') {
                // not a plain integer (decimal or exponent): parse the token the slow way
                while (i < len && line[i] != ',') i++
                out[field] = line.substring(start, i).trim().toDoubleOrNull() ?: return false
            } else {
                if (digits == 0) return false
                out[field] = (if (neg) -v else v).toDouble()
            }
            field++
            i++ // the comma
        }
        return true
    }
}
