package com.raphael.handmouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.raphael.handmouse.MainActivity
import com.raphael.handmouse.R
import com.raphael.handmouse.input.AppLauncher
import com.raphael.handmouse.input.AccessibleTargetFinder
import com.raphael.handmouse.input.GestureInjector
import com.raphael.handmouse.input.TextInserter
import com.raphael.handmouse.input.VoiceCommandController
import com.raphael.handmouse.overlay.CursorOverlay
import com.raphael.handmouse.overlay.DexDisplayMonitor
import com.raphael.handmouse.overlay.DisplaySelection
import com.raphael.handmouse.overlay.hasDisplaySelectionChanged
import com.raphael.handmouse.tracking.CursorPipeline
import com.raphael.handmouse.tracking.DisplayBounds
import com.raphael.handmouse.tracking.MenuAction
import com.raphael.handmouse.input.WindowController
import com.raphael.handmouse.util.KEY_BTN_PHOTO
import com.raphael.handmouse.util.KEY_BTN_RECORD
import com.raphael.handmouse.util.KEY_BTN_TRACKING
import com.raphael.handmouse.util.KEY_TRACKING_ENABLED
import com.raphael.handmouse.util.Prefs

/**
 * AccessibilityService das Tarefas 4/5 — PLANO.md §3.4/§3.5/Fase 3/Fase 4/Fase 5. Config XML
 * (`res/xml/accessibility_config.xml`): `canPerformGestures="true"` (usado pelo
 * [GestureInjector] desde a Tarefa 5) e `isAccessibilityTool="true"`.
 *
 * Hospeda o [CursorOverlay] (a11y service é dono do contexto que dispensa
 * `SYSTEM_ALERT_WINDOW` — PLANO.md §3.4), o [CursorPipeline] que alimenta esse overlay a partir
 * dos resultados do [EyeCaptureService], e o [GestureInjector] (injeção real de clique/drag —
 * `dispatchGesture` só existe como método de instância de [AccessibilityService], por isso o
 * `GestureInjector` recebe `this`).
 *
 * Singleton (mesmo processo, sem IPC) — ver Javadoc de [EyeCaptureService] ("Handshake") sobre
 * a ordem de inicialização entre os dois serviços.
 *
 * ## Dedupe da recriação da janela (fix de revisão da T4)
 * `DisplayManager.DisplayListener.onDisplayChanged` dispara para QUALQUER evento de display do
 * sistema, não só o do DeX — mas [DexDisplayMonitor.refresh] reavalia e republica a seleção a
 * cada disparo, mesmo quando o display do DeX escolhido não mudou. Sem guarda,
 * [onDexDisplayChanged] chamava `overlay.show(display)` (= `hide()` + `addView` completo) em
 * TODO esses eventos — janela do cursor sendo destruída/reconstruída à toa durante operação
 * normal (jank visível), não só na troca de modo UltraWide (o caso genuíno que precisa
 * recriar). [lastSelection] + [hasDisplaySelectionChanged] guardam a última seleção
 * efetivamente aplicada (id do display + bounds em pixels); só uma mudança REAL (id diferente,
 * ou bounds diferentes pro mesmo id, ou aparecer/sumir) passa da guarda e recria a janela.
 *
 * ## Watchdog do FGS (Tarefa 5 — PLANO.md Fase 5)
 * A cada [WATCHDOG_INTERVAL_MS] (handler, main thread), [checkCaptureServiceHealth] confere se
 * a captura DEVERIA estar rodando ([Prefs.captureActive] — setado em `EyeCaptureService.start()`,
 * nunca limpo automaticamente, ver Javadoc de [Prefs]) mas [EyeCaptureService.getInstance] está
 * `null` (o processo/serviço morreu sem o usuário ter pedido). Nesse caso, tenta
 * `EyeCaptureService.start()` de novo; se o sistema recusar por causa do limite de
 * background-start (`ForegroundServiceStartNotAllowedException`, API 31+ — sempre disponível,
 * `minSdk 34`), publica uma notificação high-priority com `PendingIntent` pra `MainActivity`
 * pedindo pro usuário reabrir o app (1 toque resolve — reabrir traz a Activity ao foreground,
 * de onde um novo `startForegroundService` É permitido). Tudo logado (brief).
 *
 * ## Re-entrancia de onServiceConnected (fix de revisao, achado M2)
 * O sistema pode chamar [onServiceConnected] de novo na MESMA instancia do servico sem que
 * [onDestroy] tenha rodado antes (rebind - ex.: o usuario desliga/religa a acessibilidade
 * rapidamente nas Configuracoes, ou o system_server decide re-vincular o servico). Antes deste
 * fix, cada chamada criava overlay/cursorPipeline/displayMonitor NOVOS sem desfazer os antigos:
 * a janela antiga do overlay nunca era escondida (ficava uma janela "fantasma" viva sobre o
 * display do DeX), o displayMonitor antigo nunca era parado (listener duplicado no
 * DisplayManager), o cursorPipeline antigo nunca era removido de
 * EyeCaptureService.trackingListeners (dois pipelines processando o mesmo resultado de mao) - e,
 * pior, [lastSelection] continuava com a selecao antiga, entao o dedupe de [onDexDisplayChanged]
 * (ver secao acima) descartava o PRIMEIRO overlay.show() da instancia NOVA por achar que "nada
 * mudou" (o cursor ficava invisivel, mascarando de falha de tracking - exatamente o risco que o
 * spike S4 queria descartar). Fix: no topo de [onServiceConnected], desfaz explicitamente
 * qualquer estado de uma conexao anterior (mesmo padrao de [onDestroy]) e zera [lastSelection]
 * ANTES de reconstruir - idempotente mesmo na primeira chamada real (todos os
 * ::campo.isInitialized sao falsos nesse caso, entao os ifs nao fazem nada).
 */
class HandMouseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HandMouseAccessibilityService"
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val NOTIF_CHANNEL_ID_WATCHDOG = "watchdog"
        private const val NOTIF_ID_WATCHDOG = 2

        // Retry do overlay num display do DeX recém-criado (ver showOverlayWithRetry).
        private const val OVERLAY_SHOW_RETRY_DELAY_MS = 250L
        private const val MAX_OVERLAY_SHOW_ATTEMPTS = 8

        @Volatile
        var instance: HandMouseAccessibilityService? = null
            private set

        /** Eye Tools fork: pref key waiting for a button press ("learn" mode), or null. */
        @Volatile
        var learnKeyFor: String? = null
        @Volatile
        private var learnDeadlineMs = 0L

        const val LEARN_TIMEOUT_MS = 10_000L

        fun startLearning(prefKey: String) {
            learnKeyFor = prefKey
            learnDeadlineMs = SystemClock.uptimeMillis() + LEARN_TIMEOUT_MS
            instance?.updateKeyFilter()
        }
    }

    private lateinit var overlay: CursorOverlay
    private lateinit var cursorPipeline: CursorPipeline
    private lateinit var displayMonitor: DexDisplayMonitor
    private lateinit var prefs: Prefs
    private var voiceController: VoiceCommandController? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Eye Tools fork: settings screen → CursorPipeline, live (listener runs on the main thread,
    // the same thread CursorPipeline.onHandResult runs on).
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null -> {}
            key.startsWith("btn_") -> loadButtons()
            key.startsWith("dim_") -> applyDimSettings()
            // tracking on/off is handled by the capture service; it must not reset the pipeline
            key.startsWith("hm_") && key != KEY_TRACKING_ENABLED && ::cursorPipeline.isInitialized -> applyHandSettings()
        }
    }

    private var autoDimmer: com.raphael.handmouse.overlay.AutoDimmer? = null

    private fun applyDimSettings() {
        val d = autoDimmer ?: return
        d.maxDim = prefs.dimMaxPct / 100f
        d.startLux = prefs.dimStartLux.toFloat()
        if (prefs.dimAuto && ::displayMonitor.isInitialized && displayMonitor.currentDexDisplay != null) {
            d.start()
        } else {
            d.stop()
        }
    }

    /** Assigned hardware buttons (key codes), cached — onKeyEvent runs for every key press. */
    private var buttonCodes = IntArray(3)
    private val buttonActions = arrayOf(EyeAction.RECORD_TOGGLE, EyeAction.PHOTO, EyeAction.TRACKING_TOGGLE)

    private fun loadButtons() {
        buttonCodes = intArrayOf(
            prefs.raw.getInt(KEY_BTN_RECORD, 0),
            prefs.raw.getInt(KEY_BTN_PHOTO, 0),
            prefs.raw.getInt(KEY_BTN_TRACKING, 0),
        )
        updateKeyFilter()
    }

    /** Key filtering only while a button is assigned or being learned: otherwise every DeX
     * keystroke would detour through this service's (busy) main thread. */
    private fun updateKeyFilter() {
        val want = learnKeyFor != null || buttonCodes.any { it != KeyEvent.KEYCODE_UNKNOWN }
        val info = serviceInfo ?: return
        val has = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        if (want == has) return
        info.flags = if (want) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
    }

    private fun applyHandSettings() = cursorPipeline.applySettings(prefs.handSettings())

    private val displayManager: DisplayManager by lazy { getSystemService(DisplayManager::class.java) }

    // Runnable de retry do overlay (ver showOverlayWithRetry) — nomeado para poder ser cancelado.
    private var overlayRetryRunnable: Runnable = Runnable {}

    // Última seleção (id + bounds) aplicada à janela do overlay OU em processo de retry de show
    // (invariante: != null ⇒ janela mostrada OU retry em voo — ver comentário no topo de
    // onDexDisplayChanged). Ver Javadoc da classe ("Dedupe da recriação da janela").
    // `null` = nenhum display do DeX mostrado nem sendo tentado agora.
    private var lastSelection: DisplaySelection? = null

    private val dexDisplayListener = DexDisplayMonitor.Listener { display -> onDexDisplayChanged(display) }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkCaptureServiceHealth()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Fix de revisao (achado M2) - ver Javadoc da classe ("Re-entrancia de
        // onServiceConnected"): desfaz qualquer estado de uma conexao ANTERIOR desta mesma
        // instancia de servico antes de reconstruir. No-op na primeira conexao real (todos os
        // ::campo.isInitialized sao falsos ainda).
        if (::cursorPipeline.isInitialized) {
            EyeCaptureService.getInstance()?.removeTrackingListener(cursorPipeline)
        }
        if (::displayMonitor.isInitialized) displayMonitor.stop()
        if (::overlay.isInitialized) overlay.hide()
        voiceController?.shutdown() // sessão de voz de uma conexão anterior (rebind)
        lastSelection = null
        mainHandler.removeCallbacks(overlayRetryRunnable) // idem - retry de overlay de uma conexão anterior
        mainHandler.removeCallbacks(watchdogRunnable) // idem - evita 2 watchdogs rodando em paralelo

        prefs = Prefs(this)

        // Construção completa ANTES de publicar `instance` — nenhum outro código no mesmo
        // processo consegue enxergar um `instance` parcialmente inicializado (single-threaded:
        // onServiceConnected roda até o fim antes de qualquer outra mensagem do main Looper).
        overlay = CursorOverlay(this)
        // O MESMO GestureInjector serve o cursor (tap/drag) e os gestos de mídia (duplo-tap do
        // seek) — instância única de propósito, o estado de drag dele é um só.
        val gestureInjector = GestureInjector(this)
        // Eye Tools fork: closes the window under the cursor (palm menu and voice "닫기").
        val windowController = WindowController(this, gestureInjector)
        val voice = VoiceCommandController(
            service = this,
            appLauncher = AppLauncher(this),
            textInserter = TextInserter(this),
            prefs = prefs,
            overlay = overlay,
            closeWindow = { displayId ->
                val sel = lastSelection?.takeIf { it.displayId == displayId }
                if (sel == null) {
                    false
                } else {
                    val (x, y) = overlay.cursorPosition ?: (sel.width / 2f to sel.height / 2f)
                    windowController.closeApp(x, y, DisplayBounds(sel.width, sel.height, displayId))
                }
            },
        )
        voiceController = voice
        cursorPipeline = CursorPipeline(
            overlay,
            gestureInjector,
            voiceController = voice,
        )
        val targetFinder = AccessibleTargetFinder(this)
        cursorPipeline.clickTargetResolver = CursorPipeline.ClickTargetResolver { x, y, display ->
            targetFinder.nearest(x, y, display)
        }
        displayMonitor = DexDisplayMonitor(this).apply {
            listener = dexDisplayListener
        }
        // Eye Tools fork: palm-menu actions.
        cursorPipeline.menuActionHandler = CursorPipeline.MenuActionHandler { action, x, y, display ->
            when (action) {
                MenuAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                MenuAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                MenuAction.CLOSE_APP -> windowController.closeApp(x, y, display)
            }
        }
        applyHandSettings()
        loadButtons()
        autoDimmer?.stop()
        autoDimmer = com.raphael.handmouse.overlay.AutoDimmer(this) { level -> overlay.setDim(level) }
        applyDimSettings()
        prefs.raw.unregisterOnSharedPreferenceChangeListener(settingsListener)
        prefs.raw.registerOnSharedPreferenceChangeListener(settingsListener)

        instance = this
        displayMonitor.start() // dispara refresh() -> onDexDisplayChanged síncrono, se já houver display
        mainHandler.post(watchdogRunnable)

        // Handshake com o EyeCaptureService (ver Javadoc dele) — se o FGS de captura já
        // estiver rodando (a11y habilitada depois de já estar capturando), registra já.
        EyeCaptureService.getInstance()?.addTrackingListener(cursorPipeline)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(overlayRetryRunnable)
        if (::prefs.isInitialized) prefs.raw.unregisterOnSharedPreferenceChangeListener(settingsListener)
        EyeCaptureService.getInstance()?.removeTrackingListener(cursorPipeline)
        if (::displayMonitor.isInitialized) displayMonitor.stop()
        if (::overlay.isInitialized) overlay.hide()
        voiceController?.shutdown()
        voiceController = null
        autoDimmer?.stop()
        autoDimmer = null
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não usado nesta tarefa — nenhuma leitura de conteúdo de tela é necessária pro cursor.
    }

    /**
     * Eye Tools fork: hardware-button shortcuts. Any key Android delivers (phone volume keys, a
     * Bluetooth handlebar/ring remote, and the glasses' buttons IF their firmware forwards them as
     * key events) can be assigned to record / photo / tracking in Settings ("learn" mode). Only
     * assigned keys are consumed; everything else passes through untouched.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_UNKNOWN) return false // unmapped HID keys all report 0
        val learning = learnKeyFor
        if (learning != null) {
            if (SystemClock.uptimeMillis() > learnDeadlineMs) {
                learnKeyFor = null
                updateKeyFilter()
            } else {
                if (event.action == KeyEvent.ACTION_UP) {
                    learnKeyFor = null
                    prefs.raw.edit().putInt(learning, code).apply() // → settingsListener → loadButtons
                    Log.d(TAG, "Button learned: $learning = ${KeyEvent.keyCodeToString(code)}")
                }
                return true
            }
        }
        val index = buttonCodes.indexOf(code)
        if (index < 0) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            Log.d(TAG, "Button ${KeyEvent.keyCodeToString(code)} -> ${buttonActions[index]}")
            EyeCaptureService.perform(this, buttonActions[index])
        }
        return true
    }

    override fun onInterrupt() {
        // Não usado nesta tarefa.
    }

    /** Chamado pelo [EyeCaptureService] quando ele (re)inicia — ver handshake documentado no
     * Javadoc dele. Idempotente (o registro do lado de lá é um Set). */
    fun attachTo(captureService: EyeCaptureService) {
        captureService.addTrackingListener(cursorPipeline)
        cursorPipeline.gyroHistory = captureService.gyroHistory
    }

    /** Ver Javadoc de [CursorPipeline] ("Supressão de injeção", achado Important I4) - chamado
     * pela `MainActivity` no inicio/fim do wizard de calibracao via este singleton (o
     * CursorPipeline de producao vive aqui, nao na Activity). Sem efeito (log) se o a11y
     * service ainda nao estiver conectado - nesse caso o wizard tambem nao teria um pipeline
     * de producao rodando pra suprimir. */
    fun setInjectionSuppressed(suppressed: Boolean) {
        if (!::cursorPipeline.isInitialized) {
            Log.w(TAG, "setInjectionSuppressed chamado antes de onServiceConnected — ignorado")
            return
        }
        cursorPipeline.setInjectionSuppressed(suppressed)
    }

    private fun onDexDisplayChanged(display: Display?) {
        // Fix (2026-07-23, "cursor some ao abrir o app"): NÃO cancelar o retry pendente antes do
        // dedupe. A versão anterior cancelava incondicionalmente aqui no topo; como lastSelection
        // é setado ANTES de overlay.show() ter sucesso, um evento duplicado de display (o DeX se
        // estabilizando, a tela do celular ligando...) durante a janela de retry cancelava o
        // retry e caía no dedupe — sem janela, sem retry, e todo evento futuro idêntico
        // descartado. O cursor nunca aparecia até o usuário desligar/religar a acessibilidade.
        // Agora o cancelamento só acontece quando a seleção REALMENTE mudou: no ramo display==null
        // abaixo, ou dentro de showOverlayWithRetry (primeira linha) no ramo de (re)show.
        // Invariante resultante: lastSelection != null ⇒ janela mostrada OU retry em voo.

        if (display == null) {
            applyDimSettings()
            if (!hasDisplaySelectionChanged(lastSelection, null)) return // já sabíamos: sem display
            mainHandler.removeCallbacks(overlayRetryRunnable)
            lastSelection = null
            cursorPipeline.updateBounds(null)
            overlay.hide()
            return
        }

        val bounds = displayMonitor.windowBounds(display)
        // bounds == null (falha ao obter) não vira uma seleção comparável -- trata sempre como
        // mudança (mais seguro recriar do que arriscar deixar a janela desatualizada quando não
        // dá pra confirmar "nada mudou").
        val selection = bounds?.let { DisplaySelection(display.displayId, it.width(), it.height()) }
        if (selection != null && !hasDisplaySelectionChanged(lastSelection, selection)) return

        lastSelection = selection
        cursorPipeline.updateBounds(bounds?.let { DisplayBounds(it.width(), it.height(), display.displayId) })
        applyDimSettings()
        showOverlayWithRetry(display.displayId, attempt = 0)
    }

    /**
     * Mostra o overlay do cursor, re-tentando se falhar. O display do DeX recém-criado
     * (`onDisplayAdded`) frequentemente ainda não aceita janelas de acessibilidade — `overlay.show`
     * retorna `false` (BadTokenException, ver Javadoc de [CursorOverlay.show]). Poucas centenas de
     * ms depois passa a aceitar. Re-busca o [Display] por id a cada tentativa (o objeto pode ficar
     * obsoleto) e desiste se o display sumiu ou a seleção mudou nesse meio-tempo. */
    private fun showOverlayWithRetry(displayId: Int, attempt: Int) {
        mainHandler.removeCallbacks(overlayRetryRunnable)

        // Se o display do DeX desejado mudou desde que esta tentativa foi agendada (outro
        // display, ou sumiu), aborta — quem mudou já disparou seu próprio fluxo. Compara com
        // displayMonitor.currentDexDisplay (fonte de verdade do display desejado), NÃO com
        // lastSelection: quando windowBounds falha (mesmo motivo "display recém-criado ainda não
        // pronto" do addView), lastSelection fica null e o guard antigo abortava o show na hora —
        // o overlay nem chegava a tentar aparecer (fix 2026-07-23, "cursor some ao abrir o app").
        if (displayMonitor.currentDexDisplay?.displayId != displayId) return

        val display = displayManager.getDisplay(displayId)
        if (display == null) return // display sumiu — onDexDisplayChanged(null) cuidará do resto

        if (overlay.show(display)) {
            // Sucesso — RE-publica bounds/seleção (fix 2026-07-23, "cursor só volta religando a
            // acessibilidade"): quando windowBounds() falhou lá em onDexDisplayChanged (display
            // recém-criado, mesma causa do addView falhar), o cursorPipeline ficou com bounds
            // NULL — e nada re-tentava obtê-los: o retry daqui só re-mostrava a JANELA, então o
            // overlay existia mas o pipeline ignorava todo frame (sem bounds não há pra onde
            // mapear) até um próximo evento de display ou o usuário religar o serviço. Agora o
            // mesmo retry que espera a janela "pegar" também re-resolve os bounds no sucesso.
            val bounds = displayMonitor.windowBounds(display)
            if (bounds != null) {
                lastSelection = DisplaySelection(displayId, bounds.width(), bounds.height())
                cursorPipeline.updateBounds(DisplayBounds(bounds.width(), bounds.height(), displayId))
            }
            return
        }

        if (attempt < MAX_OVERLAY_SHOW_ATTEMPTS) {
            overlayRetryRunnable = Runnable { showOverlayWithRetry(displayId, attempt + 1) }
            mainHandler.postDelayed(overlayRetryRunnable, OVERLAY_SHOW_RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "Overlay não pôde ser adicionado ao display $displayId após ${attempt + 1} tentativas")
            // Zera lastSelection para que um próximo evento idêntico não seja descartado pelo
            // dedupe — dá nova chance de mostrar o cursor.
            lastSelection = null
        }
    }

    // --- Watchdog do FGS de captura (Tarefa 5) ---

    private fun checkCaptureServiceHealth() {
        if (!prefs.captureActive) return // usuário nunca iniciou a captura -- nada a vigiar
        if (!prefs.keepAlive || prefs.manualConnect) return // Eye Tools fork: no automatic revival
        if (EyeCaptureService.getInstance() != null) return // já rodando, tudo bem
        if (!EyeCaptureService.isGlassesReady(this)) {
            // Guard (2026-07-23): sem óculos no USB — ou sem a permissão USB deles — não há o que
            // recuperar: startForeground(connectedDevice) seria negado (SecurityException, ver
            // EyeCaptureService.isGlassesReady). Silencioso de propósito (roda a cada 15s); o
            // ATTACH/grant seguinte religa via MainActivity/próximo tick.
            return
        }

        Log.w(TAG, "Watchdog: captura deveria estar ativa mas EyeCaptureService não está rodando — tentando reiniciar")
        try {
            EyeCaptureService.start(this)
            Log.d(TAG, "Watchdog: startForegroundService disparado com sucesso")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e(TAG, "Watchdog: reinício em background negado pelo sistema — notificando usuário", e)
            notifyUserToReopen()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: falha inesperada ao tentar reiniciar a captura", e)
        }
    }

    private fun notifyUserToReopen() {
        ensureWatchdogNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID_WATCHDOG)
            .setContentTitle(getString(R.string.notif_watchdog_title))
            .setContentText(getString(R.string.notif_watchdog_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // Fix de revisao (achado Important I6): checkCaptureServiceHealth roda a cada
            // WATCHDOG_INTERVAL_MS (15s) e chama notifyUserToReopen() TODA VEZ que o restart
            // falhar - sem isto, cada ciclo reemite som/vibração/heads-up (mesmo NOTIF_ID,
            // IMPORTANCE_HIGH) enquanto o usuario nao reabrir o app, virando spam de alerta a
            // cada 15s. setOnlyAlertOnce faz o sistema so alertar (som/vibra/heads-up) a
            // primeira vez que a notificacao aparece; atualizacoes seguintes com o mesmo ID
            // continuam visiveis na bandeja, so silenciosas.
            .setOnlyAlertOnce(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_WATCHDOG, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Watchdog: sem permissão POST_NOTIFICATIONS pra avisar o usuário", e)
        }
    }

    private fun ensureWatchdogNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID_WATCHDOG,
            getString(R.string.notif_channel_watchdog),
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
