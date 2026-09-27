package com.raphael.handmouse.tracking

import kotlin.math.abs
import kotlin.math.sqrt

/** Direção de um swipe de palma aberta (relativo à imagem: y cresce PRA BAIXO). */
enum class SwipeEvent { LEFT, RIGHT, UP, DOWN }

/**
 * Detector de "SWIPE DE PALMA ABERTA" (2026-07-23) — dois estágios encadeados:
 *
 * ## 1. Gate de palma aberta (ESTRITO, por dedo + anti-pinch)
 * A mão relaxada de quem só aponta o cursor costuma ter anular/mindinho semi-curvados. Como o
 * chamador CONGELA o cursor enquanto [isPalmOpen] é `true`, uma detecção por MÉDIA (como a do
 * [FistDetector]) deixaria a palma "abrir" com 2 dedos esticados e travaria o cursor sem querer.
 * Por isso o gate é por dedo INDIVIDUAL: palma aberta só quando os 4 dedos (pontas 8/12/16/20)
 * estão TODOS estendidos, i.e. `min(ratio_i) > ENTER`, com
 * `ratio_i = dist3(ponta_i, WRIST=0) / handScale` e `handScale = dist3(WRIST=0, INDEX_MCP=5)` — o
 * MESMO normalizador do [PinchDetector]/[FistDetector], invariante à distância da câmera.
 *
 * **Gate do polegar (fix de hardware 2026-07-23, "palma abre quando tento pinçar")**: os 4 ratios
 * acima IGNORAM o polegar — um pinch com a mão espalmada (polegar↔indicador juntos, os outros 3
 * dedos abertos) mantinha os 4 ratios altos e o modo palma engolia o pinch (resetava o
 * [PinchDetector] todo frame; clique nunca saía). A palma aberta DE VERDADE tem o polegar longe
 * da ponta do indicador, então o gate agora também exige `dist3(THUMB_TIP=4, INDEX_TIP=8) /
 * handScale` ALTO — a MESMA métrica do [PinchDetector] (enter pinch < 0.28, mão relaxada > 0.6):
 * entra em palma só com > [THUMB_FAR_ENTER] (0.70) e SAI assim que cair < [THUMB_FAR_EXIT]
 * (0.50) — ou seja, começar a pinçar derruba o modo palma antes de o pinch confirmar.
 *
 * Aplico o EMA (alpha=0.5) sobre o MÍNIMO dos 4 ratios (não um EMA por dedo). Justificativa: o
 * gate é um AND estrito ("todos estendidos"), que é exatamente `min(ratios) > threshold`; o pior
 * dedo é quem manda. Suavizar diretamente esse mínimo dá um único escalar que representa "o dedo
 * mais fechado", casa 1-pra-1 com a semântica do gate. O ratio do polegar tem um segundo EMA
 * próprio (mesma alpha). Curvar QUALQUER dedo (ou aproximar o polegar do indicador) derruba o
 * gate e, após o debounce, fecha a palma. Histerese nas duas métricas; debounce ASSIMÉTRICO:
 * entrada em [ENTER_DEBOUNCE_FRAMES] (deliberada — blinda contra extensões transitórias no meio
 * de um gesto), saída em [EXIT_DEBOUNCE_FRAMES] (folgada o bastante pra atravessar o glitch de
 * landmarks do próprio swipe rápido — ver histórico nos constantes).
 *
 * ## 2. Swipe (só com a palma aberta)
 * Rastreia a posição normalizada do landmark 5 (INDEX_MCP) — a MESMA referência que o pipeline usa
 * pro cursor. Mantém um buffer das amostras `(x, y, t)` dos últimos [SWIPE_MAX_DURATION_MS]; a cada
 * frame compara a amostra atual com a mais antiga AINDA dentro da janela. Um swipe é confirmado
 * quando o deslocamento nessa janela tem um eixo DOMINANTE e travel suficiente:
 *
 * Correção de aspecto 4:3: a imagem é mais larga (x) que alta (y), então o MESMO deslocamento
 * físico gera um `dy` normalizado MAIOR que `dx` na razão 4/3. Para comparar com justiça eu trago
 * tudo pra mesma escala corrigindo o y por [ASPECT_CORRECTION] = 0.75 (=3/4): `dyc = dy * 0.75`.
 * Com isso o critério fica SIMÉTRICO e único nos dois eixos (escolhi "comparar tudo já corrigido"
 * em vez de mexer no threshold do y): dominância exige `|eixo| > DOMINANCE_RATIO * |outro|` e o
 * travel exige `|eixo| >= SWIPE_MIN_TRAVEL` — ambos já sobre valores corrigidos. Isso significa,
 * na prática, exigir o mesmo deslocamento FÍSICO nos dois eixos (raw `|dy| >= 0.16` equivale a
 * `|dx| >= 0.12`).
 *
 * Direções (imagem): dx>0 = [SwipeEvent.RIGHT], dx<0 = [SwipeEvent.LEFT]; y cresce pra baixo, então
 * dy>0 = [SwipeEvent.DOWN], dy<0 = [SwipeEvent.UP].
 *
 * Ao confirmar, emite o evento UMA vez, limpa o buffer e entra em cooldown de [SWIPE_COOLDOWN_MS]
 * — assim um único movimento nunca emite 2 eventos, mas "swipe → volta a mão → swipe de novo"
 * funciona (a volta acontece dentro do cooldown). Perder a palma ([isPalmOpen] → false) ou
 * [reset] limpam buffer e cooldown.
 *
 * Todos os thresholds são estimativa de bancada — VALIDAR/AFINAR EM HARDWARE.
 *
 * Classe PURA (sem Android) — testável em JVM ([PalmSwipeDetectorTest]).
 */
