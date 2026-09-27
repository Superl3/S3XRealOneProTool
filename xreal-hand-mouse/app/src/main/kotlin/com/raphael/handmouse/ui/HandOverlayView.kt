package com.raphael.handmouse.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/**
 * View de debug (NÃO é o overlay do cursor no DeX — esse é Tarefa 4) desenhada por cima do
 * preview da câmera: os 21 landmarks + conexões dos dedos (via `HandLandmarker.HAND_CONNECTIONS`
 * — evita hardcodar os pares de índices), cor muda quando `isPinched`, e um HUD com as
 * métricas pedidas pelo brief (fps de inferência, latência de inferência, tempo de conversão,
 * latência fim-a-fim estimada).
 *
 * Espelhamento/orientação (PLANO.md §6.2): a câmera Eye aponta pra fora do usuário (visão
 * "como os olhos veem") — landmarks são desenhados com x direto, SEM espelhar. Suposição
 * documentada; validação real só é possível com hardware (Tarefa 4).
 */
class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Metrics(
        val inferenceFps: Double,
        val endToEndLatencyMs: Long,
        val conversionMs: Double,
    ) {
        /** Aproximação: fim-a-fim menos conversão (não é uma medição isolada da inferência —
         * não há um timestamp dedicado "pós-conversão/pré-inferência" no pipeline atual). */
        val estimatedInferenceLatencyMs: Long
            get() = (endToEndLatencyMs - conversionMs).toLong().coerceAtLeast(0L)
    }

    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var isPinched: Boolean = false
    private var metrics: Metrics? = null

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }
    private val hudBackgroundPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }

    /** Atualiza os landmarks + estado de pinch + métricas do frame mais recente e redesenha. */
    fun update(landmarks: List<NormalizedLandmark>, isPinched: Boolean, metrics: Metrics) {
        this.landmarks = landmarks
        this.isPinched = isPinched
        this.metrics = metrics
        invalidate()
    }

    /** Sem mão detectada neste frame — mantém o último HUD de métricas, mas some com o
     * esqueleto (evita "congelar" uma mão fantasma na tela). */
    fun clearHand() {
        landmarks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val color = if (isPinched) Color.RED else Color.parseColor("#00E676")
        pointPaint.color = color
        linePaint.color = color

        if (landmarks.isNotEmpty()) {
            for (connection in HandLandmarker.HAND_CONNECTIONS) {
                val start = landmarks.getOrNull(connection.start()) ?: continue
                val end = landmarks.getOrNull(connection.end()) ?: continue
                canvas.drawLine(
                    start.x() * width, start.y() * height,
                    end.x() * width, end.y() * height,
                    linePaint,
                )
            }
            for (landmark in landmarks) {
                canvas.drawCircle(landmark.x() * width, landmark.y() * height, 9f, pointPaint)
            }
        }

        drawHud(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        val m = metrics ?: return
        val lines = buildList {
            add("fps inferência: %.1f".format(m.inferenceFps))
            add("latência inferência (est.): ${m.estimatedInferenceLatencyMs}ms")
            add("conversão: %.1f ms".format(m.conversionMs))
            add("fim-a-fim (est.): ${m.endToEndLatencyMs}ms")
            if (isPinched) add("PINCH")
        }

        val padding = 16f
        val lineHeight = hudTextPaint.textSize + 10f
        val boxHeight = padding * 2 + lineHeight * lines.size
        canvas.drawRect(0f, 0f, 440f, boxHeight, hudBackgroundPaint)

        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, padding + lineHeight * (index + 1) - 8f, hudTextPaint)
        }
    }
}
