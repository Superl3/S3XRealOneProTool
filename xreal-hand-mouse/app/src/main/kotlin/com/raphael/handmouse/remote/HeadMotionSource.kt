package com.raphael.handmouse.remote

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import com.raphael.handmouse.imu.ImuSample
import com.raphael.handmouse.imu.XrealImuClient
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * The head's angular velocity about world axes (rad/s), from the glasses' IMU.
 *
 * - [yaw]: about world up, positive = counter-clockwise seen from above (turning left).
 * - [pitch]: about the head's horizontal right axis, positive = nose up.
 * - [uptimeMs]: when the newest sample behind them was taken (`SystemClock.uptimeMillis()`).
 */
data class HeadRates(val yaw: Float, val pitch: Float, val uptimeMs: Long)

/**
 * The head's turn rates for the air mouse ([AirMouse.headRates]), so that turning the body or
 * walking (phone and head turn together) does not move the pointer.
 *
 * - Owns its own [XrealImuClient] (TCP 169.254.2.1:52998, retried every 3 s while started); it
 *   does not depend on the Eye camera service. When that service also reads the IMU (recording
 *   with the gyro log, head compensation), the glasses get a second connection.
 * - The IMU thread feeds every sample to a [HeadRateEstimator] and publishes an immutable
 *   [HeadRates]; the main thread reads it ([latestRates], [isLive]). Thread-safe.
 */
class HeadMotionSource(context: Context) {

