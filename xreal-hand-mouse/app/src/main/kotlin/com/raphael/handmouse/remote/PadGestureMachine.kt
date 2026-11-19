package com.raphael.handmouse.remote

import kotlin.math.abs

/**
 * What the Remote touchpad does, as [PadGestureMachine] decides it. Main thread. Distances are
 * touchpad pixels in the upright panel (the caller scales them to pointer pixels).
 */
interface PadGestureOutput {
    /**
     * Whether the DeX system cursor is in use. Read when the second finger lands (wheel or swipe,
     * so one gesture never mixes the two) and before every wheel notch.
     */
    val systemCursorActive: Boolean

    /**
     * The first finger landed at [atMs]: a press that ends in exactly one of [tap], [beginDrag]
     * or [pressAbandoned] (or, if the touchpad goes away first, nothing more).
     */
    fun pressStarted(atMs: Long)

    /**
     * The press [pressStarted] began will neither click nor drag: the finger crossed the touch
     * slop (a pointer stroke), another finger landed, the touch was cancelled, or a tap-drag
     * could not start.
     */
    fun pressAbandoned(atMs: Long)

    /** Moves the pointer by ([dx], [dy]) touchpad pixels. */
    fun move(dx: Float, dy: Float)

    /** Left click at the pointer. */
    fun tap()

    /** Left button down at the pointer; true when a drag is now held (false: nothing happened). */
    fun beginDrag(): Boolean

    /** Releases the drag [beginDrag] began. */
    fun endDrag()

    /** Two-finger wheel; negative = fingers moved up. Only while [systemCursorActive]. */
    fun scroll(notches: Int)

    /** One two-finger swipe of the overlay cursor mode; [up] = fingers moved up. */
    fun swipe(up: Boolean)

    /** Two-finger tap with the system cursor. */
    fun secondaryClick()

    /** Three-finger tap. */
    fun back()

    /** A short vibration, so a drag that starts or ends is felt (the eyes are on the glasses). */
    fun haptic(kind: PadHaptic)
}

enum class PadHaptic { DRAG_START, DRAG_END }

/**
 * Touchpad tuning in touchpad pixels and milliseconds.
 *
 * @property slopPx Manhattan travel from the touch-down point past which a finger has moved
 *   (`ViewConfiguration.scaledTouchSlop`); also the travel limit of two- and three-finger taps.
 * @property scrollStepPx two-finger travel per wheel notch.
 * @property swipePx two-finger travel that fires the overlay mode's one swipe.
 * @property tapMaxMs a lift before this is a tap; a finger held still this long starts a drag.
 * @property tapDragWindowMs a new touch this soon after a tap's lift is armed for a tap-drag.
 * @property doubleClickGuardMs least time from a click to the button press of a tap-drag. Apps
 *   and the DeX caption read a button press within `ViewConfiguration.getDoubleTapTimeout()`
 *   (300 ms) of a click as a double click (open the file, maximize the window); 50 ms more covers
 *   the shell and uinput latency between the two.
 */
data class PadGestureConfig(
    val slopPx: Float,
    val scrollStepPx: Float,
    val swipePx: Float,
    val tapMaxMs: Long = RemoteControlMath.TAP_MAX_MS,
    val tapDragWindowMs: Long = TAP_DRAG_WINDOW_MS,
    val doubleClickGuardMs: Long = DOUBLE_CLICK_GUARD_MS,
) {
    companion object {
        const val TAP_DRAG_WINDOW_MS = 250L
        const val DOUBLE_CLICK_GUARD_MS = 350L
    }
}

