# Controle por Voz — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sinal de "V" abre janela de escuta de voz (~5s) que executa voltar/início/recentes, "abrir <app>" e ditado ("escrever <texto>", "enviar", "apagar tudo") — spec `docs/superpowers/specs/2026-07-23-voice-control-design.md`.

**Architecture:** `VSignDetector` (puro, em `tracking/`, molde do `FistDetector`) detecta o gesto no `CursorPipeline`; segurado 500ms, aciona o `VoiceCommandController` (novo, em `input/`), que roda uma sessão do `SpeechRecognizer` nativo na main thread, interpreta via `VoiceCommandParser` (puro) e executa via `performGlobalAction`/`AppLauncher`/`TextInserter`. Feedback visual no `CursorOverlay`/`CursorView` (amarelo = ouvindo, flash verde/vermelho = resultado). Idioma configurável em `Prefs.voiceLanguage` (sistema/pt-BR/en-US) com seletor na `MainActivity`.

**Tech Stack:** Kotlin, Android SpeechRecognizer, AccessibilityService (já existente), MediaPipe landmarks (já existente), JUnit4 em JVM pros puros.

## Global Constraints

- minSdk 34 — pode usar qualquer API ≤ 34 sem guarda de versão (exceto onde o código existente já guarda).
- Classes de detector são PURAS (sem import Android) e vivem em `tracking/`; controllers com dependência Android vivem em `input/`.
- Comentários/Javadoc em português, no estilo denso do repo (explicam POR QUÊ, citam data e decisão).
- Thresholds de gesto são estimativa de bancada — marcar `VALIDAR/AFINAR EM HARDWARE` no Javadoc.
- Builds/testes: rodar a partir de `xreal-hand-mouse/` com `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (PowerShell).
- Git sempre com prefixo `rtk` (`rtk git add`, `rtk git commit`). Commits em português, um por task.
- Caminhos abaixo são relativos à raiz do repo (`xreal hand tracking/`).

---

### Task 1: VSignDetector (gesto de ativação)

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/tracking/VSignDetector.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/tracking/VSignDetectorTest.kt`

**Interfaces:**
- Consumes: `HandPoint` (existente em `tracking/`).
- Produces: `class VSignDetector` com `fun update(landmarks: List<HandPoint>): Boolean`, `val isVSign: Boolean`, `fun reset()` — Task 7 instancia no `CursorPipeline`.

- [ ] **Step 1: Escrever os testes (falhando)**

Criar `VSignDetectorTest.kt`. Landmarks sintéticos no mesmo truque do `FistDetectorTest`: punho na origem, PIPs (6/10/14/18) e INDEX_MCP (5) em `(1,0,0)` → o ratio ponta/PIP de cada dedo é literalmente o x da ponta. Indicador/médio estendidos ≈ 1.35; dobrados ≈ 0.9.

```kotlin
package com.raphael.handmouse.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD do detector de sinal de "V" (ativação do controle por voz, 2026-07-23 — spec
 * `2026-07-23-voice-control-design.md`): indicador+médio ESTENDIDOS (min dos ratios ponta/PIP
 * > 1.25) e anular+mindinho DOBRADOS (max dos ratios < 1.05), mesma métrica ponta/PIP invariante
 * a ângulo do FistDetector. EMA alpha 0.5, histerese dupla, debounce assimétrico 12/3 frames.
 *
 * Landmarks sintéticos: punho na origem, PIPs (6/10/14/18) e INDEX_MCP (5) em (1,0,0) → o ratio
 * de cada dedo é o x da própria ponta.
 */
class VSignDetectorTest {

    private fun landmarks(
        indexRatio: Float,
        middleRatio: Float = indexRatio,
        ringRatio: Float,
        pinkyRatio: Float = ringRatio,
    ): List<HandPoint> {
        val zero = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { zero }
        points[0] = HandPoint(0f, 0f, 0f) // WRIST
        points[5] = HandPoint(1f, 0f, 0f) // INDEX_MCP
        for (pip in intArrayOf(6, 10, 14, 18)) points[pip] = HandPoint(1f, 0f, 0f)
        points[8] = HandPoint(indexRatio, 0f, 0f)
        points[12] = HandPoint(middleRatio, 0f, 0f)
        points[16] = HandPoint(ringRatio, 0f, 0f)
        points[20] = HandPoint(pinkyRatio, 0f, 0f)
        return points
    }

    private fun vSign() = landmarks(indexRatio = 1.35f, ringRatio = 0.9f)

    @Test
    fun `sinal de V confirma apos 12 frames sustentados`() {
        val detector = VSignDetector()
        repeat(11) { assertFalse("frame ${it + 1} ainda não confirma", detector.update(vSign())) }
        assertTrue(detector.update(vSign()))
        assertTrue(detector.isVSign)
    }

    @Test
    fun `pose transitoria nao confirma`() {
        val detector = VSignDetector()
        repeat(6) { detector.update(vSign()) }
        assertFalse(detector.isVSign)
        repeat(5) { detector.update(landmarks(indexRatio = 1.35f, ringRatio = 1.35f)) }
        assertFalse(detector.isVSign)
    }

    @Test
    fun `palma aberta (todos estendidos) nunca vira V`() {
        val detector = VSignDetector()
        val palm = landmarks(indexRatio = 1.35f, ringRatio = 1.35f)
        repeat(20) { assertFalse(detector.update(palm)) }
    }

    @Test
    fun `punho (todos dobrados) nunca vira V`() {
        val detector = VSignDetector()
        val fist = landmarks(indexRatio = 0.9f, ringRatio = 0.9f)
        repeat(20) { assertFalse(detector.update(fist)) }
    }

    @Test
    fun `so indicador estendido (apontar) nao vira V`() {
        // A mão de quem aponta o cursor: médio semi-dobrado — o MIN(indicador, médio) manda.
        val detector = VSignDetector()
        val pointing = landmarks(indexRatio = 1.35f, middleRatio = 1.05f, ringRatio = 0.95f)
        repeat(20) { assertFalse(detector.update(pointing)) }
    }

    @Test
    fun `oscilacao dentro da histerese nao alterna o estado`() {
        val detector = VSignDetector()
        repeat(12) { detector.update(vSign()) }
        assertTrue(detector.isVSign)
        // Estendidos caem até a zona morta (1.10 < ext < 1.25) — não deve soltar.
        for (ext in listOf(1.20f, 1.15f, 1.22f, 1.12f)) {
            detector.update(landmarks(indexRatio = ext, ringRatio = 0.9f))
            assertTrue("ext=$ext não deveria soltar o V", detector.isVSign)
        }
    }

    @Test
    fun `dobrar o medio solta o V apos 3 frames`() {
        val detector = VSignDetector()
        repeat(12) { detector.update(vSign()) }
        assertTrue(detector.isVSign)
        // 0.5 derruba o EMA do min(estendidos) abaixo de 1.10 já no 1º frame
        // (0.5*0.5 + 0.5*1.35 = 0.925) — confirmação em exatamente 3 chamadas.
        val dropped = landmarks(indexRatio = 1.35f, middleRatio = 0.5f, ringRatio = 0.9f)
        detector.update(dropped)
        detector.update(dropped)
        assertTrue(detector.isVSign)
        detector.update(dropped)
        assertFalse(detector.isVSign)
    }

    @Test
    fun `reset limpa estado e exige debounce completo de novo`() {
        val detector = VSignDetector()
        repeat(12) { detector.update(vSign()) }
        assertTrue(detector.isVSign)
        detector.reset()
        assertFalse(detector.isVSign)
        repeat(11) { assertFalse(detector.update(vSign())) }
        assertTrue(detector.update(vSign()))
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

```powershell
cd xreal-hand-mouse
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.tracking.VSignDetectorTest"
```
Esperado: FALHA de compilação ("unresolved reference: VSignDetector").

- [ ] **Step 3: Implementar o VSignDetector**

```kotlin
package com.raphael.handmouse.tracking

