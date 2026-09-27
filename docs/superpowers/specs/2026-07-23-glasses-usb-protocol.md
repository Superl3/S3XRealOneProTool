# Protocolo USB dos óculos XREAL (câmera Eye) — reconhecimento

**Origem:** spike do plano 2026-07-23-copyright-clean-spike.md
**Método:** captura black-box in-process (LD_PRELOAD de ioctl), diff de sessões.

## Fase 0 — Literatura (preenchida na Task 1)

### Fontes consultadas

1. **Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android** (GitHub, MIT, criado e enviado em
   2026-03-15, 2 estrelas) — <https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android>
   - README: <https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android/blob/main/README.md>
   - Guia técnico: <https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android/blob/main/docs/camera-access-guide.md>
   - Este é o **único** projeto público encontrado que menciona explicitamente o par
     `libota-lib.so` / `libnr_glasses_api.so` e o VID `0x3318` do One Pro no contexto da câmera Eye.
2. **Void Computing — "More AR glasses USB protocols: the Worse, the Better and the Prettier"**
   — <https://voidcomputing.hu/blog/worse-better-prettier/> — RE do protocolo HID da XREAL Air
   (geração anterior, sem câmera Eye).
3. **Monado (driver XREAL Air/Air 2/Air 2 Pro)** —
   <https://monado.pages.freedesktop.org/monado/group__drv__xreal__air.html> — driver de tracking
   3DoF; não cobre câmera nem geração One Pro.
