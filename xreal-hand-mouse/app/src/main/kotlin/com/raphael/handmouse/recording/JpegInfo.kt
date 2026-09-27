package com.raphael.handmouse.recording

/** Reads the picture size from a JPEG's SOFn marker — pure Kotlin, JVM-testable. */
object JpegInfo {

    data class Size(val width: Int, val height: Int)

    fun size(jpeg: ByteArray): Size? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 -> { i += 2; continue }
                marker == 0xDA || marker == 0xD9 -> return null // SOS/EOI before any SOF
            }
            val segLen = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= jpeg.size) return null
                val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) Size(w, h) else null
            }
            if (segLen < 2) return null
            i += 2 + segLen
        }
        return null
    }
}
