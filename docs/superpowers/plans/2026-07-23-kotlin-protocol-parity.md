# Camada de protocolo XREAL em Kotlin — paridade + validação diferencial — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar em Kotlin puro as 4 operações de ativação da câmera (`WaitPilotReady`, `GetUsbConfigAll`, `SetUsbConfigAll`, `GetCameraStatus`), emitindo **exatamente os mesmos bytes** que `libnr_glasses_api.so` emite, e **provar** essa equivalência por diff contra a captura nativa.

**Architecture:** Três camadas puras e testáveis na JVM (codec de quadro → codec de config → sequência de comandos), uma camada fina de transporte USB (`bulkTransfer` nos endpoints `0x01`/`0x81`), e uma flag de debug que alterna entre o caminho nativo e o Kotlin. As `.so` **permanecem no lugar** como fallback — removê-las é uma fase seguinte, só depois da equivalência provada em hardware.

**Tech Stack:** Kotlin, `java.util.zip.CRC32`, Android USB Host API (`UsbDeviceConnection.bulkTransfer`), JUnit 4. arm64-v8a.

## Global Constraints

- **Paridade é o critério, não "funciona":** a Fase 1 deve emitir bytes idênticos aos da `.so`. Qualquer otimização (poll no lugar do `sleep`, menos comandos, menos re-enumeração) é **fora de escopo** deste plano — vira fase seguinte, depois do diff verde.
- **As `.so` NÃO são removidas neste plano.** `libota-lib.so`, `libnr_glasses_api.so`, `assets/nr_ota_default/` e o shim `ai/nreal/glasses/control/*.java` ficam intactos e funcionais.
- **Formato do quadro** (vale nas DUAS direções; fonte: `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md`):
  ```
  [0]      0xfd  magic
  [1..4]   CRC-32 (java.util.zip.CRC32) de bytes[5 : 5+len], LITTLE-ENDIAN
  [5]      len = tamanho_total - 5
  [6..14]  zeros nos comandos que enviamos (nas respostas variam — campo NÃO decodificado)
  [15]     código do comando (a resposta ecoa o mesmo código)
  [16..21] zeros
  [22..]   payload (opcional)
  ```
- **Transporte:** BULK. `ep 0x01` = OUT (comandos), `ep 0x81` = IN (respostas, buffer de 1024 B). O endpoint `0x89` é vídeo UVC — **não tocar**.
- **Build:** `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` antes de `.\gradlew.bat`. Diretório: `xreal-hand-mouse/`. adb: `C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe`, aparelho `<DEVICE-IP>:5555`.
- **`adb install -r` derruba o serviço de acessibilidade e revoga o grant USB** deste aparelho. Agrupe instalações; avise no relatório o que o usuário precisa restaurar.
- **Sessão paralela ativa no repo:** `git add` apenas nos caminhos exatos da task. **NUNCA** `git add -A`, `git add .` ou `git commit -a`.
- **Linha de copyright:** a única fonte legítima é a captura black-box deste projeto. Não usar a tabela de opcodes HID do repo do Aloim (falsificada pela captura e marcada como proibida no doc do protocolo).

---

### Task 1: Codec de quadro (`GlassesFrame`) — puro, TDD

O coração. Constrói e parseia o quadro. Vetores golden vêm das capturas reais.

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesFrame.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesFrameTest.kt`

**Interfaces:**
- Produces:
  - `object GlassesFrame`
  - `fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray`
  - `data class Parsed(val cmd: Int, val payload: ByteArray)`
  - `fun parse(buf: ByteArray, length: Int): Parsed` — lança `IllegalArgumentException` em magic/CRC/tamanho inválidos.

- [ ] **Step 1: Escrever o teste que falha**

Create `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesFrameTest.kt`:

```kotlin
package com.raphael.handmouse.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vetores golden capturados da libnr_glasses_api.so real (captures/run5_fulldump.txt). */
class GlassesFrameTest {

    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // --- comandos reais emitidos pela .so nativa ---
    private val CMD_26 = hex("fd 25 af 75 f5 11 00 00 00 00 00 00 00 00 00 26 00 00 00 00 00 00")
    private val CMD_D2 = hex("fd ef b1 44 65 11 00 00 00 00 00 00 00 00 00 d2 00 00 00 00 00 00")
    private val CMD_D4 = hex("fd d5 84 94 06 11 00 00 00 00 00 00 00 00 00 d4 00 00 00 00 00 00")
    private val CMD_D5 = hex("fd 61 8f e3 a0 11 00 00 00 00 00 00 00 00 00 d5 00 00 00 00 00 00")
    private val CMD_D3 = hex("fd c6 e4 89 b8 15 00 00 00 00 00 00 00 00 00 d3 00 00 00 00 00 00 45 10 01 00")
    private val CMD_D6 = hex(
        "fd ee 87 da 7c 28 00 00 00 00 00 00 00 00 00 d6 00 00 00 00 00 00 " +
            "72 6f 2e 62 73 70 2e 61 70 70 5f 70 72 65 70 61 72 65 5f 64 6f 6e 65"
    )

