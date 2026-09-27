# Remoção das `.so` — Kotlin como caminho de produção — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promover a camada de protocolo Kotlin (já provada byte-a-byte em hardware) ao caminho de produção de ativação da câmera, com paridade de robustez ao caminho nativo, e então remover as 2 `.so` proprietárias + o shim verbatim de terceiro + os assets mortos.

**Architecture:** Trocar as tripas do `GlassesConnection` no lugar (mesma interface pública; miolo `XrealGlasses.NRBSP*` → `GlassesTransport` + codecs Kotlin). Ativação serializada num executor single-thread com token de geração; fluxo ramificado (skip-se-uvc0-ativa) idêntico ao nativo; retry limitado p/ não regredir robustez; fallback observável pro nativo durante a validação, removido no fim.

**Tech Stack:** Kotlin, Android USB Host API (`UsbDeviceConnection.bulkTransfer`), `java.util.concurrent.Executors`, JUnit 4. Spec: `docs/superpowers/specs/2026-07-23-kotlin-so-removal-design.md`.

## Global Constraints

- **Preâmbulo mantido:** cada operação = par `(0x26,0xd4)` 2× + comando. NÃO otimizar/remover (função desconhecida; fase separada).
- **Sequência de produção é RAMIFICADA, não a de paridade:** `WaitPilotReady` → `GetUsbConfigAll` → **pula `SetUsbConfigAll` se `uvc0==1` && !forceReconfigure** → senão `SetUsbConfigAll` → `sleep(3000)` → `GetCameraStatus`. Espelha `GlassesConnection.kt:191` nativo.
- **Não regredir robustez:** retry limitado (teto 10× / 500ms, paridade com o `getFd` nativo) no open e em cada `request()`; nunca deixar o serviço travar em `PipelineState.ERROR` com `pipelineActive=true` (nada auto-recupera disso — `EyeCaptureService.kt:459` só age com `!pipelineActive`).
- **Rollout seguro:** nas etapas 1–2, sempre tenta Kotlin; em falha, cai pro nativo (fallback OBSERVÁVEL, logado — nunca silencioso). Gatilho de fallback = exceção OU `Set` code≠0 OU skip-tomado-mas-scan-de-câmera-esgotado. Loga qual caminho rodou + o `UsbConfigState` cru.
- **Interface pública do `GlassesConnection` inalterada:** `Listener`, `start()`, `stop()`, `scanForGlasses()`, `enableEyeCamera(forceReconfigure)`, constantes `XREAL_VID`/`XREAL_EYE_PID`.
- **`KotlinGlassesProtocol.enableCameraSequence()` + hook `FORCE_CAPTURE_KOTLIN` permanecem** (artefato de paridade).
- **Copyright:** alvo = sem `.so` proprietária + sem código verbatim de terceiro + atribuição MIT preservada (NOTICE na raiz) pros 5 arquivos MIT derivados que ficam. Nada vindo de decompilar as `.so`.
- **Build (Windows):** `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` antes de `.\gradlew.bat`. Dir: `xreal-hand-mouse/`. adb: `C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe`, aparelho `<DEVICE-IP>:5555`.
- **Sessão paralela ativa:** `git add` só nos caminhos exatos da task. NUNCA `-A`/`.`/`commit -a`. `git status --porcelain` antes de cada commit.
- **Verificação de enumeração/streaming (não usar sysfs — SELinux bloqueia):** `dumpsys usb | grep vendor_id=13080` + logcat `EyeCaptureService` `[STREAMING]`.

---

### Task 1: Helpers de operação em `GlassesCommands` (puro, TDD)

