package com.raphael.handmouse.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Compact circular pointer with a dark outline and bright inner ring, visible on DeX windows
 * and video. The hotspot is the CENTER of this view.
 * PLANO.md §3.4. Muda de cor (ciano) e escala levemente quando [setPinched] indica pinch ativo
 * (cobre visualmente os estados PRESSED/DRAGGING da [com.raphael.handmouse.tracking.ClickDragStateMachine]
 * — ambos são "pinçado", mesmo sinal — Tarefa 5 estende este feedback em vez de duplicá-lo, ver
 * [pulseClick] pro que É novo: o pulso de clique CONFIRMADO).
 *
 * Tamanho fixo (não depende de layout_width/height do pai) porque é sempre adicionada com
 * `FrameLayout.LayoutParams(sizePx, sizePx)` pelo [CursorOverlay] — ver [sizePx].
 */
class CursorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        private const val SIZE_DP = 26f
        private const val PULSE_PEAK_SCALE = 1.6f
        private const val PULSE_DURATION_MS = 150L
        /** Duração do flash verde/vermelho de resultado do comando de voz (2026-07-23). */
        private const val FLASH_DURATION_MS = 400L
        private val COLOR_LISTENING = Color.YELLOW
        private val COLOR_FLASH_OK = Color.GREEN
        private val COLOR_FLASH_FAIL = Color.RED
    }

    /** Lado (px) do quadrado que hospeda o desenho da seta — usado pelo [CursorOverlay] tanto
     * para o LayoutParams quanto para centralizar a translação no ponto-alvo. */
    val sizePx: Int = (SIZE_DP * context.resources.displayMetrics.density).toInt()

    private var pinched = false
    private var listening = false
    /** Pending fist click, 0..1 (0 = no ring) — see [setFistProgress]. */
    private var fistProgress = 0f
    /** Fix de revisão 2026-07-23: setPinched durante os 400ms do flash truncava o flash — a
     * cor-base só pode voltar quando o flash termina ou é cancelado por setListening. */
    private var flashing = false
    private var pulseAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 4f * context.resources.displayMetrics.density
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 0, 0, 0)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * context.resources.displayMetrics.density
    }
    private val ringBounds = RectF()

    init {
        pivotX = sizePx / 2f
        pivotY = sizePx / 2f
    }

    /** Atualiza o feedback visual de pinch — idempotente (não invalida se o estado não mudou). */
    fun setPinched(isPinched: Boolean) {
        if (pinched == isPinched) return
        pinched = isPinched
        if (!flashing) fillPaint.color = baseFillColor() // flash em voo termina os 400ms dele
        scaleX = if (isPinched) 1.25f else 1f
        scaleY = if (isPinched) 1.25f else 1f
        invalidate()
    }

    /** Ring around the pointer that fills while a fist click is pending (2026-09-28): the user
     * sees the click coming and can cancel it by opening the hand before the ring closes. */
    fun setFistProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (kotlin.math.abs(p - fistProgress) < 0.02f && (p == 0f) == (fistProgress == 0f)) return
        fistProgress = p
        invalidate()
    }

    /** Pulso visual de feedback num clique CONFIRMADO (Tarefa 5, brief: "Click confirmado dá um
     * pulso visual (...) — estenda, não duplique"). Sobe rapidamente a escala além do que
     * [setPinched] já aplica e volta pra escala-base atual (0 ou 1.25 conforme [pinched]) — não
     * mexe em cor, só um "bump" de escala curto e independente do estado de pinch. */
    fun pulseClick() {
        pulseAnimator?.cancel()
        val baseScale = if (pinched) 1.25f else 1f
        pulseAnimator = ValueAnimator.ofFloat(PULSE_PEAK_SCALE, baseScale).apply {
            duration = PULSE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                scaleX = v
                scaleY = v
            }
            start()
        }
    }

    /** Cursor AMARELO enquanto a escuta de voz está aberta (2026-07-23, spec voice-control) —
     * mesma mecânica de cor do [setPinched]; escuta tem prioridade visual sobre pinch (durante
     * a escuta o pipeline suprime gestos, então o pinch nem deveria acontecer). */
    fun setListening(isListening: Boolean) {
        if (listening == isListening) return
        listening = isListening
        removeCallbacks(flashRestoreRunnable) // um flash pendente não pode sobrescrever depois
        flashing = false // cancelamento de flash também limpa a flag
        fillPaint.color = baseFillColor()
        invalidate()
    }

    /** Flash verde (comando executado) / vermelho (timeout, no-match, erro) por
     * [FLASH_DURATION_MS], voltando sozinho à cor-base. */
    fun flashResult(success: Boolean) {
        removeCallbacks(flashRestoreRunnable)
        flashing = true // marca o início do flash antes de pintar
        fillPaint.color = if (success) COLOR_FLASH_OK else COLOR_FLASH_FAIL
        invalidate()
        postDelayed(flashRestoreRunnable, FLASH_DURATION_MS)
    }

    private val flashRestoreRunnable = Runnable {
        flashing = false // flash expirou, permite setPinched mudar a cor novamente
        fillPaint.color = baseFillColor()
        invalidate()
    }

    /** Cor-base do preenchimento pela precedência: escuta > pinch > normal. */
    private fun baseFillColor(): Int = when {
        listening -> COLOR_LISTENING
        pinched -> Color.CYAN
        else -> Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = sizePx / 2f
        val r = 8f * resources.displayMetrics.density
        canvas.drawCircle(c, c, r, innerPaint)
        canvas.drawCircle(c, c, r, strokePaint)
        canvas.drawCircle(c, c, r, fillPaint)
        dotPaint.color = fillPaint.color
        canvas.drawCircle(c, c, (if (pinched) 3.5f else 2f) * resources.displayMetrics.density, dotPaint)
        if (fistProgress > 0f) {
            val ringR = 11.5f * resources.displayMetrics.density
            ringBounds.set(c - ringR, c - ringR, c + ringR, c + ringR)
            canvas.drawArc(ringBounds, -90f, 360f * fistProgress, false, ringPaint)
        }
    }
}
