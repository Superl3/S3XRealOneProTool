package com.raphael.handmouse.overlay

/**
 * Entrada de display já categorizada, sem depender de `android.view.Display` — existe só para
 * permitir testar [DexDisplaySelector.select] 100% em JVM (brief Tarefa 4: "teste-a (JVM, sem
 * Android)"). [DexDisplayMonitor] é quem faz a ponte com os `Display` reais.
 */
data class CategorizedDisplay(
    val displayId: Int,
    val isDesktop: Boolean,
    val isPresentation: Boolean,
)

/**
 * Prioridade de seleção do display do DeX — PLANO.md §3.4/§6.4: categoria Samsung
 * `DESKTOP` > `DISPLAY_CATEGORY_PRESENTATION` > nenhum. Função pura sobre a lista já
 * categorizada — nada de Android aqui, para que [DexDisplayMonitor] permaneça a única classe
 * desta tarefa que efetivamente toca `DisplayManager`/`Display`.
 */
object DexDisplaySelector {
    fun select(displays: List<CategorizedDisplay>): CategorizedDisplay? =
        displays.firstOrNull { it.isDesktop } ?: displays.firstOrNull { it.isPresentation }
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
