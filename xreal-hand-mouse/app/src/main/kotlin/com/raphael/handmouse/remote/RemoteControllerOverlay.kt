package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class RemoteControllerOverlay(
    private val service: AccessibilityService,
    private val prefs: RemoteDockPrefs,
    private val actions: RemoteActions,
) {
    private val logTag = "RemoteControllerOverlay"
    private val displayManager = service.getSystemService(DisplayManager::class.java)
    private val phoneDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    private val context = service.createDisplayContext(phoneDisplay)
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var expanded = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        root?.post { if (root != null) render(expanded) }
    }

    init {
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun show() {
        android.util.Log.d(logTag, "show rootPresent=" + (root != null))
        if (root == null) render(false)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        expanded = false
    }

    fun destroy() {
        hide()
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun render(open: Boolean) {
        root?.let { runCatching { wm.removeView(it) } }
        expanded = open
        val view = if (open) expandedPanel() else collapsedButton()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = cornerGravity(prefs.corner)
            x = dp(10)
            y = dp(18)
        }
        try {
            wm.addView(view, lp)
            root = view
            android.util.Log.d(logTag, "render open=" + open + " display=" + context.display?.displayId + " corner=" + prefs.corner)
        } catch (e: Exception) {
            root = null
            android.util.Log.e(logTag, "addView failed open=" + open + " display=" + context.display?.displayId, e)
        }
    }

    private fun collapsedButton(): View = textButton("◉").apply {
        contentDescription = "DeX Remote 열기"
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { render(true) }
        setOnLongClickListener {
            prefs.cycleCorner()
            true
        }
    }

    private fun expandedPanel(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.argb(232, 24, 27, 31), 18f)
        }

        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(RemoteDockPrefs.SLOT_COUNT) { slot ->
            dock.addView(dockButton(slot), LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(4)
            })
        }
        dock.addView(textButton("−").apply {
            contentDescription = "DeX Remote 접기"
            setOnClickListener { render(false) }
        }, LinearLayout.LayoutParams(dp(44), dp(52)))
        panel.addView(dock)

        panel.addView(touchpad(), LinearLayout.LayoutParams(dp(300), dp(190)).apply {
            topMargin = dp(8)
        })

        val windowActions = listOf(
            Triple("↩", "뒤로가기") { actions.back() },
            Triple("⌂", "DeX 홈") { actions.home() },
            Triple("▣", "최대화 또는 복원") { actions.toggleMaximize() },
            Triple("—", "최소화") { actions.minimizeActive() },
            Triple("✕", "활성 창 닫기") { actions.closeActive() },
        )
        panel.addView(actionRow(windowActions), LinearLayout.LayoutParams(dp(300), dp(48)).apply {
            topMargin = dp(8)
        })

        val contentActions = listOf(
            Triple("↑", "활성 앱 위로 스와이프") { actions.swipeActive(true) },
            Triple("↓", "활성 앱 아래로 스와이프") { actions.swipeActive(false) },
        )
        panel.addView(actionRow(contentActions), LinearLayout.LayoutParams(dp(300), dp(44)).apply {
            topMargin = dp(4)
        })

        val configActions = listOf(
            Triple("속도 ${prefs.pointerSpeed}×", "포인터 속도 변경") { prefs.cyclePointerSpeed(); Unit },
            Triple("위치 ${cornerLabel(prefs.corner)}", "플로팅 버튼 위치 변경") { prefs.cycleCorner(); Unit },
        )
        panel.addView(actionRow(configActions, textSize = 12f), LinearLayout.LayoutParams(dp(300), dp(40)).apply {
            topMargin = dp(4)
        })
        return panel
    }

    private fun actionRow(
        specs: List<Triple<String, String, () -> Unit>>,
        textSize: Float = 20f,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            specs.forEach { (label, description, action) ->
                addView(textButton(label).apply {
                    this.textSize = textSize
                    contentDescription = description
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = dp(4)
                })
            }
        }
    }

    private fun dockButton(slot: Int): View {
        val packageName = prefs.packageFor(slot)
        val b = ImageButton(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.argb(215, 48, 52, 59), 14f)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        }
        if (packageName != null) {
            runCatching {
                b.setImageDrawable(context.packageManager.getApplicationIcon(packageName))
                b.contentDescription = context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                )
            }.onFailure { b.contentDescription = packageName }
        } else {
            b.setImageResource(android.R.drawable.ic_input_add)
            b.contentDescription = "앱 지정 ${slot + 1}"
        }
        b.setOnClickListener {
            if (prefs.packageFor(slot) == null) actions.pickSlot(slot) else actions.launchSlot(slot)
        }
        b.setOnLongClickListener {
            actions.pickSlot(slot)
            true
        }
        return b
    }

    private fun touchpad(): View {
        val pad = TextView(context).apply {
            text = "DeX TOUCHPAD\n두 손가락 ↑↓ = 활성 앱 스와이프"
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            background = rounded(Color.argb(210, 12, 14, 17), 16f)
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val pointerSpeed = prefs.pointerSpeed
        var lastX = 0f
        var lastY = 0f
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var moved = false
        var dragging = false
        var twoFinger = false
        var swipeTriggered = false
        var twoFingerY = 0f

        val longPress = Runnable {
            if (!moved && !twoFinger) {
                actions.beginDrag()
                dragging = true
            }
        }

        pad.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    downAt = event.eventTime
                    moved = false
                    dragging = false
                    twoFinger = false
                    swipeTriggered = false
                    v.postDelayed(longPress, RemoteControlMath.TAP_MAX_MS)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.removeCallbacks(longPress)
                    if (dragging) {
                        actions.endDrag()
                        dragging = false
                    }
                    twoFinger = true
                    swipeTriggered = false
                    twoFingerY = averageY(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 || twoFinger) {
                        v.removeCallbacks(longPress)
                        val y = averageY(event)
                        val dy = y - twoFingerY
                        if (!swipeTriggered && abs(dy) > dp(42)) {
                            actions.swipeActive(up = dy < 0f)
                            swipeTriggered = true
                        }
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(event.x - downX) + abs(event.y - downY) > slop) {
                            moved = true
                            v.removeCallbacks(longPress)
                        }
                        actions.movePointer(dx * pointerSpeed, dy * pointerSpeed)
                        lastX = event.x
                        lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (dragging) actions.endDrag()
                    else if (RemoteControlMath.isTap(
                            lifted = event.actionMasked == MotionEvent.ACTION_UP,
                            moved = moved,
                            twoFinger = twoFinger,
                            elapsedMs = event.eventTime - downAt,
                        )
                    ) actions.tap()
                    dragging = false
                    twoFinger = false
                    true
                }
                else -> true
            }
        }
        return pad
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 0) return 0f
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun textButton(textValue: String) = TextView(context).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        background = rounded(Color.argb(220, 48, 52, 59), 14f)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun cornerLabel(corner: RemoteDockPrefs.Corner): String = when (corner) {
        RemoteDockPrefs.Corner.TOP_LEFT -> "좌상"
        RemoteDockPrefs.Corner.TOP_RIGHT -> "우상"
        RemoteDockPrefs.Corner.BOTTOM_LEFT -> "좌하"
        RemoteDockPrefs.Corner.BOTTOM_RIGHT -> "우하"
    }

    private fun cornerGravity(corner: RemoteDockPrefs.Corner): Int = when (corner) {
        RemoteDockPrefs.Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
        RemoteDockPrefs.Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
        RemoteDockPrefs.Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        RemoteDockPrefs.Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