import kotlin.math.sqrt

/**
 * Detector de SINAL DE "V" (indicador+médio estendidos, anular+mindinho dobrados) — gesto de
 * ativação do CONTROLE POR VOZ (2026-07-23, spec `2026-07-23-voice-control-design.md`): segurar
 * o "V" ~500ms (cronometrado pelo [CursorPipeline], como punho/thumbs-up) abre a janela de
 * escuta do `VoiceCommandController`.
 *
 * ## Métrica — a mesma razão ponta/PIP invariante a ângulo do [FistDetector]
 * `ratio_i = dist3(punho, ponta_i) / dist3(punho, PIP_i)`: estendido ~1.3+, dobrado ~0.8-1.0,
 * e a razão sobrevive à rotação da mão (numerador e denominador distorcem juntos na projeção).
 * Duas agregações, cada uma com o pior caso mandando:
 * - **Estendidos**: `min(ratio_indicador, ratio_médio)` — os DOIS têm que estar esticados.
 * - **Dobrados**: `max(ratio_anular, ratio_mindinho)` — os DOIS têm que estar dobrados.
 *
 * Isso separa o "V" de todos os gestos existentes por construção: palma aberta tem anular/
 * mindinho estendidos (max alto), punho e thumbs-up têm indicador/médio dobrados (min baixo),
 * e a mão de quem aponta o cursor tem o médio semi-dobrado (min ~1.05-1.2, abaixo do enter).
 * O polegar fica FORA da métrica (num "V" real ele pode cruzar a palma ou ficar solto — varia
 * demais; nenhuma separação depende dele).
 *
 * Mesma receita de robustez dos irmãos: EMA (alpha 0.5) em cada agregação + histerese dupla +
 * debounce ASSIMÉTRICO (entra em [ENTER_DEBOUNCE_FRAMES] ≈ 200ms a 60fps — pose deliberada;
 * sai em [EXIT_DEBOUNCE_FRAMES]). Thresholds calibrados pela anatomia — VALIDAR/AFINAR EM
 * HARDWARE.
 *
 * Classe PURA (sem Android) — testável em JVM ([VSignDetectorTest]); 1 instância no
 * [CursorPipeline].
 */
class VSignDetector {

    companion object {
        /** Entra quando o EMA do MIN(indicador, médio) passa disto (os dois bem esticados). */
        private const val EXTENDED_ENTER = 1.25f
        /** Sai quando o EMA do min cai abaixo disto (gap pra não oscilar). */
        private const val EXTENDED_EXIT = 1.10f
        /** Entra quando o EMA do MAX(anular, mindinho) está abaixo disto (os dois dobrados). */
        private const val CURLED_ENTER = 1.05f
        /** Sai quando o EMA do max passa disto (algum dos dois esticou — virou palma?). */
        private const val CURLED_EXIT = 1.20f
        private const val EMA_ALPHA = 0.5f
        /** ~200ms a 60fps — pose deliberada, imune a transições entre gestos. */
        private const val ENTER_DEBOUNCE_FRAMES = 12
        private const val EXIT_DEBOUNCE_FRAMES = 3

        private const val LM_WRIST = 0

        /** Pares (ponta, PIP): indicador e médio (estendidos no "V"). */
        private val EXTENDED_TIP_PIP = arrayOf(intArrayOf(8, 6), intArrayOf(12, 10))

        /** Pares (ponta, PIP): anular e mindinho (dobrados no "V"). */
        private val CURLED_TIP_PIP = arrayOf(intArrayOf(16, 14), intArrayOf(20, 18))
    }

    private var extendedEma = Float.NaN
    private var curledEma = Float.NaN
    private var candidate: Boolean? = null
    private var candidateFrames = 0

    var isVSign: Boolean = false
        private set

    /** [landmarks]: 21 pontos (ordem do MediaPipe). Retorna o estado JÁ atualizado ([isVSign])
     * — quem cronometra o hold de 500ms é o chamador ([CursorPipeline]). */
    fun update(landmarks: List<HandPoint>): Boolean {
        val wrist = landmarks[LM_WRIST]

        var minExtended = Float.MAX_VALUE
        for (pair in EXTENDED_TIP_PIP) {
            val ratio = dist3(wrist, landmarks[pair[0]]) / dist3(wrist, landmarks[pair[1]])
            if (ratio < minExtended) minExtended = ratio
        }
        extendedEma = ema(extendedEma, minExtended)

        var maxCurled = 0f
        for (pair in CURLED_TIP_PIP) {
            val ratio = dist3(wrist, landmarks[pair[0]]) / dist3(wrist, landmarks[pair[1]])
            if (ratio > maxCurled) maxCurled = ratio
        }
        curledEma = ema(curledEma, maxCurled)

        val want = when {
            !isVSign && extendedEma > EXTENDED_ENTER && curledEma < CURLED_ENTER -> true
            isVSign && (extendedEma < EXTENDED_EXIT || curledEma > CURLED_EXIT) -> false
            else -> return isVSign
        }

        if (candidate != want) {
            candidate = want
            candidateFrames = 0
        }
        candidateFrames++
        val requiredFrames = if (want) ENTER_DEBOUNCE_FRAMES else EXIT_DEBOUNCE_FRAMES
        if (candidateFrames < requiredFrames) return isVSign

        isVSign = want
        candidate = null
        return isVSign
    }

    /** Limpa EMAs, candidato e estado (chamar quando a mão some/reaparece). */
    fun reset() {
        extendedEma = Float.NaN
        curledEma = Float.NaN
        candidate = null
        candidateFrames = 0
        isVSign = false
    }

    private fun ema(prev: Float, sample: Float): Float =
        if (prev.isNaN()) sample else EMA_ALPHA * sample + (1f - EMA_ALPHA) * prev

