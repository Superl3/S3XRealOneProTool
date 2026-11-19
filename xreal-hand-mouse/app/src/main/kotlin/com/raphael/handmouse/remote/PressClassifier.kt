package com.raphael.handmouse.remote

import kotlin.math.abs

/**
 * Tells a tap from a long press on one Remote panel button that has both (spec 2026-11-17
 * section S). Pure (no Android), main thread; one instance per button, reused press after press.
 *
 * Such a button cannot act when the finger lands, as the one-shot buttons do: the landing does not
 * know yet which of the two it is. So:
 * - the long press fires [LONG_PRESS_MS] after the landing ([tick]), once, while the finger has
 *   stayed inside [slopPx]; the lift then does nothing;
 * - a lift before that is the tap ([up]), unless the finger slid out of [slopPx] (a press the user
 *   took back);
 * - a cancel is a tap only when it is the accessibility input filter's (`FLAG_CANCELED`: another
 *   input device sent during the touch, spec section O, and such a touch never gets its lift) and
 *   came while the press was still a tap: before [tapMs] and inside [slopPx]. Any other cancel (the
 *   panel re-rendered, an edge swipe) does nothing.
 */
internal class PressClassifier(
    private val slopPx: Float,
    /** The longest press a filter-made cancel still counts as a tap ([RemoteControlMath.TAP_MAX_MS]). */
    private val tapMs: Long = RemoteControlMath.TAP_MAX_MS,
    private val longPressMs: Long = LONG_PRESS_MS,
) {
    enum class Outcome { NONE, TAP, LONG }

    private var down = false
    private var downAtMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var slid = false
    private var longFired = false

    /** When [tick] should next run (the long press is due), or null while nothing waits. */
    val nextDeadlineMs: Long?
        get() = if (down && !slid && !longFired) downAtMs + longPressMs else null

    /** The finger landed at [atMs] (its own event time, not a split event's downTime). */
    fun down(atMs: Long, x: Float, y: Float) {
        down = true
        downAtMs = atMs
        downX = x
        downY = y
        slid = false
        longFired = false
    }

    fun move(x: Float, y: Float) {
        if (down && !slid && abs(x - downX) + abs(y - downY) > slopPx) slid = true
    }

    /** [Outcome.LONG] once, when the long press is due at [nowMs]. */
    fun tick(nowMs: Long): Outcome {
        val due = nextDeadlineMs ?: return Outcome.NONE
        if (nowMs < due) return Outcome.NONE
        longFired = true
        return Outcome.LONG
    }

    /** The finger lifted at [atMs]; a long press that came due by then fires instead of the tap. */
    fun up(atMs: Long): Outcome {
        if (!down) return Outcome.NONE
        val late = tick(atMs)
        down = false
        if (late == Outcome.LONG) return Outcome.LONG
        return if (longFired || slid) Outcome.NONE else Outcome.TAP
    }

    /** The touch was cancelled at [atMs] at ([x], [y]); [byFilter]: the event has `FLAG_CANCELED`. */
    fun cancel(atMs: Long, x: Float, y: Float, byFilter: Boolean): Outcome {
        if (!down) return Outcome.NONE
        down = false
        val tap = byFilter && !longFired && !slid && atMs - downAtMs < minOf(tapMs, longPressMs) &&
            abs(x - downX) + abs(y - downY) <= slopPx
        return if (tap) Outcome.TAP else Outcome.NONE
    }

    companion object {
        /** How long a finger holds a button before its long action fires. */
        const val LONG_PRESS_MS = 500L
    }
}
