package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do detector de "swipe de palma aberta" (2026-07-23). Duas responsabilidades:
 *
 * 1. PALMA ABERTA (gate estrito, por dedo + anti-pinch): TODOS os 4 dedos (pontas 8/12/16/20)
 *    precisam estar individualmente estendidos — `dist3(ponta, WRIST=0)/handScale > 1.65` — E o
 *    polegar precisa estar LONGE da ponta do indicador (`dist3(4,8)/handScale > 0.70` pra entrar;
 *    sai se < 0.50) para confirmar palma aberta. A mão relaxada de quem aponta (anular/mindinho
 *    semi-curvados) NÃO pode contar, senão o chamador congela o cursor sem querer; um PINCH com os
 *    outros dedos abertos (polegar↔indicador juntos) também NÃO pode contar — era exatamente o
 *    falso-positivo reportado em hardware (2026-07-23: "palma abre quando tento pinçar").
 *    Métrica normalizada por `handScale = dist3(0, 5)` (mesmo do [PinchDetector]/[FistDetector],
 *    invariante à distância da câmera). EMA alpha=0.5 no MÍNIMO dos 4 ratios + EMA separado do
 *    ratio do polegar, histerese nas duas métricas, debounce assimétrico (entra em 8 frames
 *    ~133ms, sai em 6 ~100ms — 8ª rodada: saída folgada atravessa o glitch de landmarks do
 *    próprio swipe) E gate de mão parada na entrada (speedEma < 0.45 FOV/s — mão aberta em
 *    MOVIMENTO PLENO é alguém apontando o cursor, não pedindo modo mídia).
 *
 * 2. SWIPE (só com palma aberta): rastreia a posição do landmark 5 (mesma referência do cursor).
 *    Deslocamento >= 0.12 do FOV em x dentro de <= 400ms, com eixo dominante (correção de aspecto
 *    4:3: dy é corrigido por ×0.75 pra comparação justa com dx). Cooldown de 600ms após emitir.
 *
 * Landmarks sintéticos (mesma ideia do [FistDetectorTest]): WRIST (lm0) em (palmX, palmY, 0),
 * lm5 em (palmX+1, palmY, 0) → handScale = 1 sempre; cada ponta em (palmX+ratio, palmY, 0) →
 * `dist3(ponta, wrist) = ratio`. O polegar (lm4) fica NO punho por default → distância
 * polegar↔indicador = ratio8 (longe, palma legítima); [thumbNearIndex] o coloca a 0.2 da ponta
 * do indicador (pose de pinch). Movo palmX/palmY pra gerar swipe — lm0 e lm5 andam JUNTOS, então
 * handScale continua 1 e os ratios de palma não mudam (só a posição rastreada do lm5).
 */
class PalmSwipeDetectorTest {

    /** Constrói os 21 landmarks com ratio individual por dedo e deslocamento do punho. */
    private fun landmarks(
        ratio8: Float,
        ratio12: Float,
        ratio16: Float,
        ratio20: Float,
        palmX: Float = 0f,
        palmY: Float = 0f,
        thumbNearIndex: Boolean = false,
    ): List<HandPoint> {
        val zero = HandPoint(palmX, palmY, 0f)
        val points = MutableList(21) { zero }
        points[0] = HandPoint(palmX, palmY, 0f)          // WRIST
        points[5] = HandPoint(palmX + 1f, palmY, 0f)     // INDEX_MCP -> handScale = 1
        points[8] = HandPoint(palmX + ratio8, palmY, 0f) // INDEX_TIP
        points[12] = HandPoint(palmX + ratio12, palmY, 0f) // MIDDLE_TIP
        points[16] = HandPoint(palmX + ratio16, palmY, 0f) // RING_TIP
        points[20] = HandPoint(palmX + ratio20, palmY, 0f) // PINKY_TIP
        // THUMB_TIP (lm4): default no punho (longe da ponta do indicador = palma legítima);
        // thumbNearIndex=true encosta a 0.2 da ponta do indicador (pose de pinch).
        points[4] = if (thumbNearIndex) {
            HandPoint(palmX + ratio8 - 0.2f, palmY, 0f)
        } else {
            HandPoint(palmX, palmY, 0f)
        }
        return points
    }

    /** Palma totalmente aberta: os 4 dedos no mesmo ratio (bem acima do ENTER pra EMA cruzar já). */
    private fun openHand(palmX: Float = 0f, palmY: Float = 0f, ratio: Float = 1.9f) =
        landmarks(ratio, ratio, ratio, ratio, palmX, palmY)