4. **wheaney/breezy-desktop** e **calendulish/xrealAirLinuxDriver** —
   <https://github.com/wheaney/breezy-desktop>, <https://github.com/calendulish/xrealAirLinuxDriver>
   — RE do driver Linux da XREAL Air (mesma geração/protocolo do item 2, "baseado em engenharia
   reversa, sem garantias"). Nenhuma menção a One Pro, Eye camera, ou `NRBSP*`.
5. Buscas diretas pelos símbolos `NRBSPWaitPilotReady`, `NRBSPGetUsbConfigAll`,
   `NRBSPSetUsbConfigAll`, `NRBSPGetCameraStatus`, `libnr_glasses_api` (fora do repo do Aloim) e
   por "USBDEVFS ioctl reverse engineering XREAL camera" — sem resultados adicionais relevantes.

### O protocolo já é documentado publicamente?

**Parcial — e o essencial para o spike continua indocumentado.**

O que o repo do Aloim documenta:
- A sequência de chamadas JNI de alto nível (`NRBSPWaitPilotReady` → `NRBSPGetUsbConfigAll` →
  `config.uvc0 = 1` → `NRBSPSetUsbConfigAll(config)` → re-enumeração 13→17 interfaces →
  `NRBSPGetCameraStatus`) — mas isso é **o mesmo nível de conhecimento que este projeto já tinha**
  por inspeção do próprio `XrealGlasses.java`/`GlassesConnection.kt`. Não é informação nova.
- Uma tabela "HID Command Reference" com códigos que o autor diz ter "descoberto na APK do XREAL
  Glasses Control": `0x68` (RGB Enable), `0x69` (RGB Stream), `0x6A` (Network Enable), `0x1101`
  (Start Stream), `0x1102` (IDR Request). **Isto é potencialmente valioso**, mas com duas ressalvas
  fortes: (a) o próprio app do Aloim **não usa esses códigos** — ele carrega `libota-lib.so` e
  `libnr_glasses_api.so` da APK oficial via JNI e trata as 4 chamadas como caixa-preta, exatamente
  como este projeto faz hoje; os códigos parecem vir de strings/engenharia estática na `.so`, não
  de uma captura de tráfego USB verificada; (b) o repositório é pequeno, novo (criado e enviado no
  mesmo dia) e sem outras fontes que o corroborem — **tratar como pista não verificada, não como
  fato**.
- O protocolo UVC de vídeo (probe/commit, cabeçalho de payload, bulk transfer) documentado é o
  **padrão USB Video Class** — não é proprietário da XREAL, é conhecimento genérico de UVC.

O que **não** está documentado em nenhuma fonte encontrada:
- Os bytes exatos do control transfer (ou report HID) que `NRBSPSetUsbConfigAll` emite para
  ativar `uvc0` — nenhuma fonte mostra uma captura de tráfego real desse comando específico.
- Se há algum campo variável (contador, nonce, checksum sobre payload dinâmico) nesse comando
  específico do One Pro.

As fontes sobre a geração **Air** (itens 2–4) descrevem um protocolo HID diferente (pacotes com
header fixo `0xfd`, checksum CRC32/Adler, sem necessidade de habilitar câmera/sensores
individualmente — a Air não tem o recurso de câmera Eye/config `uvc0`). Útil como pista de
convenção geral de framing da XREAL, mas **não** é o mesmo protocolo/comando que o spike precisa
capturar — geração de hardware e recurso diferentes.

**Conclusão prática:** a Fase 1 (harness de captura) continua necessária na íntegra. Nenhuma fonte
pública permite pular a captura real dos URBs das 4 chamadas no One Pro.

> ### 🚫 FECHAMENTO DA PISTA HID (escrito na Fase 2, após a captura)
>
> A tabela de comandos HID acima (`0x68`, `0x69`, `0x6A`, `0x1101`, `0x1102`), derivada de
> **análise estática da `.so`** por terceiro, foi **FALSIFICADA pela captura real**:
> - O vocabulário observado é `0x26, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6` — **nenhum** dos códigos da
>   tabela aparece.
> - O transporte é **BULK** (ep `0x01`/`0x81`), não HID.
>
> **Esses opcodes NÃO devem ser usados como insumo da reescrita.** A fase seguinte é uma
> reimplementação *clean-room*, e material derivado de análise estática do binário proprietário
> alheio é exatamente o que não pode entrar nela. A única fonte legítima para a reescrita é a
> captura black-box deste spike (Fase 1/2).

### Hipótese preliminar estático-vs-dinâmico

**Hipótese fraca, a favor de "estático"** — mas baseada em evidência indireta e não confirmada,
que a Fase 1/2 devem validar empiricamente antes de qualquer decisão GO/NO-GO:

- O guia do Aloim descreve a ativação da câmera como um comando único de configuração seguido de
  espera por re-enumeração — nenhuma menção a handshake, resposta desafio-resposta, ou múltiplas
  idas-e-vindas de autenticação.
- A tabela de comandos HID (mesmo não verificada) lista opcodes fixos e de propósito único
  (`0x68` = "RGB Enable"), consistente com um protocolo de comandos estáticos e não com tokens de
  sessão.
- O protocolo da geração Air (fontes 2–4) usa checksum determinístico sobre os dados do próprio
  pacote (CRC32/Adler) — isso é integridade de dados, não autenticação de sessão; se a XREAL
  mantém a mesma convenção de framing no One Pro, um checksum assim **não invalida** a hipótese de
  bytes repetíveis, pois é recalculável a partir de um payload fixo conhecido.
- Contra-hipótese a levar a sério: nenhuma fonte é uma captura de tráfego real do comando
  específico do One Pro. A ausência de menção a nonce/contador é ausência de evidência, não
  evidência de ausência — a Fase 1 (diff de ≥2 sessões frescas) é o único jeito de confirmar.

## Fase 1 — Sequência capturada (Task 6)

### Descoberta central: o protocolo NÃO usa control transfers

A hipótese implícita do plano (control transfers vendor) estava **errada**. O canal de controle da
XREAL é **BULK**, num fd e endpoints separados do vídeo:

| Canal | fd (exemplo) | Endpoints | Conteúdo |
|---|---|---|---|
| **Controle (XREAL)** | 157 | `0x01` OUT / `0x81` IN | comandos 22–45 B / respostas 1024 B |
| Vídeo (UVC) | 127 | `0x89` IN | frames MJPEG |

Os control transfers que aparecem na captura (`bmRequestType=0x21/0xa1`, `wIndex=0x000a`, blocos de
34 B) são **UVC padrão** (PROBE/COMMIT + `SET_INTERFACE`) do restart do stream — não são a XREAL.

### Formato do quadro (deduzido de 6 comandos distintos)

```
offset  campo
0       magic 0xfd
1..4    CRC-32 de bytes[5 : 5+len], little-endian   <-- vale nas DUAS direções; ver Fase 2
5       comprimento = tamanho_total - 5
6..14   zeros (reservado)
15      código do comando
16..21  zeros
22..    payload (opcional)
```

### Vocabulário completo observado (6 comandos)

```
len=22  fd 25 af 75 f5 11 ...00... 26 ...00...
len=22  fd ef b1 44 65 11 ...00... d2 ...00...
len=26  fd c6 e4 89 b8 15 ...00... d3 ...00... 45 10 01 00
len=22  fd d5 84 94 06 11 ...00... d4 ...00...
len=22  fd 61 8f e3 a0 11 ...00... d5 ...00...
len=45  fd ee 87 da 7c 28 ...00... d6 ...00... "ro.bsp.app_prepare_done"
```

- `0xd6` carrega **nome de propriedade em ASCII** (`ro.bsp.app_prepare_done`) — é o transporte do
  `NRBSPGetProperty`/`SetProperty`.
- `0xd3` carrega payload binário de 4 B (`45 10 01 00`) — provável bitmask de config USB.
  **Ressalva:** `GlassesConnection.kt:197-202` monta SEMPRE o mesmo `UsbConfigList`
  (`ncm=1, ecm=1, hid_ctrl=1, enable=1, uvc0=1`), então esse payload ser invariante entre runs é
  em parte artefato do chamador, não só propriedade do protocolo. Não enfraquece o GO (a reescrita
  só precisa dessa constante), mas não permite inferir o mapeamento bit-a-bit dos campos.
- Respostas IN de 1024 B; uma delas contém a versão de firmware em ASCII: `15.1.03.442_20260722`.

### Sequência ordenada e mapeamento para as 4 chamadas

20 comandos OUT por execução. Cada operação lógica é precedida por um **par de preâmbulo
`0x26, 0xd4`** (repetido 2×). Âncora temporal: o intervalo de **3,02 s** entre os comandos 15 e 16
corresponde ao `Thread.sleep(REENUMERATION_WAIT_MS)` que `GlassesConnection.enableEyeCamera`
executa **logo após** `NRBSPSetUsbConfigAll` — é isso que ancora o mapeamento abaixo em evidência,
e não em suposição:

```
 #      len  cmd    chamada lógica
 1- 2    22  26,d4  (preâmbulo)
 3- 4    22  26,d4  (preâmbulo)
 5       45  d6     NRBSPWaitPilotReady   -> lê a propriedade "ro.bsp.app_prepare_done"
 6- 9    22  26,d4  (preâmbulo ×2)
10       22  d2     NRBSPGetUsbConfigAll
11-14    22  26,d4  (preâmbulo ×2)
15       26  d3     NRBSPSetUsbConfigAll  -> payload 45 10 01 00
        ~~~~ intervalo de 3,02 s = Thread.sleep(REENUMERATION_WAIT_MS) ~~~~
16-19    22  26,d4  (preâmbulo ×2)
20       22  d5     NRBSPGetCameraStatus
```

Capturas brutas preservadas em `.superpowers/sdd/captures/run{1,2,3,4_freshsession}.txt`
(scratch git-ignored).

### Nota histórica: o teto de dump de 64 B

A primeira rodada de capturas usou teto de 64 B no `ioctltap.c`, o que observava os comandos OUT
inteiros (22–45 B) mas escondia ~94 % das respostas IN (1024 B). A revisão final whole-branch
pegou que o veredito alegava completude que a instrumentação não podia sustentar. O teto foi
subido para 1024 B e as respostas recapturadas — ver "Direção IN — RESOLVIDA" na Fase 2.

## Fase 2 — Veredito GO/NO-GO (Task 7)

### Evidência: estático vs dinâmico

Três execuções consecutivas de `FORCE_CAPTURE` (sequência completa
`WaitPilotReady → GetUsbConfigAll → SetUsbConfigAll → GetCameraStatus`), 20 comandos OUT cada:

```
DIFF run1 vs run2 -> IDENTICOS          (mesma sessão)
DIFF run1 vs run3 -> IDENTICOS          (mesma sessão)
DIFF run1 vs run4 -> IDENTICOS          (SESSÃO NOVA, após replug físico)
```

**Byte por byte idênticos**, inclusive atravessando uma **re-enumeração USB completa** (run4 foi
capturada depois de desplugar e replugar os óculos). Nenhum nonce, contador ou token de sessão —
nem por comando, nem por sessão de dispositivo. Os bytes 1–4, que a princípio pareciam variar, são
determinísticos por conteúdo: o mesmo comando produz sempre os mesmos 4 bytes.

### O checksum foi resolvido

Brute-force offline de variantes de CRC contra os 6 pares (mensagem, checksum) — sem tocar nas
`.so`, apenas sobre bytes observados:

```
MATCHES: [('crc32', 'bytes[5:]', '<I')]
```

**CRC-32 padrão (ISO-HDLC / zlib), little-endian, sobre `bytes[5:]`** — bate em todas as 6
mensagens. Confirmação no comando `0x26`: armazenado `0xf575af25`, calculado
`crc32(bytes[5:]) = 0xf575af25`.

Em Kotlin isso é `java.util.zip.CRC32` — biblioteca padrão, zero dependências.

### VEREDITO: **GO** (confiança alta) — ambas as direções

O GO se sustenta integralmente: o protocolo é **estático, sem autenticação, sem nonce e sem
cripto**, e o checksum está resolvido. Isso é conclusivo porque um desafio-resposta ou nonce
ecoado necessariamente perturbaria os bytes **OUT**, e 4 execuções × 20 comandos saíram idênticas
atravessando re-enumeração USB completa.

O que a reescrita já tem resolvido:

| Requisito | Situação |
|---|---|
| Transporte | `UsbDeviceConnection.bulkTransfer()` nos ep `0x01`/`0x81` |
| Framing | documentado acima |
| Checksum | CRC-32 padrão — `java.util.zip.CRC32` |
| Comandos | 6, estáticos e enumerados |
| Autenticação | **nenhuma** — sem nonce, sem desafio-resposta, sem cripto |

Não há nada no protocolo que exija a `libnr_glasses_api.so`. O ramo de escalação para Ghidra
(previsto caso o protocolo fosse dinâmico) **não é necessário** — a linha de copyright fica
intacta: tudo foi obtido por observação black-box do nosso próprio processo.

### Direção IN — RESOLVIDA (recaptura com teto de 1024 B)

O teto de dump foi subido de 64 → 1024 B (emitido em pedaços de 256 B com `off=`, para não
esbarrar no limite de ~4000 chars do `__android_log_print` e truncar em silêncio). Recaptura em
`captures/run5_fulldump.txt`. As 4 pendências caíram:

**1. Framing da resposta = idêntico ao do comando.** Confere nas **6** respostas:

```
[0]=0xfd magic | [1:5]=CRC-32 LE | [5]=len | [15]=eco do código do comando | [22:]=payload
```

Refinamento importante da regra do CRC, agora unificada para as duas direções:

```
CRC-32(bytes[5 : 5+len])      <-- NÃO bytes[5:]
```

Nos comandos OUT o buffer tem exatamente o tamanho da mensagem, então `bytes[5:]` coincidia. Nas
respostas o buffer é sempre 1024 B mas a mensagem tem `5+len` — é `5+len` que vale.

**2. `NRBSPWaitPilotReady` (`0xd6`)** — a resposta carrega o **ASCII `"true"`**. O critério de
"ready" é literalmente a propriedade `ro.bsp.app_prepare_done` retornando `true`.

**3. `NRBSPGetUsbConfigAll` (`0xd2`)** — payload `00 55 9a 00 00`. Os campos são empacotados em
**campos de 2 bits**, não um byte por campo:

```
0x55 = 01|01|01|01  -> ncm=1  ecm=1  hid_ctrl=1  uvc0=1
0x9a = ...|10       -> uvc1=2
```

Correlação na MESMA execução (log do app às 21:02:01): `Atual: ncm=1 ecm=1 hid=1 uvc0=1 uvc1=2`. ✓

**4. `NRBSPSetUsbConfigAll` (`0xd3`)** responde payload `00`; `NRBSPGetCameraStatus` (`0xd5`)
responde `00 00`. Correlacionado na mesma execução com `Resultado: 0` e `Status da câmera: 0`. ✓

### Única pendência restante (menor, não bloqueia)

**Encoding do payload que o `SetUsbConfigAll` ENVIA** (`45 10 01 00`) não foi decodificado. Só
temos **uma amostra** de configuração, porque `GlassesConnection.kt:197-202` sempre monta o mesmo
`UsbConfigList`. Igualmente, o layout exato de bits do `0xd2` (ordem dos campos de 2 bits, LSB-vs-
MSB-first) casa perfeitamente com uma amostra, mas **uma amostra não distingue as ordens possíveis**.

Isso **não bloqueia a reescrita**: ela só precisa reproduzir essa configuração exata, e a constante
`45 10 01 00` está capturada. Para generalizar (ligar/desligar campos arbitrários), capture com
configs variadas e diffe — barato, com o harness já pronto.

### Pré-estado dos óculos nas capturas

As capturas foram feitas com `uvc0` **já ativa** (`uvc0=1`), não a partir de `uvc0=0`. Isso é
irrelevante para o resultado porque `forceReconfigure=true` **contorna** o atalho
"uvc0 já ativa → pula reconfiguração" (`GlassesConnection.kt:191`), de modo que toda execução
emitiu de fato o `NRBSPSetUsbConfigAll`. Na prática o resultado fica **mais forte**: os bytes são
idênticos independentemente do pré-estado.

## Peso-morto OTA (preenchida na Task 8)

### As 2 `.so` são obrigatórias (fato já estabelecido, não re-derivado)

Inspeção por `readelf`/`nm` (spike anterior) mostrou que:

- `libota-lib.so` hospeda **todos** os entry points JNI `Java_ai_nreal_glasses_control_XrealGlasses_*`
  (inclusive as 4 chamadas de câmera) — é exatamente o que `System.loadLibrary("ota-lib")` carrega.
- `libota-lib.so` tem **NEEDED-link** em `libnr_glasses_api.so` (a implementação C do protocolo).

Ou seja, **nenhuma das duas `.so` é removível** para o caminho atual de câmera nativa — sem elas
não há símbolo JNI para o `System.loadLibrary` resolver, nem a implementação do protocolo que ele
invoca. Isto não foi re-testado nesta task (por instrução explícita); é herdado da spike anterior.

### `assets/nr_ota_default/` — testado em hardware nesta task

**Resultado: peso-morto confirmado.** A pasta foi movida para fora da árvore de assets, o app foi
rebuildado (`assembleDebug`) e reinstalado, e o fluxo completo de ativação de câmera
(`FORCE_CAPTURE`) foi disparado nos óculos reais.

Build sem os assets — sucesso, sem erros nem warnings novos:
```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 3s
```

Log do dispositivo após o broadcast, com os assets OTA ausentes (trecho relevante):
```
D EyeCaptureService: Aguardando pilot ready...
D EyeCaptureService: Pilot ready: true
D EyeCaptureService: Lendo config USB atual...
D EyeCaptureService: Atual: ncm=1 ecm=1 hid=1 uvc0=1 uvc1=2
D EyeCaptureService: Chamando NRBSPSetUsbConfigAll (uvc0=1)...
I [NRGlassOTA]: [libnr_glasses_api] [...] [NRBSPSetUsbConfigAll-2192] : function called
D EyeCaptureService: Resultado: 0
D EyeCaptureService: Config USB aplicada, aguardando re-enumeração...
D EyeCaptureService: Status da câmera: 0
D EyeCaptureService: Câmera ativada — aguardando 2ª permissão USB pós re-enumeração...
D EyeCaptureService: UVC Eye camera (IF#10) em VID=13080 PID=1078
D EyeCaptureService: [STREAMING] Streaming MJPEG...
D EyeCaptureService: Stats: 1000 leituras, 993 frames
```

A câmera ativou, reenumerou e voou em streaming MJPEG normalmente — nenhuma leitura de arquivo sob
`assets/nr_ota_default/` foi necessária em nenhum ponto da sequência. Confirma a hipótese: esses
blobs de firmware só são consumidos pelas chamadas `NROTA*` (atualização de firmware), que este
app nunca invoca; o caminho de câmera (`NRBSPWaitPilotReady` → `NRBSPGetUsbConfigAll` →
`NRBSPSetUsbConfigAll` → `NRBSPGetCameraStatus`) não toca neles.

Os assets foram restaurados ao final do teste (`Move-Item` de volta para
`app/src/main/assets/nr_ota_default/`) e o app foi rebuildado/reinstalado novamente para
confirmar que o fluxo volta a funcionar normalmente com os assets no lugar — este teste é apenas
uma prova de conceito; a remoção definitiva fica para a fase de reescrita.

**Conclusão:** `assets/nr_ota_default/` é candidato confirmado a remoção definitiva na reescrita;
as 2 `.so` (`libota-lib.so`, `libnr_glasses_api.so`) permanecem obrigatórias enquanto o app usar o
caminho de câmera nativo via JNI.

---

## Validação da paridade Kotlin (2026-07-23)

A reescrita em Kotlin puro das 4 operações de ativação da câmera (`WaitPilotReady`,
`GetUsbConfigAll`, `SetUsbConfigAll`, `GetCameraStatus`) foi validada **em hardware** por diff
diferencial contra o baseline nativo (`libnr_glasses_api.so`), no mesmo aparelho e build.

**Método:** o mesmo interpositor `IOCTLTAP` (`LD_PRELOAD`) que hookou a `.so` também hooka o
`bulkTransfer` da USB Host API do Android (ambos descem para `USBDEVFS_BULK`). Dispararam-se dois
broadcasts debug — `FORCE_CAPTURE` (caminho nativo) e `FORCE_CAPTURE_KOTLIN` (caminho Kotlin) — e
compararam-se as linhas `bulk-OUT` das duas capturas.

**Resultado — PARIDADE PROVADA:**

```
native: 20 comandos | kotlin: 20 comandos
diff native.norm kotlin.norm  ->  VAZIO
```

Os 20 comandos OUT são **idênticos byte-a-byte** (preâmbulo `0x26,0xd4` 2× antes de cada operação;
`0xd6` com `ro.bsp.app_prepare_done`; `0xd2`; `0xd3` com payload `45 10 01 00`; `0xd5`). A direção
IN também foi validada: o app Kotlin logou `Pilot ready: true`,
`UsbConfigState(ncm=1, ecm=1, hidCtrl=1, uvc0=1, uvc1=2)` (bate com o payload golden `00 55 9a 00 00`),
`Resultado: 0` e `Status da câmera: 00 00` — parsing correto das respostas de 1024 B.

### Divergência corrigida no caminho: seleção da interface de controle

A primeira captura Kotlin falhou com `interface de controle (ep 0x01/0x81) não encontrada` —
`GlassesTransport.find()` retornava null. Causa-raiz, apurada pelo descritor real do dispositivo
(`dumpsys usb` → `host_manager`, captura black-box do próprio aparelho):

- Os endpoints de controle `0x01` (OUT) e `0x81` (IN) ficam numa interface de **classe HID
  (`class=3`)** e são do tipo **INTERRUPT (`type=3`)**, com `max_packet_size=1024` — **não** BULK.
- A lib nativa faz `USBDEVFS_BULK` nesses endpoints interrupt (o kernel roteia bulk em endpoint
  interrupt); `UsbDeviceConnection.bulkTransfer` faz exatamente o mesmo — daí a paridade no fio.
- O `find()` original filtrava `if (ep.type != USB_ENDPOINT_XFER_BULK) continue`, descartando
  justamente os endpoints de controle.

**Correção:** `find()` passou a casar pelo **endereço exato** `0x01`/`0x81` independentemente do
tipo de endpoint. É seguro: o endereço já é único (vídeo = `0x89`; interfaces CDC-data usam
`0x02/0x82/0x03/0x04`), então o guard contra o endpoint de vídeo permanece intacto. Foi
precisamente essa atribuição byte-exata que a estratégia de paridade comprou — o diff apontou a
mensagem que faltava e a causa foi isolada no descritor, não por tentativa e erro.

> Nota de terminologia: isto **não** contradiz a conclusão do spike ("transporte é BULK, não HID").
> O *mecanismo* de transferência é bulk-style (`USBDEVFS_BULK`); o que é HID é a *classe da
> interface* que hospeda os endpoints. A tabela de opcodes HID de terceiro segue proibida como
> insumo — todo dado aqui vem da captura black-box deste projeto.

### Nota para a fase de otimização

O preâmbulo (`0x26,0xd4` ×8) e o `Thread.sleep(3000)` cego após o `SetUsbConfigAll` foram mantidos
**de propósito** para preservar o diff byte-a-byte. Agora que a paridade está provada, ambos são
candidatos legítimos a investigação/remoção na fase seguinte.