Expõe cada operação avulsa (com preâmbulo embutido) para a produção poder ramificar, mantendo `enableCameraSequence()` idêntica.

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesCommands.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesCommandsTest.kt`

**Interfaces:**
- Consumes: `GlassesFrame.build` (existente), `UsbConfigCodec.SET_UVC0_PAYLOAD` (existente).
- Produces:
  - `fun waitPilotReadyMessages(): List<ByteArray>` → `[26,d4,26,d4,d6+prop]`
  - `fun getUsbConfigMessages(): List<ByteArray>` → `[26,d4,26,d4,d2]`
  - `fun setUsbConfigMessages(payload: ByteArray): List<ByteArray>` → `[26,d4,26,d4,d3+payload]`
  - `fun getCameraStatusMessages(): List<ByteArray>` → `[26,d4,26,d4,d5]`
  - `enableCameraSequence()` reescrita como concatenação dos 4 helpers (bytes idênticos aos de hoje).

- [ ] **Step 1: Escrever os testes que falham**

Adicionar a `GlassesCommandsTest.kt` (manter os testes existentes):

```kotlin
    @Test
    fun `helpers de operacao carregam preambulo + comando corretos`() {
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd6),
            GlassesCommands.waitPilotReadyMessages().map { it[15].toInt() and 0xff })
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd2),
            GlassesCommands.getUsbConfigMessages().map { it[15].toInt() and 0xff })
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd3),
            GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD).map { it[15].toInt() and 0xff })
        assertEquals(listOf(0x26, 0xd4, 0x26, 0xd4, 0xd5),
            GlassesCommands.getCameraStatusMessages().map { it[15].toInt() and 0xff })
    }

    @Test
    fun `waitPilotReady carrega a propriedade e setUsbConfig o payload`() {
        val prop = GlassesCommands.waitPilotReadyMessages()[4]
        assertEquals("ro.bsp.app_prepare_done", prop.copyOfRange(22, prop.size).toString(Charsets.US_ASCII))
        val set = GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD)[4]
        assertArrayEquals(UsbConfigCodec.SET_UVC0_PAYLOAD, set.copyOfRange(22, set.size))
    }

    @Test
    fun `enableCameraSequence e a concatenacao dos 4 helpers`() {
        val expected = GlassesCommands.waitPilotReadyMessages() +
            GlassesCommands.getUsbConfigMessages() +
            GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD) +
            GlassesCommands.getCameraStatusMessages()
        val actual = GlassesCommands.enableCameraSequence()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a) }
    }
```

- [ ] **Step 2: Rodar e confirmar RED**

```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.glasses.GlassesCommandsTest"
```
Expected: FALHA — `Unresolved reference: waitPilotReadyMessages`.

- [ ] **Step 3: Implementar**

Em `GlassesCommands.kt`, substituir o bloco a partir de `enableCameraSequence()` por:

```kotlin
    /** Uma operação = par de preâmbulo 2× + o comando dela. */
    fun waitPilotReadyMessages(): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_GET_PROPERTY, PROP_APP_PREPARE_DONE.toByteArray(Charsets.US_ASCII)))
    }

    fun getUsbConfigMessages(): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_GET_USB_CONFIG))
    }

    fun setUsbConfigMessages(payload: ByteArray): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_SET_USB_CONFIG, payload))
    }

    fun getCameraStatusMessages(): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_GET_CAMERA_STATUS))
    }

    fun enableCameraSequence(): List<ByteArray> =
        waitPilotReadyMessages() +
            getUsbConfigMessages() +
            setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD) +
            getCameraStatusMessages()
```

- [ ] **Step 4: Rodar e confirmar GREEN**

Rodar o comando do Step 2. Expected: todos os testes de `GlassesCommandsTest` passam (os 4 antigos + os 3 novos).

- [ ] **Step 5: Rodar a suíte inteira (zero regressão — `enableCameraSequence` mudou de forma)**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, 169+ testes verdes (o teste de paridade `a ordem dos codigos de comando bate com a captura` deve continuar passando — os bytes não mudaram).

- [ ] **Step 6: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesCommands.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesCommandsTest.kt
git commit -m "Kotlin producao: helpers de operacao avulsa em GlassesCommands (preambulo embutido)"
```

---

