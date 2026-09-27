package com.raphael.handmouse.capture

import com.raphael.handmouse.glasses.UsbConfigState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Só a decisão pura de reconfigurar (skip-if-active), sem hardware. */
class GlassesConnectionDecisionTest {
    private fun cfg(uvc0: Int) = UsbConfigState(ncm = 1, ecm = 1, hidCtrl = 1, uvc0 = uvc0, uvc1 = 2)

    @Test fun `uvc0 ativa, video presente e sem force - pula reconfiguracao`() =
        assertFalse(GlassesConnection.shouldReconfigure(cfg(uvc0 = 1), forceReconfigure = false, videoInterfacePresent = true))

    // Causa-raiz do travamento "procurando UVC" (validado em hardware 2026-07-23): o device atacha
    // com uvc0=1 MAS sem a interface de video exposta — precisa do Set+re-enum p/ expo-la, entao NAO pula.
    @Test fun `uvc0 ativa mas interface de video ausente - reconfigura`() =
        assertTrue(GlassesConnection.shouldReconfigure(cfg(uvc0 = 1), forceReconfigure = false, videoInterfacePresent = false))

    @Test fun `uvc0 inativa - reconfigura`() =
        assertTrue(GlassesConnection.shouldReconfigure(cfg(uvc0 = 0), forceReconfigure = false, videoInterfacePresent = true))

    @Test fun `force reconfigure - reconfigura mesmo com uvc0 ativa e video presente`() =
        assertTrue(GlassesConnection.shouldReconfigure(cfg(uvc0 = 1), forceReconfigure = true, videoInterfacePresent = true))

    @Test fun `config desconhecida (null) - reconfigura por seguranca`() =
        assertTrue(GlassesConnection.shouldReconfigure(null, forceReconfigure = false, videoInterfacePresent = true))
}