    private fun dist3(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Mesmo comando do Step 2. Esperado: PASS (8 testes).

- [ ] **Step 5: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/tracking/VSignDetector.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/tracking/VSignDetectorTest.kt
rtk git commit -m "Voz: VSignDetector (gesto de ativacao da escuta, TDD)"
```

---

### Task 2: VoiceCommand + VoiceCommandParser

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/VoiceCommand.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/input/VoiceCommandParserTest.kt`

**Interfaces:**
- Consumes: nada do projeto (arquivo puro, só `java.text.Normalizer`).
- Produces: `sealed class VoiceCommand` (objetos `Back`, `Home`, `Recents`, `SendEnter`, `ClearText`, `NoMatch`; data classes `OpenApp(val query: String)`, `Dictate(val text: String)`) e `object VoiceCommandParser { fun parse(raw: String): VoiceCommand }` — Task 5 consome.

- [ ] **Step 1: Escrever os testes (falhando)**

```kotlin
package com.raphael.handmouse.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD do parser de comandos de voz (2026-07-23, spec `2026-07-23-voice-control-design.md`):
 * frase exata (tabela bilíngue pt/en, normalizada: minúsculas + sem acento) → prefixo
 * "abrir/open" → prefixo "escrever/write" → NoMatch. O texto do ditado e o nome do app
 * preservam a forma ORIGINAL (caixa/acentos) — só o roteamento é normalizado.
 */
class VoiceCommandParserTest {

    @Test
    fun `comandos exatos em portugues com acentos e caixa`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("Voltar"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("início"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("INICIO"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("recentes"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("apps recentes"))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("enviar"))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("apagar tudo"))
    }

    @Test
    fun `comandos exatos em ingles`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("back"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("home"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("recents"))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("send"))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("clear"))
    }

    @Test
    fun `abrir app extrai o nome original`() {
        assertEquals(VoiceCommand.OpenApp("YouTube"), VoiceCommandParser.parse("abrir YouTube"))
        assertEquals(VoiceCommand.OpenApp("o YouTube"), VoiceCommandParser.parse("abre o YouTube"))
        assertEquals(VoiceCommand.OpenApp("chrome"), VoiceCommandParser.parse("open chrome"))
    }

    @Test
    fun `ditado preserva o texto original apos o prefixo`() {
        assertEquals(
            VoiceCommand.Dictate("Bom dia, chego em 10 minutos"),
            VoiceCommandParser.parse("escrever Bom dia, chego em 10 minutos"),
        )
        assertEquals(VoiceCommand.Dictate("olá"), VoiceCommandParser.parse("escreve olá"))
        assertEquals(VoiceCommand.Dictate("hello there"), VoiceCommandParser.parse("write hello there"))
    }

    @Test
    fun `prefixo sem resto nao vira comando`() {
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("abrir"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("escrever "))
    }

    @Test
    fun `frases desconhecidas viram NoMatch`() {
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("fazer um cafe"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse(""))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("   "))
    }

    @Test
    fun `espacos extras sao tolerados`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("  voltar  "))
        assertEquals(VoiceCommand.OpenApp("maps"), VoiceCommandParser.parse("  abrir   maps"))
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.input.VoiceCommandParserTest"
```
Esperado: FALHA de compilação ("unresolved reference: VoiceCommand").

- [ ] **Step 3: Implementar VoiceCommand + parser**

```kotlin
package com.raphael.handmouse.input

import java.text.Normalizer
import java.util.Locale

/**
 * Comando de voz reconhecido (2026-07-23, spec `2026-07-23-voice-control-design.md`). Quem
 * EXECUTA é o `VoiceCommandController` (global actions, AppLauncher, TextInserter) — este
 * arquivo é a fronteira PURA (testável em JVM) entre "texto reconhecido" e "ação".
 */
sealed class VoiceCommand {
    object Back : VoiceCommand()
    object Home : VoiceCommand()
    object Recents : VoiceCommand()
    object SendEnter : VoiceCommand()
    object ClearText : VoiceCommand()
    data class OpenApp(val query: String) : VoiceCommand()
    data class Dictate(val text: String) : VoiceCommand()
    object NoMatch : VoiceCommand()
}

/**
 * Texto reconhecido → [VoiceCommand]. Regras, nesta ordem:
 * 1. Frase EXATA na tabela bilíngue (pt + en sempre aceitos, independente do idioma escolhido
 *    no reconhecimento — a tabela é pequena e sinônimo não colide).
 * 2. Primeira palavra é um verbo de ABRIR ("abrir"/"abre"/"open") → resto é nome de app.
 * 3. Primeira palavra é um verbo de ESCREVER ("escrever"/"escreve"/"write") → resto é ditado.
 * 4. [VoiceCommand.NoMatch].
 *
 * O roteamento compara a forma NORMALIZADA (minúsculas, sem acento — [normalize]); o payload
 * (nome de app, texto de ditado) preserva a forma ORIGINAL do reconhecedor (caixa, acentos,
 * pontuação) — remove só a primeira palavra do texto cru.
 */
object VoiceCommandParser {

    private val EXACT: Map<String, VoiceCommand> = mapOf(
        "voltar" to VoiceCommand.Back,
        "back" to VoiceCommand.Back,
        "inicio" to VoiceCommand.Home,
        "home" to VoiceCommand.Home,
        "recentes" to VoiceCommand.Recents,
        "apps recentes" to VoiceCommand.Recents,
        "recents" to VoiceCommand.Recents,
        "enviar" to VoiceCommand.SendEnter,
        "send" to VoiceCommand.SendEnter,
        "apagar tudo" to VoiceCommand.ClearText,
        "clear" to VoiceCommand.ClearText,
    )

    private val OPEN_VERBS = setOf("abrir", "abre", "open")
    private val WRITE_VERBS = setOf("escrever", "escreve", "write")

    fun parse(raw: String): VoiceCommand {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return VoiceCommand.NoMatch

        val normalized = normalize(trimmed)
        EXACT[normalized]?.let { return it }

        val firstWord = normalized.substringBefore(' ')
        // Payload = texto ORIGINAL sem a primeira palavra (preserva caixa/acento do ditado).
        val rest = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
        return when {
            firstWord in OPEN_VERBS && rest.isNotEmpty() -> VoiceCommand.OpenApp(rest)
            firstWord in WRITE_VERBS && rest.isNotEmpty() -> VoiceCommand.Dictate(rest)
            else -> VoiceCommand.NoMatch
        }
    }

    /** Minúsculas + remoção de acentos (NFD, descarta combining marks) + espaços colapsados. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Mesmo comando do Step 2. Esperado: PASS (7 testes).

- [ ] **Step 5: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/VoiceCommand.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/input/VoiceCommandParserTest.kt
rtk git commit -m "Voz: VoiceCommand + parser bilingue pt/en (TDD)"
```

---

### Task 3: AppNameMatcher (puro) + AppLauncher (Android)

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/AppNameMatcher.kt`
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/AppLauncher.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/input/AppNameMatcherTest.kt`

**Interfaces:**
- Consumes: `VoiceCommandParser.normalize` (Task 2).
- Produces: `object AppNameMatcher { fun match(query: String, apps: List<InstalledApp>): InstalledApp? }` com `data class InstalledApp(val label: String, val packageName: String)`; `class AppLauncher(context: Context) { fun launch(query: String, displayId: Int): Boolean }` — Task 5 consome `AppLauncher`.

- [ ] **Step 1: Escrever os testes do matcher (falhando)**

```kotlin
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
    fun `sem casamento retorna null`() {
        assertNull(AppNameMatcher.match("aplicativo inexistente", apps))
        assertNull(AppNameMatcher.match("", apps))
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.input.AppNameMatcherTest"
```
Esperado: FALHA de compilação.

- [ ] **Step 3: Implementar o AppNameMatcher**

```kotlin
package com.raphael.handmouse.input

/**
 * Casamento do nome de app FALADO com os labels dos apps instalados (2026-07-23, spec
 * `2026-07-23-voice-control-design.md`). PURO (testável em JVM) — quem consulta o
 * PackageManager e lança o intent é o [AppLauncher].
 *
 * Normalização = a mesma do parser ([VoiceCommandParser.normalize]) + descarte de artigo
 * inicial ("abre o YouTube" → query "o youtube" → "youtube"). Ranking: igualdade exata >
 * prefixo > contém; empate no mesmo nível → label mais curto (o mais específico pro que foi
 * dito: "youtu" casa YouTube, não YouTube Music).
 */
object AppNameMatcher {

    data class InstalledApp(val label: String, val packageName: String)

    private val LEADING_ARTICLES = setOf("o", "a", "os", "as", "the")

    fun match(query: String, apps: List<InstalledApp>): InstalledApp? {
        val q = stripLeadingArticle(VoiceCommandParser.normalize(query))
        if (q.isEmpty()) return null

        val normalized = apps.map { it to VoiceCommandParser.normalize(it.label) }

        normalized.filter { (_, label) -> label == q }
            .minByOrNull { (_, label) -> label.length }
            ?.let { return it.first }
        normalized.filter { (_, label) -> label.startsWith(q) }
            .minByOrNull { (_, label) -> label.length }
            ?.let { return it.first }
        return normalized.filter { (_, label) -> label.contains(q) }
            .minByOrNull { (_, label) -> label.length }
            ?.first
    }

    private fun stripLeadingArticle(q: String): String {
        val first = q.substringBefore(' ')
        return if (first in LEADING_ARTICLES) q.substringAfter(' ', "").trim() else q
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Mesmo comando do Step 2. Esperado: PASS (7 testes).

- [ ] **Step 5: Implementar o AppLauncher (Android, sem teste JVM)**

```kotlin
package com.raphael.handmouse.input

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Lança um app pelo nome FALADO (2026-07-23, spec `2026-07-23-voice-control-design.md`):
 * enumera as activities de launcher via PackageManager, casa o nome com [AppNameMatcher]
 * e dispara o launch intent NO DISPLAY DO DEX ([ActivityOptions.setLaunchDisplayId] — o
 * comando de voz é pra tela dos óculos, não pra do celular; VALIDAR EM HARDWARE: o DeX
 * costuma rotear sozinho, o launchDisplayId é o cinto-e-suspensório).
 *
 * A lista de apps é consultada A CADA chamada (sem cache): a enumeração é rápida no volume
 * típico (~200 apps) e instalações/desinstalações ficam sempre frescas.
 *
 * Não testável em JVM puro (PackageManager/Context reais) — o casamento, que é a lógica de
 * verdade, vive no [AppNameMatcher] (testado). Toda ação é logada (brief).
 */
class AppLauncher(private val context: Context) {

    companion object {
        private const val TAG = "AppLauncher"
    }

    /** Retorna `true` se um app casou E o intent foi disparado. */
    fun launch(query: String, displayId: Int): Boolean {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0L),
        ).map { AppNameMatcher.InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }

        val match = AppNameMatcher.match(query, apps)
        if (match == null) {
            Log.w(TAG, "Nenhum app instalado casa com \"$query\" (${apps.size} candidatos)")
            return false
        }

        val intent = pm.getLaunchIntentForPackage(match.packageName)
        if (intent == null) {
            Log.w(TAG, "App ${match.packageName} sem launch intent")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic().apply {
            if (displayId >= 0) launchDisplayId = displayId
        }
        return try {
            context.startActivity(intent, options.toBundle())
            Log.d(TAG, "\"$query\" → ${match.label} (${match.packageName}) no display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao lançar ${match.packageName} no display $displayId", e)
            false
        }
    }
}
```

- [ ] **Step 6: Compilar**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/AppNameMatcher.kt xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/AppLauncher.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/input/AppNameMatcherTest.kt
rtk git commit -m "Voz: AppNameMatcher (TDD) + AppLauncher no display do DeX"
```

---

### Task 4: TextInserter (ditado no campo focado)

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/TextInserter.kt`

**Interfaces:**
- Consumes: `AccessibilityService` (o host, Task 7).
- Produces: `class TextInserter(service: AccessibilityService)` com `fun appendText(text: String): Boolean`, `fun pressEnter(): Boolean`, `fun clearAll(): Boolean` — Task 5 consome.

- [ ] **Step 1: Implementar**

```kotlin
package com.raphael.handmouse.input

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserção de texto DITADO no campo focado (2026-07-23, spec
 * `2026-07-23-voice-control-design.md`). Exige `canRetrieveWindowContent="true"` +
 * `flagRetrieveInteractiveWindows` na config de acessibilidade (Task 7) — sem isso,
 * [AccessibilityService.findFocus] retorna null sempre.
 *
 * ## Estratégia de inserção (appendText)
 * `ACTION_SET_TEXT` com o texto EXISTENTE + " " + ditado — SET_TEXT substitui tudo, então a
 * concatenação preserva o que já estava digitado. Cuidado com hint: campo vazio reporta o
 * placeholder em `node.text` com `isShowingHintText=true` — nesse caso o existente é "".
 * Fallback pra apps que recusam SET_TEXT (alguns editores custom): clipboard + `ACTION_PASTE`
 * (aqui SEM concatenação — o paste insere na posição do cursor, comportamento natural).
 *
 * "Enviar" usa `ACTION_IME_ENTER` (API 30+; minSdk 34) — o mesmo enter do teclado, que em
 * apps de chat envia a mensagem. "Apagar tudo" é SET_TEXT com string vazia.
 *
 * Não testável em JVM puro (AccessibilityNodeInfo real) — toda ação/falha é logada (brief).
 */
class TextInserter(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "TextInserter"
    }

