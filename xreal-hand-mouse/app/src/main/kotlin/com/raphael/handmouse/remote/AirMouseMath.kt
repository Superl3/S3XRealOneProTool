package com.raphael.handmouse.remote

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * A 3-vector. [AirMouseMath] uses the phone's sensor frame (+x right edge, +y top edge, +z out of
 * the screen); [HeadRateEstimator] the glasses' IMU frame.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    val length: Float
        get() = sqrt(x * x + y * y + z * z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)

    /** This vector scaled to length 1; a zero (or non-finite) vector comes back as zero. */
    fun normalized(): Vec3 {
        val len = length
        return if (len > 1e-9f && len.isFinite()) this * (1f / len) else Vec3(0f, 0f, 0f)
    }
}

/**
 * Air-mouse mapping: phone gyro rate → DeX pointer motion, in the phone's sensor frame.
 *
 * - `up` is the normalized gravity reading (at rest it points up — same sign as the
 *   accelerometer).
 * - Horizontal: turning left (counter-clockwise seen from above) gives ω·up > 0 → pointer left,
 *   `dx = -gain · softDeadZone(ω·up) · dt`.
 * - Vertical: the aiming direction is `p = horiz(-z) + horiz(top(phys))` (horiz removes the
 *   `up` component), so it works both held like a remote (screen up, aimed with the top edge) and
 *   like a camera (upright, aimed with the back). `right = normalize(p) × up`, and raising the
 *   front is a positive rotation about `right`, so `dy = -gain · softDeadZone(ω·right) · dt`
 *   (pointer up).
 * - A degenerate pose (no gravity, or `|p|` below [MIN_POINTING]) gives no motion for that sample.
 * - Head-relative (2026-11-18): with the head's rates from the glasses' IMU ([HeadRates], same
 *   conventions: yaw about world up positive turning left, pitch positive raising the front), the
 *   pointer follows the phone's rotation relative to the head: `ω·up − headYaw` and
 *   `ω·right − headPitch`. Turning the body or walking turns phone and head together, so the
 *   pointer stays. The soft dead zone is applied after the subtraction.
 */
object AirMouseMath {
    const val DEAD_ZONE_RAD_S = 0.03f
    const val BASE_GAIN_PX_PER_RAD = 1100f

    /** Longest sample interval used; a longer gap (sensor hiccup, first sample) is clamped. */
    private const val MAX_DT_SEC = 0.05f

    /** Below this gravity magnitude there is no usable "up". */
    private const val MIN_GRAVITY = 1e-3f

    /** Below this horizontal aiming length the pitch axis is undefined. */
    private const val MIN_POINTING = 0.2f

    private val NO_MOTION = Pair(0f, 0f)

    /**
     * The device edge that points away from the user for a physical hold of
     * [physicalQuarterTurns] (Surface.ROTATION units, normalized mod 4):
     * 0 → +y, 1 → +x, 2 → −y, 3 → −x.
     */
    fun topDirection(physicalQuarterTurns: Int): Vec3 = when (physicalQuarterTurns.mod(4)) {
        0 -> Vec3(0f, 1f, 0f)
        1 -> Vec3(1f, 0f, 0f)
        2 -> Vec3(0f, -1f, 0f)
        else -> Vec3(-1f, 0f, 0f)
    }

    /**
     * Soft dead zone `sign(r)·max(0, |r| − dz)`: rest drift and tremor below [dz] vanish, and the
     * output grows from zero at the threshold instead of jumping.
     */
    fun softDeadZone(rate: Float, dz: Float = DEAD_ZONE_RAD_S): Float {
        val excess = abs(rate) - dz
        return if (excess > 0f) sign(rate) * excess else 0f
    }

    /**
     * Pixel motion (dx, dy) for one gyro sample [gyro] (rad/s, sensor frame) given the latest
     * [gravity] reading (any scale), the physical hold [physicalQuarterTurns], the sample
     * interval [dtSec] (clamped to 0..0.05 s) and [gainPxPerRad]. A degenerate pose gives (0, 0).
     *
     * [headYawRate] / [headPitchRate] (rad/s, [HeadRates] conventions) are subtracted from the
     * phone's yaw / pitch rate before the soft dead zone [deadZone] (rad/s); the defaults (0) keep
     * the phone-only mapping. A non-finite head rate counts as 0, and a negative or NaN dead zone
     * as none.
     */
    fun pointerDelta(
        gyro: Vec3,
        gravity: Vec3,
        physicalQuarterTurns: Int,
        dtSec: Float,
        gainPxPerRad: Float,
        headYawRate: Float = 0f,
        headPitchRate: Float = 0f,
        deadZone: Float = DEAD_ZONE_RAD_S,
    ): Pair<Float, Float> {
        if (!(dtSec > 0f)) return NO_MOTION
        val dt = dtSec.coerceAtMost(MAX_DT_SEC)
        val g = gravity.length
        if (!(g >= MIN_GRAVITY) || !g.isFinite()) return NO_MOTION
        val up = gravity * (1f / g)
        val pointing = horizontal(Vec3(0f, 0f, -1f), up) +
            horizontal(topDirection(physicalQuarterTurns), up)
        val reach = pointing.length
        if (!(reach >= MIN_POINTING)) return NO_MOTION
        // Both factors are unit length and perpendicular, so `right` is a unit vector.
        val right = (pointing * (1f / reach)) cross up
        val dz = if (deadZone > 0f) deadZone else 0f
        val headYaw = if (headYawRate.isFinite()) headYawRate else 0f
        val headPitch = if (headPitchRate.isFinite()) headPitchRate else 0f
        val dx = -gainPxPerRad * softDeadZone((gyro dot up) - headYaw, dz) * dt
        val dy = -gainPxPerRad * softDeadZone((gyro dot right) - headPitch, dz) * dt
        return Pair(dx, dy)
    }

