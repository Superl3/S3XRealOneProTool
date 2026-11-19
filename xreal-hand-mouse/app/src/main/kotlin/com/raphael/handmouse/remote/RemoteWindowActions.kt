package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.raphael.handmouse.input.GestureInjector
import com.raphael.handmouse.tracking.DisplayBounds

/**
 * Window actions of the Remote panel (maximize toggle, minimize, swipe target bounds, the point
 * for closing).
 *
 * Every action targets the window under a point (the DeX cursor): the top-most (highest `layer`)
 * `TYPE_APPLICATION` window whose screen bounds contain it. The DeX desktop (home) window, which
 * is also `TYPE_APPLICATION`, is never the target (see [RemoteWindowTarget.desktopIndex]). When no
 * point is known or no other window contains it, the active/focused application window is used,
 * else the highest-layer one. Closing itself is not here: it uses `WindowController.closeApp`,
 * fed with [closePoint] so that it closes the same window.
 */
class RemoteWindowActions(
    private val service: AccessibilityService,
    private val gestures: GestureInjector,
) {
    companion object {
        private const val TAG = "RemoteWindowActions"
        private val MAX_HINTS = listOf("maximize", "maximise", "최대화")
        private val MIN_HINTS = listOf("minimize", "minimise", "최소화")
        private val RESTORE_HINTS = listOf("restore", "복원", "원래 크기")

        /** DeX caption height in dp (60 px measured on a 1920×1080 DeX screen, assumed 1.5× density). */
        private const val CAPTION_DP = 40f

        /** Density used when the DeX display cannot be read. */
        private const val DEFAULT_DEX_DENSITY = 1.5f
    }

    /** Screen bounds of the window under ([x], [y]) on [display]; see the class doc for the rule. */
    fun boundsAt(display: DisplayBounds, x: Float?, y: Float?): Rect? =
        target(windows(display.displayId), display, x, y)?.let(::boundsOf)

    /**
     * A point to hand to `WindowController.closeApp` (which closes the top-most application window
     * containing its point) so that it closes the window the class doc picks: the cursor itself
     * when a window other than the desktop is under it, else a point inside the fallback window
     * that no higher window covers. Null when there is no such window or point.
     */
    fun closePoint(display: DisplayBounds, x: Float?, y: Float?): Pair<Float, Float>? {
        val apps = applicationWindows(windows(display.displayId))
        val candidates = candidatesOf(apps)
        val choice = choose(candidates, display, x, y) ?: return null
        if (choice.underPoint && x != null && y != null) return Pair(x, y)
        val point = RemoteWindowTarget.uncoveredPoint(candidates, choice.index)
        if (point == null) Log.w(TAG, "close: fallback window is covered everywhere display=${display.displayId}")
        return point
    }

    /**
     * A point that makes the other methods target window [windowId] (for example the one a mouse
     * last hovered): inside it and not covered by a higher application window. Null when it is
     * not an application window on [display] (a SystemUI caption, the window is gone), is the
     * desktop, or is covered everywhere.
     */
    fun pointForWindow(display: DisplayBounds, windowId: Int): Pair<Float, Float>? {
        val apps = applicationWindows(windows(display.displayId))
        val index = apps.indexOfFirst { it.id == windowId }
        if (index < 0) return null
        val candidates = candidatesOf(apps)
        if (RemoteWindowTarget.desktopIndex(candidates, display.width, display.height) == index) return null
        return RemoteWindowTarget.uncoveredPoint(candidates, index)
    }

    /** Whether [windowId] is an application window on [display] (the desktop included). */
    fun isApplicationWindow(display: DisplayBounds, windowId: Int): Boolean =
        applicationWindows(windows(display.displayId)).any { it.id == windowId }

    /**
     * Maximizes or restores the window under ([x], [y]). [decorFrames]: the caption windows
     * `dumpsys input` lists on the display ([SystemCursorQuery]), null when not read.
     */
    internal fun toggleMaximize(
        display: DisplayBounds,
        x: Float?,
        y: Float?,
        decorFrames: List<SystemCursorQuery.Frame>? = null,
    ): Boolean {
        val windows = windows(display.displayId)
        val target = target(windows, display, x, y) ?: return false
        val hints = MAX_HINTS + RESTORE_HINTS
        if (clickCaptionAction(target, windows, hints, listOf("maximize", "restore"))) return true
        return tapCaptionButton(display, target, CaptionButton.MAXIMIZE, decorFrames)
    }

    /**
     * Restores the window under ([x], [y]) only if its caption offers a restore action, which on
     * DeX means it is maximized. The window's own bounds cannot tell: a maximized DeX window
     * reported [768,0][1920,1080] to accessibility (2026-11-19).
     */
    internal fun restoreIfMaximized(display: DisplayBounds, x: Float?, y: Float?): Boolean {
        val windows = windows(display.displayId)
        val target = target(windows, display, x, y) ?: return false
        return clickCaptionAction(target, windows, RESTORE_HINTS, listOf("restore"))
    }

    /** Minimizes the window under ([x], [y]); [decorFrames] as for [toggleMaximize]. */
    internal fun minimize(
        display: DisplayBounds,
        x: Float?,
        y: Float?,
        decorFrames: List<SystemCursorQuery.Frame>? = null,
    ): Boolean {
        val windows = windows(display.displayId)
        val target = target(windows, display, x, y) ?: return false
        if (clickCaptionAction(target, windows, MIN_HINTS, listOf("minimize"))) return true
        return tapCaptionButton(display, target, CaptionButton.MINIMIZE, decorFrames)
    }

    /**
     * Taps the caption [button] of the window under ([x], [y]) by position (see
     * [tapCaptionButton]); for the close button after `WindowController.closeApp` found no node.
     */
    internal fun tapCaptionAt(
        display: DisplayBounds,
        x: Float?,
        y: Float?,
        button: CaptionButton,
        decorFrames: List<SystemCursorQuery.Frame>?,
    ): Boolean {
        val target = target(windows(display.displayId), display, x, y) ?: return false
        return tapCaptionButton(display, target, button, decorFrames)
    }

    /** DeX window caption buttons, with their centre's distance from the right edge in caption heights. */
    enum class CaptionButton(val fromRightInCaptionHeights: Float) {
        CLOSE(49f / 60f),
        FULLSCREEN(114f / 60f),
        MAXIMIZE(180f / 60f),
        MINIMIZE(247f / 60f),
    }

    /**
     * Taps [button] of the caption of the window with screen [bounds] by its position. On the
     * SM-S937N's DeX (SDK 37) the caption (the desktop-mode window decoration, drawn by SystemUI)
     * is not in the accessibility tree, so [clickCaptionAction] finds nothing there (2026-11-18:
     * "close: caption button not found. Near the caption: []" on a maximized YouTube window).
     *
     * Geometry from a 1920×1080 DeX screenshot of that window: caption 60 px tall, button centres
     * 49 / 114 / 180 / 247 px from the right edge, vertically centred. It is kept relative to the
     * caption height, taken as [CAPTION_DP] dp at the DeX display's density — the density was not
     * measured, so the computed point is logged for checking on the device.
     *
     * It taps only where a caption is known to be ([RemoteWindowTarget.captionTapAllowed]): never on
     * a child window (a menu or popup), and on a display-sized window — maximized with a caption,
     * or immersive full screen without one — only when a decoration window from [decorFrames]
     * covers its top strip. Otherwise it logs and does nothing rather than touch app content.
     */
    private fun tapCaptionButton(
        display: DisplayBounds,
        window: AccessibilityWindowInfo,
        button: CaptionButton,
        decorFrames: List<SystemCursorQuery.Frame>?,
    ): Boolean {
        val bounds = boundsOf(window)
        val h = captionHeightPx(display.displayId)
        val allowed = RemoteWindowTarget.captionTapAllowed(
            isChild = window.parent != null,
            left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
            displayWidth = display.width, displayHeight = display.height,
            captionHeight = h,
            decorFrames = decorFrames,
        )
        if (!allowed) {
            Log.w(TAG, "caption tap $button skipped: no caption known bounds=$bounds captionH=$h decor=$decorFrames")
            return false
        }
        val x = bounds.right - button.fromRightInCaptionHeights * h
        val y = bounds.top + h / 2f
        gestures.tap(x, y, display.displayId)
        Log.d(TAG, "caption tap $button at ($x, $y) captionH=$h bounds=$bounds display=${display.displayId}")
        return true
    }

    private fun captionHeightPx(displayId: Int): Float {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        val density = display?.let { runCatching { service.createDisplayContext(it).resources.displayMetrics.density }.getOrNull() }
            ?: DEFAULT_DEX_DENSITY
        return CAPTION_DP * density
    }

    private fun windows(displayId: Int): List<AccessibilityWindowInfo> = try {
        // The service's notificationTimeout is 10 s, so window-change events (and with them the
        // client's window cache) lag: on 2026-11-19 a DeX window just maximized or restored still
        // reported its old bounds seconds later. Read the windows fresh.
        if (android.os.Build.VERSION.SDK_INT >= 34) service.clearCache()
        service.windowsOnAllDisplays.get(displayId).orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "windowsOnAllDisplays failed: ${e.message}")
        emptyList()
    }

    private fun target(
        windows: List<AccessibilityWindowInfo>,
        display: DisplayBounds,
        x: Float?,
        y: Float?,
    ): AccessibilityWindowInfo? {
        val apps = applicationWindows(windows)
        val choice = choose(candidatesOf(apps), display, x, y) ?: return null
        return apps[choice.index]
    }

    private fun applicationWindows(windows: List<AccessibilityWindowInfo>) =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

    private fun candidatesOf(apps: List<AccessibilityWindowInfo>) = apps.map { w ->
        val b = boundsOf(w)
        WindowCandidate(b.left, b.top, b.right, b.bottom, w.layer, w.isActive || w.isFocused)
    }

    /** [RemoteWindowTarget.choose] on [display], logged. */
    private fun choose(
        candidates: List<WindowCandidate>,
        display: DisplayBounds,
        x: Float?,
        y: Float?,
    ): RemoteWindowTarget.Choice? {
        val choice = RemoteWindowTarget.choose(candidates, x, y, display.width, display.height)
        if (choice == null) {
            Log.w(TAG, "no application window (point=$x,$y)")
            return null
        }
        val c = candidates[choice.index]
        val source = if (choice.underPoint) "cursor" else "active fallback"
        Log.d(
            TAG,
            "target via $source bounds=[${c.left},${c.top},${c.right},${c.bottom}] " +
                "layer=${c.layer} point=($x,$y)",
        )
        return choice
    }

    private fun clickCaptionAction(
        target: AccessibilityWindowInfo,
        windows: List<AccessibilityWindowInfo>,
        textHints: List<String>,
        idHints: List<String>,
    ): Boolean {
        val b = boundsOf(target)
        val caption = Rect(b.left, b.top, b.right, b.top + captionHeight(b).toInt())
        var best: AccessibilityNodeInfo? = null
        var bestRight = Int.MIN_VALUE
        for (w in windows) {
            if (w !== target && !Rect.intersects(boundsOf(w), caption)) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            w.root?.let(stack::add)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (!Rect.intersects(r, caption)) continue
                val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
                val desc = (n.contentDescription ?: n.text)?.toString()?.lowercase().orEmpty()
                val match = idHints.any { it in id } ||
                    (desc.length in 1..64 && textHints.any { it in desc })
                if (match && r.right > bestRight) {
                    best = n
                    bestRight = r.right
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
            }
        }
        val node = best ?: return false
        val clickable = generateSequence(node) { it.parent }.take(4).firstOrNull { it.isClickable } ?: node
        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) Log.d(TAG, "caption action via ${node.viewIdResourceName ?: node.contentDescription}")
        return ok
    }

    private fun captionHeight(bounds: Rect): Float =
        (bounds.height() * 0.08f).coerceIn(48f, 88f)

    private fun boundsOf(w: AccessibilityWindowInfo) = Rect().also { w.getBoundsInScreen(it) }
}

