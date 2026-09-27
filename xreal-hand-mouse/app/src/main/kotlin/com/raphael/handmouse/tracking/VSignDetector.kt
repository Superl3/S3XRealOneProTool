package com.raphael.handmouse.tracking

import kotlin.math.sqrt

/**
 * Detector de SINAL DE "V" (indicador+médio estendidos, anular+mindinho dobrados) — gesto de
 * ativação do CONTROLE POR VOZ (2026-07-23, spec `2026-07-23-voice-control-design.md`): segurar
 * o "V" ~500ms (cronometrado pelo [CursorPipeline], como punho/thumbs-up) abre a janela de
 * escuta do `VoiceCommandController`.
 *
 * ## Métrica — a mesma razão ponta/PIP invariante a ângulo do [FistDetector]
 * `ratio_i = dist3(punho, ponta_i) / dist3(punho, PIP_i)`: estendido ~1.3+, dobrado ~0.8-1.0,
 * e a razão sobrevive à rotação da mão (numerador e denominador distorcem juntos na projeção).
 * Duas agregações, cada uma com o pior caso mandando:
 * - **Estendidos**: `min(ratio_indicador, ratio_médio)` — os DOIS têm que estar esticados.
 * - **Dobrados**: `max(ratio_anular, ratio_mindinho)` — os DOIS têm que estar dobrados.
 *
 * Isso separa o "V" de todos os gestos existentes por construção: palma aberta tem anular/
 * mindinho estendidos (max alto), punho e thumbs-up têm indicador/médio dobrados (min baixo),
 * e a mão de quem aponta o cursor tem o médio semi-dobrado (min ~1.05-1.2, abaixo do enter).
 * O polegar fica FORA da métrica (num "V" real ele pode cruzar a palma ou ficar solto — varia
 * demais; nenhuma separação depende dele).
 *
 * Mesma receita de robustez dos irmãos: EMA (alpha 0.5) em cada agregação + histerese dupla +
 * debounce ASSIMÉTRICO (entra em [ENTER_DEBOUNCE_FRAMES] ≈ 200ms a 60fps — pose deliberada;
 * sai em [EXIT_DEBOUNCE_FRAMES]). Thresholds calibrados pela anatomia — VALIDAR/AFINAR EM
 * HARDWARE.
 *
 * Classe PURA (sem Android) — testável em JVM ([VSignDetectorTest]); 1 instância no
 * [CursorPipeline].
 */
class VSignDetector {

    companion object {
        /** Entra quando o EMA do MIN(indicador, médio) passa disto (os dois bem esticados). */
        private const val EXTENDED_ENTER = 1.25f
        /** Sai quando o EMA do min cai abaixo disto (gap pra não oscilar). */
        private const val EXTENDED_EXIT = 1.10f
        /** Entra quando o EMA do MAX(anular, mindinho) está abaixo disto (os dois dobrados). */
        private const val CURLED_ENTER = 1.05f
        /** Sai quando o EMA do max passa disto (algum dos dois esticou — virou palma?). */
        private const val CURLED_EXIT = 1.20f
        private const val EMA_ALPHA = 0.5f
        /** ~200ms a 60fps — pose deliberada, imune a transições entre gestos. */
        private const val ENTER_DEBOUNCE_FRAMES = 12
        private const val EXIT_DEBOUNCE_FRAMES = 3

        private const val LM_WRIST = 0

        /** Pares (ponta, PIP): indicador e médio (estendidos no "V"). */
        private val EXTENDED_TIP_PIP = arrayOf(intArrayOf(8, 6), intArrayOf(12, 10))

        /** Pares (ponta, PIP): anular e mindinho (dobrados no "V"). */
        private val CURLED_TIP_PIP = arrayOf(intArrayOf(16, 14), intArrayOf(20, 18))
    }

    private var extendedEma = Float.NaN
    private var curledEma = Float.NaN
    private var candidate: Boolean? = null
    private var candidateFrames = 0

    var isVSign: Boolean = false
        private set

    /** [landmarks]: 21 pontos (ordem do MediaPipe). Retorna o estado JÁ atualizado ([isVSign])
     * — quem cronometra o hold de 500ms é o chamador ([CursorPipeline]). */
    fun update(landmarks: List<HandPoint>): Boolean {
        val wrist = landmarks[LM_WRIST]

        var minExtended = Float.MAX_VALUE
        for (pair in EXTENDED_TIP_PIP) {
            val ratio = dist3(wrist, landmarks[pair[0]]) / dist3(wrist, landmarks[pair[1]])
            if (ratio < minExtended) minExtended = ratio
        }
        extendedEma = ema(extendedEma, minExtended)

        var maxCurled = 0f
        for (pair in CURLED_TIP_PIP) {
            val ratio = dist3(wrist, landmarks[pair[0]]) / dist3(wrist, landmarks[pair[1]])
            if (ratio > maxCurled) maxCurled = ratio
        }
        curledEma = ema(curledEma, maxCurled)

        val want = when {
            !isVSign && extendedEma > EXTENDED_ENTER && curledEma < CURLED_ENTER -> true
            isVSign && (extendedEma < EXTENDED_EXIT || curledEma > CURLED_EXIT) -> false
            else -> return isVSign
        }

        if (candidate != want) {
            candidate = want
            candidateFrames = 0
        }
        candidateFrames++
        val requiredFrames = if (want) ENTER_DEBOUNCE_FRAMES else EXIT_DEBOUNCE_FRAMES
        if (candidateFrames < requiredFrames) return isVSign

        isVSign = want
        candidate = null
        return isVSign
    }

    /** Limpa EMAs, candidato e estado (chamar quando a mão some/reaparece). */
    fun reset() {
        extendedEma = Float.NaN
        curledEma = Float.NaN
        candidate = null
        candidateFrames = 0
        isVSign = false
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