    /** Anexa [text] ao campo focado. `false` se não há campo focado ou toda tentativa falhou. */
    fun appendText(text: String): Boolean {
        val node = focusedNode() ?: return false
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val combined = if (existing.isEmpty()) text else "$existing $text"

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Log.d(TAG, "Ditado inserido via SET_TEXT (${text.length} chars)")
            return true
        }

        // Fallback: clipboard + paste (apps que recusam SET_TEXT).
        Log.d(TAG, "SET_TEXT recusado — tentando clipboard + ACTION_PASTE")
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ditado", text))
        val pasted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)
        if (!pasted) Log.w(TAG, "ACTION_PASTE também recusado — ditado perdido")
        return pasted
    }

    /** Enter do teclado (envia em apps de chat). `false` sem campo focado ou ação recusada. */
    fun pressEnter(): Boolean {
        val node = focusedNode() ?: return false
        val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Log.d(TAG, "IME_ENTER → $ok")
        return ok
    }

    /** Limpa o campo focado (SET_TEXT vazio). */
    fun clearAll(): Boolean {
        val node = focusedNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "clearAll → $ok")
        return ok
    }

    /** Campo com foco de INPUT em qualquer janela/display (exige flagRetrieveInteractiveWindows). */
    private fun focusedNode(): AccessibilityNodeInfo? {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) Log.w(TAG, "Nenhum campo de texto focado — comando de ditado ignorado")
        return node
    }
}
```

- [ ] **Step 2: Compilar**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/TextInserter.kt
rtk git commit -m "Voz: TextInserter (ditado/enter/apagar no campo focado)"
```

