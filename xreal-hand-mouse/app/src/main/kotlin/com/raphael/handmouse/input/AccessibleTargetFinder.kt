package com.raphael.handmouse.input

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.raphael.handmouse.tracking.CursorPoint
import com.raphael.handmouse.tracking.DisplayBounds
import kotlin.math.hypot
import kotlin.math.min

/** Finds a nearby accessible button only when a click is requested. */
class AccessibleTargetFinder(private val service: AccessibilityService) {
    fun nearest(x: Float, y: Float, display: DisplayBounds): CursorPoint? {
        val density = try {
            val physicalDisplay = service.getSystemService(DisplayManager::class.java)?.getDisplay(display.displayId)
            physicalDisplay?.let { service.createDisplayContext(it).resources.displayMetrics.density }
                ?: service.resources.displayMetrics.density
        } catch (_: Exception) {
            service.resources.displayMetrics.density
        }
        val radius = 44f * density
        val search = Rect(
            (x - radius).toInt(), (y - radius).toInt(),
            (x + radius).toInt() + 1, (y + radius).toInt() + 1,
        )
        val windows = try {
            service.windowsOnAllDisplays.get(display.displayId).orEmpty()
        } catch (e: Exception) {
            Log.w("AccessibleTargetFinder", "Window lookup failed: ${e.message}")
            return null
        }
        var best: CursorPoint? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            try {
                val windowBounds = Rect().also { window.getBoundsInScreen(it) }
                if (!Rect.intersects(windowBounds, search)) continue
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                window.root?.let { stack.add(it) }
                var examined = 0
                while (stack.isNotEmpty() && examined++ < 500) {
                    val node = stack.removeLast()
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    if (!Rect.intersects(rect, search)) continue
                    for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
                    if (!node.isVisibleToUser || !node.isEnabled || !node.isClickable) continue
                    if (rect.isEmpty || rect.width() > 220f * density || rect.height() > 180f * density) continue
                    if (rect.contains(x.toInt(), y.toInt())) return CursorPoint(x, y)
                    val closestX = x.coerceIn(rect.left.toFloat(), rect.right.toFloat())
                    val closestY = y.coerceIn(rect.top.toFloat(), rect.bottom.toFloat())
                    val distance = hypot(x - closestX, y - closestY)
                    if (distance > radius) continue
                    val score = distance + (rect.width() + rect.height()) / (20f * density)
                    if (score >= bestScore) continue
                    val inset = min(8f * density, min(rect.width(), rect.height()) / 4f)
                    val tx = if (rect.width() <= 80f * density) rect.exactCenterX()
                        else x.coerceIn(rect.left + inset, rect.right - inset)
                    val ty = if (rect.height() <= 80f * density) rect.exactCenterY()
                        else y.coerceIn(rect.top + inset, rect.bottom - inset)
                    best = CursorPoint(tx, ty)
                    bestScore = score
                }
                // Windows are top to bottom; never select an obscured application's controls.
                if (best != null || windowBounds.contains(x.toInt(), y.toInt())) break
            } catch (e: Exception) {
                Log.w("AccessibleTargetFinder", "Window tree changed during click: ${e.message}")
            }
        }
        return best
    }
}
