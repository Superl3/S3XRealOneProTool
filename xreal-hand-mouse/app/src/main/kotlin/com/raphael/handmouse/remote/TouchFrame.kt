package com.raphael.handmouse.remote

/**
 * One finger on the phone's touchscreen as the shell service reads it from the kernel
 * ([RawTouchReader]), ahead of Android's input pipeline. That pipeline's accessibility input filter
 * keeps one active device: once the Remote's virtual mouse sends, it cancels the finger's window
 * events and drops its MOVE and UP until the next DOWN (spec 2026-11-17 section O). Raw contacts
 * never pass through that filter, so a pad drag read from them keeps going.
 *
 * [x] and [y] are the touchscreen's axes normalized over their range to [0, 1), in the screen's
 * natural orientation (`Surface.ROTATION_0`: on a phone x runs across the short edge, y down the long
 * one). The scale is InputReader's, `(raw - min) / (max - min + 1)`, so [naturalToDisplay] lands on
 * the same pixel a window's MotionEvent would. Either is NaN while the kernel has not reported that
 * axis for the contact's slot since the reader opened the device: evdev only sends values that
 * changed, so a finger landing exactly where an unseen earlier one lifted gets no position until it
 * moves.
 *
 * @property id stays the same while the finger is down; every new touch gets a new one.
 */
data class TouchContact(val id: Int, val x: Float, val y: Float) {
    /** False while [x] or [y] is unknown (NaN). Such a contact is down but has no place yet. */
    val hasPosition: Boolean
        get() = !x.isNaN() && !y.isNaN()
}

/**
 * Every finger on the touchscreen at one moment: one per kernel report (SYN_REPORT) that added,
 * moved or lifted a contact.
 *
 * @property uptimeMs `SystemClock.uptimeMillis()` when the shell read that report: the clock of
 *   `MotionEvent.getEventTime`, about a millisecond after the kernel's own time.
 * @property contacts the contacts down, in slot order. Empty once the last finger lifted.
 * @property cancelLifts the contacts this frame lacks against the previous one were taken away,
 *   not lifted: the kernel dropped events (SYN_DROPPED), or the touchscreen driver cancelled the
 *   touch (Samsung's KEY_INT_CANCEL, which Android turns into ACTION_CANCEL). Like a cancel, such a
 *   lift must never click; a finger may in fact still be down.
 */
data class TouchFrame(val uptimeMs: Long, val contacts: List<TouchContact>, val cancelLifts: Boolean = false) {
    val anyDown: Boolean
        get() = contacts.isNotEmpty()

    companion object {
        /** Most contacts one frame carries over binder (two touchscreens of ten slots fit). */
        const val MAX_CONTACTS = 32
    }
}

/**
 * A [TouchFrame] as one int array, the body of the shell's oneway frame callback:
 * `[uptime high, uptime low, flags, (id, x bits, y bits) per contact]`, where flag bit 0 is
 * [TouchFrame.cancelLifts]. Ints keep the parcel compact and the format testable without Android.
 */
internal object TouchFrameCodec {
    private const val HEADER = 3
    private const val PER_CONTACT = 3
    private const val FLAG_CANCEL_LIFTS = 1

    fun encode(frame: TouchFrame): IntArray {
        val count = minOf(frame.contacts.size, TouchFrame.MAX_CONTACTS)
        val ints = IntArray(HEADER + count * PER_CONTACT)
        ints[0] = (frame.uptimeMs ushr 32).toInt()
        ints[1] = frame.uptimeMs.toInt()
        ints[2] = if (frame.cancelLifts) FLAG_CANCEL_LIFTS else 0
        for (i in 0 until count) {
            val contact = frame.contacts[i]
            val p = HEADER + i * PER_CONTACT
            ints[p] = contact.id
            ints[p + 1] = contact.x.toRawBits()
            ints[p + 2] = contact.y.toRawBits()
        }
        return ints
    }

    /** The frame in [ints], or null when they are not one (wrong length, too many contacts). */
    fun decode(ints: IntArray?): TouchFrame? {
        if (ints == null || ints.size < HEADER || (ints.size - HEADER) % PER_CONTACT != 0) return null
        val count = (ints.size - HEADER) / PER_CONTACT
        if (count > TouchFrame.MAX_CONTACTS) return null
        val uptimeMs = (ints[0].toLong() shl 32) or (ints[1].toLong() and 0xffffffffL)
        val contacts = List(count) { i ->
            val p = HEADER + i * PER_CONTACT
            TouchContact(ints[p], Float.fromBits(ints[p + 1]), Float.fromBits(ints[p + 2]))
        }
        return TouchFrame(uptimeMs, contacts, cancelLifts = ints[2] and FLAG_CANCEL_LIFTS != 0)
    }
}

/**
 * Pixel coordinates on display 0 for a [TouchContact]'s ([x], [y]), with the display turned by
 * [rotation] (`Surface.ROTATION_0..270` as 0..3, from `Display.getRotation()`) and
 * [naturalWidth] x [naturalHeight] its size at rotation 0. The result is in the rotated display's
 * coordinates, the space of `View.getLocationOnScreen`. It follows InputReader's own rotation of
 * touch points, which holds while the touchscreen's raw axes are the display's natural ones (true on
 * the SM-S937N: `InputDeviceOrientation: Rotation0`, raw rotation identity).
 */
fun naturalToDisplay(x: Float, y: Float, rotation: Int, naturalWidth: Int, naturalHeight: Int): Pair<Float, Float> {
    val px = x * naturalWidth
    val py = y * naturalHeight
    return when (rotation and 3) {
        1 -> py to naturalWidth - px
        2 -> naturalWidth - px to naturalHeight - py
        3 -> naturalHeight - py to px
        else -> px to py
    }
}
