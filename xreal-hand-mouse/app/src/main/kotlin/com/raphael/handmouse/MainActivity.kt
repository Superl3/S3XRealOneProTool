package com.raphael.handmouse

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.raphael.handmouse.capture.GlassesConnection
import com.raphael.handmouse.capture.UsbPermissionRequests
import com.raphael.handmouse.enhance.EnhanceService
import com.raphael.handmouse.enhance.VideoEnhancer
import com.raphael.handmouse.overlay.DexDisplayMonitor
import com.raphael.handmouse.recording.EyeRecorder
import com.raphael.handmouse.recording.RecordingOutput
import com.raphael.handmouse.service.EyeCaptureService
import com.raphael.handmouse.service.HandMouseAccessibilityService
import com.raphael.handmouse.tracking.HandTracker
import com.raphael.handmouse.tracking.PinchDetector
import com.raphael.handmouse.tracking.PinchEvent
import com.raphael.handmouse.ui.HandOverlayView
import com.raphael.handmouse.util.Prefs

/**
 * Wizard de setup + preview + debug de tracking. Passos: permissão CAMERA (pré-condição do
 * grant USB para dispositivo de classe vídeo), POST_NOTIFICATIONS, isenção de otimização de
 * bateria — e só então inicia o [EyeCaptureService], que conduz os 2 grants USB sequenciais
 * (HID + câmera pós re-enumeração). O pipeline (captura + conversão + tracking) vive no
 * serviço; esta Activity só se conecta a ele como listener e hospeda o preview (Tarefa 3:
 * trocado de `SurfaceView` pra `ImageView` + [HandOverlayView] — ver Javadoc de
 * `EyeCaptureService` sobre a integração do `ImageReader`) e o [PinchDetector] local usado
 * só pra colorir o HUD de debug (a lógica de cursor/clique real é Tarefa 4/5).
 */
