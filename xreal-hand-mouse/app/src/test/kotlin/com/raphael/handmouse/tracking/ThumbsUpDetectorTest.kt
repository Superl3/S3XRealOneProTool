package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do detector de THUMBS-UP (gesto de mute do cursor, 2026-07-23 — ver spec
 * `2026-07-23-cursor-mute-gesture-design.md`). Pose = TRÊS condições simultâneas, cada uma com
 * EMA+histerese:
 * 1. 4 dedos dobrados: max(dist3(punho,ponta)/dist3(punho,PIP)) < 1.05 (sai > 1.20) — mesma
 *    métrica invariante a ângulo do [FistDetector];
 * 2. polegar ESTENDIDO: dist3(4,5)/handScale > 0.85 (sai < 0.65) — separa do punho (polegar
 *    junto dos dedos);
 * 3. polegar pra CIMA: (wrist.y - thumb.y)/handScale > 0.5 (sai < 0.3) — rejeita thumbs-down
 *    e polegar caído de lado. y cresce PRA BAIXO na imagem.
 * Debounce assimétrico: entra em 8 frames, sai em 3.
 *
 * Landmarks sintéticos (mesma receita do [FistDetectorTest]): punho na origem, lm5=(1,0,0) →
 * handScale=1; PIPs (6/10/14/18) em (1,0,0) → ratio de cada dedo = x da ponta; polegar (lm4)
 * posicionado por caso de teste.
 */
class ThumbsUpDetectorTest {

    private fun landmarks(fingerRatio: Float, thumb: HandPoint): List<HandPoint> {
        val zero = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { zero }
        points[0] = HandPoint(0f, 0f, 0f) // WRIST
        points[5] = HandPoint(1f, 0f, 0f) // INDEX_MCP -> handScale = 1
        for (pip in intArrayOf(6, 10, 14, 18)) points[pip] = HandPoint(1f, 0f, 0f)
        for (tip in intArrayOf(8, 12, 16, 20)) points[tip] = HandPoint(fingerRatio, 0f, 0f)
        points[4] = thumb
        return points
    }

    /** Thumbs-up canônico: dedos dobrados (0.9) + polegar bem acima do punho.
     * dist(4,5)=sqrt(1+2.25)≈1.8 > 0.85; up=(0-(-1.5))/1=1.5 > 0.5. */
    private fun thumbsUp() = landmarks(0.9f, HandPoint(0f, -1.5f, 0f))

    /** Punho clássico: dedos dobrados + polegar recolhido junto deles.
     * dist(4,5)=0.1 < 0.65; up=0 < 0.3. */
    private fun fist() = landmarks(0.9f, HandPoint(0.9f, 0f, 0f))

    @Test
    fun `thumbs-up confirma apos 8 frames sustentados`() {
        val d = ThumbsUpDetector()
        repeat(7) { assertFalse("frame ${it + 1} ainda não confirma", d.update(thumbsUp())) }
        assertTrue(d.update(thumbsUp())) // 8º frame -> confirma
        assertTrue(d.isActive)
    }

    @Test
    fun `punho com polegar recolhido nunca vira thumbs-up`() {
        val d = ThumbsUpDetector()
        repeat(20) { assertFalse(d.update(fist())) }
    }

    @Test
    fun `mao aberta com polegar pra cima nao ativa (dedos nao dobrados)`() {
        val d = ThumbsUpDetector()
        repeat(20) { assertFalse(d.update(landmarks(1.35f, HandPoint(0f, -1.5f, 0f)))) }
    }

    @Test
    fun `thumbs-DOWN nao ativa (polegar abaixo do punho)`() {
        // dist(4,5) ok (≈1.8) mas up = (0 - 1.5)/1 = -1.5 < 0.5 — y cresce pra baixo.
        val d = ThumbsUpDetector()
        repeat(20) { assertFalse(d.update(landmarks(0.9f, HandPoint(0f, 1.5f, 0f)))) }
    }

    @Test
    fun `polegar estendido DE LADO nao ativa (sem componente pra cima)`() {
        // dist(4,5)=2.5 > 0.85, mas up=0 < 0.5 — carona/hitchhiker lateral não é o gesto.
        val d = ThumbsUpDetector()
        repeat(20) { assertFalse(d.update(landmarks(0.9f, HandPoint(-1.5f, 0f, 0f)))) }
    }

    @Test
    fun `abrir a mao solta o thumbs-up apos 3 frames`() {
        val d = ThumbsUpDetector()
        repeat(8) { d.update(thumbsUp()) }
        assertTrue(d.isActive)

        // Mão abre (dedos 1.6): curlEma 0.9 -> 0.5*1.6+0.5*0.9 = 1.25 > EXIT 1.20 já no 1º
        // frame; debounce de saída = 3.
        val open = landmarks(1.6f, HandPoint(0f, -1.5f, 0f))
        d.update(open)
        d.update(open)
        assertTrue("2 de 3 — ainda ativo", d.isActive)
        d.update(open)
        assertFalse(d.isActive)
    }

    @Test
    fun `recolher o polegar (virar punho) tambem solta o thumbs-up`() {
        val d = ThumbsUpDetector()
        repeat(8) { d.update(thumbsUp()) }
        assertTrue(d.isActive)

        // Polegar recolhe: thumbEma cai de ~1.8 e upEma de 1.5; a condição de saída dispara
        // quando qualquer métrica cruza o exit. 6 frames dão folga pra EMA + debounce de 3.
        repeat(6) { d.update(fist()) }
        assertFalse(d.isActive)
    }

    @Test
    fun `reset limpa estado e exige debounce completo de novo`() {
        val d = ThumbsUpDetector()
        repeat(8) { d.update(thumbsUp()) }
        assertTrue(d.isActive)

        d.reset()

        assertFalse(d.isActive)
        repeat(7) { assertFalse(d.update(thumbsUp())) }
        assertTrue(d.update(thumbsUp()))
    }
}