class PalmSwipeDetector {

    companion object {
        // ---- Gate de palma aberta ----
        /** Entra em palma aberta quando o EMA do MÍNIMO dos 4 ratios passa disto. Mão relaxada
         *  típica tem o pior dedo ~1.2-1.4; palma bem aberta ~1.7-2.0. Ajuste de hardware
         *  2026-07-23: 1.55 → 1.65 ("full palm open, not partially" — pedido do usuário). */
        private const val ENTER_THRESHOLD = 1.65f
        /** Sai quando o EMA do mínimo cai abaixo disto (gap de 0.20 pra não oscilar). Afinar. */
        private const val EXIT_THRESHOLD = 1.45f
        /** Gate anti-pinch (ver Javadoc): EMA de `dist3(4,8)/handScale` precisa passar disto pra
         *  ENTRAR em palma. Mão relaxada > 0.6, palma bem aberta ~0.9-1.3, pinch < 0.4. */
        private const val THUMB_FAR_ENTER = 0.70f
        /** Palma SAI se o EMA do polegar cair abaixo disto (começou a pinçar). Gap de 0.20. */
        private const val THUMB_FAR_EXIT = 0.50f
        private const val EMA_ALPHA = 0.5f
        /** Debounce assimétrico (ver Javadoc). Histórico: 5 frames (bancada) → 12 (5ª rodada,
         * anti-falso-positivo) → **8/6 (8ª rodada 2026-07-23, demo em hardware)**: com 12 de
         * entrada + 3 de saída a 60fps, o modo palma ficou DIFÍCIL DE USAR — a saída de 50ms
         * caía com o borrão de movimento do próprio swipe (medido: modo ativo por 121ms) e a
         * re-entrada de 200ms + mão parada engolia a próxima tentativa (3 ativações na demo
         * inteira). 8 frames de entrada (~133ms) mantêm a deliberação; 6 de saída (~100ms)
         * atravessam o glitch de landmarks do swipe rápido sem derrubar o modo. */
        private const val ENTER_DEBOUNCE_FRAMES = 8
        private const val EXIT_DEBOUNCE_FRAMES = 6

        // ---- Gate de mão parada (5ª rodada 2026-07-23, "cursor congela enquanto eu movo") ----
        /** Velocidade máxima do INDEX_MCP (unidades normalizadas do FOV por SEGUNDO) pra ENTRAR
         * em palma aberta: quem está APONTANDO/movendo o cursor com a mão aberta não pode cair
         * no modo mídia (que congela o cursor) — o modo é pra mão aberta deliberada.
         * Mover a mão pelo FOV inteiro em 1s ≈ 1.0/s; deriva de mão "parada" ≈ 0.05-0.15/s.
         * 0.25 → 0.45 (8ª rodada, demo em hardware): o fluxo natural "levanto a mão abrindo a
         * palma e já swipo" chegava com velocidade residual > 0.25 e a entrada era negada —
         * 0.45 aceita esse fluxo e ainda barra o gesto de apontar em movimento pleno. SÓ vale
         * pra entrada — dentro do modo, o swipe é rápido por definição. */
        private const val PALM_ENTER_MAX_SPEED = 0.45f
        private const val SPEED_EMA_ALPHA = 0.5f
        private val FINGERTIPS = intArrayOf(8, 12, 16, 20)
        private const val LM_WRIST = 0
        private const val LM_THUMB_TIP = 4
        private const val LM_INDEX_MCP = 5
        private const val LM_INDEX_TIP = 8

        // ---- Swipe ----
        /** Travel mínimo (já corrigido) pra contar como swipe: ~0.12 do FOV em x. Afinar. */
        private const val SWIPE_MIN_TRAVEL = 0.12f
        /** Janela máxima do gesto: o deslocamento tem que acontecer dentro disto. Afinar. */
        private const val SWIPE_MAX_DURATION_MS = 400L
        /** Silêncio após emitir, pra um movimento só não virar 2 eventos e a volta da mão não
         *  disparar swipe reverso. Afinar. */
        private const val SWIPE_COOLDOWN_MS = 600L
        /** Correção de aspecto 4:3 aplicada ao eixo y (=3/4): traz `dy` pra escala do `dx`. */
        private const val ASPECT_CORRECTION = 0.75f
        /** Fator de dominância do eixo: horizontal exige |dx| > 1.5*|dyc| e vice-versa. Afinar. */
        private const val DOMINANCE_RATIO = 1.5f
    }

