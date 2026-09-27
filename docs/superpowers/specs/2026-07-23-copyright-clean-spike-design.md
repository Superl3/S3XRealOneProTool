# Spike de reconhecimento: caminho para um APK 100% livre de copyright

**Data:** 2026-07-23
**Status:** Design aprovado — aguardando revisão do spec antes do plano de implementação
**Tipo:** Spike de de-risking (mede viabilidade; **não** reimplementa)

---

## 1. Objetivo e escopo

### Objetivo final (do usuário)
Um **APK único e autocontido, com ZERO binários proprietários da XREAL** — livre para abrir o
código (MIT) e livre para distribuir o APK sem esbarrar na licença da XREAL.

**Decisão do usuário (firme):** só um APK 100% autocontido conta como "pronto". Não há
fallback de "bring your own binary" — se a câmera depende de um `.so` proprietário, o objetivo
não foi atingido.

### Escopo DESTE spec (apenas o spike)
Responder, com confiança, a **uma** pergunta:

> As 4 chamadas `NRBSP*` que ativam a câmera Eye podem ser reimplementadas em Kotlin puro,
> sobre a USB Host API do Android, sem nenhum `.so` da XREAL?

O spike **captura e analisa; não reimplementa nada.** A reescrita completa é uma fase seguinte,
com spec próprio, e só acontece se o spike der **GO**.

### Fora do escopo do spike (mas na checklist mestre do "100% limpo")
- A reimplementação Kotlin em si (fase seguinte, gated no GO).
- Confirmar a licença do modelo `hand_landmarker.task` (provavelmente Apache-2.0; exige um olhar).
- R8/minify + shrinkResources para o build de release.

---

## 2. Contexto: a superfície de copyright hoje

| Artefato | Licença | Usado pelo caminho da câmera? |
|---|---|---|
| `libota-lib.so` | **Proprietário XREAL** | ✅ Sim — hospeda TODOS os JNI `Java_...XrealGlasses_*` (inclusive as 4 chamadas de câmera); é o que o `loadLibrary("ota-lib")` carrega |
| `libnr_glasses_api.so` | **Proprietário XREAL** | ✅ Sim — implementação C do protocolo; `libota-lib.so` faz `NEEDED`-link nela |
| `assets/nr_ota_default/` | **Proprietário XREAL** (extraído do ControlGlasses) | ❔ Só assets de firmware — provável peso morto (a testar) |
| `ai/nreal/glasses/control/*.java` (shim) | MIT (Aloim) | ✅ Sim — mas já é limpo |
| MediaPipe, libyuv, androidx, Material | Apache/BSD/MIT | ✅ Todas permissivas |
| `hand_landmarker.task` (modelo) | Modelo Google — confirmar | ✅ Sim |

**Fato-chave descoberto** (`XrealGlasses.java`, `getFd()` linha ~254): a camada **Java** abre o
device (`usbManager.openDevice()`) e extrai o **file descriptor bruto** (`getFileDescriptor()`),
entregando-o ao código nativo. A `.so` faz o I/O USB **direto no fd**, via ioctls `USBDEVFS` —
ela **contorna** `UsbDeviceConnection.controlTransfer()`. Isso significa:
- ❌ Não dá para interceptar envelopando o `UsbDeviceConnection` em Java.
- ✅ Mas a nativa chama `ioctl()` da libc **dentro do nosso próprio processo** → um hook de
  `ioctl` in-process captura tudo, **sem root e sem hardware**.

**As 4 chamadas que importam** (`GlassesConnection.enableEyeCamera`):
`NRBSPWaitPilotReady` → `NRBSPGetUsbConfigAll` → `NRBSPSetUsbConfigAll(uvc0=1, ncm=1, ecm=1,
hid_ctrl=1, enable=1)` → `NRBSPGetCameraStatus`. Todo o resto da superfície nativa (os `NROTA*`)
é atualização de firmware, que este app nunca executa.

---

## 3. A distinção que define tudo: estático vs. dinâmico

