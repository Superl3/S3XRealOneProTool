package com.raphael.handmouse.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.raphael.handmouse.R
import com.raphael.handmouse.tracking.MenuAction

/** Compact three-way menu around the frozen cursor. The middle is a safe neutral zone. */
class LayeredMenuView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val pillW = 86f * density
    private val pillH = 40f * density
    private val radius = 92f * density
    private val margin = 14f * density
    private var shown = false
    private var cx = 0f
    private var cy = 0f
    private var selected: MenuAction? = null
    private val rect = RectF()

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(226, 24, 30, 38) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(74, 220, 233) }
    private val danger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 102, 104) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val selectedText = Paint(text).apply { color = Color.rgb(12, 29, 35) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 255, 255)
        strokeWidth = 1.5f * density
    }

    fun show(cursorX: Float, cursorY: Float, selection: MenuAction?) {
        val maxX = (width - radius - pillW / 2f - margin).coerceAtLeast(width / 2f)
        val minX = width - maxX
        val minY = radius + pillH / 2f + margin
        val maxY = (height - pillH / 2f - margin).coerceAtLeast(minY)
        val x = cursorX.coerceIn(minX, maxX)
        val y = cursorY.coerceIn(minY, maxY)
        if (shown && selected == selection && cx == x && cy == y) return
        shown = true
        selected = selection
        cx = x
        cy = y
        invalidate()
    }

    fun hide() {
        if (!shown) return
        shown = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!shown) return
        canvas.drawLine(cx, cy, cx - radius * 0.65f, cy, guide)
        canvas.drawLine(cx, cy, cx + radius * 0.65f, cy, guide)
        canvas.drawLine(cx, cy, cx, cy - radius * 0.65f, guide)
        drawPill(canvas, cx - radius, cy, MenuAction.BACK, R.string.menu_back)
        drawPill(canvas, cx, cy - radius, MenuAction.HOME, R.string.menu_home)
        drawPill(canvas, cx + radius, cy, MenuAction.CLOSE_APP, R.string.menu_close)
        canvas.drawCircle(cx, cy, 5f * density, if (selected == null) accent else background)
    }

    private fun drawPill(canvas: Canvas, x: Float, y: Float, action: MenuAction, label: Int) {
        rect.set(x - pillW / 2f, y - pillH / 2f, x + pillW / 2f, y + pillH / 2f)
        val hot = action == selected
        canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f,
            if (!hot) background else if (action == MenuAction.CLOSE_APP) danger else accent)
        canvas.drawText(context.getString(label), x, y - (text.ascent() + text.descent()) / 2f,
            if (hot) selectedText else text)
    }
}
