package com.raphael.handmouse.overlay

/**
 * What kind of display a [CategorizedDisplay] is: the hidden `Display.getType()` (TYPE_INTERNAL 1,
 * TYPE_EXTERNAL 2, TYPE_WIFI 3, TYPE_OVERLAY 4, TYPE_VIRTUAL 5), or [UNKNOWN] when it cannot be
 * read (the hidden API may be blocked).
 */
enum class DisplayKind {
    INTERNAL, EXTERNAL, WIFI, OVERLAY, VIRTUAL, UNKNOWN;

    companion object {
        fun fromType(type: Int?): DisplayKind = when (type) {
            1 -> INTERNAL
            2 -> EXTERNAL
            3 -> WIFI
            4 -> OVERLAY
            5 -> VIRTUAL
            else -> UNKNOWN
        }
    }
}

/**
 * Entrada de display já categorizada, sem depender de `android.view.Display` — existe só para
 * permitir testar [DexDisplaySelector.select] 100% em JVM (brief Tarefa 4: "teste-a (JVM, sem
 * Android)"). [DexDisplayMonitor] é quem faz a ponte com os `Display` reais.
 *
 * @property kind the display's type; see [DisplayKind].
 * @property hasProductInfo `Display.getDeviceProductInfo()` (public API) is set: the EDID of a
 *   physical display. Virtual displays have none. The XREAL glasses report one (`XREAL One Pro`).
 * @property ownerUid the uid that created a virtual display (hidden `Display.getOwnerUid()`); null
 *   when it is not one or cannot be read.
 */
data class CategorizedDisplay(
    val displayId: Int,
    val isDesktop: Boolean,
    val isPresentation: Boolean,
    val kind: DisplayKind = DisplayKind.UNKNOWN,
    val hasProductInfo: Boolean = false,
    val ownerUid: Int? = null,
)

/**
 * Picks the DeX display. A candidate is in Samsung's `DESKTOP` category or is a
 * `DISPLAY_CATEGORY_PRESENTATION` display (the DeX display on the glasses is only the latter, spec
 * 2026-11-17 section H). Among those:
 * - A virtual display another uid created is never picked. The Buddy app's shell daemon (uid 2000)
 *   makes 1080x2340 `buddy-<hash>` presentation displays, whose ids can be lower than DeX's after a
 *   glasses reconnect; picking one sent home, maximize and close to an invisible display (spec
 *   2026-11-17 section O).
 * - A display of unknown type is picked only with product info, which proves it physical; without
 *   both it may be such a virtual display.
 * - DESKTOP comes first, then physical external displays, then the rest (Wi-Fi, overlay, an own
 *   virtual display); within a rank the first in the list (DisplayManager lists by id), as before.
 *
 * @param selfUid this app's uid: a virtual display it created itself may be picked.
 */
object DexDisplaySelector {
    fun select(displays: List<CategorizedDisplay>, selfUid: Int = -1): CategorizedDisplay? =
        displays
            .filter { (it.isDesktop || it.isPresentation) && eligible(it, selfUid) }
            .sortedWith(compareByDescending<CategorizedDisplay> { it.isDesktop }.thenByDescending { isPhysicalExternal(it) })
            .firstOrNull()

    private fun eligible(display: CategorizedDisplay, selfUid: Int): Boolean = when (display.kind) {
        DisplayKind.VIRTUAL -> display.ownerUid != null && display.ownerUid == selfUid
        DisplayKind.UNKNOWN -> display.hasProductInfo
        else -> true
    }

    /** The public product info first; the hidden type covers an external display without EDID. */
    private fun isPhysicalExternal(display: CategorizedDisplay): Boolean =
        (display.hasProductInfo && display.kind != DisplayKind.INTERNAL) || display.kind == DisplayKind.EXTERNAL
}

/**
 * Identidade mínima de uma "seleção de display do DeX" — id + bounds em pixels — usada só pra
 * decidir se algo que justifique recriar a janela do overlay realmente mudou. Sem depender de
 * `android.view.Display`/`Rect` para que a decisão ([hasDisplaySelectionChanged]) rode 100% em
 * JVM (fix de revisão da Tarefa 4: ver Javadoc de
 * [com.raphael.handmouse.service.HandMouseAccessibilityService] sobre o bug original —
 * `DisplayManager.DisplayListener.onDisplayChanged` dispara pra eventos de displays não
 * relacionados, e o handler recriava a janela do cursor incondicionalmente a cada disparo).
 */
data class DisplaySelection(val displayId: Int, val width: Int, val height: Int)

/**
 * `true` se [previous] e [current] representam seleções DIFERENTES (id ou bounds mudaram, ou um
 * dos dois é `null` e o outro não — display apareceu/sumiu). `null` representa "nenhum display
 * do DeX selecionado". O chamador só deve recriar a janela do overlay quando isto retornar
 * `true` — evita destruir/reconstruir uma janela já correta em resposta a eventos de
 * `DisplayListener` que não afetam nem o display escolhido nem sua resolução.
 */
fun hasDisplaySelectionChanged(previous: DisplaySelection?, current: DisplaySelection?): Boolean =
    previous != current