    /** Uma amostra da posição rastreada (landmark 5) com o instante em que foi capturada. */
    private data class Sample(val x: Float, val y: Float, val t: Long)

    // Estado do gate de palma (mesma receita EMA+histerese+debounce do PinchDetector/FistDetector,
    // com um segundo EMA pro gate anti-pinch do polegar e um terceiro pra velocidade da mão —
    // ver Javadoc e PALM_ENTER_MAX_SPEED).
    private var ema = Float.NaN
    private var thumbEma = Float.NaN
    private var speedEma = 0f
    private var prevX = Float.NaN
    private var prevY = Float.NaN
    private var prevTimestampMs = 0L
    private var candidate: Boolean? = null
    private var candidateFrames = 0

    // Estado do swipe.
    private val buffer = ArrayDeque<Sample>()
    private var cooldownUntilMs = Long.MIN_VALUE

    var isPalmOpen: Boolean = false
        private set

    /**
     * [landmarks]: 21 pontos (mesma ordem do MediaPipe). [timestampMs]: instante do frame (ms;
     * monotônico). Retorna um [SwipeEvent] SÓ no frame em que o swipe é confirmado; `null` em
     * todos os outros (inclusive durante o debounce da palma e o cooldown).
     */
    fun update(landmarks: List<HandPoint>, timestampMs: Long): SwipeEvent? {
        updatePalmOpen(landmarks, timestampMs)

        // Sem palma aberta não existe swipe: zera o rastreamento e sai.
        if (!isPalmOpen) {
            buffer.clear()
            cooldownUntilMs = Long.MIN_VALUE
            return null
        }

        // Registra a posição atual do landmark 5 e poda o que saiu da janela.
        val mcp = landmarks[LM_INDEX_MCP]
        buffer.addLast(Sample(mcp.x, mcp.y, timestampMs))
        while (buffer.size > 1 && timestampMs - buffer.first().t > SWIPE_MAX_DURATION_MS) {
            buffer.removeFirst()
        }

        // Em cooldown: continua alimentando o buffer (janela fresca), mas não detecta.
        if (timestampMs < cooldownUntilMs) return null

        val oldest = buffer.first()
        val dx = mcp.x - oldest.x
        val dy = mcp.y - oldest.y
        val dyc = dy * ASPECT_CORRECTION // y corrigido pra escala do x (aspecto 4:3)
        val adx = abs(dx)
        val adyc = abs(dyc)

        val event = when {
            adx > DOMINANCE_RATIO * adyc && adx >= SWIPE_MIN_TRAVEL ->
                if (dx > 0f) SwipeEvent.RIGHT else SwipeEvent.LEFT
            adyc > DOMINANCE_RATIO * adx && adyc >= SWIPE_MIN_TRAVEL ->
                if (dy > 0f) SwipeEvent.DOWN else SwipeEvent.UP
            else -> null
        } ?: return null

        // Confirmou: emite uma vez, zera o buffer e entra em cooldown.
        buffer.clear()
        cooldownUntilMs = timestampMs + SWIPE_COOLDOWN_MS
        return event
    }