---

### Task 5: Prefs.voiceLanguage + VoiceCommandController

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/util/Prefs.kt`
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/VoiceCommandController.kt`

**Interfaces:**
- Consumes: `VoiceCommandParser.parse` (Task 2), `AppLauncher.launch(query, displayId)` (Task 3), `TextInserter` (Task 4), `CursorOverlay.setListening(Boolean)` / `CursorOverlay.flashVoiceResult(Boolean)` (Task 6 — compila DEPOIS da Task 6; ver ordem no Step 4).
- Produces: `class VoiceCommandController(service, appLauncher, textInserter, prefs, overlay)` com `fun startListening(displayId: Int)`, `val isListening: Boolean`, `fun shutdown()`; `Prefs.voiceLanguage: String` ("system" | "pt-BR" | "en-US") — Tasks 7 e 8 consomem.

- [ ] **Step 1: Adicionar o pref de idioma**

Em `Prefs.kt`, adicionar ao companion:

```kotlin
        private const val KEY_VOICE_LANGUAGE = "voice_language"

        /** Valor especial de [voiceLanguage]: usa o idioma do sistema (default). */
        const val VOICE_LANGUAGE_SYSTEM = "system"
```

E a propriedade (depois de `captureActive`):

```kotlin
    /** Idioma do reconhecimento de voz (2026-07-23, spec voice-control): "system" (default —
     * SpeechRecognizer usa o locale do aparelho), "pt-BR" ou "en-US" (forçados via
     * RecognizerIntent.EXTRA_LANGUAGE). A tabela de comandos é bilíngue independente disto —
     * a escolha afeta só a qualidade do reconhecimento de fala livre (ditado, nomes de app). */
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, VOICE_LANGUAGE_SYSTEM) ?: VOICE_LANGUAGE_SYSTEM
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()
```

- [ ] **Step 2: Implementar o VoiceCommandController**

```kotlin
package com.raphael.handmouse.input

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.raphael.handmouse.overlay.CursorOverlay
import com.raphael.handmouse.util.Prefs

/**
 * Controle por VOZ (2026-07-23, spec `2026-07-23-voice-control-design.md`): o [startListening]
 * (chamado pelo `CursorPipeline` quando o sinal de "V" é segurado) abre UMA sessão do
 * [SpeechRecognizer] nativo; o melhor resultado passa pelo [VoiceCommandParser] e a ação é
 * executada (global actions / [AppLauncher] / [TextInserter]). Estados: IDLE → LISTENING →
 * (executa) → IDLE — sem fila: um novo gesto durante LISTENING é ignorado ([isListening]).
 *
 * ## Threading
 * [startListening] chega na HandlerThread do HandTracker (pipeline); TODO o resto roda na main
 * thread ([mainHandler]) — o SpeechRecognizer EXIGE main thread (criação, startListening,
 * callbacks do [RecognitionListener] e destroy). [isListening] é @Volatile: escrito na main,
 * lido no pipeline a cada frame (congela gestos enquanto ouve).
 *
 * ## Idioma
 * [Prefs.voiceLanguage] = "system" não seta EXTRA_LANGUAGE (o recognizer usa o locale do
 * aparelho); "pt-BR"/"en-US" forçam via EXTRA_LANGUAGE. Pacote de idioma ausente no aparelho
 * cai no onError (ERROR_LANGUAGE_NOT_SUPPORTED/UNAVAILABLE) → feedback vermelho.
 *
 * ## Feedback visual (via [CursorOverlay], sempre na main)
 * setListening(true) no início (cursor amarelo), flashVoiceResult(true/false) no fim (verde =
 * ação executada, vermelho = timeout/no-match/erro). Timeout de segurança [LISTEN_TIMEOUT_MS]
 * cancela sessões que o endpointing do recognizer não encerrar sozinho.
 *
 * Erros NUNCA propagam pro pipeline — tudo logado + feedback e volta a IDLE.
 */
class VoiceCommandController(
    private val service: AccessibilityService,
    private val appLauncher: AppLauncher,
    private val textInserter: TextInserter,
    private val prefs: Prefs,
    private val overlay: CursorOverlay,
) {

    companion object {
        private const val TAG = "VoiceCommandController"

        /** Teto da sessão: o endpointing do recognizer costuma fechar antes; isto é o guarda. */
        private const val LISTEN_TIMEOUT_MS = 7_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var displayId: Int = -1

    @Volatile
    var isListening: Boolean = false
        private set

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Timeout de ${LISTEN_TIMEOUT_MS}ms sem resultado — cancelando sessão")
        finish(success = false)
    }

    /** Chamado pelo CursorPipeline (HandlerThread) quando o "V" completa o hold. Idempotente
     * durante uma sessão ativa. [displayId]: display do DeX pra onde "abrir <app>" lança. */
    fun startListening(displayId: Int) {
        if (isListening) return
        isListening = true
        this.displayId = displayId
        mainHandler.post { startSessionOnMain() }
    }

    /** Encerra qualquer sessão e libera o recognizer (onDestroy do a11y service). */
    fun shutdown() {
        mainHandler.post {
            mainHandler.removeCallbacks(timeoutRunnable)
            recognizer?.destroy()
            recognizer = null
            overlay.setListening(false)
            isListening = false
        }
    }

    // --- Tudo abaixo roda na MAIN THREAD ---

    private fun startSessionOnMain() {
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO não concedida — abra o app e rode o wizard de permissões")
            finish(success = false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            Log.w(TAG, "Reconhecimento de voz indisponível neste aparelho")
            finish(success = false)
            return
        }

        val r = SpeechRecognizer.createSpeechRecognizer(service)
        recognizer = r
        r.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            val lang = prefs.voiceLanguage
            if (lang != Prefs.VOICE_LANGUAGE_SYSTEM) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }

        overlay.setListening(true)
        Log.d(TAG, "Escutando (idioma=${prefs.voiceLanguage}, display=$displayId)...")
        r.startListening(intent)
        mainHandler.postDelayed(timeoutRunnable, LISTEN_TIMEOUT_MS)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.d(TAG, "Resultados: $phrases")
            // Tenta cada hipótese do recognizer em ordem — a primeira que casar executa.
            val command = phrases.asSequence()
                .map { VoiceCommandParser.parse(it) }
                .firstOrNull { it != VoiceCommand.NoMatch } ?: VoiceCommand.NoMatch
            execute(command)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer onError=$error")
            finish(success = false)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun execute(command: VoiceCommand) {
        val ok = when (command) {
            VoiceCommand.Back -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            VoiceCommand.Home -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            VoiceCommand.Recents -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            VoiceCommand.SendEnter -> textInserter.pressEnter()
            VoiceCommand.ClearText -> textInserter.clearAll()
            is VoiceCommand.OpenApp -> appLauncher.launch(command.query, displayId)
            is VoiceCommand.Dictate -> textInserter.appendText(command.text)
            VoiceCommand.NoMatch -> false
        }
        Log.d(TAG, "Comando $command → ${if (ok) "OK" else "FALHOU"}")
        finish(success = ok)
    }

    private fun finish(success: Boolean) {
        mainHandler.removeCallbacks(timeoutRunnable)
        recognizer?.destroy()
        recognizer = null
        overlay.setListening(false)
        overlay.flashVoiceResult(success)
        isListening = false
    }
}
```

