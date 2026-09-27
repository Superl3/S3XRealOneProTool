package com.raphael.handmouse.util

import kotlin.math.sqrt

/**
 * Dead-zone radial: retém pequenas variações de posição do cursor (jitter residual que
 * sobrevive ao [com.raphael.handmouse.tracking.OneEuroFilter]) — PLANO.md Fase 3, brief
 * Tarefa 4. Só emite um novo ponto se a distância até o último ponto **emitido** (não o
 * último ponto recebido — retenções sucessivas não deslocam a referência) for `>= [radiusPx]`;
 * o primeiro ponto sempre é emitido, pois ainda não há referência.
 *
 * Classe pura (sem dependência de Android) — 1 instância no [com.raphael.handmouse.tracking.CursorPipeline],
 * aplicada depois do OneEuroFilter, antes de [com.raphael.handmouse.overlay.CursorOverlay.moveTo].
 */
// Ajuste de hardware (2026-07-23): raio default 2.5 → 1.5px. Com a mão parada o pós-filtro ainda
// oscila ±3-5px; com 2.5px o cursor andava em "degraus" perceptíveis (trava → pula ≥2.5px → trava)
// que o usuário sentia como travado/tremendo. 1.5px mantém a supressão de micro-jitter com passos
// menores e menos visíveis.
class RadialDeadZone(var radiusPx: Float = 1.5f) { // mutable: dead-zone setting (Eye Tools fork)

    private var lastEmittedX: Float? = null
    private var lastEmittedY: Float? = null

    /** Retorna o ponto (x, y) a emitir, ou `null` se retido dentro da dead-zone. */
    fun filter(x: Float, y: Float): Pair<Float, Float>? {
        val lx = lastEmittedX
        val ly = lastEmittedY
        if (lx == null || ly == null) {
            lastEmittedX = x
            lastEmittedY = y
            return x to y
        }

        val dx = x - lx
        val dy = y - ly
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < radiusPx) return null

        lastEmittedX = x
        lastEmittedY = y
        return x to y
    }

    /** Limpa a referência do último ponto emitido (chamar quando a mão some/reaparece —
     * evita reter o primeiro ponto novo contra uma posição "velha" de antes da mão sumir). */
    fun reset() {
        lastEmittedX = null
        lastEmittedY = null
    }
}
