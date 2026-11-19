package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Which window the system (DeX) mouse cursor is over, from the `TYPE_VIEW_HOVER_ENTER`
 * accessibility events views send when a mouse hovers them (the path TalkBack uses to read what is
 * under a mouse). The window buttons of the Remote use it in the system cursor mode, where the
 * cursor's coordinates cannot be read: [SystemCursorTracker] needs a signature permission that
 * this app does not hold (spec 2026-11-17, "기기 상태 확인").
 *
 * It gives a window and the bounds of the hovered view, not the cursor position: enough to pick
 * the window for ▣ — ✕ ↑↓, not to aim the recenter.
 *
 * While enabled it adds `TYPE_VIEW_HOVER_ENTER` to the service's event types and lowers the
 * notification timeout (the service config uses 10 s, which would hold a hover event back that
 * long); [disable] restores both. Main thread, except [last], which any thread may call.
 */
class HoverTargetTracker {

    /** The last hovered view: its window and the centre of its screen bounds (display pixels). */
    data class Hover(
        val displayId: Int,
        val windowId: Int,
        val x: Float,
        val y: Float,
        val uptimeMs: Long,
    )

    private data class Saved(val eventTypes: Int, val notificationTimeout: Long)

    @Volatile
    private var latest: Hover? = null

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var saved: Saved? = null
    private var loggedFirst = false

    /** Starts listening for hover events. Main thread. */
    fun enable(service: AccessibilityService) {
        if (isEnabled) return
        val info = service.serviceInfo ?: return
        saved = Saved(info.eventTypes, info.notificationTimeout)
        info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
        info.notificationTimeout = HOVER_NOTIFICATION_TIMEOUT_MS
        service.serviceInfo = info
        isEnabled = true
        Log.i(TAG, "Listening for hover events")
    }

    /** Stops listening, restores the service's event types and timeout, forgets the last hover. Main thread. */
    fun disable(service: AccessibilityService) {
        if (!isEnabled) return
        isEnabled = false
        latest = null
        val previous = saved ?: return
        saved = null
        val info = service.serviceInfo ?: return
        info.eventTypes = previous.eventTypes
        info.notificationTimeout = previous.notificationTimeout
        service.serviceInfo = info
    }

    /** Forward every `AccessibilityService.onAccessibilityEvent` here. Main thread. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isEnabled || event.eventType != AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) return
        val node = event.source ?: return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return
        val hover = Hover(event.displayId, event.windowId, bounds.exactCenterX(), bounds.exactCenterY(), SystemClock.uptimeMillis())
        latest = hover
        if (!loggedFirst) {
            loggedFirst = true
            Log.i(TAG, "First hover event observed: $hover")
        }
    }

    /** The last hover seen on [displayId], or null. */
    fun last(displayId: Int): Hover? = latest?.takeIf { it.displayId == displayId }

    private companion object {
        const val TAG = "HoverTargetTracker"

        /** Delivery delay for hover events while enabled (the config's 10 s would make them stale). */
        const val HOVER_NOTIFICATION_TIMEOUT_MS = 50L
    }
}
