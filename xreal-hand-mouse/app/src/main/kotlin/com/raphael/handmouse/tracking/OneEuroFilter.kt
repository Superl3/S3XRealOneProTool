package com.raphael.handmouse.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro Filter (Casiez, Roussel & Vogel, 2012 — http://cristal.univ-lille.fr/~casiez/1euro/):
 * suaviza um sinal 1D ruidoso com baixa latência, adaptando o corte do passa-baixa conforme a
 * velocidade do sinal (`minCutoff` domina quando parado — reduz jitter; `beta` domina quando em
 * movimento rápido — reduz lag). Uma instância por eixo (x, y) — PLANO.md §6.2/§3.2, Fase 3.
 *
 * `freq` é recalculada a cada chamada a partir do delta real entre timestamps (não assume uma
 * taxa de quadros fixa) — necessário porque a câmera Eye não entrega frames em intervalos
 * perfeitamente regulares.
 */
class OneEuroFilter(
    // 4ª iteração (2026-07-23) — DIAGNÓSTICO DE RAIZ das 3 anteriores: beta=0.1 era ~25x alto
    // demais pra um sinal em PIXELS e mantinha o filtro efetivamente DESLIGADO o tempo todo.
    // O cutoff adaptativo é `minCutoff + beta*|dxHat|` com dxHat em px/s: até o simples jitter de
    // repouso (±4px a 30fps ≈ ±120px/s, dxHat ~±40) dava cutoff ≈ 0.4+4 = 4.4Hz >> minCutoff —
    // ou seja, o termo de velocidade dominava SEMPRE e o sinal passava quase cru (por isso mexer
    // no minCutoff entre 0.4/0.8/1.5 nunca mudou muito o tremor, e o cursor "tremia no movimento":
    // era o ruído cru do MediaPipe amplificado pelo ganho do mapeamento). beta=0.007 é o valor
    // clássico do paper/demos do Casiez pra coordenadas em pixels: em repouso o cutoff fica ≈
    // minCutoff (1.0 → suaviza de verdade, jitter residual ~1px, DENTRO da dead-zone de 1.5px);
    // em movimento rápido sustentado (~3000px/s) o cutoff sobe pra ~20Hz e o lag fica ~15-20px —
    // responsivo sem tremer. minCutoff volta a 1.0 (default do paper) porque agora ele REALMENTE
    // governa o repouso/movimento lento.
    //
    // 5ª iteração (2026-07-24, "parecer que treme menos, deslizar naturalmente"): minCutoff
    // 1.0 → 0.6 + beta 0.007 → 0.01 — a receita de tuning do próprio paper (baixar minCutoff até
    // o lag em movimento LENTO incomodar; subir beta pra devolver resposta no movimento RÁPIDO).
    // 0.6Hz suaviza ~2x mais forte o regime lento (onde o tremor residual era visível); o beta
    // maior faz o cutoff subir mais cedo com a velocidade, então o lag no movimento médio/rápido
    // fica praticamente igual ao de antes. Se o cursor ficar "pesado" em ajustes finos, subir
    // minCutoff de volta em direção a 1.0.
    var minCutoff: Float = 0.6f, // mutable: smoothing setting (Eye Tools fork)
    private val beta: Float = 0.01f,
    private val dCutoff: Float = 1.0f,
) {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrevMs: Long? = null

    /** Filtra [value] amostrado em [timestampMs] (`SystemClock.uptimeMillis()` no chamador). */
    fun filter(value: Float, timestampMs: Long): Float {
        val prevX = xPrev
        val prevT = tPrevMs
        if (prevX == null || prevT == null) {
            xPrev = value
            tPrevMs = timestampMs
            dxPrev = 0f
            return value
        }

        // dtMs>=1 evita divisão por zero/frequência infinita se dois frames caírem no mesmo ms
        // (o chamador — HandTracker — já deve garantir timestamps estritamente crescentes, mas
        // a defesa aqui é barata e evita NaN/Infinity propagarem pro cursor).
        val dtMs = (timestampMs - prevT).coerceAtLeast(1L)
        val freq = 1000f / dtMs

        val dx = (value - prevX) * freq
        val alphaD = smoothingFactor(dCutoff, freq)
        val dxHat = lowPass(dx, dxPrev, alphaD)

        val cutoff = minCutoff + beta * abs(dxHat)
        val alpha = smoothingFactor(cutoff, freq)
        val xHat = lowPass(value, prevX, alpha)

        xPrev = xHat
        dxPrev = dxHat
        tPrevMs = timestampMs
        return xHat
    }

    /** Limpa o estado (chamar quando a mão some/reaparece — evita salto suavizado com o "ar"). */
    fun reset() {
        xPrev = null
        dxPrev = 0f
        tPrevMs = null
    }

    private fun smoothingFactor(cutoff: Float, freq: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        val te = 1f / freq
        return 1f / (1f + tau / te)
    }

    private fun lowPass(x: Float, prev: Float, alpha: Float): Float = alpha * x + (1f - alpha) * prev
}
