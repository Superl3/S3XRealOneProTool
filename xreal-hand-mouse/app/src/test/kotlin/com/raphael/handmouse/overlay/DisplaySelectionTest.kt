package com.raphael.handmouse.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do fix de revisão da Tarefa 4: `DexDisplayMonitor`/`HandMouseAccessibilityService`
 * recriavam a janela do overlay (`overlay.show(display)` = hide + addView) em TODO evento de
 * `DisplayManager.DisplayListener`, mesmo quando o display do DeX escolhido e seus bounds não
 * mudaram (eventos de displays não relacionados também disparam `onDisplayChanged`) — jank
 * visível. Fix: [hasDisplaySelectionChanged] decide, de forma pura (sem `android.view.Display`
 * nem `Rect`), se uma nova seleção (id + bounds em pixels) é realmente diferente da anterior;
 * só então o chamador ([com.raphael.handmouse.service.HandMouseAccessibilityService]) recria a
 * janela.
 */
class DisplaySelectionTest {

    @Test
    fun `mesmo id e mesmos bounds nao e mudanca`() {
        val previous = DisplaySelection(displayId = 2, width = 1920, height = 1080)
        val current = DisplaySelection(displayId = 2, width = 1920, height = 1080)

        assertFalse(hasDisplaySelectionChanged(previous, current))
    }

    @Test
    fun `id diferente e mudanca`() {
        val previous = DisplaySelection(displayId = 2, width = 1920, height = 1080)
        val current = DisplaySelection(displayId = 3, width = 1920, height = 1080)

        assertTrue(hasDisplaySelectionChanged(previous, current))
    }

    @Test
    fun `bounds diferentes com mesmo id e mudanca (troca de modo UltraWide)`() {
        val previous = DisplaySelection(displayId = 2, width = 1920, height = 1080)
        val current = DisplaySelection(displayId = 2, width = 3840, height = 1080)

        assertTrue(hasDisplaySelectionChanged(previous, current))
    }

    @Test
    fun `display aparecendo (null para nao-null) e mudanca`() {
        val current = DisplaySelection(displayId = 2, width = 1920, height = 1080)

        assertTrue(hasDisplaySelectionChanged(null, current))
    }

    @Test
    fun `display sumindo (nao-null para null) e mudanca`() {
        val previous = DisplaySelection(displayId = 2, width = 1920, height = 1080)

        assertTrue(hasDisplaySelectionChanged(previous, null))
    }

    @Test
    fun `ambos nulos (nenhum display antes e depois) nao e mudanca`() {
        assertFalse(hasDisplaySelectionChanged(null, null))
    }
}
