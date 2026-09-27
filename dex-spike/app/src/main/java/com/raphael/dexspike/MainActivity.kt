package com.raphael.dexspike

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CATEGORY_SAMSUNG_DESKTOP = "com.samsung.android.hardware.display.category.DESKTOP"

/** Um display encontrado, com a(s) categoria(s) em que apareceu durante a varredura. */
private data class DisplayEntry(
    val display: Display,
    val isDesktop: Boolean,
    val isPresentation: Boolean
)

class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logBuilder = StringBuilder()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var currentEntries: List<DisplayEntry> = emptyList()
    private var selectedDisplayId: Int? = null

    // Views construídas em código (sem XML, sem bibliotecas externas).
    private lateinit var statusText: TextView
    private lateinit var displayListContainer: LinearLayout
    private lateinit var reasonText: TextView
    private lateinit var overlayButton: Button
    private lateinit var tapCenterButton: Button
    private lateinit var tapCornersButton: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var displayManager: DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            appendLog("Display adicionado: id=$displayId")
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            appendLog("Display removido: id=$displayId")
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            val name = runCatching { displayManager.getDisplay(displayId)?.name }.getOrNull()
            appendLog("Display alterado: id=$displayId${if (name != null) " \"$name\"" else ""}")
            refreshDisplays()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DisplayManager::class.java)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        refreshDisplays()
        updateServiceStatus()
    }

    override fun onPause() {
        super.onPause()
        displayManager.unregisterDisplayListener(displayListener)
    }

    // ---------------------------------------------------------------------
    // Construção da UI (programática)
    // ---------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // --- Painel superior: status, displays, botões ---
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        statusText = TextView(this).apply { textSize = 16f }
        topPanel.addView(statusText)

        val openSettingsButton = Button(this).apply {
            text = "Abrir configurações de acessibilidade"
            setOnClickListener {
                appendLog("Clique: Abrir configurações de acessibilidade")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        topPanel.addView(openSettingsButton)

        topPanel.addView(TextView(this).apply {
            text = "Displays:"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
        })

        displayListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        topPanel.addView(displayListContainer)

        overlayButton = Button(this).apply {
            text = "S4: Overlay 15s"
            setOnClickListener { onOverlayClicked() }
        }
        tapCenterButton = Button(this).apply {
            text = "S3: Tap centro em 5s"
            setOnClickListener { onTapCenterClicked() }
        }
        tapCornersButton = Button(this).apply {
            text = "S3: Sequência de 4 cantos em 5s"
            setOnClickListener { onTapCornersClicked() }
        }
        topPanel.addView(overlayButton)
        topPanel.addView(tapCenterButton)
        topPanel.addView(tapCornersButton)

        reasonText = TextView(this).apply {
            setTextColor(Color.RED)
            setPadding(0, dp(4), 0, 0)
        }
        topPanel.addView(reasonText)

        root.addView(topPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- Painel inferior: log ---
        val logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeaderRow.addView(TextView(this).apply {
            text = "Log:"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val copyLogButton = Button(this).apply {
            text = "Copiar log"
            setOnClickListener { onCopyLogClicked() }
        }
        logHeaderRow.addView(copyLogButton)
        logPanel.addView(logHeaderRow)

        logText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply {
            addView(logText)
        }
        logPanel.addView(
            logScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(logPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    // ---------------------------------------------------------------------
    // Status do serviço de acessibilidade
    // ---------------------------------------------------------------------

    private fun updateServiceStatus() {
        val active = SpikeAccessibilityService.instance != null
        statusText.text = if (active) {
            "Serviço de acessibilidade: ATIVO"
        } else {
            "Serviço de acessibilidade: INATIVO (ative em Configurações)"
        }
        updateButtonsState()
    }

    // ---------------------------------------------------------------------
    // Displays
    // ---------------------------------------------------------------------

    private fun collectDisplays(): List<DisplayEntry> {
        val desktop = runCatching { displayManager.getDisplays(CATEGORY_SAMSUNG_DESKTOP) }.getOrDefault(emptyArray())
        val presentation = runCatching { displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) }.getOrDefault(emptyArray())
        val all = displayManager.displays

        val ordered = LinkedHashMap<Int, DisplayEntry>()
        for (d in desktop) {
            ordered[d.displayId] = DisplayEntry(d, isDesktop = true, isPresentation = false)
        }
        for (d in presentation) {
            val existing = ordered[d.displayId]
            ordered[d.displayId] = existing?.copy(isPresentation = true)
                ?: DisplayEntry(d, isDesktop = false, isPresentation = true)
        }
        for (d in all) {
            if (!ordered.containsKey(d.displayId)) {
                ordered[d.displayId] = DisplayEntry(d, isDesktop = false, isPresentation = false)
            }
        }
        return ordered.values.toList()
    }

    private fun refreshDisplays() {
        val entries = collectDisplays()
        currentEntries = entries

        if (selectedDisplayId == null || entries.none { it.display.displayId == selectedDisplayId }) {
            selectedDisplayId = entries.firstOrNull { it.isDesktop }?.display?.displayId
                ?: entries.firstOrNull { it.isPresentation }?.display?.displayId
        }

        renderDisplayList(entries)
        updateButtonsState()
    }

    private fun renderDisplayList(entries: List<DisplayEntry>) {
        displayListContainer.removeAllViews()
        if (entries.isEmpty()) {
            displayListContainer.addView(TextView(this).apply { text = "(nenhum display encontrado)" })
            return
        }
        for (entry in entries) {
            val mode = entry.display.mode
            val tags = buildString {
                if (entry.isDesktop) append("[DESKTOP]")
                if (entry.isPresentation) append("[PRESENTATION]")
            }
            val label = "$tags id=${entry.display.displayId} \"${entry.display.name}\" ${mode.physicalWidth}x${mode.physicalHeight}"

            val row = TextView(this).apply {
                text = label
                setPadding(dp(12), dp(10), dp(12), dp(10))
                textSize = 14f
                isClickable = true
                setBackgroundColor(if (entry.display.displayId == selectedDisplayId) Color.LTGRAY else Color.TRANSPARENT)
                setOnClickListener {
                    selectedDisplayId = entry.display.displayId
                    appendLog("Display selecionado: id=${entry.display.displayId}")
                    renderDisplayList(currentEntries)
                    updateButtonsState()
                }
            }
            displayListContainer.addView(row)
        }
    }

    private fun selectedDisplay(): Display? =
        currentEntries.firstOrNull { it.display.displayId == selectedDisplayId }?.display

    // ---------------------------------------------------------------------
    // Estado dos botões
    // ---------------------------------------------------------------------

    private fun updateButtonsState() {
        val serviceActive = SpikeAccessibilityService.instance != null
        val hasSelection = selectedDisplayId != null

        val reasons = mutableListOf<String>()
        if (!serviceActive) reasons += "serviço de acessibilidade inativo"
        if (!hasSelection) reasons += "nenhum display selecionado"

        val enabled = serviceActive && hasSelection
        overlayButton.isEnabled = enabled
        tapCenterButton.isEnabled = enabled
        tapCornersButton.isEnabled = enabled

        reasonText.text = if (enabled) "" else "Botões desabilitados: ${reasons.joinToString(", ")}"
    }

    // ---------------------------------------------------------------------
    // Ações
    // ---------------------------------------------------------------------

    private fun runCountdownThen(seconds: Int, action: () -> Unit) {
        appendLog("Countdown iniciado: ${seconds}s")
        object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L) + 1
                appendLog("  ... $secLeft")
            }

            override fun onFinish() {
                appendLog("Countdown concluído")
                action()
            }
        }.start()
    }

    private fun onOverlayClicked() {
        val display = selectedDisplay() ?: return
        appendLog("S4: solicitando overlay de 15s no display ${display.displayId}")
        val service = SpikeAccessibilityService.instance
        if (service == null) {
            appendLog("S4: serviço de acessibilidade não disponível")
            return
        }
        service.showOverlay(display, 15_000L) { event -> appendLog(event) }
    }

    private fun onTapCenterClicked() {
        val display = selectedDisplay() ?: return
        appendLog("S3: preparando tap central no display ${display.displayId} (5s)")
        runCountdownThen(5) {
            val service = SpikeAccessibilityService.instance
            if (service == null) {
                appendLog("S3: serviço de acessibilidade não disponível")
                return@runCountdownThen
            }
            val mode = display.mode
            val cx = mode.physicalWidth / 2f
            val cy = mode.physicalHeight / 2f
            service.tapAt(display.displayId, cx, cy) { result -> appendLog(result) }
        }
    }

    private fun onTapCornersClicked() {
        val display = selectedDisplay() ?: return
        appendLog("S3: preparando sequência de 4 cantos no display ${display.displayId} (5s)")
        runCountdownThen(5) {
            val service = SpikeAccessibilityService.instance
            if (service == null) {
                appendLog("S3: serviço de acessibilidade não disponível")
                return@runCountdownThen
            }
            val mode = display.mode
            val w = mode.physicalWidth.toFloat()
            val h = mode.physicalHeight.toFloat()
            val points = listOf(
                0.10f * w to 0.10f * h,
                0.90f * w to 0.10f * h,
                0.90f * w to 0.90f * h,
                0.10f * w to 0.90f * h
            )
            points.forEachIndexed { index, (px, py) ->
                mainHandler.postDelayed({
                    SpikeAccessibilityService.instance?.tapAt(display.displayId, px, py) { result -> appendLog(result) }
                        ?: appendLog("S3: serviço de acessibilidade não disponível")
                }, index * 1000L)
            }
        }
    }

    private fun onCopyLogClicked() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("dex-spike log", logBuilder.toString()))
        appendLog("Log copiado para a área de transferência")
    }

    // ---------------------------------------------------------------------
    // Log
    // ---------------------------------------------------------------------

    private fun appendLog(message: String) {
        val ts = timestampFormat.format(Date())
        logBuilder.append(ts).append("  ").append(message).append('\n')
        logText.text = logBuilder.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
