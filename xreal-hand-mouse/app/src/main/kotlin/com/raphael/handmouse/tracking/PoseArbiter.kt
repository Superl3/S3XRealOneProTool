package com.raphael.handmouse.tracking

/** The held hand poses [PoseArbiter] keeps apart. */
enum class HandPose { THUMBS_UP, V_SIGN, FIST, PINCH }

/**
 * One held pose at a time (2026-09-29, after Meta Interaction SDK pose detection): a pose that
 * is already active blocks the others from STARTING. [CursorPipeline.onHandResult] updates the
 * detectors in priority order — thumbs-up, V sign, fist, pinch — so when two would start on the
 * same frame the earlier one wins. An active pose is never forced off; each ends on its own exit
 * rule.
 *
 * The poses are mostly exclusive by definition (a V needs the index extended, a fist needs it
 * curled). This covers the transitions between them, where two detectors' thresholds are met at
 * once — e.g. fist → thumbs-up while the thumb opens, or a thumbs-up whose thumb brushes the
 * index and reads as a pinch.
 *
 * PINCH blocks nothing: a fist forming reads as a pinch first, and the fist must be able to take
 * over (see [CursorPipeline] `updateFistState`).
 */
object PoseArbiter {
    fun mayStart(pose: HandPose, active: Set<HandPose>): Boolean =
        active.all { it == pose || it == HandPose.PINCH }
}
