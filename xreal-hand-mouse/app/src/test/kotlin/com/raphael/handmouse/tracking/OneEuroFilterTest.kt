package com.raphael.handmouse.tracking

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do One Euro Filter (PLANO.md §6.2/Fase 3, brief Tarefa 3): 1 instância por eixo,
 * minCutoff=1.0, beta=0.007, dCutoff=1.0 (defaults — retuning de 2026-07-23, ver comentário
 * no construtor do filtro), freq calculada dos timestamps reais (`filter(value, timestampMs)`),
 * sem estado além do necessário para a recorrência (`x_prev`, `dx_prev`, `t_prev`).
 */
class OneEuroFilterTest {

    private val frameIntervalMs = 33L // ~30fps

    @Test
    fun `entrada constante converge para o valor e para de variar`() {
        val filter = OneEuroFilter()
        var t = 0L
        var last = Float.NaN
        repeat(10) {
            last = filter.filter(10f, t)
            t += frameIntervalMs
        }
        assertEquals(10f, last, 1e-4f)

        // mais algumas chamadas: a saída não deve mais variar (já convergiu)
        val afterConvergence = filter.filter(10f, t)
        t += frameIntervalMs
        val oneMore = filter.filter(10f, t)
        assertEquals(afterConvergence, oneMore, 1e-6f)
        assertEquals(10f, oneMore, 1e-4f)
    }

    @Test
    fun `jitter de baixa amplitude tem desvio padrao de saida menor que o de entrada`() {
        val filter = OneEuroFilter()
        val random = Random(42)
        val base = 50f
        val inputs = mutableListOf<Float>()
        val outputs = mutableListOf<Float>()
        var t = 0L
        repeat(60) {
            val noisy = base + random.nextFloat() * 2f - 1f // ruido em [-1, 1]
            val out = filter.filter(noisy, t)
            inputs.add(noisy)
            outputs.add(out)
            t += frameIntervalMs
        }

        // ignora as primeiras amostras (warm-up do filtro)
        val warmup = 5
        val inputStd = stdDev(inputs.drop(warmup))
        val outputStd = stdDev(outputs.drop(warmup))

        assertTrue(
            "desvio padrao da saida ($outputStd) deveria ser menor que o da entrada ($inputStd)",
            outputStd < inputStd
        )
    }

    @Test
    fun `degrau grande alcança cerca de 90 por cento em poucos frames`() {
        val filter = OneEuroFilter()
        var t = 0L
        // aquece com valor parado em 0 (dx=0, cutoff=minCutoff estavel)
        repeat(10) {
            filter.filter(0f, t)
            t += frameIntervalMs
        }

        // degrau para 100 — com o tuning de 2026-07-23 (minCutoff=1.0, beta=0.007, que
        // FILTRA de verdade em vez de passar o sinal quase cru como o beta=0.1 antigo),
        // >=90% do degrau chega em ~4 frames (~130ms a 30fps): responsivo pra cursor,
        // suave o suficiente pra matar o tremor do tracking.
        var out = 0f
        repeat(4) {
            out = filter.filter(100f, t)
            t += frameIntervalMs
        }

        assertTrue("saida ($out) deveria alcançar >=90% do degrau em ~4 frames", out >= 90f)
    }

    @Test
    fun `reset limpa o estado e o proximo valor nao eh suavizado`() {
        val filter = OneEuroFilter()
        var t = 0L
        repeat(5) {
            filter.filter(10f, t)
            t += frameIntervalMs
        }

        filter.reset()

        // apos reset, o primeiro valor deve ser devolvido sem suavização (estado zerado)
        val out = filter.filter(500f, t)
        assertEquals(500f, out, 1e-4f)
    }

    private fun stdDev(values: List<Float>): Double {
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
