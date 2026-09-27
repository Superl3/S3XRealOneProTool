# Spike de reconhecimento do protocolo USB (caminho pro APK 100% limpo) — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Descobrir, com evidência, se as 4 chamadas de câmera (`NRBSPWaitPilotReady`, `NRBSPGetUsbConfigAll`, `NRBSPSetUsbConfigAll`, `NRBSPGetCameraStatus`) podem ser reimplementadas em Kotlin puro sobre a USB Host API — capturando o tráfego USB real das `.so` da XREAL in-process, sem root e sem hardware.

**Architecture:** Um interpositor `ioctl()` via `LD_PRELOAD`+`wrap.sh` (só no build debug) loga os pacotes `USBDEVFS_*` que `libnr_glasses_api.so` envia enquanto a `.so` real roda. Capturamos a sequência ≥2 vezes em sessões frescas, diffamos os bytes (estático→GO / dinâmico→escala Ghidra), e escrevemos um veredito GO/NO-GO. O spike **não reimplementa nada**; a reescrita é fase seguinte, gated no GO.

**Tech Stack:** Android (Kotlin + NDK/CMake C), `LD_PRELOAD`/`wrap.sh`, `USBDEVFS` ioctls, `__android_log`, adb/logcat. arm64-v8a.

## Global Constraints

- **Plataforma:** arm64-v8a apenas; `minSdk = 34`; `compileSdk = 35`. NDK instalado: `27.1.12297006`.
- **Build:** `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` antes de `.\gradlew.bat`. Diretório do app: `xreal-hand-mouse/`. Instalar com `adb install -r`. adb Wi-Fi da sessão: `adb connect <DEVICE-IP>:5555` (o IP pode mudar).
- **As 2 `.so` são obrigatórias e proprietárias:** `libota-lib.so` hospeda os JNI `Java_...XrealGlasses_*` (inclusive as 4 de câmera) e faz `NEEDED`-link em `libnr_glasses_api.so` (impl C do protocolo). Ambas ficam em `xreal-hand-mouse/app/src/main/jniLibs/arm64-v8a/`, são **gitignored** e fornecidas localmente. **NUNCA commitar as `.so` nem `assets/nr_ota_default/`.**
- **Linha de copyright:** só observação black-box do nosso próprio processo/hardware. Ghidra é **só referência de entendimento** no ramo dinâmico; nada da estrutura de código das `.so` entra em código de produção.
- **Toda a instrumentação do spike é debug-only:** o build release NÃO pode conter `wrap.sh` nem `libioctltap.so`. Verificado na Task 9.

---

### Task 1: Fase 0 — Revisão de literatura + doc do protocolo (esqueleto)

Pesquisa antes de código: talvez o protocolo já esteja documentado pela comunidade, o que encurta ou resolve o spike.

**Files:**
- Create: `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md`

**Interfaces:**
- Produces: o doc-vivo `glasses-usb-protocol.md` que as Tasks 6–8 preenchem.

- [ ] **Step 1: Pesquisar RE existente do USB da XREAL**

Pesquisar (web) e anotar achados sobre o protocolo USB de controle/câmera dos óculos XREAL:
- Projeto Monado (driver XREAL), `xrealAirLinuxDriver` / `breezy-desktop` (wheaney), e o repo do Aloim (`Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android`).
- Termos-chave: "XREAL One Pro USB config", "uvc0 enable", "NRBSP", "pilot ready", "usbdevfs control transfer glasses", VID `0x3318` (13080).
- Objetivo: existe descrição dos control transfers que ligam a UVC? O protocolo parece estático (comandos fixos) ou tem handshake/auth?

- [ ] **Step 2: Escrever o esqueleto do doc do protocolo**

Criar `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md` com:

```markdown
# Protocolo USB dos óculos XREAL (câmera Eye) — reconhecimento

**Origem:** spike do plano 2026-07-23-copyright-clean-spike.md
**Método:** captura black-box in-process (LD_PRELOAD de ioctl), diff de sessões.

## Fase 0 — Literatura (preenchida na Task 1)
- Fontes consultadas: <lista com links>
- O protocolo já é documentado publicamente? <sim/parcial/não>
- Hipótese preliminar estático-vs-dinâmico: <...> (justificativa)

## Fase 1 — Sequência capturada (preenchida na Task 6)
_(a preencher)_

## Fase 2 — Veredito GO/NO-GO (preenchida na Task 7)
_(a preencher)_

## Peso-morto OTA (preenchida na Task 8)
_(a preencher)_
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md
git commit -m "Spike: Fase 0 (literatura) + esqueleto do doc do protocolo USB"
```

