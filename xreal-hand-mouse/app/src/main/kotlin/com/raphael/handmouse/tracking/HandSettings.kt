package com.raphael.handmouse.tracking

import kotlin.math.pow

/**
 * User-tunable hand-mouse parameters (Eye Tools fork), resolved from the settings screen into the
 * concrete values the pipeline components use. Pure — JVM-testable. Every tuning default maps
 * exactly onto the upstream constants.
 */
data class HandSettings(
    /** [RelativeCursorMapper.spanX]: fraction of the camera FOV that crosses the whole screen. */
    val spanX: Float = 0.40f,
    /** [PinchDetector] enter threshold; exit = enter + [PINCH_HYSTERESIS]. */
    val pinchEnter: Float = 0.28f,
    /** [OneEuroFilter.minCutoff] (Hz) — lower = smoother, laggier. */
    val minCutoff: Float = 0.6f,
    val deadZonePx: Float = 1.5f,
    val holdThresholdMs: Long = 400L,
    val downDebounceFrames: Int = 3,
    /** Stationary long pinch shows the three-way menu (left / up / right). */
    val palmMenu: Boolean = true,
    val magneticClick: Boolean = true,
    val debugOverlay: Boolean = false,
    /** Off by default since 2026-09-28: the fist is the fork's click, and holding it 2 s to
     * recenter was rarely used (the user's call). */
    val fistRecenter: Boolean = false,
    /** Fist = touch down (2026-09-29): the fist presses and holds until it opens, instead of a
     * tap when it closes. Off by default — the tap on close stays the fork's click. */
    val fistTouch: Boolean = false,
    /** Thumbs-up 1 s toggles the cursor on/off (on by default — robust pose, no pinch needed). */
    val thumbsUpMute: Boolean = true,
    val vSignVoice: Boolean = false,
    /** Use MediaPipe metric world landmarks for pinch ratios (robust to hand rotation). Off by
     * default: the pinch thresholds were tuned on normalized coordinates. */
    val worldPinch: Boolean = false,
    /** Hand travel to choose a direction (fraction of the camera image width). */
    val layerStep: Float = 0.045f,
    /** Short "what to do next" line under the cursor during a gesture ([GestureHint]). */
    val gestureHints: Boolean = true,
    /** Remove head rotation from the hand position ([HeadMotionCompensator]); needs [headCalibration]. */
    val headCompensation: Boolean = false,
    val headCalibration: com.raphael.handmouse.imu.ImuCameraCalibration? = null,
    /** A landmark within [HandReadiness.EDGE_MARGIN] of the image edge blocks new pinches and
     * fists ([HandReadiness]). Off by default: it blocked 37–85 % of real hand frames. */
    val edgeBlock: Boolean = false,
) {
    companion object {
        const val PINCH_HYSTERESIS = 0.10f

        /**
         * @param sensitivityPct 50..200 (100 = upstream gain)
         * @param pinch 0..100 (50 = upstream 0.28 enter threshold)
         * @param smoothing 0..100 (50 = upstream minCutoff 0.6 Hz; geometric 1.5 Hz → 0.24 Hz)
         */
        fun fromUi(
            sensitivityPct: Int,
            pinch: Int,
            smoothing: Int,
            deadZoneTenthsPx: Int,
            holdMs: Int,
            debounceFrames: Int,
            palmMenu: Boolean,
            fistRecenter: Boolean,
            thumbsUpMute: Boolean,
            vSignVoice: Boolean,
            worldPinch: Boolean = false,
            layerStepThousandths: Int = 45,
            magneticClick: Boolean = true,
            debugOverlay: Boolean = false,
            gestureHints: Boolean = true,
            fistTouch: Boolean = false,
            headCompensation: Boolean = false,
            headCalibration: com.raphael.handmouse.imu.ImuCameraCalibration? = null,
            edgeBlock: Boolean = false,
        ): HandSettings {
            val sens = sensitivityPct.coerceIn(50, 200) / 100f
            val p = pinch.coerceIn(0, 100) / 100f
            val s = smoothing.coerceIn(0, 100) / 100.0
            return HandSettings(
                spanX = 0.40f / sens,
                pinchEnter = 0.20f + 0.16f * p,
                minCutoff = (1.5 * 0.16.pow(s)).toFloat(),
                deadZonePx = deadZoneTenthsPx.coerceIn(0, 50) / 10f,
                holdThresholdMs = holdMs.coerceIn(200, 1000).toLong(),
                downDebounceFrames = debounceFrames.coerceIn(1, 6),
                palmMenu = palmMenu,
                magneticClick = magneticClick,
                debugOverlay = debugOverlay,
                gestureHints = gestureHints,
                fistRecenter = fistRecenter,
                fistTouch = fistTouch,
                thumbsUpMute = thumbsUpMute,
                vSignVoice = vSignVoice,
                worldPinch = worldPinch,
                layerStep = layerStepThousandths.coerceIn(25, 100) / 1000f,
                headCompensation = headCompensation,
                headCalibration = headCalibration,
                edgeBlock = edgeBlock,
            )
        }
    }
}
