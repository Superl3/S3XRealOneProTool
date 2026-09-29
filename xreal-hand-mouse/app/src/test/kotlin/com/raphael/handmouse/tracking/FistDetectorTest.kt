package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do detector de punho fechado (gesto de recentralização do cursor, 2026-07-23; métrica
 * re-trabalhada na 3ª rodada — ver Javadoc do [FistDetector]): por dedo,
 * `ratio = dist3(punho, ponta)/dist3(punho, PIP)` (pares 8/6, 12/10, 16/14, 20/18 — polegar
 * FORA, a posição dele varia demais num punho); o gate usa o MÁXIMO dos 4 (o dedo mais
 * estendido manda). EMA alpha=0.5, entra quando ema < 0.95, sai quando ema > 1.20 (histerese),
 * debounce ASSIMÉTRICO: entrada em 15 frames (~250ms a 60fps — pose sustentada, imune às
 * transições do pinch), saída em 3. A razão ponta/PIP é invariante ao ângulo da mão
 * (numerador e denominador distorcem juntos na projeção) — o motivo da re-trabalhada.
 *
 * Landmarks sintéticos: lm[0] (punho) na origem, PIPs (6/10/14/18) em (1,0,0) → o ratio de
 * cada dedo é literalmente o x da ponta. Dedo estendido real ≈ 1.3; relaxado ≈ 1.05-1.2;
 * dobrado (punho) ≈ 0.8-1.0.
 */
class FistDetectorTest {

    /** Frame timestamps 60fps apart — the frame counts in these tests are 60fps frames (the
     * detectors are time-based since 2026-09-28, see [FrameTiming]). */
    private var frame = 0
    private fun ts(): Long = frame++ * 1000L / 60

    private fun landmarks(tipRatio: Float, thumbExtended: Boolean = false): List<HandPoint> {
        val zero = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { zero }
        points[0] = HandPoint(0f, 0f, 0f) // WRIST
        points[5] = HandPoint(1f, 0f, 0f) // INDEX_MCP -> handScale = 1
        for (pip in intArrayOf(6, 10, 14, 18)) {
            points[pip] = HandPoint(1f, 0f, 0f) // denominador = 1 -> ratio = x da ponta
        }
        for (tip in intArrayOf(8, 12, 16, 20)) {
            points[tip] = HandPoint(tipRatio, 0f, 0f)
        }
        // Polegar (lm4): recolhido junto dos dedos por default (dist(4,5)=0.1 < 0.85 — punho
        // legítimo); thumbExtended=true afasta pra pose de thumbs-up (dist≈1.8 > 1.0).
        points[4] = if (thumbExtended) HandPoint(0f, -1.5f, 0f) else HandPoint(0.9f, 0f, 0f)
        return points
    }

    /** Um dedo mais estendido que os outros — o MÁXIMO manda (punho exige TODOS dobrados). */
    private fun landmarksIndexExtended(indexRatio: Float, othersRatio: Float): List<HandPoint> {
        val points = landmarks(othersRatio).toMutableList()
        points[8] = HandPoint(indexRatio, 0f, 0f)
        return points
    }

    @Test
    fun `punho fechado confirma apos 15 frames sustentados`() {
        val detector = FistDetector()
        val fist = landmarks(tipRatio = 0.9f)

        repeat(14) { assertFalse("frame ${it + 1} ainda não confirma", detector.update(fist, ts())) }
        assertTrue(detector.update(fist, ts())) // 15º frame consecutivo -> confirma
        assertTrue(detector.isFist)
    }

    @Test
    fun `pose transitoria de punho nao confirma`() {
        val detector = FistDetector()
        // Um mergulho de poucos frames na zona de punho (a "passagem" da formação de um pinch
        // com dedos relaxados) não pode confirmar — o debounce de 15 frames exige sustentação.
        repeat(8) { detector.update(landmarks(0.9f), ts()) }
        assertFalse(detector.isFist)
        // Mão volta a relaxar antes de completar o debounce.
        repeat(5) { detector.update(landmarks(1.3f), ts()) }
        assertFalse(detector.isFist)
    }

    @Test
    fun `mao aberta nunca vira punho`() {
        val detector = FistDetector()
        val open = landmarks(tipRatio = 1.35f)
        repeat(10) { assertFalse(detector.update(open, ts())) }
    }

