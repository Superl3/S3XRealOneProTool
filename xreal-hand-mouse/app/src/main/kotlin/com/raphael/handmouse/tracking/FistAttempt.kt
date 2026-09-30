package com.raphael.handmouse.tracking

import kotlin.math.max

/**
 * The fist ring as a signal (2026-09-30, review of 532983e, M1). The ring shows while a fist is
 * being confirmed ([FistDetector.enterProgress] > 0). [CursorPipeline] drops a held pinch press
 * when the ring is abandoned ("open the hand to cancel") or the pinch is released under it.
 *
 * The curl EMA can dip under the entry threshold for a frame or two and come back: the ring
 * flickers, and a pinch held with the other fingers curled was thrown away with it — the press
 * restarted from zero, or a released pinch lost its click. A ring counts as a real fist attempt
 * only once its progress has reached [REAL_PROGRESS] (a third of the enter debounce).
 *
 * Pure — JVM-tested (FistAttemptTest). One instance in the [CursorPipeline], updated once a frame.
 */
internal class FistAttempt {

    companion object {
        const val REAL_PROGRESS = 0.3f
    }

    private var peak = 0f

    /** The ring is up: a fist is being confirmed and the hand is not yet one. */
    var pending = false
        private set

    /** The ring was up last frame and is gone without the hand becoming a fist (any duration). */
    var abandoned = false
        private set

    /** [abandoned], and the ring had grown to [REAL_PROGRESS] first. */
    var abandonedReal = false
        private set

    /** [pending], and the ring has grown to [REAL_PROGRESS] by now. */
    var pendingReal = false
        private set

    fun update(isFist: Boolean, enterProgress: Float) {
        val wasPending = pending
        pending = !isFist && enterProgress > 0f
        abandoned = wasPending && !pending && !isFist
        abandonedReal = abandoned && peak >= REAL_PROGRESS
        peak = if (pending) max(peak, enterProgress) else 0f
        pendingReal = pending && peak >= REAL_PROGRESS
    }

    fun reset() {
        pending = false
        abandoned = false
        abandonedReal = false
        pendingReal = false
        peak = 0f
    }
}
