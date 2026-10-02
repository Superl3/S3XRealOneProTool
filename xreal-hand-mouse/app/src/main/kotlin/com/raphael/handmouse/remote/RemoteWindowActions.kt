package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.raphael.handmouse.input.GestureInjector
import com.raphael.handmouse.tracking.DisplayBounds

class RemoteWindowActions(
    private val service: AccessibilityService,
    private val gestures: GestureInjector,
) {
    companion object {
        private const val TAG = "RemoteWindowActions"
        private val CLOSE_HINTS = listOf("close", "닫기", "종료")
        private val MAX_HINTS = listOf("maximize", "maximise", "최대화")
        private val MIN_HINTS = listOf("minimize", "minimise", "최소화")
        private val RESTORE_HINTS = listOf("restore", "복원", "원래 크기")
    }

    fun activeBounds(displayId: Int): Rect? = activeApp(displayId)?.let(::boundsOf)

    fun closeActive(display: DisplayBounds): Boolean {
        val windows = windows(display.displayId)
        val target = activeApp(windows) ?: return false
        if (clickCaptionAction(target, windows, CLOSE_HINTS, listOf("close"))) return true
        val b = boundsOf(target)
        if (b.width() >= display.width && b.height() >= display.height) return false
        val caption = captionHeight(b)
        gestures.tap(b.right - 24f, b.top + caption / 2f, display.displayId)
        Log.d(TAG, "close fallback tap on display=${display.displayId}")
        return true
    }

    fun toggleMaximize(display: DisplayBounds): Boolean {
        val windows = windows(display.displayId)
        val target = activeApp(windows) ?: return false
        val hints = MAX_HINTS + RESTORE_HINTS
        if (clickCaptionAction(target, windows, hints, listOf("maximize", "restore"))) return true
        val b = boundsOf(target)
        val y = b.top + captionHeight(b) / 2f
        gestures.doubleTap(b.centerX().toFloat(), y, display.displayId)
        Log.d(TAG, "maximize fallback double-tap title bar display=${display.displayId}")
        return true
    }

    fun minimize(display: DisplayBounds): Boolean {
        val windows = windows(display.displayId)
        val target = activeApp(windows) ?: return false
        val ok = clickCaptionAction(target, windows, MIN_HINTS, listOf("minimize"))
        if (!ok) Log.w(TAG, "minimize caption action not found display=${display.displayId}")
        return ok
    }

    private fun windows(displayId: Int): List<AccessibilityWindowInfo> = try {
        service.windowsOnAllDisplays.get(displayId).orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "windowsOnAllDisplays failed: ${e.message}")
        emptyList()
    }

    private fun activeApp(displayId: Int): AccessibilityWindowInfo? = activeApp(windows(displayId))

    private fun activeApp(windows: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.filter { it.isActive || it.isFocused }.maxByOrNull { it.layer }
            ?: apps.maxByOrNull { it.layer }
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

