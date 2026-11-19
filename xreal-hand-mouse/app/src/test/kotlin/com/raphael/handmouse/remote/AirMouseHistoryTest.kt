package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AirMouseHistoryTest {
    private fun assertMotion(expectedDx: Float, expectedDy: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedDx, actual.first, 1e-6f)
        assertEquals(expectedDy, actual.second, 1e-6f)
    }

    @Test
    fun takeSince_sumsOnlyNewerEntriesAndRemovesThem() {
        val h = AirMouseHistory()
        h.add(100, 1f, 2f)
        h.add(200, 3f, 4f)
        h.add(250, 5f, -6f)

        assertMotion(8f, -2f, h.takeSince(150))
        // The taken entries are gone; the older one is still there.
        assertMotion(0f, 0f, h.takeSince(150))
        assertMotion(1f, 2f, h.takeSince(0))
        assertMotion(0f, 0f, h.takeSince(0))
    }

    @Test
    fun takeSince_includesAnEntryExactlyAtTheBoundary() {
        val h = AirMouseHistory()
        h.add(100, 1f, 1f)
        h.add(120, 2f, 2f)

        assertMotion(2f, 2f, h.takeSince(120))
    }

    @Test
    fun add_dropsEntriesOlderThanKeepMs() {
        val h = AirMouseHistory(keepMs = 300)
        h.add(0, 1f, 1f)
        h.add(100, 2f, 2f)
        h.add(350, 4f, 4f) // 0 is 350 ms old → dropped; 100 is 250 ms old → kept

        assertMotion(6f, 6f, h.takeSince(Long.MIN_VALUE))
    }

    @Test
    fun add_keepsAnEntryExactlyKeepMsOld() {
        val h = AirMouseHistory(keepMs = 300)
        h.add(0, 1f, 1f)
        h.add(300, 2f, 2f)

        assertMotion(3f, 3f, h.takeSince(Long.MIN_VALUE))
    }

    @Test
    fun clear_forgetsEverything() {
        val h = AirMouseHistory()
        h.add(100, 1f, 2f)
        h.add(110, 3f, 4f)
        h.clear()

        assertMotion(0f, 0f, h.takeSince(Long.MIN_VALUE))
        h.add(120, 5f, 6f)
        assertMotion(5f, 6f, h.takeSince(0))
    }
}
