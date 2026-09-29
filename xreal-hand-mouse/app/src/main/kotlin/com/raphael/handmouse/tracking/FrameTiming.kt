package com.raphael.handmouse.tracking

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Frame-rate independent timing (2026-09-28). Every detector and smoother was tuned at 60fps with
 * per-frame EMA alphas and frame-count debounces, but inference runs at 60/40/24fps (thermal) and
 * 12fps (idle), so the same gesture took 250ms at 60fps and 625ms at 24fps. These helpers keep the
 * 60fps behaviour exactly and make every other rate take the same wall-clock time.
 */
internal object FrameTiming {
    /** Frame interval the constants were tuned at. */
    const val REFERENCE_FRAME_MS = 1000f / 60f

    /** Per-frame EMA [alphaAt60fps] converted to a sample [dtMs] apart (same time constant). */
    fun alpha(alphaAt60fps: Float, dtMs: Long): Float {
        if (alphaAt60fps >= 1f) return 1f
        val frames = dtMs.coerceAtLeast(1L) / REFERENCE_FRAME_MS
        return 1f - (1f - alphaAt60fps).pow(frames)
    }

    /** EMA step; a NaN [previous] (first sample / after reset) takes [sample] as is. */
    fun ema(previous: Float, sample: Float, alphaAt60fps: Float, dtMs: Long): Float =
        if (previous.isNaN()) sample else previous + alpha(alphaAt60fps, dtMs) * (sample - previous)

    /** "N consecutive frames at 60fps" as a hold duration measured from the first frame. Half a
     * frame of slack absorbs arrival jitter, so N frames at 60fps still confirm on the N-th. */
    fun framesToHoldMs(frames: Int): Long =
        ((frames - 1.5f) * REFERENCE_FRAME_MS).roundToLong().coerceAtLeast(0L)
}

/** Confirms a requested state once it has been requested for a hold duration (time, not frames). */
internal class TimedDebounce {
    private var candidate: Boolean? = null
    private var sinceMs = 0L

    /** Returns `true` on the call where [want] has been pending for at least [holdMs]. */
    fun confirm(want: Boolean, nowMs: Long, holdMs: Long): Boolean {
        if (candidate != want) {
            candidate = want
            sinceMs = nowMs
        }
        if (nowMs - sinceMs < holdMs) return false
        candidate = null
        return true
    }

    /** Fraction 0..1 of [holdMs] elapsed while [want] is pending; 0 when something else is. */
    fun progress(want: Boolean, nowMs: Long, holdMs: Long): Float {
        if (candidate != want) return 0f
        if (holdMs <= 0L) return 1f
        return ((nowMs - sinceMs).toFloat() / holdMs).coerceIn(0f, 1f)
    }

    fun reset() {
        candidate = null
    }
}
