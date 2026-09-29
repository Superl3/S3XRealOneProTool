package com.raphael.handmouse.tracking

import kotlin.math.abs

enum class MenuAction { BACK, HOME, CLOSE_APP }

/** Three-way palm menu. A short move left/up/right selects; a pinch confirms. */
class LayeredMenuTracker(
    /** Selection distance as a fraction of the camera image width. */
    var step: Float = 0.045f,
    private val confirmWindowMs: Long = 450L,
    private val smoothingAlpha: Float = 1f,
    private val selectionFrames: Int = 1,
) {
    data class Frame(
        val visible: Boolean,
        val selected: MenuAction?,
        val fired: MenuAction?,
    )

    private enum class State { IDLE, OPEN, AWAIT_CONFIRM, FIRED }

    private var state = State.IDLE
    private var anchorX = 0f
    private var anchorY = 0f
    private var filteredX = 0f
    private var filteredY = 0f
    private var selected: MenuAction? = null
    private var pending: MenuAction? = null
    private var pendingSinceMs = 0L
    private var lastTimestampMs = 0L
    private var deadlineMs = 0L

    val isActive: Boolean get() = state == State.OPEN || state == State.AWAIT_CONFIRM

    /** [palmX]/[palmY]: isotropic position ([HandTracker.Result.isoPoints]) — equal travel on X
     * and Y is equal physical travel. [smoothingAlpha]/[selectionFrames] are per 60fps frame and
     * scaled by the real frame interval ([FrameTiming]). */
    fun onPalmOpen(palmX: Float, palmY: Float, pinchDown: Boolean, timestampMs: Long): Frame {
        if (state == State.FIRED) return Frame(false, null, null)
        if (state == State.IDLE) {
            anchorX = palmX
            anchorY = palmY
            filteredX = palmX
            filteredY = palmY
            selected = null
            pending = null
            pendingSinceMs = timestampMs
        } else {
            // Menu movement is a coarse directional choice, so suppress hand jitter aggressively.
            val alpha = FrameTiming.alpha(smoothingAlpha, timestampMs - lastTimestampMs)
            filteredX += (palmX - filteredX) * alpha
            filteredY += (palmY - filteredY) * alpha
        }
        lastTimestampMs = timestampMs
        state = State.OPEN
        val dx = filteredX - anchorX
        val up = anchorY - filteredY
        val candidate = when {
            dx <= -step && abs(dx) > abs(up) * 1.35f -> MenuAction.BACK
            dx >= step && dx > abs(up) * 1.35f -> MenuAction.CLOSE_APP
            up >= step && up > abs(dx) * 1.35f -> MenuAction.HOME
            else -> null
        }
        // Hysteresis prevents flicker near the distance threshold. Diagonal movement stays neutral.
        if (candidate != pending) {
            pending = candidate
            pendingSinceMs = timestampMs
        }
        if (timestampMs - pendingSinceMs >= FrameTiming.framesToHoldMs(selectionFrames)) {
            if (candidate != null) selected = candidate
            else if ((abs(dx) < step * 0.65f && abs(up) < step * 0.65f) ||
                (abs(dx) > step && abs(up) > step)) selected = null
        }
        if (pinchDown && selected != null) return fire()
        return Frame(true, selected, null)
    }

    fun onPalmClosed(pinchDown: Boolean, timestampMs: Long): Frame {
        when (state) {
            State.IDLE -> return Frame(false, null, null)
            State.FIRED -> {
                reset()
                return Frame(false, null, null)
            }
            State.OPEN -> {
                if (selected == null) {
                    reset()
                    return Frame(false, null, null)
                }
                state = State.AWAIT_CONFIRM
                deadlineMs = timestampMs + confirmWindowMs
            }
            State.AWAIT_CONFIRM -> {}
        }
        if (timestampMs > deadlineMs) {
            reset()
            return Frame(false, null, null)
        }
        if (pinchDown) return fire().also { reset() }
        return Frame(true, selected, null)
    }

    fun reset() {
        state = State.IDLE
        selected = null
        pending = null
    }

    private fun fire(): Frame {
        val action = selected
        state = State.FIRED
        selected = null
        return Frame(false, null, action)
    }
}
