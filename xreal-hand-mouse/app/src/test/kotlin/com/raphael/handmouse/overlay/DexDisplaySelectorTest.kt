package com.raphael.handmouse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TDD da prioridade de seleção do display do DeX (brief Tarefa 4, PLANO.md §3.4/§6.4):
 * categoria Samsung DESKTOP > DISPLAY_CATEGORY_PRESENTATION > nenhum. Função pura sobre
 * [CategorizedDisplay] (sem android.view.Display) para rodar 100% em JVM, sem Robolectric.
 */
class DexDisplaySelectorTest {

    @Test
    fun `desktop tem prioridade sobre presentation`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 1, isDesktop = false, isPresentation = true),
            CategorizedDisplay(displayId = 2, isDesktop = true, isPresentation = false),
        )

        val selected = DexDisplaySelector.select(displays)

        assertEquals(2, selected?.displayId)
    }

    @Test
    fun `sem desktop cai para presentation`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false),
            CategorizedDisplay(displayId = 3, isDesktop = false, isPresentation = true),
        )

        val selected = DexDisplaySelector.select(displays)

        assertEquals(3, selected?.displayId)
    }

    @Test
    fun `lista vazia retorna null`() {
        assertNull(DexDisplaySelector.select(emptyList()))
    }

    @Test
    fun `nenhum display categorizado como desktop ou presentation retorna null`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false),
            CategorizedDisplay(displayId = 1, isDesktop = false, isPresentation = false),
        )

        assertNull(DexDisplaySelector.select(displays))
    }

    @Test
    fun `remocao do display desktop selecionado cai pro proximo candidato presentation`() {
        val comDesktop = listOf(
            CategorizedDisplay(displayId = 1, isDesktop = true, isPresentation = false),
            CategorizedDisplay(displayId = 2, isDesktop = false, isPresentation = true),
        )
        val primeiraSelecao = DexDisplaySelector.select(comDesktop)
        assertEquals(1, primeiraSelecao?.displayId)

        // simula DisplayManager.DisplayListener.onDisplayRemoved: o display 1 some da lista
        val depoisDaRemocao = comDesktop.filterNot { it.displayId == primeiraSelecao?.displayId }
        val segundaSelecao = DexDisplaySelector.select(depoisDaRemocao)

        assertEquals(2, segundaSelecao?.displayId)
    }

    @Test
    fun `remocao do unico display selecionado retorna null`() {
        val apenasPresentation = listOf(
            CategorizedDisplay(displayId = 2, isDesktop = false, isPresentation = true),
        )
        val selecionado = DexDisplaySelector.select(apenasPresentation)
        assertEquals(2, selecionado?.displayId)

        val vazioAposRemocao = apenasPresentation.filterNot { it.displayId == selecionado?.displayId }

        assertNull(DexDisplaySelector.select(vazioAposRemocao))
    }
}
