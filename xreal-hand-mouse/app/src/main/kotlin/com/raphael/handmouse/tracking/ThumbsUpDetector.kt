package com.raphael.handmouse.tracking

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
    }

    private var curlEma = Float.NaN
    private var thumbExtEma = Float.NaN
    private var thumbUpEma = Float.NaN
    private var lastTimestampMs = 0L
    private var dtMs = 0L
    private val debounce = TimedDebounce()

    var isActive: Boolean = false
        private set

    /** [landmarks]: 21 pontos (ordem do MediaPipe). Retorna o estado JÁ atualizado ([isActive])
     * — quem cronometra o hold de 1s é o chamador ([CursorPipeline]). [timestampMs]: frame
     * time — EMA and debounce are time-based ([FrameTiming]). */
    fun update(landmarks: List<HandPoint>, timestampMs: Long): Boolean =
        update(HandFeatures.from(landmarks), timestampMs)

    /** [allowEnter] = false blocks a NEW thumbs-up (see [FistDetector.update]). */
    fun update(features: HandFeatures, timestampMs: Long, allowEnter: Boolean = true): Boolean {
        dtMs = timestampMs - lastTimestampMs
        lastTimestampMs = timestampMs
        curlEma = ema(curlEma, features.maxCurl)
        thumbExtEma = ema(thumbExtEma, features.thumbExtension)
        thumbUpEma = ema(thumbUpEma, features.thumbUp)

        val want = when {
            !isActive && allowEnter && curlEma < CURL_ENTER_THRESHOLD &&
                thumbExtEma > THUMB_EXT_ENTER && thumbUpEma > THUMB_UP_ENTER -> true
            isActive && (curlEma > CURL_EXIT_THRESHOLD ||
                thumbExtEma < THUMB_EXT_EXIT || thumbUpEma < THUMB_UP_EXIT) -> false
            else -> {
                // Condition broke: the pending transition starts over. The debounce means N
                // CONSECUTIVE frames (as documented); the frame counter used to survive these
                // frames, so scattered qualifying frames added up — and with a clock, a stale
                // pending entry would confirm instantly on the next qualifying frame.
                debounce.reset()
                return isActive
            }
        }

        val requiredFrames = if (want) ENTER_DEBOUNCE_FRAMES else EXIT_DEBOUNCE_FRAMES
        if (!debounce.confirm(want, timestampMs, FrameTiming.framesToHoldMs(requiredFrames))) return isActive

        isActive = want
        return isActive
    }

    /** Limpa EMAs, candidato e estado (chamar quando a mão some/reaparece). */
    fun reset() {
        curlEma = Float.NaN
        thumbExtEma = Float.NaN
        thumbUpEma = Float.NaN
        debounce.reset()
        isActive = false
    }

    private fun ema(prev: Float, sample: Float): Float = FrameTiming.ema(prev, sample, EMA_ALPHA, dtMs)
}
