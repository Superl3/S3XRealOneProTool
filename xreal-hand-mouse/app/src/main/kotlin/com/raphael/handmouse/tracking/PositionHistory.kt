package com.raphael.handmouse.tracking

import kotlin.math.abs

/**
 * Posição de cursor amostrada num instante — [timestampMs] usa a mesma base de tempo do
 * `HandTracker.Result.timestampMs` (ver [PositionHistory]).
 */
data class Position(
    val x: Float,
    val y: Float,
    val timestampMs: Long,
    /** [FistDetector.curl] of the same frame (NaN when not recorded) — see [PositionHistory.fistOnset]. */
    val curl: Float = Float.NaN,
)

/**
 * Buffer circular de posições do cursor (PLANO.md §6.3-nota, brief Tarefa 5): usado pra
 * "congelar" o cursor na posição de ~100ms atrás no pinch-down — o ato de pinçar desloca a
 * ponta do indicador, a mesma solução usada por HoloLens/Vision Pro. Classe PURA (sem
 * dependência de Android) — 1 instância no [CursorPipeline], alimentada a cada frame com a
 * posição já mapeada+filtrada (pré dead-zone — ver Javadoc de [CursorPipeline] sobre a decisão
 * de alimentar isto a cada frame, sem gating pela dead-zone).
 *
 * [positionAt] busca o ponto **mais próximo** (vizinho mais próximo por distância absoluta de
 * timestamp) do alvo pedido — não interpola. Se o histórico não cobre o passado pedido (buffer
 * curto ou capacidade insuficiente), o ponto mais antigo disponível já É o mais próximo por
 * definição.
 *
 * 2026-09-28: the window is TIME ([maxAgeMs]), not a sample count — 12 samples were 200ms at
 * 60fps but 500ms at 24fps, and the fist click needs to reach back to before the fingers started
 * closing (~250ms debounce + the closing motion itself), which 12 samples at 60fps could not.
 */
class PositionHistory(private val maxAgeMs: Long = 600L) {

    private val buffer = ArrayDeque<Position>()

    /** Registra uma nova posição, descartando as mais antigas que [maxAgeMs]. */
    fun record(x: Float, y: Float, timestampMs: Long, curl: Float = Float.NaN) {
        buffer.addLast(Position(x, y, timestampMs, curl))
        while (buffer.size > 1 && timestampMs - buffer.first().timestampMs > maxAgeMs) buffer.removeFirst()
    }

    /** Posição mais próxima de [targetMs], ou `null` se o buffer estiver vazio. */
    fun positionAt(targetMs: Long): Position? {
        return buffer.minByOrNull { abs(it.timestampMs - targetMs) }
    }

    /**
     * Where the hand was when the fingers started closing into the fist confirmed at [nowMs].
     * Walks back from the last sample that was not yet a fist (curl ≥ [fistCurl]) through the
     * closing motion — where the curl was still falling by at least [closingDelta] per
     * [stableSpanMs] — and returns the first sample where it was not: the moment right before
     * the fingers (and the knuckles the cursor follows) started to move. An open hand, a relaxed
     * one or one that moved while open all end the walk at the last steady pose, so a hand swept
     * open onto the target and relaxed there resolves to the target, not to the sweep. `null`
     * without curl samples or when the whole history is already a fist.
     */
    fun fistOnset(
        nowMs: Long,
        fistCurl: Float,
        stableSpanMs: Long = 50L,
        closingDelta: Float = 0.03f,
    ): Position? {
        val samples = buffer.filter { it.timestampMs <= nowMs && !it.curl.isNaN() }
        val last = samples.indexOfLast { it.curl >= fistCurl }
        if (last < 0) return null
        for (i in last downTo 0) {
            val here = samples[i]
            val earlier = samples.lastOrNull { it.timestampMs <= here.timestampMs - stableSpanMs } ?: return here
            if (earlier.curl - here.curl < closingDelta) return here
        }
        return samples[0]
    }

    /** Limpa o histórico (chamar quando a mão some — evita congelar num ponto "velho" de antes
     * da mão sumir, mesma semântica de [com.raphael.handmouse.tracking.OneEuroFilter.reset]). */
    fun reset() {
        buffer.clear()
    }
}
