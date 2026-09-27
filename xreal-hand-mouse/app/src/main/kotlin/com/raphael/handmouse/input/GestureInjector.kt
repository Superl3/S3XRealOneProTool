package com.raphael.handmouse.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Injeção de clique/drag no display do DeX via `dispatchGesture` — PLANO.md §3.3/§6.4, brief
 * Tarefa 5. Chamada pelo [com.raphael.handmouse.tracking.CursorPipeline], que é dono de uma
 * instância desta classe construída pelo [com.raphael.handmouse.service.HandMouseAccessibilityService]
 * (dispatchGesture só existe como método de instância de [AccessibilityService] — [service] É
 * essa instância). Coordenadas SEMPRE em pixels do display alvo (bounds vêm do
 * `DexDisplayMonitor`, repassadas pelo `CursorPipeline` — nunca hardcodadas aqui).
 *
 * ## Drag guiado por conclusão — fix do backlog (2026-07-23)
 * O esquema anterior (despachar uma continuação a cada 50ms com segmentos de 130-320ms) tinha um
 * defeito estrutural comprovado em hardware: o system_server toca as strokes continuadas EM SÉRIE,
 * cada uma consumindo a duração INTEIRA configurada — alimentar mais rápido do que toca acumula
 * fila sem limite. Log da sessão de validação: latência de stroke crescendo 97ms → 1468ms num
 * drag de 3,3s, e o ponteiro continuando a se mover por ~1,5s DEPOIS do endDrag (a fila drenando)
 * — era o "scroll travado" + "continua mexendo depois de soltar" reportados pelo usuário.
 *
 * O modelo novo elimina o backlog por construção: **no máximo 1 stroke em voo**. Enquanto uma
 * stroke toca, as posições novas da mão só ATUALIZAM [pendingTarget] (coalescing — a última
 * vence); quando o `onCompleted` chega, despachamos UM segmento do fim da stroke anterior direto
 * pra posição mais recente. O ponteiro injetado nunca reproduz posição velha e o [endDrag] vale
 * em no máximo 1 segmento (~[DRAG_SEGMENT_DURATION_MS]). Sem throttle por tempo — o ritmo é
 * ditado pelo próprio sistema (conclusão real da stroke).
 *
 * ## Segmento real (fix anterior do "completa em 7ms", mantido)
 * Todo segmento de drag é uma linha REAL de 2 pontos (`moveTo(fimAnterior) → lineTo(novo)`),
 * comprimento > 0 — path só com `moveTo` (comprimento 0) completa em ~7ms e mata a cadeia de
 * `continueStroke`. Com a mão parada, [segmentEnd] injeta um empurrão de [DRAG_MIN_SEGMENT_PX]
 * alternado no sinal (deriva líquida ~0) pra o segmento nunca degenerar.
 *
 * ## Duplo-tap de mídia (2026-07-23)
 * [doubleTap] injeta dois taps na mesma posição pra gestos de mídia (seek de vídeo — ver
 * [MediaGestureController]). Usa strokes INDEPENDENTES (`willContinue=false`), como o [tap]
 * simples — NÃO toca no maquinário de drag guiado por conclusão acima (currentDragStroke/
 * pendingTarget/lock ficam intocados). Ver o Javadoc de [doubleTap] pro racional do agendamento
 * do 2º tap e a ausência deliberada de retry.
 *
 * ## Threading
 * [beginDrag]/[updateDrag]/[endDrag] chegam da HandlerThread do HandTracker (via CursorPipeline);
 * os callbacks de `dispatchGesture` chegam na MAIN thread (handler `null`). Todo o estado de drag
 * é protegido por [lock]. [dragGeneration] descarta callbacks de uma cadeia substituída (um
 * `beginDrag` novo enquanto a antiga ainda tinha stroke em voo).
 *
 * Não testável em JVM puro (depende de `GestureDescription`/`Path`/`AccessibilityService` reais
 * do Android); toda decisão de estado/transição fica logada (brief) pra depuração em hardware.
 */
