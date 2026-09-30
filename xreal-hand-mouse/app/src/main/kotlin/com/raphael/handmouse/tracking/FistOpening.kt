package com.raphael.handmouse.tracking

/**
 * Tells a hand that is opening from a fist that is merely loose (2026-09-30, review of 532983e,
 * M2). In fist touch mode the held touch is pinned while the fingers open, so the release point
 * does not shift with the knuckles. That used to hold whenever the curl EMA was above the fist
 * entry threshold — but a fist is kept until the curl passes 1.20, so a loose fist sits between
 * the two, and its touch stopped following the hand.
 *
 * Opening is a fast rise: the EMA climbs from a closed fist to open in about four frames (several
 * curl units per second); relaxing drifts at well under one. The rise sets a latch, which holds
 * until the curl is back under the entry threshold — the hold is as long as it used to be, only
 * its start needs the rise.
 *
 * Pure — JVM-tested (FistOpeningTest). One instance per held touch, updated once a frame.
 */
internal class FistOpening {

    companion object {
        /** Curl units per second above which the fingers are opening. */
        const val RISE_PER_S = 2f
    }

    private var lastCurl = Float.NaN
    private var lastMs = 0L

    var opening = false
        private set

    /** [curl]: [FistDetector.curl]; [enterThreshold]: [FistDetector.ENTER_THRESHOLD]. Returns [opening]. */
    fun update(curl: Float, timestampMs: Long, enterThreshold: Float): Boolean {
        if (curl < enterThreshold) {
            opening = false
        } else if (!opening && !lastCurl.isNaN() && timestampMs > lastMs &&
            (curl - lastCurl) * 1000f / (timestampMs - lastMs) > RISE_PER_S
        ) {
            opening = true
        }
        lastCurl = curl
        lastMs = timestampMs
        return opening
    }
}