---

### Task 2: Interpositor `ioctl` (C/NDK) + build CMake debug

O interpositor loga os pacotes `USBDEVFS_*`. Ainda não é preloaded (Task 3); aqui só garantimos que compila e é empacotado no APK debug.

**Files:**
- Create: `xreal-hand-mouse/app/src/main/cpp/ioctltap.c`
- Create: `xreal-hand-mouse/app/src/main/cpp/CMakeLists.txt`
- Modify: `xreal-hand-mouse/app/build.gradle.kts` (externalNativeBuild + excluir do release)

**Interfaces:**
- Produces: `libioctltap.so` (arm64) empacotada no APK debug; símbolo interposto `int ioctl(int, unsigned long, ...)`; log tag `IOCTLTAP`.

- [ ] **Step 1: Escrever o interpositor**

Create `xreal-hand-mouse/app/src/main/cpp/ioctltap.c`:

```c
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <linux/ioctl.h>        // _IOR/_IOW/_IOWR — NÃO vem via usbdevice_fs.h neste NDK.
                                // (não usar <sys/ioctl.h>: conflita com o ioctl() overloaded da bionic)
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define TAG "IOCTLTAP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static int (*real_ioctl)(int, unsigned long, ...) = NULL;

__attribute__((constructor))
static void tap_init(void) { LOGI("IOCTLTAP active (LD_PRELOAD)"); }

static void hexdump(const char *label, const unsigned char *p, int len) {
    if (!p || len <= 0) { LOGI("%s <none>", label); return; }
    int n = len > 64 ? 64 : len;   // limita p/ não estourar o buffer de log
    char buf[64 * 3 + 4];
    int o = 0;
    for (int i = 0; i < n; i++) o += snprintf(buf + o, sizeof(buf) - o, "%02x ", p[i]);
    LOGI("%s len=%d %s%s", label, len, buf, len > n ? "..." : "");
}

int ioctl(int fd, unsigned long request, ...) {
    va_list ap; va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (!real_ioctl) real_ioctl = dlsym(RTLD_NEXT, "ioctl");
    if (!real_ioctl) {
        static int logged = 0;
        if (!logged) {
            LOGI("ERROR: Failed to resolve real ioctl via dlsym");
            logged = 1;
        }
        errno = ENOSYS;
        return -1;
    }

    if (request == USBDEVFS_CONTROL) {
        struct usbdevfs_ctrltransfer *c = arg;
        LOGI("CONTROL fd=%d bmRequestType=0x%02x bRequest=0x%02x wValue=0x%04x wIndex=0x%04x wLength=%d",
             fd, c->bRequestType, c->bRequest, c->wValue, c->wIndex, c->wLength);
        if ((c->bRequestType & 0x80) == 0) hexdump("  ctrl-OUT", c->data, c->wLength);
        int r = real_ioctl(fd, request, arg);
        // Salva/restaura errno em volta do logging: __android_log_print faz I/O (socket/writev)
        // que pode sobrescrever errno — sem isto, r == -1 chegava no caller (libnr_glasses_api.so)
        // com o errno do LOG, não o do ioctl real (ETIMEDOUT/EPIPE/ENODEV mascarado).
        int saved_errno = errno;
        if ((c->bRequestType & 0x80) && r >= 0) hexdump("  ctrl-IN", c->data, r);
        LOGI("CONTROL ret=%d", r);
        errno = saved_errno;
        return r;
    }
    if (request == USBDEVFS_BULK) {
        struct usbdevfs_bulktransfer *b = arg;
        LOGI("BULK fd=%d ep=0x%02x len=%u", fd, b->ep, b->len);
        if ((b->ep & 0x80) == 0) hexdump("  bulk-OUT", b->data, b->len);
        int r = real_ioctl(fd, request, arg);
        int saved_errno = errno; // ver comentário no ramo CONTROL acima
        if ((b->ep & 0x80) && r >= 0) hexdump("  bulk-IN", b->data, r);
        LOGI("BULK ret=%d", r);
        errno = saved_errno;
        return r;
    }
    if (request == USBDEVFS_SUBMITURB) {
        struct usbdevfs_urb *u = arg;
        LOGI("SUBMITURB fd=%d type=%d ep=0x%02x len=%d", fd, u->type, u->endpoint, u->buffer_length);
        if (u->type == USBDEVFS_URB_TYPE_CONTROL) hexdump("  urb-setup+data", u->buffer, u->buffer_length);
        else if ((u->endpoint & 0x80) == 0) hexdump("  urb-OUT", u->buffer, u->buffer_length);
        return real_ioctl(fd, request, arg);
    }
    if (request == USBDEVFS_REAPURB || request == USBDEVFS_REAPURBNDELAY) {
        int r = real_ioctl(fd, request, arg);
        struct usbdevfs_urb **pu = arg;
        if (r == 0 && pu && *pu) {
            struct usbdevfs_urb *u = *pu;
            LOGI("REAPURB type=%d ep=0x%02x actual=%d status=%d", u->type, u->endpoint, u->actual_length, u->status);
            if (u->endpoint & 0x80) hexdump("  urb-IN", (const unsigned char *)u->buffer, u->actual_length);
        }
        return r;
    }
    return real_ioctl(fd, request, arg);  // todos os outros ioctls passam direto
}
```