> **Capturar bytes ≠ entender o protocolo.** Uma captura mostra os pacotes de **uma** sessão.
> - Se a sequência for **estática** (mesmos bytes toda vez) → a gente **repete** os control
>   transfers a partir do Kotlin e acabou → **GO**.
> - Se for **dinâmica** (nonce de sessão, contador, checksum sobre dados variáveis, ou
>   challenge-response) → repetir bytes velhos falha, e é preciso reverter o **algoritmo** que
>   os gera. A captura black-box sozinha não revela isso.

Essa determinação **é** o veredito GO/NO-GO. O trabalho nº 1 do spike é forçar essa pergunta.

---

## 4. Arquitetura — três fases, da mais barata para a mais cara

```
Fase 0  Revisão de literatura     →  talvez o protocolo já seja público (GO de graça)
Fase 1  Harness de captura (App A)→  sequência exata de URBs do SetUsbConfigAll(uvc0=1)
Fase 2  Análise e veredito        →  estático-vs-dinâmico → doc GO / NO-GO
(paralelo) Teste do peso-morto OTA →  provar que assets/nr_ota_default é removível (as 2 .so NÃO são)
```

### Fase 0 — Revisão de literatura
Antes de escrever qualquer código: varrer Monado, `xrealAirLinuxDriver`/`breezy`, as notas do
repo do Aloim, e qualquer RE do USB da XREAL. Se o protocolo de config da câmera do One Pro já
estiver documentado, o spike encolhe para "validar os pacotes conhecidos". Caminho mais barato
possível para uma resposta.

### Fase 1 — Harness de captura (Abordagem A escolhida)
Instrumentar **o nosso próprio app** para logar os pacotes USB exatos que a `.so` real envia.

**Mecanismo:** num build `debuggable`, embarcar um `wrap.sh` que faz `LD_PRELOAD` de um `.so`
interpositor minúsculo nosso, envelopando o `ioctl()` da libc. (Fallback, se `wrap.sh`/SELinux
resistir neste aparelho: hook PLT/GOT via `shadowhook`/`xHook`.) Nos dois casos interceptamos
`ioctl` **in-process — sem root, sem hardware.**

**O que loga:**
- Em `USBDEVFS_SUBMITURB`: parsear `struct usbdevfs_urb` → tipo (control/bulk/int), endpoint,
  buffer. Para control transfers, decodificar o setup packet de 8 bytes (`bmRequestType,
  bRequest, wValue, wIndex, wLength`) + payload.
- Em `USBDEVFS_REAPURB*`: capturar os bytes de **resposta**.
- Dump em hex com timestamp para o logcat **e** para arquivo.

**Gatilho:** acionar o `GlassesConnection.enableEyeCamera(forceReconfigure = true)` existente, a
partir de um estado fresco dos óculos (uvc0=0), para a sequência real inteira rodar:
`WaitPilotReady → GetUsbConfigAll → SetUsbConfigAll → GetCameraStatus`. Capturar de ponta a ponta.

**Limpeza jurídica:** observamos o I/O que **o nosso próprio processo** faz no **nosso próprio
hardware**. Nunca decompilamos o código deles.

### Fase 2 — Análise e veredito (o ponto crítico)
Capturar a sequência **2–3 vezes em sessões/reboots frescos e diffar os bytes:**
- **Bytes idênticos toda vez → estático/repetível → GO.** Dá para repetir os mesmos control
  transfers do Kotlin via `UsbDeviceConnection.controlTransfer()`. Alta confiança de que a
  reescrita funciona.
- **Bytes divergem (nonce/contador/checksum/challenge-response) → dinâmico → escalar.** A
  captura black-box não revela **como** os bytes variáveis são computados. Escalamos para
  **Ghidra sobre a `.so`, só como referência**, para reverter o algoritmo e então reimplementar
  em Kotlin num processo clean-room. Se for cripto de autenticação de verdade → **NO-GO** honesto
  (e, pela decisão do usuário, o projeto pausa ou assume RE pesada, em vez de embarcar um `.so`).

