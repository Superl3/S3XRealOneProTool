package com.raphael.handmouse.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Short-lived ring at the point a click was actually injected (2026-09-28). The pinch click uses
 * the position 120ms before the pinch, the fist click the position where the fingers started
 * closing, and magnetic click may snap to a nearby control — all of which can differ from where
 * the live cursor is drawn. Shown by [CursorOverlay.pulseClickAt] only when they differ, and
 * held by [CursorOverlay.showClickPreview] while a fist click is pending.
 */
class ClickMarkerView(context: Context) : View(context) {

    companion object {
        private const val SIZE_DP = 22f
        private const val FADE_MS = 350L
        private const val HOLD_ALPHA = 0.7f
    }

    val sizePx: Int = (SIZE_DP * context.resources.displayMetrics.density).toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    init {
        alpha = 0f
    }

    /** Keeps the marker on ([x], [y]) while a fist click is pending — where the tap will land
     * (after magnetic snap), so the user can see it before the ring closes. */
    fun holdAt(x: Float, y: Float) {
        translationX = x - sizePx / 2f
        translationY = y - sizePx / 2f
        animate().cancel()
        alpha = HOLD_ALPHA
    }

    fun hideNow() {
        animate().cancel()
        alpha = 0f
    }

    /** Centres the marker on ([x], [y]) (display px) and fades it out. */
    fun flashAt(x: Float, y: Float) {
        translationX = x - sizePx / 2f
        translationY = y - sizePx / 2f
        animate().cancel()
        alpha = 1f
        animate().alpha(0f).setDuration(FADE_MS).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = sizePx / 2f
        canvas.drawCircle(c, c, c - paint.strokeWidth, paint)
    }
}
