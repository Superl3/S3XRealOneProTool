package com.raphael.handmouse.tracking

/**
 * "Not ready" state (2026-09-29, after OpenXR `XR_EXT_hand_interaction`, where the pinch value
 * stays 0 until the hand is ready): landmarks MediaPipe places at the image edge are
 * extrapolated, and a low handedness score means an ambiguous hand (edge-on, half occluded). In
 * both cases the fingertip distances the pinch and fist use are unreliable, so the pipeline
 * starts no new pinch press or fist. The cursor keeps moving, and gestures already in progress
 * can still end.
 *
 * The edge check is optional ([HandSettings.edgeBlock], off by default since 2026-10-01): in the
 * user's own landmark logs (4 sessions, 21,844 hand frames, 09-29/09-30) it blocked 37–85 % of
 * the frames — the wrist sits below the image whenever the arm is lowered, and fingertips cross
 * the top edge when the hand is raised — while the handedness score alone blocked 0.2–2.7 %.
 */
object HandReadiness {
    /** Every landmark must be at least this far inside the image (normalized units per axis). */
    const val EDGE_MARGIN = 0.02f

    /** Minimum MediaPipe handedness score. */
    const val MIN_HANDEDNESS_SCORE = 0.6f

    enum class Reason { EDGE, LOW_SCORE }

    /** [points]: normalized image landmarks ([HandTracker.Result.points]); [handednessScore]:
     * null when MediaPipe gave none (not held against the hand); [checkEdge]: false skips the
     * edge test ([HandSettings.edgeBlock]). Returns null when ready. */
    fun notReadyReason(points: List<HandPoint>, handednessScore: Float?, checkEdge: Boolean = true): Reason? {
        val hi = 1f - EDGE_MARGIN
        if (checkEdge && points.any { it.x < EDGE_MARGIN || it.x > hi || it.y < EDGE_MARGIN || it.y > hi }) return Reason.EDGE
        if (handednessScore != null && handednessScore < MIN_HANDEDNESS_SCORE) return Reason.LOW_SCORE
        return null
    }
}
