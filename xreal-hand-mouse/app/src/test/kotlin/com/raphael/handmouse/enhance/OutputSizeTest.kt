package com.raphael.handmouse.enhance

import org.junit.Assert.assertEquals
import org.junit.Test

/** [VideoEnhancer.outputSize] and [VideoEnhancer.bitrateFor]: the "720p" setting. */
class OutputSizeTest {

    @Test
    fun the1080pEyeRecordingBecomes1280x720() {
        assertEquals(1280 to 720, VideoEnhancer.outputSize(1920, 1080, 720))
    }

    @Test
    fun theSettingOf1080KeepsTheRecordingsSize() {
        assertEquals(1920 to 1080, VideoEnhancer.outputSize(1920, 1080, 1080))
    }

    @Test
    fun aSmallerSourceIsNeverUpscaled() {
        assertEquals(640 to 480, VideoEnhancer.outputSize(640, 480, 720))
    }

    @Test
    fun sizesStayEvenAndKeepTheAspect() {
        // 2048×1512 (the Eye's HEVC-native mode) → 720 rows: 975.2 wide → 974, not 975
        assertEquals(974 to 720, VideoEnhancer.outputSize(2048, 1512, 720))
        // an odd source size that is not downscaled is rounded down, as before the setting existed
        assertEquals(1918 to 1080, VideoEnhancer.outputSize(1919, 1081, 1200))
    }

    @Test
    fun aDownscaledOutputGetsHalfTheBitrate() {
        assertEquals(2_500_000, VideoEnhancer.bitrateFor(VideoEnhancer.HEVC_BITRATE, 720, 1080))
        assertEquals(4_000_000, VideoEnhancer.bitrateFor(VideoEnhancer.AVC_BITRATE, 720, 1080))
    }

    @Test
    fun anOutputAtTheRecordingsSizeKeepsTheFullBitrate() {
        assertEquals(5_000_000, VideoEnhancer.bitrateFor(VideoEnhancer.HEVC_BITRATE, 1080, 1080))
        // a source that was never downscaled (smaller than the target) is not "reduced" either
        assertEquals(5_000_000, VideoEnhancer.bitrateFor(VideoEnhancer.HEVC_BITRATE, 480, 480))
    }
}