- [ ] **Step 2: Escrever o CMakeLists**

Create `xreal-hand-mouse/app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(ioctltap C)
add_library(ioctltap SHARED ioctltap.c)
find_library(log-lib log)
target_link_libraries(ioctltap ${log-lib})
```

- [ ] **Step 3: Ligar o NDK no build.gradle e excluir do release**

Modify `xreal-hand-mouse/app/build.gradle.kts` — dentro do bloco `android { ... }`, adicionar:

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        // ... conteúdo existente ...
        ndk { abiFilters += "arm64-v8a" }
    }

```
E, no nível superior do arquivo (fora do bloco `android { }`), a exclusão do release:

```kotlin
// O interpositor do spike NUNCA vai pro release.
// ATENÇÃO: NÃO usar `buildTypes.release { packaging { jniLibs { excludes += … } } }` —
// verificado por A/B neste projeto (AGP 8.10.1 + Gradle 8.14): aquele DSL vaza a exclusão
// pro variant DEBUG também, removendo a lib justamente de onde ela é necessária.
// A Variant API abaixo é corretamente escopada por variant.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.packaging.jniLibs.excludes.add("**/libioctltap.so")
}
```

**Fix de revisão final (Important)**: a exclusão acima depende do literal `"**/libioctltap.so"` — um rename do alvo CMake ou um 2º `.so` novo em `CMakeLists.txt` vazaria pro release sem erro nem aviso. Testado nesta sessão: AGP 8.10.1 não expõe uma forma funcional de escopar `externalNativeBuild` por variant (o `cmake.targets` de `defaultConfig`/`buildTypes.release` é um `MutableSet` que a AGP UNE em vez de sobrescrever entre os dois — confirmado empiricamente, um `targets()` vazio em `release` não impediu nem a compilação nem o empacotamento). Por isso, além do bloco acima, adicionar TAMBÉM esta verificação pós-build (deriva os nomes de `.so` DIRETO do `CMakeLists.txt`, não hard-coded, e falha `assembleRelease` se algum sobreviver no APK de release):

```kotlin
import java.util.zip.ZipFile

tasks.register("verifyNoSpikeNativeLibsInRelease") {
    group = "verification"
    description = "Falha se algum .so definido em src/main/cpp/CMakeLists.txt estiver no APK de release."
    dependsOn("packageRelease")

    doLast {
        val cmakeListsFile = file("src/main/cpp/CMakeLists.txt")
        val libTargetRegex = Regex("""add_library\(\s*(\S+)\s+SHARED""")
        val forbiddenSoNames = cmakeListsFile.readText()
            .lineSequence()
            .mapNotNull { libTargetRegex.find(it)?.groupValues?.get(1) }
            .map { "lib$it.so" }
            .toSet()

        if (forbiddenSoNames.isEmpty()) {
            logger.warn("verifyNoSpikeNativeLibsInRelease: nenhum add_library(... SHARED ...) encontrado em $cmakeListsFile — nada a verificar (suspeito; confira o CMakeLists.txt).")
            return@doLast
        }

        val apkOutDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apkFile = apkOutDir.listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull()
            ?: throw GradleException("verifyNoSpikeNativeLibsInRelease: nenhum APK de release encontrado em $apkOutDir")

        val leaked = ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name.substringAfterLast('/') }
                .filter { it in forbiddenSoNames }
                .toList()
        }

        if (leaked.isNotEmpty()) {
            throw GradleException(
                "APK de release ($apkFile) contém biblioteca(s) nativa(s) definida(s) em " +
                    "src/main/cpp/CMakeLists.txt que deveriam ser DEBUG-ONLY: $leaked"
            )
        }
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("verifyNoSpikeNativeLibsInRelease")
    }
}
```

> Nota: `abiFilters` vai DENTRO do `defaultConfig` já existente (não crie um segundo).

- [ ] **Step 4: Build debug e confirmar que a `.so` foi empacotada**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`. Depois:
```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\jar.exe" tf app\build\outputs\apk\debug\app-debug.apk | Select-String "libioctltap.so"
```
Expected: linha `lib/arm64-v8a/libioctltap.so`.

