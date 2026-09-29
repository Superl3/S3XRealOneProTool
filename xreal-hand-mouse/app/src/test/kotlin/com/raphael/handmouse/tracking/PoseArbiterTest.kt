package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseArbiterTest {

    @Test
    fun `any pose may start on a free hand`() {
        for (pose in HandPose.values()) assertTrue(PoseArbiter.mayStart(pose, emptySet()))
    }

    @Test
    fun `an active pose blocks the others from starting`() {
        assertFalse(PoseArbiter.mayStart(HandPose.THUMBS_UP, setOf(HandPose.FIST)))
        assertFalse(PoseArbiter.mayStart(HandPose.FIST, setOf(HandPose.THUMBS_UP)))
        assertFalse(PoseArbiter.mayStart(HandPose.FIST, setOf(HandPose.V_SIGN)))
        assertFalse(PoseArbiter.mayStart(HandPose.PINCH, setOf(HandPose.V_SIGN)))
        assertFalse(PoseArbiter.mayStart(HandPose.PINCH, setOf(HandPose.THUMBS_UP, HandPose.PINCH)))
    }

    @Test
    fun `a pinch blocks nothing so a forming fist can take over`() {
        assertTrue(PoseArbiter.mayStart(HandPose.FIST, setOf(HandPose.PINCH)))
        assertTrue(PoseArbiter.mayStart(HandPose.THUMBS_UP, setOf(HandPose.PINCH)))
    }

    @Test
    fun `an active pose does not block itself`() {
        assertTrue(PoseArbiter.mayStart(HandPose.FIST, setOf(HandPose.FIST)))
        assertTrue(PoseArbiter.mayStart(HandPose.PINCH, setOf(HandPose.PINCH)))
    }
}
