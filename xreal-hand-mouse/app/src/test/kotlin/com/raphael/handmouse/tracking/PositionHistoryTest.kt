package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TDD do buffer circular de posições (PLANO.md §6.3-nota, brief Tarefa 5): usado pra "congelar"
 * o cursor na posição de ~100ms atrás no pinch-down (o ato de pinçar desloca a ponta do
 * indicador — solução HoloLens/Vision Pro). Janela de TEMPO (600ms por padrão, 2026-09-28 — era
 * 12 amostras); [PositionHistory.positionAt] busca o ponto mais próximo do timestamp alvo (não
 * uma interpolação); [PositionHistory.fistOnset] acha onde os dedos começaram a fechar.
 */
class PositionHistoryTest {

    @Test
    fun `buffer vazio retorna null`() {
        val history = PositionHistory()

        assertNull(history.positionAt(1000L))
    }

    @Test
    fun `um unico ponto e retornado para qualquer timestamp alvo`() {
        val history = PositionHistory()
        history.record(10f, 20f, 500L)

        val muitoAntes = history.positionAt(0L)
        val muitoDepois = history.positionAt(10_000L)

        assertEquals(10f, muitoAntes?.x)
        assertEquals(20f, muitoAntes?.y)
        assertEquals(500L, muitoAntes?.timestampMs)
        assertEquals(10f, muitoDepois?.x)
        assertEquals(20f, muitoDepois?.y)
    }

    @Test
    fun `busca o ponto mais proximo do timestamp alvo`() {
        val history = PositionHistory()
        history.record(0f, 0f, 0L)
        history.record(1f, 1f, 50L)
        history.record(2f, 2f, 100L)
        history.record(3f, 3f, 150L)
        history.record(4f, 4f, 200L)

        // alvo 110 -> mais perto de 100 (diff 10) do que de 150 (diff 40)
        val proximoDe110 = history.positionAt(110L)
        assertEquals(100L, proximoDe110?.timestampMs)

        // alvo 175 -> exatamente no meio de 150 e 200; minByOrNull mantém o primeiro encontrado
        // em empate -> 150 (o mais antigo dos dois, ordem de inserção)
        val empate = history.positionAt(175L)
        assertEquals(150L, empate?.timestampMs)
    }

    @Test
    fun `janela de tempo descarta o que ficou mais velho que maxAgeMs`() {
        val history = PositionHistory(maxAgeMs = 200L)
        history.record(0f, 0f, 0L)
        history.record(1f, 1f, 100L)
        history.record(2f, 2f, 200L)
        history.record(3f, 3f, 300L) // descarta o ponto de t=0

        // buscando bem no passado, o mais próximo disponível agora é t=100 (t=0 foi descartado)
        val maisAntigoDisponivel = history.positionAt(-1000L)
        assertEquals(100L, maisAntigoDisponivel?.timestampMs)
    }

    @Test
    fun `reset limpa o buffer`() {
        val history = PositionHistory()
        history.record(10f, 20f, 500L)

        history.reset()

        assertNull(history.positionAt(500L))
    }

    @Test
    fun `lookback de 100ms tipico do pinch-down encontra o ponto correto`() {
        val history = PositionHistory()
        // ~30fps: um ponto a cada ~33ms
        var t = 0L
        val positions = listOf(0f to 0f, 1f to 1f, 2f to 2f, 3f to 3f, 4f to 4f, 5f to 5f)
        for ((x, y) in positions) {
            history.record(x, y, t)
            t += 33L
        }
        // "agora" = t da ultima amostra (165); lookback de 100ms -> alvo = 65
        val now = t - 33L // 165
        val frozen = history.positionAt(now - 100L) // alvo = 65 -> mais perto de t=66 (idx4) ou t=33(idx1)?

        // amostras: 0,33,66,99,132,165 ; alvo=65 -> diffs: |0-65|=65,|33-65|=32,|66-65|=1,...
        assertEquals(66L, frozen?.timestampMs)
        assertEquals(2f, frozen?.x)
    }

    @Test
    fun `janela cobre o mesmo tempo a qualquer fps`() {
        val history = PositionHistory() // 600ms
        for (i in 0..30) history.record(i.toFloat(), 0f, i * 42L) // 24fps, 1.26s
        // 600ms back from the newest sample is still there (12 samples at 24fps would be 500ms).
        assertEquals(1260L - 588L, history.positionAt(1260L - 600L)?.timestampMs)
    }

    @Test
    fun `fistOnset devolve a ultima amostra antes de os dedos comecarem a fechar`() {
        val history = PositionHistory()
        // Hand open (curl ~1.30) and still at x=100 until t=200, then it closes while the
        // knuckles drag the cursor to x=130.
        var t = 0L
        repeat(13) { history.record(100f, 50f, t, 1.30f + (it % 2) * 0.01f); t += 16L } // until 192
        val closing = listOf(1.20f to 105f, 1.08f to 112f, 0.98f to 120f, 0.90f to 126f, 0.86f to 130f)
        for ((curl, x) in closing) { history.record(x, 50f, t, curl); t += 16L }
        repeat(10) { history.record(130f, 50f, t, 0.85f); t += 16L }

        val onset = history.fistOnset(t - 16L, fistCurl = 0.95f)
        assertEquals(100f, onset?.x)
        assertEquals(192L, onset?.timestampMs)
    }

    @Test
    fun `fistOnset ignora a mao aberta em movimento antes de relaxar no alvo`() {
        val history = PositionHistory()
        var t = 0L
        // Open hand (1.30) sweeping onto the target at x=300…
        for (x in listOf(0f, 60f, 120f, 180f, 240f, 300f)) { history.record(x, 0f, t, 1.30f); t += 16L }
        // …relaxes there (1.10) and stays still…
        repeat(12) { history.record(300f, 0f, t, 1.10f); t += 16L }
        val relaxedEnd = t - 16L
        // …then closes into a fist.
        for ((curl, x) in listOf(1.00f to 305f, 0.92f to 310f, 0.87f to 312f)) { history.record(x, 0f, t, curl); t += 16L }
        repeat(12) { history.record(312f, 0f, t, 0.86f); t += 16L }

        val onset = history.fistOnset(t - 16L, fistCurl = 0.95f)
        assertEquals(300f, onset?.x)
        assertEquals(relaxedEnd, onset?.timestampMs)
    }

    @Test
    fun `fistOnset sem curl gravado devolve null`() {
        val history = PositionHistory()
        history.record(1f, 1f, 0L)
        assertNull(history.fistOnset(0L, fistCurl = 0.95f))
    }
}