- [ ] **Step 5: Commit**

```bash
git add xreal-hand-mouse/app/src/main/cpp/ xreal-hand-mouse/app/build.gradle.kts
git commit -m "Spike: interpositor ioctl (NDK) empacotado no debug, excluido do release"
```

---

### Task 3: `wrap.sh` (LD_PRELOAD, debug-only) e confirmar interposição ativa

**Files:**
- Create: `xreal-hand-mouse/app/src/debug/resources/lib/arm64-v8a/wrap.sh`

**Interfaces:**
- Consumes: `libioctltap.so` (Task 2).
- Produces: processo do app debug inicia com `libioctltap.so` preloaded (log `IOCTLTAP active`).

- [ ] **Step 1: Escrever o wrap.sh**

Create `xreal-hand-mouse/app/src/debug/resources/lib/arm64-v8a/wrap.sh` (LF, não CRLF):

```sh
#!/system/bin/sh
LD_PRELOAD="$(dirname "$0")/libioctltap.so" exec "$@"
```

- [ ] **Step 1b: OBRIGATÓRIO — extrair as libs nativas pro disco (debug-only)**

Sem isto o `wrap.sh` aborta o processo inteiro com
`CANNOT LINK EXECUTABLE "/system/bin/app_process64": library ".../lib/arm64/libioctltap.so" not found`.
Motivo: o AGP moderno empacota com `extractNativeLibs=false` — as `.so` ficam DENTRO do APK e
nunca são escritas em disco. `System.loadLibrary` lida com isso, mas **`LD_PRELOAD` exige um
caminho real no filesystem**.

Create `xreal-hand-mouse/app/src/debug/AndroidManifest.xml` (overlay só do debug):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:extractNativeLibs="true"
        tools:replace="android:extractNativeLibs" />
</manifest>
```

> Verificado: o merged manifest do debug fica `true` e o do release `false` — não vaza.
> O AGP imprime um aviso "should be set via DSL instead"; é cosmético (não existe
> `useLegacyPackaging` escopado por variant no AGP 8.10.1) e pode ser ignorado.

- [ ] **Step 2: Garantir line endings LF**

Run:
```bash
cd "C:/Users/Raphael/Documents/Projetos/xreal hand tracking"
sed -i 's/\r$//' xreal-hand-mouse/app/src/debug/resources/lib/arm64-v8a/wrap.sh
file xreal-hand-mouse/app/src/debug/resources/lib/arm64-v8a/wrap.sh
```
Expected: sem "CRLF".

- [ ] **Step 3: Rebuild, instalar, confirmar preload**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
& "C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -c
```
Abrir o app no aparelho, então:
```powershell
& "C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -d -s IOCTLTAP:I
```
Expected: aparece `IOCTLTAP active (LD_PRELOAD)`.

> Se NÃO aparecer (SELinux/wrap.sh bloqueado neste device): fallback documentado — trocar o mecanismo por hook PLT/GOT com `shadowhook`/`xHook` (a lógica de decode do ioctltap.c é reaproveitada; muda só como o hook é instalado). Registrar o bloqueio no doc do protocolo e parar aqui pra decidir.

- [ ] **Step 4: Commit**

```bash
git add xreal-hand-mouse/app/src/debug/resources/lib/arm64-v8a/wrap.sh
git commit -m "Spike: wrap.sh debug-only faz LD_PRELOAD do interpositor ioctl"
```

