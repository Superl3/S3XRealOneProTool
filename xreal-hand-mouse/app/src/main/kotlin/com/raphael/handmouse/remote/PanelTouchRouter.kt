package com.raphael.handmouse.remote

/**
 * Decides which source feeds the Remote touchpad's [PadGestureMachine], and when a finger is on the
 * panel at all (spec 2026-11-17 section P). Pure (no Android), main thread.
 *
 * Two sources describe the same fingers:
 * - The panel window's `MotionEvent`s: the touchpad view's own ([windowPadDown] .. [windowPadCancel])
 *   and the panel frame's, after its views handled them ([panelDown], [panelEnd]). Android's
 *   accessibility input filter cancels them as soon as the virtual mouse sends during the touch,
 *   and then drops that finger's MOVE and UP (spec section O).
 * - The touchscreen's raw frames, read by the shell service ([rawFrame]) once [rawStatus] says they
 *   flow. That filter never sees them.
 *
 * While raw frames flow, they feed the touchpad: a raw contact whose first placed frame lies on the
 * touchpad ([Area.PAD]) becomes a machine contact (its kernel id is the machine id), and the window's
 * touchpad events are only consumed, so nothing is fed twice. Otherwise the window feeds it as before.
 * The source is chosen per touch: a touch the window began feeding (before raw frames flowed) stays
 * with the window until its UP or CANCEL, and raw contacts that land meanwhile are left alone for
 * their whole touch. A raw stream that ends ([rawStatus] false) cancels its touchpad contacts; the
 * window takes over from its next first finger. A raw frame that took contacts away as a cancel
 * ([TouchFrame.cancelLifts]) cancels them too, instead of lifting them (no click).
 *
 * Contacts that move together (one window `ACTION_MOVE`, one raw frame) are placed first and then
 * evaluated once ([Output.padPlace], [Output.padMoved]), so the machine sees the midpoint of the
 * whole event, not of each half-applied finger.
 *
 * The panel is [touched] from the first finger of either source until the window's touch and every
 * raw touchpad contact are gone, because a cancelled window touch says nothing about the finger:
 * - [Output.holdAir] (`RemoteActions.padTouch(true)`) once per finger landing: a touchpad finger's
 *   from its raw contact while raw frames feed the pad (right after its [Output.padDown], so the air
 *   capture of the press has begun), every other finger's from the window.
 * - [Output.releaseAir] and [Output.touchEnded] once the panel is free; a filter-made CANCEL of a
 *   touchpad finger therefore no longer ends the air capture or runs a deferred re-render mid-drag.
 *
 * A finger that lands on the panel but not on the touchpad disarms tap-drag ([Output.otherTouch]),
 * from the window and from its raw contact (a second call is harmless).
 */