/**
 * The Remote touchpad's gestures, fed by contact events from any source: the panel window's
 * `MotionEvent`s, or the touchscreen's raw frames read by the shell. Pure (no Android), main thread.
 *
 * Input: [down], [move], [up] and [cancel] per contact id, with the touch's own uptime in ms (the
 * clock of `MotionEvent.getEventTime` and `SystemClock.uptimeMillis`) and its position in upright
 * touchpad pixels. Contacts that move in one event or frame are fed as one [place] each and then
 * one [moved]: the midpoint of several fingers is judged once per event, so fingers that move
 * apart (or jitter independently) do not add up travel or turn the wheel through a half-applied
 * midpoint. [move] is [place] and [moved] for one contact.
 * [tick] runs the timers: call it at [nextDeadlineMs] (a late call is fine, and every input first
 * catches up on a deadline it passed, so the result does not depend on Handler latency).
 *
 * One finger:
 * - Motion moves the pointer at once, also inside the touch slop: holding it back would freeze the
 *   pointer at the start of every stroke.
 * - Lifted before [PadGestureConfig.tapMaxMs] without crossing the slop: a click ([PadGestureOutput.tap]).
 * - Held still for `tapMaxMs`: a drag ([PadGestureOutput.beginDrag]) until the lift.
 * - Crossing the slop makes the press a pointer stroke ([PadGestureOutput.pressAbandoned]).
 *
 * Tap-drag (laptop style, while [tapDragEnabled]): a touch that lands within
 * [PadGestureConfig.tapDragWindowMs] of a tap's lift is armed. Its motion is held back while it
 * stays inside the slop, so the button can go down where it landed. Crossing the slop starts a
 * drag there (the button, then the held motion, then live motion); lifting inside the slop is a
 * plain second click (a double click); holding still is the ordinary long-press drag. The press of
 * a tap-drag waits until [PadGestureConfig.doubleClickGuardMs] after the click, holding the motion
 * meanwhile, so it is not read as a double click; lifted before that, the held motion is applied
 * as a pointer move and nothing is pressed. The second click of a double click does not arm.
 *
 * Several fingers: the second finger ends the press (a held drag is released). With the system
 * cursor two fingers turn the wheel continuously and a two-finger tap right-clicks; with the overlay
 * cursor they swipe once per gesture. A third finger landing on a still two-finger touch makes a
 * three-finger tap (back). Taps need every finger up within `tapMaxMs` of the last landing and the
 * fingers' travel under the slop.
 *
 * A cancel ends the gesture without a click; a held drag is released.
 */