---

### Task 4: Validar o decoder com um control transfer conhecido

Prova que o decode dos structs está correto ANTES de confiar nas capturas da XREAL. Usa um `GET_DESCRIPTOR` (device) — que passa por `USBDEVFS_CONTROL` no nosso processo via `UsbDeviceConnection.controlTransfer`.

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt`
- Modify: `xreal-hand-mouse/app/build.gradle.kts` (habilitar `buildConfig`)

**Interfaces:**
- Consumes: `IOCTLTAP` log (Task 3); `GlassesConnection.XREAL_VID`.
- Produces: broadcast debug `com.raphael.handmouse.VALIDATE_TAP` que emite um GET_DESCRIPTOR nos óculos.

- [ ] **Step 1: Habilitar BuildConfig**

Modify `xreal-hand-mouse/app/build.gradle.kts` — dentro de `android { ... }`:
```kotlin
    buildFeatures { buildConfig = true }
```

- [ ] **Step 2: Adicionar o receiver de validação (debug-only) no EyeCaptureService**

Modify `EyeCaptureService.kt`. Adicionar o import e o campo/rotina. Perto dos outros `BroadcastReceiver`s (ex.: após `usbAttachReceiver`, ~linha 547):

```kotlin
    // ─── SPIKE (debug-only): valida o decoder do IOCTLTAP com um GET_DESCRIPTOR conhecido ───
    private val validateTapReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = usb.deviceList.values.firstOrNull {
                it.vendorId == GlassesConnection.XREAL_VID
            } ?: run { Log.w(TAG, "VALIDATE_TAP: óculos não encontrados"); return }
            if (!usb.hasPermission(dev)) { Log.w(TAG, "VALIDATE_TAP: sem permissão USB"); return }
            val conn = usb.openDevice(dev) ?: run { Log.w(TAG, "VALIDATE_TAP: openDevice falhou"); return }
            try {
                val buf = ByteArray(18)
                // GET_DESCRIPTOR device: bmRequestType=0x80 bRequest=6 wValue=0x0100 wIndex=0 wLength=18
                val n = conn.controlTransfer(0x80, 0x06, 0x0100, 0, buf, buf.size, 1000)
                Log.i(TAG, "VALIDATE_TAP: controlTransfer ret=$n (esperado 18)")
            } finally { conn.close() }
        }
    }
```

- [ ] **Step 3: Registrar/desregistrar o receiver só em debug**

Em `onCreate` (perto de `glassesConnection = GlassesConnection(this)`, ~linha 562), adicionar:
```kotlin
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, validateTapReceiver,
                IntentFilter("com.raphael.handmouse.VALIDATE_TAP"),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
```
No `onDestroy` (onde os outros receivers são desregistrados), adicionar:
```kotlin
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(validateTapReceiver) }
```
Garantir os imports: `com.raphael.handmouse.BuildConfig`, `androidx.core.content.ContextCompat`, `android.content.IntentFilter`, `android.hardware.usb.UsbManager`.

- [ ] **Step 4: Build, instalar, disparar, verificar decode**

Run (com óculos conectados e permissão já concedida ao Hand Mouse):
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb logcat -c
& $adb shell am broadcast -a com.raphael.handmouse.VALIDATE_TAP -p com.raphael.handmouse
& $adb logcat -d -s IOCTLTAP:I EyeCaptureService:I
```
Expected: o IOCTLTAP loga uma linha
`CONTROL fd=… bmRequestType=0x80 bRequest=0x06 wValue=0x0100 wIndex=0x0000 wLength=18`
seguida de `ctrl-IN len=18 12 01 …` (byte 0 = `0x12` = 18, byte 1 = `0x01` = DEVICE descriptor), e `VALIDATE_TAP: controlTransfer ret=18`. **Se os campos batem, o decoder está validado.**

- [ ] **Step 5: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt xreal-hand-mouse/app/build.gradle.kts
git commit -m "Spike: validacao on-device do decoder IOCTLTAP via GET_DESCRIPTOR (debug-only)"
```

---

### Task 5: Gatilho de captura repetível (force enable, debug-only)

Permite re-rodar a sequência completa das 4 chamadas sob demanda, para capturar sessões frescas.

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt`

