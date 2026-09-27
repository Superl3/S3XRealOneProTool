package com.raphael.dexspike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Quadrado vermelho com uma cruz branca central, usado como marcador visual
 * do overlay de teste (S4). Desenho simples via onDraw — sem drawables externos.
 */
class CrossSquareView(context: Context) : View(context) {

    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, squarePaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, crossPaint)
        canvas.drawLine(w / 2f, 0f, w / 2f, h, crossPaint)
    }
}
