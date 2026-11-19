package com.raphael.handmouse.remote

/**
 * A touchscreen evdev node the shell service can read: its path, the kernel's name for it, the
 * range of its multi-touch position axes and how many slots it tracks.
 */
internal data class TouchNode(
    val path: String,
    val name: String,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val slots: Int,
)

/**
 * Finds the touchscreens in the output of `getevent -pl`, which the shell user may run (uid shell is
 * in group input). A touchscreen is a device with INPUT_PROP_DIRECT and multi-touch protocol B
 * axes (ABS_MT_POSITION_X/Y with ABS_MT_TRACKING_ID). That leaves out Samsung's `sec_touchpad`, the
 * same panel's second node for DeX touchpad mode, which is not DIRECT, and every mouse or key device.
 *
 * Labels are matched with their hex codes too (getevent prints the number when it has no label).
 */
internal object RawTouchProbe {
    val COMMAND = listOf("/system/bin/getevent", "-pl")

    private val DEVICE = Regex("""^add device \d+:\s*(\S+)""")
    private val NAME = Regex("""^\s*name:\s*"(.*)"""")
    private val AXIS = Regex("""(ABS_MT_SLOT|ABS_MT_POSITION_X|ABS_MT_POSITION_Y|ABS_MT_TRACKING_ID|002f|0035|0036|0039)\b[ *]*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
    private val PROPS = Regex("""^\s*input props:""")

    /**
     * The touchscreens a `getevent -pl` run that exited with [code] found, or null when the run said
     * nothing about the devices, so the caller probes again instead of keeping "no touchscreen": it
     * failed, or it listed no device at all (RemoteCommandRunner gives exit 0 with an empty text when
     * the output thread misses its wait, on a busy phone).
     */
    fun result(code: Int, output: String): List<TouchNode>? {
        val found = parse(output)
        if (found.isNotEmpty()) return found
        if (code != 0 || output.lineSequence().none { DEVICE.containsMatchIn(it) }) return null
        return found
    }

    fun parse(output: String): List<TouchNode> {
        val nodes = ArrayList<TouchNode>()
        var device: Device? = null
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            val added = DEVICE.find(line)
            if (added == null) {
                device?.read(line)
                continue
            }
            device?.toNode()?.let(nodes::add)
            device = Device(added.groupValues[1])
        }
        device?.toNode()?.let(nodes::add)
        return nodes
    }

    /** One `add device` block while it is being read. */
    private class Device(val path: String) {
        var name = ""
        var inProps = false
        var direct = false
        val axes = HashMap<String, IntRange>()

        fun read(line: String) {
            if (inProps) {
                val prop = line.trim()
                if (prop == "INPUT_PROP_DIRECT" || prop == "0001") direct = true
                return
            }
            if (PROPS.containsMatchIn(line)) {
                inProps = true
                return
            }
            val named = NAME.find(line)
            if (named != null) {
                name = named.groupValues[1]
                return
            }
            val axis = AXIS.find(line) ?: return
            val key = when (axis.groupValues[1]) {
                "002f" -> "ABS_MT_SLOT"
                "0035" -> "ABS_MT_POSITION_X"
                "0036" -> "ABS_MT_POSITION_Y"
                "0039" -> "ABS_MT_TRACKING_ID"
                else -> axis.groupValues[1]
            }
            axes[key] = axis.groupValues[2].toInt()..axis.groupValues[3].toInt()
        }

        fun toNode(): TouchNode? {
            val x = axes["ABS_MT_POSITION_X"] ?: return null
            val y = axes["ABS_MT_POSITION_Y"] ?: return null
            if (!direct || "ABS_MT_TRACKING_ID" !in axes) return null
            if (x.last <= x.first || y.last <= y.first) return null
            // Without ABS_MT_SLOT the device has a single slot.
            val slots = axes["ABS_MT_SLOT"]?.let { it.last + 1 } ?: 1
            return TouchNode(path, name, x.first, x.last, y.first, y.last, slots)
        }
    }
}