**Interfaces:**
- Consumes: `glassesConnection.enableEyeCamera(forceReconfigure: Boolean)` (existe, ~linha 825).
- Produces: broadcast debug `com.raphael.handmouse.FORCE_CAPTURE`.

- [ ] **Step 1: Adicionar o receiver de force-capture (debug-only)**

Modify `EyeCaptureService.kt`, ao lado do `validateTapReceiver`:
```kotlin
    // ─── SPIKE (debug-only): força a sequência completa NRBSP* (WaitPilotReady→Set/GetUsbConfig) ───
    private var lastForceCaptureAcceptedMs = 0L
    private val forceCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            val timeSinceLastMs = now - lastForceCaptureAcceptedMs
            if (timeSinceLastMs < FORCE_CAPTURE_DEBOUNCE_MS) {
                val waitMs = FORCE_CAPTURE_DEBOUNCE_MS - timeSinceLastMs
                Log.w(TAG, "FORCE_CAPTURE ignorado: sequência anterior ainda em andamento (aguarde ${waitMs}ms)")
                return
            }
            lastForceCaptureAcceptedMs = now
            Log.i(TAG, "FORCE_CAPTURE: reexecutando enableEyeCamera(forceReconfigure=true)")
            // Serializa com as chamadas do próprio pipeline (ver controlThread, "fix de ANR") —
            // sem isto, a re-enumeração disparada por este enableEyeCamera direto podia correr
            // CONCORRENTEMENTE com o enableEyeCamera de glassesListener.onConnected (disparado
            // pelo ATTACH que este próprio FORCE_CAPTURE causa), duas threads GlassesEnableCamera
            // driblando o mesmo fd/lib nativa ao mesmo tempo.
            controlHandler.post { glassesConnection.enableEyeCamera(forceReconfigure = true) }
        }
    }
```
`FORCE_CAPTURE_DEBOUNCE_MS` (companion object, ex.: `10_000L`) evita que dois broadcasts em sequência rápida sobreponham duas execuções da sequência NRBSP*.

- [ ] **Step 2: Registrar/desregistrar junto do outro (debug-only)**

No mesmo bloco `if (BuildConfig.DEBUG)` do `onCreate`:
```kotlin
            ContextCompat.registerReceiver(
                this, forceCaptureReceiver,
                IntentFilter("com.raphael.handmouse.FORCE_CAPTURE"),
                ContextCompat.RECEIVER_EXPORTED
            )
```
E no `onDestroy`:
```kotlin
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(forceCaptureReceiver) }
```

- [ ] **Step 3: Build, instalar, disparar, confirmar que a sequência roda**

Run (óculos conectados):
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb logcat -c
& $adb shell am broadcast -a com.raphael.handmouse.FORCE_CAPTURE -p com.raphael.handmouse
Start-Sleep -Seconds 6
& $adb logcat -d -s IOCTLTAP:I GlassesConnection:I EyeCaptureService:I > "$env:TEMP\cap_smoke.txt"
Select-String -Path "$env:TEMP\cap_smoke.txt" -Pattern "CONTROL|BULK|SUBMITURB|SetUsbConfig" | Select-Object -First 20
```
Expected: linhas `IOCTLTAP CONTROL/BULK/SUBMITURB` intercaladas com os logs do `enableEyeCamera` (`Chamando NRBSPSetUsbConfigAll…`). Confirma que capturamos o tráfego real das 4 chamadas.

- [ ] **Step 4: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt
git commit -m "Spike: gatilho debug FORCE_CAPTURE re-roda a sequencia NRBSP* completa"
```

---

### Task 6: Captura das sessões frescas + decode da sequência

**Files:**
- Modify: `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md` (seção "Fase 1")

**Interfaces:**
- Consumes: gatilho `FORCE_CAPTURE` (Task 5), IOCTLTAP (Task 3).
- Produces: ≥2 arquivos de captura + a sequência decodificada no doc.

- [ ] **Step 1: Capturar a sessão A (fresca)**

