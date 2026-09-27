package com.raphael.handmouse.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.exp

/**
 * Janela fullscreen transparente e ESTÁTICA no display do DeX — caminho A.1 do plano
 * (PLANO.md §3.4/§6.4; caminho A.2 com `SurfaceControlViewHost` fica pra depois, só se a
 * revisão final apontar jank). Cursor = [CursorView] filha, movida EXCLUSIVAMENTE via
 * `setTranslationX/Y` sincronizado com `Choreographer.postFrameCallback` — a posição-alvo é só
 * atualizada por [moveTo]; o frame callback aplica a última posição a cada vsync. NUNCA
 * `WindowManager.updateViewLayout()` por frame (IPC ao system_server a cada frame = jank
 * garantido).
 *
 * Guardas obrigatórias (brief Tarefa 4):
 * - Uma única janela por vez: [show] sempre remove a anterior ([hide]) antes de criar a nova,
 *   mesmo que seja pro mesmo display — nunca há duas janelas simultâneas.
 * - Remoção garantida estruturalmente: o trecho entre `addView` e o registro final do estado
 *   interno roda dentro de um try/finally que desfaz (remove a view + limpa estado) se
 *   qualquer coisa falhar depois do `addView` — nunca fica uma janela órfã sem estado
 *   associado. [hide] também é chamado pelo dono ([com.raphael.handmouse.service.HandMouseAccessibilityService])
 *   em `onDisplayRemoved` e no `onDestroy` do serviço.
 * - Todas as exceções são logadas e nunca propagam pro chamador — um erro no overlay não pode
 *   derrubar o pipeline de tracking.
 *
 * Fade-out: se [moveTo] não é chamado por [FADE_DELAY_MS] (mão perdida — o
 * [com.raphael.handmouse.tracking.CursorPipeline] simplesmente para de chamar `moveTo` quando
 * `HandTracker` reporta `onHandLost`), o cursor esmaece; reaparece instantaneamente na próxima
 * chamada a [moveTo]. O reset do `OneEuroFilter`/`PinchDetector` quando a mão REAPARECE é
 * responsabilidade do `CursorPipeline` (reage a `onHandLost`/`onHandResult`, um sinal mais
 * direto e imediato que "sem updates no overlay há 2s") — o fade aqui é só o efeito visual,
 * deliberadamente desacoplado daquele reset semântico.
 */
class CursorOverlay(private val hostContext: Context) {