### Tarefa paralela e barata — mapear o que é peso morto de verdade
**Correção pós-inspeção (readelf/nm):** a hipótese original ("libota-lib.so é só OTA, dá pra
deletar") é **FALSA**. Fatos confirmados nas próprias `.so`:
- `libota-lib.so` **hospeda todos os JNI** `Java_ai_nreal_glasses_control_XrealGlasses_*` —
  inclusive `NRBSPSetUsbConfigAll/GetUsbConfigAll/WaitPilotReady/GetCameraStatus`. É o que o
  `static { System.loadLibrary("ota-lib"); }` carrega.
- `libota-lib.so` faz `NEEDED`-link em `libnr_glasses_api.so` (a implementação C real do
  protocolo). Carregar ota-lib puxa a nr_glasses_api automaticamente.
- **Portanto as DUAS `.so` são obrigatórias** para o caminho nativo atual da câmera. Nenhuma é
  removível sem quebrar a ativação.

O único candidato real a remoção **hoje** é `assets/nr_ota_default/` (blobs de firmware, usados
só pelos `NROTA*` que este app nunca chama). A tarefa vira: **provar** que a câmera ativa com
`assets/nr_ota_default/` removido. A eliminação das DUAS `.so` só acontece na reescrita Kotlin
completa (gated no GO) — o alvo real dessa reescrita é replicar o que as funções C de
`libnr_glasses_api.so` fazem, descartando a camada JNI (`libota-lib.so`) inteira.

---

## 5. A linha de copyright (regra inviolável)
- **Permitido:** observar o tráfego USB do nosso processo/hardware (black-box); reimplementar o
  protocolo a partir da **spec observada**. Protocolos/APIs não são copyrightáveis (método
  funcional de operação).
- **Proibido para código de produção:** copiar a estrutura de código da `.so` (obra derivada).
  Ghidra é **só referência de entendimento** no ramo dinâmico; o código que embarcamos é
  clean-room em Kotlin.

---

## 6. Testes e verificação
- **Correção do harness:** sanity-check do decoder contra um control transfer conhecido (ex.:
  `GET_DESCRIPTOR` padrão) antes de confiar nas capturas da XREAL.
- **Reprodutibilidade:** o diff das 2–3 rodadas **é** a evidência central; sem ele, nenhuma
  interpretação.
- **Remoção OTA:** o fluxo de ativação da câmera validado em hardware real após a deleção.

---

## 7. Entregável do spike
`docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md` (a ser criado pela implementação) contendo:
1. A sequência de URBs decodificada das 4 chamadas.
2. A determinação estático-vs-dinâmico, **com a evidência** (os diffs das rodadas).
3. Uma recomendação **GO / NO-GO** com nível de confiança.
4. Se GO: o esboço do mapeamento URB → `controlTransfer()`/`bulkTransfer()` em Kotlin para a fase
   seguinte.

---

## 8. Riscos / incógnitas honestas
- `wrap.sh` LD_PRELOAD pode ser bloqueado por SELinux neste aparelho → fallback para lib de
  PLT-hook (um pouco mais de trabalho).
- O ramo dinâmico tem uma cauda real de NO-GO (cripto de autenticação). O propósito inteiro do
  spike é revelar isso **barato, antes** da reescrita completa.
- Capturar uma "sessão fresca" de forma confiável exige cuidado (os óculos lembram o estado
  uvc0) — `forceReconfigure=true` + o tratamento de re-enumeração já existente cobrem isso.
- O hook de `ioctl` process-wide precisa filtrar o fd correto dos óculos; capturar ioctls demais
  polui o log mas não invalida a análise.

---

## 9. Critérios de sucesso do spike
O spike está **completo** quando:
- [x] A revisão de literatura (Fase 0) foi feita e registrada.
- [ ] O harness captura a sequência real de URBs das 4 chamadas, com decoder validado. (parcial: só a direção OUT — respostas IN truncadas em 64 B, ver "Pendências conhecidas" no doc do protocolo)
- [x] A sequência foi capturada em ≥2 sessões frescas e diffada.
- [x] O doc `glasses-usb-protocol.md` existe com o veredito GO/NO-GO + evidência.
- [x] O teste de peso-morto OTA rodou em hardware (câmera ativa com `assets/nr_ota_default/` removido; as 2 `.so` continuam necessárias e isso está documentado).

O spike **NÃO** entrega a câmera funcionando por Kotlin puro — isso é a fase seguinte.
