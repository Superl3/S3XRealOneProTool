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
    val fistRecenter: Boolean = true,
    /** Thumbs-up 1 s toggles the cursor on/off (on by default — robust pose, no pinch needed). */
    val thumbsUpMute: Boolean = true,
    val vSignVoice: Boolean = false,
    /** Use MediaPipe metric world landmarks for pinch ratios (robust to hand rotation). Off by
     * default: the pinch thresholds were tuned on normalized coordinates. */
    val worldPinch: Boolean = false,
    /** Hand travel to choose a direction (fraction of the camera image width). */
    val layerStep: Float = 0.045f,
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
                fistRecenter = fistRecenter,
                thumbsUpMute = thumbsUpMute,
                vSignVoice = vSignVoice,
                worldPinch = worldPinch,
                layerStep = layerStepThousandths.coerceIn(25, 100) / 1000f,
            )
        }
    }
}