    // ---- 1. PALMA ABERTA -------------------------------------------------------------------

    @Test
    fun `palma aberta parada confirma apos 8 frames`() {
        val d = PalmSwipeDetector()
        assertFalse(d.isPalmOpen)
        repeat(7) { d.update(openHand(), it * 16L) }
        assertFalse("7 frames não bastam", d.isPalmOpen)
        d.update(openHand(), 7 * 16L)
        assertTrue("8º frame consecutivo confirma", d.isPalmOpen)
    }

    @Test
    fun `mao aberta em MOVIMENTO nao entra em modo palma`() {
        val d = PalmSwipeDetector()
        // Mão totalmente aberta mas transladando rápido (movendo o cursor): 0.05 do FOV por
        // frame de 16ms ≈ 3.1 unidades/s >> PALM_ENTER_MAX_SPEED (0.25). O modo palma congela
        // o cursor — NUNCA pode engatar no meio de um movimento de apontar (5ª rodada).
        repeat(25) { d.update(openHand(palmX = 0.05f * it), it * 16L) }
        assertFalse(d.isPalmOpen)
    }

    @Test
    fun `pinch com os outros dedos estendidos nao abre palma`() {
        val d = PalmSwipeDetector()
        // Polegar encostado na ponta do indicador (dist 0.2 < THUMB_FAR_ENTER 0.70) com os 4
        // dedos "estendidos" do ponto de vista do ratio ponta↔punho — a pose real de um pinch
        // com a mão espalmada. NUNCA pode virar palma aberta, por mais frames que passem.
        repeat(15) { d.update(landmarks(1.9f, 1.9f, 1.9f, 1.9f, thumbNearIndex = true), it * 16L) }
        assertFalse(d.isPalmOpen)
    }