### Task 2: Ativação de produção Kotlin no `GlassesConnection` (integração)

Troca o miolo do `enableEyeCamera`: caminho Kotlin como primário, nativo preservado como fallback (removido na Task 4). Executor serializado + token de geração. Retry limitado. Decisão skip-if-active pura e testada.

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/capture/GlassesConnection.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/capture/GlassesConnectionDecisionTest.kt` (novo — só a lógica pura)

**Interfaces:**
- Consumes: `GlassesTransport` (find/claim/request/release), `GlassesCommands.{waitPilotReadyMessages,getUsbConfigMessages,setUsbConfigMessages,getCameraStatusMessages}` (Task 1), `UsbConfigCodec.{decode,SET_UVC0_PAYLOAD}`, `UsbConfigState`, `GlassesFrame.Parsed`, `GlassesCommands.PILOT_READY_MARKER`.
- Produces: interface pública inalterada. Novo helper interno testável `shouldReconfigure(cfg: UsbConfigState?, forceReconfigure: Boolean): Boolean`.

- [ ] **Step 1: Escrever o teste da decisão pura (falha)**

Create `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/capture/GlassesConnectionDecisionTest.kt`:

```kotlin
package com.raphael.handmouse.capture

import com.raphael.handmouse.glasses.UsbConfigState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Só a decisão pura de reconfigurar (skip-if-active), sem hardware. */
class GlassesConnectionDecisionTest {
    private fun cfg(uvc0: Int) = UsbConfigState(ncm = 1, ecm = 1, hidCtrl = 1, uvc0 = uvc0, uvc1 = 2)

    @Test fun `uvc0 ativa e sem force - pula reconfiguracao`() =
        assertFalse(GlassesConnection.shouldReconfigure(cfg(uvc0 = 1), forceReconfigure = false))

    @Test fun `uvc0 inativa - reconfigura`() =
        assertTrue(GlassesConnection.shouldReconfigure(cfg(uvc0 = 0), forceReconfigure = false))

    @Test fun `force reconfigure - reconfigura mesmo com uvc0 ativa`() =
        assertTrue(GlassesConnection.shouldReconfigure(cfg(uvc0 = 1), forceReconfigure = true))

    @Test fun `config desconhecida (null) - reconfigura por seguranca`() =
        assertTrue(GlassesConnection.shouldReconfigure(null, forceReconfigure = false))
}
```

- [ ] **Step 2: Rodar e confirmar RED**

```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.capture.GlassesConnectionDecisionTest"
```
Expected: FALHA — `Unresolved reference: shouldReconfigure`.

- [ ] **Step 3: Implementar a decisão pura + o caminho Kotlin no `GlassesConnection`**

No `GlassesConnection.kt`:

(a) Adicionar imports:
```kotlin
import android.hardware.usb.UsbDeviceConnection
import com.raphael.handmouse.glasses.GlassesCommands
import com.raphael.handmouse.glasses.GlassesFrame
import com.raphael.handmouse.glasses.GlassesTransport
import com.raphael.handmouse.glasses.UsbConfigCodec
import com.raphael.handmouse.glasses.UsbConfigState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
```

(b) No `companion object`, adicionar:
```kotlin
        // Robustez que a .so fazia escondida (XrealGlasses.getFd: 10× / 500ms).
        private const val OPEN_RETRY_COUNT = 10
        private const val RETRY_SLEEP_MS = 500L
        private const val PILOT_POLL_SLEEP_MS = 200L

        /** Decisão pura: reconfigurar a USB? Espelha a semântica nativa (pula só se uvc0==1 e sem force). */
        fun shouldReconfigure(cfg: UsbConfigState?, forceReconfigure: Boolean): Boolean =
            forceReconfigure || cfg == null || cfg.uvc0 != 1
```

(c) Adicionar campos de serialização (junto dos outros campos da classe):
```kotlin
    private val activationExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "GlassesActivation") }
    private val activationGen = AtomicInteger(0)
