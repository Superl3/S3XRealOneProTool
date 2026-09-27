package com.raphael.handmouse.overlay

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * "Is the light sensor covered?" (pocket, bag, face-down) from light + orientation + proximity.
 *
 * The proximity sensor alone is unreliable on recent Galaxy phones (it is a light-based virtual
 * sensor that often never reports "near" outside calls), so the main signal is: it is dark AND
 * the phone is not lying face-up / held facing the user. In a pocket the phone is upright or
 * upside down; face-down on a table the screen points at the floor. A dark room with the phone
 * on the desk or in the hand keeps the screen tilted up, so it is not treated as covered.
 *
 * The state flips only after the condition has held for [enterMs] (resp. been false for
 * [exitMs]); while a flip is pending [pending] is true, so callers can hold still instead of
 * reacting to a reading that may belong to the pocket.
 */
class CoverDetector(
    private val enterMs: Long = 1500,
    private val exitMs: Long = 700,
) {
    companion object {
        /** Below this the sensor may be covered (pocket fabric leaks a few lux). */
        const val DARK_LUX = 8f
        /** Screen-up component of gravity (z / |g|) below which the phone counts as not face-up
         * (~70° or more from lying flat face-up). */
        const val FACE_UP_MIN = 0.35f
    }

    var covered = false
        private set
    val pending: Boolean get() = sinceMs >= 0
    private var sinceMs = -1L

    /**
     * @param faceUp z / |g| from the accelerometer (1 = flat face-up, -1 = face-down), or null
     * when unknown; @param near proximity "near", or null when there is no proximity sensor.
     */
    fun update(nowMs: Long, lux: Float, faceUp: Float?, near: Boolean?): Boolean {
        val dark = lux < DARK_LUX
        val notFacingUp = faceUp != null && faceUp < FACE_UP_MIN
        val condition = near == true || (dark && notFacingUp)
        if (condition == covered) {
            sinceMs = -1
        } else {
            if (sinceMs < 0) sinceMs = nowMs
            if (nowMs - sinceMs >= if (condition) enterMs else exitMs) {
                covered = condition
                sinceMs = -1
            }
        }
        return covered
    }

    fun reset() {
        covered = false
        sinceMs = -1
    }
}

/**
 * Adaptation level modelled on the eye: it follows ambient light on a log scale (perception is
 * logarithmic, Weber–Fechner) with DIFFERENT speeds each way. Going dark it follows quickly: a
 * too-bright screen in a dark place glares, so dimming is what matters. Going bright it follows
 * slowly: a screen that stays a little dim for a while after walking into light is tolerable, and
 * it ignores brief flashes (headlights, a lamp passing through the view).
 */
class LightAdaptation(
    private val tauDarkMs: Float = 1500f,
    private val tauBrightMs: Float = 12_000f,
) {
    /** log10(lux + 1); NaN until the first reading. */
    var level = Float.NaN
        private set

    val lux: Float get() = if (level.isNaN()) Float.NaN else 10f.pow(level) - 1f

    fun step(targetLux: Float, dtMs: Long) {
        val target = log10(targetLux.coerceAtLeast(0f) + 1f)
        if (level.isNaN()) { level = target; return }
        val tau = if (target < level) tauDarkMs else tauBrightMs
        val k = 1f - exp(-dtMs.coerceAtLeast(0) / tau)
        level += (target - level) * k
    }

    fun reset() { level = Float.NaN }
}

/** Dim fraction for an adapted light level: none at or above [startLux], [maxDim] in the dark,
 * logarithmic in between. */
fun dimForLux(lux: Float, startLux: Float, maxDim: Float): Float {
    val t = (log10(startLux + 1f) - log10(lux.coerceAtLeast(0f) + 1f)) / log10(startLux + 1f)
    return t.coerceIn(0f, 1f) * maxDim
}
