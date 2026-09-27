package com.raphael.handmouse.util

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD da política pura de FPS-alvo por térmica (PLANO.md Fase 5, brief Tarefa 5): 60fps normal;
 * 40fps se THERMAL_STATUS_LIGHT OU headroom>0.85; 24fps se MODERATE (ou pior) OU headroom>0.95
 * (a condição mais severa vence quando as duas colidem). As constantes `PowerManager.THERMAL_STATUS_*`
 * são `public static final int` inlinadas em tempo de compilação — rodam em JVM puro sem
 * Robolectric (só instanciar `PowerManager`/chamar seus métodos exigiria stub real).
 */
class ThermalFpsPolicyTest {

    @Test
    fun `sem throttling termico e headroom baixo - fps normal`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_NONE, headroom = 0.2f)

        assertEquals(ThermalFpsPolicy.NORMAL_FPS, fps)
    }

    @Test
    fun `thermal status LIGHT - tier LIGHT mesmo com headroom baixo`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_LIGHT, headroom = 0.1f)

        assertEquals(ThermalFpsPolicy.LIGHT_FPS, fps)
    }

    @Test
    fun `headroom maior que 0-85 sem status termico ruim - tier LIGHT`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_NONE, headroom = 0.86f)

        assertEquals(ThermalFpsPolicy.LIGHT_FPS, fps)
    }

    @Test
    fun `headroom exatamente 0-85 nao cruza o limiar - ainda fps normal`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_NONE, headroom = 0.85f)

        assertEquals(ThermalFpsPolicy.NORMAL_FPS, fps)
    }

    @Test
    fun `thermal status MODERATE - tier REDUCED mesmo com headroom baixo`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_MODERATE, headroom = 0.1f)

        assertEquals(ThermalFpsPolicy.REDUCED_FPS, fps)
    }

    @Test
    fun `thermal status SEVERE (pior que MODERATE) tambem cai para o tier REDUCED`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_SEVERE, headroom = 0f)

        assertEquals(ThermalFpsPolicy.REDUCED_FPS, fps)
    }

    @Test
    fun `headroom maior que 0-95 sem status termico ruim - tier REDUCED`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_NONE, headroom = 0.96f)

        assertEquals(ThermalFpsPolicy.REDUCED_FPS, fps)
    }

    @Test
    fun `headroom exatamente 0-95 nao cruza o limiar mais severo - fica no tier LIGHT`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_NONE, headroom = 0.95f)

        assertEquals(ThermalFpsPolicy.LIGHT_FPS, fps)
    }

    @Test
    fun `LIGHT com headroom maior que 0-95 - condicao mais severa vence (tier REDUCED)`() {
        val fps = ThermalFpsPolicy.targetFps(PowerManager.THERMAL_STATUS_LIGHT, headroom = 0.99f)

        assertEquals(ThermalFpsPolicy.REDUCED_FPS, fps)
    }
}