```

(d) SUBSTITUIR o corpo atual de `enableEyeCamera` (o bloco `Thread({ ... }, "GlassesEnableCamera").start()`) por:
```kotlin
    fun enableEyeCamera(forceReconfigure: Boolean = false) {
        if (!isConnected) { listener?.onError("Não conectado"); return }
        val gen = activationGen.incrementAndGet()
        activationExecutor.execute {
            try {
                enableEyeCameraKotlin(forceReconfigure, gen)
            } catch (e: Exception) {
                // Fallback OBSERVÁVEL (etapas 1–2; removido na Task 4).
                listener?.onError("Ativação Kotlin falhou (${e.message}); fallback nativo")
                Log.e(TAG, "Kotlin activation failed — falling back to native", e)
                enableEyeCameraNative(forceReconfigure)
            }
        }
    }

    /** Ativação via protocolo Kotlin puro. Lança em falha → o chamador faz fallback nativo. */
    private fun enableEyeCameraKotlin(forceReconfigure: Boolean, gen: Int) {
        Log.i(TAG, "Ativação: caminho KOTLIN (force=$forceReconfigure)")
        val dev = device ?: usbManager.deviceList.values.firstOrNull { it.vendorId == XREAL_VID }
            ?: throw IllegalStateException("nenhum device XREAL")
        if (!usbManager.hasPermission(dev)) throw IllegalStateException("sem permissão USB")
        val found = GlassesTransport.find(dev) ?: throw IllegalStateException("interface de controle não encontrada")
        val conn = openWithRetry(dev) ?: throw IllegalStateException("openDevice falhou após retries")
        val transport = GlassesTransport(conn, found.first, found.second, found.third)
        var skipTaken = false
        try {
            if (!transport.claim()) throw IllegalStateException("claimInterface falhou")

            val ready = waitPilotReady(transport)
            listener?.onLog("Pilot ready: $ready")

            val cfg = tryDecodeConfig(transport)
            listener?.onLog("Atual: $cfg")

            if (!shouldReconfigure(cfg, forceReconfigure)) {
                listener?.onLog("uvc0 já ativa — pulando reconfiguração USB")
                skipTaken = true
            } else {
                val resp = runOperation(transport, GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD))
                val code = resp.payload.firstOrNull()?.toInt() ?: -1
                listener?.onLog("Resultado: $code")
                if (code != 0) throw IllegalStateException("SetUsbConfigAll code=$code")
            }
        } finally {
            transport.release(); conn.close()
        }

        // Uma ativação mais nova assumiu enquanto dormíamos → aborta sem re-notificar.
        if (gen != activationGen.get()) { Log.d(TAG, "ativação #$gen superada — abortando"); return }

        if (!skipTaken) Thread.sleep(REENUMERATION_WAIT_MS)   // paridade; só após um Set real
        bestEffortCameraStatus()
        listener?.onCameraEnabled()
    }

    /** WaitPilotReady como POLL (native NRBSPWaitPilotReady é bloqueante c/ timeout de 10s). */
    private fun waitPilotReady(transport: GlassesTransport): Boolean {
        val deadline = System.nanoTime() + PILOT_READY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val resp = runCatching { runOperation(transport, GlassesCommands.waitPilotReadyMessages()) }.getOrNull()
            val txt = resp?.payload?.toString(Charsets.US_ASCII) ?: ""
            if (txt.contains(GlassesCommands.PILOT_READY_MARKER)) return true
            Thread.sleep(PILOT_POLL_SLEEP_MS)
        }
        return false
    }

    /** GetUsbConfigAll → decode; null/exceção ⇒ config desconhecida (⇒ não pula). */
    private fun tryDecodeConfig(transport: GlassesTransport): UsbConfigState? =
        runCatching { UsbConfigCodec.decode(runOperation(transport, GlassesCommands.getUsbConfigMessages()).payload) }
            .getOrNull()

    /** GetCameraStatus best-effort: conexão NOVA, re-lookup do device, só loga. */
    private fun bestEffortCameraStatus() {
        val dev = usbManager.deviceList.values.firstOrNull { it.vendorId == XREAL_VID } ?: return
        if (!usbManager.hasPermission(dev)) { listener?.onLog("Status: sem permissão pós-re-enum (ok)"); return }
        val found = GlassesTransport.find(dev) ?: return
        val conn = usbManager.openDevice(dev) ?: return
        val transport = GlassesTransport(conn, found.first, found.second, found.third)
        try {
            if (!transport.claim()) return
            val resp = runOperation(transport, GlassesCommands.getCameraStatusMessages())
            listener?.onLog("Status da câmera: ${resp.payload.joinToString(" ") { "%02x".format(it) }}")
        } catch (e: Exception) {
            listener?.onLog("Status da câmera indisponível: ${e.message}")
        } finally {
            transport.release(); conn.close()
        }
    }

    /** Envia todas as mensagens da operação (preâmbulos + comando), devolve a resposta da última. */
    private fun runOperation(transport: GlassesTransport, messages: List<ByteArray>): GlassesFrame.Parsed {
        var last: GlassesFrame.Parsed? = null
        for (m in messages) last = requestWithRetry(transport, m)
        return last ?: throw IllegalStateException("operação vazia")
    }

    private fun requestWithRetry(transport: GlassesTransport, message: ByteArray): GlassesFrame.Parsed {
        var lastErr: Exception? = null
        repeat(OPEN_RETRY_COUNT) {
            try { return transport.request(message) } catch (e: Exception) { lastErr = e; Thread.sleep(RETRY_SLEEP_MS) }
        }
        throw lastErr ?: IllegalStateException("request falhou")
    }

    private fun openWithRetry(dev: android.hardware.usb.UsbDevice): UsbDeviceConnection? {
        repeat(OPEN_RETRY_COUNT) {
            usbManager.openDevice(dev)?.let { return it }
            Thread.sleep(RETRY_SLEEP_MS)
        }
        return null
    }
