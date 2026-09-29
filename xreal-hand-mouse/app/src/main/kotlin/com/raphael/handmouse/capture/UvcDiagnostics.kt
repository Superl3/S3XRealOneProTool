package com.raphael.handmouse.capture

/**
 * Read-only UVC descriptor report (2026-09-29, video-quality investigation). Pure — JVM-testable.
 * Logs what the Eye camera DECLARES: every frame descriptor with its bitrate/buffer fields (the
 * MJPEG format lists 1920x1080 twice — they may differ in quality) and the Camera Terminal /
 * Processing Unit / Extension Unit controls it exposes (exposure, gain, brightness…). Nothing
 * here changes the stream.
 */
object UvcDiagnostics {

    private const val DT_INTERFACE = 0x04
    private const val DT_CS_INTERFACE = 0x24
    private const val CLASS_VIDEO = 0x0E
    private const val SC_VIDEOCONTROL = 0x01
    private const val SC_VIDEOSTREAMING = 0x02

    /** A controllable UVC entity in the VideoControl interface. */
    data class Entity(val kind: Kind, val id: Int, val controls: Long, val guid: String? = null)

    enum class Kind { CAMERA_TERMINAL, PROCESSING_UNIT, EXTENSION_UNIT }

    /** One control: bmControls bit, UVC selector, value size in bytes. */
    data class Control(val bit: Int, val name: String, val selector: Int, val size: Int)

    val CAMERA_TERMINAL_CONTROLS = listOf(
        Control(0, "ScanningMode", 0x01, 1),
        Control(1, "AutoExposureMode", 0x02, 1),
        Control(2, "AutoExposurePriority", 0x03, 1),
        Control(3, "ExposureTimeAbsolute(100us)", 0x04, 4),
        Control(4, "ExposureTimeRelative", 0x05, 1),
        Control(5, "FocusAbsolute", 0x06, 2),
        Control(6, "FocusRelative", 0x07, 2),
        Control(7, "IrisAbsolute", 0x09, 2),
        Control(8, "IrisRelative", 0x0A, 1),
        Control(9, "ZoomAbsolute", 0x0B, 2),
        Control(10, "ZoomRelative", 0x0C, 3),
        Control(11, "PanTiltAbsolute", 0x0D, 8),
        Control(12, "PanTiltRelative", 0x0E, 4),
        Control(13, "RollAbsolute", 0x0F, 2),
        Control(14, "RollRelative", 0x10, 2),
        Control(17, "FocusAuto", 0x08, 1),
        Control(18, "Privacy", 0x11, 1),
    )

    val PROCESSING_UNIT_CONTROLS = listOf(
        Control(0, "Brightness", 0x02, 2),
        Control(1, "Contrast", 0x03, 2),
        Control(2, "Hue", 0x06, 2),
        Control(3, "Saturation", 0x07, 2),
        Control(4, "Sharpness", 0x08, 2),
        Control(5, "Gamma", 0x09, 2),
        Control(6, "WhiteBalanceTemperature", 0x0A, 2),
        Control(7, "WhiteBalanceComponent", 0x0C, 4),
        Control(8, "BacklightCompensation", 0x01, 2),
        Control(9, "Gain", 0x04, 2),
        Control(10, "PowerLineFrequency", 0x05, 1),
        Control(11, "HueAuto", 0x10, 1),
        Control(12, "WhiteBalanceTemperatureAuto", 0x0B, 1),
        Control(13, "WhiteBalanceComponentAuto", 0x0D, 1),
        Control(14, "DigitalMultiplier", 0x0E, 2),
        Control(15, "DigitalMultiplierLimit", 0x0F, 2),
        Control(18, "ContrastAuto", 0x13, 1),
    )

    fun supportedControls(entity: Entity): List<Control> {
        val table = when (entity.kind) {
            Kind.CAMERA_TERMINAL -> CAMERA_TERMINAL_CONTROLS
            Kind.PROCESSING_UNIT -> PROCESSING_UNIT_CONTROLS
            Kind.EXTENSION_UNIT -> return emptyList() // vendor-defined selectors
        }
        return table.filter { entity.controls and (1L shl it.bit) != 0L }
    }

