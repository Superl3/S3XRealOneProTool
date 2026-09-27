package com.raphael.handmouse.tracking

import kotlin.math.abs

/**
 * Posição de cursor amostrada num instante — [timestampMs] usa a mesma base de tempo do
 * `HandTracker.Result.timestampMs` (ver [PositionHistory]).
 */
data class Position(val x: Float, val y: Float, val timestampMs: Long)

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
 */
class PositionHistory(private val capacity: Int = 12) {

    private val buffer = ArrayDeque<Position>(capacity)

    /** Registra uma nova posição, descartando a mais antiga se [capacity] for excedida. */
    fun record(x: Float, y: Float, timestampMs: Long) {
        buffer.addLast(Position(x, y, timestampMs))
        if (buffer.size > capacity) buffer.removeFirst()
    }

    /** Posição mais próxima de [targetMs], ou `null` se o buffer estiver vazio. */
    fun positionAt(targetMs: Long): Position? {
        return buffer.minByOrNull { abs(it.timestampMs - targetMs) }
    }

    /** Limpa o histórico (chamar quando a mão some — evita congelar num ponto "velho" de antes
     * da mão sumir, mesma semântica de [com.raphael.handmouse.tracking.OneEuroFilter.reset]). */
    fun reset() {
        buffer.clear()
    }
}
