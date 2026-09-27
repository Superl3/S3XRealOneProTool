# Gesto de mute do cursor (thumbs-up) — design (2026-07-23)

## Problema

Ao abaixar os braços, o tracking pega detecções espúrias e o cursor fica "perdido e visível"
na tela (relato do usuário). Além do incômodo visual, há risco de clique fantasma em cenários
passivos (assistindo vídeo). Falta um jeito deliberado de "estacionar" o mouse.

## Decisão de gesto (aprovada pelo usuário)

**Thumbs-up segurado ~1s, mesma pose liga e desliga.** Alternativas descartadas: pinch com
2 mãos (exige `numHands=2` → ~2× custo de inferência + refactor multi-mão) e punho segurado
4s (sobrecarrega o gesto de recentralizar).

## Componentes

### 1. `ThumbsUpDetector` (novo, puro, `tracking/`)
Pose = **três condições simultâneas**, cada uma com EMA (alpha 0.5) + histerese:
- **4 dedos dobrados**: `max(dist3(punho,ponta)/dist3(punho,PIP))` dos pares 8/6, 12/10,
  16/14, 20/18 — entra < 1.05, sai > 1.20 (mesma métrica invariante a ângulo do FistDetector).
- **Polegar estendido**: `dist3(THUMB_TIP=4, INDEX_MCP=5)/handScale` — entra > 0.85, sai
  < 0.65 (no punho o polegar fica junto dos dedos, ~0.3-0.6; estendido ~0.9-1.3).
- **Polegar pra CIMA**: `(wrist.y - thumbTip.y)/handScale` — entra > 0.5, sai < 0.3 (y cresce
  pra baixo na imagem). Rejeita thumbs-down e punho com polegar caído de lado.
Debounce assimétrico: entra em 8 frames (~130ms — a pose é anatomicamente distinta), sai em 3.
Expõe `isActive` + `reset()`. Testes JVM puros.

### 2. Veto de polegar no `FistDetector`
Punho passa a exigir **polegar recolhido**: EMA de `dist3(4,5)/handScale` — entra em punho só
se < 0.85 e **sai** do punho se > 1.0 (transição punho→thumbs-up solta o punho). Sem isso,
thumbs-up seria lido como punho (e recentralizaria aos 2s). Fixtures dos testes atualizadas
(polegar recolhido por default) + casos novos de veto.

### 3. `CursorOverlay.setHiddenByUser(hidden)`
Esconde/mostra SÓ a view do cursor (alpha, cancela fade/animator) — sem destruir/recriar a
janela (cara e histórica fonte de bugs). Guard no `reappear()` pra `moveTo` não desfazer o
mute. Main thread (mesmo contrato dos outros métodos).

### 4. Integração no `CursorPipeline`
- Alimenta o `ThumbsUpDetector` TODO frame (antes do modo palma). Hold de
  `THUMBS_UP_TOGGLE_HOLD_MS = 1000` com a máquina de clique em `IDLE` → alterna
  `cursorMuted`; dispara 1× por hold (soltar rearma).
- **Mutado**: retorno antecipado — zero palma/pinch/punho/movimento/injeção/render; o frame
  só alimenta o detector de thumbs-up (o "ouvido" pro religar). Cursor some na hora
  (`setHiddenByUser(true)`), sem esperar o fade de 2s.
- **Desmutado**: reset geral (filtros, dead-zone, detectores, histórico, máquina de clique) +
  `relativeMapper.recenter()` — cursor reaparece no centro (a correspondência mão↔tela já se
  perdeu com os braços abaixados; centro é o previsível).
- `onHandLost` NÃO desfaz o mute (persiste até gesto explícito). Transições logadas
  (diagnóstico, mesmo padrão palma/punho).

## Custo e riscos
Zero inferência extra, zero impacto no caminho quente. Risco principal: falso-positivo do
thumbs-up — mitigado pela tripla condição + hold de 1s + exigência de IDLE. Thresholds de
bancada, afinar em hardware.
