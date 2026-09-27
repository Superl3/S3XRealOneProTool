# Remoção das `.so` — Kotlin como caminho de produção — Design

**Norte:** tornar o app vendável e open-sourceável removendo o **binário proprietário** que hoje
bloqueia a redistribuição. Este design promove a camada de protocolo Kotlin (já provada
byte-a-byte em hardware — ver `2026-07-23-glasses-usb-protocol.md`, seção "Validação da paridade
Kotlin") ao **caminho de produção** de ativação da câmera, e então **remove**:

- 2 `.so` proprietárias: `libnr_glasses_api.so`, `libota-lib.so` (SEM licença de redistribuição —
  este é o bloqueio real; removê-las tem valor legal E de engenharia: some um blob caixa-preta).
- 11 arquivos do shim `ai.nreal.glasses.control/` (copiados **verbatim** do repo do Aloim).
- Assets `assets/nr_ota_default/` (peso morto já confirmado em hardware no spike).

**Escopo do copyright (decisão do usuário, 2026-07-23):** o alvo é "sem `.so` proprietária, sem
código de terceiro verbatim, atribuição MIT preservada" — NÃO "100% first-party". Há 5 arquivos do
pipeline de captura **derivados** (adaptados, não verbatim) do repo MIT do Aloim que **permanecem**
neste plano: `UvcCameraHelper.kt`, `HevcDecoder.kt`, `HevcNal.kt`, `FrameAssembler.kt` e o próprio
`GlassesConnection.kt`. MIT permite uso comercial/venda/open-source **com atribuição**. Portanto a
etapa de remoção **preserva a atribuição**: move o conteúdo do `NOTICE.txt` para um `NOTICE` na
raiz do repo (cobrindo os arquivos derivados que ficam) antes de deletar o pacote. Re-derivar o
pipeline UVC/HEVC de forma clean-room é follow-up **opcional** (pureza legal; sem ganho de
perf/estabilidade — na verdade com risco de regressão do tuning atual), fora deste plano.

## Estado atual (o que muda e o que não)

- **Consumidor único do shim nativo:** só `capture/GlassesConnection.kt` importa
  `ai.nreal.glasses.control` (`XrealGlasses`, `UsbConfigList`). Confirmado por grep.
- **`GlassesConnection` é a fachada de ativação** usada pelo `EyeCaptureService`: expõe
  `Listener` (`onConnected`/`onDisconnected`/`onError`/`onLog`/`onCameraEnabled`), `start()`,
  `stop()`, `scanForGlasses()`, `enableEyeCamera(forceReconfigure)`, e as constantes
  `XREAL_VID`/`XREAL_EYE_PID` (referenciadas em vários pontos do serviço).
- **Robustez de produção que vive no `GlassesConnection` nativo e PRECISA ser preservada:**
  espera de pilot-ready com timeout; leitura de config + **pular reconfiguração se `uvc0==1`**;
  `forceReconfigure`; thread própria; callbacks de listener; fluxo de permissão USB. **E robustez
  que a `.so` fazia ESCONDIDA e o Kotlin precisa recriar explicitamente:** o `getFd` nativo
  reabre o device **10× com sleeps de 500ms** (`XrealGlasses.java:254-315`) — ~5s absorvendo
  falhas transientes de open; e as chamadas `NRBSP*` quase nunca lançam (usam códigos de retorno
  e **seguem em frente** até `onCameraEnabled`, `GlassesConnection.kt:208-212`).
- **A máquina de reconexão/watchdog/detach-attach do `EyeCaptureService` NÃO muda** — ela já
  trata re-enumeração no nível do pipeline (detach `EyeCaptureService.kt:525-536` → grace
  `DETACH_NOTIFY_GRACE_MS` → attach `:543-554` → reativa), para o caminho nativo, e continuará.

## Abordagem escolhida: trocar as tripas do `GlassesConnection` no lugar

Mesma interface pública; só o miolo muda: `XrealGlasses.NRBSP*` → `GlassesTransport` + codecs
Kotlin (`GlassesFrame`, `GlassesCommands`, `UsbConfigCodec`). Blast radius mínimo: `EyeCaptureService`
e sua reconexão ficam intocados. Abordagens descartadas: (B) nova classe / aposentar o
`GlassesConnection` — mais churn, risco de regredir a reconexão; (C) engordar o
`KotlinGlassesProtocol` — misturaria o artefato de paridade com produção. O
`KotlinGlassesProtocol.enableCameraSequence()` + o broadcast debug `FORCE_CAPTURE_KOTLIN`
**permanecem** como artefato de revalidação.

## Fluxo de ativação Kotlin (produção)

Porta o control-flow do `GlassesConnection.enableEyeCamera` nativo, usando os primitivos provados.
**NÃO é a sequência dumb de paridade**; ramifica no `GetUsbConfigAll`, igual ao nativo.
**Preâmbulo mantido:** cada operação é precedida pelo par `(0x26,0xd4)` 2× (não otimizamos; função
desconhecida). Cada `<op>()` = `(26,d4)×2` + comando.

**Serialização (I3):** ativações rodam num **executor single-thread dedicado** (não `Thread`
por chamada, como hoje em `GlassesConnection.kt:167,228`), com um **token de geração**: se uma
nova ativação começa, a anterior aborta antes da fase pós-`sleep` e não chama `onCameraEnabled`.
Evita 2 threads disputando a interface HID e interleaving de request/response em 0x01/0x81.

```
enableEyeCamera(forceReconfigure) [executor serializado, gen = ++activationGen]:
  dev = deviceList Values.first { vendorId == XREAL_VID } ?: return onError("sem device")
  conn = openWithRetry(dev)                    # C3: 10× com 500ms se openDevice==null/sem permissão
       ?: return failActivation("openDevice falhou")
  (iface, epOut, epIn) = GlassesTransport.find(dev)
       ?: { conn.close(); return failActivation("interface de controle nao encontrada") }
  transport.claim()                            # forceClaim=true (o shim nativo já faz isso por transfer)
  try:
    if (!waitPilotReady()) onLog("pilot nao pronto no timeout — seguindo")  # C2: POLL, ver abaixo
    cfg = tryDecodeConfig(transport)           # I1/minor: null/exceção ⇒ cfg desconhecida ⇒ NÃO pula
    onLog("Atual (raw=${hex}): $cfg")          # I1: loga o payload cru p/ auditar o decode
    if (!forceReconfigure && cfg?.uvc0 == 1):
        onLog("uvc0 já ativa — pulando reconfiguração")
        skipTaken = true
    else:
        code = requestWithRetry(setUsbConfigMessages(SET_UVC0_PAYLOAD))   # C3
        onLog("Resultado: $code")
        if (code != 0) failActivation("SetUsbConfigAll code=$code")  # I1: Set≠0 é falha, não "segue"
  catch (e): failActivation(e)                 # C3: roteia p/ fallback(1-2) ou onCameraEnabled(3)
  finally:
    transport.release(); conn.close()
  if (gen != activationGen) return             # I3: ativação mais nova assumiu; aborta
  if (!skipTaken) Thread.sleep(REENUMERATION_WAIT_MS = 3000)   # paridade; só após um Set real
  bestEffortGetCameraStatus()                  # I4: conexão NOVA, re-lookup device, try/finally, só loga
  onCameraEnabled()
```

### WaitPilotReady como POLL (C2)

Native `NRBSPWaitPilotReady(10000)` é **poll bloqueante com timeout de 10s**; o primitivo Kotlin
provado é **um** `GetProperty ro.bsp.app_prepare_done` (capturado com os óculos já ligados). Para
cold-boot (óculos recém-ligados) um shot só pode disparar comandos em firmware não-pronto. Logo:
`waitPilotReady()` = loop de `GetProperty` (cada iteração com seu preâmbulo — território
desconhecido, escolhido repetir p/ imitar o padrão nativo) até o payload conter `"true"` ou
`PILOT_READY_TIMEOUT_MS` (10s), com sleep curto (~200ms) entre polls. Retorno booleano informativo
(native ignora o dele; não abortamos por timeout — apenas logamos e seguimos).

### Tratamento de re-enumeração e permissão

- **Cold start** (`uvc0` 0→1): `SetUsbConfigAll` re-enumera (detach+attach), matando a conexão de
  controle. Por isso o `Set` é o último comando na conexão inicial (liberada antes do `sleep`), e
  o `GetCameraStatus` pós-`sleep` é **best-effort** numa conexão NOVA (re-lookup em `deviceList`;
  provável falta de permissão — só loga, não-fatal, igual ao `NRBSPGetCameraStatus` nativo em
  try/catch). O resultado que importa (`uvc0=1`) já saiu no `Set`.
- **Re-request de permissão pós-re-enumeração:** o device re-enumerado é "novo" p/ o SO — a `.so`
  também perde permissão e cai no request "2/2" do `findAndStartCamera`
  (`EyeCaptureService.kt:980-984`); a `.so` **não** se auto-concede (o `IPermission` só *checa*,
  `GlassesConnection.kt:92-101`). Mesmo comportamento; nada de máquina nova.
- **Sem loop de ativação:** cold start → Set → re-enum → detach para o pipeline → attach reinicia →
  2ª ativação toma o branch skip (uvc0 agora 1) → sem 2º Set. O contador de `forceReconfigure` é
  consumido em `EyeCaptureService.kt:923-927`, então um ciclo forçado não se auto-perpetua.
- **Warm reconnect** (`uvc0` já 1): sem `Set`, sem re-enum, sem `sleep`; conexão inicial basta.

## Error handling (C3 — não regredir a robustez do nativo)

A `.so` quase nunca lança e absorve transientes (getFd 10×500ms); o `request()` Kotlin lança em
qualquer transfer incompleto/vazio (`GlassesTransport.kt:63-70`). Para não trocar "sempre segue"
por "aborta no 1º erro" (que hoje levaria a `PipelineState.ERROR` com `pipelineActive=true`, do
qual **nada auto-recupera** — verificado: `EyeCaptureService.kt:459` só age com `!pipelineActive`):

- **`openWithRetry` / `requestWithRetry`:** retry limitado (teto ~10× / 500ms, paridade com o
  `getFd`) em torno de open/find/claim e de cada `request()`.
- **`failActivation(motivo)`:** nas etapas 1–2, loga ALTO e **cai pro caminho nativo** (fallback
  observável). Na etapa 3 (sem nativo), loga e **chama `onCameraEnabled()` mesmo assim** —
  replicando o "sempre chega em onCameraEnabled" do nativo, entregando a recuperação à política de
  retry do `findAndStartCamera` em vez de travar em ERROR.
- **`GetUsbConfigAll` null/exceção:** config desconhecida ⇒ **não** pula (segue pro Set), igual ao
  nativo que só procede a Set quando não conseguiu ler.

## Componentes (estado FINAL, pós-etapa 3)

- `capture/GlassesConnection.kt` — fachada, interface pública inalterada. **Pós-etapa 3:** sem
  `nativeGlasses`/`nativeInitialized`/`initNativeLibrary()`/imports de `ai.nreal.glasses.control`;
  miolo 100% Kotlin. **Durante etapas 1–2 esses membros nativos FICAM**, vivos atrás do fallback
  (ver Rollout) — a seção Componentes descreve o alvo, não a etapa 1. Constantes
  `XREAL_VID`/`PID`/`PILOT_READY_TIMEOUT_MS`/`REENUMERATION_WAIT_MS` ficam.
- `glasses/GlassesCommands.kt` — hoje só expõe a sequência completa; a produção **precisa** enviar
  operações avulsas ramificadas, então adicionar helpers públicos **em nível de operação** (com o
  preâmbulo embutido): `waitPilotReadyMessages()` → `[26,d4,26,d4,d6]`, `getUsbConfigMessages()` →
  `[26,d4,26,d4,d2]`, `setUsbConfigMessages(payload)` → `[26,d4,26,d4,d3+payload]`,
  `getCameraStatusMessages()` → `[26,d4,26,d4,d5]`. Reutilizar o `preamble()` interno;
  `enableCameraSequence()` vira a concatenação desses (idêntica, p/ o hook de paridade). Helpers
  ganham asserção de bytes contra os vetores golden.
- `glasses/GlassesTransport.kt`, `GlassesFrame.kt`, `UsbConfigCodec.kt`, `KotlinGlassesProtocol.kt`
  + hook `FORCE_CAPTURE_KOTLIN` — já existem/provados; reusados como estão.

## Estratégia de rollout (segurança durante a validação)

Sem flag de Prefs (YAGNI — SharedPreferences não é editável por adb na prática). Nas etapas 1–2:
**sempre tenta Kotlin primeiro**; em `failActivation`, cai pro nativo (fallback observável, nunca
silencioso). Cada ativação **loga qual caminho rodou** e o `UsbConfigState` cru decodificado.
Gatilho do fallback ampliado (I1): não só exceção, mas também **`Set` code ≠ 0** e **branch-skip
tomado porém o scan de câmera esgotou os retries** (⇒ re-ativa uma vez via nativo antes de ERROR).
Na etapa 3, o caminho nativo + fallback saem; Kotlin vira o único.

## Sequenciamento (3 etapas, hardware entre elas)

1. **Portar** o miolo do `GlassesConnection` p/ Kotlin, com `.so` presentes e o fallback acima.
   Adicionar helpers de operação em `GlassesCommands`. Unit tests onde for puro (decisão
   skip-if-active dado um `UsbConfigState`; bytes dos helpers). Suite verde + `assembleDebug`.
2. **Validar em hardware** — 4 cenários (o 4º é o que a `.so` escondia via poll/retry):
   - cold start (`uvc0` 0→1, re-enum real) — primeira validação do decode num config `uvc0=0`
     (amostra menos simétrica que a de calibração; logar o raw);
   - warm reconnect (`uvc0` já 1) — pula reconfiguração, sem re-enum;
   - dead-stream `forceReconfigure` — ciclo completo revive a câmera;
   - **cold-boot (óculos recém-ligados/plugados)** — exercita o poll do `waitPilotReady`.
   Verificar por logcat que rodou o **Kotlin sem fallback** e `[STREAMING]`.
3. **Remover:** criar `NOTICE` na raiz do repo (atribuição MIT dos arquivos derivados que ficam),
   deletar `NOTICE.txt` do pacote; deletar 2 `.so`, 10 `.java` do shim, `assets/nr_ota_default/`
   (+ `.gitkeep`), o caminho nativo e o fallback. Adicionar limpeza one-time do firmware já
   copiado p/ o dataDir (`File(dataDir,"nr_ota_default").deleteRecursively()` — a `.so` copiava em
   `XrealGlasses.java:169-177`). Rebuild. Provar por `unzip -l`/`jar tf` que o APK não tem `.so`
   proprietária, classes `ai.nreal.glasses.control`, nem entradas `assets/nr_ota_default/`. Manter
   a atribuição nos headers KDoc dos arquivos derivados que ficam. Smoke test final em hardware.

## Testing

- **Unit (JVM):** decisão skip-if-active/forceReconfigure é lógica pura sobre `UsbConfigState` —
  testável sem hardware. Codecs já cobertos. Helpers de operação ganham asserção de bytes golden.
- **Hardware:** os 4 cenários da etapa 2 + smoke test da etapa 3. `bulkTransfer`/claim de HID/
  re-enumeração só validam em aparelho — por design.

## Riscos / incógnitas (resolver na etapa 2)

- **Cold-start re-enumera e mata a conexão de controle** — mitigado no desenho (Set é o último na
  conexão; Status best-effort; reconexão fica com o pipeline). Confirmar ativação num cold real.
- **Decode errado silencioso (I1)** — `UsbConfigCodec` calibrado em amostra única de ordem de bits
  não provada; cold start é a 1ª validação com `uvc0=0`. Logar o raw; Set≠0 e skip-sem-camera
  disparam fallback.
- **Poll do waitPilotReady no cold-boot (C2)** — comportamento nunca exercitado; validar.
- **Claim repetido da HID com forceClaim** — o shim nativo já faz isso por transfer
  (`UsbPrivateFunction.java:61,78,111,128`) há semanas junto do vídeo 0x89; o Kotlin (1 claim por
  ativação) é mais gentil. Baixo risco; confirmar sob cold start + reconexões.

## Copyright

O que a reescrita usa vem da captura black-box do próprio projeto (tráfego USB + descritor via
`dumpsys`). A remoção elimina as 2 `.so` proprietárias (o bloqueio de redistribuição) e o código
de terceiro **verbatim**; os arquivos MIT **derivados** que ficam mantêm atribuição (NOTICE na
raiz). A tabela de opcodes HID de terceiro segue proibida como insumo (falsificada pela captura,
ver doc do protocolo).

---

## Validação Kotlin-produção em hardware (2026-07-24)

Validado em hardware. **Achado central:** o device às vezes atacha com `uvc0=1` no registro MAS
**sem a interface UVC de vídeo (class=14/subclass=2) exposta** — confirmado por `dumpsys usb`
(`class=14 count = 0` travado vs `2` streamando). Só o `SetUsbConfigAll` (com re-enumeração) a
expõe. O skip-if-active original (só `uvc0==1`) pulava esse Set e prendia o pipeline em
"procurando UVC" para sempre.

**Correção:** `shouldReconfigure` passou a exigir `uvc0==1` **E** a interface de vídeo presente
(condição observável). Trace confirmado no log de um replug limpo:

```
Atual: (raw=00 55 aa 00 00, videoIface=false)  → atachou sem vídeo
Resultado: 0                                    → SetUsbConfigAll enviado (não pulou)
[STREAMING] Streaming MJPEG...                  → re-enumerou, vídeo apareceu, streamou
Atual: (raw=00 55 9a 00 00, videoIface=true)    → vídeo presente → pula
```

Cenários validados (Kotlin, sem fallback): warm-skip, attach-sem-vídeo (auto-reconfigura sem
FORCE_CAPTURE), forceReconfigure. **Nota I1 vindicada:** o payload cru variou (`00 55 aa` vs golden
`00 55 9a`) — o decode de amostra única não é confiável para todas as configs, mas o skip não
depende mais só dele (exige vídeo presente), então um decode incorreto não trava o pipeline. Os
cenários cold-start `uvc0=0`/cold-boot tornaram-se não-críticos pela mesma razão (a `uvc0` persiste
em 1 no hardware; não foi possível forçá-la a 0).