- [ ] **Step 3: Commit parcial (sem compilar ainda)**

`VoiceCommandController` referencia `CursorOverlay.setListening`/`flashVoiceResult`, que só existem após a Task 6 — **NÃO rodar compileDebugKotlin nesta task**. Commit dos arquivos como estão (a Task 6 fecha a compilação):

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/util/Prefs.kt xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/input/VoiceCommandController.kt
rtk git commit -m "Voz: VoiceCommandController (SpeechRecognizer) + Prefs.voiceLanguage"
```

*(Se o executor preferir manter todo commit compilável: executar Task 6 antes deste commit e commitar as duas juntas — aceitável; anotar no commit.)*

---

### Task 6: Estados visuais de escuta no overlay

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/overlay/CursorView.kt`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/overlay/CursorOverlay.kt`

**Interfaces:**
- Produces: `CursorView.setListening(Boolean)`, `CursorView.flashResult(Boolean)`; `CursorOverlay.setListening(Boolean)`, `CursorOverlay.flashVoiceResult(Boolean)` — Task 5 consome (main thread SEMPRE).

- [ ] **Step 1: CursorView — cor de escuta + flash de resultado**

Em `CursorView.kt`:

1. No companion, adicionar:

```kotlin
        /** Duração do flash verde/vermelho de resultado do comando de voz (2026-07-23). */
        private const val FLASH_DURATION_MS = 400L
        private val COLOR_LISTENING = Color.YELLOW
        private val COLOR_FLASH_OK = Color.GREEN
        private val COLOR_FLASH_FAIL = Color.RED
```

2. Adicionar os campos (junto de `pinched`):

```kotlin
    private var listening = false
```

3. Substituir o corpo de `setPinched` para usar a cor-base central (a linha `fillPaint.color = if (isPinched) Color.CYAN else Color.WHITE` vira `fillPaint.color = baseFillColor()`):

```kotlin
    /** Atualiza o feedback visual de pinch — idempotente (não invalida se o estado não mudou). */
    fun setPinched(isPinched: Boolean) {
        if (pinched == isPinched) return
        pinched = isPinched
        fillPaint.color = baseFillColor()
        scaleX = if (isPinched) 1.25f else 1f
        scaleY = if (isPinched) 1.25f else 1f
        invalidate()
    }
```

4. Adicionar após `pulseClick()`:

```kotlin
    /** Cursor AMARELO enquanto a escuta de voz está aberta (2026-07-23, spec voice-control) —
     * mesma mecânica de cor do [setPinched]; escuta tem prioridade visual sobre pinch (durante
     * a escuta o pipeline suprime gestos, então o pinch nem deveria acontecer). */
    fun setListening(isListening: Boolean) {
        if (listening == isListening) return
        listening = isListening
        removeCallbacks(flashRestoreRunnable) // um flash pendente não pode sobrescrever depois
        fillPaint.color = baseFillColor()
        invalidate()
    }

    /** Flash verde (comando executado) / vermelho (timeout, no-match, erro) por
     * [FLASH_DURATION_MS], voltando sozinho à cor-base. */
    fun flashResult(success: Boolean) {
        removeCallbacks(flashRestoreRunnable)
        fillPaint.color = if (success) COLOR_FLASH_OK else COLOR_FLASH_FAIL
        invalidate()
        postDelayed(flashRestoreRunnable, FLASH_DURATION_MS)
    }

    private val flashRestoreRunnable = Runnable {
        fillPaint.color = baseFillColor()
        invalidate()
    }

    /** Cor-base do preenchimento pela precedência: escuta > pinch > normal. */
    private fun baseFillColor(): Int = when {
        listening -> COLOR_LISTENING
        pinched -> Color.CYAN
        else -> Color.WHITE
    }
```

- [ ] **Step 2: CursorOverlay — repasse + visibilidade durante a escuta**

Em `CursorOverlay.kt`, adicionar após `pulseClick()`:

```kotlin
    /** Estado visual de ESCUTA DE VOZ (2026-07-23, spec voice-control) — main thread (chamado
     * pelo VoiceCommandController). Ouvindo: cancela o fade e força o cursor visível (o usuário
     * PRECISA ver que o gesto pegou), exceto se mutado por gesto ([setHiddenByUser] — o mute do
     * usuário vence). Fim da escuta: rearma o fade normal. */
    fun setListening(listening: Boolean) {
        val cv = cursorView ?: return
        cv.setListening(listening)
        if (listening) {
            mainHandler.removeCallbacks(fadeRunnable)
            if (!hiddenByUser) {
                fadeAnimator?.cancel()
                fadeAnimator = null
                cv.alpha = 1f
                faded = false
            }
        } else {
            scheduleFade()
        }
    }

    /** Flash de resultado do comando de voz — repasse pro [CursorView.flashResult]. */
    fun flashVoiceResult(success: Boolean) {
        cursorView?.flashResult(success)
    }
```

- [ ] **Step 3: Compilar (fecha também a Task 5)**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```
Esperado: BUILD SUCCESSFUL (as referências do VoiceCommandController agora resolvem).

- [ ] **Step 4: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/overlay/CursorView.kt xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/overlay/CursorOverlay.kt
rtk git commit -m "Voz: cursor amarelo durante escuta + flash verde/vermelho de resultado"
```

---

### Task 7: Integração — pipeline, a11y service, manifest e config

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/tracking/CursorPipeline.kt`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/HandMouseAccessibilityService.kt`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt` (linha ~606, `startForeground`)
- Modify: `xreal-hand-mouse/app/src/main/AndroidManifest.xml`
- Modify: `xreal-hand-mouse/app/src/main/res/xml/accessibility_config.xml`

**Interfaces:**
- Consumes: `VSignDetector` (Task 1), `VoiceCommandController.startListening(displayId)/isListening/shutdown()` (Task 5).
- Produces: `CursorPipeline` ganha params `vSignDetector: VSignDetector = VSignDetector()` e `voiceController: VoiceCommandController? = null` (nullable, mesmo padrão do `mediaController`).

- [ ] **Step 1: CursorPipeline — detector, hold e congelamento durante a escuta**