    @Test
    fun `polegar aproximando do indicador derruba a palma aberta`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        // Palma confirmada; o usuário começa a pinçar (polegar vai até a ponta do indicador).
        // O EMA do ratio do polegar cai de 1.9 até cruzar THUMB_FAR_EXIT 0.50; debounce de
        // saída = 6 frames (8ª rodada) confirma o fechamento. 10 frames dão folga pra
        // EMA + debounce.
        repeat(10) { d.update(landmarks(1.9f, 1.9f, 1.9f, 1.9f, thumbNearIndex = true), t + it * 16L) }
        assertFalse("pinçar durante a palma aberta precisa sair do modo palma", d.isPalmOpen)
    }

    @Test
    fun `mao relaxada com anular e mindinho curvados nao vira palma`() {
        val d = PalmSwipeDetector()
        // Indicador (8) e médio (12) estendidos, anular (16) e mindinho (20) semi-curvados (1.2).
        // min dos 4 = 1.2 < ENTER 1.65 -> nunca confirma, por mais frames que passem.
        repeat(10) { d.update(landmarks(1.9f, 1.9f, 1.2f, 1.2f), it * 16L) }
        assertFalse(d.isPalmOpen)
    }

    @Test
    fun `curvar um unico dedo solta a palma apos 6 frames`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)

        // Curva só o mindinho pra 0.7 -> min ratio = 0.7. EMA parte de ~1.9:
        // 0.5*0.7 + 0.5*1.9 = 1.3 < EXIT 1.45 -> cruza o EXIT já no 1º frame e a confirmação
        // (debounce de saída = 6 frames, 8ª rodada) sai em exatamente 6 frames.
        val bent = landmarks(1.9f, 1.9f, 1.9f, 0.7f)
        for (i in 0 until 5) d.update(bent, t + i * 16L)
        assertTrue("5 de 6 — ainda aberta", d.isPalmOpen)
        d.update(bent, t + 5 * 16L)
        assertFalse(d.isPalmOpen)
    }

    // ---- 2. SWIPE --------------------------------------------------------------------------

    /** Abre a palma (8 frames parados em palmX/palmY) e devolve o timestamp do próximo frame. */
    private fun confirmPalmOpen(d: PalmSwipeDetector, palmX: Float = 0f, palmY: Float = 0f): Long {
        repeat(8) { d.update(openHand(palmX, palmY), it * 16L) }
        assertTrue(d.isPalmOpen)
        return 8 * 16L // 128
    }

    @Test
    fun `swipe horizontal para a direita emite RIGHT`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d) // baseline em palmX=0 nos t=0,16,32
        // Move o punho +0.15 em x até t=48 (dentro dos 400ms de janela) -> dx=0.15 >= 0.12.
        assertEquals(SwipeEvent.RIGHT, d.update(openHand(palmX = 0.15f), t))
    }

    @Test
    fun `swipe horizontal para a esquerda emite LEFT`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        assertEquals(SwipeEvent.LEFT, d.update(openHand(palmX = -0.15f), t))
    }

    @Test
    fun `deslocamento lento na mesma distancia nao emite`() {
        val d = PalmSwipeDetector()
        val t0 = confirmPalmOpen(d)
        // 0.12 total, mas espalhado em 1000ms: em qualquer janela de 400ms o deslocamento é
        // ~0.048 < 0.12. 21 amostras (passo 50ms), palmX linear de 0 a 0.12.
        var emitted: SwipeEvent? = null
        for (i in 0..20) {
            val t = t0 + i * 50L
            val x = 0.12f * i / 20f
            val e = d.update(openHand(palmX = x), t)
            if (e != null) emitted = e
        }
        assertNull("movimento lento não é swipe", emitted)
    }

    @Test
    fun `movimento diagonal sem eixo dominante nao emite`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        // dx=0.15, dy=0.15 -> dyc=0.1125. Nem |dx|>1.5*|dyc| (0.15 !> 0.169) nem o inverso.
        assertNull(d.update(openHand(palmX = 0.15f, palmY = 0.15f), t))
    }

    @Test
    fun `swipe vertical para cima emite UP`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        // dy=-0.20 -> dyc=-0.15 (>= 0.12 corrigido). dx=0 -> eixo vertical dominante. y cresce
        // pra baixo, então dy<0 = UP.
        assertEquals(SwipeEvent.UP, d.update(openHand(palmY = -0.20f), t))
    }

    @Test
    fun `swipe vertical para baixo emite DOWN`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        assertEquals(SwipeEvent.DOWN, d.update(openHand(palmY = 0.20f), t))
    }

    @Test
    fun `palma fechada nunca emite swipe`() {
        val d = PalmSwipeDetector()
        // Mão fechada (ratios 0.9) movendo bastante em x: isPalmOpen nunca vira true -> sem swipe.
        var emitted: SwipeEvent? = null
        for (i in 0..10) {
            val e = d.update(landmarks(0.9f, 0.9f, 0.9f, 0.9f, palmX = 0.03f * i), i * 16L)
            if (e != null) emitted = e
        }
        assertNull(emitted)
        assertFalse(d.isPalmOpen)
    }

    @Test
    fun `segundo swipe imediato nao emite mas apos cooldown emite`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        assertEquals(SwipeEvent.RIGHT, d.update(openHand(palmX = 0.15f), t)) // t=128, cooldown até 728

        // Tentativa imediata dentro do cooldown: mão volta e refaz o gesto -> NÃO emite.
        var duringCooldown: SwipeEvent? = null
        for (i in 1..8) {
            val tt = t + i * 20L // 148..288, tudo < 728
            val x = if (i % 2 == 0) 0f else 0.15f
            val e = d.update(openHand(palmX = x), tt)
            if (e != null) duringCooldown = e
        }
        assertNull("nada emite durante o cooldown", duringCooldown)

        // Passado o cooldown: baseline parado + swipe limpo -> emite de novo.
        d.update(openHand(palmX = 0f), 800)
        d.update(openHand(palmX = 0f), 816)
        assertEquals(SwipeEvent.RIGHT, d.update(openHand(palmX = 0.15f), 832))
    }

    @Test
    fun `reset limpa palma buffer e cooldown`() {
        val d = PalmSwipeDetector()
        val t = confirmPalmOpen(d)
        d.update(openHand(palmX = 0.15f), t) // emite e entra em cooldown

        d.reset()
        assertFalse(d.isPalmOpen)

        // Pós-reset precisa reconfirmar a palma do zero (8 frames) antes de qualquer swipe.
        assertNull(d.update(openHand(palmX = 0f), 0))
        assertFalse(d.isPalmOpen)
        for (i in 1..7) d.update(openHand(palmX = 0f), i * 16L)
        assertTrue(d.isPalmOpen)
        // E o buffer/cooldown estão limpos: um swipe limpo volta a emitir.
        assertEquals(SwipeEvent.RIGHT, d.update(openHand(palmX = 0.15f), 128))
    }
}
