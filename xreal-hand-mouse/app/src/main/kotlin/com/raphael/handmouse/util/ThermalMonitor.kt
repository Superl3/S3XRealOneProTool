package com.raphael.handmouse.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Política PURA de FPS-alvo por térmica (PLANO.md Fase 5, brief Tarefa 5) — separada de
 * [ThermalMonitor] pra ser testável em JVM sem Robolectric (as constantes `THERMAL_STATUS_*` do
 * [PowerManager] são `public static final int`, inlinadas em tempo de compilação — só
 * INSTANCIAR `PowerManager`/chamar métodos reais exigiria stub do Android). 60fps normal; 40fps
 * se `THERMAL_STATUS_LIGHT` OU `headroom > 0.85`; 24fps se `THERMAL_STATUS_MODERATE` (ou pior)
 * OU `headroom > 0.95` — a condição mais severa vence quando as duas colidem.
 *
 * **Tiers 30/24/20 → 60/40/24 (2026-07-23, fluidez do cursor):** os logs de hardware mostraram
 * que a câmera Eye entrega ~60fps de verdade (1000 frames/16,8s) — o teto de 30 era o único
 * limitador da taxa de amostragem do cursor. 60 no estado NORMAL dobra a fluidez; os tiers de
 * throttling continuam derrubando a carga sob calor (o mecanismo é o mesmo, só os alvos subiram).
 */
object ThermalFpsPolicy {
    const val NORMAL_FPS = 60
    const val LIGHT_FPS = 40
    const val REDUCED_FPS = 24

    private const val LIGHT_HEADROOM_THRESHOLD = 0.85f
    private const val REDUCED_HEADROOM_THRESHOLD = 0.95f

    fun targetFps(thermalStatus: Int, headroom: Float): Int {
        val moderateOrWorse = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        return when {
            moderateOrWorse || headroom > REDUCED_HEADROOM_THRESHOLD -> REDUCED_FPS
            thermalStatus == PowerManager.THERMAL_STATUS_LIGHT || headroom > LIGHT_HEADROOM_THRESHOLD -> LIGHT_FPS
            else -> NORMAL_FPS
        }
    }
}

/**
 * Observa a térmica do aparelho durante a captura — `PowerManager.addThermalStatusListener` +
 * `getThermalHeadroom` a cada 30s (PLANO.md Fase 5, brief Tarefa 5) — e repassa o FPS-alvo
 * calculado por [ThermalFpsPolicy.targetFps] via [onTargetFpsChanged] (dono:
 * [com.raphael.handmouse.service.EyeCaptureService], que pluga isso em
 * `HandTracker.setMaxFps`). `minSdk 34` já cobre as APIs usadas (`addThermalStatusListener`
 * API 29, `getThermalHeadroom` API 30) — sem gates de versão.
 */
class ThermalMonitor(
    context: Context,
    private val onTargetFpsChanged: (Int) -> Unit,
) {
    companion object {
        private const val TAG = "ThermalMonitor"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val HEADROOM_FORECAST_SECONDS = 10
    }

    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    private var lastThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        Log.d(TAG, "Thermal status mudou: $status")
        lastThermalStatus = status
        applyPolicy()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            applyPolicy()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private var started = false

    /** Idempotente — chamar de novo sem [stop] antes não duplica listener/polling. */
    fun start() {
        if (started) return
        started = true
        powerManager.addThermalStatusListener(thermalListener)
        lastThermalStatus = powerManager.currentThermalStatus
        handler.post(pollRunnable)
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            powerManager.removeThermalStatusListener(thermalListener)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao remover thermal listener", e)
        }
        handler.removeCallbacks(pollRunnable)
    }

    private fun applyPolicy() {
        val headroom = try {
            powerManager.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler thermal headroom", e)
            Float.NaN
        }
        // NaN (leitura indisponível/falhou) não deve empurrar pra baixo o FPS por engano —
        // trata como "sem sinal de headroom" (0f), só o thermalStatus real ainda pesa.
        val headroomForPolicy = if (headroom.isNaN()) 0f else headroom
        val fps = ThermalFpsPolicy.targetFps(lastThermalStatus, headroomForPolicy)
        Log.d(TAG, "targetFps=$fps (status=$lastThermalStatus, headroom=$headroom)")
        onTargetFpsChanged(fps)
    }
}
