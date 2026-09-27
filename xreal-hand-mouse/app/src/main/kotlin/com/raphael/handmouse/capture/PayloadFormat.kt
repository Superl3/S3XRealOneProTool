package com.raphael.handmouse.capture

/**
 * What the camera REALLY sends, read from the payload bytes (Eye Tools fork).
 *
 * Found on hardware (S25 + One Pro, firmware 2026-09): the UVC descriptors advertise a
 * frame-based (HEVC) format fmt1, but selecting it still delivers 1920x1080 JPEG frames. Choosing
 * the decoder / recorder path from the descriptor subtype therefore silently broke both tracking
 * and recording; every consumer now uses the sniffed format instead.
 */
object PayloadFormat {
    const val UNKNOWN = 0
    const val MJPEG = 0x06
    const val HEVC = 0x10

    /** Format of a frame chunk (UVC header already stripped), or [UNKNOWN] for filler/continuation. */
    fun detect(chunk: ByteArray): Int = when {
        chunk.size < 4 -> UNKNOWN
        chunk[0] == 0xFF.toByte() && chunk[1] == 0xD8.toByte() -> MJPEG
        chunk[0] == 0.toByte() && chunk[1] == 0.toByte() &&
            (chunk[2] == 1.toByte() || (chunk[2] == 0.toByte() && chunk[3] == 1.toByte())) -> HEVC
        else -> UNKNOWN
    }
}