class PadGestureMachine(
    private val out: PadGestureOutput,
    private val config: PadGestureConfig,
    /** Whether tap-drag is on; read when a first finger lands. */
    private val tapDragEnabled: () -> Boolean = { true },
) {
    private enum class Press {
        /** No finger. */
        IDLE,

        /** One finger, not decided yet: tap, long-press drag, stroke or (armed) tap-drag. */
        PENDING,

        /** An armed press crossed the slop and waits for the double-click guard, holding motion. */
        DEFERRED,

        /** A drag is held. */
        DRAGGING,

        /** Nothing pending: one finger moves the pointer, several make their gesture. */
        FREE,
    }

    private class Contact(var x: Float, var y: Float)

    private val contacts = LinkedHashMap<Int, Contact>()
    private var press = Press.IDLE

    /** The touch was cancelled: its other contacts do nothing until they lift. */
    private var cancelled = false

    /** A contact was [place]d since the last [moved]. */
    private var placed = false

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** The finger crossed the slop (sticky for the touch). */
    private var moved = false

    /** This press is a tap-drag candidate; its motion is held in [heldX], [heldY]. */
    private var armed = false
    private var heldX = 0f
    private var heldY = 0f

    /** [Press.DEFERRED]: when the tap-drag may press the button. */
    private var dragAt = 0L

    /** The lift of the last tap that may arm the next touch; consumed by that touch. */
    private var lastTapUpAt: Long? = null

    /** The lift of the last click, for the double-click guard. */
    private var lastClickAt: Long? = null

    private var twoFinger = false
    private var swipeTriggered = false
    private var twoFingerY = 0f
    private var twoFingerSystem = false
    private var twoFingerTap = false
    private var secondDownAt = 0L
    private var twoFingerTravel = 0f
    private var lastAverageX = 0f
    private var lastAverageY = 0f
    private var threeFingerTap = false
    private var thirdDownAt = 0L

    /** When [tick] has something to decide (uptime ms), or null. */
    val nextDeadlineMs: Long?
        get() = when (press) {
            Press.PENDING -> downAt + config.tapMaxMs
            Press.DEFERRED -> dragAt
            else -> null
        }

    /** A drag is held. */
    val dragging: Boolean
        get() = press == Press.DRAGGING

    /** Contact [id] landed at ([x], [y]). */
    fun down(atMs: Long, id: Int, x: Float, y: Float) {
        if (contacts.isEmpty()) {
            firstDown(atMs, id, x, y)
            return
        }
        contacts[id] = Contact(x, y)
        if (cancelled) return
        val count = contacts.size
        if (!twoFinger && count == 2) {
            // The second finger: a tap may follow unless the first one moved or dragged.
            twoFingerSystem = out.systemCursorActive
            twoFingerTap = !moved && press != Press.DRAGGING
            secondDownAt = atMs
            twoFingerTravel = 0f
        } else if (count == 3) {
            // The third finger turns a still two-finger touch into a back tap candidate.
            threeFingerTap = twoFingerTap
            thirdDownAt = atMs
            twoFingerTap = false
        } else {
            twoFingerTap = false
            threeFingerTap = false
        }
        endPress(atMs)
        twoFinger = true
        swipeTriggered = false
        twoFingerY = averageY()
        lastAverageX = averageX()
        lastAverageY = twoFingerY
    }

    /** Contact [id] is now at ([x], [y]), alone in its event: [place] and [moved]. */
    fun move(atMs: Long, id: Int, x: Float, y: Float) {
        place(id, x, y)
        moved(atMs)
    }

    /** Contact [id] is now at ([x], [y]); the gesture follows at the event's [moved]. */
    fun place(id: Int, x: Float, y: Float) {
        val contact = contacts[id] ?: return
        contact.x = x
        contact.y = y
        placed = true
    }

    /** The contacts [place]d since the last call moved together, in one event at [atMs]. */
    fun moved(atMs: Long) {
        if (!placed) return
        placed = false
        if (cancelled) return
        catchUp(atMs)
        if (contacts.size >= 2 || twoFinger) {
            severalMove()
        } else {
            val only = contacts.values.firstOrNull() ?: return
            oneMove(atMs, only.x, only.y)
        }
    }

    /** Contact [id] lifted. */
    fun up(atMs: Long, id: Int) {
        contacts.remove(id) ?: return
        if (cancelled) {
            if (contacts.isEmpty()) endGesture()
            return
        }
        if (contacts.isNotEmpty()) {
            fingerLifted()
            return
        }
        catchUp(atMs)
        when (press) {
            Press.DRAGGING -> releaseDrag()
            // A quick flick before the guard: the finger's motion stays a pointer move.
            Press.DEFERRED -> {
                flushHeld()
                out.pressAbandoned(atMs)
            }
            Press.PENDING ->
                if (RemoteControlMath.isTap(lifted = true, moved = moved, twoFinger = twoFinger, elapsedMs = atMs - downAt)) {
                    // An armed press lifted inside the slop: the double click lands where it touched.
                    heldX = 0f
                    heldY = 0f
                    out.tap()
                    lastClickAt = atMs
                    // The second click of a double click does not arm a third touch.
                    lastTapUpAt = if (armed) null else atMs
                } else {
                    out.pressAbandoned(atMs)
                }
            Press.FREE ->
                // Two-finger tap: every finger up within the tap time of the second one, no wheel
                // turned, travel under the slop. The overlay cursor has no right click.
                if (twoFingerSystem && twoFingerTap && atMs - secondDownAt < config.tapMaxMs) {
                    out.secondaryClick()
                } else if (threeFingerTap && atMs - thirdDownAt < config.tapMaxMs) {
                    out.back()
                }
            Press.IDLE -> Unit
        }
        endGesture()
    }

    /**
     * Contact [id] was taken away without a lift (the window's `ACTION_CANCEL`, or the raw source
     * lost). The whole touch ends as a cancel: nothing clicks and a held drag is released; its
     * other contacts do nothing until they lift or are cancelled too ([cancelAll] cancels them
     * all, as a window `ACTION_CANCEL` does).
     */
    fun cancel(atMs: Long, id: Int) {
        contacts.remove(id) ?: return
        if (!cancelled) {
            cancelled = true
            when (press) {
                Press.DRAGGING -> releaseDrag()
                Press.DEFERRED -> {
                    flushHeld()
                    out.pressAbandoned(atMs)
                }
                Press.PENDING -> out.pressAbandoned(atMs)
                else -> Unit
            }
            press = Press.FREE
            lastTapUpAt = null
        }
        if (contacts.isEmpty()) endGesture()
    }

    /**
     * [cancel] for every contact still down: the source lost the touch (the window's first
     * `ACTION_DOWN` while contacts remain means their lift never came; the raw reader stopped).
     */
    fun cancelAll(atMs: Long) {
        for (id in contacts.keys.toList()) cancel(atMs, id)
    }

    /** Runs the timers due at [atMs] (see [nextDeadlineMs]). */
    fun tick(atMs: Long) {
        if (!cancelled) catchUp(atMs)
    }

    /** A finger landed elsewhere on the panel (a button): the next touch is not a tap-drag. */
    fun otherTouch() {
        lastTapUpAt = null
    }

    /**
     * The touchpad went away (the panel was removed or re-rendered) while a touch may still be on
     * it: a held drag is released, a pending press abandoned, and its contacts are forgotten (their
     * later events do nothing). The last tap is kept, so a re-render between a tap and the next
     * touch does not disarm a tap-drag.
     */
    fun reset(atMs: Long) {
        when (press) {
            Press.DRAGGING -> out.endDrag()
            Press.PENDING, Press.DEFERRED -> out.pressAbandoned(atMs)
            else -> Unit
        }
        contacts.clear()
        endGesture()
    }

    private fun firstDown(atMs: Long, id: Int, x: Float, y: Float) {
        contacts[id] = Contact(x, y)
        cancelled = false
        placed = false
        downAt = atMs
        downX = x
        downY = y
        lastX = x
        lastY = y
        moved = false
        twoFinger = false
        swipeTriggered = false
        twoFingerSystem = false
        twoFingerTap = false
        threeFingerTap = false
        val tapUp = lastTapUpAt
        lastTapUpAt = null
        armed = tapUp != null && atMs >= tapUp && atMs - tapUp <= config.tapDragWindowMs && tapDragEnabled()
        heldX = 0f
        heldY = 0f
        press = Press.PENDING
        out.pressStarted(atMs)
    }

    private fun oneMove(atMs: Long, x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        val crossed = !moved && abs(x - downX) + abs(y - downY) > config.slopPx
        if (crossed) moved = true
        // The finger left after the other one lifted still counts against a two-finger tap.
        if (twoFingerTap || threeFingerTap) {
            twoFingerTravel += abs(dx) + abs(dy)
            if (twoFingerTravel > config.slopPx) {
                twoFingerTap = false
                threeFingerTap = false
            }
        }
        when (press) {
            Press.PENDING -> when {
                armed -> {
                    heldX += dx
                    heldY += dy
                    if (crossed) startTapDrag(atMs)
                }
                crossed -> {
                    press = Press.FREE
                    out.pressAbandoned(atMs)
                    out.move(dx, dy)
                }
                else -> out.move(dx, dy)
            }
            Press.DEFERRED -> {
                heldX += dx
                heldY += dy
            }
            else -> out.move(dx, dy)
        }
    }

    private fun severalMove() {
        val y = averageY()
        val dy = y - twoFingerY
        if (twoFingerTap || threeFingerTap) {
            val x = averageX()
            twoFingerTravel += abs(x - lastAverageX) + abs(y - lastAverageY)
            lastAverageX = x
            lastAverageY = y
            if (twoFingerTravel > config.slopPx) {
                twoFingerTap = false
                threeFingerTap = false
            }
        }
        if (twoFingerSystem) {
            // Continuous wheel: a notch per scroll step; fingers up (dy < 0) = content up =
            // negative notches. The remainder carries on. Once the system cursor is lost
            // mid-gesture the rest of it does nothing: the overlay cursor's scroll would swipe
            // once per notch.
            val notches = (dy / config.scrollStepPx).toInt()
            if (notches != 0) {
                if (out.systemCursorActive) out.scroll(notches)
                twoFingerY += notches * config.scrollStepPx
                twoFingerTap = false
                threeFingerTap = false
            }
        } else if (!swipeTriggered && abs(dy) > config.swipePx) {
            out.swipe(up = dy < 0f)
            swipeTriggered = true
        }
    }

    /**
     * Back to fewer fingers. With one left it moves the pointer again from where it is now; the
     * midpoint baseline no longer applies (it would fire a swipe), and [moved] keeps its lift from
     * counting as a tap.
     */
    private fun fingerLifted() {
        if (contacts.size == 1) {
            val remaining = contacts.values.first()
            lastX = remaining.x
            lastY = remaining.y
            twoFinger = false
            moved = true
        } else {
            twoFingerY = averageY()
            lastAverageX = averageX()
            lastAverageY = twoFingerY
        }
    }

    /**
     * Runs a deadline [atMs] has reached: the long-press drag (a pending press is always one finger
     * that has not left the slop), or a tap-drag past its guard.
     */
    private fun catchUp(atMs: Long) {
        when (press) {
            Press.PENDING -> if (atMs >= downAt + config.tapMaxMs) startDrag(atMs)
            Press.DEFERRED -> if (atMs >= dragAt) startDrag(atMs)
            else -> Unit
        }
    }

    private fun startTapDrag(atMs: Long) {
        val allowedAt = lastClickAt?.let { it + config.doubleClickGuardMs }
        if (allowedAt == null || atMs >= allowedAt) {
            startDrag(atMs)
        } else {
            press = Press.DEFERRED
            dragAt = allowedAt
        }
    }

    /** The button goes down where the pointer is (the press point), then the held motion follows. */
    private fun startDrag(atMs: Long) {
        if (out.beginDrag()) {
            press = Press.DRAGGING
            out.haptic(PadHaptic.DRAG_START)
        } else {
            press = Press.FREE
            out.pressAbandoned(atMs)
        }
        flushHeld()
    }

    private fun releaseDrag() {
        out.endDrag()
        out.haptic(PadHaptic.DRAG_END)
        press = Press.FREE
    }

    /** Another finger landed: the one-finger press ends without a click, a held drag is released. */
    private fun endPress(atMs: Long) {
        when (press) {
            Press.DRAGGING -> releaseDrag()
            Press.DEFERRED -> {
                flushHeld()
                out.pressAbandoned(atMs)
            }
            // The motion an armed press held is dropped: the two-finger tap acts at the press point.
            Press.PENDING -> out.pressAbandoned(atMs)
            else -> Unit
        }
        heldX = 0f
        heldY = 0f
        press = Press.FREE
    }

    private fun flushHeld() {
        if (heldX != 0f || heldY != 0f) out.move(heldX, heldY)
        heldX = 0f
        heldY = 0f
    }

    private fun endGesture() {
        press = Press.IDLE
        cancelled = false
        twoFinger = false
        twoFingerTap = false
        threeFingerTap = false
        heldX = 0f
        heldY = 0f
    }

    private fun averageX(): Float {
        if (contacts.isEmpty()) return 0f
        var sum = 0f
        for (c in contacts.values) sum += c.x
        return sum / contacts.size
    }

    private fun averageY(): Float {
        if (contacts.isEmpty()) return 0f
        var sum = 0f
        for (c in contacts.values) sum += c.y
        return sum / contacts.size
    }
}