/** Plain-data view of one application window, for [RemoteWindowTarget.choose]. */
internal data class WindowCandidate(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val layer: Int,
    val activeOrFocused: Boolean,
) {
    /** Same half-open rule as `Rect.contains`: left/top inclusive, right/bottom exclusive. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Target-window choice of [RemoteWindowActions], kept free of Android types for unit tests. */
internal object RemoteWindowTarget {
    /**
     * Whether a position tap on a caption button may be sent to a window with these bounds
     * ([RemoteWindowActions] caption fallback): not on a child window (menu, popup); not when the
     * window cannot hold a caption and some content below it (narrower than five caption heights or
     * lower than two); and on a display-sized window only when a decoration frame of
     * [decorFrames] overlaps its top strip of [captionHeight] (a maximized DeX window has one, an
     * immersive full-screen one does not). [decorFrames] null: not read, so no such evidence.
     */
    fun captionTapAllowed(
        isChild: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        displayWidth: Int,
        displayHeight: Int,
        captionHeight: Float,
        decorFrames: List<SystemCursorQuery.Frame>?,
    ): Boolean {
        if (isChild) return false
        if (right - left < captionHeight * 5 || bottom - top < captionHeight * 2) return false
        val displaySized = left <= 0 && top <= 0 && right >= displayWidth && bottom >= displayHeight
        if (!displaySized) return true
        val stripBottom = top + captionHeight
        return decorFrames.orEmpty().any { f ->
            f.left < right && f.right > left && f.top < stripBottom && f.bottom > top
        }
    }

    /** [index] into the candidate list; [underPoint] is false when the active fallback chose it. */
    data class Choice(val index: Int, val underPoint: Boolean)

    /** Grid fractions tried by [uncoveredPoint] after the centre, on each axis. */
    private val GRID = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)

    /**
     * The DeX desktop (home) window among [candidates]: the one that covers the whole
     * [displayWidth] × [displayHeight] display and lies below every other candidate. A lone
     * candidate is never taken for the desktop: it may be a full-screen app that hides the desktop.
     */
    fun desktopIndex(candidates: List<WindowCandidate>, displayWidth: Int, displayHeight: Int): Int? {
        if (candidates.size < 2) return null
        val lowest = candidates.indices.minBy { candidates[it].layer }
        val c = candidates[lowest]
        if (candidates.indices.any { it != lowest && candidates[it].layer <= c.layer }) return null
        val coversDisplay = c.left <= 0 && c.top <= 0 && c.right >= displayWidth && c.bottom >= displayHeight
        return if (coversDisplay) lowest else null
    }

    /**
     * Leaving out the desktop ([desktopIndex]): the highest-layer candidate containing ([x], [y])
     * when both are non-null; otherwise (or when none contains the point) the highest-layer
     * active/focused one, else the highest-layer one. Null only for an empty list.
     */
    fun choose(
        candidates: List<WindowCandidate>,
        x: Float?,
        y: Float?,
        displayWidth: Int,
        displayHeight: Int,
    ): Choice? {
        val desktop = desktopIndex(candidates, displayWidth, displayHeight)
        val usable = candidates.indices.filter { it != desktop }
        if (x != null && y != null) {
            val hit = usable
                .filter { candidates[it].contains(x, y) }
                .maxByOrNull { candidates[it].layer }
            if (hit != null) return Choice(hit, underPoint = true)
        }
        val fallback = usable
            .filter { candidates[it].activeOrFocused }
            .maxByOrNull { candidates[it].layer }
            ?: usable.maxByOrNull { candidates[it].layer }
            ?: return null
        return Choice(fallback, underPoint = false)
    }

    /**
     * A point (whole pixels) inside candidate [index] that no higher-layer candidate contains, so
     * a "top-most window at this point" lookup finds that candidate: its centre when uncovered,
     * else the first uncovered point of a 5 × 5 grid over it. Null when all of those are covered.
     */
    fun uncoveredPoint(candidates: List<WindowCandidate>, index: Int): Pair<Float, Float>? {
        val target = candidates[index]
        val width = target.right - target.left
        val height = target.bottom - target.top
        val fractions = listOf(0.5f to 0.5f) + GRID.flatMap { fy -> GRID.map { fx -> fx to fy } }
        return fractions.asSequence()
            .map { (fx, fy) ->
                Pair((target.left + (width * fx).toInt()).toFloat(), (target.top + (height * fy).toInt()).toFloat())
            }
            .firstOrNull { (px, py) ->
                target.contains(px, py) && candidates.none { it.layer > target.layer && it.contains(px, py) }
            }
    }
}
