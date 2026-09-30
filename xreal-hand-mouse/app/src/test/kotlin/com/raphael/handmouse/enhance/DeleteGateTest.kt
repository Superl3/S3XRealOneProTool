package com.raphael.handmouse.enhance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VideoEnhancer.mayDeleteOriginal]: the MKV is unrecoverable once deleted, so any loss keeps it. */
class DeleteGateTest {

    @Test
    fun everythingRenderedAndReadBackMayGo() {
        assertTrue(VideoEnhancer.mayDeleteOriginal(frames = 7319, rendered = 7319, samplesWritten = 7319, samplesRead = 7319))
    }

    @Test
    fun aSingleUndecodedFrameKeepsTheOriginal() {
        // decode failures are dropped before the writer, so samplesWritten == samplesRead hides them
        assertFalse(VideoEnhancer.mayDeleteOriginal(frames = 7319, rendered = 7318, samplesWritten = 7318, samplesRead = 7318))
    }

    @Test
    fun aMissingSampleWithinVerifysSlackKeepsTheOriginal() {
        // verify() passes 99 %; 7,246 of 7,319 is 1 % lost and still "plays"
        assertFalse(VideoEnhancer.mayDeleteOriginal(frames = 7319, rendered = 7319, samplesWritten = 7319, samplesRead = 7246))
    }

    @Test
    fun aHalfDecodedRecordingKeepsTheOriginal() {
        assertFalse(VideoEnhancer.mayDeleteOriginal(frames = 1000, rendered = 600, samplesWritten = 600, samplesRead = 600))
    }
}