    @Test
    fun `build reproduz byte a byte os comandos sem payload`() {
        assertArrayEquals(CMD_26, GlassesFrame.build(0x26))
        assertArrayEquals(CMD_D2, GlassesFrame.build(0xd2))
        assertArrayEquals(CMD_D4, GlassesFrame.build(0xd4))
        assertArrayEquals(CMD_D5, GlassesFrame.build(0xd5))
    }

    @Test
    fun `build reproduz o comando com payload binario`() {
        assertArrayEquals(CMD_D3, GlassesFrame.build(0xd3, hex("45 10 01 00")))
    }

    @Test
    fun `build reproduz o comando com payload ASCII`() {
        val prop = "ro.bsp.app_prepare_done".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(CMD_D6, GlassesFrame.build(0xd6, prop))
    }

    @Test
    fun `campo de comprimento e total menos 5`() {
        assertEquals(0x11, GlassesFrame.build(0x26)[5].toInt() and 0xff)   // 22 - 5
        assertEquals(0x15, GlassesFrame.build(0xd3, hex("45 10 01 00"))[5].toInt() and 0xff) // 26 - 5
    }

    @Test
    fun `parse aceita uma resposta real e extrai payload`() {
        // resposta real ao 0xd2, em buffer de 1024 B como vem do bulkTransfer
        val resp = ByteArray(1024)
        hex("fd ed 94 a7 06 16 00 00 00 00 00 4a fb ed ff d2 00 00 00 00 00 00 00 55 9a 00 00")
            .copyInto(resp)
        val p = GlassesFrame.parse(resp, 1024)
        assertEquals(0xd2, p.cmd)
        assertArrayEquals(hex("00 55 9a 00 00"), p.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejeita magic invalido`() {
        val bad = ByteArray(1024).also { it[0] = 0x00 }
        GlassesFrame.parse(bad, 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejeita CRC corrompido`() {
        val resp = ByteArray(1024)
        hex("fd ed 94 a7 06 16 00 00 00 00 00 4a fb ed ff d2 00 00 00 00 00 00 00 55 9a 00 00")
            .copyInto(resp)
        resp[23] = 0x00 // corrompe o payload sem corrigir o CRC
        GlassesFrame.parse(resp, 1024)
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha (RED)**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.glasses.GlassesFrameTest"
```
Expected: FALHA de compilação — `Unresolved reference 'GlassesFrame'`.

- [ ] **Step 3: Implementar o mínimo**

Create `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesFrame.kt`:

```kotlin
package com.raphael.handmouse.glasses

import java.util.zip.CRC32

/**
 * Codec do quadro de controle dos óculos XREAL.
 *
 * Formato (vale nas duas direções — ver docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md):
 *   [0]      0xfd magic
 *   [1..4]   CRC-32 de bytes[5 : 5+len], little-endian
 *   [5]      len = total - 5
 *   [6..14]  zeros nos comandos (nas respostas variam; campo não decodificado)
 *   [15]     código do comando (a resposta ecoa o mesmo)
 *   [16..21] zeros
 *   [22..]   payload
 *
 * Origem: captura black-box do nosso próprio processo (spike 2026-07-23). Nada aqui vem de
 * decompilar a .so proprietária.
 */
object GlassesFrame {

    const val MAGIC = 0xfd
    const val HEADER_SIZE = 22
    private const val LEN_OFFSET = 5
    private const val CMD_OFFSET = 15

    data class Parsed(val cmd: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Parsed && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
    }

    private fun crc32Of(buf: ByteArray, offset: Int, length: Int): Long =
        CRC32().apply { update(buf, offset, length) }.value

    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = HEADER_SIZE + payload.size
        val buf = ByteArray(total)
        buf[0] = MAGIC.toByte()
        val len = total - 5
        buf[LEN_OFFSET] = len.toByte()
        buf[CMD_OFFSET] = cmd.toByte()
        payload.copyInto(buf, HEADER_SIZE)
        val crc = crc32Of(buf, LEN_OFFSET, len)
        buf[1] = (crc and 0xff).toByte()
        buf[2] = ((crc shr 8) and 0xff).toByte()
        buf[3] = ((crc shr 16) and 0xff).toByte()
        buf[4] = ((crc shr 24) and 0xff).toByte()
        return buf
    }

    fun parse(buf: ByteArray, length: Int): Parsed {
        require(length >= HEADER_SIZE) { "resposta curta demais: $length" }
        require(buf[0].toInt() and 0xff == MAGIC) {
            "magic inválido: 0x%02x".format(buf[0].toInt() and 0xff)
        }
        val len = buf[LEN_OFFSET].toInt() and 0xff
        val total = len + 5
        require(total in HEADER_SIZE..length) { "campo de comprimento inconsistente: len=$len" }
        val stored = ((buf[1].toInt() and 0xff).toLong()) or
            ((buf[2].toInt() and 0xff).toLong() shl 8) or
            ((buf[3].toInt() and 0xff).toLong() shl 16) or
            ((buf[4].toInt() and 0xff).toLong() shl 24)
        val actual = crc32Of(buf, LEN_OFFSET, len)
        require(stored == actual) {
            "CRC não confere: esperado 0x%08x, calculado 0x%08x".format(stored, actual)
        }
        return Parsed(buf[CMD_OFFSET].toInt() and 0xff, buf.copyOfRange(HEADER_SIZE, total))
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa (GREEN)**

Run o mesmo comando do Step 2.
Expected: `BUILD SUCCESSFUL`, 7/7 testes passando.

- [ ] **Step 5: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesFrame.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesFrameTest.kt
git commit -m "Protocolo Kotlin: codec de quadro validado contra vetores golden da captura (TDD)"
```

---

### Task 2: Codec da config USB (`UsbConfigCodec`) — puro, TDD

Decodifica os campos de 2 bits da resposta do `GetUsbConfigAll`.

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/UsbConfigCodec.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/UsbConfigCodecTest.kt`

**Interfaces:**
- Consumes: nada de tasks anteriores.
- Produces:
  - `data class UsbConfigState(val ncm: Int, val ecm: Int, val hidCtrl: Int, val uvc0: Int, val uvc1: Int)`
  - `object UsbConfigCodec { fun decode(payload: ByteArray): UsbConfigState }`
  - `const val SET_UVC0_PAYLOAD: ByteArray` — a constante `45 10 01 00` capturada.

- [ ] **Step 1: Escrever o teste que falha**

Create `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/UsbConfigCodecTest.kt`:

```kotlin
package com.raphael.handmouse.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbConfigCodecTest {

    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Payload real da resposta ao 0xd2, correlacionado NA MESMA execução com o log do app
     * ("Atual: ncm=1 ecm=1 hid=1 uvc0=1 uvc1=2" — 2026-07-23 21:02:01).
     */
    @Test
    fun `decodifica o payload real capturado`() {
        val st = UsbConfigCodec.decode(hex("00 55 9a 00 00"))
        assertEquals(1, st.ncm)
        assertEquals(1, st.ecm)
        assertEquals(1, st.hidCtrl)
        assertEquals(1, st.uvc0)
        assertEquals(2, st.uvc1)
    }

    @Test
    fun `payload curto demais vira estado zerado em vez de crashar`() {
        val st = UsbConfigCodec.decode(hex("00"))
        assertEquals(0, st.ncm)
        assertEquals(0, st.uvc1)
    }

    @Test
    fun `a constante de ativacao da uvc0 e a capturada`() {
        assertArrayEquals(hex("45 10 01 00"), UsbConfigCodec.SET_UVC0_PAYLOAD)
    }
}
```

- [ ] **Step 2: Rodar e confirmar RED**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.glasses.UsbConfigCodecTest"
```
Expected: `Unresolved reference 'UsbConfigCodec'`.

- [ ] **Step 3: Implementar**

Create `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/UsbConfigCodec.kt`:

```kotlin
package com.raphael.handmouse.glasses

/** Estado das interfaces USB compostas dos óculos. */
data class UsbConfigState(
    val ncm: Int,
    val ecm: Int,
    val hidCtrl: Int,
    val uvc0: Int,
    val uvc1: Int,
)

/**
 * Codec da config USB.
 *
 * Os campos vêm empacotados em **2 bits cada**, não um byte por campo. Evidência (captura
 * 2026-07-23, correlacionada na mesma execução com o log do app):
 *   payload = 00 55 9a 00 00
 *   0x55 = 01|01|01|01 -> ncm=1 ecm=1 hidCtrl=1 uvc0=1
 *   0x9a = ......|10   -> uvc1=2
 *
 * LIMITE CONHECIDO: temos UMA amostra de configuração (o app sempre monta a mesma), então a
 * ordem exata dos campos de 2 bits casa com essa amostra mas não está provada contra
 * configurações alternativas. Para generalizar, capturar com configs variadas e diffar.
 * Para o objetivo atual (reproduzir esta configuração) isso basta.
 */
object UsbConfigCodec {

    /** Payload que o SetUsbConfigAll envia para ligar a uvc0 — constante capturada da .so. */
    val SET_UVC0_PAYLOAD: ByteArray = byteArrayOf(0x45, 0x10, 0x01, 0x00)

    private fun field(b: Int, index: Int): Int = (b shr (index * 2)) and 0b11

    fun decode(payload: ByteArray): UsbConfigState {
        val b1 = if (payload.size > 1) payload[1].toInt() and 0xff else 0
        val b2 = if (payload.size > 2) payload[2].toInt() and 0xff else 0
        return UsbConfigState(
            ncm = field(b1, 0),
            ecm = field(b1, 1),
            hidCtrl = field(b1, 2),
            uvc0 = field(b1, 3),
            uvc1 = field(b2, 0),
        )
    }
}
```

- [ ] **Step 4: Rodar e confirmar GREEN**

Run o comando do Step 2. Expected: 3/3 passando.

> Se o teste do payload real falhar, a ordem dos campos de 2 bits está invertida (MSB-first em vez de LSB-first). Troque `field(b, index)` para `(b shr ((3 - index) * 2)) and 0b11` e rode de novo. **Registre no relatório qual das duas ordens passou** — é justamente o que a amostra única não determina a priori.

- [ ] **Step 5: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/UsbConfigCodec.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/UsbConfigCodecTest.kt
git commit -m "Protocolo Kotlin: codec da config USB (campos de 2 bits) validado na captura (TDD)"
```

---

### Task 3: Sequência de comandos (`GlassesCommands`) — puro, TDD

A sequência exata das 20 mensagens que a `.so` emite, incluindo os pares de preâmbulo. **Paridade: não otimizar, não remover preâmbulos.**

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesCommands.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesCommandsTest.kt`

**Interfaces:**
- Consumes: `GlassesFrame.build` (Task 1), `UsbConfigCodec.SET_UVC0_PAYLOAD` (Task 2).
- Produces:
  - `object GlassesCommands` com `const val CMD_PREAMBLE_A = 0x26`, `CMD_PREAMBLE_B = 0xd4`, `CMD_GET_USB_CONFIG = 0xd2`, `CMD_SET_USB_CONFIG = 0xd3`, `CMD_GET_CAMERA_STATUS = 0xd5`, `CMD_GET_PROPERTY = 0xd6`
  - `const val PROP_APP_PREPARE_DONE = "ro.bsp.app_prepare_done"`
  - `fun enableCameraSequence(): List<ByteArray>` — as 20 mensagens, na ordem capturada.

- [ ] **Step 1: Escrever o teste que falha**

Create `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesCommandsTest.kt`:

```kotlin
package com.raphael.handmouse.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesCommandsTest {

    /** Ordem capturada da .so nativa (captures/run1.txt, 20 comandos OUT). */
    private val EXPECTED_ORDER = listOf(
        0x26, 0xd4,
        0x26, 0xd4, 0xd6,
        0x26, 0xd4,
        0x26, 0xd4, 0xd2,
        0x26, 0xd4,
        0x26, 0xd4, 0xd3,
        0x26, 0xd4,
        0x26, 0xd4, 0xd5,
    )

    @Test
    fun `a sequencia tem exatamente 20 mensagens`() {
        assertEquals(20, GlassesCommands.enableCameraSequence().size)
    }

    @Test
    fun `a ordem dos codigos de comando bate com a captura`() {
        val actual = GlassesCommands.enableCameraSequence().map { it[15].toInt() and 0xff }
        assertEquals(EXPECTED_ORDER, actual)
    }

    @Test
    fun `o comando de propriedade carrega a string correta`() {
        val msg = GlassesCommands.enableCameraSequence()[4]
        assertEquals(0xd6, msg[15].toInt() and 0xff)
        val payload = msg.copyOfRange(22, msg.size).toString(Charsets.US_ASCII)
        assertEquals("ro.bsp.app_prepare_done", payload)
    }

    @Test
    fun `o comando de set config carrega a constante capturada`() {
        val msg = GlassesCommands.enableCameraSequence()[14]
        assertEquals(0xd3, msg[15].toInt() and 0xff)
        assertEquals(26, msg.size)
    }
}
```

- [ ] **Step 2: Rodar e confirmar RED**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.glasses.GlassesCommandsTest"
```
Expected: `Unresolved reference 'GlassesCommands'`.

- [ ] **Step 3: Implementar**

Create `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesCommands.kt`:

```kotlin
package com.raphael.handmouse.glasses

/**
 * Sequência de comandos da ativação da câmera Eye, na ordem exata capturada da .so nativa.
 *
 * Cada operação lógica é precedida por um par de preâmbulo (0x26, 0xd4) repetido 2x. O propósito
 * do preâmbulo NÃO é conhecido — pode ser handshake obrigatório ou hábito da lib deles.
 * Como este plano mira PARIDADE, o preâmbulo é reproduzido fielmente. Investigar se é removível
 * é trabalho da fase de otimização, não desta.
 */
object GlassesCommands {

    const val CMD_PREAMBLE_A = 0x26
    const val CMD_PREAMBLE_B = 0xd4
    const val CMD_GET_USB_CONFIG = 0xd2
    const val CMD_SET_USB_CONFIG = 0xd3
    const val CMD_GET_CAMERA_STATUS = 0xd5
    const val CMD_GET_PROPERTY = 0xd6

    const val PROP_APP_PREPARE_DONE = "ro.bsp.app_prepare_done"

    /** Resposta do WaitPilotReady contém este ASCII quando o pilot está pronto. */
    const val PILOT_READY_MARKER = "true"

    private fun preamble(): List<ByteArray> = listOf(
        GlassesFrame.build(CMD_PREAMBLE_A),
        GlassesFrame.build(CMD_PREAMBLE_B),
    )

    fun enableCameraSequence(): List<ByteArray> = buildList {
        addAll(preamble())
        addAll(preamble())
        add(GlassesFrame.build(CMD_GET_PROPERTY, PROP_APP_PREPARE_DONE.toByteArray(Charsets.US_ASCII)))
        addAll(preamble())
        addAll(preamble())
        add(GlassesFrame.build(CMD_GET_USB_CONFIG))
        addAll(preamble())
        addAll(preamble())
        add(GlassesFrame.build(CMD_SET_USB_CONFIG, UsbConfigCodec.SET_UVC0_PAYLOAD))
        addAll(preamble())
        addAll(preamble())
        add(GlassesFrame.build(CMD_GET_CAMERA_STATUS))
    }
}
```

- [ ] **Step 4: Rodar e confirmar GREEN**

Run o comando do Step 2. Expected: 4/4 passando.

- [ ] **Step 5: Rodar a suíte inteira (garantir zero regressão)**

Run:
```powershell
.\gradlew.bat :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, todos os testes verdes (a suíte pré-existente tem ~150 testes).

- [ ] **Step 6: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesCommands.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesCommandsTest.kt
git commit -m "Protocolo Kotlin: sequencia de 20 comandos em paridade com a captura (TDD)"
```

---

### Task 4: Transporte USB (`GlassesTransport`)

Camada fina sobre a USB Host API: acha a interface de controle, reivindica, e faz o par OUT/IN.

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesTransport.kt`
- Test: `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesTransportTest.kt`

**Interfaces:**
- Consumes: `GlassesFrame.parse` (Task 1).
- Produces:
  - `class GlassesTransport(private val conn: UsbDeviceConnection, private val iface: UsbInterface, private val epOut: UsbEndpoint, private val epIn: UsbEndpoint)`
  - `fun request(message: ByteArray, timeoutMs: Int = 1000): GlassesFrame.Parsed`
  - `companion object { fun find(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? }` — localiza a interface cujos endpoints são `0x01` OUT e `0x81` IN.

- [ ] **Step 1: Escrever o teste da lógica pura de seleção de endpoint**

O `bulkTransfer` exige hardware, mas a **regra de seleção** é pura e é onde mora o risco de pegar o endpoint de vídeo por engano. Create `xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesTransportTest.kt`:

```kotlin
package com.raphael.handmouse.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesTransportTest {

    @Test
    fun `endpoint de controle OUT e o 0x01`() {
        assertTrue(GlassesTransport.isControlOut(0x01))
        assertFalse(GlassesTransport.isControlOut(0x81))
        assertFalse(GlassesTransport.isControlOut(0x89))
    }

    @Test
    fun `endpoint de controle IN e o 0x81`() {
        assertTrue(GlassesTransport.isControlIn(0x81))
        assertFalse(GlassesTransport.isControlIn(0x01))
    }

    @Test
    fun `o endpoint de video 0x89 nunca e confundido com controle`() {
        // 0x89 é o stream MJPEG — reivindicá-lo derrubaria a câmera.
        assertFalse(GlassesTransport.isControlIn(0x89))
        assertFalse(GlassesTransport.isControlOut(0x89))
    }
}
```

- [ ] **Step 2: Rodar e confirmar RED**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.raphael.handmouse.glasses.GlassesTransportTest"
```
Expected: `Unresolved reference 'GlassesTransport'`.

- [ ] **Step 3: Implementar**

Create `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesTransport.kt`:

```kotlin
package com.raphael.handmouse.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Transporte BULK do canal de controle dos óculos.
 *
 * ATENÇÃO: o endpoint 0x89 é o stream de vídeo MJPEG. Reivindicar a interface dele derrubaria a
 * câmera — por isso a seleção é por número de endpoint exato, não "o primeiro bulk que achar".
 */
class GlassesTransport(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
) {
    companion object {
        private const val TAG = "GlassesTransport"
        const val EP_CONTROL_OUT = 0x01
        const val EP_CONTROL_IN = 0x81
        const val RESPONSE_BUFFER_SIZE = 1024

        fun isControlOut(address: Int) = address == EP_CONTROL_OUT
        fun isControlIn(address: Int) = address == EP_CONTROL_IN

        /** Acha a interface cujos endpoints bulk são exatamente 0x01 (OUT) e 0x81 (IN). */
        fun find(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var out: UsbEndpoint? = null
                var inp: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (isControlOut(ep.address)) out = ep
                    if (isControlIn(ep.address)) inp = ep
                }
                if (out != null && inp != null) return Triple(iface, out, inp)
            }
            return null
        }
    }

    /** Envia uma mensagem e devolve a resposta parseada. Lança IOException-like em falha. */
    fun request(message: ByteArray, timeoutMs: Int = 1000): GlassesFrame.Parsed {
        val sent = conn.bulkTransfer(epOut, message, message.size, timeoutMs)
        require(sent == message.size) { "envio incompleto: $sent de ${message.size}" }
        val buf = ByteArray(RESPONSE_BUFFER_SIZE)
        val got = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        require(got > 0) { "sem resposta (bulkTransfer devolveu $got)" }
        return GlassesFrame.parse(buf, got)
    }

    fun claim(): Boolean = conn.claimInterface(iface, true).also {
        if (!it) Log.w(TAG, "claimInterface falhou na interface ${iface.id}")
    }

    fun release() {
        runCatching { conn.releaseInterface(iface) }
    }
}
```

- [ ] **Step 4: Rodar e confirmar GREEN**

Run o comando do Step 2. Expected: 3/3 passando.

- [ ] **Step 5: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/GlassesTransport.kt xreal-hand-mouse/app/src/test/kotlin/com/raphael/handmouse/glasses/GlassesTransportTest.kt
git commit -m "Protocolo Kotlin: transporte BULK com selecao exata de endpoint (TDD)"
```

---

### Task 5: As 4 operações (`KotlinGlassesProtocol`) + flag de seleção

Junta tudo e permite escolher, em debug, entre o caminho nativo e o Kotlin.

**Files:**
- Create: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/KotlinGlassesProtocol.kt`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt`

**Interfaces:**
- Consumes: `GlassesCommands` (Task 3), `GlassesTransport` (Task 4), `UsbConfigCodec` (Task 2).
- Produces:
  - `class KotlinGlassesProtocol(private val transport: GlassesTransport)`
  - `fun enableEyeCamera(log: (String) -> Unit): Boolean` — roda a sequência de 20 mensagens em paridade, incluindo o `sleep` de 3 s após o `SetUsbConfigAll`.
  - Broadcast debug `com.raphael.handmouse.FORCE_CAPTURE_KOTLIN`.

- [ ] **Step 1: Implementar a orquestração**

Create `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/KotlinGlassesProtocol.kt`:

```kotlin
package com.raphael.handmouse.glasses

/**
 * As 4 operações de ativação da câmera, em Kotlin puro — SEM a .so proprietária.
 *
 * FASE DE PARIDADE: reproduz fielmente o que a .so faz, incluindo o sleep cego de 3 s após o
 * SetUsbConfigAll. NÃO otimizar aqui — trocar o sleep por poll é a fase seguinte, e fazer isso
 * agora invalidaria o diff byte-a-byte que valida esta implementação.
 */
class KotlinGlassesProtocol(private val transport: GlassesTransport) {

    companion object {
        /** Mesmo valor que GlassesConnection usa após o SetUsbConfigAll (paridade). */
        const val REENUMERATION_WAIT_MS = 3000L
    }

    fun enableEyeCamera(log: (String) -> Unit): Boolean {
        val seq = GlassesCommands.enableCameraSequence()
        var lastConfig: UsbConfigState? = null
        seq.forEachIndexed { index, msg ->
            val cmd = msg[15].toInt() and 0xff
            val resp = runCatching { transport.request(msg) }.getOrElse { e ->
                log("Falha no comando #${index + 1} (0x%02x): ${e.message}".format(cmd))
                return false
            }
            when (cmd) {
                GlassesCommands.CMD_GET_PROPERTY -> {
                    val txt = resp.payload.toString(Charsets.US_ASCII)
                    log("Pilot ready: ${txt.contains(GlassesCommands.PILOT_READY_MARKER)}")
                }
                GlassesCommands.CMD_GET_USB_CONFIG -> {
                    lastConfig = UsbConfigCodec.decode(resp.payload)
                    log("Atual: $lastConfig")
                }
                GlassesCommands.CMD_SET_USB_CONFIG -> {
                    val code = resp.payload.firstOrNull()?.toInt() ?: -1
                    log("Resultado: $code")
                    Thread.sleep(REENUMERATION_WAIT_MS)   // paridade com a .so
                }
                GlassesCommands.CMD_GET_CAMERA_STATUS ->
                    log("Status da câmera: ${resp.payload.joinToString(" ") { "%02x".format(it) }}")
            }
        }
        return true
    }
}
```

- [ ] **Step 2: Adicionar o gatilho debug no serviço**

Modify `EyeCaptureService.kt`. Junto dos outros receivers debug (o bloco iniciado pelo comentário de seção "Debug receivers (spike-only)"), adicionar:

```kotlin
    private val forceCaptureKotlinReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            val since = now - lastForceCaptureAcceptedMs
            if (since < FORCE_CAPTURE_DEBOUNCE_MS) {
                Log.w(TAG, "FORCE_CAPTURE_KOTLIN ignorado: aguarde ${FORCE_CAPTURE_DEBOUNCE_MS - since}ms")
                return
            }
            lastForceCaptureAcceptedMs = now
            Log.i(TAG, "FORCE_CAPTURE_KOTLIN: rodando a sequencia em Kotlin puro")
            controlHandler.post {
                val usb = getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList.values.firstOrNull {
                    it.vendorId == GlassesConnection.XREAL_VID
                } ?: run { Log.w(TAG, "KOTLIN: óculos não encontrados"); return@post }
                if (!usb.hasPermission(dev)) { Log.w(TAG, "KOTLIN: sem permissão USB"); return@post }
                val found = GlassesTransport.find(dev)
                    ?: run { Log.w(TAG, "KOTLIN: interface de controle (ep 0x01/0x81) não encontrada"); return@post }
                val conn = usb.openDevice(dev) ?: run { Log.w(TAG, "KOTLIN: openDevice falhou"); return@post }
                val transport = GlassesTransport(conn, found.first, found.second, found.third)
                try {
                    if (!transport.claim()) { Log.w(TAG, "KOTLIN: claimInterface falhou"); return@post }
                    val ok = KotlinGlassesProtocol(transport).enableEyeCamera { Log.i(TAG, "KOTLIN: $it") }
                    Log.i(TAG, "KOTLIN: sequencia concluida ok=$ok")
                } finally {
                    transport.release()
                    conn.close()
                }
            }
        }
    }
```

Registrar/desregistrar no mesmo bloco `if (BuildConfig.DEBUG)` do `onCreate`/`onDestroy`, com a ação `com.raphael.handmouse.FORCE_CAPTURE_KOTLIN` e `ContextCompat.RECEIVER_EXPORTED`. Adicionar os imports de `com.raphael.handmouse.glasses.GlassesTransport` e `com.raphael.handmouse.glasses.KotlinGlassesProtocol`.

- [ ] **Step 3: Rodar a suíte inteira**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, todos verdes.

- [ ] **Step 4: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/glasses/KotlinGlassesProtocol.kt xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/service/EyeCaptureService.kt
git commit -m "Protocolo Kotlin: 4 operacoes em paridade + gatilho debug FORCE_CAPTURE_KOTLIN"
```

---

### Task 6: Validação diferencial contra o baseline nativo

O gate que justifica a estratégia de paridade. **Exige hardware e o usuário presente.**

**Files:**
- Modify: `docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md` (nova seção "Validação da paridade Kotlin")

- [ ] **Step 1: Build, instalar, restaurar estado**

Run:
```powershell
cd "C:\Users\Raphael\Documents\Projetos\xreal hand tracking\xreal-hand-mouse"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```
Depois: pedir ao usuário para reabilitar o serviço de acessibilidade, replugar os óculos (aceitando o diálogo de permissão USB) e abrir o app com "Iniciar Captura". Confirmar com:
```powershell
& $adb logcat -d -s EyeCaptureService:D | Select-String "STREAMING"
```

- [ ] **Step 2: Capturar o baseline NATIVO**

```powershell
$adb="C:\Users\Raphael\AppData\Local\Android\Sdk\platform-tools\adb.exe"
mkdir "$env:TEMP\xreal-parity" -Force | Out-Null
& $adb logcat -G 32M; & $adb logcat -c
& $adb shell am broadcast -a com.raphael.handmouse.FORCE_CAPTURE -p com.raphael.handmouse
Start-Sleep -Seconds 8
& $adb logcat -d -s IOCTLTAP:V > "$env:TEMP\xreal-parity\native.txt"
```

- [ ] **Step 3: Capturar o caminho KOTLIN**

```powershell
Start-Sleep -Seconds 11   # respeita o debounce
& $adb logcat -c
& $adb shell am broadcast -a com.raphael.handmouse.FORCE_CAPTURE_KOTLIN -p com.raphael.handmouse
Start-Sleep -Seconds 8
& $adb logcat -d -s IOCTLTAP:V > "$env:TEMP\xreal-parity\kotlin.txt"
```

> O interpositor `LD_PRELOAD` também intercepta o nosso `bulkTransfer` (ele hooka `ioctl` no processo inteiro, e a USB Host API do Android usa `USBDEVFS_BULK` por baixo). É exatamente isso que torna a comparação possível.

- [ ] **Step 4: Diffar as duas capturas**

```bash
cd "$TEMP/xreal-parity"   # PowerShell: cd "$env:TEMP\xreal-parity"
for f in native kotlin; do
  grep "bulk-OUT" $f.txt | grep -v "ep=0x89" | sed -E 's/^.*bulk-OUT //' > $f.norm
done
echo "native: $(wc -l < native.norm) comandos | kotlin: $(wc -l < kotlin.norm)"
diff native.norm kotlin.norm && echo ">>> PARIDADE PROVADA <<<" || echo "DIVERGE (ver acima)"
```
Expected: **20 comandos de cada lado, diff vazio.**

> Se divergir, o diff aponta exatamente qual mensagem e qual byte — é essa atribuição precisa que a estratégia de paridade comprou. Corrigir e repetir até zerar.

- [ ] **Step 5: Registrar o resultado**

Adicionar ao doc do protocolo uma seção "Validação da paridade Kotlin" com: contagem de comandos dos dois lados, o resultado do diff, e a data. Se houve divergência corrigida no caminho, registrar qual era — é conhecimento útil para a fase de otimização.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md
git commit -m "Protocolo Kotlin: validacao diferencial contra o baseline nativo"
```

---

## Self-Review (cobertura)

- **Paridade byte-a-byte como critério:** ✅ Tasks 1–3 (vetores golden), Task 6 (diff em hardware).
- **Sem otimização nesta fase:** ✅ preâmbulos reproduzidos (Task 3), `sleep` de 3 s mantido (Task 5), ambos com comentário explicando por quê.
- **`.so` intactas como fallback:** ✅ nenhuma task as remove; o caminho nativo continua sendo o padrão, o Kotlin fica atrás de um broadcast debug.
- **TDD onde é puro:** ✅ Tasks 1–4 têm RED→GREEN com testes JVM; Tasks 5–6 são integração/hardware, verificadas por logcat e diff.
- **Risco de reivindicar o endpoint de vídeo:** ✅ Task 4 seleciona por endereço exato e tem teste explícito de que `0x89` nunca casa.
- **Limite conhecido (amostra única de config):** ✅ documentado no KDoc do `UsbConfigCodec` e no Step 4 da Task 2 (qual ordem de bits passou deve ir pro relatório).
- **Copyright:** ✅ Global Constraints; toda constante vem da captura própria, nenhuma da tabela HID de terceiro.