class MainActivity : AppCompatActivity(), EyeCaptureService.StateListener, EyeCaptureService.TrackingListener,
    EyeCaptureService.RecorderStatusListener, EnhanceService.Listener {

    companion object {
        private const val HANDSHAKE_WARNING_SECONDS = 10
        private const val AUTO_CONNECT_DELAY_MS = 4_000L
        private const val USB_PERMISSION_TIMEOUT_MS = 20_000L

        /** Grant USB pedido PELA ACTIVITY antes de iniciar o FGS (fix 2026-07-23) — ver
         * [ensureUsbPermissionThenStart]. */
        private const val ACTION_MAIN_USB_PERMISSION = "com.raphael.handmouse.MAIN_USB_PERMISSION"
    }

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var accessibilityDot: View
    private lateinit var countdownText: TextView
    private lateinit var logText: TextView
    private lateinit var btnGrantPermissions: Button
    private lateinit var btnStartCapture: Button
    private var pendingUsbPermissionDeviceName: String? = null
    private var pendingUsbPermissionUserInitiated = false
    private var startAfterCameraPermission = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoConnectRunnable = Runnable {
        if (!prefs.manualConnect && EyeCaptureService.getInstance() == null &&
            allWizardPermissionsGranted() && EyeCaptureService.isGlassesAttached(this)) {
            ensureUsbPermissionThenStart()
        }
    }
    private val usbPermissionTimeoutRunnable = Runnable {
        pendingUsbPermissionDeviceName?.let { deviceName ->
            UsbPermissionRequests.finish(deviceName)
            pendingUsbPermissionDeviceName = null
            pendingUsbPermissionUserInitiated = false
            appendLog("USB permission timed out. Tap Connect to retry.")
            updateConnectionButtons()
        }
    }
    private lateinit var setupCard: View
    private lateinit var setupCompleteLabel: View
    private lateinit var btnToggleSetup: Button
    private lateinit var previewImage: ImageView
    private lateinit var handOverlay: HandOverlayView
    // Preview recolhível (2026-07-23, pedido do usuário): recolhido, a Activity se DESREGISTRA
    // do fluxo de frames (removeTrackingListener) — zero trabalho de UI por frame. Persistido em
    // Prefs.previewVisible (default recolhido). O tracking/cursor do SERVIÇO não é afetado.
    private lateinit var btnTogglePreview: Button
    private lateinit var previewCard: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilitySteps: View
    private lateinit var btnOpenAccessibilitySettings: Button
    private lateinit var dexDisplayStatusText: TextView
    private lateinit var voiceLangGroup: RadioGroup
    private lateinit var usageCard: View
    private lateinit var btnToggleUsage: Button
    private lateinit var voiceCommandsText: TextView

    // Tarefa 5: checklist anti-kill Samsung (best-effort, ver runBestEffortIntent).
    private lateinit var btnBatteryAppSettings: Button
    private lateinit var btnBatteryBackgroundUsage: Button
    private lateinit var btnBatteryDeviceSettings: Button

    // Eye Tools fork: recorder card
    private lateinit var recorderStatusText: TextView
    private lateinit var recorderDetailText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnOpenLastRecording: Button
    private lateinit var btnOpenSettings: View
    private lateinit var btnStopCapture: Button
    private lateinit var btnEnhance: Button
    private var lastRecordingUri: Uri? = null

    // Só para o HUD de debug (cor do esqueleto) — a lógica de cursor/clique real (Tarefa 4/5)
    // tem sua própria instância no pipeline de produção (CursorPipeline, dono:
    // HandMouseAccessibilityService).
    private val debugPinchDetector = PinchDetector()

    private lateinit var prefs: Prefs

    private var countdownTimer: CountDownTimer? = null

    // Instância PRÓPRIA (não a do HandMouseAccessibilityService) — só para status somente-
    // leitura (nome/bounds físicos do display), reaproveitando o padrão do dex-spike; não cria
    // nenhuma janela (ver Javadoc de DexDisplayMonitor.windowBounds sobre por que MainActivity
    // não chama esse método).
    private lateinit var dexDisplayMonitor: DexDisplayMonitor

    private val requestCameraPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Camera permission granted" else "Camera permission denied")
            updatePermissionsButtonState()
            if (startAfterCameraPermission) {
                startAfterCameraPermission = false
                if (granted) ensureUsbPermissionThenStart(userInitiated = true)
            }
        }

    private val requestNotificationsPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Notification permission granted" else "Notification permission denied")
            updatePermissionsButtonState()
        }

    private val requestRecordAudioPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied — voice control unavailable")
            // fix de revisão final 2026-07-23: se a captura já está rodando, o FGS foi iniciado
            // (talvez) SEM o type de microfone — atualiza os types agora em vez de esperar um
            // reinício do serviço (ver EyeCaptureService.refreshForegroundServiceTypes).
            if (granted) EyeCaptureService.getInstance()?.refreshForegroundServiceTypes()
            updatePermissionsButtonState()
        }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                appendLog("USB attached — checking for XREAL glasses...")
                onGlassesAttached()
            }
        }
    }

    // Resultado do grant USB pedido pela Activity (ver ensureUsbPermissionThenStart): concedido →
    // inicia o FGS de captura (agora a isenção "USB Device" do tipo connectedDevice existe).
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_MAIN_USB_PERMISSION) return
            val callbackDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val pendingName = pendingUsbPermissionDeviceName
            UsbPermissionRequests.finish(callbackDevice?.deviceName ?: pendingName)
            if (pendingName == null || callbackDevice?.deviceName != pendingName) {
                appendLog("Ignoring USB response from an earlier device")
                return
            }
            pendingUsbPermissionDeviceName = null
            val userInitiated = pendingUsbPermissionUserInitiated
            pendingUsbPermissionUserInitiated = false
            uiHandler.removeCallbacks(usbPermissionTimeoutRunnable)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                appendLog("Glasses USB permission granted — starting capture")
                EyeCaptureService.getInstance()?.onUsbPermissionResolved()
                startCaptureService(userInitiated)
            } else {
                appendLog("Glasses USB permission denied — tap Connect to retry")
                updateConnectionButtons()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        accessibilityDot = findViewById(R.id.accessibilityDot)
        countdownText = findViewById(R.id.countdownText)
        logText = findViewById(R.id.logText)
        logText.movementMethod = ScrollingMovementMethod()
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnStartCapture = findViewById(R.id.btnStartCapture)
        setupCard = findViewById(R.id.setupCard)
        setupCompleteLabel = findViewById(R.id.setupCompleteLabel)
        btnToggleSetup = findViewById(R.id.btnToggleSetup)
        previewImage = findViewById(R.id.previewImage)
        handOverlay = findViewById(R.id.handOverlay)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        accessibilitySteps = findViewById(R.id.accessibilitySteps)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
        dexDisplayStatusText = findViewById(R.id.dexDisplayStatusText)
        voiceLangGroup = findViewById(R.id.voiceLangGroup)
        usageCard = findViewById(R.id.usageCard)
        btnToggleUsage = findViewById(R.id.btnToggleUsage)
        voiceCommandsText = findViewById(R.id.voiceCommandsText)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        previewCard = findViewById(R.id.previewCard)
        btnBatteryAppSettings = findViewById(R.id.btnBatteryAppSettings)
        btnBatteryBackgroundUsage = findViewById(R.id.btnBatteryBackgroundUsage)
        btnBatteryDeviceSettings = findViewById(R.id.btnBatteryDeviceSettings)
        recorderStatusText = findViewById(R.id.recorderStatusText)
        recorderDetailText = findViewById(R.id.recorderDetailText)
        btnRecord = findViewById(R.id.btnRecord)
        btnOpenLastRecording = findViewById(R.id.btnOpenLastRecording)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnStopCapture = findViewById(R.id.btnStopCapture)
        btnEnhance = findViewById(R.id.btnEnhanceRecordings)
        btnEnhance.setOnClickListener { onEnhanceClicked() }

        // Descriptions are not always-visible text: the "info" button next to a section title opens
        // the full text in a popup, and the title itself carries it as a tooltip (long-press / hover;
        // the system cuts a tooltip at three lines, so the popup is the complete version).
        bindDescription(R.id.sectionRecorderTitle, R.id.btnInfoRecorder, R.string.recorder_hint)
        bindDescription(R.id.sectionSetupTitle, R.id.btnInfoSetup, R.string.setup_onetime_note)
        bindDescription(R.id.sectionBatteryTitle, R.id.btnInfoBattery, R.string.battery_checklist_title)

        btnRecord.setOnClickListener { toggleRecording() }
        btnOpenSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnOpenLastRecording.setOnClickListener { openLastRecording() }
        btnStopCapture.setOnClickListener {
            appendLog("Stopping capture (and any recording)")
            EyeCaptureService.stop(this)
            onRecorderStatus(EyeRecorder.Status())
            updatePermissionsButtonState()
        }

        prefs = Prefs(this)

        appendLog(getString(R.string.usb_prompt_warning))

        btnGrantPermissions.setOnClickListener { runPermissionWizard() }
        btnStartCapture.setOnClickListener { startCapture() }
        btnToggleSetup.setOnClickListener {
            prefs.setupSectionExpanded = !prefs.setupSectionExpanded
            applySetupSectionState()
        }
        btnTogglePreview.setOnClickListener { setPreviewVisible(!prefs.previewVisible) }
        btnOpenAccessibilitySettings.setOnClickListener {
            appendLog("Opening Accessibility Settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnToggleUsage.setOnClickListener { setUsageVisible(!prefs.usageVisible) }
        setUsageVisible(prefs.usageVisible)
        updateVoiceCommandsText()

        // Korean or English recognition; the choice applies to the next listening session (the
        // VoiceCommandController lê o pref a cada startListening — sem restart).
        val checkedId = if (prefs.voiceLanguage == "ko-KR") R.id.voiceLangKo else R.id.voiceLangEn
        voiceLangGroup.check(checkedId)
        voiceLangGroup.setOnCheckedChangeListener { _, id ->
            prefs.voiceLanguage = when (id) {
                R.id.voiceLangKo -> "ko-KR"
                else -> "en-US"
            }
            appendLog("Voice recognition language: ${prefs.voiceLanguage}")
            updateVoiceCommandsText()
        }

        btnBatteryAppSettings.setOnClickListener {
            runBestEffortIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        btnBatteryBackgroundUsage.setOnClickListener {
            runBestEffortIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        btnBatteryDeviceSettings.setOnClickListener {
            runBestEffortIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        }

        dexDisplayMonitor = DexDisplayMonitor(this).apply {
            listener = DexDisplayMonitor.Listener { display -> updateDexDisplayStatus(display) }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val permFilter = IntentFilter(ACTION_MAIN_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            // RECEIVER_EXPORTED: o resultado do requestPermission chega do system_server (mesmo
            // padrão do GlassesConnection/EyeCaptureService).
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbAttachReceiver, filter)
            registerReceiver(usbPermissionReceiver, permFilter)
        }

        updatePermissionsButtonState()
        applySetupSectionState()
    }

    override fun onResume() {
        super.onResume()
        // Registro SEMPRE no companion (fix 2026-07-24, caso 2 do "status preso" — ver
        // EyeCaptureService.stateListener): não depende de a instância do serviço já existir;
        // se ele nascer depois desta Activity, a primeira transição já chega aqui.
        EyeCaptureService.stateListener = this
        EyeCaptureService.recorderListener = this
        onRecorderStatus(EyeCaptureService.getInstance()?.recorderStatus ?: EyeRecorder.Status())
        EnhanceService.listener = this
        onEnhanceStatus(EnhanceService.status)
        EyeCaptureService.getInstance()?.let { service ->
            // Sincroniza o estado ATUAL na hora do registro (fix 2026-07-23, "status preso em
            // 'Permissões OK'"): o listener só recebe TRANSIÇÕES — uma Activity (re)criada com
            // o serviço já em STREAMING (ex.: processo reiniciado por re-enumeração USB, que
            // mata e recria Activity e serviço em ordens diferentes) nunca via estado nenhum e
            // exibia o texto inicial do wizard pra sempre, parecendo "tracking morto" com o
            // pipeline saudável.
            onStateChanged(service.state, getString(R.string.log_state_synced))
        }
        setPreviewVisible(prefs.previewVisible) // aplica o estado salvo + (des)registra o listener
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appendLog("Opened by USB attach")
            onGlassesAttached()
            intent?.action = Intent.ACTION_MAIN // consume the attach intent once, not on every resume
        } else {
            maybeResumeCapture()
        }
        dexDisplayMonitor.start()
        updateAccessibilityStatus()
        updateConnectionButtons()
    }

    /** Recuperação ao abrir o app (6ª rodada 2026-07-23): se uma captura desejada
     * ([Prefs.captureActive]) está PARADA (serviço morto, ou vivo em IDLE/ERROR depois de um
     * reset de link em que os óculos sumiram do barramento), religa a cadeia — com a Activity
     * em foreground o diálogo de permissão USB pode aparecer, o que o serviço em background não
     * consegue garantir. Estados de handshake em andamento/STREAMING não são tocados (retryScan
     * num pipeline saudável re-dispararia a ativação da câmera por cima do stream vivo). */
    private fun maybeResumeCapture() {
        if (prefs.manualConnect || !prefs.captureActive || !allWizardPermissionsGranted()) return
        val service = EyeCaptureService.getInstance()
        if (service == null) {
            appendLog("Capture requested but service stopped — resuming")
            scheduleAutoConnect()
            return
        }
        // A running service owns attach and recovery; the Activity must not race its USB scan.
    }

    override fun onPause() {
        super.onPause()
        EyeCaptureService.stateListener = null
        EyeCaptureService.recorderListener = null
        EnhanceService.listener = null
        EyeCaptureService.getInstance()?.removeTrackingListener(this)
        // Em background ninguém desenha o preview — desliga a alocação por frame no serviço
        // (onResume religa via setPreviewVisible conforme a preferência salva).
        EyeCaptureService.getInstance()?.previewFramesEnabled = false
        dexDisplayMonitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(autoConnectRunnable)
        uiHandler.removeCallbacks(usbPermissionTimeoutRunnable)
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        countdownTimer?.cancel()
    }

    // --- Status do HandMouseAccessibilityService + display do DeX (Tarefa 4) ---

    private fun updateAccessibilityStatus() {
        val active = HandMouseAccessibilityService.instance != null
        accessibilityStatusText.text = getString(
            if (active) R.string.accessibility_status_active else R.string.accessibility_status_inactive
        )
        tintDot(accessibilityDot, if (active) R.color.hm_accent else R.color.hm_error)
        // The 3 steps are instructions, not a description: shown only while there is something to do.
        accessibilitySteps.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** Colore o dot oval de status (@drawable/status_dot) via backgroundTintList. */
    private fun tintDot(dot: View, colorRes: Int) {
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun updateDexDisplayStatus(display: android.view.Display?) {
        dexDisplayStatusText.text = if (display == null) {
            getString(R.string.dex_display_status_none)
        } else {
            val mode = display.mode
            "Display do DeX: id=${display.displayId} \"${display.name}\" ${mode.physicalWidth}x${mode.physicalHeight}"
        }
    }

    // --- Wizard de permissões ---

    private fun runPermissionWizard() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestBatteryExemption()
        // Voz (fix de revisão final 2026-07-23): o pedido de mic vem DEPOIS da isenção de
        // bateria e SEM return — mic é opcional ("negar não bloqueia nada"), e o return antigo
        // fazia uma negação permanente (auto-deny do Android) travar o wizard antes do passo
        // de bateria, que é o load-bearing anti-kill da Samsung.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        updatePermissionsButtonState()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            appendLog("Requesting battery optimization exemption...")
            runBestEffortIntent(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    /** Dispara [intent] sem checar de antemão se existe uma Activity que a resolva — algumas
     * telas de configuração (principalmente as específicas de fabricante, Samsung neste caso)
     * variam por versão do One UI e não têm uma forma confiável de checar disponibilidade antes
     * de tentar. Falha vira só um log, nunca crash (brief Tarefa 5: "deep-link best-effort com
     * try/catch" — card de checklist anti-kill Samsung, PLANO.md §7). */
    private fun runBestEffortIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("Could not open Settings: ${e.message}")
        }
    }

    private fun allWizardPermissionsGranted(): Boolean {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return cameraOk
    }

    private fun updatePermissionsButtonState() {
        val ready = allWizardPermissionsGranted()
        updateConnectionButtons()
        // Só escreve o texto do wizard quando o pipeline NÃO tem estado próprio a exibir (fix
        // 2026-07-24, "status volta pra 'Permissões OK' com o stream vivo"): este método roda
        // no onCreate e em todo callback de permissão, e sobrescrevia incondicionalmente o
        // texto real ([STREAMING]/[ERROR]...) que onStateChanged mantém.
        val pipelineState = EyeCaptureService.getInstance()?.state
        if (ready && (pipelineState == null || pipelineState == EyeCaptureService.PipelineState.IDLE)) {
            statusText.text = getString(R.string.status_permissions_ready)
        }
    }

    /** Aplica o estado colapsado/expandido do card de Setup (2026-07-24, onboarding UX):
     * botão Show/Hide e checkmark "✓ Complete" (só quando [Prefs.setupCompleted]). Chamado no
     * onCreate, no toggle manual e ao completar o setup pela primeira vez (ver
     * [onStateChanged]). */
    private fun applySetupSectionState() {
        val expanded = prefs.setupSectionExpanded
        setupCard.visibility = if (expanded) View.VISIBLE else View.GONE
        btnToggleSetup.text = getString(if (expanded) R.string.btn_preview_hide else R.string.btn_preview_show)
        setupCompleteLabel.visibility = if (prefs.setupCompleted) View.VISIBLE else View.GONE
    }

    // --- Captura ---

    /** Ver [ensureUsbPermissionThenStart] — o botão agora passa pelo grant USB primeiro. */
    private fun startCapture() {
        uiHandler.removeCallbacks(autoConnectRunnable)
        if (!allWizardPermissionsGranted()) {
            startAfterCameraPermission = true
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        ensureUsbPermissionThenStart(userInitiated = true)
    }

    private fun scheduleAutoConnect() {
        if (prefs.manualConnect) return
        uiHandler.removeCallbacks(autoConnectRunnable)
        uiHandler.postDelayed(autoConnectRunnable, AUTO_CONNECT_DELAY_MS)
    }

    /**
     * Fix do "tenho que plugar/desplugar várias vezes" (2026-07-23): o FGS `connectedDevice` SÓ
     * pode dar `startForeground` com a permissão USB dos óculos JÁ concedida (isenção "USB
     * Device" do tipo de FGS) — mas quem pedia essa permissão era o próprio serviço, DEPOIS de
     * iniciar: ovo-e-galinha que só se resolvia quando o Android auto-concedia via intent-filter
     * de USB_DEVICE_ATTACHED (sorte de timing). A ACTIVITY agora conduz o grant ANTES de iniciar
     * o serviço: acha os óculos no barramento → pede a permissão se faltar
     * ([usbPermissionReceiver] inicia o serviço no callback) → senão inicia direto.
     */
    private fun ensureUsbPermissionThenStart(userInitiated: Boolean = false) {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val glasses: UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == GlassesConnection.XREAL_VID }
        if (glasses == null) {
            appendLog("No XREAL glasses on USB — connect the cable and try again")
            return
        }
        if (userInitiated) {
            // A previous automatic attempt may have lost its USB permission callback behind DeX.
            // A button press explicitly starts a fresh request for this enumeration.
            pendingUsbPermissionDeviceName?.let { UsbPermissionRequests.finish(it) }
            pendingUsbPermissionDeviceName = null
            pendingUsbPermissionUserInitiated = false
            uiHandler.removeCallbacks(usbPermissionTimeoutRunnable)
            UsbPermissionRequests.finish(glasses.deviceName)
        }
        if (!usb.hasPermission(glasses)) {
            if (!UsbPermissionRequests.begin(glasses.deviceName)) {
                appendLog("USB permission already requested for this connection; waiting for a response")
                return
            }
            pendingUsbPermissionDeviceName = glasses.deviceName
            pendingUsbPermissionUserInitiated = userInitiated
            uiHandler.removeCallbacks(usbPermissionTimeoutRunnable)
            uiHandler.postDelayed(usbPermissionTimeoutRunnable, USB_PERMISSION_TIMEOUT_MS)
            appendLog("Requesting glasses USB permission...")
            btnStartCapture.isEnabled = false
            val intent = Intent(ACTION_MAIN_USB_PERMISSION).apply { setPackage(packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            try {
                usb.requestPermission(glasses, PendingIntent.getBroadcast(this, 3, intent, flags))
            } catch (e: Exception) {
                UsbPermissionRequests.finish(glasses.deviceName)
                pendingUsbPermissionDeviceName = null
                pendingUsbPermissionUserInitiated = false
                uiHandler.removeCallbacks(usbPermissionTimeoutRunnable)
                updateConnectionButtons()
                appendLog("USB permission request failed: ${e.message}")
            }
            return
        }
        UsbPermissionRequests.finish(glasses.deviceName)
        startCaptureService(userInitiated)
    }

    private fun startCaptureService(userInitiated: Boolean) {
        uiHandler.removeCallbacks(autoConnectRunnable)
        appendLog("Starting EyeCaptureService...")
        prefs.captureActive = true
        val existing = EyeCaptureService.getInstance()
        if (existing == null) EyeCaptureService.start(this) else existing.retryScan(userInitiated = userInitiated)
        EyeCaptureService.stateListener = this
        if (prefs.previewVisible) {
            EyeCaptureService.getInstance()?.previewFramesEnabled = true
            EyeCaptureService.getInstance()?.addTrackingListener(this)
        }
        btnStartCapture.isEnabled = false
    }

    /** ATTACH dos óculos (receiver dinâmico ou relaunch via intent-filter do manifest): se o
     * serviço já vive, um retryScan basta (ele mesmo religa o pipeline se preciso); se o
     * serviço morreu mas o usuário já tinha uma captura ativa ([Prefs.captureActive]), religa
     * a cadeia inteira passando pelo grant USB — sem depender de mais um plug/desplug. */
    private fun onGlassesAttached() {
        if (prefs.manualConnect) {
            if (EyeCaptureService.getInstance() == null) appendLog(getString(R.string.conn_manual_attached))
            return
        }
        val service = EyeCaptureService.getInstance()
        if (service == null && (prefs.captureActive || prefs.startOnConnect) && allWizardPermissionsGranted()) {
            scheduleAutoConnect()
        }
    }

    /** Aplica visibilidade do preview + REGISTRO no fluxo de frames de uma vez (ver o comentário
     * do campo [previewCard]): recolhido = sem listener = zero custo de UI por frame. Idempotente
     * (addTrackingListener é um Set; remove sem estar registrado é no-op) — chamado do botão, do
     * onResume (estado salvo) e do startCapture. */
    private fun setPreviewVisible(visible: Boolean) {
        prefs.previewVisible = visible
        previewCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnTogglePreview.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
        val service = EyeCaptureService.getInstance() ?: return
        // previewFramesEnabled: gate de alocação por frame no serviço (ver Javadoc lá) — segue
        // a visibilidade do preview 1:1.
        service.previewFramesEnabled = visible
        if (visible) service.addTrackingListener(this) else service.removeTrackingListener(this)
    }

    /** Colapsa/expande o card "Usage" (gestos + comandos de voz, 2026-09-30) — mesmo padrão do
     * preview, sem efeito colateral no serviço (é conteúdo estático). */
    private fun setUsageVisible(visible: Boolean) {
        prefs.usageVisible = visible
        usageCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnToggleUsage.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
    }

    /** Show the Korean or English command list for the selected recognition language. */
    private fun updateVoiceCommandsText() {
        voiceCommandsText.text = getString(
            if (prefs.voiceLanguage == "ko-KR") R.string.voice_commands_list_ko
            else R.string.voice_commands_list_en
        )
    }

    // --- EyeCaptureService.StateListener ---

    /** Connect only when nothing is running (or it failed); Disconnect whenever a service lives. */
    private fun updateConnectionButtons() {
        val service = EyeCaptureService.getInstance()
        btnStartCapture.isEnabled = service?.state != EyeCaptureService.PipelineState.STREAMING
        btnStopCapture.isEnabled = service != null
    }

    /** Texto da status pill (2026-09-30): rótulo curto por estado (a pill divide a linha com o
     * título do app); a mensagem detalhada do serviço vai só pro log, inclusive em ERROR. */
    private fun statusLabel(state: EyeCaptureService.PipelineState): String = when (state) {
        EyeCaptureService.PipelineState.IDLE -> getString(R.string.state_idle)
        EyeCaptureService.PipelineState.CONNECTING_HID -> getString(R.string.state_connecting_hid)
        EyeCaptureService.PipelineState.CAMERA_ENABLING -> getString(R.string.state_camera_enabling)
        EyeCaptureService.PipelineState.FINDING_CAMERA -> getString(R.string.state_finding_camera)
        EyeCaptureService.PipelineState.REQUESTING_CAMERA_PERMISSION ->
            getString(R.string.state_requesting_camera_permission)
        EyeCaptureService.PipelineState.STREAMING -> getString(R.string.state_streaming)
        EyeCaptureService.PipelineState.ERROR -> getString(R.string.state_error)
    }

    override fun onStateChanged(state: EyeCaptureService.PipelineState, message: String) {
        statusText.text = statusLabel(state)
        appendLog(message)
        updateConnectionButtons()

        // Dot da status pill: verde = streaming OK, vermelho = erro, âmbar = qualquer estado
        // transitório do handshake (mesma paleta do esqueleto/HUD).
        tintDot(statusDot, when (state) {
            EyeCaptureService.PipelineState.STREAMING -> R.color.hm_accent
            EyeCaptureService.PipelineState.ERROR -> R.color.hm_error
            else -> R.color.hm_warning
        })

        // Onboarding UX (2026-07-24): 1ª vez que o pipeline chega a STREAMING com as permissões
        // do wizard OK, marca o setup como completo e colapsa o card automaticamente — dali em
        // diante o usuário controla show/hide manualmente (ver applySetupSectionState).
        if (state == EyeCaptureService.PipelineState.STREAMING && !prefs.setupCompleted && allWizardPermissionsGranted()) {
            prefs.setupCompleted = true
            prefs.setupSectionExpanded = false
            applySetupSectionState()
        }

        when (state) {
            EyeCaptureService.PipelineState.CAMERA_ENABLING,
            EyeCaptureService.PipelineState.REQUESTING_CAMERA_PERMISSION -> startHandshakeCountdown()
            EyeCaptureService.PipelineState.STREAMING,
            EyeCaptureService.PipelineState.ERROR -> stopHandshakeCountdown()
            else -> {}
        }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }

    private fun startHandshakeCountdown() {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(HANDSHAKE_WARNING_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdownText.text = getString(R.string.countdown_accept_usb_prompt, millisUntilFinished / 1000)
                countdownText.visibility = View.VISIBLE
            }

            override fun onFinish() {
                countdownText.text = ""
                countdownText.visibility = View.GONE
            }
        }.start()
    }

    private fun stopHandshakeCountdown() {
        countdownTimer?.cancel()
        countdownText.text = ""
        countdownText.visibility = View.GONE
    }

    // --- EyeCaptureService.TrackingListener (debug: preview + esqueleto + HUD) ---

    override fun onFrameConverted(bitmap: Bitmap, conversionMs: Double) {
        previewImage.setImageBitmap(bitmap)
        lastConversionMs = conversionMs
    }

    override fun onHandResult(result: HandTracker.Result) {
        val pinchEvent = debugPinchDetector.update(result.isoPoints, result.timestampMs)
        if (pinchEvent != null) {
            appendLog("Pinch: $pinchEvent")
        }

        val metrics = HandOverlayView.Metrics(
            inferenceFps = result.inferenceFps,
            endToEndLatencyMs = result.latencyMs,
            conversionMs = lastConversionMs,
        )
        handOverlay.update(result.landmarks, debugPinchDetector.isPinched, metrics)
    }

    override fun onHandLost() {
        debugPinchDetector.reset() // evita EMA/debounce "velhos" contaminarem quando a mão voltar
        handOverlay.clearHand()
    }

    override fun onTrackingError(message: String) {
        appendLog("Tracking error: $message")
    }

    // último tempo de conversão conhecido (atualizado a cada frame convertido); usado como
    // aproximação no HUD quando um resultado de mão chega (ver HandOverlayView.Metrics) —
    // resultados chegam de forma assíncrona, não há correlação exata frame<->resultado sem
    // um identificador de frame dedicado (fora de escopo pra uma métrica de debug).
    private var lastConversionMs: Double = 0.0

    // --- Eye Tools fork: recorder card ---

    private fun bindDescription(titleId: Int, infoButtonId: Int, textRes: Int) {
        val text = getString(textRes)
        TooltipCompat.setTooltipText(findViewById(titleId), text)
        findViewById<View>(infoButtonId).setOnClickListener { showDescriptionPopup(it, text) }
    }

    /** Full-width card under [anchor]; tap outside to dismiss. Does not shift the page layout. */
    private fun showDescriptionPopup(anchor: View, text: String) {
        val dp = resources.displayMetrics.density
        val body = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.hm_text_primary))
            setLineSpacing(2 * dp, 1f)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.hm_surface_raised))
                setStroke((1 * dp).toInt(), ContextCompat.getColor(context, R.color.hm_text_secondary))
                cornerRadius = 12 * dp
            }
        }
        val margin = (16 * dp).toInt()
        val popup = PopupWindow(body, resources.displayMetrics.widthPixels - 2 * margin,
            ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = 8 * dp
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        popup.showAsDropDown(anchor, margin - loc[0], 0)
    }

    /** Same dispatcher as tiles / notification / buttons: starts capture too when needed. */
    private fun toggleRecording() {
        EyeCaptureService.perform(this, com.raphael.handmouse.service.EyeAction.RECORD_TOGGLE)
    }

    override fun onRecorderStatus(status: EyeRecorder.Status) {
        btnRecord.text = getString(if (status.recording) R.string.btn_record_stop else R.string.btn_record_start)
        if (status.recording) {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - status.sessionStartElapsedMs) / 1000
            val time = "%d:%02d:%02d".format(elapsed / 3600, (elapsed / 60) % 60, elapsed % 60)
            val codec = status.codecLabel + if (status.audioActive) " + " + getString(R.string.recorder_audio_on) else ""
            recorderStatusText.text = getString(
                R.string.recorder_status_fmt,
                time,
                android.text.format.Formatter.formatShortFileSize(this, status.sessionBytes),
                status.segmentIndex,
                codec,
            )
            tintText(recorderStatusText, R.color.hm_error)
        } else {
            recorderStatusText.text = getString(R.string.recorder_idle)
            tintText(recorderStatusText, R.color.hm_text_primary)
        }
        val details = ArrayList<String>()
        if (status.recording && status.waitingForStream) details += getString(R.string.recorder_waiting)
        status.currentFile?.let { details += it }
        status.lastFinishedFile?.let { details += getString(R.string.recorder_last_file, it) }
        if (status.droppedChunks > 0) details += getString(R.string.recorder_dropped, status.droppedChunks)
        status.error?.let { details += getString(R.string.recorder_error, it) }
        // The idle hint lives in a tooltip on the status line; the detail line only shows live info.
        recorderDetailText.text = details.joinToString("\n")
        recorderDetailText.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        lastRecordingUri = status.lastFinishedUri
        btnOpenLastRecording.isEnabled = lastRecordingUri != null
    }

    private fun tintText(view: TextView, colorRes: Int) {
        view.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun openLastRecording() {
        val uri = lastRecordingUri ?: return
        runBestEffortIntent(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    // --- Eye Tools fork: recording enhancer (2026-09-29) ---

    override fun onEnhanceStatus(status: EnhanceService.Status) {
        btnEnhance.text = if (status.running) {
            getString(R.string.btn_enhance_running, status.index, status.total, (status.fraction * 100).toInt())
        } else {
            getString(R.string.btn_enhance)
        }
    }

    override fun onEnhanceLog(line: String) = appendLog("Enhance: $line")

    /** Idle: pick recordings (none checked: the default deletes originals) and start. Running: offer to stop. */
    private fun onEnhanceClicked() {
        if (EnhanceService.status.running) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.enhance_cancel_title)
                .setMessage(R.string.enhance_cancel_msg)
                .setPositiveButton(R.string.enhance_cancel) { _, _ -> EnhanceService.cancel(this) }
                .setNegativeButton(R.string.enhance_keep_going, null)
                .show()
            return
        }
        if (EyeCaptureService.getInstance()?.isRecording == true) {
            appendLog(getString(R.string.enhance_busy_recording))
            return
        }
        btnEnhance.isEnabled = false
        Thread {
            val output = RecordingOutput(this)
            val candidates = output.listRecordings()
                .filter { !output.exists(it.storage, VideoEnhancer.outputName(it.displayName)) }
            val gyro = candidates.map { rec -> output.openSidecar(rec.displayName)?.use { true } ?: false }
            runOnUiThread {
                btnEnhance.isEnabled = true
                if (!isFinishing && !isDestroyed) showEnhanceDialog(candidates, gyro)
            }
        }.start()
    }

    private fun showEnhanceDialog(candidates: List<RecordingOutput.Recording>, gyro: List<Boolean>) {
        if (candidates.isEmpty()) {
            appendLog(getString(R.string.enhance_none))
            return
        }
        val labels = candidates.mapIndexed { i, rec ->
            val size = android.text.format.Formatter.formatShortFileSize(this, rec.sizeBytes)
            rec.displayName + "\n" + size + if (gyro[i]) " · " + getString(R.string.enhance_item_gyro) else ""
        }.toTypedArray<CharSequence>()
        val checked = BooleanArray(candidates.size)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enhance_dialog_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(if (prefs.enhanceDeleteOriginal) R.string.enhance_start_delete else R.string.enhance_start_keep) { _, _ ->
                val picked = candidates.filterIndexed { i, _ -> checked[i] }
                if (picked.isNotEmpty() && !EnhanceService.start(this, picked)) {
                    appendLog(getString(R.string.enhance_busy_recording))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appendLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logText.append("[$time] $message\n")
    }
}