1. Imports: adicionar `com.raphael.handmouse.input.VoiceCommandController` (já importa `GestureInjector` de `input`).

2. Construtor — adicionar depois de `palmSwipeDetector`:

```kotlin
    private val vSignDetector: VSignDetector = VSignDetector(),
```

e depois de `mediaController`:

```kotlin
    // Controle por voz (2026-07-23): sinal de "V" segurado abre a escuta. Nullable com default
    // null pelo mesmo motivo do mediaController (testes/usos sem dependências Android).
    private val voiceController: com.raphael.handmouse.input.VoiceCommandController? = null,
```

3. Companion — adicionar:

```kotlin
        /** Sinal de "V" segurado por este tempo abre a escuta de voz (2026-07-23, spec
         * voice-control). Curto de propósito (o debounce de ~200ms do detector já filtra
         * transitórios): o gesto deve responder rápido. */
        private const val V_SIGN_HOLD_MS = 500L
```

4. Campos — adicionar junto dos de punho/thumbs-up:

```kotlin
    // Cronometragem do sinal de "V" (escuta de voz) — ver [updateVSignState].
    private var vSignHoldStartMs: Long? = null
    private var vSignFired = false
    private var lastLoggedVSign = false
```

5. Em `onHandResult`, logo APÓS o bloco do thumbs-up (`if (cursorMuted) return`) e ANTES do bloco do modo palma, inserir:

```kotlin
        // ---- Controle por voz (2026-07-23): "V" segurado V_SIGN_HOLD_MS abre a escuta ----
        // Durante a escuta o frame acaba aqui: âncora solta (sem salto na volta — mesmo clutch
        // do modo palma), pinch suprimido, cursor parado. A mão fica livre pra ficar à vontade
        // enquanto o usuário FALA (inclusive abaixá-la — a escuta não depende mais do gesto).
        updateVSignState(points, result.timestampMs)
        if (voiceController?.isListening == true) {
            relativeMapper.onHandLost()
            pinchDetector.reset()
            overlay.setPinched(false)
            return
        }
```

6. Adicionar o método (depois de `updateFistState`):

```kotlin
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
```

7. Em `onHandLost`, junto dos resets dos outros detectores (após `thumbsUpToggleFired = false`):

```kotlin
        // Voz: o DETECTOR/hold resetam (pose não sobrevive à perda da mão), mas uma ESCUTA em
        // andamento continua de propósito — abaixar a mão enquanto fala é o fluxo natural.
        vSignDetector.reset()
        vSignHoldStartMs = null
        vSignFired = false
```

- [ ] **Step 2: HandMouseAccessibilityService — criar/destruir o controller**

1. Imports: adicionar

```kotlin
import com.raphael.handmouse.input.AppLauncher
import com.raphael.handmouse.input.TextInserter
import com.raphael.handmouse.input.VoiceCommandController
```

2. Campo (junto de `overlay`/`cursorPipeline`):

```kotlin
    private var voiceController: VoiceCommandController? = null
```

3. Em `onServiceConnected`, no bloco de desfazimento do topo (após `if (::overlay.isInitialized) overlay.hide()`):

```kotlin
        voiceController?.shutdown() // sessão de voz de uma conexão anterior (rebind)
```

4. Ainda em `onServiceConnected`, substituir a construção do `cursorPipeline`:

```kotlin
        val voice = VoiceCommandController(
            service = this,
            appLauncher = AppLauncher(this),
            textInserter = TextInserter(this),
            prefs = prefs,
            overlay = overlay,
        )
        voiceController = voice
        cursorPipeline = CursorPipeline(
            overlay,
            gestureInjector,
            mediaController = MediaGestureController(this, gestureInjector),
            voiceController = voice,
        )
```

5. Em `onDestroy` (antes de `instance = null`):

```kotlin
        voiceController?.shutdown()
        voiceController = null
```

- [ ] **Step 3: accessibility_config.xml — leitura de conteúdo (ditado)**

Substituir as duas linhas de flags/conteúdo:

```xml
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
```

E adicionar ao comentário do topo do arquivo:

```
     canRetrieveWindowContent + flagRetrieveInteractiveWindows (2026-07-23, controle por voz):
     o ditado precisa achar o campo de texto FOCADO em qualquer janela/display (DeX) via
     findFocus(FOCUS_INPUT) — sem os dois, retorna null sempre. onAccessibilityEvent continua
     sem uso (eventTypes segue typeAnnouncement).
```

- [ ] **Step 4: AndroidManifest.xml — permissões e FGS type**

1. Adicionar após a linha do `FOREGROUND_SERVICE_CONNECTED_DEVICE`:

```xml
    <!-- Controle por voz (2026-07-23): mic pro SpeechRecognizer; o FGS type microphone permite
         a escuta com o app em background (a janela abre por gesto, com qualquer app na frente) -->
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
```

2. Alterar o service de captura:

```xml
        <service android:name=".service.EyeCaptureService" android:exported="false"
                 android:foregroundServiceType="connectedDevice|microphone"/>
```

- [ ] **Step 5: EyeCaptureService — declarar o type microphone no startForeground**

Na linha ~606 (`startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`), substituir por:

```kotlin
        // Type microphone (2026-07-23, controle por voz) SÓ quando RECORD_AUDIO já foi
        // concedida — declarar o type sem a permissão lança SecurityException no start. Sem o
        // type, o SpeechRecognizer falha com o app em background (mic bloqueado no Android 14+).
        // Nota: reinícios do watchdog EM BACKGROUND podem ser recusados por causa do type de
        // mic (restrição do Android 14) — o catch de ForegroundServiceStartNotAllowedException
        // do watchdog já cobre (notificação pro usuário reabrir). VALIDAR EM HARDWARE.
        val micType = if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or micType,
            )
```

(O `try {` acima é o MESMO try já existente — só a chamada muda; manter o `catch (e: SecurityException)` como está.)

- [ ] **Step 6: Compilar e rodar TODOS os testes**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin
```
Esperado: BUILD SUCCESSFUL, todos os testes existentes + novos passando.

- [ ] **Step 7: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/tracking/CursorPipeline.kt xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/HandMouseAccessibilityService.kt xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt xreal-hand-mouse/app/src/main/AndroidManifest.xml xreal-hand-mouse/app/src/main/res/xml/accessibility_config.xml
rtk git commit -m "Voz: integracao no pipeline (V-500ms abre escuta) + FGS mic + config a11y"
```

---

### Task 8: MainActivity — permissão RECORD_AUDIO + seletor de idioma

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt`
- Modify: `xreal-hand-mouse/app/src/main/res/layout/activity_main.xml`
- Modify: `xreal-hand-mouse/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Prefs.voiceLanguage` / `Prefs.VOICE_LANGUAGE_SYSTEM` (Task 5).
- Produces: nada consumido por outras tasks.

- [ ] **Step 1: strings.xml**

Adicionar:

```xml
    <!-- Controle por voz (2026-07-23) -->
    <string name="voice_language_label">Idioma do reconhecimento de voz</string>
    <string name="voice_language_system">Sistema</string>
    <string name="voice_language_pt">Português</string>
    <string name="voice_language_en">English</string>
