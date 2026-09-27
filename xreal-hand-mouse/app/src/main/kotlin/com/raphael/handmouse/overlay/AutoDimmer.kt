package com.raphael.handmouse.overlay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automatic dimming of the DeX screen from the phone's ambient-light sensor (Eye Tools fork).
 *
 * The One Pro's brightness command is not public, so this dims in software: a translucent black
 * layer over DeX. On the glasses' micro-OLED panels darker pixels emit less light, so it behaves
 * much like lowering the brightness.
 * - Light goes through [LightAdaptation]: darkens within ~2 s, brightens over ~30 s.
 * - At or above [startLux] (adapted) there is no dimming; darkness reaches [maxDim].
 * - While [CoverDetector] says the sensor is covered (pocket, bag, face-down) the level is held;
 *   while that decision is pending nothing moves either, so a phone going into a pocket does
 *   not first dim the screen.
 * - A fixed tick drives the adaptation: an on-change light sensor may send a single event after
 *   a sudden change and then nothing.
 */
class AutoDimmer(context: Context, private val onDim: (Float) -> Unit) : SensorEventListener {

    companion object {
        private const val TAG = "AutoDimmer"
        private const val TICK_MS = 250L
    }

    /** Maximum dim level 0..1 (in the dark). */
    var maxDim = 0.6f
        set(value) {
            field = value.coerceIn(0f, 0.9f)
            publish()
        }

    /** Ambient-light threshold below which dimming begins. */
    var startLux = 20f
        set(value) {
            field = value.coerceIn(5f, 200f)
            publish()
        }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val light = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximity = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())
    private val cover = CoverDetector()
    private val adaptation = LightAdaptation()

    private var lux = Float.NaN
    private var near: Boolean? = null
    private var faceUp: Float? = null
    private var lastTick = 0L
    private var lastLoggedDim = Float.NaN
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running || light == null) {
            if (light == null) Log.w(TAG, "No ambient light sensor")
            return
        }
        running = true
        if (proximity != null) near = false
        proximity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accel?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (sensors?.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL) != true) {
            running = false
            sensors?.unregisterListener(this)
            Log.w(TAG, "Could not register ambient light sensor")
            return
        }
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        sensors?.unregisterListener(this)
        lux = Float.NaN
        near = null
        faceUp = null
        cover.reset()
        adaptation.reset()
        lastLoggedDim = Float.NaN
        onDim(0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = event.values[0].coerceAtLeast(0f)
            Sensor.TYPE_PROXIMITY -> near = event.values[0] < (proximity?.maximumRange ?: 5f)
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val g = sqrt(x * x + y * y + z * z)
                if (g > 3f) faceUp = z / g // ignore free-fall / hard shakes
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTick
        lastTick = now
        if (lux.isNaN()) return
        val wasCovered = cover.covered
        val covered = cover.update(now, lux, faceUp, near)
        if (covered != wasCovered) {
            Log.d(TAG, if (covered) "Sensor covered (pocket / face-down) - holding dim level"
                       else "Sensor uncovered - adapting again")
        }
        if (covered || cover.pending) return
        adaptation.step(lux, dt)
        publish()
    }

    private fun publish() {
        if (!running) return
        val adapted = adaptation.lux
        if (adapted.isNaN()) return
        val level = dimForLux(adapted, startLux, maxDim)
        onDim(level)
        if (lastLoggedDim.isNaN() || abs(level - lastLoggedDim) >= 0.05f) {
            Log.d(TAG, "Ambient %.1f lux (adapted %.1f) -> dim %.0f%%".format(lux, adapted, level * 100f))
            lastLoggedDim = level
        }
    }
}
