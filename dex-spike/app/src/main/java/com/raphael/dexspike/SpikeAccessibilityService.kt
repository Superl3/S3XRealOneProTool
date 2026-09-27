package com.raphael.dexspike

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Serviço de acessibilidade do spike. Expõe a instância viva via [instance] para a
 * Activity chamar diretamente (mesmo processo, sem IPC).
 *
 * Responsável pelos dois testes de risco do projeto:
 * - S3: [tapAt] injeta um tap num display específico via dispatchGesture + setDisplayId.
 * - S4: [showOverlay] cria uma janela TYPE_ACCESSIBILITY_OVERLAY num display específico.
 */
class SpikeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SpikeAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não usado neste spike.
    }

    override fun onInterrupt() {
        // Não usado neste spike.
    }

    /** S3: dispatcha um tap em (x, y) no display [displayId]. */
    fun tapAt(displayId: Int, x: Float, y: Float, onResult: (String) -> Unit) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .setDisplayId(displayId)
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onResult("S3: tap CONCLUÍDO em (${x.toInt()}, ${y.toInt()}) display $displayId")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onResult("S3: tap CANCELADO em (${x.toInt()}, ${y.toInt()}) display $displayId")
                    }
                },
                null
            )
            if (!dispatched) {
                onResult("S3: dispatchGesture retornou false para (${x.toInt()}, ${y.toInt()}) display $displayId")
            }
        } catch (e: Exception) {
            onResult("S3: EXCEÇÃO ao tocar em (${x.toInt()}, ${y.toInt()}) display $displayId: ${summarize(e)}")
        }
    }

    /** S4: mostra um overlay fullscreen animado no [display] alvo por [durationMs]. */
    fun showOverlay(display: Display, durationMs: Long = 15_000L, onEvent: (String) -> Unit) {
        try {
            val winCtx = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = winCtx.getSystemService(WindowManager::class.java)

            val metrics = winCtx.resources.displayMetrics
            val density = metrics.density
            val sizePx = (120 * density).toInt()

            val square = CrossSquareView(winCtx)
            val label = TextView(winCtx).apply {
                text = "OVERLAY DE TESTE — display ${display.displayId}"
                textSize = 24f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                setPadding(24, 16, 24, 16)
            }

            val root = FrameLayout(winCtx)
            root.addView(square, FrameLayout.LayoutParams(sizePx, sizePx))
            root.addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            wm.addView(root, lp)
            onEvent("S4: overlay CRIADA no display ${display.displayId}")

            val maxX = (metrics.widthPixels - sizePx).coerceAtLeast(0).toFloat()
            val maxY = (metrics.heightPixels - sizePx).coerceAtLeast(0).toFloat()

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3000L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    square.translationX = f * maxX
                    square.translationY = f * maxY
                }
            }
            animator.start()
            onEvent("S4: overlay ANIMANDO no display ${display.displayId}")

            mainHandler.postDelayed({
                try {
                    animator.cancel()
                    wm.removeView(root)
                    onEvent("S4: overlay REMOVIDA do display ${display.displayId}")
                } catch (e: Exception) {
                    onEvent("S4: EXCEÇÃO ao remover overlay: ${summarize(e)}")
                }
            }, durationMs)
        } catch (e: Exception) {
            onEvent("S4: EXCEÇÃO ao criar overlay: ${summarize(e)}")
        }
    }

    private fun summarize(e: Throwable): String {
        val frames = e.stackTrace.take(3).joinToString(" | ") { it.toString() }
        return "${e::class.java.simpleName}: ${e.message} [$frames]"
    }
}
