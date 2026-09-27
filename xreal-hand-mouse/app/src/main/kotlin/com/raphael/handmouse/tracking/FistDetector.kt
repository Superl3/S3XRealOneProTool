package com.raphael.handmouse.tracking

import kotlin.math.sqrt

/**
 * Detector de PUNHO FECHADO — gesto de recentralização do cursor (2026-07-23, pedido do
 * usuário): segurar o punho fechado por ~2s recentraliza o cursor na tela (o modo relativo
 * [RelativeCursorMapper] perde a correspondência mão↔centro com o tempo — cada clamp em borda
 * ou re-ancoragem desloca um pouco; este gesto é o "reset" barato).
 *
 * ## Métrica invariante ao ponto de vista (3ª rodada 2026-07-23, "punho difícil de reconhecer")
 * A métrica original — média de `dist3(ponta, punho)/handScale` — funcionava com a palma de
 * frente pra câmera, mas variava demais com a ROTAÇÃO da mão (punho de lado / costas da mão):
 * as distâncias ponta↔punho encolhem com a projeção enquanto o `handScale` (punho↔MCP) encolhe
 * numa proporção DIFERENTE, então o mesmo punho físico dava razões bem distintas conforme o
 * ângulo — e o usuário tinha que "acertar a pose" pro gesto pegar.
 *
 * Métrica nova, por dedo: `ratio_i = dist3(punho, ponta_i) / dist3(punho, PIP_i)` — a ponta
 * comparada com a PRÓPRIA junta média do dedo (PIPs 6/10/14/18). Dedo estendido: a ponta fica
 * além da PIP na mesma direção → ratio ~1.3+. Dedo dobrado (punho): a ponta curva de volta e
 * fica tão ou mais perto do punho que a PIP → ratio ~0.8-1.0. Como numerador e denominador são
 * segmentos quase colineares da MESMA cadeia do dedo, a projeção em qualquer ângulo distorce os
 * dois juntos — a razão sobrevive à rotação da mão (de lado, de costas, inclinada).
 *
 * O gate usa o MÁXIMO dos 4 ratios (o dedo MAIS estendido manda): punho de verdade exige TODOS
 * os dedos dobrados — a mão relaxada de quem aponta (indicador semi-estendido, ratio ~1.25)
 * fica fora por causa do próprio indicador, sem depender da média mascarar nada. O polegar fica
 * FORA da métrica (num punho real ele pode envolver os dedos por fora ou dentro, variando
 * demais).
 *
 * Mesma receita de robustez do [PinchDetector]: EMA (alpha 0.5) + histerese dupla (entra
 * < [ENTER_THRESHOLD], sai > [EXIT_THRESHOLD]) + debounce ASSIMÉTRICO (entra em
 * [ENTER_DEBOUNCE_FRAMES] ≈ 250ms — pose sustentada, imune às transições do pinch; sai em
 * [EXIT_DEBOUNCE_FRAMES]). Thresholds calibrados pela anatomia (estendido ~1.3, relaxado
 * ~1.05-1.2, dobrado ~0.8-1.0) — VALIDAR/AFINAR EM HARDWARE.
 *
 * Classe PURA (sem Android) — testável em JVM ([FistDetectorTest]); 1 instância no
 * [CursorPipeline].
 */
class FistDetector {

