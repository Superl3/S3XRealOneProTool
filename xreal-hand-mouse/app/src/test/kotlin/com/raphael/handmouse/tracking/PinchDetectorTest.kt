package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do detector de pinch (PLANO.md §6.3, brief Tarefa 3): razão dist3(4,8)/dist3(0,5),
 * EMA alpha=0.5, entra em pinch quando ema<0.28, sai quando ema>0.38 (histerese dupla),
 * debounce assimétrico (DOWN: 3 frames consecutivos; UP: 2 — liberação mais rápida, ajuste de
 * hardware 2026-07-22) antes de confirmar a transição.
 *
 * Landmarks sintéticos (21 pontos, só os 4 usados pelo algoritmo importam: 0, 4, 5, 8):
 * lm[0] e lm[5] fixam a "escala da mão" (dist3 = handScale); lm[4] e lm[8] fixam a distância
 * polegar-indicador (dist3 = pinchDist). ratio = pinchDist / handScale.
 */
class PinchDetectorTest {

    private fun landmarks(ratio: Float, handScale: Float = 1f): List<HandPoint> {
        val zero = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { zero }
        points[0] = HandPoint(0f, 0f, 0f) // WRIST
        points[5] = HandPoint(handScale, 0f, 0f) // INDEX_MCP -> dist3(0,5) = handScale
        points[4] = HandPoint(0f, 0f, 0f) // THUMB_TIP
        points[8] = HandPoint(ratio * handScale, 0f, 0f) // INDEX_TIP -> dist3(4,8) = ratio*handScale
        return points
    }

    @Test
    fun `abre para fecha dispara DOWN apos exatamente 3 frames abaixo do threshold`() {
        val detector = PinchDetector()
        val lm = landmarks(ratio = 0.10f) // bem abaixo de 0.28

        assertNull(detector.update(lm))
        assertFalse(detector.isPinched)
        assertNull(detector.update(lm))
        assertFalse(detector.isPinched)

        val event = detector.update(lm) // 3o frame consecutivo -> confirma
        assertEquals(PinchEvent.DOWN, event)
        assertTrue(detector.isPinched)
    }

    @Test
    fun `oscilacao entre 0-28 e 0-50 nao dispara nada (histerese)`() {
        val detector = PinchDetector()
        // ratios estritamente entre os dois thresholds: nunca entra (nao pinçado, ema nao
        // fica abaixo de 0.28) nem sai (ja que nunca entrou, isPinched permanece false).
        val ratios = listOf(0.30f, 0.45f, 0.32f, 0.48f, 0.35f, 0.40f, 0.30f, 0.45f)

        for (ratio in ratios) {
            val event = detector.update(landmarks(ratio))
            assertNull("ratio=$ratio nao deveria gerar evento", event)
            assertFalse(detector.isPinched)
        }
    }

    @Test
    fun `fecha para abre dispara UP apos debounce`() {
        val detector = PinchDetector()
        val closed = landmarks(ratio = 0.10f)
        // fecha (DOWN confirmado)
        repeat(2) { detector.update(closed) }
        assertEquals(PinchEvent.DOWN, detector.update(closed))
        assertTrue(detector.isPinched)

        // ratio bem acima de 0.38: a EMA cruza o threshold de saida ja no 1o frame pos-troca
        // (0.5*0.95 + 0.5*0.10 = 0.525 > 0.38), garantindo a confirmação em exatamente
        // UP_DEBOUNCE_FRAMES=2 chamadas — liberação mais rápida (ajuste de hardware 2026-07-22).
        val open = landmarks(ratio = 0.95f)
        assertNull(detector.update(open)) // 1o frame: candidato, ainda nao confirma (UP debounce=2)
        assertTrue(detector.isPinched)

        val event = detector.update(open) // 2o frame -> confirma saida
        assertEquals(PinchEvent.UP, event)
        assertFalse(detector.isPinched)
    }

    @Test
    fun `frames insuficientes nao disparam DOWN (debounce nao completo)`() {
        val detector = PinchDetector()
        val closed = landmarks(ratio = 0.10f)

        assertNull(detector.update(closed))
        assertNull(detector.update(closed)) // só 2 de 3 frames necessários

        assertFalse(detector.isPinched)
    }

    @Test
    fun `reset limpa ema candidato e isPinched`() {
        val detector = PinchDetector()
        val closed = landmarks(ratio = 0.10f)
        repeat(3) { detector.update(closed) }
        assertTrue(detector.isPinched)

        detector.reset()

        assertFalse(detector.isPinched)
        // apos reset, precisa de 3 frames novamente para confirmar (EMA reiniciada do zero)
        assertNull(detector.update(closed))
        assertNull(detector.update(closed))
        assertEquals(PinchEvent.DOWN, detector.update(closed))
    }
}
