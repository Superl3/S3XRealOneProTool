package com.raphael.handmouse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TDD da dead-zone radial (brief Tarefa 4, PLANO.md Fase 3): retém pequenas variações de
 * posição do cursor (jitter residual pós-OneEuroFilter). Regra exata do brief: "se distância
 * do último ponto EMITIDO < raio (default 2.5px), retém; senão emite".
 */
class RadialDeadZoneTest {

    @Test
    fun `primeiro ponto sempre emite`() {
        val deadZone = RadialDeadZone(radiusPx = 2.5f)

        val result = deadZone.filter(100f, 200f)

        assertEquals(100f to 200f, result)
    }

    @Test
    fun `ponto dentro do raio do ultimo ponto emitido e retido`() {
        val deadZone = RadialDeadZone(radiusPx = 2.5f)
        deadZone.filter(100f, 100f)

        // distancia = sqrt(1^2 + 1^2) ~= 1.41 < 2.5
        val result = deadZone.filter(101f, 101f)

        assertNull(result)
    }

    @Test
    fun `ponto fora do raio do ultimo ponto emitido emite`() {
        val deadZone = RadialDeadZone(radiusPx = 2.5f)
        deadZone.filter(100f, 100f)

        // distancia = 10 >= 2.5
        val result = deadZone.filter(110f, 100f)

        assertEquals(110f to 100f, result)
    }

    @Test
    fun `distancia exatamente igual ao raio emite (regra e menor-que, nao menor-igual)`() {
        val deadZone = RadialDeadZone(radiusPx = 2.5f)
        deadZone.filter(0f, 0f)

        val result = deadZone.filter(2.5f, 0f)

        assertEquals(2.5f to 0f, result)
    }

    @Test
    fun `pontos retidos em sequencia nao movem a referencia (drift lento nao escapa)`() {
        val deadZone = RadialDeadZone(radiusPx = 2.5f)
        deadZone.filter(0f, 0f)

        // cada passo de 1px fica sempre < 2.5 da referencia original (0,0) enquanto retido
        assertNull(deadZone.filter(1f, 0f))
        assertNull(deadZone.filter(2f, 0f))
        // 2.4 ainda < 2.5
        assertNull(deadZone.filter(2.4f, 0f))

        // agora emite, e passa a ser a nova referencia
        val emitted = deadZone.filter(3f, 0f)
        assertEquals(3f to 0f, emitted)
    }

    @Test
    fun `reset limpa a referencia e o proximo ponto sempre emite`() {
        val deadZone = RadialDeadZone(radiusPx = 2.5f)
        deadZone.filter(100f, 100f)

        deadZone.reset()

        // sem reset, (101,101) seria retido (visto no teste acima) -- apos reset, emite de novo
        val result = deadZone.filter(101f, 101f)
        assertEquals(101f to 101f, result)
    }
}