Desconectar e reconectar os óculos (estado fresco), então:
```powershell
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am broadcast -a com.raphael.handmouse.FORCE_CAPTURE -p com.raphael.handmouse
Start-Sleep -Seconds 8
mkdir "$env:TEMP\xreal-cap" -Force | Out-Null
& $adb logcat -d -s IOCTLTAP:I > "$env:TEMP\xreal-cap\capA.txt"
```
(As capturas ficam em `$env:TEMP\xreal-cap\` — **fora do repo**, sem risco de vazar pro git.)

- [ ] **Step 2: Capturar a sessão B (fresca, após reboot dos óculos)**

Reiniciar os óculos (desplugar/replugar ou power-cycle), repetir o Step 1 salvando em `$env:TEMP\xreal-cap\capB.txt`.

- [ ] **Step 3: Decodificar a sequência lógica**

A partir de `capA.txt`, transcrever a ordem dos control/bulk transfers no doc — para cada um: `bmRequestType/bRequest/wValue/wIndex/wLength` (ou ep/len no bulk) e os bytes OUT/IN. Agrupar por chamada lógica (`WaitPilotReady`, `GetUsbConfigAll`, `SetUsbConfigAll`, `GetCameraStatus`) usando os timestamps relativos aos logs do `GlassesConnection`.

Preencher a seção "Fase 1" de `2026-07-23-glasses-usb-protocol.md` com a tabela de transfers.

- [ ] **Step 4: Commit (só o doc, nunca as capturas)**

```bash
git add docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md
git commit -m "Spike: Fase 1 — sequencia USB das 4 chamadas NRBSP* decodificada"
```

---

### Task 7: Análise estático-vs-dinâmico + veredito GO/NO-GO

**Files:**
- Modify: `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md` (seção "Fase 2")

**Interfaces:**
- Consumes: `capA.txt`, `capB.txt` (Task 6).
- Produces: veredito GO/NO-GO documentado.

- [ ] **Step 1: Diffar as duas capturas**

Run:
```bash
cd "$TEMP/xreal-cap"   # PowerShell: cd "$env:TEMP\xreal-cap"
# normaliza tirando fd/timestamps variáveis, compara só os campos do protocolo
grep -oE "(CONTROL|BULK|SUBMITURB).*" capA.txt | sed -E 's/fd=[0-9]+//' > a.norm
grep -oE "(CONTROL|BULK|SUBMITURB).*" capB.txt | sed -E 's/fd=[0-9]+//' > b.norm
diff a.norm b.norm && echo "IDENTICO=ESTATICO" || echo "DIVERGE=DINAMICO"
```

- [ ] **Step 2: Classificar e escrever o veredito**

Preencher a seção "Fase 2" do doc:
- **Se idêntico → ESTÁTICO → GO.** Anexar o mapeamento URB→Kotlin de cada transfer: para control, `UsbDeviceConnection.controlTransfer(bmRequestType, bRequest, wValue, wIndex, dados, wLength, timeout)`; para bulk, `bulkTransfer(endpoint, dados, len, timeout)`. Isso vira a base do spec da reescrita.
- **Se diverge → identificar o que muda** (offset dos bytes variáveis; parece nonce/contador/checksum?). Marcar como **DINÂMICO → escalar pra Ghidra (só referência)** para reverter o algoritmo. Registrar o risco de NO-GO se for cripto de auth.
- Registrar a recomendação final **GO / NO-GO** com nível de confiança (alto/médio/baixo) e a evidência (o resultado do diff).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md
git commit -m "Spike: Fase 2 — veredito GO/NO-GO (estatico-vs-dinamico) com evidencia"
```

---

### Task 8: Teste de peso-morto — `assets/nr_ota_default/` (independente)

Fatos já confirmados por readelf/nm (baked-in, não re-descobrir): **as 2 `.so` são obrigatórias** (`libota-lib.so` hospeda os JNI e faz NEEDED-link em `libnr_glasses_api.so`). O único candidato a remoção é `assets/nr_ota_default/`. Esta task **prova** isso em hardware.

**Files:**
- Temp move: `xreal-hand-mouse/app/src/main/assets/nr_ota_default/` (mover fora e restaurar)
- Modify: `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md` (seção "Peso-morto OTA")

- [ ] **Step 1: Remover temporariamente os assets OTA e rebuildar**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
Move-Item app\src\main\assets\nr_ota_default "$env:TEMP\nr_ota_default_bak"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
& "C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: Rodar o fluxo da câmera e confirmar ativação**

Com óculos conectados, abrir o app e disparar:
```powershell
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am broadcast -a com.raphael.handmouse.FORCE_CAPTURE -p com.raphael.handmouse
Start-Sleep -Seconds 8
& $adb logcat -d -s GlassesConnection:I EyeCaptureService:I | Select-String "câmera|camera|onCameraEnabled|uvc0"
```
Expected: a câmera ativa normalmente (`onCameraEnabled` / `uvc0=1`), sem crash. **Se ativar → `nr_ota_default` é peso morto confirmado.**

- [ ] **Step 3: Restaurar os assets**

Run:
```powershell
Move-Item "$env:TEMP\nr_ota_default_bak" "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse\app\src\main\assets\nr_ota_default"
```
> Restaurar mesmo com resultado positivo — a remoção definitiva só entra na fase de reescrita (mantém o app atual funcional).

- [ ] **Step 4: Documentar e commitar**

Preencher a seção "Peso-morto OTA": as 2 `.so` são obrigatórias (com a evidência readelf/nm), `nr_ota_default` é removível (resultado do teste). Commit:
```bash
git add docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md
git commit -m "Spike: mapa de peso-morto — 2 .so obrigatorias, nr_ota_default removivel"
```

---

### Task 9: Confinar a instrumentação ao debug (release limpo) + fechamento

**Files:**
- Verify: build release
- Modify: `.gitignore` (se necessário, garantir capturas fora do git)

- [ ] **Step 1: Confirmar que capturas não vão pro git**

As capturas moram em `$env:TEMP\xreal-cap\` (fora do repo), então não podem vazar. Confirmar:
```bash
cd "C:/Users/Raphael/Documents/Projetos/xreal hand tracking"
git status --porcelain | grep -iE "cap[AB]?\.txt|\.norm|libnr_glasses|libota-lib|nr_ota_default" && echo "VAZAMENTO — NAO COMMITAR" || echo "ok limpo"
```
Se aparecer qualquer `.so`/asset proprietário ou captura, **não** commitar; se necessário, adicionar ao `.gitignore`.

- [ ] **Step 2: Build release e provar que a instrumentação NÃO está nele**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleRelease
& "C:\Program Files\Android\Android Studio\jbr\bin\jar.exe" tf app\build\outputs\apk\release\app-release-unsigned.apk | Select-String "wrap.sh|libioctltap.so"
```
Expected: **nenhuma** linha (o release não tem `wrap.sh` nem `libioctltap.so`). Os receivers debug já estão sob `BuildConfig.DEBUG`.

> Se `assembleRelease` falhar por assinatura/config não relacionada ao spike, basta inspecionar o APK intermediário ou o diretório `app/build/intermediates/…/release/` e confirmar a ausência dos dois arquivos.

- [ ] **Step 3: Verificação final do critério de sucesso do spike**

Conferir no doc `2026-07-23-glasses-usb-protocol.md` que existem: Fase 0 (literatura), Fase 1 (sequência), Fase 2 (veredito GO/NO-GO com evidência), Peso-morto OTA. Marcar a checklist da seção 9 do spec.

- [ ] **Step 4: Commit final**

```bash
git add .gitignore
git commit -m "Spike: garante release limpo (sem wrap.sh/interpositor) e capturas fora do git"
```

---

## Self-Review (cobertura do spec)

- **Escopo = spike, não reescrita:** ✅ nenhuma task reimplementa protocolo; Task 7 só produz o veredito/mapeamento.
- **Captura Abordagem A (ioctl in-process, sem root/hardware):** ✅ Tasks 2–5.
- **Fase 0 literatura:** ✅ Task 1. **Fase 1 captura:** ✅ Task 6. **Fase 2 veredito:** ✅ Task 7.
- **Estático-vs-dinâmico como discriminador GO/NO-GO:** ✅ Task 7 Step 1–2; ramo dinâmico → Ghidra só-referência.
- **Peso-morto OTA (corrigido: 2 .so obrigatórias, só nr_ota_default removível):** ✅ Task 8.
- **Linha de copyright:** ✅ Global Constraints + Task 7 (Ghidra só referência).
- **Debug-only / release limpo:** ✅ Task 2 (exclude), Task 3 (src/debug), Task 4–5 (BuildConfig.DEBUG), Task 9 (prova).
- **Validação do decoder antes de confiar nas capturas:** ✅ Task 4.
- **Reprodutibilidade (≥2 sessões + diff):** ✅ Tasks 6–7.
- **Risco wrap.sh/SELinux:** ✅ fallback documentado em Task 3 Step 3.
- **Nunca commitar .so/assets/capturas:** ✅ Global Constraints + Task 9.