```

- [ ] **Step 2: Layout — RadioGroup do idioma**

Em `activity_main.xml`, dentro do card de acessibilidade, logo APÓS o `TextView` `@+id/dexDisplayStatusText` (linha ~184, antes do `</LinearLayout>` que fecha o card):

```xml
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:textSize="13sp"
                    android:textColor="@color/hm_text_primary"
                    android:text="@string/voice_language_label" />

                <RadioGroup
                    android:id="@+id/voiceLangGroup"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <RadioButton
                        android:id="@+id/voiceLangSystem"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:textColor="@color/hm_text_primary"
                        android:text="@string/voice_language_system" />

                    <RadioButton
                        android:id="@+id/voiceLangPt"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="8dp"
                        android:textColor="@color/hm_text_primary"
                        android:text="@string/voice_language_pt" />

                    <RadioButton
                        android:id="@+id/voiceLangEn"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="8dp"
                        android:textColor="@color/hm_text_primary"
                        android:text="@string/voice_language_en" />
                </RadioGroup>
```

- [ ] **Step 3: MainActivity — launcher de permissão + wizard + seletor**

1. Campo novo (junto dos launchers, após `requestNotificationsPermission`):

```kotlin
    private val requestRecordAudioPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "Permissão de microfone concedida" else "Permissão de microfone negada — controle por voz indisponível")
            updatePermissionsButtonState()
        }
```

2. Em `runPermissionWizard()`, adicionar ANTES de `requestBatteryExemption()`:

```kotlin
        // Voz (2026-07-23): mic é opcional (o resto do app funciona sem), mas o wizard pede
        // junto — negar não bloqueia o botão de captura (allWizardPermissionsGranted não inclui).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
```

(`allWizardPermissionsGranted()` fica como está — mic NÃO entra no gate do botão de captura.)

3. Campo + wiring do seletor. Campo (junto dos outros `lateinit`):

```kotlin
    private lateinit var voiceLangGroup: android.widget.RadioGroup
```

Em `onCreate`, junto dos `findViewById`:

```kotlin
        voiceLangGroup = findViewById(R.id.voiceLangGroup)
```

E após `btnOpenAccessibilitySettings.setOnClickListener { ... }`:

```kotlin
        // Seletor de idioma do reconhecimento de voz (2026-07-23, spec voice-control):
        // default "system"; a escolha vale a partir da PRÓXIMA sessão de escuta (o
        // VoiceCommandController lê o pref a cada startListening — sem restart).
        val checkedId = when (prefs.voiceLanguage) {
            "pt-BR" -> R.id.voiceLangPt
            "en-US" -> R.id.voiceLangEn
            else -> R.id.voiceLangSystem
        }
        voiceLangGroup.check(checkedId)
        voiceLangGroup.setOnCheckedChangeListener { _, id ->
            prefs.voiceLanguage = when (id) {
                R.id.voiceLangPt -> "pt-BR"
                R.id.voiceLangEn -> "en-US"
                else -> com.raphael.handmouse.util.Prefs.VOICE_LANGUAGE_SYSTEM
            }
            appendLog("Idioma do reconhecimento de voz: ${prefs.voiceLanguage}")
        }
```

- [ ] **Step 4: Compilar**

```powershell
.\gradlew.bat :app:assembleDebug
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
rtk git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt xreal-hand-mouse/app/src/main/res/layout/activity_main.xml xreal-hand-mouse/app/src/main/res/values/strings.xml
rtk git commit -m "Voz: wizard pede RECORD_AUDIO + seletor de idioma (sistema/PT/EN)"
```

---

### Task 9: Verificação final + validação em hardware

**Files:** nenhum novo — só verificação.

- [ ] **Step 1: Suite completa + build**

```powershell
cd xreal-hand-mouse
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
Esperado: BUILD SUCCESSFUL, zero testes falhando (novos: VSignDetectorTest, VoiceCommandParserTest, AppNameMatcherTest; antigos intactos).

- [ ] **Step 2: Instalar no aparelho (adb wireless)**

```powershell
adb connect <DEVICE-IP>:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
Esperado: `Success`. **Atenção (memória do projeto): reinstalar o APK revoga a permissão USB dos óculos — vai precisar re-conceder no ATTACH seguinte.**

- [ ] **Step 3: Checklist de validação em hardware (com o usuário)**

Anotar resultado de cada item; thresholds do VSignDetector e o hold de 500ms são candidatos a ajuste:

1. Sinal de "V" segurado ~0.5s → cursor fica AMARELO (escuta aberta); logcat mostra "abrindo escuta de voz".
2. Falar "voltar" → ação de voltar + flash VERDE. Repetir "início" e "recentes".
3. Falar comando em inglês ("back") com idioma "Sistema" (pt-BR) → deve funcionar (tabela bilíngue).
4. "abrir YouTube" → app abre NO DISPLAY DO DEX (não na tela do celular).
5. Foco num campo de texto → "escrever bom dia" insere o texto; "enviar" dispara o enter; "apagar tudo" limpa.
6. Ficar em silêncio após abrir a escuta → flash VERMELHO após o timeout, cursor volta ao normal, gestos voltam a funcionar.
7. Falar algo sem sentido → flash VERMELHO (NoMatch).
8. Durante a escuta: mexer a mão → cursor NÃO anda, pinch NÃO clica; ao terminar, cursor retoma sem salto.
9. "V" no meio de um drag → NÃO abre escuta (máquina fora de IDLE).
10. Punho, palma aberta, thumbs-up → NENHUM vira "V" falso (e vice-versa: o "V" não congela o cursor como palma nem recentraliza como punho).
11. Com outro app em foco (YouTube fullscreen no DeX) e o app em background → gesto + comando funcionam (FGS mic type valendo).
12. Trocar idioma pra EN na MainActivity → ditado em inglês melhora; comandos pt continuam funcionando.
13. Apagar/reconceder RECORD_AUDIO → sem crash; comando de voz falha com flash vermelho + log claro.

- [ ] **Step 4: Registrar ajustes de tuning (se houver) e commit final**

Qualquer threshold ajustado em hardware segue o padrão do repo: editar a constante + atualizar o Javadoc com a data/motivo, e commitar como "Voz: tuning de hardware — <o que mudou>".

---

## Riscos conhecidos (aceitos no design)

- **SpeechRecognizer em background**: mesmo com o FGS type microphone, algumas builds da One UI podem restringir o mic de sessões iniciadas sem UI visível. Se o item 11 do checklist falhar, o plano B (documentar como follow-up, não implementar agora) é mover a sessão de escuta pra um FGS próprio curto ou usar `createOnDeviceSpeechRecognizer`.
- **Watchdog + mic type**: reinício do FGS pelo watchdog em background pode ser recusado pelo Android 14 por causa do type microphone; o catch existente já notifica o usuário pra reabrir o app (comportamento aceito).
- **Pacote de idioma ausente** (EN sem pacote en-US): onError → flash vermelho; solução manual nas configurações de voz do aparelho.