    companion object {
        private const val TAG = "HeadMotion"

        /** A sample older than this is not "live": [latestRates] returns null. */
        const val LIVE_MS = 200L
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    /** Guarded by [lock]: the IMU thread updates it, [start] / [stop] reset it. */
    private val estimator = HeadRateEstimator()
    private var loggedFirst = false

    @Volatile private var running = false
    @Volatile private var latest: HeadRates? = null

    /** The last connection failure (log text, see [XrealImuClient.failureMessage]); null once samples arrive. */
    @Volatile var lastFailure: String? = null
        private set

    private val client = XrealImuClient(
        appContext,
        { failure ->
            val message = XrealImuClient.failureMessage(failure)
            lastFailure = message
            Log.w(TAG, message)
        },
    ) { sample, localNs -> onSample(sample, localNs) }

    /** True while the newest usable sample is at most [LIVE_MS] old. */
    val isLive: Boolean
        get() = latestRates() != null

    /** The newest head rates, or null before the first usable sample or when it is older than [LIVE_MS]. */
    fun latestRates(): HeadRates? {
        val rates = latest ?: return null
        return if (SystemClock.uptimeMillis() - rates.uptimeMs <= LIVE_MS) rates else null
    }

    /**
     * Starts reading the glasses' IMU in the background (the connection itself may come later:
     * see [isLive]). False (logged) only when the device has no connectivity service, so the
     * glasses' USB network cannot be found. Already started → true.
     */
    fun start(): Boolean {
        if (running) return true
        if (appContext.getSystemService(ConnectivityManager::class.java) == null) {
            Log.w(TAG, "No ConnectivityManager - glasses IMU unavailable")
            return false
        }
        synchronized(lock) {
            estimator.reset()
            loggedFirst = false
            latest = null
            running = true
        }
        lastFailure = null
        client.start()
        Log.d(TAG, "Started")
        return true
    }

    /** Closes the IMU connection and forgets the rates. */
    fun stop() {
        if (!running) return
        synchronized(lock) {
            running = false
            estimator.reset()
            latest = null
        }
        client.stop()
        Log.d(TAG, "Stopped")
    }

    /** IMU thread. */
    private fun onSample(sample: ImuSample, localNs: Long) {
        synchronized(lock) {
            if (!running) return
            val ok = estimator.update(
                gyro = Vec3(sample.gx, sample.gy, sample.gz),
                accel = Vec3(sample.ax, sample.ay, sample.az),
                deviceTimeNs = sample.deviceTimeNs,
            )
            if (!ok) return
            latest = HeadRates(estimator.yaw, estimator.pitch, localNs / 1_000_000L)
            if (!loggedFirst) {
                loggedFirst = true
                lastFailure = null
                val up = estimator.up
                Log.i(TAG, String.format(Locale.US, "Head rates live, up in the glasses frame (%.2f, %.2f, %.2f)",
                    up?.x ?: 0f, up?.y ?: 0f, up?.z ?: 0f))
            }
        }
    }
}

/**
 * Head yaw / pitch rates from the glasses' IMU samples. Pure (JVM-tested, HeadRateEstimatorTest);
 * not thread-safe.
 *
 * Glasses IMU frame, measured 2026-11-18 from this user's recordings (the protocol doc leaves axes
 * and signs open):
 * - The accelerometer reads specific force: worn, it shows about +g along **up**, which in the
 *   glasses frame is mostly −y with a +z part (median (0.01, −0.83, 0.53) over a 15-minute worn
 *   session; the IMU sits pitched ~30–35° in the frame).
 * - The gyro is right-handed in that same frame: the up vector seen from the glasses follows
 *   `du/dt = −ω × u` (beats `+ω × u` in 79–93 % of 0.1–0.15 s windows).
 * - Head yaw to the left is a positive rotation about up: the Eye camera image fit gives content
 *   moving right for positive rotation about (−0.13, −0.81, 0.57) ≈ the accelerometer's up.
 * - **+x is the wearer's right**: nose up (image content moving down) is +gx.
 *
 * So `yaw = ω·up` and `pitch = ω·right`, with `right` = the horizontal part of +x.
 *
 * "Up" is the accelerometer low-pass with gyro propagation (a complementary filter): the previous
 * up is carried along by the gyro (`u −= (ω × u)·dt`, so a quick nod does not lag) and pulled
 * toward the normalized accelerometer with time constant [upTauSec]. A reading whose magnitude is
 * more than [ACCEL_TOLERANCE] off g (footsteps, bumps) does not pull. Both rates are smoothed with
 * an EMA of time constant [rateTauSec]. The sample interval comes from the glasses' own clock
 * (`deviceTimeNs`, 1 ms apart), clamped to [MAX_DT_SEC].
 */
class HeadRateEstimator(
    private val upTauSec: Float = UP_TAU_SEC,
    private val rateTauSec: Float = RATE_TAU_SEC,
) {

    companion object {
        const val UP_TAU_SEC = 0.5f
        const val RATE_TAU_SEC = 0.02f

        /** Longest sample interval used (a gap or a glasses reboot is not integrated). */
        const val MAX_DT_SEC = 0.05f

        const val GRAVITY = 9.81f

        /** Accelerometer readings outside g·(1 ± this) are mostly motion and do not correct up. */
        const val ACCEL_TOLERANCE = 0.25f

        /** Below this horizontal length of the right axis (head rolled near 90°) pitch is 0. */
        const val MIN_RIGHT_HORIZONTAL = 0.2f

        /** The wearer's right in the glasses' IMU frame. */
        val GLASSES_RIGHT = Vec3(1f, 0f, 0f)
    }

    /** Smoothed yaw rate (rad/s, positive = turning left); valid after [update] returned true. */
    var yaw = 0f
        private set

    /** Smoothed pitch rate (rad/s, positive = nose up); valid after [update] returned true. */
    var pitch = 0f
        private set

    /** Unit up vector in the glasses frame; null before the first usable accelerometer reading. */
    var up: Vec3? = null
        private set

    private var lastDeviceNs = 0L
    private var hasRates = false

    /**
     * Feeds one sample: [gyro] in rad/s, [accel] in m/s² (glasses frame), [deviceTimeNs] on the
     * glasses' clock. True when [yaw] / [pitch] hold rates (from the first sample with a usable
     * accelerometer reading on). A sample with a non-finite gyro is ignored.
     */
    fun update(gyro: Vec3, accel: Vec3, deviceTimeNs: Long): Boolean {
        if (!gyro.x.isFinite() || !gyro.y.isFinite() || !gyro.z.isFinite()) return hasRates
        val previous = up
        val elapsedSec = (deviceTimeNs - lastDeviceNs) / 1e9f
        lastDeviceNs = deviceTimeNs
        val dt = if (previous != null && elapsedSec > 0f) min(elapsedSec, MAX_DT_SEC) else 0f

        val aLen = accel.length
        val accelUp = if (aLen.isFinite() && abs(aLen - GRAVITY) <= GRAVITY * ACCEL_TOLERANCE) {
            accel * (1f / aLen)
        } else {
            null
        }
        var u = if (previous == null) {
            accelUp ?: return false
        } else {
            // A world-fixed vector seen from a frame turning at ω changes at −ω × u.
            val carried = previous - (gyro cross previous) * dt
            val pulled = if (accelUp != null) carried + (accelUp - carried) * (dt / (upTauSec + dt)) else carried
            pulled.normalized()
        }
        if (u.length == 0f) u = accelUp ?: previous ?: return false
        up = u

        val yawRaw = gyro dot u
        val rightH = GLASSES_RIGHT - u * (GLASSES_RIGHT dot u)
        val rightLen = rightH.length
        val pitchRaw = if (rightLen >= MIN_RIGHT_HORIZONTAL) (gyro dot rightH) / rightLen else 0f
        if (!hasRates) {
            yaw = yawRaw
            pitch = pitchRaw
            hasRates = true
        } else {
            val k = dt / (rateTauSec + dt)
            yaw += (yawRaw - yaw) * k
            pitch += (pitchRaw - pitch) * k
        }
        return true
    }

    fun reset() {
        yaw = 0f
        pitch = 0f
        up = null
        lastDeviceNs = 0L
        hasRates = false
    }
}
