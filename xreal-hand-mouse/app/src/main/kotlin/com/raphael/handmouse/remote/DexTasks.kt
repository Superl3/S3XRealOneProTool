package com.raphael.handmouse.remote

/**
 * DeX window actions through the activity manager instead of the window caption (spec section Q).
 * The caption's buttons are not in the accessibility tree and tapping them by position needs a
 * guessed layout; `am` acts on the task itself. Measured 2026-11-19 on the SM-S937N (SDK 37) with
 * Settings in a freeform DeX window: `am task resize <id> 0 0 1920 1080` filled the DeX screen and
 * `am stack remove <id>` closed the window.
 */
internal object DexTasks {
    /** Lists every root task with its display, and each task's component, bounds and visibility. */
    val LIST_COMMAND = arrayOf("/system/bin/sh", "-c", "am stack list")

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    data class Task(
        val id: Int,
        val displayId: Int,
        val component: String,
        val bounds: Bounds,
        val visible: Boolean,
    )

    private val ROOT = Regex("""^\s*RootTask id=\d+ .*displayId=(-?\d+)""")
    private val TASK = Regex("""^\s*taskId=(\d+): (\S+) bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\].*visible=(true|false)""")

    /** Tasks of `am stack list` [output], each tagged with the display of its root task. */
    fun parse(output: String): List<Task> {
        var display: Int? = null
        val tasks = ArrayList<Task>()
        for (line in output.lineSequence()) {
            ROOT.find(line)?.let {
                display = it.groupValues[1].toInt()
                return@let
            }
            val m = TASK.find(line) ?: continue
            val d = display ?: continue
            val g = m.groupValues
            tasks += Task(
                id = g[1].toInt(),
                displayId = d,
                component = g[2],
                bounds = Bounds(g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt()),
                visible = g[7] == "true",
            )
        }
        return tasks
    }

    /**
     * The task a window button acts on: a visible app task on [displayId] (not the empty "unknown"
     * desk container, not the DeX launcher). With [windowBounds] (the accessibility window under
     * the cursor, which knows the stacking order) the task with the same bounds wins; otherwise the
     * smallest task containing ([x], [y]); without a point, the smallest visible one.
     */
    fun target(
        tasks: List<Task>,
        displayId: Int,
        x: Float?,
        y: Float?,
        windowBounds: Bounds?,
    ): Task? {
        val apps = tasks.filter {
            it.displayId == displayId && it.visible && it.component != "unknown" && !isLauncher(it.component)
        }
        if (windowBounds != null) {
            apps.firstOrNull { near(it.bounds, windowBounds) }?.let { return it }
        }
        val under = if (x != null && y != null) apps.filter { it.bounds.contains(x, y) } else apps
        return under.minByOrNull { it.bounds.area }
    }

    /** Whether [bounds] fill a display of [displayWidth] × [displayHeight] (a maximized window), within a few pixels. */
    fun fillsDisplay(bounds: Bounds, displayWidth: Int, displayHeight: Int): Boolean =
        near(bounds, Bounds(0, 0, displayWidth, displayHeight))

    /** Bounds for a maximize toggle: the whole display, or back to [restore] (or a centred window). */
    fun toggleBounds(current: Bounds, displayWidth: Int, displayHeight: Int, restore: Bounds?): Bounds {
        val full = Bounds(0, 0, displayWidth, displayHeight)
        if (!fillsDisplay(current, displayWidth, displayHeight)) return full
        if (restore != null && !near(restore, full)) return restore
        val w = displayWidth * 2 / 3
        val h = displayHeight * 2 / 3
        val l = (displayWidth - w) / 2
        val t = (displayHeight - h) / 2
        return Bounds(l, t, l + w, t + h)
    }

    fun resizeCommand(taskId: Int, b: Bounds) =
        arrayOf("/system/bin/sh", "-c", "am task resize $taskId ${b.left} ${b.top} ${b.right} ${b.bottom}")

    fun removeCommand(taskId: Int) = arrayOf("/system/bin/sh", "-c", "am stack remove $taskId")

    private fun isLauncher(component: String) = "launcher" in component.lowercase()

    private fun near(a: Bounds, b: Bounds, slack: Int = 8) =
        kotlin.math.abs(a.left - b.left) <= slack && kotlin.math.abs(a.top - b.top) <= slack &&
            kotlin.math.abs(a.right - b.right) <= slack && kotlin.math.abs(a.bottom - b.bottom) <= slack
}
