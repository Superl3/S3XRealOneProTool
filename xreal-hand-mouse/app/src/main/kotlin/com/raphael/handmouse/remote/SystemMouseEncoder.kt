package com.raphael.handmouse.remote

import kotlin.math.truncate

/**
 * Turns Remote panel input into Linux input events for the virtual `XrealRemote Mouse` (the
 * uinput device the shell service drives, spec E). Each result is a flat `int[]` of
 * (type, code, value) triples, ready for `MOUSE_EVENTS`.
 *
 * Every array it returns satisfies the shell's `MouseEventPolicy`, so nothing it builds is
 * dropped there:
 * - its size is a multiple of 3, it holds at least one event and at most [MAX_EVENTS] events;
 * - it only holds `(EV_SYN, SYN_REPORT, 0)`, `(EV_KEY, BTN_LEFT | BTN_RIGHT | BTN_MIDDLE, 0 | 1)`
 *   and `(EV_REL, REL_X | REL_Y | REL_WHEEL | REL_HWHEEL, v)` with `1 <= |v| <= MAX_REL`;
 * - it ends with a `SYN_REPORT`, and every report inside it is closed by one.
 *
 * Pointer motion keeps the fractional pixels between calls (slow finger or gyro motion adds up
 * instead of being lost). Motion or wheel larger than one array can carry is clamped: the
 * excess is dropped, not carried over, so the pointer never keeps moving after the hand stopped.
 *
 * Pure Kotlin, no Android dependency. Not thread-safe (the Remote panel calls it from the main
 * thread).
 */
class SystemMouseEncoder {

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_REL = 2
        const val SYN_REPORT = 0
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val BTN_MIDDLE = 274
        const val REL_X = 0
        const val REL_Y = 1
        const val REL_HWHEEL = 6
        const val REL_WHEEL = 8

        /** Largest |value| of one `EV_REL` event the shell accepts. */
        const val MAX_REL = 4096

        /** Most events (triples) one array may hold. */
        const val MAX_EVENTS = 64
    }

    // Fractional pixels not sent yet, always in (-1, 1).
    private var remainderX = 0f
    private var remainderY = 0f

    /**
     * Pointer motion by ([dx], [dy]) pixels. Adds them to the kept fractions and emits the whole
     * pixels as `REL_X` / `REL_Y` (an axis whose whole part is 0 is left out) followed by
     * `SYN_REPORT`. Returns null when neither axis reached a whole pixel.
     *
     * A step beyond [MAX_REL] is split into several reports in the one array; past
     * [MAX_EVENTS] events the rest is dropped. Non-finite input counts as 0.
     */
    fun move(dx: Float, dy: Float): IntArray? {
        remainderX += if (dx.isFinite()) dx else 0f
        remainderY += if (dy.isFinite()) dy else 0f
        val wholeX = truncate(remainderX)
        val wholeY = truncate(remainderY)
        remainderX -= wholeX
        remainderY -= wholeY
        var x = wholeX.toInt()
        var y = wholeY.toInt()
        if (x == 0 && y == 0) return null

        val out = EventBuilder()
        while (x != 0 || y != 0) {
            val stepX = x.coerceIn(-MAX_REL, MAX_REL)
            val stepY = y.coerceIn(-MAX_REL, MAX_REL)
            val needed = (if (stepX != 0) 1 else 0) + (if (stepY != 0) 1 else 0) + 1
            if (!out.fits(needed)) break
            if (stepX != 0) out.add(EV_REL, REL_X, stepX)
            if (stepY != 0) out.add(EV_REL, REL_Y, stepY)
            out.syn()
            x -= stepX
            y -= stepY
        }
        return out.toArray()
    }

    /** Presses ([down] true) or releases one of [BTN_LEFT], [BTN_RIGHT], [BTN_MIDDLE]. */
    fun button(button: Int, down: Boolean): IntArray {
        requireButton(button)
        return EventBuilder().apply {
            add(EV_KEY, button, if (down) 1 else 0)
            syn()
        }.toArray()
    }

    /** Press and release of [button] (one of [BTN_LEFT], [BTN_RIGHT], [BTN_MIDDLE]). */
    fun click(button: Int): IntArray {
        requireButton(button)
        return EventBuilder().apply {
            add(EV_KEY, button, 1)
            syn()
            add(EV_KEY, button, 0)
            syn()
        }.toArray()
    }

    /**
     * Vertical wheel by [notches] (`REL_WHEEL`; positive = wheel turned away from the user, which
     * scrolls up). Null for 0. Beyond [MAX_REL] it is split into several reports, clamped at
     * [MAX_EVENTS] events.
     */
    fun wheel(notches: Int): IntArray? = scroll(REL_WHEEL, notches)

    /** Horizontal wheel by [notches] (`REL_HWHEEL`; positive scrolls right). Same rules as [wheel]. */
    fun hwheel(notches: Int): IntArray? = scroll(REL_HWHEEL, notches)

    /** Drops the kept fractional pixels (e.g. when the virtual mouse is reopened). */
    fun reset() {
        remainderX = 0f
        remainderY = 0f
    }

    private fun scroll(code: Int, notches: Int): IntArray? {
        if (notches == 0) return null
        var left = notches
        val out = EventBuilder()
        while (left != 0 && out.fits(2)) {
            val step = left.coerceIn(-MAX_REL, MAX_REL)
            out.add(EV_REL, code, step)
            out.syn()
            left -= step
        }
        return out.toArray()
    }

    private fun requireButton(button: Int) {
        require(button == BTN_LEFT || button == BTN_RIGHT || button == BTN_MIDDLE) {
            "Not a button of the virtual mouse: $button"
        }
    }

    /** Collects (type, code, value) triples, at most [MAX_EVENTS] of them. */
    private class EventBuilder {
        private val values = IntArray(MAX_EVENTS * 3)
        private var size = 0

        fun fits(events: Int): Boolean = size / 3 + events <= MAX_EVENTS

        fun add(type: Int, code: Int, value: Int) {
            values[size] = type
            values[size + 1] = code
            values[size + 2] = value
            size += 3
        }

        fun syn() = add(EV_SYN, SYN_REPORT, 0)

        fun toArray(): IntArray = values.copyOf(size)
    }
}