```

(e) RENOMEAR o método nativo atual para o fallback: o corpo antigo de `enableEyeCamera` (o que usa `nativeGlasses`/`NRBSP*`) vira `private fun enableEyeCameraNative(forceReconfigure: Boolean)`, rodando **inline** (remover o wrapper `Thread({...}).start()` — o `activationExecutor` já provê a thread; manter o try/catch interno). `nativeGlasses`/`nativeInitialized`/`initNativeLibrary()` FICAM (removidos na Task 4). O `initNativeLibrary()` segue sendo chamado no `start()`.

> Detalhe de resposta: cada comando OUT (inclusive os preâmbulos) recebe um IN; `transport.request` faz um par OUT/IN. Por isso `runOperation` envia cada mensagem e usa a resposta da ÚLTIMA (o comando real).

- [ ] **Step 4: Rodar e confirmar GREEN da decisão + compilação**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.capture.GlassesConnectionDecisionTest"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: 4/4 na decisão; `compileDebugKotlin` `BUILD SUCCESSFUL`.

- [ ] **Step 5: Suíte inteira**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, tudo verde (a lógica USB real é validada na Task 3).

- [ ] **Step 6: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/capture/GlassesConnection.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/capture/GlassesConnectionDecisionTest.kt
git commit -m "Kotlin producao: ativacao via GlassesTransport no GlassesConnection (serializada, retry, skip-if-active, fallback nativo)"
```

---

### Task 3: Validação em hardware (4 cenários) — EXIGE APARELHO + USUÁRIO

Gate antes da remoção. Executada pelo controller com o usuário presente (o build/install derruba a11y + grant USB). NÃO é subagente.

**Files:** nenhum de código; registra resultado no doc do protocolo + ledger.

- [ ] **Step 1: Build + install (agrupado, 1 vez)**

