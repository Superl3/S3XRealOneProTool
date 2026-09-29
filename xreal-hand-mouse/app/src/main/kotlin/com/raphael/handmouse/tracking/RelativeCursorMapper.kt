package com.raphael.handmouse.tracking

/** Bounds em pixels do display alvo (nunca hardcodar — vêm de `currentWindowMetrics.bounds`
 * no chamador; ver PLANO.md §6.2 sobre a troca normal 1920x1080 ↔ UltraWide 3840x1080).
 * [displayId] (Tarefa 5) é o id do display real repassado ao [com.raphael.handmouse.input.GestureInjector]
 * (`dispatchGesture` precisa saber pra QUAL display injetar — nunca hardcodar); default `-1`
 * (inválido) só existe pra não quebrar construções antigas/testes que não precisam dele. */
data class DisplayBounds(val width: Int, val height: Int, val displayId: Int = -1)

/** Ponto de cursor em pixels do display alvo. */
data class CursorPoint(val x: Float, val y: Float)

/**
 * Mapeamento RELATIVO (modo trackpad) — substitui o mapeamento absoluto + calibração de 2 pontos
 * (2026-07-23, decisão com o usuário): o cursor se move pelos DELTAS da posição normalizada da
 * mão × ganho fixo, como um trackpad. Zero calibração — funciona em qualquer postura na primeira
 * abertura do app, que era a limitação estrutural do mapeamento absoluto (o box de calibração
 * dependia de ONDE a mão fica no FOV da câmera, que muda com a postura do usuário; o default era
 * um chute conservador de ganho baixo, e o wizard confundia — foi aposentado junto).
 *
 * - **Ganho**: [spanX] = fração da LARGURA do FOV que cruza a tela inteira (0.40 → mover a mão
 *   por 40% do campo de visão atravessa a tela; ajustar aqui o "sensibilidade" global).
 * - **Isotropia física**: a entrada já é isotrópica ([HandTracker.Result.isoPoints], y em
 *   unidades de largura da imagem — 2026-09-28). Antes, y normalizado pela ALTURA era corrigido
 *   por uma constante 0.75 feita pra câmera 4:3; a Eye entrega 1920×1080 (16:9), então o
 *   cursor andava 1.33× mais na vertical que na horizontal pro mesmo movimento físico.
 * - **Clutch natural**: o cursor clampa nas bordas SEM acumular excesso (voltar responde na
 *   hora), e [onHandLost] solta a âncora — tirar a mão do FOV e reposicionar continua o cursor
 *   de onde estava (igual levantar o dedo do trackpad). O 1º sample de todos nasce no centro.
 * - **Ruído não acumula**: o delta é telescópico (cursor = origem + (nx_t − nx_âncora) × ganho),
 *   então jitter da mão NÃO vira random-walk — mesmo comportamento de ruído do mapeamento
 *   absoluto, com o [com.raphael.handmouse.tracking.OneEuroFilter] filtrando depois como sempre.
 *
 * Classe PURA (sem Android) — testável em JVM ([RelativeCursorMapperTest]); 1 instância no
 * [CursorPipeline].
 */
class RelativeCursorMapper(
    var spanX: Float = 0.40f, // mutable: cursor-sensitivity setting (Eye Tools fork)
    // Modo precisão (2026-07-23, "difícil clicar em coisas pequenas"): ganho ADAPTATIVO por
    // velocidade, a mesma ideia do "Enhance pointer precision" de mouse de verdade. Movimento
    // LENTO (mirando um alvo) usa [precisionFactor] do ganho; movimento rápido usa o ganho
    // cheio, com rampa linear entre [precisionLowPxPerFrame] e [precisionHighPxPerFrame]
    // (px/frame do delta CRU, EMA 0.5 pra não chavear no ruído). A "deriva" mão↔cursor que isso
    // introduz é um não-problema no modo relativo (punho 2s recentraliza).
    //
    // 2ª iteração (2026-07-24, "aumentar a precisão em movimentos lentos"): precisionFactor
    // 0.35 → 0.25 (ajuste fino ÷4 do ganho cheio) e rampa 3-18 → 4-20 px/frame — começa a
    // acelerar um pouco mais tarde e estica a zona de transição. Nota de calibração: os limiares
    // são px/FRAME e a inferência subiu de 30 → 60fps depois do tuning original, então em px/s a
    // zona de precisão já tinha dobrado de faixa (4px/frame @ 60fps ≈ 240px/s; 20 ≈ 1200px/s) —
    // valores confirmados em hardware nesta sessão, não re-derivados no papel.
    // 2026-09-28: os limiares seguem em px por frame DE 60fps, mas a velocidade é medida por dt
    // real ([FrameTiming]) — a 24fps (térmico) o mesmo movimento dava 2,5× mais px/frame e o
    // modo precisão soltava cedo demais.
    private val precisionFactor: Float = 0.25f,
    private val precisionLowPxPerFrame: Float = 4f,
    private val precisionHighPxPerFrame: Float = 20f,
) {
    private var anchorNx: Float? = null
    private var anchorNy: Float? = null
    private var cursorX: Float? = null
    private var cursorY: Float? = null
    private var speedEma = Float.NaN
    private var lastTimestampMs = 0L

    /** [nx]/[ny]: isotropic hand position ([HandTracker.Result.isoPoints]); [timestampMs]: frame
     * time, used to measure speed per real interval. */
    fun map(nx: Float, ny: Float, bounds: DisplayBounds, timestampMs: Long): CursorPoint {
        val gain = bounds.width / spanX
        var cx = cursorX ?: (bounds.width / 2f)
        var cy = cursorY ?: (bounds.height / 2f)

        val ax = anchorNx
        val ay = anchorNy
        if (ax != null && ay != null) {
            val dtMs = (timestampMs - lastTimestampMs).coerceAtLeast(1L)
            val rawDx = (nx - ax) * gain
            val rawDy = (ny - ay) * gain
            val distPerRefFrame = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy) * FrameTiming.REFERENCE_FRAME_MS / dtMs
            speedEma = FrameTiming.ema(speedEma, distPerRefFrame, 0.5f, dtMs)
            val ramp = ((speedEma - precisionLowPxPerFrame) /
                (precisionHighPxPerFrame - precisionLowPxPerFrame)).coerceIn(0f, 1f)
            val factor = precisionFactor + (1f - precisionFactor) * ramp
            cx += rawDx * factor
            cy += rawDy * factor
        }

        cx = cx.coerceIn(0f, bounds.width - 1f)
        cy = cy.coerceIn(0f, bounds.height - 1f)

        anchorNx = nx
        anchorNy = ny
        lastTimestampMs = timestampMs
        cursorX = cx
        cursorY = cy
        return CursorPoint(cx, cy)
    }

    /** Mão sumiu: solta só a ÂNCORA (a reaparição re-ancora sem teleporte); a posição do cursor
     * fica onde está de propósito — é o comportamento de trackpad ("levantar o dedo"). */
    fun onHandLost() {
        anchorNx = null
        anchorNy = null
        speedEma = Float.NaN
    }

    /** Recentraliza: o próximo sample renasce no CENTRO do display e re-ancora na posição atual
     * da mão (gesto de punho fechado 2s — ver [FistDetector]/[CursorPipeline]). É o "reset" da
     * correspondência mão↔tela que o modo relativo perde aos poucos (clamps de borda e
     * re-ancoragens deslocam o centro com o tempo). */
    fun recenter() {
        cursorX = null
        cursorY = null
        anchorNx = null
        anchorNy = null
        speedEma = Float.NaN
    }
}
