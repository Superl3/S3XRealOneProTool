# Pipeline MJPEG — design do caminho futuro (2026-07-23)

> **STATUS (2026-07-23, fim do dia): IMPLEMENTADO E VALIDADO EM HARDWARE** — fases 1+2+3
> executadas no mesmo dia (time de 3 agentes + integração). Resultados: FOV do fmt2 confirmado
> IDÊNTICO ao fmt1 (frame de dump inspecionado visualmente — 1920x1080 paisagem, nítido);
> descoberta: o FrameAssembler truncava JPEGs em 65534b (framing UVC do MJPEG difere do HEVC) —
> resolvido com remontagem por marcadores SOI/EOI ([MjpegStreamAssembler], puro+testado);
> stream MJPEG recordista: **13.178 frames (~110s) contínuos** vs 5056 do melhor HEVC do dia,
> zero falhas de decode, ~99,6% de eficiência de remontagem; usuário reportou blinks
> "muito menores" no Anchored. MJPEG é o formato preferido (candidato #0) com fallback
> automático HEVC 1080p → HEVC nativo em dois níveis (silêncio e decode).

## Motivação e evidência

O stream HEVC (fmt1) engasga a cada ~20–90s ("Endpoint morto"/stalls), pior no modo
**Anchored** dos óculos (o chip X1 faz reprojection + encode HEVC 60fps ao mesmo tempo;
em casos extremos o link USB inteiro reseta — display "pisca", óculos somem do barramento).
A sessão de 23/07 construiu uma máquina de recuperação em camadas que reduziu cada
interrupção a ~0,5–3s auto-recuperados, mas **a causa (encoder HEVC sobrecarregado) permanece**.

Durante o experimento de resolução descobrimos via `rawDescriptors` que a câmera Eye expõe
um **segundo formato completo em MJPEG** (fmt2, subtype 0x06):

| fmt | subtype | frames |
|-----|---------|--------|
| 1 | 0x10 (frame-based/HEVC) | 2048x1512, **1920x1080 (em uso hoje)** |
| 2 | 0x06 (**MJPEG**) | 1920x1080 (2x), 1080x1920 (2x), 720x1280 |
| 6 | 0x06 | 16384x24322 (descritor lixo/still — ignorar) |

Teste em hardware (fmt2/frm5 720x1280): a câmera **entrega JPEG real** (payload `FF D8`,
~42KB/frame, 60fps) e o stream rodou **4000 frames sem um único stall** — enquanto o HEVC
nunca passou de ~5000 na melhor janela do dia e tipicamente morre antes de 2000. Amostra
pequena (67s), mas a explicação técnica sustenta:

- **MJPEG é intra-only**: sem estado entre frames no encoder → carga menor e constante no
  X1; um soluço se recupera NO FRAME SEGUINTE (não existe "esperar VPS/SPS/IDR", que hoje
  custa ~600ms em cada recuperação).
- **Decode trivial de reiniciar**: qualquer frame é decodificável isolado — a recuperação
  de stall vira "descartar bytes até o próximo `FF D8`".

## Por que pode NÃO valer (incertezas — o spike responde todas)

1. **FOV/conteúdo do fmt2**: pode ser um stream processado/cropado (os frames retrato
   1080x1920 sugerem uso interno de CV dos óculos). Se o FOV for diferente do fmt1, o
   tracking muda de característica. → validação VISUAL no spike.
2. **Estabilidade em sessão longa**: os 4000 frames sem stall podem ter sido sorte de
   janela. → spike roda 15+ min com os contadores de stall existentes.
3. **Custo de decode no CELULAR**: HEVC hoje é decodificado por hardware (MediaCodec);
   MJPEG 1080p@60 seria CPU (libyuv/`MJPGToI420`, ~3–6ms/frame estimado) ou
   `BitmapFactory` (Skia, com `inBitmap` reuse). Mais CPU no S25 = mais térmica do lado do
   celular. → medir no spike (o `ThermalMonitor` já derruba fps se precisar).
4. **Banda USB**: 1080p MJPEG ~80–150KB/frame → 5–9MB/s; bulk USB 2.0 dá ~35MB/s efetivos.
   Folga confortável, mas medir o tamanho real do frame 1080p no spike.

## Plano em fases (cada uma com gate GO/NO-GO)

### Fase 1 — Spike de validação (1 sessão curta)
- Negociar fmt2/**frm1 1920x1080 paisagem** (não o retrato) com o pipeline atual em modo
  "tap": salvar ~20 frames JPEG em disco + rodar 15 min só contando stalls (sem decode).
- Decodificar os JPEGs salvos no desktop: conferir FOV vs fmt1 (mesma cena?), orientação,
  qualidade, tamanho médio de frame.
- **GO se**: FOV equivalente ao fmt1 E taxa de stall claramente menor que o HEVC na mesma
  sessão/modo (Anchored). **NO-GO**: arquivar este doc com os números medidos.

### Fase 2 — MjpegDecoder (1 sessão)
- Novo `MjpegDecoder` substituindo `HevcDecoder`+`FrameConverter` no caminho MJPEG:
  thread própria; por frame: `FF D8`-scan defensivo → decode (preferência:
  `BitmapFactory.Options.inBitmap` com pool de 3 Bitmaps RGBA reutilizáveis → `MPImage`
  via `BitmapImageBuilder`; alternativa se latência decepcionar: libyuv `MJPGToI420` +
  caminho RGBA atual).
- `FrameAssembler` fica como está (headers UVC são idênticos); detecção de stall/endpoint
  morto fica como está; recuperação de stall vira só "re-negociar stream" (sem decoder
  pra reconstruir — mais barata que a atual).
- Seleção de formato: `UvcCameraHelper` ganha preferência configurável
  (`Prefs.pipelineFormat`: AUTO → tenta MJPEG, fallback HEVC com a máquina de strikes já
  existente; HEVC → comportamento atual).

### Fase 3 — Validação A/B em hardware (1 sessão de uso real)
- Mesmos contadores de hoje (stalls, endpoints mortos, soluços, recuperações) por 20–30
  min em cada pipeline, no Anchored.
- Latência ponta-a-ponta e térmica do celular comparadas (o HUD já mostra ambas).
- **GO**: MJPEG vira default (AUTO), HEVC permanece como fallback automático.

## Estimativa e recomendação

~3 sessões curtas, risco técnico baixo (cada fase é reversível e o fallback pro HEVC é
automático). A recomendação é **fazer**: é a única via identificada que ataca a CAUSA dos
stalls/blinks (carga do encoder no X1) em vez de só recuperar mais rápido — e o spike da
Fase 1 é barato o suficiente pra desistir cedo com dados, se a hipótese cair.
