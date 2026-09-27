package com.raphael.handmouse.tracking

import kotlin.math.sqrt

/**
 * Ponto de mão desacoplado do tipo do MediaPipe (`NormalizedLandmark`) — o chamador
 * ([HandTracker]/quem consumir seus resultados) converte para esta abstração antes de chamar
 * [PinchDetector.update]. Mantém esta classe 100% testável em JVM puro, sem depender do
 * MediaPipe nem do Android.
 */
data class HandPoint(val x: Float, val y: Float, val z: Float)

enum class PinchEvent { DOWN, UP }

/**
 * Detector de pinch por histerese dupla + debounce — PLANO.md §6.3 (réplica exata do
 * pseudocódigo, sem alterações de threshold/alpha/debounce):
 *
 * ratio = dist3(THUMB_TIP=4, INDEX_TIP=8) / dist3(WRIST=0, INDEX_MCP=5)   (invariante à
 * distância da câmera, já que ambas as distâncias escalam junto)
 * ema = EMA(ratio, alpha=0.5)
 * entra em pinch quando ema < 0.28; sai quando ema > 0.38 (histerese — nunca um threshold só)
 * debounce assimétrico: DOWN confirma após 3 frames consecutivos; UP após 2 (liberação mais
 * rápida sem elevar falsos cliques, já que o clique só sai no UP via ClickDragStateMachine).
 */
class PinchDetector {

    companion object {
        /** Raw (un-smoothed) pinch ratio thumb-tip↔index-tip / wrist↔index-MCP (Eye Tools fork:
         * palm-menu confirm and click suppression read it without disturbing the EMA). */
        fun ratio(landmarks: List<HandPoint>): Float =
            dist3(landmarks[4], landmarks[8]) / dist3(landmarks[0], landmarks[5])

        private fun dist3(a: HandPoint, b: HandPoint): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        private const val ENTER_THRESHOLD = 0.28f
        // Ajuste de hardware (2026-07-22): EXIT 0.50→0.38 (o usuário tinha que abrir MUITO os dedos
        // pra soltar o pinch). Mantém histerese de 0.10 (gap ENTER 0.28 → EXIT 0.38) — zona morta
        // suficiente pra não oscilar (mão relaxada fica bem acima de 0.6). Debounce assimétrico:
        // liberação (UP) confirma em 2 frames em vez de 3, cortando ~37ms do lag do clique, sem
        // elevar falso-positivo (o custo de um UP prematuro é encurtar um clique, nunca inventá-lo;
        // o caminho crítico de falso clique é o DOWN, mantido em 3). EMA_ALPHA fica em 0.5 de
        // propósito — é a suavização que torna o debounce curto seguro contra spikes de 1 frame.
        private const val EXIT_THRESHOLD = 0.38f
        private const val EMA_ALPHA = 0.5f
        private const val DOWN_DEBOUNCE_FRAMES = 3
        private const val UP_DEBOUNCE_FRAMES = 2
    }

    /** Tunables (Eye Tools fork settings) — defaults are the upstream constants. */
    var enterThreshold = ENTER_THRESHOLD
    var exitThreshold = EXIT_THRESHOLD
    var downDebounceFrames = DOWN_DEBOUNCE_FRAMES

    private var ema = Float.NaN
    private var candidate: Boolean? = null
    private var candidateFrames = 0

    var isPinched: Boolean = false
        private set

    /**
     * [landmarks]: 21 pontos normalizados (mesma ordem do `HandLandmarkerResult.landmarks()[0]`
     * do MediaPipe — índices 0=WRIST, 4=THUMB_TIP, 5=INDEX_MCP, 8=INDEX_TIP).
     * Retorna [PinchEvent.DOWN]/[PinchEvent.UP] só no frame em que a transição é confirmada
     * (após o debounce); `null` em todos os outros frames (inclusive durante a contagem).
     */
    fun update(landmarks: List<HandPoint>): PinchEvent? {
        val ratio = ratio(landmarks)
        ema = if (ema.isNaN()) ratio else EMA_ALPHA * ratio + (1f - EMA_ALPHA) * ema

        val want = when {
            !isPinched && ema < enterThreshold -> true
            isPinched && ema > exitThreshold -> false
            else -> return null
        }

        if (candidate != want) {
            candidate = want
            candidateFrames = 0
        }
        candidateFrames++
        // want==true é entrada (DOWN); want==false é liberação (UP) — ver o ramo acima.
        val requiredFrames = if (want) downDebounceFrames else UP_DEBOUNCE_FRAMES
        if (candidateFrames < requiredFrames) return null

        isPinched = want
        candidate = null
        return if (want) PinchEvent.DOWN else PinchEvent.UP
    }

    /** Limpa EMA, candidato e estado de pinch (chamar quando a mão some/reaparece). */
    fun reset() {
        ema = Float.NaN
        candidate = null
        candidateFrames = 0
        isPinched = false
    }

}
