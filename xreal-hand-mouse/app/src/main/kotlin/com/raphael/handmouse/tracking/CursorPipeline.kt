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
 * ao vivo em TODAS as fases, sempre via dead-zone.
 *
 * 2026-09-28 correction: the lookback is back at 120ms ([CLICK_FREEZE_LOOKBACK_MS]) without the
 * visual freeze, so a pinch click lands where the cursor was 120ms before the confirmed DOWN
 * while the visible cursor keeps following the hand. [injectClick] marks the real click point
 * ([CursorOverlay.pulseClickAt]) when it differs from the cursor. The fist click uses the
 * position where the fingers started closing instead ([PositionHistory.fistOnset]).
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

        /** Fist click is dropped when the cursor was moving faster than this (px/s) when the
         * fingers started closing — a fist formed mid-sweep is a gesture transition, not an aimed
         * click. 1200px/s is where [RelativeCursorMapper] leaves precision mode entirely (20px
         * per 60fps frame), so aimed movement stays well below it. */
        private const val FIST_CLICK_MAX_SPEED_PX_S = 1200f

        /** Span before the fist onset used to measure the cursor speed for the check above. */
        private const val FIST_CLICK_SPEED_SPAN_MS = 100L

        /** A pinch shows its "release = tap / move = drag" hint only once held this long, so a
         * quick tap does not flash text under the cursor. */
        private const val PINCH_HINT_DELAY_MS = 200L

        /** Punho fechado segurado por este tempo recentraliza o cursor (2026-07-23, pedido do
         * usuário — o modo relativo perde a correspondência mão↔centro com o tempo; ver
         * [FistDetector] e [RelativeCursorMapper.recenter]). */
        private const val FIST_RECENTER_HOLD_MS = 2000L

        /** Thumbs-up segurado por este tempo alterna o MUTE do cursor (2026-07-23, pedido do
         * usuário: "quando abaixo os braços o cursor fica perdido e visível" — ver
         * [ThumbsUpDetector] e a spec `2026-07-23-cursor-mute-gesture-design.md`). */
        private const val THUMBS_UP_TOGGLE_HOLD_MS = 1000L

        /** Fist touch-down mode: the press stays pinned until the hand has moved this far (px,
         * like ultraleap TouchFree's grab drag threshold), so the knuckle shift of a held fist
         * does not turn a press into a drag. */
        private const val FIST_TOUCH_SLOP_PX = 28f

        /** A "not ready" hint stays up this long after it blocked a pinch or fist. */
        private const val NOT_READY_HINT_MS = 800L

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

    /** Head rotation removed from the hand position (2026-09-29, off by default). */
    private val headMotion = HeadMotionCompensator()

    /** The capture service's gyro history ([EyeCaptureService.gyroHistory]); null = no IMU. */
    var gyroHistory: com.raphael.handmouse.imu.GyroHistory?
        get() = headMotion.gyro
        set(value) { headMotion.gyro = value }

    /** Applies the settings screen values to every pipeline component (live, no restart). */
    fun applySettings(newSettings: HandSettings) {
        // Switching between world/normalized ratios must not mix pinch EMAs; any other change
        // leaves a pinch/drag in progress alone.
        if (newSettings.worldPinch != settings.worldPinch) onHandLost()
        settings = newSettings
        headMotion.calibration = if (newSettings.headCompensation) newSettings.headCalibration else null
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

    // Fist click planned when its ring starts (2026-09-28): the tap point (after magnetic snap)
    // is shown as a preview and used as-is on confirm — what the user sees is where it lands.
    private class FistPlan(val target: CursorPoint?, val dropped: Boolean)
    private var fistPlan: FistPlan? = null
    private val fistAttempt = FistAttempt()
    private var pinchDownAtMs = 0L

    // Fist touch-down mode (setting, 2026-09-29): a confirmed fist presses at the planned point
    // and holds the touch until the fist opens. [handX]/[handY]: live cursor where the drag is
    // measured from (re-based when the slop is crossed, so the press point does not jump).
    private class FistTouch(val anchorX: Float, val anchorY: Float, var handX: Float, var handY: Float) {
        var dragging = false
        var x = anchorX
        var y = anchorY
        val opening = FistOpening()
    }
    private var fistTouch: FistTouch? = null

    // "Not ready" ([HandReadiness]): when it last blocked a pinch press or a fist.
    private var notReadyBlockedAtMs: Long? = null
    private var notReadyReason: HandReadiness.Reason? = null

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

        // Isotropic landmarks: every distance/ratio below is direction-independent (see
        // [HandTracker.Result.isoPoints]).
        val points = result.isoPoints
        val t = result.timestampMs

        // Pinch ratios from metric world landmarks when available (robust to hand rotation —
        // normalized-image z is a rough estimate), else from the isotropic landmarks.
        val pinchPoints = if (settings.worldPinch && result.worldLandmarks.size == 21) result.worldLandmarks else points

        // Finger measurements shared by the pose detectors, and whether the hand is reliable
        // enough to START a pinch or fist (2026-09-29 — [HandFeatures], [HandReadiness]).
        val features = HandFeatures.from(points)
        notReadyReason = HandReadiness.notReadyReason(result.points, result.handedness.firstOrNull()?.score())
        val ready = notReadyReason == null

        // ---- Mute por thumbs-up (2026-07-23): PRIMEIRO de tudo, e único caminho vivo quando
        // mutado ----
        // O detector é alimentado TODO frame; segurar o gesto THUMBS_UP_TOGGLE_HOLD_MS com a
        // máquina de clique em IDLE alterna o mute (1x por hold). Mutado, o frame acaba aqui:
        // zero palma/pinch/punho/movimento/injeção — o pipeline vira só o "ouvido" pro gesto de
        // religar. Estados dos outros detectores ficam obsoletos de propósito (o unmute reseta
        // tudo — ver [toggleCursorMuted]).
        if (settings.thumbsUpMute) updateThumbsUpState(features, t)
        if (cursorMuted) {
            overlay.setFistProgress(0f)
            clearFistPlan()
            showHint(null)
            showDebug(result, pinchPoints, null, "MUTED")
            return
        }

        // ---- Controle por voz (2026-07-23): "V" segurado V_SIGN_HOLD_MS abre a escuta ----
        // Durante a escuta o frame acaba aqui: âncora solta (sem salto na volta — mesmo clutch
        // do modo palma), pinch suprimido, cursor parado. A mão fica livre pra ficar à vontade
        // enquanto o usuário FALA (inclusive abaixá-la — a escuta não depende mais do gesto).
        if (settings.vSignVoice) updateVSignState(features, t)
        if (voiceController?.isListening == true) {
            relativeMapper.onHandLost()
            palmReference.reset()
            pinchDetector.reset()
            overlay.setPinched(false)
            overlay.setFistProgress(0f)
            clearFistPlan()
            closeMenuIfOpen()
            showHint(GestureHint.LISTENING)
            showDebug(result, pinchPoints, null, "VOICE")
            return
        }

        // A stationary long pinch opens the menu. While it is open, only the displacement from
        // its opening position matters; the normal cursor mapping is paused.
        if (layeredMenu.isActive) {
            val event = pinchDetector.update(pinchPoints, t)
            if (event == PinchEvent.UP && menuAwaitRelease) {
                menuAwaitRelease = false
                clickDragStateMachine.pinchUp(t)
            }
            val fist = fistDetector.update(features, t)
            overlay.setFistProgress(0f)
            clearFistPlan()
            showHint(GestureHint.MENU)
            if (fist || t - menuOpenedAtMs > 8_000L) {
                if (fist) {
                    lastLoggedFist = true
                    fistReleasePending = true
                }
                layeredMenu.reset()
                overlay.hidePalmMenu()
                clickDragStateMachine.reset()
            } else {
                val confirm = !menuAwaitRelease && event == PinchEvent.DOWN
                handleMenuFrame(layeredMenu.onPalmOpen(points[5].x, points[5].y, confirm, t), currentBounds)
            }
            relativeMapper.onHandLost()
            palmReference.reset()
            overlay.setPinched(false)
            showDebug(result, pinchPoints, null, "MENU")
            return
        }

        val aspect = if (result.imageWidth > 0) result.imageHeight.toFloat() / result.imageWidth else 9f / 16f
        val ref = palmReference.update(headMotion.compensate(points, t, aspect), t)
        val mapped = relativeMapper.map(ref.x, ref.y, currentBounds, t)
        val fx = oneEuroX.filter(mapped.x, t)
        val fy = oneEuroY.filter(mapped.y, t)
        lastLivePosition = fx to fy // achado I3b — ver Javadoc da classe ("Drag órfão")

        // Fist state first, so its curl goes into the same history sample (fist click position).
        val fistMayStart = PoseArbiter.mayStart(HandPose.FIST, activePoses())
        val isFist = fistDetector.update(features, t, allowEnter = ready && fistMayStart)
        if (!ready && fistMayStart && !isFist && features.maxCurl < FistDetector.ENTER_THRESHOLD) notReadyBlockedAtMs = t

        // Ver Javadoc da classe ("Congelamento de posição") sobre por que isto roda ANTES da
        // dead-zone e é usado tanto pro clique/drag quanto pro histórico de congelamento.
        positionHistory.record(fx, fy, t, fistDetector.curl)

        // Punho fechado: suprime o pinch (num punho o polegar encosta no indicador e o
        // PinchDetector confundiria com clique), clica e, segurado FIST_RECENTER_HOLD_MS, recentraliza.
        val fistSuppressesPinch = updateFistState(isFist, t, currentBounds)
        fistAttempt.update(isFist, fistDetector.enterProgress)
        val fistPending = fistAttempt.pending
        val fistAbandoned = fistAttempt.abandoned
        val fistRingShown = updateFistPlan(fistPending, fistAbandoned, t, currentBounds)
        overlay.setFistProgress(if (fistRingShown) fistDetector.enterProgress else 0f)
        if (!fistSuppressesPinch && fistReleasePending) {
            if (PinchDetector.ratio(pinchPoints) > pinchDetector.exitThreshold + 0.08f) {
                fistReleasePending = false
            } else {
                pinchDetector.reset()
            }
        }

        val rawPinchEvent = when {
            fistSuppressesPinch || fistReleasePending -> null
            else -> pinchDetector.update(pinchPoints, t)
        }
        // 2026-09-28: while a fist forms, the thumb touching the index reads as a pinch, and the
        // ring's "open the hand to cancel" must cancel everything. A pending fist holds back a
        // pinch press; abandoning the fist, or releasing the pinch during it, drops the press.
        val pressed = clickDragStateMachine.phase == ClickDragStateMachine.Phase.PRESSED
        val pinchEvent = when {
            fistPending && rawPinchEvent == PinchEvent.DOWN -> {
                pinchDetector.reset()
                null
            }
            // 2026-09-29: a thumbs-up or V owns the hand ([PoseArbiter]), or the hand is not
            // ready ([HandReadiness]) — no new press; it can start once the block lifts.
            rawPinchEvent == PinchEvent.DOWN && (!ready || !PoseArbiter.mayStart(HandPose.PINCH, activePoses())) -> {
                if (!ready) notReadyBlockedAtMs = t
                pinchDetector.reset()
                null
            }
            // Only a ring that grew ([FistAttempt.REAL_PROGRESS]) counts: one frame of curl
            // noise must not throw away a held pinch or the click of a released one.
            pressed && (fistAttempt.abandonedReal || (fistAttempt.pendingReal && rawPinchEvent == PinchEvent.UP)) -> {
                Log.d(TAG, "Pinch press dropped together with the abandoned fist")
                clickDragStateMachine.reset()
                pinchDetector.reset()
                null
            }
            else -> rawPinchEvent
        }
        overlay.setPinched(!fistSuppressesPinch && pinchDetector.isPinched)

        val actions = when (pinchEvent) {
            PinchEvent.DOWN -> {
                val frozen = positionHistory.positionAt(t - CLICK_FREEZE_LOOKBACK_MS)
                val frozenX = frozen?.x ?: fx
                val frozenY = frozen?.y ?: fy
                pinchDownAtMs = t
                clickDragStateMachine.pinchDown(frozenX, frozenY, t)
            }
            PinchEvent.UP -> clickDragStateMachine.pinchUp(t)
            null -> clickDragStateMachine.positionUpdate(fx, fy, t)
        }
        // Ver "Supressão de injeção" no Javadoc da classe (achado I4): a máquina de estados e o
        // overlay (setPinched acima, moveTo abaixo) continuam rodando incondicionalmente durante
        // a supressão — só o clique/drag REAL (e o pulso de clique confirmado) pausa.
        if (!injectionSuppressed) {
            dispatchActions(actions, currentBounds, points[5], t)
        }

        val visiblePosition = filteredDragPosition ?: (fx to fy)
        renderCursor(visiblePosition.first, visiblePosition.second)
        showHint(normalHint(fistRingShown, t))
        showDebug(result, pinchPoints, visiblePosition, if (fistTouch != null) "FIST_TOUCH" else clickDragStateMachine.phase.name)
    }

    /** Poses currently holding the hand, for [PoseArbiter]. A detector whose gesture is turned
     * off is not updated, so its last state does not count. */
    private fun activePoses(): Set<HandPose> = buildSet {
        if (settings.thumbsUpMute && thumbsUpDetector.isActive) add(HandPose.THUMBS_UP)
        if (settings.vSignVoice && vSignDetector.isVSign) add(HandPose.V_SIGN)
        if (fistDetector.isFist) add(HandPose.FIST)
        if (pinchDetector.isPinched) add(HandPose.PINCH)
    }

    /** What to do next in the normal (non-menu, non-voice) path — see [GestureHint]. */
    private fun normalHint(fistRingShown: Boolean, timestampMs: Long): GestureHint? {
        val phase = clickDragStateMachine.phase
        val blockedAt = notReadyBlockedAtMs
        return when {
            fistTouch != null -> GestureHint.FIST_PRESSED
            phase == ClickDragStateMachine.Phase.DRAGGING -> GestureHint.DRAGGING
            fistRingShown -> if (settings.fistTouch) GestureHint.FIST_PENDING_TOUCH else GestureHint.FIST_PENDING
            blockedAt != null && timestampMs - blockedAt < NOT_READY_HINT_MS && notReadyReason != null ->
                if (notReadyReason == HandReadiness.Reason.EDGE) GestureHint.NOT_READY_EDGE else GestureHint.NOT_READY
            phase == ClickDragStateMachine.Phase.PRESSED && timestampMs - pinchDownAtMs >= PINCH_HINT_DELAY_MS ->
                if (settings.palmMenu) GestureHint.PINCH_PRESSED else GestureHint.PINCH_PRESSED_NO_MENU
            thumbsUpHoldStartMs != null && !thumbsUpToggleFired -> GestureHint.THUMBS_UP
            vSignHoldStartMs != null && !vSignFired -> GestureHint.V_SIGN
            else -> null
        }
    }

    private fun showHint(hint: GestureHint?) {
        overlay.setHint(if (settings.gestureHints) hint else null)
    }

    /** The menu is modal: entering mute or voice from it must not leave it frozen on screen. */
    private fun closeMenuIfOpen() {
        if (!layeredMenu.isActive) return
        layeredMenu.reset()
        overlay.hidePalmMenu()
        clickDragStateMachine.reset()
    }

    /**
     * Gesto de thumbs-up (2026-07-23, spec `2026-07-23-cursor-mute-gesture-design.md`): segurar
     * [THUMBS_UP_TOGGLE_HOLD_MS] alterna [cursorMuted]. Regras:
     * - Só alterna com a máquina de clique em IDLE (nunca no meio de um clique/drag) — exceto
     *   quando JÁ mutado (mutado, a máquina está garantidamente resetada).
     * - Dispara UMA vez por hold ([thumbsUpToggleFired]); soltar o gesto rearma.
     * - Transições de estado do detector são logadas (mesmo padrão palma/punho).
     */
    private fun updateThumbsUpState(features: HandFeatures, timestampMs: Long) {
        val active = thumbsUpDetector.update(features, timestampMs, PoseArbiter.mayStart(HandPose.THUMBS_UP, activePoses()))
        if (active != lastLoggedThumbsUp) {
            lastLoggedThumbsUp = active
            Log.d(TAG, "Thumbs-up ${if (active) "ATIVO (segure ${THUMBS_UP_TOGGLE_HOLD_MS}ms pra alternar o mute)" else "inativo"}")
        }

        if (!active || (!cursorMuted && clickDragStateMachine.phase != ClickDragStateMachine.Phase.IDLE) ||
            layeredMenu.isActive || // 2026-09-28: the menu is modal — muting from it left it frozen on screen
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
            closeMenuIfOpen()
            clearFistPlan()
            overlay.setHint(null)
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
    private fun updateFistState(isFist: Boolean, timestampMs: Long, display: DisplayBounds): Boolean {
        val justClosed = isFist && !lastLoggedFist
        if (isFist != lastLoggedFist) {
            lastLoggedFist = isFist
            Log.d(TAG, "Punho ${if (isFist) "ATIVO — pinch suprimido (2s segura = recentraliza)" else "inativo"}")
        }
        if (!isFist) {
            releaseFistTouch()
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
            if (settings.fistTouch) fistTouchDown(timestampMs, display) else fistClick(timestampMs, display)
        } else if (justClosed) {
            clearFistPlan()
        } else {
            updateFistTouch(timestampMs, display)
        }

        // Recentering would move the cursor under a held touch, so touch mode turns it off.
        val start = fistHoldStartMs ?: timestampMs.also { fistHoldStartMs = it }
        if (settings.fistRecenter && !settings.fistTouch && !fistRecenterFired && timestampMs - start >= FIST_RECENTER_HOLD_MS) {
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
     * Fist click (the fork's primary click for hard-to-hit targets), fired once when a fist is
     * confirmed. 2026-09-28:
     * - Position: where the fingers STARTED closing ([PositionHistory.fistOnset]). The fist only
     *   confirms after the enter debounce (~225ms) plus the closing motion, and closing shifts
     *   the knuckles the cursor follows; the old fixed 180ms lookback landed mid-closure.
     * - A fist formed while the cursor was sweeping faster than [FIST_CLICK_MAX_SPEED_PX_S] is a
     *   gesture transition, not an aimed click — dropped.
     */
    private fun fistClick(timestampMs: Long, display: DisplayBounds) {
        val plan = fistPlan ?: planFistClick(timestampMs, display)
        clearFistPlan()
        val target = plan.target ?: return // dropped by the speed check
        tapAt(target, display)
    }

    /**
     * Fist touch-down mode (setting, 2026-09-29, after ultraleap TouchFree's grab interaction):
     * the confirmed fist presses at the planned point (same plan and speed check as [fistClick])
     * and the touch is held until the fist opens — a quick fist is a tap, a held one a long
     * press, and moving the closed hand drags.
     */
    private fun fistTouchDown(timestampMs: Long, display: DisplayBounds) {
        val plan = fistPlan ?: planFistClick(timestampMs, display)
        clearFistPlan()
        val target = plan.target ?: return // dropped by the speed check
        val (hx, hy) = lastLivePosition
        fistTouch = FistTouch(target.x, target.y, hx, hy)
        dragXFilter.reset()
        dragYFilter.reset()
        dragXFilter.filter(target.x, timestampMs)
        dragYFilter.filter(target.y, timestampMs)
        filteredDragPosition = target.x to target.y
        Log.d(TAG, "Fist touch down (${target.x}, ${target.y}) display=${display.displayId}")
        gestureInjector.beginDrag(target.x, target.y, display.displayId)
        overlay.pulseClickAt(target.x, target.y)
    }

    /** Held fist touch: pinned at the press point until the cursor has moved
     * [FIST_TOUCH_SLOP_PX], then follows the hand from there. Frames where the fingers are
     * opening ([FistOpening]: the curl rose fast and has not come back under the fist entry
     * threshold, before the exit confirms) hold the position, so opening the hand does not shift
     * the release point; a loose fist, curl between entry and exit, keeps following. The
     * injector gets a segment every frame either way — a still touch needs them too (see
     * [GestureInjector]). */
    private fun updateFistTouch(timestampMs: Long, display: DisplayBounds) {
        val touch = fistTouch ?: return
        val (hx, hy) = lastLivePosition
        if (!touch.opening.update(fistDetector.curl, timestampMs, FistDetector.ENTER_THRESHOLD)) {
            if (!touch.dragging && kotlin.math.hypot(hx - touch.handX, hy - touch.handY) > FIST_TOUCH_SLOP_PX) {
                touch.dragging = true
                touch.handX = hx
                touch.handY = hy
            }
            if (touch.dragging) {
                touch.x = (touch.anchorX + hx - touch.handX).coerceIn(0f, display.width - 1f)
                touch.y = (touch.anchorY + hy - touch.handY).coerceIn(0f, display.height - 1f)
            }
        }
        val x = dragXFilter.filter(touch.x, timestampMs)
        val y = dragYFilter.filter(touch.y, timestampMs)
        filteredDragPosition = x to y
        gestureInjector.updateDrag(x, y)
    }

    /** Ends a held fist touch at its unfiltered position (the pinned point when it never moved). */
    private fun releaseFistTouch() {
        val touch = fistTouch ?: return
        fistTouch = null
        Log.d(TAG, "Fist touch up (${touch.x}, ${touch.y}) dragged=${touch.dragging}")
        gestureInjector.endDrag(touch.x, touch.y)
        dragXFilter.reset()
        dragYFilter.reset()
        filteredDragPosition = null
    }

    /** Plans the fist click once, when its ring starts, and returns whether the ring is shown:
     * not during a drag (fist does not interfere there), with injection suppressed, or when the
     * speed check already dropped the click — the ring promises a tap. */
    private fun updateFistPlan(pending: Boolean, abandoned: Boolean, timestampMs: Long, display: DisplayBounds): Boolean {
        if (abandoned) clearFistPlan()
        if (!pending || injectionSuppressed) return false
        if (clickDragStateMachine.phase == ClickDragStateMachine.Phase.DRAGGING) return false
        val plan = fistPlan ?: planFistClick(timestampMs, display).also { p ->
            fistPlan = p
            p.target?.let { overlay.showClickPreview(it.x, it.y) }
        }
        return !plan.dropped
    }

    private fun planFistClick(timestampMs: Long, display: DisplayBounds): FistPlan {
        val onset = positionHistory.fistOnset(timestampMs, FistDetector.ENTER_THRESHOLD)
            ?: return FistPlan(resolveClickTarget(lastLivePosition.first, lastLivePosition.second, display), dropped = false)
        val before = positionHistory.positionAt(onset.timestampMs - FIST_CLICK_SPEED_SPAN_MS)
        if (before != null && before.timestampMs < onset.timestampMs) {
            val speed = kotlin.math.hypot(onset.x - before.x, onset.y - before.y) * 1000f /
                (onset.timestampMs - before.timestampMs)
            if (speed > FIST_CLICK_MAX_SPEED_PX_S) {
                Log.d(TAG, "Fist click dropped: cursor moving %.0f px/s when the fist started".format(speed))
                return FistPlan(null, dropped = true)
            }
        }
        Log.d(TAG, "Fist click planned at the onset ${timestampMs - onset.timestampMs}ms back")
        return FistPlan(resolveClickTarget(onset.x, onset.y, display), dropped = false)
    }

    private fun clearFistPlan() {
        if (fistPlan == null) return
        fistPlan = null
        overlay.hideClickPreview()
    }

    /**
     * Gesto de sinal de "V" (2026-07-23, spec voice-control): segurar [V_SIGN_HOLD_MS] abre a
     * janela de escuta do [voiceController]. Regras (mesmo molde do punho/thumbs-up):
     * - Só com a máquina de clique em IDLE (nunca no meio de clique/drag).
     * - Dispara UMA vez por hold ([vSignFired]); soltar o "V" rearma.
     * - Suprimido com injectionSuppressed (wizard de calibração) e sem bounds (sem display).
     * - Transições logadas (mesmo padrão palma/punho/thumbs-up).
     */
    private fun updateVSignState(features: HandFeatures, timestampMs: Long) {
        val active = vSignDetector.update(features, timestampMs, PoseArbiter.mayStart(HandPose.V_SIGN, activePoses()))
        if (active != lastLoggedVSign) {
            lastLoggedVSign = active
            Log.d(TAG, "Sinal de V ${if (active) "ATIVO (segure ${V_SIGN_HOLD_MS}ms pra falar)" else "inativo"}")
        }

        if (!active || clickDragStateMachine.phase != ClickDragStateMachine.Phase.IDLE ||
            layeredMenu.isActive // 2026-09-28: modal menu (see updateThumbsUpState)
        ) {
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
        overlay.setFistProgress(0f)
        clearFistPlan()
        fistAttempt.reset()
        overlay.setHint(null)
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
        releaseFistTouch() // same for a held fist touch
        notReadyBlockedAtMs = null
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
                    handleMenuFrame(layeredMenu.onPalmOpen(hand.x, hand.y, false, timestampMs), display)
                }
            }
        }
    }

    private fun injectClick(x: Float, y: Float, display: DisplayBounds) {
        tapAt(resolveClickTarget(x, y, display), display)
    }

    /** Magnetic click: the nearby accessible control for ([x], [y]), else the point itself. */
    private fun resolveClickTarget(x: Float, y: Float, display: DisplayBounds): CursorPoint {
        val snapped = if (settings.magneticClick) clickTargetResolver?.resolve(x, y, display) else null
        lastClickTarget = snapped
        if (snapped != null) Log.d(TAG, "Click target ($x, $y) -> (${snapped.x}, ${snapped.y})")
        return snapped ?: CursorPoint(x, y)
    }

    private fun tapAt(target: CursorPoint, display: DisplayBounds) {
        Log.d(TAG, "Click (${target.x}, ${target.y}) display=${display.displayId}")
        gestureInjector.tap(target.x, target.y, display.displayId)
        overlay.pulseClickAt(target.x, target.y)
    }

    /** [pinchPoints]: the landmarks the pinch detector actually uses (world or isotropic), so
     * the shown ratio is the one compared against the thresholds. The skeleton and palm values
     * are in image coordinates ([HandTracker.Result.points]). */
    private fun showDebug(
        result: HandTracker.Result,
        pinchPoints: List<HandPoint>,
        cursor: Pair<Float, Float>?,
        state: String,
    ) {
        if (!settings.debugOverlay) return
        val points = result.points
        val ref = if (points.size >= 18) listOf(5, 9, 13, 17) else emptyList()
        val palmX = if (ref.isEmpty()) 0f else ref.map { points[it].x }.average().toFloat()
        val palmY = if (ref.isEmpty()) 0f else ref.map { points[it].y }.average().toFloat()
        overlay.updateHandDebug(points, listOf(
            "HAND ${"%.0f".format(result.inferenceFps)} fps",
            "pinch ${pinchDetector.isPinched} (%.2f)".format(PinchDetector.ratio(pinchPoints)),
            "fist ${fistDetector.isFist}",
            "state $state",
            "palm %.3f / %.3f".format(palmX, palmY),
            cursor?.let { "cursor %.0f / %.0f".format(it.first, it.second) } ?: "cursor held",
            lastClickTarget?.let { "snap %.0f / %.0f".format(it.x, it.y) } ?: "snap none",
        ))
    }
}