```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell pm grant com.raphael.handmouse android.permission.CAMERA
```
Pedir ao usuário: reabilitar acessibilidade, replugar óculos (aceitar diálogo USB), abrir app → "Iniciar Captura". Confirmar `[STREAMING]` e que o log diz **"Ativação: caminho KOTLIN"** SEM `fallback nativo`.

- [ ] **Step 2: Cenário warm reconnect (`uvc0` já 1)**

Com a câmera já ativa, observar no logcat: "uvc0 já ativa — pulando reconfiguração USB", `onCameraEnabled`, sem re-enumeração. Confirmar streaming estável.

- [ ] **Step 3: Cenário cold start (`uvc0` 0→1)**

Estado onde `uvc0` começa 0 (ex.: após power-cycle dos óculos que zera a config). Observar: `Atual:` com `uvc0=0` (logar o raw p/ auditar o decode de amostra nova), `Resultado: 0`, `sleep`, re-enumeração tratada pelo pipeline, `[STREAMING]`. Verificar que rodou Kotlin sem fallback.

- [ ] **Step 4: Cenário forceReconfigure (dead-stream)**

Forçar streams natimortos consecutivos (o `EyeCaptureService` passa `forceReconfigure=true`) — ou disparar `FORCE_CAPTURE` (que chama `enableEyeCamera(forceReconfigure=true)`). Observar reconfiguração completa mesmo com uvc0 ativa, câmera revive.

- [ ] **Step 5: Cenário cold-boot (óculos recém-ligados) — exercita o poll do waitPilotReady**

Desligar/religar os óculos e conectar; observar o poll do `waitPilotReady` (várias iterações de GetProperty até "true"), depois ativação normal. Confirmar que não trava nem falha por firmware não-pronto.

- [ ] **Step 6: Registrar + commit**

Adicionar seção "Validação Kotlin-produção em hardware" ao `docs/superpowers/specs/2026-07-23-kotlin-so-removal-design.md` com o resultado dos 4 cenários (qual caminho rodou, decode cru observado, streaming). Se algum cenário exigiu ajuste de código, corrigir na Task 2 e re-validar.
```bash
git add docs/superpowers/specs/2026-07-23-kotlin-so-removal-design.md
git commit -m "Kotlin producao: validacao em hardware dos 4 cenarios de ativacao"
```

> **GATE:** só prosseguir pra Task 4 com os 4 cenários verdes rodando o caminho Kotlin SEM fallback.

---

### Task 4: Remoção do proprietário/terceiro + prova de APK limpo

Só após a Task 3 verde. Remove as `.so`, o shim verbatim, os assets, o caminho nativo e o fallback; preserva a atribuição MIT.

**Files:**
- Create: `NOTICE` (raiz do repo)
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/capture/GlassesConnection.kt` (remover nativo/fallback)
- Delete: `xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/libnr_glasses_api.so`, `libota-lib.so`; `xreal-hand-mouse/app/src/main/java/ai/nreal/glasses/control/` (11 arquivos); `xreal-hand-mouse/app/src/main/assets/nr_ota_default/` (incl. `.gitkeep`)

- [ ] **Step 1: Criar `NOTICE` na raiz (atribuição MIT dos derivados que ficam)**

Create `NOTICE` com o conteúdo do `NOTICE.txt` do pacote, adaptado para listar os arquivos MIT-derivados que PERMANECEM (`UvcCameraHelper.kt`, `HevcDecoder.kt`, `HevcNal.kt`, `FrameAssembler.kt`, `GlassesConnection.kt` — control-flow), citando a licença MIT e o Copyright 2026 Aloim e a URL do repo.

- [ ] **Step 2: Remover o caminho nativo + fallback do `GlassesConnection`**

Remover: `enableEyeCameraNative`, `nativeGlasses`, `nativeInitialized`, `initNativeLibrary()`, a chamada a `initNativeLibrary()` no `start()`, os imports de `ai.nreal.glasses.control`. No `enableEyeCamera`, o `catch` do fallback vira: logar `onError` + **chamar `listener?.onCameraEnabled()`** (replicando o "sempre chega em onCameraEnabled" do nativo — entrega a recuperação ao retry do `findAndStartCamera`; NÃO deixar travar em ERROR). Adicionar limpeza one-time no `start()`: `runCatching { java.io.File(context.filesDir, "nr_ota_default").deleteRecursively() }` (a `.so` copiava firmware pro dataDir).

- [ ] **Step 3: Deletar `.so`, shim e assets**

```bash
git rm xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/libnr_glasses_api.so xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/libota-lib.so
git rm -r xreal-hand-mouse/app/src/main/java/ai/nreal/glasses/control
git rm -r xreal-hand-mouse/app/src/main/assets/nr_ota_default
```
> Se as `.so` forem gitignored (não rastreadas), `git rm` falha nelas — nesse caso deletar do disco com `Remove-Item` e confirmar que somem do build. Os `.java` e assets são rastreados.

- [ ] **Step 4: Build + suíte**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest assembleDebug
```
Expected: `BUILD SUCCESSFUL` (nada mais referencia `ai.nreal.glasses.control` nem `System.loadLibrary("ota-lib")`).

