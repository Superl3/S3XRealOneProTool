package com.raphael.handmouse.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.raphael.handmouse.tracking.HandPoint
import kotlin.math.max

/** Small on-glasses diagnostic panel showing what the hand tracker actually sees. */
class HandDebugView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val panel = RectF(12f * d, 12f * d, 330f * d, 230f * d)
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(226, 12, 20, 28) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(89, 216, 224)
        strokeWidth = 2f * d
    }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val palm = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 85) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * d
    }
    private var points: List<HandPoint> = emptyList()
    private var lines: List<String> = emptyList()

    private val links = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
    )

    fun show(hand: List<HandPoint>, status: List<String>) {
        points = hand.toList()
        lines = status.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(panel, 12f * d, 12f * d, background)
        lines.take(7).forEachIndexed { index, text ->
            canvas.drawText(text, 24f * d, (38f + index * 25f) * d, label)
        }
        if (points.size < 21) return
        val left = 193f * d
        val top = 28f * d
        val size = 120f * d
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val scale = size / max(maxX - minX, maxY - minY).coerceAtLeast(0.01f)
        fun sx(i: Int) = left + (points[i].x - minX) * scale
        fun sy(i: Int) = top + (points[i].y - minY) * scale
        links.forEach { (a, b) -> canvas.drawLine(sx(a), sy(a), sx(b), sy(b), line) }
        points.indices.forEach { i -> canvas.drawCircle(sx(i), sy(i), 3f * d, if (i in listOf(5, 9, 13, 17)) palm else point) }
    }
}