    /** Atualiza [isPalmOpen] com EMA(min dos 4 ratios) + EMA do polegar (anti-pinch) + gate de
     * mão parada ([PALM_ENTER_MAX_SPEED], só na entrada) + histerese dupla + debounce
     * assimétrico — ver Javadoc. */
    private fun updatePalmOpen(landmarks: List<HandPoint>, timestampMs: Long) {
        val wrist = landmarks[LM_WRIST]
        val handScale = dist3(wrist, landmarks[LM_INDEX_MCP])
        var minRatio = Float.MAX_VALUE
        for (tip in FINGERTIPS) {
            val ratio = dist3(wrist, landmarks[tip]) / handScale
            if (ratio < minRatio) minRatio = ratio
        }
        ema = if (ema.isNaN()) minRatio else EMA_ALPHA * minRatio + (1f - EMA_ALPHA) * ema

        val thumbRatio = dist3(landmarks[LM_THUMB_TIP], landmarks[LM_INDEX_TIP]) / handScale
        thumbEma = if (thumbEma.isNaN()) thumbRatio else EMA_ALPHA * thumbRatio + (1f - EMA_ALPHA) * thumbEma

        // Velocidade da mão (posição normalizada do INDEX_MCP, unidades do FOV por segundo) —
        // ver PALM_ENTER_MAX_SPEED. Primeira amostra/timestamps não-monotônicos: velocidade 0.
        val mcp = landmarks[LM_INDEX_MCP]
        if (!prevX.isNaN() && timestampMs > prevTimestampMs) {
            val dt = (timestampMs - prevTimestampMs) / 1000f
            val dx = mcp.x - prevX
            val dy = mcp.y - prevY
            val speed = sqrt(dx * dx + dy * dy) / dt
            speedEma = SPEED_EMA_ALPHA * speed + (1f - SPEED_EMA_ALPHA) * speedEma
        }
        prevX = mcp.x
        prevY = mcp.y
        prevTimestampMs = timestampMs

        val want = when {
            !isPalmOpen && ema > ENTER_THRESHOLD && thumbEma > THUMB_FAR_ENTER &&
                speedEma < PALM_ENTER_MAX_SPEED -> true
            isPalmOpen && (ema < EXIT_THRESHOLD || thumbEma < THUMB_FAR_EXIT) -> false
            else -> return
        }

        if (candidate != want) {
            candidate = want
            candidateFrames = 0
        }
        candidateFrames++
        val requiredFrames = if (want) ENTER_DEBOUNCE_FRAMES else EXIT_DEBOUNCE_FRAMES
        if (candidateFrames < requiredFrames) return

        isPalmOpen = want
        candidate = null
    }

    /** Limpa TODO o estado — palma, EMAs, velocidade, debounce, buffer de swipe e cooldown
     * (mão sumiu). */
    fun reset() {
        ema = Float.NaN
        thumbEma = Float.NaN
        speedEma = 0f
        prevX = Float.NaN
        prevY = Float.NaN
        prevTimestampMs = 0L
        candidate = null
        candidateFrames = 0
        isPalmOpen = false
        buffer.clear()
        cooldownUntilMs = Long.MIN_VALUE
    }

    private fun dist3(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
