package com.raphael.handmouse.tracking

import android.graphics.Bitmap
import android.util.Log
import com.raphael.handmouse.input.GestureInjector
import com.raphael.handmouse.input.VoiceCommandController
import com.raphael.handmouse.overlay.CursorOverlay
import com.raphael.handmouse.service.EyeCaptureService
import com.raphael.handmouse.util.RadialDeadZone

/**
 * Orquestração da Tarefa 4/5 (decisão documentada aqui, conforme pedido pelo brief): landmark de
 * referência → [RelativeCursorMapper] (modo relativo/trackpad, 2026-07-23 — substituiu o
 * mapeamento absoluto calibrado) → [OneEuroFilter] (x/y, freq dos timestamps reais do frame) →
 * [RadialDeadZone] → [CursorOverlay.moveTo]. Em paralelo, [PinchDetector] alimenta
 * [ClickDragStateMachine] (clique-vs-drag) → [GestureInjector] (injeção real no display do DeX —
 * Tarefa 5; até a T4 só mudava cor/escala do cursor).
 *
 * ## Por que uma classe própria (e não dentro do service)
 * Implementa [EyeCaptureService.TrackingListener] diretamente: assim ela se registra/remove do
 * FGS de captura como QUALQUER outro listener (mesma interface que a `MainActivity` já usa pro
 * HUD de debug), sem precisar que `EyeCaptureService` saiba nada sobre cursor/overlay/a11y —
 * mantém o Task 2/3 desacoplado da Tarefa 4. Fica em `tracking/` (não em `overlay/` nem
 * `service/`) porque sua responsabilidade é matemática de pipeline (mapear → filtrar → decidir
 * se emite), não desenho nem ciclo de vida de janela — só a chamada final a [CursorOverlay] e a
 * [GestureInjector] cruzam essa fronteira.
 *
 * ## Handshake com o `HandMouseAccessibilityService` (dono desta instância) e o `EyeCaptureService`
 * Ver Javadoc de [EyeCaptureService.onCreate] e de
 * [com.raphael.handmouse.service.HandMouseAccessibilityService.onServiceConnected] — os dois
 * singletons podem iniciar em qualquer ordem; cada um, ao (re)iniciar, verifica se o outro já
 * existe e faz o registro que faltar. Se o a11y service nunca chegar a ficar ativo, este
 * pipeline nunca é registrado: `EyeCaptureService` continua processando frames normalmente
 * (custo de CPU perdido, mas inofensivo), e a `MainActivity` mostra esse estado claramente
 * (status do a11y service + atalho pra Configurações) para o usuário corrigir.
 *
 * ## Bounds do display
 * [updateBounds] deve ser chamado pelo dono sempre que o display do DeX aparecer/mudar/sumir
 * (`null` = sumiu). Enquanto `bounds` for `null`, [onHandResult] ignora os resultados (não há
 * pra onde mapear o cursor, nem em qual `displayId` injetar).
 *
 * ## Congelamento de posição no clique/drag (Tarefa 5 — PLANO.md §6.3-nota)
 * [positionHistory] é alimentada a cada frame com a posição JÁ FILTRADA (pós-[OneEuroFilter],
 * PRÉ dead-zone) — deliberadamente ANTES da dead-zone, não depois: a dead-zone suprime frames
 * quando a mão está parada (exatamente a situação típica de um hold pra clique/drag), e usar a
 * versão pós-dead-zone faria o histórico "travar" sem novas amostras bem na janela de 100ms que
 * mais importa. A diferença entre as duas versões é no máximo o raio da dead-zone (2.5px,
 * default) — irrelevante pra essa finalidade. O mesmo valor pré-dead-zone alimenta
 * [clickDragStateMachine] (via [PinchEvent]/`positionUpdate`) pelo mesmo motivo: o limiar de
 * 300ms precisa de "ticks" a 30fps mesmo com a mão parada, senão a transição pra drag ficaria
 * refém do próximo movimento perceptível.
 *
 * ## Congelamento VISUAL do cursor — REMOVIDO (2026-07-24)
 * A injeção (`GestureInjector`) usa a posição do pinch-down pro `Click`/`DragStart` (ver seção
 * acima) — e entre 2026-07-23 e 2026-07-24 o cursor VISÍVEL também ficava travado nessa âncora
 * durante [ClickDragStateMachine.Phase.PRESSED] (via um `CursorRenderDecision`, removido junto),
 * pra fechar a divergência "vejo o cursor num lugar, o clique cai no outro". Aquilo fazia
 * sentido com lookback de 80-180ms; com o lookback ZERADO ([CLICK_FREEZE_LOOKBACK_MS] = 0) a
 * âncora É a posição ao vivo do instante do pinch-down, e a divergência residual é só o quanto a
 * mão anda DURANTE o próprio gesto (~200-400ms) — pequena demais pra pagar a "congelada"
 * perceptível a cada clique (feedback do usuário, 2026-07-24). [renderCursor] agora segue a mão
 * ao vivo em TODAS as fases, sempre via dead-zone. Se o lookback voltar a subir um dia, o freeze
 * visual deve voltar junto (git: procurar CursorRenderDecision).
 *
 * ## Drag órfão ao perder a mão (fix de revisão, achado Important I3b)
 * Antes deste fix, [onHandLost] resetava [clickDragStateMachine] (`phase` volta a `IDLE`) mas
 * NUNCA avisava o [gestureInjector] — se a mão sumisse no meio de um `Phase.DRAGGING` (ex.: mão
 * sai do campo de visão da câmera Eye enquanto arrasta), `GestureInjector.currentDragStroke`
 * ficava pendurado: nem `endDrag` nem `updateDrag` seriam chamados de novo (o pinch já não
 * existe mais pro `PinchDetector` reportar `UP`), então o sistema do DeX ficava com um stroke
 * tecnicamente "em progresso" (`willContinue=true` na última chamada) até expirar sozinho por
 * timeout do próprio `dispatchGesture`. [onHandLost] agora encerra explicitamente o drag
 * (reusa `GestureInjector.endDrag` — do ponto de vista do injetor, "cancelar" e "encerrar" são a
 * mesma operação: última stroke com `willContinue=false` na última posição ao vivo conhecida;
 * não há um conceito de "abortar sem soltar" no `dispatchGesture`) ANTES de resetar a máquina de
 * estados — a ordem importa, porque `clickDragStateMachine.reset()` apaga a fase que usamos pra
 * decidir se havia um drag ativo.
 *
 * ## Supressão de injeção (fix de revisão, achado Important I4)
 * O wizard de calibração de 2 pontos da `MainActivity` usa o MESMO [CursorPipeline] de produção
 * (não uma cópia) pra detectar o pinch nos cantos da tela — mas antes deste fix, cada pinch
 * durante o wizard também disparava um `tap`/drag REAL no DeX (o usuário via um clique
 * indesejado aparecer sob o cursor a cada ponto calibrado). [setInjectionSuppressed] permite ao
 * dono (`HandMouseAccessibilityService`, chamado pela `MainActivity` via esse singleton — ver
 * `HandMouseAccessibilityService.setInjectionSuppressed`) pausar só a chamada a
 * [dispatchActions] (clique/drag real E o pulso visual de clique confirmado, que não faz
 * sentido sem um clique real) — [renderCursor] continua rodando incondicionalmente, então o
 * cursor visível continua seguindo a mão normalmente durante o wizard (só a INJEÇÃO pausa). A
 * flag é `@Volatile` (escrita na main thread pela `MainActivity`/`HandMouseAccessibilityService`,
 * leitura na `HandlerThread` do `HandTracker` dentro de [onHandResult]).
 */
