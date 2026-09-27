package com.raphael.handmouse.tracking

import kotlin.math.sqrt

/**
 * Detector de THUMBS-UP — gesto de MUTE do cursor (2026-07-23, pedido do usuário: "quando
 * abaixo os braços, o cursor fica perdido e visível na tela"; ver spec
 * `docs/superpowers/specs/2026-07-23-cursor-mute-gesture-design.md`). Segurar o thumbs-up
 * ~1s (cronometrado pelo [CursorPipeline], como o punho faz com a recentralização) alterna o
 * cursor entre ativo e mutado.
 *
 * ## Pose = TRÊS condições simultâneas (cada uma com EMA alpha 0.5 + histerese própria)
 * 1. **4 dedos dobrados** — `max(dist3(punho, ponta_i) / dist3(punho, PIP_i))` dos pares
 *    8/6, 12/10, 16/14, 20/18: a MESMA métrica ponta/PIP invariante a ângulo do
 *    [FistDetector] (numerador e denominador distorcem juntos na projeção).
 * 2. **Polegar ESTENDIDO** — `dist3(THUMB_TIP=4, INDEX_MCP=5) / handScale`: é o que separa
 *    o thumbs-up de um punho (no punho o polegar envolve os dedos, ficando perto do MCP do
 *    indicador ~0.3-0.6; estendido fica ~0.9-1.3).
 * 3. **Polegar pra CIMA** — `(wrist.y - thumbTip.y) / handScale` (y da imagem cresce PRA
 *    BAIXO): rejeita thumbs-down e o punho com polegar caído de lado — o gesto exige a
 *    intenção clara do "joinha".
 *
 * Debounce assimétrico: entrada em [ENTER_DEBOUNCE_FRAMES] (~130ms — a tripla condição já é
 * anatomicamente distinta de pinch/palma/punho, e o hold de 1s no chamador é a defesa final
 * contra transitórios), saída em [EXIT_DEBOUNCE_FRAMES].
 *
 * Classe PURA (sem Android) — testável em JVM ([ThumbsUpDetectorTest]); 1 instância no
 * [CursorPipeline].
 */
class ThumbsUpDetector {

    companion object {
        /** Dedos dobrados: max dos ratios ponta/PIP (mesma escala do [FistDetector]). */
        private const val CURL_ENTER_THRESHOLD = 1.05f
        private const val CURL_EXIT_THRESHOLD = 1.20f

        /** Polegar estendido: dist3(4,5)/handScale. Punho ~0.3-0.6; thumbs-up ~0.9-1.3. */
        private const val THUMB_EXT_ENTER = 0.85f
        private const val THUMB_EXT_EXIT = 0.65f

        /** Polegar pra cima: (wrist.y - thumb.y)/handScale. Thumbs-up real ~1.2-2.0. */
        private const val THUMB_UP_ENTER = 0.5f
        private const val THUMB_UP_EXIT = 0.3f

        private const val EMA_ALPHA = 0.5f
        private const val ENTER_DEBOUNCE_FRAMES = 8
        private const val EXIT_DEBOUNCE_FRAMES = 3

        private const val LM_WRIST = 0
        private const val LM_THUMB_TIP = 4
        private const val LM_INDEX_MCP = 5

        /** Pares (ponta, PIP) — indicador, médio, anular, mindinho (mesmos do FistDetector). */
        private val FINGER_TIP_PIP = arrayOf(
            intArrayOf(8, 6),
            intArrayOf(12, 10),
            intArrayOf(16, 14),
            intArrayOf(20, 18),
        )
    }

    private var curlEma = Float.NaN
    private var thumbExtEma = Float.NaN
    private var thumbUpEma = Float.NaN
    private var candidate: Boolean? = null
    private var candidateFrames = 0

    var isActive: Boolean = false
        private set

    /** [landmarks]: 21 pontos (ordem do MediaPipe). Retorna o estado JÁ atualizado ([isActive])
     * — quem cronometra o hold de 1s é o chamador ([CursorPipeline]). */
    fun update(landmarks: List<HandPoint>): Boolean {
        val wrist = landmarks[LM_WRIST]
        val handScale = dist3(wrist, landmarks[LM_INDEX_MCP])

        var maxCurl = 0f
        for (pair in FINGER_TIP_PIP) {
            val ratio = dist3(wrist, landmarks[pair[0]]) / dist3(wrist, landmarks[pair[1]])
            if (ratio > maxCurl) maxCurl = ratio
        }
        curlEma = ema(curlEma, maxCurl)

        val thumb = landmarks[LM_THUMB_TIP]
        thumbExtEma = ema(thumbExtEma, dist3(thumb, landmarks[LM_INDEX_MCP]) / handScale)
        thumbUpEma = ema(thumbUpEma, (wrist.y - thumb.y) / handScale)

        val want = when {
            !isActive && curlEma < CURL_ENTER_THRESHOLD &&
                thumbExtEma > THUMB_EXT_ENTER && thumbUpEma > THUMB_UP_ENTER -> true
            isActive && (curlEma > CURL_EXIT_THRESHOLD ||
                thumbExtEma < THUMB_EXT_EXIT || thumbUpEma < THUMB_UP_EXIT) -> false
            else -> return isActive
        }

        if (candidate != want) {
            candidate = want
            candidateFrames = 0
        }
        candidateFrames++
        val requiredFrames = if (want) ENTER_DEBOUNCE_FRAMES else EXIT_DEBOUNCE_FRAMES
        if (candidateFrames < requiredFrames) return isActive

        isActive = want
        candidate = null
        return isActive
    }

    /** Limpa EMAs, candidato e estado (chamar quando a mão some/reaparece). */
    fun reset() {
        curlEma = Float.NaN
        thumbExtEma = Float.NaN
        thumbUpEma = Float.NaN
        candidate = null
        candidateFrames = 0
        isActive = false
    }

    private fun ema(prev: Float, sample: Float): Float =
        if (prev.isNaN()) sample else EMA_ALPHA * sample + (1f - EMA_ALPHA) * prev

    private fun dist3(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
