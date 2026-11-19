package com.raphael.handmouse.overlay

import com.raphael.handmouse.overlay.DisplayKind.EXTERNAL
import com.raphael.handmouse.overlay.DisplayKind.INTERNAL
import com.raphael.handmouse.overlay.DisplayKind.UNKNOWN
import com.raphael.handmouse.overlay.DisplayKind.VIRTUAL
import com.raphael.handmouse.overlay.DisplayKind.WIFI
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
            CategorizedDisplay(displayId = 1, isDesktop = false, isPresentation = true, kind = EXTERNAL),
            CategorizedDisplay(displayId = 2, isDesktop = true, isPresentation = false, kind = EXTERNAL),
        )

        val selected = DexDisplaySelector.select(displays)

        assertEquals(2, selected?.displayId)
    }

    @Test
    fun `sem desktop cai para presentation`() {
        val displays = listOf(
            CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false, kind = EXTERNAL),
            CategorizedDisplay(displayId = 3, isDesktop = false, isPresentation = true, kind = EXTERNAL),
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
            CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false, kind = EXTERNAL),
            CategorizedDisplay(displayId = 1, isDesktop = false, isPresentation = false, kind = EXTERNAL),
        )

        assertNull(DexDisplaySelector.select(displays))
    }

    @Test
    fun `remocao do display desktop selecionado cai pro proximo candidato presentation`() {
        val comDesktop = listOf(
            CategorizedDisplay(displayId = 1, isDesktop = true, isPresentation = false, kind = EXTERNAL),
            CategorizedDisplay(displayId = 2, isDesktop = false, isPresentation = true, kind = EXTERNAL),
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
            CategorizedDisplay(displayId = 2, isDesktop = false, isPresentation = true, kind = EXTERNAL),
        )
        val selecionado = DexDisplaySelector.select(apenasPresentation)
        assertEquals(2, selecionado?.displayId)

        val vazioAposRemocao = apenasPresentation.filterNot { it.displayId == selecionado?.displayId }

        assertNull(DexDisplaySelector.select(vazioAposRemocao))
    }

    // Spec 2026-11-17 section O: the Buddy app's shell daemon (uid 2000) makes 1080x2340
    // presentation displays ("buddy-<hash>") whose ids can be lower than the DeX display's.

    private val buddy = CategorizedDisplay(displayId = 61, isDesktop = false, isPresentation = true, kind = VIRTUAL, ownerUid = 2000)
    private val phone = CategorizedDisplay(displayId = 0, isDesktop = false, isPresentation = false, kind = INTERNAL, hasProductInfo = true)
    private val glasses = CategorizedDisplay(displayId = 64, isDesktop = false, isPresentation = true, kind = EXTERNAL, hasProductInfo = true)

    @Test
    fun `a lower-id virtual presentation display of another uid loses to the glasses`() {
        assertEquals(64, DexDisplaySelector.select(listOf(phone, buddy, glasses), selfUid = 10_321)?.displayId)
    }

    @Test
    fun `glasses removed while another uid's virtual display exists selects nothing`() {
        assertNull(DexDisplaySelector.select(listOf(phone, buddy), selfUid = 10_321))
    }

    @Test
    fun `a virtual display with an unreadable owner is never picked`() {
        assertNull(DexDisplaySelector.select(listOf(phone, buddy.copy(ownerUid = null)), selfUid = 10_321))
    }

    @Test
    fun `a virtual display of this app may be picked, after a physical one`() {
        val own = buddy.copy(ownerUid = 10_321)
        assertEquals(61, DexDisplaySelector.select(listOf(phone, own), selfUid = 10_321)?.displayId)
        assertEquals(64, DexDisplaySelector.select(listOf(phone, own, glasses), selfUid = 10_321)?.displayId)
    }

    @Test
    fun `without the hidden type, product info proves a physical display`() {
        val unknownGlasses = glasses.copy(kind = UNKNOWN)
        val unknownBuddy = buddy.copy(kind = UNKNOWN, ownerUid = null)
        assertEquals(64, DexDisplaySelector.select(listOf(phone.copy(kind = UNKNOWN), unknownBuddy, unknownGlasses))?.displayId)
        assertNull(DexDisplaySelector.select(listOf(phone.copy(kind = UNKNOWN), unknownBuddy)))
    }

    @Test
    fun `a physical external presentation display beats a lower-id wifi one`() {
        val wifi = CategorizedDisplay(displayId = 5, isDesktop = false, isPresentation = true, kind = WIFI)
        assertEquals(64, DexDisplaySelector.select(listOf(phone, wifi, glasses))?.displayId)
        assertEquals(5, DexDisplaySelector.select(listOf(phone, wifi))?.displayId)
    }

    @Test
    fun `the desktop category still comes first`() {
        val desktop = CategorizedDisplay(displayId = 70, isDesktop = true, isPresentation = false, kind = WIFI)
        assertEquals(70, DexDisplaySelector.select(listOf(phone, glasses, desktop))?.displayId)
    }

    @Test
    fun `a desktop-category virtual display of another uid is not picked either`() {
        val foreignDesktop = buddy.copy(isDesktop = true)
        assertEquals(64, DexDisplaySelector.select(listOf(foreignDesktop, glasses), selfUid = 10_321)?.displayId)
    }

    @Test
    fun `display kinds map from the hidden type constants`() {
        assertEquals(INTERNAL, DisplayKind.fromType(1))
        assertEquals(EXTERNAL, DisplayKind.fromType(2))
        assertEquals(WIFI, DisplayKind.fromType(3))
        assertEquals(VIRTUAL, DisplayKind.fromType(5))
        assertEquals(UNKNOWN, DisplayKind.fromType(null))
        assertEquals(UNKNOWN, DisplayKind.fromType(0))
    }
}