class GestureInjector(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "GestureInjector"

        /** Duração do stroke de tap — PLANO.md §6.4. */
        private const val TAP_DURATION_MS = 50L

        /** Gap entre os DOIS taps do [doubleTap] (ms). O detector de duplo-toque dos players de
         * vídeo (YouTube/Netflix) precisa de uma folga PERCEPTÍVEL entre os taps pra distingui-los:
         * rápido demais (gap ~0) é tratado como um tap só e NÃO dispara o seek. 110ms fica na
         * janela típica desses detectores — folga clara entre os taps, mas bem dentro do timeout
         * de duplo-toque (~300ms) pra ainda contar como o mesmo gesto. */
        private const val DOUBLE_TAP_GAP_MS = 110L

        /** Duração de cada segmento de drag. Com o dispatch guiado por conclusão (1 stroke em
         * voo), isto define o RITMO do ponteiro injetado (~1 segmento a cada duração + IPC):
         * 60ms ≈ 13-15 segmentos/s, sempre pra posição mais recente da mão. Não precisa mais de
         * margem sobre um throttle — não existe fila. */
        private const val DRAG_SEGMENT_DURATION_MS = 60L

        /** Comprimento mínimo (px) de CADA segmento de drag. O bug do "completa em 7ms" era path
         * degenerado (só `moveTo` = comprimento 0). Um segmento REAL (>0 px) faz a stroke honrar
         * [DRAG_SEGMENT_DURATION_MS] e permanecer continuável. 2px é invisível no DeX mas
         * inequivocamente não-degenerado. */
        private const val DRAG_MIN_SEGMENT_PX = 2f
    }

    /** Handler da main thread — só pro gap agendado do [doubleTap] (o postDelayed do 2º tap).
     * Independente do maquinário de drag; os callbacks de gesto já chegam na main thread, então
     * postar aqui mantém o 2º dispatch na mesma thread dos callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Estado da cadeia de drag corrente — TODO acesso sob [lock] (ver "Threading") ----
    private val lock = Any()
    private var currentDragStroke: GestureDescription.StrokeDescription? = null
    private var dragDisplayId: Int = -1
    private var dragGeneration: Int = 0

    /** Há uma stroke de drag despachada cujo onCompleted/onCancelled ainda não chegou. */
    private var strokeInFlight = false

    /** Última posição da mão ainda não despachada (coalescida — só a mais recente sobrevive). */
    private var pendingTarget: Pair<Float, Float>? = null

    /** endDrag pedido enquanto havia stroke em voo — despachado no próximo onCompleted. */
    private var pendingEnd: Pair<Float, Float>? = null

    // Ponto FINAL da última stroke de drag despachada — toda continuação começa AQUI (contrato do
    // continueStroke: o path da continuação inicia onde a stroke anterior terminou).
    private var dragLastX: Float = 0f
    private var dragLastY: Float = 0f
    // Alterna o "empurrãozinho" quando a mão está parada, pra manter o segmento não-degenerado sem
    // acumular deriva (soma líquida ~0).
    private var dragNudgeSign: Float = 1f
    // Sequência por-stroke só pra instrumentação (correlaciona dispatch <-> callback no log).
    private var dragStrokeSeq: Int = 0

    /** Tap único (clique) — stroke de [TAP_DURATION_MS] na posição congelada resolvida pelo
     * chamador. Em `onCancelled`, retry exatamente 1x (brief); se o retry também falhar, só
     * loga (desiste). */
    fun tap(x: Float, y: Float, displayId: Int) {
        dispatchTap(x, y, displayId, isRetry = false)
    }

    private fun dispatchTap(x: Float, y: Float, displayId: Int, isRetry: Boolean) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "tap completado em ($x, $y) display=$displayId${if (isRetry) " [retry]" else ""}")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (isRetry) {
                    Log.w(TAG, "tap cancelado em ($x, $y) display=$displayId no retry — desistindo")
                } else {
                    Log.w(TAG, "tap cancelado em ($x, $y) display=$displayId — retry 1x")
                    dispatchTap(x, y, displayId, isRetry = true)
                }
            }
        }, null)

        if (!accepted) {
            Log.w(TAG, "dispatchGesture recusou o tap em ($x, $y) display=$displayId (não aceito pelo sistema)")
        }
    }

    /**
     * Duplo-tap (dois taps na MESMA posição) pra gestos de mídia — seek de vídeo (ver
     * [MediaGestureController]). Dois strokes de [TAP_DURATION_MS] separados por
     * [DOUBLE_TAP_GAP_MS], strokes INDEPENDENTES (`willContinue=false`, como o [tap] simples) —
     * não interfere na cadeia de drag guiada por conclusão.
     *
     * ## Agendamento do 2º tap — no `onCompleted` do 1º (NÃO numa timeline fixa)
     * O 2º tap é despachado dentro do `onCompleted` do 1º, com o gap num `postDelayed` do
     * [mainHandler]. A alternativa (agendar os dois de uma vez com postDelayed a partir do início)
     * foi REJEITADA de propósito: se o 1º tap fosse cancelado numa timeline fixa, o 2º ainda
     * dispararia sozinho — sobraria UM tap solto = tap simples = play/pause acidental no player.
     * Ligando o 2º ao `onCompleted` do 1º, um 1º cancelado aborta a sequência inteira e NENHUM
     * tap sobra (falha segura). `dispatchGesture` pode ser chamado de qualquer thread, mas os
     * callbacks chegam na main thread — o `postDelayed` no [mainHandler] já está na thread certa.
     *
     * ## Sem retry (diferente do [tap] simples)
     * Se QUALQUER um dos dois taps for cancelado, só loga e desiste. Retry atrasado de metade de um
     * duplo-tap viraria dois taps muito espaçados = dois taps simples (play/pause acidental) — o
     * risco de não fazer nada é bem menor que o de agir errado no player.
     */
    fun doubleTap(x: Float, y: Float, displayId: Int) {
        Log.d(TAG, "doubleTap solicitado em ($x, $y) display=$displayId")
        dispatchDoubleTapStroke(x, y, displayId, index = 1) {
            // 1º tap confirmado pelo sistema — agenda o 2º após o gap perceptível.
            mainHandler.postDelayed({
                dispatchDoubleTapStroke(x, y, displayId, index = 2, onCompleted = null)
            }, DOUBLE_TAP_GAP_MS)
        }
    }

    /** Despacha UM dos taps do [doubleTap]. Sem retry (ver Javadoc do [doubleTap]): `onCancelled`
     * só loga. [onCompleted] (não-nulo só no 1º tap) agenda o 2º — assim o 2º só sai se o 1º
     * realmente aterrissou. Segue o estilo de callback logado do [dispatchTap]. */
    private fun dispatchDoubleTapStroke(
        x: Float,
        y: Float,
        displayId: Int,
        index: Int,
        onCompleted: (() -> Unit)?,
    ) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "doubleTap: tap $index/2 completado em ($x, $y) display=$displayId")
                onCompleted?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "doubleTap: tap $index/2 CANCELADO em ($x, $y) display=$displayId — desistindo (sem retry)")
            }
        }, null)

        if (!accepted) {
            Log.w(TAG, "doubleTap: dispatchGesture recusou o tap $index/2 em ($x, $y) display=$displayId (não aceito pelo sistema)")
        }
    }

    /** Inicia um drag: primeira stroke com `willContinue=true`, com um SEGMENTO REAL (moveTo →
     * lineTo) pra não degenerar (ver Javadoc da classe). Substitui qualquer drag em andamento
     * (a geração nova invalida os callbacks da cadeia antiga). */
    fun beginDrag(x: Float, y: Float, displayId: Int) = synchronized(lock) {
        if (currentDragStroke != null) {
            Log.w(TAG, "beginDrag com drag já ativo — substituindo a cadeia anterior")
        }
        dragGeneration++
        val endX = x + DRAG_MIN_SEGMENT_PX
        val path = Path().apply { moveTo(x, y); lineTo(endX, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, DRAG_SEGMENT_DURATION_MS, true)
        currentDragStroke = stroke
        dragDisplayId = displayId
        dragLastX = endX
        dragLastY = y
        dragNudgeSign = 1f
        strokeInFlight = true
        pendingTarget = null
        pendingEnd = null
        Log.d(TAG, "beginDrag em ($x, $y) display=$displayId")
        dispatchDragStroke(stroke, displayId, "beginDrag", willContinue = true, generation = dragGeneration)
    }

    /** Segue a mão. Com stroke em voo, só coalesce em [pendingTarget] (a posição mais recente
     * vence — quem despacha é o `onCompleted`); sem stroke em voo, despacha imediatamente. Sem
     * efeito (loga e ignora) se não há [beginDrag] ativo. */
    fun updateDrag(x: Float, y: Float) = synchronized(lock) {
        if (currentDragStroke == null) {
            Log.w(TAG, "updateDrag($x, $y) sem beginDrag ativo — ignorado")
            return
        }
        if (pendingEnd != null) return // já soltando — posição nova não importa mais
        if (strokeInFlight) {
            pendingTarget = x to y
        } else {
            dispatchNextSegment(x, y, willContinue = true, label = "updateDrag")
        }
    }

    /** Finaliza o drag. Com stroke em voo, agenda a stroke final pro próximo `onCompleted`
     * (no máximo ~1 segmento de atraso); sem stroke em voo, despacha já. Sem efeito (loga e
     * ignora) se não há [beginDrag] ativo. */
    fun endDrag(x: Float, y: Float) = synchronized(lock) {
        if (currentDragStroke == null) {
            Log.w(TAG, "endDrag($x, $y) sem beginDrag ativo — ignorado")
            return
        }
        Log.d(TAG, "endDrag pedido em ($x, $y) display=$dragDisplayId (inFlight=$strokeInFlight)")
        pendingTarget = null
        if (strokeInFlight) {
            pendingEnd = x to y
        } else {
            dispatchNextSegment(x, y, willContinue = false, label = "endDrag")
        }
    }

    /** Despacha o próximo segmento da cadeia (fim anterior → alvo), como continuação da stroke
     * corrente. `willContinue=false` encerra a cadeia e limpa o estado. Chamar SOB [lock]. */
    private fun dispatchNextSegment(x: Float, y: Float, willContinue: Boolean, label: String) {
        val stroke = currentDragStroke ?: return
        val displayId = dragDisplayId
        val (endX, endY) = segmentEnd(x, y)
        val path = Path().apply { moveTo(dragLastX, dragLastY); lineTo(endX, endY) }
        val next = stroke.continueStroke(path, 0L, DRAG_SEGMENT_DURATION_MS, willContinue)
        dragLastX = endX
        dragLastY = endY
        if (willContinue) {
            currentDragStroke = next
            strokeInFlight = true
        } else {
            // Cadeia encerrada: estado limpo JÁ — callbacks da stroke final são só instrumentação.
            currentDragStroke = null
            dragDisplayId = -1
            strokeInFlight = false
            pendingTarget = null
            pendingEnd = null
        }
        dispatchDragStroke(next, displayId, label, willContinue, generation = dragGeneration)
    }

    /** Extremidade do próximo segmento a partir de (dragLastX,dragLastY). Se o deslocamento for
     * menor que [DRAG_MIN_SEGMENT_PX] (mão parada), aplica um empurrão de [DRAG_MIN_SEGMENT_PX]
     * alternado no sinal — mantém o segmento não-degenerado com deriva líquida ~0. */
    private fun segmentEnd(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - dragLastX
        val dy = y - dragLastY
        if (dx * dx + dy * dy >= DRAG_MIN_SEGMENT_PX * DRAG_MIN_SEGMENT_PX) return x to y
        dragNudgeSign = -dragNudgeSign
        return (x + DRAG_MIN_SEGMENT_PX * dragNudgeSign) to y
    }

    /**
     * Despacha uma stroke de drag. O `onCompleted` é o MOTOR da cadeia: baixa [strokeInFlight] e
     * despacha o que estiver pendente ([pendingEnd] tem prioridade sobre [pendingTarget]).
     * Callbacks chegam na main thread; [generation] descarta callbacks de cadeia substituída.
     * `onCancelled` de uma stroke `willContinue=true` significa cadeia morta (o sistema descartou
     * a continuação) — reseta o estado pra não deixar drag fantasma.
     */
    private fun dispatchDragStroke(
        stroke: GestureDescription.StrokeDescription,
        displayId: Int,
        label: String,
        willContinue: Boolean,
        generation: Int,
    ) {
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(stroke)
            .build()
        val seq = ++dragStrokeSeq
        val dispatchAt = android.os.SystemClock.uptimeMillis()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val dt = android.os.SystemClock.uptimeMillis() - dispatchAt
                Log.d(TAG, "$label #$seq: COMPLETED após ${dt}ms (willContinue=$willContinue) display=$displayId")
                synchronized(lock) {
                    if (generation != dragGeneration || !willContinue) return
                    strokeInFlight = false
                    val end = pendingEnd
                    val target = pendingTarget
                    pendingTarget = null
                    if (end != null) {
                        dispatchNextSegment(end.first, end.second, willContinue = false, label = "endDrag")
                    } else if (target != null) {
                        dispatchNextSegment(target.first, target.second, willContinue = true, label = "updateDrag")
                    }
                    // Sem pendências: fica parado com o ponteiro pressionado — o próximo
                    // updateDrag (frames chegam a ~30fps) despacha direto por !strokeInFlight.
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                val dt = android.os.SystemClock.uptimeMillis() - dispatchAt
                Log.w(TAG, "$label #$seq: CANCELLED após ${dt}ms (willContinue=$willContinue) display=$displayId")
                synchronized(lock) {
                    if (generation != dragGeneration || !willContinue) return
                    // Cadeia morta — o sistema não vai aceitar continuação de stroke cancelada.
                    Log.w(TAG, "cadeia de drag cancelada pelo sistema — resetando estado do drag")
                    currentDragStroke = null
                    dragDisplayId = -1
                    strokeInFlight = false
                    pendingTarget = null
                    pendingEnd = null
                }
            }
        }, null)
        if (!accepted) {
            Log.w(TAG, "$label #$seq: dispatchGesture recusou a stroke (display=$displayId)")
        }
    }
}