    /** [v] with its component along the unit vector [up] removed. */
    private fun horizontal(v: Vec3, up: Vec3): Vec3 = v - up * (v dot up)
}

/**
 * Air-mouse motion held back while a touchpad press is undecided (spec 2026-11-17 section N, item
 * 3). Before, the press froze the air mouse and its motion was lost; now it is kept, so a press that
 * becomes a long-press drag can replay it right after the button goes down at the press point,
 * while a tap discards it and clicks where the press began.
 *
 * - Samples before the jolt deadline ([begin], [extendJolt]) are dropped: pressing the phone
 *   tilts it, and that is not aiming.
 * - Later samples are summed as a net vector (sway back and forth cancels).
 * - A capture nobody ends lapses at its expiry: the sample that finds it lapsed is not taken, so a
 *   lost resolution never leaves the air mouse dead.
 * - Shadow log only: when the held motion first reached [begin]'s `shadowPx`. That is when an early
 *   "aim to drag" promotion would have fired; it is logged to calibrate one, never acted on (the
 *   reviews found it misfires on deliberate taps and slow strokes).
 *
 * Pure; times are uptime milliseconds in non-decreasing order. Main thread, like [AirMouse].
 */
class AirPressCapture {
    /** What a capture held when it ended. */
    data class Result(
        /** Net motion after the jolt deadline: what a drag replays. */
        val dx: Float,
        val dy: Float,
        /** Net motion dropped as jolt (for the log). */
        val joltX: Float,
        val joltY: Float,
        /** Milliseconds from [begin] to the moment the held motion reached `shadowPx`, or null. */
        val shadowAfterMs: Long?,
    )

    /** A capture is under way: [offer] takes every sample until it ends or lapses. */
    var active = false
        private set

    private var startMs = 0L
    private var joltUntilMs = 0L
    private var expiresAtMs = 0L
    private var shadowPx = Float.POSITIVE_INFINITY
    private var shadowAtMs: Long? = null
    private var heldX = 0f
    private var heldY = 0f
    private var joltX = 0f
    private var joltY = 0f

    /**
     * Starts a capture at [startMs] (replacing one under way): samples before [joltUntilMs] are
     * dropped, and the capture lapses at [expiresAtMs].
     */
    fun begin(startMs: Long, joltUntilMs: Long, expiresAtMs: Long, shadowPx: Float) {
        active = true
        this.startMs = startMs
        this.joltUntilMs = joltUntilMs
        this.expiresAtMs = expiresAtMs
        this.shadowPx = shadowPx
        shadowAtMs = null
        heldX = 0f
        heldY = 0f
        joltX = 0f
        joltY = 0f
    }

    /** Another jolt (a second finger landing): drops samples until [untilMs] too. */
    fun extendJolt(untilMs: Long) {
        if (active && untilMs > joltUntilMs) joltUntilMs = untilMs
    }

    /**
     * Offers the sample ([dx], [dy]) taken at [atMs]. True when the capture took it (held or
     * dropped as jolt), so the caller applies nothing; false when no capture is under way or it
     * lapsed (it ends here, and the sample is the caller's to apply).
     */
    fun offer(atMs: Long, dx: Float, dy: Float): Boolean {
        if (!active) return false
        if (atMs >= expiresAtMs) {
            end()
            return false
        }
        if (atMs < joltUntilMs) {
            joltX += dx
            joltY += dy
            return true
        }
        heldX += dx
        heldY += dy
        if (shadowAtMs == null && hypot(heldX, heldY) >= shadowPx) shadowAtMs = atMs
        return true
    }

    /** Ends the capture and returns what it held; null when none was under way. */
    fun end(): Result? {
        if (!active) return null
        active = false
        return Result(heldX, heldY, joltX, joltY, shadowAtMs?.let { it - startMs })
    }
}

/**
 * Recent air-mouse motion, so the jolt of pressing the touchpad can be undone. Times are
 * milliseconds on one monotonic clock and are expected in non-decreasing order. Not thread-safe:
 * the air mouse and the touchpad both run on the main thread.
 */
class AirMouseHistory(private val keepMs: Long = 300) {
    private class Entry(val tMs: Long, val dx: Float, val dy: Float)

    private val entries = ArrayDeque<Entry>()

    /** Records a motion emitted at [tMs]; entries more than [keepMs] older than [tMs] are dropped. */
    fun add(tMs: Long, dx: Float, dy: Float) {
        val oldest = tMs - keepMs
        while (entries.isNotEmpty() && entries.first().tMs < oldest) entries.removeFirst()
        entries.addLast(Entry(tMs, dx, dy))
    }

    /**
     * The summed motion of the entries recorded at or after [tMs]; those entries are removed
     * (older ones stay until they expire).
     */
    fun takeSince(tMs: Long): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.tMs >= tMs) {
                sx += e.dx
                sy += e.dy
                it.remove()
            }
        }
        return Pair(sx, sy)
    }

    fun clear() {
        entries.clear()
    }
}