internal class PanelTouchRouter(
    private val out: Output,
    /** Diagnostics: which source a touchpad touch takes, raw landings, raw contacts cancelled. */
    private val log: (String) -> Unit = {},
) {
    interface Output {
        /** [PadGestureMachine.down]. */
        fun padDown(atMs: Long, id: Int, x: Float, y: Float)

        /** [PadGestureMachine.place]: one contact of an event or frame moved; [padMoved] follows. */
        fun padPlace(id: Int, x: Float, y: Float)

        /** [PadGestureMachine.moved]: the contacts placed since the last call moved together at [atMs]. */
        fun padMoved(atMs: Long)

        /** [PadGestureMachine.up]. */
        fun padUp(atMs: Long, id: Int)

        /** [PadGestureMachine.cancel]. */
        fun padCancel(atMs: Long, id: Int)

        /** [PadGestureMachine.cancelAll]. */
        fun padCancelAll(atMs: Long)

        /** [PadGestureMachine.otherTouch]: a finger landed elsewhere on the panel. */
        fun otherTouch()

        /** The first finger landed on the panel at [atMs]. */
        fun touchStarted(atMs: Long)

        /** A finger landed on the panel: hold the air mouse (`RemoteActions.padTouch(true)`). */
        fun holdAir()

        /** The last finger left the panel (`RemoteActions.padTouch(false)`). */
        fun releaseAir()

        /** The panel is free again, right after [releaseAir]: a re-render that waited may run. */
        fun touchEnded()
    }

    /** What lies under a raw contact. */
    enum class Area {
        /** The touchpad itself (not the controls floating over it: the enlarge switch, the round buttons). */
        PAD,

        /** Elsewhere on the panel window: a button, a gap, a control floating over the touchpad. */
        PANEL,

        /** Not on the panel. */
        OUTSIDE,
    }

    /** A raw contact on the panel now: [x], [y] in upright touchpad pixels (the pad's `getX`/`getY`). */
    class Spot(val x: Float, val y: Float, val area: Area)

    /** A raw contact this router has decided on; [pad] while it feeds the touchpad. */
    private class RawContact(var pad: Boolean, var x: Float, var y: Float)

    /** Raw frames flow ([rawStatus]). */
    var rawActive = false
        private set

    /** A finger is on the panel, from the window or as a raw touchpad contact. */
    var touched = false
        private set

    /** The window's touch is down: the panel frame's ACTION_DOWN came, its UP or CANCEL not yet. */
    private var windowTouch = false

    /** The touchpad's current window touch feeds the machine (it began while no raw frames flowed). */
    private var windowFeedsPad = false

    /** The touchpad view saw the finger of the event the panel frame dispatches now. */
    private var padSawDown = false

    /**
     * Raw contacts decided on, by id, until they lift. A contact that has no position yet, or landed
     * while the panel could not be placed, is not in it: its first placed frame decides.
     */
    private val raw = LinkedHashMap<Int, RawContact>()

    /** The touchpad view's ACTION_DOWN ([first]) or ACTION_POINTER_DOWN. */
    fun windowPadDown(atMs: Long, id: Int, x: Float, y: Float, first: Boolean) {
        padSawDown = true
        if (first) {
            windowFeedsPad = !rawActive
            log("touchpad window touch at (${x.toInt()}, ${y.toInt()}): " + if (windowFeedsPad) "fed from window events" else "raw frames feed it")
            // A first finger while the machine still holds window contacts: their lift never came.
            if (windowFeedsPad) out.padCancelAll(atMs)
        }
        if (windowFeedsPad) out.padDown(atMs, id, x, y)
    }

    /** One pointer of the touchpad view's ACTION_MOVE; [windowPadMoved] follows its last one. */
    fun windowPadMove(id: Int, x: Float, y: Float) {
        if (windowFeedsPad) out.padPlace(id, x, y)
    }

    /** The touchpad view's ACTION_MOVE at [atMs] is fully given ([windowPadMove] for each pointer). */
    fun windowPadMoved(atMs: Long) {
        if (windowFeedsPad) out.padMoved(atMs)
    }

    /** The touchpad view's ACTION_POINTER_UP, or ACTION_UP ([last]). */
    fun windowPadUp(atMs: Long, id: Int, last: Boolean) {
        if (windowFeedsPad) out.padUp(atMs, id)
        if (last) windowFeedsPad = false
    }

    /** The touchpad view's ACTION_CANCEL. */
    fun windowPadCancel(atMs: Long) {
        if (windowFeedsPad) out.padCancelAll(atMs)
        windowFeedsPad = false
    }

    /**
     * The panel frame dispatched an ACTION_DOWN ([first]) or ACTION_POINTER_DOWN, after its views
     * (the touchpad's listener included) handled it.
     */
    fun panelDown(atMs: Long, first: Boolean) {
        val onPad = padSawDown
        padSawDown = false
        if (first) windowTouch = true
        start(atMs)
        if (!onPad) out.otherTouch()
        // A touchpad finger fed by raw frames gets its hold from its raw contact.
        if (!onPad || windowFeedsPad) out.holdAir()
    }

    /** The current panel frame's ACTION_UP or ACTION_CANCEL. */
    fun panelEnd() {
        windowTouch = false
        padSawDown = false
        endIfFree()
    }

    /**
     * Raw frames start or stop flowing at [atMs]. Stopping cancels the touchpad contacts they fed
     * (no lift came) and keeps every contact known, so a finger still down when frames return is
     * not taken for a new touch.
     */
    fun rawStatus(available: Boolean, atMs: Long) {
        if (available == rawActive) return
        rawActive = available
        if (available) return
        var cancelled = 0
        for ((id, contact) in raw) {
            if (!contact.pad) continue
            contact.pad = false
            cancelled++
            out.padCancel(atMs, id)
        }
        if (cancelled == 0) return
        log("raw frames stopped: $cancelled touchpad contact(s) cancelled")
        endIfFree()
    }

    /**
     * One raw frame. [locate] places a contact on the panel as it is now, or returns null when the
     * panel cannot be mapped (no touchpad, a layout under way, a rotation the window is not turned
     * for yet); it is only called for contacts that land, wait to be placed, or feed the touchpad.
     * Like Android's InputReader the frame is applied as lifts (cancels for a
     * [TouchFrame.cancelLifts] frame), then moves, then landings, all at the frame's time.
     */
    fun rawFrame(frame: TouchFrame, locate: (TouchContact) -> Spot?) {
        if (!rawActive) return
        val atMs = frame.uptimeMs
        var padLifted = false
        val known = raw.entries.iterator()
        while (known.hasNext()) {
            val (id, contact) = known.next()
            if (frame.contacts.any { it.id == id }) continue
            known.remove()
            if (contact.pad) {
                padLifted = true
                if (frame.cancelLifts) {
                    log("raw touchpad contact id=$id cancelled by the touchscreen")
                    out.padCancel(atMs, id)
                } else {
                    out.padUp(atMs, id)
                }
            }
        }
        var placed = false
        for (c in frame.contacts) {
            val contact = raw[c.id] ?: continue
            if (!contact.pad || !c.hasPosition) continue
            // An unplaceable frame is skipped; the next one carries the motion.
            val spot = locate(c) ?: continue
            if (spot.x == contact.x && spot.y == contact.y) continue
            contact.x = spot.x
            contact.y = spot.y
            out.padPlace(c.id, spot.x, spot.y)
            placed = true
        }
        if (placed) out.padMoved(atMs)
        for (c in frame.contacts) {
            // A contact without a position yet is decided by its first placed frame.
            if (c.id in raw || !c.hasPosition) continue
            if (windowFeedsPad) {
                // A window-fed touch keeps the touchpad: this finger is left alone until it lifts.
                raw[c.id] = RawContact(false, 0f, 0f)
                continue
            }
            // A panel that cannot be placed now (a layout or a rotation under way; the window still
            // takes the finger, but does not feed it): the first placed frame decides.
            val spot = locate(c) ?: continue
            if (spot.area == Area.PAD) {
                raw[c.id] = RawContact(true, spot.x, spot.y)
                log("raw touchpad down id=${c.id} at (${spot.x.toInt()}, ${spot.y.toInt()})")
                out.padDown(atMs, c.id, spot.x, spot.y)
                start(atMs)
                out.holdAir()
            } else {
                // Off the touchpad: left alone until it lifts.
                raw[c.id] = RawContact(false, 0f, 0f)
                if (spot.area == Area.PANEL) out.otherTouch()
            }
        }
        if (padLifted) endIfFree()
    }

    /**
     * The touchpad view went away (a re-render or the panel's removal; the caller resets the
     * machine): the window's touch is forgotten and raw touchpad contacts are left alone until they
     * lift, with no callback (as the window's own touch on the old view is).
     */
    fun reset() {
        windowTouch = false
        windowFeedsPad = false
        padSawDown = false
        touched = false
        for (contact in raw.values) contact.pad = false
    }

    private fun start(atMs: Long) {
        if (touched) return
        touched = true
        out.touchStarted(atMs)
    }

    private fun endIfFree() {
        if (!touched || windowTouch || raw.values.any { it.pad }) return
        touched = false
        out.releaseAir()
        out.touchEnded()
    }
}
