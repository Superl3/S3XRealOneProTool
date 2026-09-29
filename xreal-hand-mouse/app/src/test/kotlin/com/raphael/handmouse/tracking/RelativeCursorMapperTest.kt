package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Modo relativo (trackpad): o cursor se move pelos DELTAS da posição normalizada da mão × ganho,
 * sem mapeamento absoluto nem calibração. Contratos principais:
 * - 1º sample: cursor nasce no CENTRO da tela (não há âncora anterior — delta indefinido).
 * - Deltas seguintes: dx = dnx * (width/SPAN_X); dy = dny * (width/SPAN_X) — a entrada já é
 *   isotrópica (y em unidades de largura, [HandTracker.Result.isoPoints]), então o mesmo ganho
 *   vale nos dois eixos (antes: constante 0.75 de câmera 4:3, errada pro 16:9 da Eye).
 * - Clamp nos limites da tela; empurrar "além da borda" não acumula (voltar responde na hora).
 * - [RelativeCursorMapper.onHandLost]: solta a âncora — a mão reaparecendo em QUALQUER lugar
 *   não teleporta o cursor (re-ancoragem), que continua de onde estava.
 */
class RelativeCursorMapperTest {

    /** Frame timestamps 60fps apart — the frame counts in these tests are 60fps frames (the
     * detectors are time-based since 2026-09-28, see [FrameTiming]). */
    private var frame = 0
    private fun ts(): Long = frame++ * 1000L / 60

    private val bounds = DisplayBounds(width = 1920, height = 1080)

    @Test
    fun `primeiro sample posiciona o cursor no centro da tela`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        val p = mapper.map(0.7f, 0.9f, bounds, ts())
        assertEquals(960f, p.x, 0.001f)
        assertEquals(540f, p.y, 0.001f)
    }

    @Test
    fun `delta horizontal move o cursor proporcional ao ganho`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts()) // ancora no centro
        // dnx = 0.1; ganho = 1920/0.4 = 4800 px/unidade → dx = 480
        val p = mapper.map(0.6f, 0.5f, bounds, ts())
        assertEquals(960f + 480f, p.x, 0.001f)
        assertEquals(540f, p.y, 0.001f)
    }

    @Test
    fun `delta vertical usa o mesmo ganho do horizontal (entrada isotropica)`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts())
        // dny = 0.1 (isotrópico) → dy = 0.1 * 4800 = 480, igual ao dx do mesmo deslocamento
        val p = mapper.map(0.5f, 0.6f, bounds, ts())
        assertEquals(960f, p.x, 0.001f)
        assertEquals(540f + 480f, p.y, 0.001f)
    }

    @Test
    fun `modo precisao depende da velocidade real, nao dos px por frame`() {
        // Same hand speed at 60fps and at 24fps (thermal): 2.4px per 60fps frame = 144px/s.
        // At 24fps that is 6px per frame, which the old per-frame threshold (4px) read as fast.
        val slow60 = RelativeCursorMapper(spanX = 0.4f)
        slow60.map(0.5f, 0.5f, bounds, 0L)
        val p60 = slow60.map(0.5005f, 0.5f, bounds, 17L) // 2.4px in 17ms
        val slow24 = RelativeCursorMapper(spanX = 0.4f)
        slow24.map(0.5f, 0.5f, bounds, 0L)
        val p24 = slow24.map(0.50125f, 0.5f, bounds, 42L) // 6px in 42ms
        assertEquals(0.25f, (p60.x - 960f) / 2.4f, 0.01f)
        assertEquals(0.25f, (p24.x - 960f) / 6f, 0.01f)
    }

    @Test
    fun `cursor eh clampado nas bordas e nao acumula alem delas`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts())
        // Empurra MUITO além da borda direita (dnx=0.5 → dx=2400 >> 960 restantes).
        val atEdge = mapper.map(1.0f, 0.5f, bounds, ts())
        assertEquals(1919f, atEdge.x, 0.001f)
        // Volta um pouco (dnx=-0.1 → dx=-480): responde IMEDIATAMENTE a partir da borda,
        // sem precisar "desfazer" o excesso que foi clampado.
        val back = mapper.map(0.9f, 0.5f, bounds, ts())
        assertEquals(1919f - 480f, back.x, 0.001f)
    }

    @Test
    fun `onHandLost re-ancora sem teleportar o cursor`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts())
        val before = mapper.map(0.6f, 0.5f, bounds, ts()) // cursor em 1440, 540
        mapper.onHandLost()
        // Mão reaparece num lugar completamente diferente do FOV: cursor NÃO pula.
        val after = mapper.map(0.1f, 0.9f, bounds, ts())
        assertEquals(before.x, after.x, 0.001f)
        assertEquals(before.y, after.y, 0.001f)
        // E o próximo delta parte da nova âncora normalmente (0.05 * 4800 = 240px, sem
        // encostar no clamp da borda direita).
        val moved = mapper.map(0.15f, 0.9f, bounds, ts())
        assertEquals(before.x + 240f, moved.x, 0.001f)
    }

    @Test
    fun `movimento lento entra no modo precisao (ganho reduzido)`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts()) // âncora no centro
        // Delta minúsculo: raw = 0.0005 * 4800 = 2.4px/frame < LOW (4px) → fator mínimo 0.25.
        val p = mapper.map(0.5005f, 0.5f, bounds, ts())
        assertEquals(960f + 2.4f * 0.25f, p.x, 0.01f)
    }

    @Test
    fun `movimento rapido usa o ganho cheio`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts())
        // raw = 0.01 * 4800 = 48px/frame > HIGH (18px) → fator 1.0 (ganho cheio).
        val p = mapper.map(0.51f, 0.5f, bounds, ts())
        assertEquals(960f + 48f, p.x, 0.01f)
    }

    @Test
    fun `recenter volta o cursor pro centro e re-ancora sem salto`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts())
        mapper.map(0.6f, 0.7f, bounds, ts()) // cursor longe do centro

        mapper.recenter()

        // Próximo sample (mão em qualquer lugar): cursor renasce no CENTRO.
        val p = mapper.map(0.8f, 0.2f, bounds, ts())
        assertEquals(960f, p.x, 0.001f)
        assertEquals(540f, p.y, 0.001f)
        // E os deltas seguintes partem da nova âncora normalmente.
        val moved = mapper.map(0.85f, 0.2f, bounds, ts())
        assertEquals(960f + 240f, moved.x, 0.001f)
    }

    @Test
    fun `mudanca de bounds mantem o cursor clampado no display novo`() {
        val mapper = RelativeCursorMapper(spanX = 0.4f)
        mapper.map(0.5f, 0.5f, bounds, ts())
        mapper.map(1.0f, 0.5f, bounds, ts()) // cursor na borda direita (1919)
        // Display encolheu (ex.: 1920 → 1280): próximo sample clampa pro novo limite.
        val smaller = DisplayBounds(width = 1280, height = 720)
        val p = mapper.map(1.0f, 0.5f, smaller, ts()) // dnx=0 (mesma âncora) — só o clamp age
        assertEquals(1279f, p.x, 0.001f)
        assertEquals(540f, p.y, 0.001f) // dentro de 720? 540 < 719 → mantém
    }
}
