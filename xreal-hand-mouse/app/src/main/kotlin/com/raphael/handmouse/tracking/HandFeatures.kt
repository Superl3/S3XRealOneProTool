package com.raphael.handmouse.tracking

import kotlin.math.sqrt

/**
 * Per-frame finger measurements computed ONCE and shared by every pose detector (2026-09-29,
 * after Meta Interaction SDK's per-finger states). Before, the fist, thumbs-up and V-sign
 * detectors each recomputed the same tip/PIP ratios from the landmarks.
 *
 * All ratios are scale-free (distances divided by distances of the same hand), so they do not
 * change with hand size or distance to the camera. Use isotropic points
 * ([HandTracker.Result.isoPoints]).
 */
class HandFeatures(
    /** dist(wrist, tip) / dist(wrist, PIP) for index, middle, ring, pinky: ~1.3 extended, <1.0 curled. */
    val curl: FloatArray,
    /** dist(thumb tip, index MCP) / hand scale: ~0.3–0.6 tucked, ~0.9–1.3 extended. */
    val thumbExtension: Float,
    /** (wrist.y − thumb tip.y) / hand scale: > 0 when the thumb points up in the image. */
    val thumbUp: Float,
) {
    /** The most extended of the four fingers (a fist needs all of them curled). */
    val maxCurl: Float get() = maxOf(maxOf(curl[0], curl[1]), maxOf(curl[2], curl[3]))

    companion object {
        private const val WRIST = 0
        private const val THUMB_TIP = 4
        private const val INDEX_MCP = 5
        private val TIP_PIP = arrayOf(intArrayOf(8, 6), intArrayOf(12, 10), intArrayOf(16, 14), intArrayOf(20, 18))

        fun from(landmarks: List<HandPoint>): HandFeatures {
            val wrist = landmarks[WRIST]
            val curl = FloatArray(4) { i ->
                dist(wrist, landmarks[TIP_PIP[i][0]]) / dist(wrist, landmarks[TIP_PIP[i][1]])
            }
            val handScale = dist(wrist, landmarks[INDEX_MCP])
            val thumb = landmarks[THUMB_TIP]
            return HandFeatures(
                curl = curl,
                thumbExtension = dist(thumb, landmarks[INDEX_MCP]) / handScale,
                thumbUp = (wrist.y - thumb.y) / handScale,
            )
        }

        private fun dist(a: HandPoint, b: HandPoint): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