- [ ] **Step 5: Provar APK limpo**

```bash
APK=xreal-hand-mouse/app/build/outputs/apk/debug/app-debug.apk
unzip -l "$APK" | grep -iE "libnr_glasses_api|libota-lib|nr_ota_default" && echo "!!! SUJO" || echo "sem .so proprietaria / assets"
for d in $(unzip -l "$APK" | grep -oE "classes[0-9]*\.dex"); do unzip -p "$APK" "$d" | strings | grep -c "ai/nreal/glasses/control" ; done
```
Expected: sem `.so`/assets proprietários; 0 refs a `ai/nreal/glasses/control` nos dex.

- [ ] **Step 6: Install + smoke test em hardware (usuário)**

Build/install (mesmo grupo da Task 3 Step 1). Usuário restaura a11y + replug + "Iniciar Captura". Confirmar `[STREAMING]` e "Ativação: caminho KOTLIN" — agora sem nenhum caminho nativo existente. Rodar warm + cold start uma vez cada.

- [ ] **Step 7: Commit**

```bash
git add NOTICE xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/capture/GlassesConnection.kt
git commit -m "Remove .so proprietarias + shim verbatim + assets mortos; Kotlin unico caminho; atribuicao MIT preservada (NOTICE)"
```

---

## Self-Review (cobertura do spec)

- **Promover Kotlin a produção, interface inalterada:** ✅ Task 2 (troca miolo, mesma API pública).
- **Fluxo ramificado skip-if-active + preâmbulo:** ✅ Task 2 (`shouldReconfigure`, helpers da Task 1).
- **WaitPilotReady poll + cenário cold-boot:** ✅ Task 2 (`waitPilotReady`), Task 3 Step 5.
- **Retry limitado (não regredir robustez) + não travar em ERROR:** ✅ Task 2 (`openWithRetry`/`requestWithRetry`), Task 4 Step 2 (fallback → `onCameraEnabled`).
- **Fallback observável ampliado (Set≠0, decode):** ✅ Task 2 (Set≠0 lança; decode cru logado; exceção → nativo).
- **Serialização + token de geração:** ✅ Task 2 (`activationExecutor`, `activationGen`).
- **GetCameraStatus best-effort (re-lookup, try/finally):** ✅ Task 2 (`bestEffortCameraStatus`).
- **Remover .so + shim + assets + limpeza dataDir + NOTICE:** ✅ Task 4.
- **Prova de APK limpo:** ✅ Task 4 Step 5.
- **Validação em hardware dos 4 cenários (gate):** ✅ Task 3.
- **Copyright (atribuição MIT preservada):** ✅ Task 4 Steps 1–2, headers KDoc mantidos.