    /** Camera Terminal / Processing Unit / Extension Unit entities of the VideoControl interface. */
    fun entities(raw: ByteArray): List<Entity> {
        val out = mutableListOf<Entity>()
        forEachDescriptor(raw) { d, subclass ->
            if (subclass != SC_VIDEOCONTROL || d.type != DT_CS_INTERFACE || d.len < 3) return@forEachDescriptor
            when (d.u8(2)) {
                0x02 -> if (d.len >= 15 && d.u16(4) == 0x0201) {
                    out += Entity(Kind.CAMERA_TERMINAL, d.u8(3), d.bitmap(15, d.u8(14)))
                }
                0x05 -> if (d.len >= 8) {
                    out += Entity(Kind.PROCESSING_UNIT, d.u8(3), d.bitmap(8, d.u8(7)))
                }
                0x06 -> if (d.len >= 23) {
                    val pins = d.u8(21)
                    val sizeAt = 22 + pins
                    if (sizeAt < d.len) {
                        val guid = (4 until 20).joinToString("") { "%02x".format(d.u8(it)) }
                        out += Entity(Kind.EXTENSION_UNIT, d.u8(3), d.bitmap(sizeAt + 1, d.u8(sizeAt)), guid)
                    }
                }
            }
        }
        return out
    }

    /** Human-readable lines: every VideoStreaming format/frame descriptor, then the VC entities. */
    fun describe(raw: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var format = -1
        forEachDescriptor(raw) { d, subclass ->
            if (subclass != SC_VIDEOSTREAMING || d.type != DT_CS_INTERFACE || d.len < 4) return@forEachDescriptor
            when (val sub = d.u8(2)) {
                0x04, 0x06, 0x10 -> {
                    format = d.u8(3)
                    lines += "UVC format fmt$format sub=0x%02x frames=%d".format(sub, d.u8(4))
                }
                0x05, 0x07 -> if (d.len >= 26) {
                    lines += "UVC frame fmt$format/frm${d.u8(3)} ${d.u16(5)}x${d.u16(7)} " +
                        "bitrate=${d.u32(9)}..${d.u32(13)} maxFrameBuf=${d.u32(17)} " +
                        "defaultFps=%.1f".format(fps(d.u32(21)))
                }
                0x11 -> if (d.len >= 22) {
                    lines += "UVC frame fmt$format/frm${d.u8(3)} ${d.u16(5)}x${d.u16(7)} " +
                        "bitrate=${d.u32(9)}..${d.u32(13)} defaultFps=%.1f".format(fps(d.u32(17)))
                }
            }
        }
        for (e in entities(raw)) {
            val names = supportedControls(e).joinToString(",") { it.name }
            lines += "UVC ${e.kind} id=${e.id} controls=0x${e.controls.toString(16)}" +
                (e.guid?.let { " guid=$it" } ?: "") + (if (names.isNotEmpty()) " [$names]" else "")
        }
        return lines
    }

    /** Little-endian value of a GET_CUR/MIN/MAX/DEF response (2-byte values also shown signed). */
    fun formatValue(data: ByteArray): String = when (data.size) {
        1 -> "${data[0].toInt() and 0xFF}"
        2 -> {
            val u = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            if (u >= 0x8000) "$u(${u - 0x10000})" else "$u"
        }
        4 -> "${(data[0].toLong() and 0xFF) or ((data[1].toLong() and 0xFF) shl 8) or
            ((data[2].toLong() and 0xFF) shl 16) or ((data[3].toLong() and 0xFF) shl 24)}"
        else -> data.joinToString(" ") { "%02x".format(it) }
    }

    private fun fps(interval100ns: Long): Double = if (interval100ns > 0) 10_000_000.0 / interval100ns else 0.0

    private class Desc(val raw: ByteArray, val at: Int, val len: Int, val type: Int) {
        fun u8(o: Int) = raw[at + o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int): Long = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        fun bitmap(o: Int, size: Int): Long {
            var v = 0L
            for (b in 0 until size.coerceAtMost(8)) if (o + b < len) v = v or (u8(o + b).toLong() shl (8 * b))
            return v
        }
    }

    /** Walks the configuration descriptor, reporting each descriptor with the subclass of the
     * video interface it belongs to (-1 outside one). */
    private fun forEachDescriptor(raw: ByteArray, block: (Desc, Int) -> Unit) {
        var subclass = -1
        var i = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val d = Desc(raw, i, len, type)
            if (type == DT_INTERFACE && len >= 7) {
                subclass = if (d.u8(5) == CLASS_VIDEO) d.u8(6) else -1
            } else {
                block(d, subclass)
            }
            i += len
        }
    }
}
