package com.raphael.handmouse.remote

/**
 * Reads, through the Shizuku shell, where the system (DeX) mouse cursor is and which window
 * decorations (DeX captions) exist, from one `dumpsys input` (the app cannot read the cursor:
 * [SystemCursorTracker] needs a signature permission).
 *
 * The cursor comes from InputDispatcher's first "CursorStatesByDisplay" section: per display, the
 * windows a mouse hovers and the pointer position in display pixels. Measured 2026-11-18 on the
 * SM-S937N (SDK 37) while the Remote moved the cursor on the DeX display 63:
 * ```
 *   CursorStatesByDisplay:
 *     LogicalDisplayId 63:
 *         0: name='1bed02 app.morphe.android.youtube/...', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates=
 *           DeviceId(41):[touchingPointers=[], downTimeInTarget=<not set>, hoveringPointers=[Pointer(id=0, MOUSE) at (1298.1, 662.489)], pilferingPointerIds=<none>]
 *     LogicalDisplayId 0:
 *   Display: 63
 * ```
 * It follows the pointer continuously (1298,662 → 1866,868 → 1919,1079 during one swipe). Only the
 * first section counts: the dump repeats the dispatcher state under "Input Dispatcher State at
 * time of last ANR", whose positions are old. The list can be empty while the cursor is hidden,
 * or right after the virtual mouse re-opened; the position is then unknown.
 *
 * Decorations are the input-window lines whose name mentions "decor" or "caption" (the desktop
 * window caption is a SystemUI view host, "...WindowDecorViewHost..." in the 2026-11-18 dump);
 * their frames are the evidence [RemoteWindowActions.tapCaptionButton] needs before it taps where a
 * caption button should be. Not yet checked against a device dump: no match means no evidence.
 */
internal object SystemCursorQuery {
    const val DECOR_MARKER = "---DECOR---"

    /** One dumpsys: the first cursor section, a marker, then the decoration window lines. */
    val COMMAND = arrayOf(
        "/system/bin/sh",
        "-c",
        "d=\$(dumpsys input); " +
            "echo \"\$d\" | sed -n '/CursorStatesByDisplay/,/^  Display: /{p;/^  Display: /q;}'; " +
            "echo '$DECOR_MARKER'; " +
            "echo \"\$d\" | grep -iE 'name=[^,]*(decor|caption)' | grep 'frame=\\['",
    )

    /** A decoration window's frame on a display, in display pixels. */
    data class Frame(val displayId: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Snapshot(
        /** The cursor on the asked display, or null when no mouse hovers there. */
        val cursor: Pair<Float, Float>?,
        /** Decoration (caption) windows on the asked display. */
        val decorFrames: List<Frame>,
    )

    private val DISPLAY = Regex("""LogicalDisplayId\s+(-?\d+):""")
    private val DEVICE_MOUSE_AT =
        Regex("""DeviceId\((-?\d+)\):\[.*?hoveringPointers=\[[^\]]*?MOUSE\)\s+at\s+\((-?[\d.]+),\s*(-?[\d.]+)\)""")
    private val WINDOW_DISPLAY = Regex("""displayId=(-?\d+)""")
    private val FRAME = Regex("""frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    fun parse(output: String, displayId: Int, preferDeviceIds: Set<Int> = emptySet()): Snapshot {
        val marker = output.indexOf(DECOR_MARKER)
        val cursorPart = if (marker >= 0) output.substring(0, marker) else output
        val decorPart = if (marker >= 0) output.substring(marker + DECOR_MARKER.length) else ""
        return Snapshot(parseCursor(cursorPart, displayId, preferDeviceIds), parseDecor(decorPart, displayId))
    }

    /**
     * The cursor position on [displayId] from the first CursorStatesByDisplay section of [output];
     * an entry of [preferDeviceIds] (the virtual mouse) wins over another mouse.
     */
    fun parseCursor(output: String, displayId: Int, preferDeviceIds: Set<Int> = emptySet()): Pair<Float, Float>? {
        val start = output.indexOf("CursorStatesByDisplay")
        if (start < 0) return null
        var current: Int? = null
        var first: Pair<Float, Float>? = null
        val lines = output.substring(start).lineSequence().drop(1)
        for (line in lines) {
            // The end of the first section: the next top-level entry, or a repeated dump.
            if (line.trimStart().startsWith("Display:") || "CursorStatesByDisplay" in line ||
                "Input Dispatcher State" in line
            ) break
            val display = DISPLAY.find(line)
            if (display != null) {
                current = display.groupValues[1].toIntOrNull()
                continue
            }
            if (current != displayId) continue
            val m = DEVICE_MOUSE_AT.find(line) ?: continue
            val device = m.groupValues[1].toIntOrNull()
            val x = m.groupValues[2].toFloatOrNull() ?: continue
            val y = m.groupValues[3].toFloatOrNull() ?: continue
            if (device != null && device in preferDeviceIds) return x to y
            if (first == null) first = x to y
        }
        return first
    }

    /** Frames of the decoration window lines on [displayId]; empty frames are skipped. */
    fun parseDecor(output: String, displayId: Int): List<Frame> = output.lineSequence().mapNotNull { line ->
        val d = WINDOW_DISPLAY.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        if (d != displayId) return@mapNotNull null
        val f = FRAME.find(line) ?: return@mapNotNull null
        val (l, t, r, b) = f.destructured
        val frame = Frame(d, l.toInt(), t.toInt(), r.toInt(), b.toInt())
        frame.takeIf { it.right > it.left && it.bottom > it.top }
    }.toList()
}
