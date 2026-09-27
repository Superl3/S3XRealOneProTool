package com.raphael.handmouse.input

import com.raphael.handmouse.input.AppNameMatcher.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TDD do casamento nome-falado ↔ label de app instalado (2026-07-23): normalização compartilhada
 * com o parser (minúsculas/sem acento), artigos iniciais descartados ("o youtube" == "youtube"),
 * ranking igualdade > prefixo > contém, empate = label mais curto.
 */
class AppNameMatcherTest {

    private val apps = listOf(
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("YouTube Music", "com.google.android.apps.youtube.music"),
        InstalledApp("Chrome", "com.android.chrome"),
        InstalledApp("Câmera", "com.sec.android.app.camera"),
        InstalledApp("Configurações", "com.android.settings"),
    )

    @Test
    fun `igualdade exata vence prefixo`() {
        assertEquals("com.google.android.youtube", AppNameMatcher.match("youtube", apps)?.packageName)
    }

    @Test
    fun `acentos e caixa nao importam`() {
        assertEquals("com.sec.android.app.camera", AppNameMatcher.match("camera", apps)?.packageName)
        assertEquals("com.android.chrome", AppNameMatcher.match("CHROME", apps)?.packageName)
    }

    @Test
    fun `artigo inicial e descartado`() {
        assertEquals("com.google.android.youtube", AppNameMatcher.match("o YouTube", apps)?.packageName)
        assertEquals("com.android.chrome", AppNameMatcher.match("the chrome", apps)?.packageName)
    }

    @Test
    fun `prefixo casa quando nao ha igualdade`() {
        assertEquals("com.android.settings", AppNameMatcher.match("config", apps)?.packageName)
    }

    @Test
    fun `prefixo ambiguo escolhe o label mais curto`() {
        // "youtube m..." não; "youtu" prefixa YouTube E YouTube Music -> o mais curto vence.
        assertEquals("com.google.android.youtube", AppNameMatcher.match("youtu", apps)?.packageName)
    }

    @Test
    fun `contem casa por ultimo`() {
        assertEquals("com.google.android.apps.youtube.music", AppNameMatcher.match("music", apps)?.packageName)
    }

    @Test
    fun `pontuacao final do reconhecedor nao impede o casamento`() {
        // 3ª rodada de tuning (2026-07-23): "abrir WhatsApp." (ponto do formatter on-device)
        // chegava aqui como query "WhatsApp." e não casava nada — a normalização agora descarta
        // pontuação dos dois lados (query e label).
        assertEquals("com.google.android.youtube", AppNameMatcher.match("YouTube.", apps)?.packageName)
        assertEquals("com.android.chrome", AppNameMatcher.match("o Chrome!", apps)?.packageName)
    }

    @Test
    fun `sem casamento retorna null`() {
        assertNull(AppNameMatcher.match("aplicativo inexistente", apps))
        assertNull(AppNameMatcher.match("", apps))
    }
}
