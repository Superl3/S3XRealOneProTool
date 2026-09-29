package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do detector de sinal de "V" (ativação do controle por voz, 2026-07-23 — spec
 * `2026-07-23-voice-control-design.md`): indicador+médio ESTENDIDOS (min dos ratios ponta/PIP
 * > 1.25) e anular+mindinho DOBRADOS (max dos ratios < 1.05), mesma métrica ponta/PIP invariante
 * a ângulo do FistDetector. EMA alpha 0.5, histerese dupla, debounce assimétrico 12/3 frames.
 *
 * Landmarks sintéticos: punho na origem, PIPs (6/10/14/18) e INDEX_MCP (5) em (1,0,0) → o ratio
 * de cada dedo é o x da própria ponta.
 */
class VSignDetectorTest {

    /** Frame timestamps 60fps apart — the frame counts in these tests are 60fps frames (the
     * detectors are time-based since 2026-09-28, see [FrameTiming]). */
    private var frame = 0
    private fun ts(): Long = frame++ * 1000L / 60

    private fun landmarks(
        indexRatio: Float,
        middleRatio: Float = indexRatio,
        ringRatio: Float,
        pinkyRatio: Float = ringRatio,
    ): List<HandPoint> {
        val zero = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { zero }
        points[0] = HandPoint(0f, 0f, 0f) // WRIST
        points[5] = HandPoint(1f, 0f, 0f) // INDEX_MCP
        for (pip in intArrayOf(6, 10, 14, 18)) points[pip] = HandPoint(1f, 0f, 0f)
        points[8] = HandPoint(indexRatio, 0f, 0f)
        points[12] = HandPoint(middleRatio, 0f, 0f)
        points[16] = HandPoint(ringRatio, 0f, 0f)
        points[20] = HandPoint(pinkyRatio, 0f, 0f)
        return points
    }

    private fun vSign() = landmarks(indexRatio = 1.35f, ringRatio = 0.9f)

    @Test
    fun `sinal de V confirma apos 12 frames sustentados`() {
        val detector = VSignDetector()
        repeat(11) { assertFalse("frame ${it + 1} ainda não confirma", detector.update(vSign(), ts())) }
        assertTrue(detector.update(vSign(), ts()))
        assertTrue(detector.isVSign)
    }

    @Test
    fun `pose transitoria nao confirma`() {
        val detector = VSignDetector()
        repeat(6) { detector.update(vSign(), ts()) }
        assertFalse(detector.isVSign)
        repeat(5) { detector.update(landmarks(indexRatio = 1.35f, ringRatio = 1.35f), ts()) }
        assertFalse(detector.isVSign)
    }

    @Test
    fun `palma aberta (todos estendidos) nunca vira V`() {
        val detector = VSignDetector()
        val palm = landmarks(indexRatio = 1.35f, ringRatio = 1.35f)
        repeat(20) { assertFalse(detector.update(palm, ts())) }
    }

    @Test
    fun `punho (todos dobrados) nunca vira V`() {
        val detector = VSignDetector()
        val fist = landmarks(indexRatio = 0.9f, ringRatio = 0.9f)
        repeat(20) { assertFalse(detector.update(fist, ts())) }
    }

    @Test
    fun `so indicador estendido (apontar) nao vira V`() {
        // A mão de quem aponta o cursor: médio semi-dobrado — o MIN(indicador, médio) manda.
        val detector = VSignDetector()
        val pointing = landmarks(indexRatio = 1.35f, middleRatio = 1.05f, ringRatio = 0.95f)
        repeat(20) { assertFalse(detector.update(pointing, ts())) }
    }

    @Test
    fun `oscilacao dentro da histerese nao alterna o estado`() {
        val detector = VSignDetector()
        repeat(12) { detector.update(vSign(), ts()) }
        assertTrue(detector.isVSign)
        // Estendidos caem até a zona morta (1.10 < ext < 1.25) — não deve soltar.
        for (ext in listOf(1.20f, 1.15f, 1.22f, 1.12f)) {
            detector.update(landmarks(indexRatio = ext, ringRatio = 0.9f), ts())
            assertTrue("ext=$ext não deveria soltar o V", detector.isVSign)
        }
    }

    @Test
    fun `dobrar o medio solta o V apos 3 frames`() {
        val detector = VSignDetector()
        repeat(12) { detector.update(vSign(), ts()) }
        assertTrue(detector.isVSign)
        // 0.5 derruba o EMA do min(estendidos) abaixo de 1.10 já no 1º frame
        // (0.5*0.5 + 0.5*1.35 = 0.925) — confirmação em exatamente 3 chamadas.
        val dropped = landmarks(indexRatio = 1.35f, middleRatio = 0.5f, ringRatio = 0.9f)
        detector.update(dropped, ts())
        detector.update(dropped, ts())
        assertTrue(detector.isVSign)
        detector.update(dropped, ts())
        assertFalse(detector.isVSign)
    }

    @Test
    fun `reset limpa estado e exige debounce completo de novo`() {
        val detector = VSignDetector()
        repeat(12) { detector.update(vSign(), ts()) }
        assertTrue(detector.isVSign)
        detector.reset()
        assertFalse(detector.isVSign)
        repeat(11) { assertFalse(detector.update(vSign(), ts())) }
        assertTrue(detector.update(vSign(), ts()))
    }
}