    companion object {
        private const val TAG = "CursorOverlay"
        private const val FADE_DELAY_MS = 2_000L
        private const val FADE_DURATION_MS = 300L

        /** Constante de tempo da interpolação por vsync (fix de fluidez 2026-07-23, 4ª rodada):
         * o tracking produz ~60 amostras/s mas o Choreographer roda no refresh do display
         * (120Hz no S25) — aplicar a amostra SECA a cada vsync deixava o cursor andando em
         * degraus de 1 amostra (metade dos vsyncs repetia o frame anterior). Em vez disso, a
         * posição VISÍVEL desliza exponencialmente até o alvo com esta tau: cada vsync anda
         * `1-exp(-dt/tau)` do caminho — movimento contínuo a 120Hz, como um mouse nativo. Custo:
         * o próprio tau de lag visual (imperceptível somado à latência do pipeline; a POSIÇÃO DE
         * CLIQUE não passa por aqui — GestureInjector usa a âncora do pipeline, sem esse lag).
         *
         * 2026-07-24 ("deslizar naturalmente"): 18ms → 30ms, junto com o reforço do OneEuroFilter
         * (minCutoff 0.6). Tau maior arredonda os degraus da dead-zone e qualquer resíduo de
         * jitter num deslize contínuo — o cursor "flutua" até o alvo em vez de acompanhar cada
         * micro-correção. 30ms ainda é só ~2 amostras de tracking de lag visual. */
        private const val SMOOTHING_TAU_NANOS = 30_000_000f // 30ms

        /** Distância (px) abaixo da qual a posição visível "cola" no alvo — encerra o glide sem
         * rastejar subpixel indefinidamente. */
        private const val SNAP_DISTANCE_PX = 0.5f
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var cursorView: CursorView? = null
    private var layeredMenuView: LayeredMenuView? = null // Eye Tools fork
    private var handDebugView: HandDebugView? = null
    private var debugEnabled = false
    private var dimView: android.view.View? = null // Eye Tools fork: auto-dimming layer
    private var dimLevel = 0f

    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    private var frameLoopActive = false
    private var faded = false
    private var fadeAnimator: ValueAnimator? = null

    /** Mute do cursor (gesto thumbs-up, 2026-07-23 — ver [setHiddenByUser]): escondido POR
     * DECISÃO DO USUÁRIO, distinto do fade por inatividade. Enquanto `true`, [reappear] é
     * no-op — um moveTo defensivo de quem esqueceu o estado não pode desfazer o mute. */
    private var hiddenByUser = false

    // Estado da interpolação por vsync (ver SMOOTHING_TAU_NANOS) — só tocado no frameCallback
    // (main thread). hasApplied=false força SNAP no 1º frame após show() (sem glide vindo de
    // uma posição obsoleta de outra sessão da janela).
    private var appliedX = 0f
    private var appliedY = 0f
    private var hasApplied = false
    private var lastFrameNanos = 0L

    private val fadeRunnable = Runnable { startFadeOut() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            cursorView?.let { cv ->
                // The circular cursor's center is its click hotspot. Its view is centered on
                // the same target used by GestureInjector, including during scale animation.
                //
                // Interpolação por vsync (4ª rodada — ver SMOOTHING_TAU_NANOS): a posição
                // visível desliza exponencialmente até o alvo em vez de saltar de amostra em
                // amostra — 120Hz de movimento com 60 amostras/s de tracking.
                val tx = targetX
                val ty = targetY
                if (!hasApplied) {
                    appliedX = tx
                    appliedY = ty
                    hasApplied = true
                } else {
                    val dt = (frameTimeNanos - lastFrameNanos).coerceIn(0L, 100_000_000L)
                    val alpha = 1f - exp(-dt / SMOOTHING_TAU_NANOS)
                    appliedX += (tx - appliedX) * alpha
                    appliedY += (ty - appliedY) * alpha
                    if (abs(tx - appliedX) < SNAP_DISTANCE_PX && abs(ty - appliedY) < SNAP_DISTANCE_PX) {
                        appliedX = tx
                        appliedY = ty
                    }
                }
                cv.translationX = appliedX - cv.sizePx / 2f
                cv.translationY = appliedY - cv.sizePx / 2f
            }
            lastFrameNanos = frameTimeNanos
            if (frameLoopActive) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /** Cria (ou substitui) a janela do cursor no [display] alvo. Guard de janela única: sempre
     * remove a anterior primeiro, mesmo se for o mesmo display. Nunca lança — falhas são
     * logadas e deixam o overlay em estado "escondido" (sem janela ativa).
     *
     * Retorna `true` se a janela foi adicionada com sucesso; `false` se falhou (o dono deve
     * re-tentar após um atraso). O caso de falha mais comum em hardware (validado no S25): o
     * display do DeX acabou de ser criado (`onDisplayAdded`) e sua infraestrutura de janelas
     * ainda não está pronta — `addView` lança `BadTokenException` ("token null is not valid").
     * Poucas centenas de ms depois o display aceita a janela normalmente. */
    fun show(display: Display): Boolean {
        hide()

        val winCtx = try {
            hostContext.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao criar contexto de janela pro display ${display.displayId}", e)
            return false
        }

        val wm = winCtx.getSystemService(WindowManager::class.java)
        val cv = CursorView(winCtx)
        val layered = LayeredMenuView(winCtx)
        val debug = HandDebugView(winCtx).apply {
            visibility = if (debugEnabled) android.view.View.VISIBLE else android.view.View.GONE
        }
        val dim = android.view.View(winCtx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = dimLevel
        }
        val root = FrameLayout(winCtx).apply {
            addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(debug, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(layered, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(cv, FrameLayout.LayoutParams(cv.sizePx, cv.sizePx, Gravity.TOP or Gravity.LEFT))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adicionar overlay ao display ${display.displayId} (re-tentável)", e)
            return false
        }

        // Guard de remoção garantida: qualquer exceção daqui em diante desfaz a janela recém
        // criada (remove + limpa estado) antes de desistir — nunca fica uma janela órfã.
        var initialized = false
        try {
            windowManager = wm
            rootView = root
            cursorView = cv
            layeredMenuView = layered
            handDebugView = debug
            dimView = dim
            // Janela recriada DURANTE o mute (troca de display com o cursor mutado): a view
            // nova nasce respeitando o estado do usuário — invisível até o gesto de religar.
            faded = hiddenByUser
            cv.alpha = if (hiddenByUser) 0f else 1f
            startFrameLoop()
            scheduleFade()
            initialized = true
        } finally {
            if (!initialized) {
                try {
                    wm.removeView(root)
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao remover overlay após erro de inicialização", e)
                }
                clearState()
            }
        }
        return initialized
    }

    /** Atualiza a posição-alvo (pixels do display do DeX) — o Choreographer aplica no próximo
     * vsync. Sem efeito se não há janela ativa (ver [show]). */
    /** Eye Tools fork: where the cursor is drawn (null until the first move) — palm-menu actions
     * apply here. */
    val cursorPosition: Pair<Float, Float>? get() = if (hasTarget) targetX to targetY else null
    private var hasTarget = false

    fun moveTo(x: Float, y: Float) {
        targetX = x
        targetY = y
        hasTarget = true
        if (rootView == null) return
        if (faded) reappear()
        scheduleFade()
    }

    /** Feedback visual (cor/escala) do estado de pinch (cobre PRESSED/DRAGGING — ver Javadoc de
     * [CursorView]). O clique/drag em si é injetado pelo [com.raphael.handmouse.input.GestureInjector],
     * chamado pelo [com.raphael.handmouse.tracking.CursorPipeline] — nunca aqui. */
    fun setPinched(pinched: Boolean) {
        cursorView?.setPinched(pinched)
    }

    /** Pulso visual de feedback num clique CONFIRMADO (Tarefa 5) — sem efeito se não há janela
     * ativa. */
    fun pulseClick() {
        cursorView?.pulseClick()
    }

    // ---- Eye Tools fork ----

    /** Auto-dimming level 0..1 (black layer under the cursor and menu), animated. */
    fun setDim(level: Float) {
        val target = level.coerceIn(0f, 0.9f)
        if (kotlin.math.abs(target - dimLevel) < 0.01f) return
        dimLevel = target
        dimView?.animate()?.alpha(target)?.setDuration(800)?.start()
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        handDebugView?.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun updateHandDebug(points: List<com.raphael.handmouse.tracking.HandPoint>, status: List<String>) {
        if (debugEnabled) handDebugView?.show(points, status)
    }

    fun hidePalmMenu() {
        layeredMenuView?.hide()
    }

    /** Layered palm menu next to the cursor. */
    fun showLayeredMenu(selection: com.raphael.handmouse.tracking.MenuAction?) {
        val v = layeredMenuView ?: return
        if (faded) reappear()
        scheduleFade()
        v.show(targetX, targetY, selection)
    }

    /** Estado visual de ESCUTA DE VOZ (2026-07-23, spec voice-control) — main thread (chamado
     * pelo VoiceCommandController). Ouvindo: cancela o fade e força o cursor visível (o usuário
     * PRECISA ver que o gesto pegou), exceto se mutado por gesto ([setHiddenByUser] — o mute do
     * usuário vence). Fim da escuta: rearma o fade normal. */
    fun setListening(listening: Boolean) {
        val cv = cursorView ?: return
        cv.setListening(listening)
        if (listening) {
            mainHandler.removeCallbacks(fadeRunnable)
            if (!hiddenByUser) {
                fadeAnimator?.cancel()
                fadeAnimator = null
                cv.alpha = 1f
                faded = false
            }
        } else {
            scheduleFade()
        }
    }

    /** Flash de resultado do comando de voz — repasse pro [CursorView.flashResult]. */
    fun flashVoiceResult(success: Boolean) {
        cursorView?.flashResult(success)
    }

    /**
     * Esconde/mostra SÓ a view do cursor (gesto de mute por thumbs-up, 2026-07-23) — a JANELA
     * fica viva (destruir/recriar janela é caro e historicamente frágil; ver retry do
     * showOverlayWithRetry). Esconder cancela fade pendente e zera o alpha NA HORA (sem esperar
     * os 2s do fade por inatividade); mostrar restaura o alpha e rearma o fade normal. Main
     * thread (mesmo contrato dos demais métodos). Sem janela ativa, só registra o estado — o
     * próximo [show] cria a view já respeitando-o (janela recriada durante o mute nasce
     * invisível).
     */
    fun setHiddenByUser(hidden: Boolean) {
        hiddenByUser = hidden
        val cv = cursorView ?: return
        mainHandler.removeCallbacks(fadeRunnable)
        fadeAnimator?.cancel()
        fadeAnimator = null
        if (hidden) {
            cv.alpha = 0f
            faded = true
        } else {
            cv.alpha = 1f
            faded = false
            scheduleFade()
        }
    }

    /** Remove a janela, se existir — idempotente (seguro chamar mesmo sem janela ativa).
     * Chamado pelo dono em `onDisplayRemoved` e no `onDestroy` do serviço. */
    fun hide() {
        stopFrameLoop()
        mainHandler.removeCallbacks(fadeRunnable)
        fadeAnimator?.cancel()
        fadeAnimator = null

        val wm = windowManager
        val root = rootView
        try {
            if (wm != null && root != null) {
                wm.removeView(root)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao remover overlay", e)
        } finally {
            clearState()
        }
    }

    private fun scheduleFade() {
        mainHandler.removeCallbacks(fadeRunnable)
        mainHandler.postDelayed(fadeRunnable, FADE_DELAY_MS)
    }

    private fun reappear() {
        if (hiddenByUser) return // mutado por gesto: nenhum moveTo desfaz (ver setHiddenByUser)
        fadeAnimator?.cancel()
        cursorView?.alpha = 1f
        faded = false
    }

    private fun startFadeOut() {
        val cv = cursorView ?: return
        fadeAnimator?.cancel()
        faded = true
        fadeAnimator = ValueAnimator.ofFloat(cv.alpha, 0f).apply {
            duration = FADE_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { anim -> cv.alpha = anim.animatedValue as Float }
            start()
        }
    }

    private fun startFrameLoop() {
        if (frameLoopActive) return
        frameLoopActive = true
        hasApplied = false // 1º frame da janela nova SNAPA no alvo (ver comentário do campo)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameLoopActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun clearState() {
        windowManager = null
        rootView = null
        cursorView = null
        layeredMenuView = null
        handDebugView = null
        dimView = null
        faded = false
    }
}
