package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class HandSettingsTest {

    private fun ui(
        sens: Int = 100, pinch: Int = 50, smooth: Int = 50, dz: Int = 15, hold: Int = 400, deb: Int = 3,
        palm: Boolean = true,
    ) = HandSettings.fromUi(sens, pinch, smooth, dz, hold, deb, palm, fistRecenter = false, thumbsUpMute = true, vSignVoice = false)

    @Test
    fun defaultUiValuesReproduceUpstreamConstants() {
        val s = ui()
        assertEquals(0.40f, s.spanX, 1e-6f)
        assertEquals(0.28f, s.pinchEnter, 1e-6f)
        assertEquals(0.38f, s.pinchEnter + HandSettings.PINCH_HYSTERESIS, 1e-6f)
        assertEquals(0.6f, s.minCutoff, 1e-4f)
        assertEquals(1.5f, s.deadZonePx, 1e-6f)
        assertEquals(400L, s.holdThresholdMs)
        assertEquals(3, s.downDebounceFrames)
        assertEquals(HandSettings(), s)
    }

    @Test
    fun rangesAreMonotonicAndClamped() {
        assertEquals(0.20f, ui(sens = 200).spanX, 1e-6f)  // 2x faster
        assertEquals(0.80f, ui(sens = 10).spanX, 1e-6f)   // clamped to 50%
        assertEquals(1.5f, ui(smooth = 0).minCutoff, 1e-4f)
        assertEquals(0.24f, ui(smooth = 100).minCutoff, 1e-4f)
        assertEquals(0.20f, ui(pinch = 0).pinchEnter, 1e-6f)
        assertEquals(0.36f, ui(pinch = 100).pinchEnter, 1e-6f)
        assertEquals(false, ui(palm = false).palmMenu)
    }
}
