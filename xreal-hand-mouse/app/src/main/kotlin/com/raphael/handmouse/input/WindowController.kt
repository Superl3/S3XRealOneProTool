package com.raphael.handmouse.input

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.raphael.handmouse.tracking.DisplayBounds

/**
 * "종료" — closes the DeX app window under the cursor (or the active one) without travelling to
 * its ✕ button (Eye Tools fork, palm-menu action).
 *
 * 1. Target: the top-most application window on the DeX display containing the cursor, else the
 *    active/focused one.
 * 2. Search only the caption area (top [CAPTION_DP] of the window, right half) of the window's own
 *    tree (classic DeX caption) and of windows overlapping it (Android 16 desktop-mode headers live
 *    in a SystemUI surface). Subtrees outside that area are pruned, so this is a handful of binder
 *    calls, not a full-tree walk. A view-id match wins; otherwise the right-most "close/닫기"
 *    description — so a tab's "Close tab" button further left is not picked.
 * 3. Fallback, only for a window smaller than the display (a fullscreen one has no caption):
 *    tap the caption's right end. Otherwise report failure and log what was near the caption.
 */
class WindowController(
    private val service: AccessibilityService,
    private val gestureInjector: GestureInjector,
) {
    companion object {
        private const val TAG = "WindowController"
        private val DESC_HINTS = listOf("close", "닫기", "종료")
        private const val CAPTION_DP = 48f
    }

    private val density get() = service.resources.displayMetrics.density

    fun closeApp(cursorX: Float, cursorY: Float, display: DisplayBounds): Boolean {
        val windows = try {
            service.windowsOnAllDisplays.get(display.displayId).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "windowsOnAllDisplays failed: ${e.message}")
            emptyList()
        }
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val target = apps
            .filter { boundsOf(it).contains(cursorX.toInt(), cursorY.toInt()) }
            .maxByOrNull { it.layer }
            ?: apps.firstOrNull { it.isActive || it.isFocused }
            ?: run {
                Log.w(TAG, "close: no application window on display ${display.displayId}")
                return false
            }
        val bounds = boundsOf(target)
        val caption = Rect(bounds.centerX(), bounds.top, bounds.right, bounds.top + (CAPTION_DP * density).toInt())

        var byId: AccessibilityNodeInfo? = null
        var byDesc: AccessibilityNodeInfo? = null
        var byDescRight = Int.MIN_VALUE
        val seen = ArrayList<String>()
        for (w in windows) {
            if (w !== target && !Rect.intersects(boundsOf(w), caption)) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            w.root?.let { stack.add(it) }
            while (stack.isNotEmpty() && byId == null) {
                val n = stack.removeLast()
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (!Rect.intersects(r, caption)) continue // prune: children stay inside parents
                val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
                val desc = (n.contentDescription ?: n.text)?.toString()?.lowercase().orEmpty()
                when {
                    "close" in id -> byId = n
                    desc.length in 1..40 && DESC_HINTS.any { it in desc } && r.right > byDescRight -> {
                        byDesc = n
                        byDescRight = r.right
                    }
                    (n.isClickable || n.contentDescription != null) && seen.size < 20 ->
                        seen += "id=${n.viewIdResourceName} desc=${n.contentDescription} $r"
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        val node = byId ?: byDesc
        if (node != null) {
            val clickable = generateSequence(node) { it.parent }.take(4).firstOrNull { it.isClickable } ?: node
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "close via '${node.viewIdResourceName ?: node.contentDescription}'")
                return true
            }
        }
        Log.w(TAG, "close: caption button not found. Near the caption: $seen")

        val fullscreen = bounds.width() >= display.width && bounds.height() >= display.height
        if (fullscreen) return false
        gestureInjector.tap(bounds.right - 20f * density, bounds.top + 16f * density, display.displayId)
        Log.d(TAG, "close fallback: tap at caption right end")
        return true
    }

    private fun boundsOf(w: AccessibilityWindowInfo) = Rect().also { w.getBoundsInScreen(it) }
}