class CursorPipeline(
    private val overlay: CursorOverlay,
    private val gestureInjector: GestureInjector,
    private val relativeMapper: RelativeCursorMapper = RelativeCursorMapper(),
    private val palmReference: PalmCursorReference = PalmCursorReference(),
    private val oneEuroX: OneEuroFilter = OneEuroFilter(),
    private val oneEuroY: OneEuroFilter = OneEuroFilter(),
    private val deadZone: RadialDeadZone = RadialDeadZone(),
    private val pinchDetector: PinchDetector = PinchDetector(),
    private val fistDetector: FistDetector = FistDetector(),
    private val thumbsUpDetector: ThumbsUpDetector = ThumbsUpDetector(),
    private val vSignDetector: VSignDetector = VSignDetector(),
    private val positionHistory: PositionHistory = PositionHistory(),
    private val clickDragStateMachine: ClickDragStateMachine = ClickDragStateMachine(),
    // Eye Tools fork: three-way menu (back / home / close app).
    private val layeredMenu: LayeredMenuTracker = LayeredMenuTracker(smoothingAlpha = 0.18f, selectionFrames = 3),
    // Controle por voz (2026-07-23): sinal de "V" segurado abre a escuta. Nullable com default
    // null pra não obrigar testes/usos antigos a construir dependências Android.
    private val voiceController: com.raphael.handmouse.input.VoiceCommandController? = null,
) : EyeCaptureService.TrackingListener {

    companion object {
        private const val TAG = "CursorPipeline"

        /** Use a pre-gesture position so closing fingers does not move the click target. */
        private const val CLICK_FREEZE_LOOKBACK_MS = 120L

        /** Punho fechado segurado por este tempo recentraliza o cursor (2026-07-23, pedido do
         * usuário — o modo relativo perde a correspondência mão↔centro com o tempo; ver
         * [FistDetector] e [RelativeCursorMapper.recenter]). */
        private const val FIST_RECENTER_HOLD_MS = 2000L

        /** Thumbs-up segurado por este tempo alterna o MUTE do cursor (2026-07-23, pedido do
         * usuário: "quando abaixo os braços o cursor fica perdido e visível" — ver
         * [ThumbsUpDetector] e a spec `2026-07-23-cursor-mute-gesture-design.md`). */
        private const val THUMBS_UP_TOGGLE_HOLD_MS = 1000L

        /** Sinal de "V" segurado por este tempo abre a escuta de voz (2026-07-23, spec
         * voice-control). Curto de propósito (o debounce de ~200ms do detector já filtra
         * transitórios): o gesto deve responder rápido. */
        private const val V_SIGN_HOLD_MS = 500L
    }

    /** Runs menu actions (implemented by the accessibility service). */
    fun interface MenuActionHandler {
        fun onMenuAction(action: MenuAction, x: Float, y: Float, display: DisplayBounds): Boolean
    }

    var menuActionHandler: MenuActionHandler? = null

    fun interface ClickTargetResolver {
        fun resolve(x: Float, y: Float, display: DisplayBounds): CursorPoint?
    }

    var clickTargetResolver: ClickTargetResolver? = null

    // ---- Eye Tools fork: user settings (applied on the main thread, same as onHandResult) ----
    private var settings = HandSettings()

    /** Applies the settings screen values to every pipeline component (live, no restart). */
    fun applySettings(newSettings: HandSettings) {
        // Switching between world/normalized ratios must not mix pinch EMAs; any other change
        // leaves a pinch/drag in progress alone.
        if (newSettings.worldPinch != settings.worldPinch) onHandLost()
        settings = newSettings
        relativeMapper.spanX = newSettings.spanX
        oneEuroX.minCutoff = newSettings.minCutoff
        oneEuroY.minCutoff = newSettings.minCutoff
        deadZone.radiusPx = newSettings.deadZonePx
        pinchDetector.enterThreshold = newSettings.pinchEnter
        pinchDetector.exitThreshold = newSettings.pinchEnter + HandSettings.PINCH_HYSTERESIS
        pinchDetector.downDebounceFrames = newSettings.downDebounceFrames
        clickDragStateMachine.holdThresholdMs = newSettings.holdThresholdMs
        clickDragStateMachine.longPinchMenuEnabled = newSettings.palmMenu
        layeredMenu.step = newSettings.layerStep
        overlay.setDebugEnabled(newSettings.debugOverlay)
        // Disabling the mute gesture while muted would strand the cursor hidden forever.
        if (!newSettings.thumbsUpMute && cursorMuted) toggleCursorMuted()
        Log.d(TAG, "Settings applied: $newSettings")
    }

    @Volatile
    private var bounds: DisplayBounds? = null

    // Última posição AO VIVO (pós-filtro, pré-dead-zone) conhecida — usada só por onHandLost
    // (achado I3b) pra encerrar um drag em andamento na posição mais recente da mão, já que
    // ClickDragStateMachine não expõe lastX/lastY (são privados de propósito, ver Javadoc dela).
    private var lastLivePosition: Pair<Float, Float> = 0f to 0f

    // Ver "Supressão de injeção" no Javadoc da classe (achado I4).
    @Volatile
    private var injectionSuppressed = false

    // Cronometragem do gesto de punho (recentralização) — ver [updateFistState].
    private var fistHoldStartMs: Long? = null
    private var fistRecenterFired = false
    private var fistReleasePending = false
    private var menuAwaitRelease = false
    private var menuOpenedAtMs = 0L
    // Drag uses its own, stronger filter. Cursor tuning should not change the held pointer path.
    private val dragXFilter = OneEuroFilter(minCutoff = 0.4f, beta = 0.003f)
    private val dragYFilter = OneEuroFilter(minCutoff = 0.4f, beta = 0.003f)
    private var filteredDragPosition: Pair<Float, Float>? = null
    private var lastClickTarget: CursorPoint? = null

    // Mute do cursor por thumbs-up (2026-07-23) — ver [updateThumbsUpState]. cursorMuted
    // persiste até o gesto explícito de religar (onHandLost NÃO desfaz — é exatamente o cenário
    // "abaixei os braços" que motivou o gesto).
    private var cursorMuted = false
    private var thumbsUpHoldStartMs: Long? = null
    private var thumbsUpToggleFired = false

    // Cronometragem do sinal de "V" (escuta de voz) — ver [updateVSignState].
    private var vSignHoldStartMs: Long? = null
    private var vSignFired = false
    private var lastLoggedVSign = false

    // Diagnóstico (5ª rodada 2026-07-23, "cursor trava e não sei se é gesto ou câmera"): loga
    // SÓ as transições de estado de palma/punho — barato (nada por frame) e permite correlacionar
    // cada travada/reset do cursor com o gesto que o causou (ou inocentar os gestos e culpar a
    // câmera) direto no logcat.
    private var lastLoggedFist = false
    private var lastLoggedThumbsUp = false

    /** Chamado pelo dono (via [com.raphael.handmouse.overlay.DexDisplayMonitor.Listener])
     * sempre que o display do DeX muda; `null` quando o display some. */
    fun updateBounds(newBounds: DisplayBounds?) {
        bounds = newBounds
    }

    /** Ver "Supressão de injeção" no Javadoc da classe (achado I4) — chamado pelo
     * `HandMouseAccessibilityService` (reachable a partir da `MainActivity` via esse singleton)
     * quando o wizard de calibração começa/termina. `true` = cursor continua se movendo, mas
     * clique/drag reais e o pulso de "clique confirmado" ficam pausados. */
    fun setInjectionSuppressed(suppressed: Boolean) {
        injectionSuppressed = suppressed
    }

    override fun onFrameConverted(bitmap: Bitmap, conversionMs: Double) {
        // Não usado pelo pipeline de cursor — só o HUD de debug da Activity precisa do bitmap.
    }

    override fun onHandResult(result: HandTracker.Result) {
        val currentBounds = bounds ?: return // sem display do DeX -> nada a mover/injetar

        val points = result.points

        // Pinch ratios from metric world landmarks when available (robust to hand rotation —
        // normalized-image z is a rough estimate), else from the normalized landmarks.
        val pinchPoints = if (settings.worldPinch && result.worldLandmarks.size == 21) result.worldLandmarks else points

        // ---- Mute por thumbs-up (2026-07-23): PRIMEIRO de tudo, e único caminho vivo quando
        // mutado ----
        // O detector é alimentado TODO frame; segurar o gesto THUMBS_UP_TOGGLE_HOLD_MS com a
        // máquina de clique em IDLE alterna o mute (1x por hold). Mutado, o frame acaba aqui:
        // zero palma/pinch/punho/movimento/injeção — o pipeline vira só o "ouvido" pro gesto de
        // religar. Estados dos outros detectores ficam obsoletos de propósito (o unmute reseta
        // tudo — ver [toggleCursorMuted]).
        if (settings.thumbsUpMute) updateThumbsUpState(points, result.timestampMs)
        if (cursorMuted) {
            showDebug(points, result, null, "MUTED")
            return
        }

        // ---- Controle por voz (2026-07-23): "V" segurado V_SIGN_HOLD_MS abre a escuta ----
        // Durante a escuta o frame acaba aqui: âncora solta (sem salto na volta — mesmo clutch
        // do modo palma), pinch suprimido, cursor parado. A mão fica livre pra ficar à vontade
        // enquanto o usuário FALA (inclusive abaixá-la — a escuta não depende mais do gesto).
        if (settings.vSignVoice) updateVSignState(points, result.timestampMs)
        if (voiceController?.isListening == true) {
            relativeMapper.onHandLost()
            palmReference.reset()
            pinchDetector.reset()
            overlay.setPinched(false)
            showDebug(points, result, null, "VOICE")
            return
        }

        // A stationary long pinch opens the menu. While it is open, only the displacement from
        // its opening position matters; the normal cursor mapping is paused.
        if (layeredMenu.isActive) {
            val event = pinchDetector.update(pinchPoints)
            if (event == PinchEvent.UP && menuAwaitRelease) {
                menuAwaitRelease = false
                clickDragStateMachine.pinchUp(result.timestampMs)
            }
            val fist = fistDetector.update(points)
            if (fist || result.timestampMs - menuOpenedAtMs > 8_000L) {
                if (fist) {
                    lastLoggedFist = true
                    fistReleasePending = true
                }
                layeredMenu.reset()
                overlay.hidePalmMenu()
                clickDragStateMachine.reset()
            } else {
                val confirm = !menuAwaitRelease && event == PinchEvent.DOWN
                handleMenuFrame(layeredMenu.onPalmOpen(points[5].x, points[5].y, confirm), currentBounds)
            }
            relativeMapper.onHandLost()
            palmReference.reset()
            overlay.setPinched(false)
            showDebug(points, result, null, "MENU")
            return
        }

        val ref = palmReference.update(points, result.timestampMs)
        val mapped = relativeMapper.map(ref.x, ref.y, currentBounds)
        val fx = oneEuroX.filter(mapped.x, result.timestampMs)
        val fy = oneEuroY.filter(mapped.y, result.timestampMs)
        lastLivePosition = fx to fy // achado I3b — ver Javadoc da classe ("Drag órfão")

        // Ver Javadoc da classe ("Congelamento de posição") sobre por que isto roda ANTES da
        // dead-zone e é usado tanto pro clique/drag quanto pro histórico de congelamento.
        positionHistory.record(fx, fy, result.timestampMs)

        // Punho fechado: suprime o pinch (num punho o polegar encosta no indicador e o
        // PinchDetector confundiria com clique) e, segurado FIST_RECENTER_HOLD_MS, recentraliza.
        val fistSuppressesPinch = updateFistState(points, result.timestampMs, currentBounds)
        if (!fistSuppressesPinch && fistReleasePending) {
            if (PinchDetector.ratio(pinchPoints) > pinchDetector.exitThreshold + 0.08f) {
                fistReleasePending = false
            } else {
                pinchDetector.reset()
            }
        }

        val pinchEvent = when {
            fistSuppressesPinch || fistReleasePending -> null
            else -> pinchDetector.update(pinchPoints)
        }
        overlay.setPinched(!fistSuppressesPinch && pinchDetector.isPinched)

        val actions = when (pinchEvent) {
            PinchEvent.DOWN -> {
                val frozen = positionHistory.positionAt(result.timestampMs - CLICK_FREEZE_LOOKBACK_MS)
                val frozenX = frozen?.x ?: fx
                val frozenY = frozen?.y ?: fy
                clickDragStateMachine.pinchDown(frozenX, frozenY, result.timestampMs)
            }
            PinchEvent.UP -> clickDragStateMachine.pinchUp(result.timestampMs)
            null -> clickDragStateMachine.positionUpdate(fx, fy, result.timestampMs)
        }
        // Ver "Supressão de injeção" no Javadoc da classe (achado I4): a máquina de estados e o
        // overlay (setPinched acima, moveTo abaixo) continuam rodando incondicionalmente durante
        // a supressão — só o clique/drag REAL (e o pulso de clique confirmado) pausa.
        if (!injectionSuppressed) {
            dispatchActions(actions, currentBounds, points[5], result.timestampMs)
        }

        val visiblePosition = filteredDragPosition ?: (fx to fy)
        renderCursor(visiblePosition.first, visiblePosition.second)
        showDebug(points, result, visiblePosition, clickDragStateMachine.phase.name)
    }

    /**
     * Gesto de thumbs-up (2026-07-23, spec `2026-07-23-cursor-mute-gesture-design.md`): segurar
     * [THUMBS_UP_TOGGLE_HOLD_MS] alterna [cursorMuted]. Regras:
     * - Só alterna com a máquina de clique em IDLE (nunca no meio de um clique/drag) — exceto
     *   quando JÁ mutado (mutado, a máquina está garantidamente resetada).
     * - Dispara UMA vez por hold ([thumbsUpToggleFired]); soltar o gesto rearma.
     * - Transições de estado do detector são logadas (mesmo padrão palma/punho).
     */
    private fun updateThumbsUpState(points: List<HandPoint>, timestampMs: Long) {
        val active = thumbsUpDetector.update(points)
        if (active != lastLoggedThumbsUp) {
            lastLoggedThumbsUp = active
            Log.d(TAG, "Thumbs-up ${if (active) "ATIVO (segure ${THUMBS_UP_TOGGLE_HOLD_MS}ms pra alternar o mute)" else "inativo"}")
        }

        if (!active || (!cursorMuted && clickDragStateMachine.phase != ClickDragStateMachine.Phase.IDLE) ||
            voiceController?.isListening == true // fix de revisão final 2026-07-23: um "joia" casual segurado enquanto o usuário FALA não pode mutar o cursor no meio da sessão de voz
        ) {
            thumbsUpHoldStartMs = null
            thumbsUpToggleFired = false
            return
        }

        val start = thumbsUpHoldStartMs ?: timestampMs.also { thumbsUpHoldStartMs = it }
        if (!thumbsUpToggleFired && timestampMs - start >= THUMBS_UP_TOGGLE_HOLD_MS) {
            thumbsUpToggleFired = true
            toggleCursorMuted()
        }
    }

    /** Alterna o mute (ver [updateThumbsUpState]). Mutar esconde o cursor NA HORA e silencia
     * tudo; desmutar faz reset geral + recentraliza (a correspondência mão↔tela se perdeu com
     * os braços abaixados — voltar no centro é o previsível). */
    private fun toggleCursorMuted() {
        cursorMuted = !cursorMuted
        if (cursorMuted) {
            overlay.setPinched(false)
            overlay.setHiddenByUser(true)
            Log.d(TAG, "Cursor MUTADO (thumbs-up ${THUMBS_UP_TOGGLE_HOLD_MS}ms) — thumbs-up de novo religa")
        } else {
            // Reset geral: nenhum estado acumulado durante o mute (EMAs velhos, histórico,
            // âncoras) pode contaminar a volta — e o cursor renasce no CENTRO.
            relativeMapper.recenter()
            palmReference.reset()
            oneEuroX.reset()
            oneEuroY.reset()
            deadZone.reset()
            pinchDetector.reset()
            fistDetector.reset()
            fistReleasePending = false
            lastLoggedFist = false
            positionHistory.reset()
            clickDragStateMachine.reset()
            lastLivePosition = 0f to 0f
            fistHoldStartMs = null
            fistRecenterFired = false
            overlay.setHiddenByUser(false)
            Log.d(TAG, "Cursor REATIVADO (thumbs-up) — recentralizado")
        }
    }

    /**
     * Gesto de punho fechado (2026-07-23): segurar [FIST_RECENTER_HOLD_MS] recentraliza o cursor
     * ([RelativeCursorMapper.recenter] — filtros/dead-zone resetados junto pra o salto ao centro
     * ser instantâneo, não um deslize suavizado). Retorna `true` quando o punho está confirmado e
     * o processamento de PINCH deste frame deve ser suprimido — num punho real o polegar encosta
     * nas pontas dos dedos e o [PinchDetector] leria um "clique". Regras por fase:
     * - `DRAGGING`: punho NÃO interfere (um pinch-drag genuíno curva os dedos o suficiente pra
     *   ocasionalmente parecer punho — abortar um drag em andamento seria pior que não
     *   recentralizar; o gesto de recentralizar só vale com a mão "livre").
     * - `PRESSED`: um punho confirmado engoliu um pinch fantasma no caminho — aborta o clique
     *   pendente (reset da máquina) em vez de deixá-lo virar clique/drag ao soltar o punho.
     * - `IDLE`: caminho normal do gesto.
     * O trigger dispara UMA vez por hold ([fistRecenterFired]) — segurar além de 2s não fica
     * recentralizando em loop; soltar o punho rearma.
     */
    private fun updateFistState(points: List<HandPoint>, timestampMs: Long, display: DisplayBounds): Boolean {
        val isFist = fistDetector.update(points)
        val justClosed = isFist && !lastLoggedFist
        if (isFist != lastLoggedFist) {
            lastLoggedFist = isFist
            Log.d(TAG, "Punho ${if (isFist) "ATIVO — pinch suprimido (2s segura = recentraliza)" else "inativo"}")
        }
        if (!isFist) {
            fistHoldStartMs = null
            fistRecenterFired = false
            return false
        }

        val phaseBeforeFist = clickDragStateMachine.phase
        when (phaseBeforeFist) {
            ClickDragStateMachine.Phase.DRAGGING -> return false
            ClickDragStateMachine.Phase.PRESSED -> {
                Log.d(TAG, "Punho confirmado durante PRESSED — abortando pinch fantasma")
                clickDragStateMachine.reset()
            }
            ClickDragStateMachine.Phase.IDLE -> {}
            ClickDragStateMachine.Phase.MENU_OPEN -> clickDragStateMachine.reset()
        }
        pinchDetector.reset() // punho ativo: nenhum estado de pinch sobrevive (ver Javadoc)
        fistReleasePending = true

        if (justClosed && !injectionSuppressed && phaseBeforeFist != ClickDragStateMachine.Phase.MENU_OPEN) {
            val anchor = positionHistory.positionAt(timestampMs - 180L)
            val (x, y) = anchor?.let { it.x to it.y } ?: lastLivePosition
            injectClick(x, y, display)
        }

        val start = fistHoldStartMs ?: timestampMs.also { fistHoldStartMs = it }
        if (settings.fistRecenter && !fistRecenterFired && timestampMs - start >= FIST_RECENTER_HOLD_MS) {
            fistRecenterFired = true
            relativeMapper.recenter()
            palmReference.reset()
            oneEuroX.reset()
            oneEuroY.reset()
            deadZone.reset()
            positionHistory.reset()
            Log.d(TAG, "Punho segurado ${FIST_RECENTER_HOLD_MS}ms — cursor recentralizado")
        }
        return true
    }

    /**
     * Gesto de sinal de "V" (2026-07-23, spec voice-control): segurar [V_SIGN_HOLD_MS] abre a
     * janela de escuta do [voiceController]. Regras (mesmo molde do punho/thumbs-up):
     * - Só com a máquina de clique em IDLE (nunca no meio de clique/drag).
     * - Dispara UMA vez por hold ([vSignFired]); soltar o "V" rearma.
     * - Suprimido com injectionSuppressed (wizard de calibração) e sem bounds (sem display).
     * - Transições logadas (mesmo padrão palma/punho/thumbs-up).
     */
    private fun updateVSignState(points: List<HandPoint>, timestampMs: Long) {
        val active = vSignDetector.update(points)
        if (active != lastLoggedVSign) {
            lastLoggedVSign = active
            Log.d(TAG, "Sinal de V ${if (active) "ATIVO (segure ${V_SIGN_HOLD_MS}ms pra falar)" else "inativo"}")
        }

        if (!active || clickDragStateMachine.phase != ClickDragStateMachine.Phase.IDLE) {
            vSignHoldStartMs = null
            vSignFired = false
            return
        }

        val start = vSignHoldStartMs ?: timestampMs.also { vSignHoldStartMs = it }
        if (!vSignFired && timestampMs - start >= V_SIGN_HOLD_MS) {
            vSignFired = true
            val currentBounds = bounds
            if (injectionSuppressed || currentBounds == null) return
            Log.d(TAG, "V segurado ${V_SIGN_HOLD_MS}ms — abrindo escuta de voz")
            voiceController?.startListening(currentBounds.displayId)
        }
    }

    /** Cursor visível segue a mão AO VIVO em todas as fases, sempre via dead-zone — ver
     * "Congelamento VISUAL do cursor — REMOVIDO" no Javadoc da classe (histórico completo do
     * vai-e-vem dessa decisão). A âncora do pinch-down continua valendo pra INJEÇÃO do
     * clique/drag ([clickDragStateMachine]) — só a renderização deixou de congelar. */
    private fun renderCursor(fx: Float, fy: Float) {
        val emitted = deadZone.filter(fx, fy) ?: return
        overlay.moveTo(emitted.first, emitted.second)
    }

    private fun handleMenuFrame(f: LayeredMenuTracker.Frame, b: DisplayBounds) {
        if (f.visible) overlay.showLayeredMenu(f.selected) else overlay.hidePalmMenu()
        val action = f.fired ?: return
        layeredMenu.reset()
        if (injectionSuppressed) return
        val (x, y) = overlay.cursorPosition ?: (b.width / 2f to b.height / 2f)
        Log.d(TAG, "Long pinch menu -> $action at ($x, $y)")
        val ok = menuActionHandler?.onMenuAction(action, x, y, b) ?: false
        if (ok) overlay.pulseClick() else overlay.flashVoiceResult(false)
    }

    override fun onHandLost() {
        layeredMenu.reset()
        overlay.hidePalmMenu()
        // Achado I3b — ver "Drag órfão ao perder a mão" no Javadoc da classe: encerra um drag em
        // andamento ANTES de resetar clickDragStateMachine (reset() apaga phase, precisamos ler
        // ANTES). Sem injectionSuppressed checado aqui de propósito — se a mão some no meio de um
        // drag real (não durante o wizard, que não deixa a fase chegar a DRAGGING em cenários
        // normais de uso), o drag É real e precisa ser encerrado no sistema independentemente.
        if (clickDragStateMachine.phase == ClickDragStateMachine.Phase.DRAGGING) {
            val (x, y) = lastLivePosition
            Log.w(TAG, "onHandLost durante DRAGGING — encerrando drag em ($x, $y)")
            gestureInjector.endDrag(x, y)
        }
        dragXFilter.reset()
        dragYFilter.reset()
        filteredDragPosition = null
        lastClickTarget = null

        // Mão sumiu: reseta todo o estado transitório para não contaminar a próxima aparição
        // (salto suavizado com o "ar" no OneEuroFilter, dead-zone presa numa posição velha,
        // EMA/debounce velhos no PinchDetector, histórico de posição/estado de clique-drag
        // "velhos" no PositionHistory/ClickDragStateMachine — T5). O overlay some sozinho por
        // fade após 2s sem moveTo (ver CursorOverlay) — não precisa ser escondido explicitamente
        // aqui.
        relativeMapper.onHandLost() // modo relativo: solta a âncora (reaparição NÃO teleporta)
        palmReference.reset()
        oneEuroX.reset()
        oneEuroY.reset()
        deadZone.reset()
        pinchDetector.reset()
        fistDetector.reset()
        positionHistory.reset()
        clickDragStateMachine.reset()
        lastLivePosition = 0f to 0f
        fistHoldStartMs = null
        fistRecenterFired = false
        lastLoggedFist = false
        fistReleasePending = false
        menuAwaitRelease = false
        // Mute por thumbs-up: o DETECTOR/hold resetam (pose não sobrevive à perda da mão), mas
        // cursorMuted PERSISTE de propósito — "abaixei os braços" (mão some) é exatamente o
        // cenário que o mute existe pra cobrir; só o gesto explícito religa.
        thumbsUpDetector.reset()
        thumbsUpHoldStartMs = null
        thumbsUpToggleFired = false
        // Voz: o DETECTOR/hold resetam (pose não sobrevive à perda da mão), mas uma ESCUTA em
        // andamento continua de propósito — abaixar a mão enquanto fala é o fluxo natural.
        vSignDetector.reset()
        vSignHoldStartMs = null
        vSignFired = false
        if (settings.debugOverlay) overlay.updateHandDebug(emptyList(), listOf("Hand: LOST", "Tracking interrupted"))
    }

    override fun onTrackingError(message: String) {
        // Já logado pelo EyeCaptureService (Log.e); nada específico do cursor a fazer aqui.
    }

    private fun dispatchActions(
        actions: List<ClickDragStateMachine.Action>,
        display: DisplayBounds,
        hand: HandPoint,
        timestampMs: Long,
    ) {
        for (action in actions) {
            when (action) {
                is ClickDragStateMachine.Action.Click -> {
                    injectClick(action.x, action.y, display)
                }
                is ClickDragStateMachine.Action.DragStart -> {
                    dragXFilter.reset()
                    dragYFilter.reset()
                    dragXFilter.filter(action.x, timestampMs)
                    dragYFilter.filter(action.y, timestampMs)
                    filteredDragPosition = action.x to action.y
                    gestureInjector.beginDrag(action.x, action.y, display.displayId)
                }
                is ClickDragStateMachine.Action.DragMove -> {
                    val x = dragXFilter.filter(action.x, timestampMs)
                    val y = dragYFilter.filter(action.y, timestampMs)
                    filteredDragPosition = x to y
                    gestureInjector.updateDrag(
                        x, y,
                    )
                }
                is ClickDragStateMachine.Action.DragEnd -> {
                    // Finish at the real hand position so smoothing does not leave a drop short.
                    gestureInjector.endDrag(action.x, action.y)
                    dragXFilter.reset()
                    dragYFilter.reset()
                    filteredDragPosition = null
                }
                is ClickDragStateMachine.Action.OpenMenu -> {
                    menuAwaitRelease = pinchDetector.isPinched
                    menuOpenedAtMs = timestampMs
                    handleMenuFrame(layeredMenu.onPalmOpen(hand.x, hand.y, false), display)
                }
            }
        }
    }

    private fun injectClick(x: Float, y: Float, display: DisplayBounds) {
        val target = if (settings.magneticClick) clickTargetResolver?.resolve(x, y, display) else null
        lastClickTarget = target
        val tx = target?.x ?: x
        val ty = target?.y ?: y
        Log.d(TAG, "Click (${x}, ${y}) -> (${tx}, ${ty}) display=${display.displayId}")
        gestureInjector.tap(tx, ty, display.displayId)
        overlay.pulseClick()
    }

    private fun showDebug(
        points: List<HandPoint>,
        result: HandTracker.Result,
        cursor: Pair<Float, Float>?,
        state: String,
    ) {
        if (!settings.debugOverlay) return
        val ref = if (points.size >= 18) listOf(5, 9, 13, 17) else emptyList()
        val palmX = if (ref.isEmpty()) 0f else ref.map { points[it].x }.average().toFloat()
        val palmY = if (ref.isEmpty()) 0f else ref.map { points[it].y }.average().toFloat()
        overlay.updateHandDebug(points, listOf(
            "HAND ${"%.0f".format(result.inferenceFps)} fps",
            "pinch ${pinchDetector.isPinched} (%.2f)".format(PinchDetector.ratio(points)),
            "fist ${fistDetector.isFist}",
            "state $state",
            "palm %.3f / %.3f".format(palmX, palmY),
            cursor?.let { "cursor %.0f / %.0f".format(it.first, it.second) } ?: "cursor held",
            lastClickTarget?.let { "snap %.0f / %.0f".format(it.x, it.y) } ?: "snap none",
        ))
    }
}
