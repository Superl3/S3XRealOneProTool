package com.raphael.handmouse.remote

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

/**
 * Air mouse: the phone's gyroscope moves the DeX pointer (mapping in [AirMouseMath]).
 *
 * - Listens to `TYPE_GYROSCOPE` and `TYPE_GRAVITY` at `SENSOR_DELAY_GAME` on the main looper.
 *   Without a gravity sensor, "up" comes from the accelerometer through a low-pass filter.
 * - The sample interval is the difference of event timestamps; the first gyro sample after
 *   [start] only primes that clock.
 * - Motion is accumulated and handed to [onDelta] (unrounded floats) once it reaches a whole
 *   pixel on either axis, so slow aiming adds up instead of being lost in sub-pixel steps.
 *   [onDelta] moves the pointer and returns the motion it actually applied (less than asked at a
 *   display edge); that applied motion is what [rewind] undoes.
 * - [freezeUntil] / [unfreeze] hold the pointer still while the panel is pressed; frozen
 *   samples move nothing and are not recorded. [rewind] undoes the jolt of the press itself.
 * - [beginCapture] / [endCapture]: during an undecided touchpad press the samples are held back
 *   in an [AirPressCapture] instead of dropped, so the controller can replay them into a drag
 *   (or discard them for a tap). Captured samples move nothing and are not recorded either.
 * - [headRates] (glasses IMU, [HeadMotionSource]): while it returns fresh rates, the pointer
 *   follows the phone's rotation relative to the head, so turning the body or walking does not
 *   move it. Without them (no glasses IMU, stale) the phone's own rotation is used as before.
 *
 * Everything runs on the main thread (sensor callbacks, touchpad calls); not thread-safe.
 */
