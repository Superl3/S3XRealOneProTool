package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FistAttempt]: a one-frame flicker of the fist ring is not a fist attempt. */
class FistAttemptTest {

    private fun FistAttempt.run(vararg progress: Float) = progress.forEach { update(isFist = false, enterProgress = it) }

    @Test
    fun aTwoFrameRingFlickerIsAbandonedButNotReal() {
        val a = FistAttempt()
        a.run(0f, 0.08f, 0.15f)
        assertTrue(a.pending)
        assertFalse(a.pendingReal) // a UP now must not drop the press
        a.update(isFist = false, enterProgress = 0f)
        assertTrue(a.abandoned)
        assertFalse(a.abandonedReal) // …and neither may the ring going away
    }

    @Test
    fun aRingThatGrewPastAThirdIsRealWhenItIsAbandoned() {
        val a = FistAttempt()
        a.run(0.1f, 0.2f, 0.35f)
        assertTrue(a.pendingReal)
        a.run(0.5f)
        assertTrue(a.pendingReal)
        a.update(isFist = false, enterProgress = 0f)
        assertTrue(a.abandoned)
        assertTrue(a.abandonedReal)
    }

    @Test
    fun aRingThatBecomesAFistIsNotAbandoned() {
        val a = FistAttempt()
        a.run(0.2f, 0.6f, 0.95f)
        a.update(isFist = true, enterProgress = 1f)
        assertFalse(a.pending)
        assertFalse(a.abandoned)
        assertFalse(a.abandonedReal)
    }

    @Test
    fun aFlickerAfterARealAttemptDoesNotInheritItsProgress() {
        val a = FistAttempt()
        a.run(0.4f, 0.6f, 0f) // real attempt, abandoned
        assertTrue(a.abandonedReal)
        a.run(0f, 0.1f)
        assertFalse(a.pendingReal)
        a.update(isFist = false, enterProgress = 0f)
        assertTrue(a.abandoned)
        assertFalse(a.abandonedReal)
    }

    @Test
    fun resetForgetsTheAttempt() {
        val a = FistAttempt()
        a.run(0.5f)
        a.reset()
        a.update(isFist = false, enterProgress = 0f)
        assertFalse(a.abandoned)
        assertFalse(a.pending)
    }
}
