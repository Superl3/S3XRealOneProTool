package com.raphael.handmouse.remote

/**
 * When the Remote panel offers its rotate chip (spec 2026-11-17 section N, request 1). Pure, so
 * it runs in JVM tests; the overlay feeds it and shows [shown] in place. All times are uptime ms.
 *
 * The panel is drawn for a hold the user chose ([panelHold], [RemoteDockPrefs.panelHold]); the
 * orientation sensor never turns it. The sensed hold only makes this model offer the hold the
 * phone is held in now, like Android's rotate suggestion, and nothing turns until the user taps it:
 * - Only a quarter turn away from [panelHold] is offered, never a half turn and never reverse
 *   portrait ([REVERSE_PORTRAIT]). A hanging arm, or aiming the top edge low with the phone held
 *   flat, reads as upside down; such a reading must not become a sticky, inverted panel (and air
 *   mouse). The ⚙ sheet's manual control reaches every hold.
 * - The sensed hold must stay the same for [STABLE_MS] and the panel must have had no finger on it
 *   for [QUIET_MS]: a chip that appears the moment a finger lifts lands under the next tap.
 * - Nothing appears, disappears or times out while a finger is on the panel.
 * - Shown for [TIMEOUT_MS], then that hold counts as dismissed: it is not offered again until the
 *   sensed hold changes to another one. A dismissal survives collapsing, the phone screen turning
 *   off and on and a service restart (the overlay carries it), so a user who keeps a mismatched panel
 *   on purpose is not asked again after every wake.
 * - While the air mouse runs it takes its axes from [panelHold], so a mismatch makes it aim wrong:
 *   a dismissed hold is offered again after the next lift (the quiet second applies) or when the air
 *   mouse is switched on. The chip still times out, because a flat phone gives no reading: a false
 *   hold sensed before the phone went flat would otherwise keep the chip on screen.
 * - A press counts only when it landed [ARM_MS] or more after the chip appeared ([accept]): a press
 *   that began sooner was aimed at whatever the chip now covers.
 * - The sensed hold is stale (null) after the sensor restarts (panel shown, screen on) until a fresh
 *   reading, so a phone lying flat cannot bring back an old hold.
 */
