package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.view.Display
import com.raphael.handmouse.input.GestureInjector
import com.raphael.handmouse.overlay.CursorOverlay
import com.raphael.handmouse.overlay.DisplaySelection
import com.raphael.handmouse.tracking.DisplayBounds

class RemoteDexController(
    private val service: AccessibilityService,
    private val cursor: CursorOverlay,
    private val gestures: GestureInjector,
    private val selectionProvider: () -> DisplaySelection?,
) : RemoteActions {
    private val logTag = "RemoteDexController"
    private val prefs = RemoteDockPrefs(service)
    private val windows = RemoteWindowActions(service, gestures)
    private var shell: RemoteShellBridge? = null
    private val panel = RemoteControllerOverlay(service, prefs, this)
    private var dragging = false
    private var lastSwipeAt = 0L

    fun syncDisplay() {
        val selection = selectionProvider()
        android.util.Log.d(logTag, "syncDisplay selection=" + selection)
        if (selection == null) {
            panel.hide()
            shell?.destroy()
            shell = null
        } else {
            panel.show()
        }
    }

    fun destroy() {
        if (dragging) endDrag()
        panel.destroy()
        shell?.destroy()
        shell = null
    }

    override fun movePointer(dx: Float, dy: Float) {
        val s = selectionProvider() ?: return
        val (x0, y0) = cursor.cursorPosition ?: (s.width / 2f to s.height / 2f)
        val point = RemoteControlMath.movePointer(x0, y0, dx, dy, s.width, s.height)
        cursor.moveTo(point.x, point.y)
        if (dragging) gestures.updateDrag(point.x, point.y)
    }

    override fun tap() {
        val s = selectionProvider() ?: return
        val (x, y) = cursor.cursorPosition ?: (s.width / 2f to s.height / 2f).also {
            cursor.moveTo(it.first, it.second)
        }
        gestures.tap(x, y, s.displayId)
        cursor.pulseClickAt(x, y)
    }

    override fun beginDrag() {
        val s = selectionProvider() ?: return
        val (x, y) = cursor.cursorPosition ?: (s.width / 2f to s.height / 2f).also {
            cursor.moveTo(it.first, it.second)
        }
        gestures.beginDrag(x, y, s.displayId)
        dragging = true
        cursor.setPinched(true)
    }

    override fun endDrag() {
        val s = selectionProvider()
        val p = cursor.cursorPosition
        if (dragging && s != null && p != null) gestures.endDrag(p.first, p.second)
        dragging = false
        cursor.setPinched(false)
    }

    override fun back() {
        dispatchKey("KEYCODE_BACK")
    }

    override fun home() {
        dispatchKey("KEYCODE_HOME")
    }

    override fun toggleMaximize() {
        val s = bounds() ?: return
        windows.toggleMaximize(s)
    }

    override fun minimizeActive() {
        val s = bounds() ?: return
        windows.minimize(s)
    }

    override fun closeActive() {
        val s = bounds() ?: return
        windows.closeActive(s)
    }

    override fun swipeActive(up: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSwipeAt < 320L) return
        lastSwipeAt = now
        val s = bounds() ?: return
        val b = windows.activeBounds(s.displayId)
            ?: android.graphics.Rect(0, 0, s.width, s.height)
        val swipe = RemoteControlMath.activeSwipe(b.left, b.top, b.right, b.bottom, up)
        gestures.swipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, 260L, s.displayId)
    }

    override fun launchSlot(slot: Int) {
        val s = selectionProvider() ?: return
        val pkg = prefs.packageFor(slot) ?: return
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = s.displayId }
        runCatching { service.startActivity(intent, options.toBundle()) }
            .onSuccess { android.util.Log.d(logTag, "launchSlot slot=$slot pkg=$pkg display=${s.displayId}") }
            .onFailure { android.util.Log.w(logTag, "launchSlot failed slot=$slot pkg=$pkg display=${s.displayId}", it) }
    }

    override fun pickSlot(slot: Int) {
        val intent = Intent(service, RemoteAppPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteDockPrefs.EXTRA_SLOT, slot)
        }
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = Display.DEFAULT_DISPLAY
        }
        runCatching { service.startActivity(intent, options.toBundle()) }
            .onFailure { android.util.Log.w(logTag, "pickSlot failed slot=$slot", it) }
    }

    private fun dispatchKey(keyCode: String) {
        val s = selectionProvider() ?: return
        val bridge = shell ?: RemoteShellBridge(service).also { shell = it }
        if (bridge.keyEvent(s.displayId, keyCode) == RemoteShellBridge.DispatchResult.UNAVAILABLE) {
            android.util.Log.w(logTag, "display-targeted key unavailable: $keyCode display=${s.displayId}")
        }
    }

    private fun bounds(): DisplayBounds? = selectionProvider()?.let {
        DisplayBounds(it.width, it.height, it.displayId)
    }
}