    @Test
    fun `dedos dobrados com polegar ESTENDIDO nao vira punho (e um thumbs-up)`() {
        // Veto de polegar (gesto de mute, 2026-07-23): os 4 dedos têm assinatura de punho, mas
        // o polegar estendido denuncia um thumbs-up — sem o veto, o gesto de mute dispararia a
        // recentralização aos 2s.
        val detector = FistDetector()
        repeat(25) { assertFalse(detector.update(landmarks(0.9f, thumbExtended = true), ts())) }
    }

    @Test
    fun `punho ativo SOLTA quando o polegar estende (transicao punho para thumbs-up)`() {
        val detector = FistDetector()
        repeat(15) { detector.update(landmarks(0.9f), ts()) }
        assertTrue(detector.isFist)

        // Polegar estende: thumbEma sobe de 0.1 rumo a ~1.8 — cruza o THUMB_TUCKED_EXIT (1.0)
        // no 2º frame (0.5*1.8+0.5*0.1=0.95; depois 1.37) e o debounce de saída (3) confirma.
        // 6 frames dão folga.
        repeat(6) { detector.update(landmarks(0.9f, thumbExtended = true), ts()) }
        assertFalse(detector.isFist)
    }

    @Test
    fun `mao relaxada apontando (indicador semi-estendido) nao vira punho`() {
        val detector = FistDetector()
        // Anular/mindinho/médio dobrados (0.95) mas o indicador semi-estendido (1.25): o gate
        // por MÁXIMO exige TODOS dobrados — a mão de quem só aponta o cursor não pode disparar
        // recentralização.
        repeat(10) { assertFalse(detector.update(landmarksIndexExtended(1.25f, 0.95f), ts())) }
    }

    @Test
    fun `oscilacao dentro da histerese nao alterna o estado`() {
        val detector = FistDetector()
        // Fecha o punho de verdade primeiro.
        repeat(15) { detector.update(landmarks(0.9f), ts()) }
        assertTrue(detector.isFist)

        // Razões entre os dois thresholds (0.95 < r < 1.20): não deve SAIR do punho.
        for (ratio in listOf(1.08f, 1.15f, 1.10f, 1.18f, 1.07f)) {
            detector.update(landmarks(ratio), ts())
            assertTrue("ratio=$ratio não deveria soltar o punho", detector.isFist)
        }
    }

    @Test
    fun `abrir a mao solta o punho apos 3 frames`() {
        val detector = FistDetector()
        repeat(15) { detector.update(landmarks(0.9f), ts()) }
        assertTrue(detector.isFist)

        // 1.6 escolhido pra EMA cruzar o threshold de saída JÁ no 1º frame pós-troca
        // (0.5*1.6 + 0.5*0.9 = 1.25 > 1.20) — assim a confirmação sai em exatamente
        // DEBOUNCE_FRAMES=3 chamadas (mesmo truque do PinchDetectorTest).
        val open = landmarks(1.6f)
        detector.update(open, ts())
        detector.update(open, ts())
        assertTrue(detector.isFist) // 2 de 3 — ainda punho
        detector.update(open, ts())
        assertFalse(detector.isFist)
    }

    @Test
    fun `reset limpa estado e exige debounce completo de novo`() {
        val detector = FistDetector()
        repeat(15) { detector.update(landmarks(0.9f), ts()) }
        assertTrue(detector.isFist)

        detector.reset()

        assertFalse(detector.isFist)
        repeat(14) { assertFalse(detector.update(landmarks(0.9f), ts())) }
        assertTrue(detector.update(landmarks(0.9f), ts()))
    }

    @Test
    fun `a 24fps o punho confirma no mesmo tempo que a 60fps`() {
        // 15 frames at 60fps ≈ 233ms. At 24fps (42ms) the same wall-clock hold is 7 frames
        // (252ms) — frame counting took 15 frames = 625ms.
        val detector = FistDetector()
        repeat(6) { assertFalse(detector.update(landmarks(0.9f), it * 42L)) }
        assertTrue(detector.update(landmarks(0.9f), 6 * 42L))
    }

    @Test
    fun `enterProgress sobe durante o debounce e zera ao abrir a mao`() {
        val detector = FistDetector()
        detector.update(landmarks(0.9f), ts())
        assertEquals(0f, detector.enterProgress, 0.001f)
        repeat(7) { detector.update(landmarks(0.9f), ts()) }
        assertTrue(detector.enterProgress in 0.4f..0.6f)
        repeat(4) { detector.update(landmarks(1.3f), ts()) }
        assertEquals(0f, detector.enterProgress, 0.001f)
    }

    @Test
    fun `curl expoe a metrica suavizada`() {
        val detector = FistDetector()
        detector.update(landmarks(1.3f), ts())
        assertEquals(1.3f, detector.curl, 0.001f)
    }
}