class RotationSuggestion(
    panelHold: Int,
    private val stableMs: Long = STABLE_MS,
    private val quietMs: Long = QUIET_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val armMs: Long = ARM_MS,
) {
    /** Why the chip last went away, for the log. */
    enum class Hide { RETURNED, CHANGED, TIMEOUT, COLLAPSED, APPLIED }

    /** The hold the panel is drawn for (0..3). */
    var panelHold: Int = panelHold.mod(4)
        private set

    /** The hold the chip offers; null = hidden. */
    var shown: Int? = null
        private set

    /** When [shown] appeared. */
    var shownAtMs: Long = 0L
        private set

    /** The hold whose chip timed out (or that a manual turn left behind); not offered until the sensed hold changes. */
    var dismissed: Int? = null
        private set

    /** When [dismissed] was set; the air mouse re-offers it after a later lift or switch-on. */
    private var dismissedAtMs = NEVER

    /** Why [shown] last became null. */
    var lastHide: Hide? = null
        private set

    private var sensed: Int? = null
    private var sensedSinceMs = 0L
    private var expanded = false
    private var touching = false
    private var lastLiftMs = NEVER
    private var airMouse = false

    /** When the air mouse was last switched on. */
    private var airOnMs = NEVER

    /** A fresh sensor reading ([hold] in `Surface.ROTATION_*` units), or null: no fresh reading (sensor restarted). */
    fun onSensedHold(hold: Int?, nowMs: Long) {
        val h = hold?.mod(4)
        if (h != sensed) {
            // Turning to some other hold ends a dismissal; going stale or coming back to it does not.
            if (h != null && h != dismissed) dismissed = null
            sensed = h
            sensedSinceMs = nowMs
        }
        settle(nowMs)
    }

    /**
     * The panel is now drawn for [hold] (the chip was accepted, the manual control, or a sync). The
     * chip hides; a sensed hold other than the new one counts as dismissed, so cycling by hand is
     * not nagged back, and any older dismissal ends.
     */
    fun onPanelHold(hold: Int, nowMs: Long) {
        panelHold = hold.mod(4)
        hide(Hide.APPLIED)
        dismiss(sensed?.takeIf { it != panelHold }, nowMs)
        settle(nowMs)
    }

    /** The panel is expanded and the phone screen on ([open]), or not: a collapsed panel offers nothing. */
    fun onExpanded(open: Boolean, nowMs: Long) {
        expanded = open
        settle(nowMs)
    }

    /** The first finger landed on the panel ([down]) or the last one left it. */
    fun onTouch(down: Boolean, nowMs: Long) {
        if (down) {
            touching = true
            return
        }
        if (!touching) return
        touching = false
        lastLiftMs = nowMs
        settle(nowMs)
    }

    /** Whether the air mouse is switched on (it runs while the panel is expanded). */
    fun onAirMouse(on: Boolean, nowMs: Long) {
        if (on == airMouse) return
        airMouse = on
        if (on) airOnMs = nowMs
        settle(nowMs)
    }

    /** The deadline from [nextDeadline] came (or any time: it only settles). */
    fun onTime(nowMs: Long) = settle(nowMs)

    /**
     * A press on the chip landed at [downAtMs] (that finger's own ACTION_DOWN time): the hold to turn
     * the panel to, or null when the chip is hidden or appeared less than [armMs] before. The caller
     * applies it through [onPanelHold].
     */
    fun accept(downAtMs: Long): Int? = shown?.takeIf { downAtMs - shownAtMs >= armMs }

    /** When [onTime] must run next, or null: nothing is pending (or a finger is down; the lift settles). */
    fun nextDeadline(): Long? {
        if (!expanded || touching) return null
        if (shown != null) return shownAtMs + timeoutMs
        return if (candidate() != null) readyAtMs() else null
    }

    /** A dismissal carried over from the panel of a restarted service. */
    fun restoreDismissed(hold: Int?) {
        dismissed = hold?.mod(4)
        dismissedAtMs = NEVER
    }

    /** The sensed hold if it may be offered now, else null. */
    private fun candidate(): Int? {
        val s = sensed ?: return null
        if (s == panelHold || s == REVERSE_PORTRAIT) return null
        if (PanelOrientationMath.suggestionTurn(panelHold, s) == 2) return null
        if (s == dismissed && !(airMouse && maxOf(lastLiftMs, airOnMs) > dismissedAtMs)) return null
        return s
    }

    private fun dismiss(hold: Int?, nowMs: Long) {
        dismissed = hold
        dismissedAtMs = nowMs
    }

    /** The sensed hold has been stable long enough and the panel untouched long enough. */
    private fun readyAtMs(): Long = maxOf(sensedSinceMs + stableMs, lastLiftMs + quietMs)

    private fun settle(nowMs: Long) {
        if (!expanded) {
            hide(Hide.COLLAPSED)
            return
        }
        if (touching) return
        val want = candidate()
        val current = shown
        if (current != null) {
            if (want == current) {
                if (nowMs - shownAtMs < timeoutMs) return
                dismiss(current, nowMs)
                hide(Hide.TIMEOUT)
                return
            }
            hide(if (sensed == panelHold) Hide.RETURNED else Hide.CHANGED)
        }
        if (want != null && nowMs >= readyAtMs()) {
            shown = want
            shownAtMs = nowMs
        }
    }

    private fun hide(reason: Hide) {
        if (shown == null) return
        shown = null
        lastHide = reason
    }

    companion object {
        /** How long the sensed hold must stay the same before it is offered. */
        const val STABLE_MS = 800L

        /** How long the panel must have been untouched before a chip appears. */
        const val QUIET_MS = 1000L

        /** How long a chip stays up. */
        const val TIMEOUT_MS = 8000L

        /** A press must land this long after the chip appeared (about a reaction time). */
        const val ARM_MS = 300L

        /** `Surface.ROTATION_180`: upside down for a phone; never offered (see the class doc). */
        const val REVERSE_PORTRAIT = 2

        /** [lastLiftMs] before any lift: far enough back that it never delays a chip. */
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