    companion object {
        /** Entra em punho quando o EMA do MÁXIMO dos 4 ratios ponta/PIP cai abaixo disto.
         * 1.05 → 0.95 (5ª rodada 2026-07-23, "punho falso durante o pinch"): um pinch com os
         * outros dedos relaxados/curvados passava do 1.05 e o punho falso ou engolia o clique
         * ou — pior — disparava a recentralização depois de 2s (o "cursor resetou do nada"
         * relatado em hardware). 0.95 exige curl de punho DE VERDADE em todos os dedos. */
        private const val ENTER_THRESHOLD = 0.95f
        /** Sai quando o EMA sobe acima disto (gap generoso pra não oscilar). */
        private const val EXIT_THRESHOLD = 1.20f
        private const val EMA_ALPHA = 0.5f
        /** Debounce ASSIMÉTRICO (5ª rodada): a 60fps os 3 frames antigos viraram ~50ms — poses
         * TRANSITÓRIAS da formação do pinch confirmavam punho. 15 frames (~250ms a 60fps)
         * exigem uma pose SUSTENTADA — de graça pro gesto real (que já segura 2s pro recenter)
         * e fatal pros falsos. A saída continua rápida (3 frames) pra devolver o pinch logo. */
        private const val ENTER_DEBOUNCE_FRAMES = 15
        private const val EXIT_DEBOUNCE_FRAMES = 3

        /** Veto de polegar (gesto de mute, 2026-07-23): punho DE VERDADE tem o polegar
         * recolhido junto dos dedos — `dist3(THUMB_TIP=4, INDEX_MCP=5)/handScale` ~0.3-0.6.
         * Um THUMBS-UP (dedos dobrados + polegar estendido, ~0.9-1.3) tem exatamente a mesma
         * assinatura nos 4 dedos e, sem o veto, seria lido como punho (e dispararia a
         * recentralização aos 2s no meio do gesto de mute). Entra em punho só com o EMA do
         * polegar < [THUMB_TUCKED_ENTER_MAX]; SAI do punho se passar de [THUMB_TUCKED_EXIT]
         * (transição punho→thumbs-up solta o punho pro [ThumbsUpDetector] assumir). */
        private const val THUMB_TUCKED_ENTER_MAX = 0.85f
        private const val THUMB_TUCKED_EXIT = 1.0f

        private const val LM_WRIST = 0
        private const val LM_THUMB_TIP = 4
        private const val LM_INDEX_MCP = 5

        /** Pares (ponta, PIP) por dedo — indicador, médio, anular, mindinho. */
        private val FINGER_TIP_PIP = arrayOf(
            intArrayOf(8, 6),
            intArrayOf(12, 10),
            intArrayOf(16, 14),
            intArrayOf(20, 18),
        )
    }

    private var ema = Float.NaN
    private var thumbEma = Float.NaN
    private var candidate: Boolean? = null
    private var candidateFrames = 0

    var isFist: Boolean = false
        private set

    /** [landmarks]: 21 pontos (mesma ordem do MediaPipe). Retorna o estado JÁ atualizado
     * ([isFist]) — diferente do [PinchDetector], não há evento de transição: quem cronometra o
     * hold de 2s é o chamador ([CursorPipeline]), que só precisa do estado por frame. */
    fun update(landmarks: List<HandPoint>): Boolean {
        val wrist = landmarks[LM_WRIST]
        var maxRatio = 0f
        for (pair in FINGER_TIP_PIP) {
            val ratio = dist3(wrist, landmarks[pair[0]]) / dist3(wrist, landmarks[pair[1]])
            if (ratio > maxRatio) maxRatio = ratio
        }
        ema = if (ema.isNaN()) maxRatio else EMA_ALPHA * maxRatio + (1f - EMA_ALPHA) * ema

        // Veto de polegar (ver THUMB_TUCKED_*): mesma métrica do ThumbsUpDetector, de propósito
        // — os dois detectores enxergam a MESMA fronteira punho↔thumbs-up.
        val handScale = dist3(wrist, landmarks[LM_INDEX_MCP])
        val thumbRatio = dist3(landmarks[LM_THUMB_TIP], landmarks[LM_INDEX_MCP]) / handScale
        thumbEma = if (thumbEma.isNaN()) thumbRatio else EMA_ALPHA * thumbRatio + (1f - EMA_ALPHA) * thumbEma

        val want = when {
            !isFist && ema < ENTER_THRESHOLD && thumbEma < THUMB_TUCKED_ENTER_MAX -> true
            isFist && (ema > EXIT_THRESHOLD || thumbEma > THUMB_TUCKED_EXIT) -> false
            else -> return isFist
        }

        if (candidate != want) {
            candidate = want
            candidateFrames = 0
        }
        candidateFrames++
        val requiredFrames = if (want) ENTER_DEBOUNCE_FRAMES else EXIT_DEBOUNCE_FRAMES
        if (candidateFrames < requiredFrames) return isFist

        isFist = want
        candidate = null
        return isFist
    }

    /** Limpa EMAs, candidato e estado (chamar quando a mão some/reaparece). */
    fun reset() {
        ema = Float.NaN
        thumbEma = Float.NaN
        candidate = null
        candidateFrames = 0
        isFist = false
    }

    private fun dist3(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