class AirMouse(
    context: Context,
    /** Moves the pointer by (dx, dy) and returns the (dx, dy) it actually applied. */
    private val onDelta: (dx: Float, dy: Float) -> Pair<Float, Float>,
) {

    companion object {
        private const val TAG = "AirMouse"

        /** Weight of a new accelerometer sample in the gravity low-pass (fallback only). */
        private const val ACCEL_ALPHA = 0.1f

        /** Accumulated motion is emitted once it reaches this many pixels on either axis. */
        private const val EMIT_MIN_PX = 1f

        /** Head rates older than this (by [HeadRates.uptimeMs]) are not subtracted. */
        private const val HEAD_RATES_MAX_AGE_MS = 200L

        /**
         * A capture lapses this long after its press. A press resolves by about TAP_MAX_MS
         * (340 ms) plus the tick grace, so this only ends one whose resolution got lost.
         */
        private const val CAPTURE_MAX_MS = 1_000L
    }

    /** Pointer pixels per radian of rotation. */
    var gainPxPerRad: Float = AirMouseMath.BASE_GAIN_PX_PER_RAD

    /**
     * Soft dead zone on the (head-relative) rotation rate, rad/s; rest drift and tremor below it
     * do not move the pointer. Negative or NaN acts as none.
     */
    var deadZoneRadS: Float = AirMouseMath.DEAD_ZONE_RAD_S

    /**
     * The head's current turn rates (e.g. [HeadMotionSource.latestRates]), called once per gyro
     * sample on the main thread. Null, a null result or rates older than 200 ms → no head
     * compensation for that sample.
     */
    var headRates: (() -> HeadRates?)? = null

    /** How the phone is physically held (Surface.ROTATION units), normalized mod 4. */
    var physicalQuarterTurns: Int = 0
        set(value) {
            field = value.mod(4)
        }

    val isRunning: Boolean
        get() = running

    /** A [beginCapture] is under way. */
    val isCapturing: Boolean
        get() = capture.active

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroSensor = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())
    private val history = AirMouseHistory()
    private val capture = AirPressCapture()

    private var running = false
    private var gravity: Vec3? = null
    private var primed = false
    private var lastGyroNs = 0L
    private var frozenUntilMs = 0L
    private var pendingX = 0f
    private var pendingY = 0f

    /** Whether the last computed sample subtracted head rates (logged on change). */
    private var headCompensating = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> onGyro(event.timestamp, Vec3(v[0], v[1], v[2]))
                Sensor.TYPE_GRAVITY -> gravity = Vec3(v[0], v[1], v[2])
                Sensor.TYPE_ACCELEROMETER -> {
                    val a = Vec3(v[0], v[1], v[2])
                    val g = gravity
                    gravity = if (g == null) a else g + (a - g) * ACCEL_ALPHA
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Registers the sensors. False (logged) when there is no gyroscope, no gravity or
     * accelerometer sensor, or registration fails. Already running → true.
     */
    fun start(): Boolean {
        if (running) return true
        val sm = sensors
        val gyro = gyroSensor
        if (sm == null || gyro == null) {
            Log.w(TAG, "No gyroscope - air mouse unavailable")
            return false
        }
        val reference = gravitySensor ?: accelSensor
        if (reference == null) {
            Log.w(TAG, "No gravity or accelerometer sensor - air mouse unavailable")
            return false
        }
        resetState()
        running = true
        val ok = sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME, handler) &&
            sm.registerListener(listener, reference, SensorManager.SENSOR_DELAY_GAME, handler)
        if (!ok) {
            running = false
            sm.unregisterListener(listener)
            resetState()
            Log.w(TAG, "Could not register air mouse sensors")
            return false
        }
        val upSource = if (reference === gravitySensor) "gravity" else "low-passed accelerometer"
        Log.d(TAG, "Started (gyro=${gyro.name}, up from $upSource, gain=$gainPxPerRad px/rad, " +
            "phys=$physicalQuarterTurns)")
        return true
    }

    /** Unregisters the sensors and forgets all motion state. */
    fun stop() {
        val wasRunning = running
        running = false
        sensors?.unregisterListener(listener)
        resetState()
        if (wasRunning) Log.d(TAG, "Stopped")
    }

    /**
     * Holds the pointer still until [uptimeMs] (SystemClock.uptimeMillis), replacing any earlier
     * freeze or capture (its motion is dropped). Motion not yet emitted is dropped.
     */
    fun freezeUntil(uptimeMs: Long) {
        capture.end()
        frozenUntilMs = uptimeMs
        pendingX = 0f
        pendingY = 0f
    }

    /** Moves the pointer again from the next sample (a capture under way is dropped). */
    fun unfreeze() {
        capture.end()
        frozenUntilMs = 0L
    }

    /**
     * A touchpad press landed at [pressAtMs] (uptime): from now on samples move nothing. Those
     * before [pressAtMs] + [joltMs] are dropped, later ones are held for [endCapture]. Replaces a
     * freeze or capture under way; lapses on its own [CAPTURE_MAX_MS] after the press.
     * [shadowPx]: held motion at which the press log notes an early drag would have fired.
     */
    fun beginCapture(pressAtMs: Long, joltMs: Long, shadowPx: Float) {
        frozenUntilMs = 0L
        pendingX = 0f
        pendingY = 0f
        capture.begin(SystemClock.uptimeMillis(), pressAtMs + joltMs, pressAtMs + CAPTURE_MAX_MS, shadowPx)
    }

    /** Another finger jolted the phone during a capture: drops its samples until [uptimeMs] too. */
    fun extendCaptureJolt(uptimeMs: Long) {
        capture.extendJolt(uptimeMs)
    }

    /**
     * Ends a capture and returns what it held (null when none was under way). The pointer moves
     * again from the next sample; to replay the held motion, apply the result yourself.
     */
    fun endCapture(): AirPressCapture.Result? {
        pendingX = 0f
        pendingY = 0f
        return capture.end()
    }

    /**
     * The amount to move back: the negated sum of the motion applied in the last [windowMs]
     * (as [onDelta] reported it, so motion stopped at a display edge is not undone).
     * Those deltas leave the history, and motion not yet emitted is dropped.
     */
    fun rewind(windowMs: Long = 80): Pair<Float, Float> {
        pendingX = 0f
        pendingY = 0f
        val (sx, sy) = history.takeSince(SystemClock.uptimeMillis() - windowMs)
        return Pair(-sx, -sy)
    }

    private fun onGyro(timestampNs: Long, rate: Vec3) {
        val previousNs = lastGyroNs
        val wasPrimed = primed
        lastGyroNs = timestampNs
        primed = true
        if (!wasPrimed) return
        val now = SystemClock.uptimeMillis()
        if (now < frozenUntilMs) return
        val up = gravity ?: return
        val head = headRates?.invoke()?.takeIf { now - it.uptimeMs <= HEAD_RATES_MAX_AGE_MS }
        if ((head != null) != headCompensating) {
            headCompensating = head != null
            Log.d(TAG, if (headCompensating) "Head compensation on (glasses IMU)" else "Head compensation off")
        }
        val (dx, dy) = AirMouseMath.pointerDelta(
            gyro = rate,
            gravity = up,
            physicalQuarterTurns = physicalQuarterTurns,
            dtSec = (timestampNs - previousNs) / 1e9f,
            gainPxPerRad = gainPxPerRad,
            headYawRate = head?.yaw ?: 0f,
            headPitchRate = head?.pitch ?: 0f,
            deadZone = deadZoneRadS,
        )
        if (capture.active) {
            if (capture.offer(now, dx, dy)) return
            Log.w(TAG, "press capture lapsed after $CAPTURE_MAX_MS ms; moving again")
        }
        pendingX += dx
        pendingY += dy
        if (abs(pendingX) < EMIT_MIN_PX && abs(pendingY) < EMIT_MIN_PX) return
        val ex = pendingX
        val ey = pendingY
        pendingX = 0f
        pendingY = 0f
        val (ax, ay) = onDelta(ex, ey)
        history.add(now, ax, ay)
    }

    private fun resetState() {
        gravity = null
        primed = false
        lastGyroNs = 0L
        frozenUntilMs = 0L
        pendingX = 0f
        pendingY = 0f
        headCompensating = false
        history.clear()
        capture.end()
    }
}
