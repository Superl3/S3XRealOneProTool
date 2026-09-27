# Controle por voz — design

Data: 2026-07-23
Status: aprovado em brainstorming (sessão 23/07)

## Objetivo

Adicionar controle por voz ao xreal-hand-mouse, complementando o hand tracking: navegação
básica do sistema, abertura de apps e ditado de texto — sem precisar digitar por gesto nem
pegar o celular.

## Decisões de produto

- **Ativação por gesto**: sinal de "V" (indicador + médio esticados) abre uma janela de
  escuta de ~5s. Zero consumo de mic/bateria fora da janela; zero falso-positivo em
  conversa normal.
- **Motor**: `SpeechRecognizer` nativo do Android (on-device no Galaxy). Sem dependência
  nova, sem modelo embarcado no APK.
- **Idioma**: configurável no app — `sistema` (default), `pt-BR` ou `en-US`. O locale
  escolhido vai em `RecognizerIntent.EXTRA_LANGUAGE`. A tabela de comandos é bilíngue
  independente do idioma escolhido (aceita "voltar" e "back" sempre); a escolha afeta a
  qualidade do reconhecimento de fala livre (ditado, nomes de app).
- **Escopo da v1**:
  - Navegação: voltar, início, recentes (global actions).
  - Abrir app por nome falado.
  - Ditado: "escrever <texto>" no campo focado, "enviar", "apagar tudo".
- **Fora da v1**: fechar app específico (sem API pública; caminho via recentes + swipe
  injetado é frágil), wake word, escuta contínua, ações de sistema extras (screenshot,
  notificações, volume — triviais de adicionar depois via tabela).

## Componentes novos

### `VSignDetector` (`tracking/`)

Mesmo molde do `FistDetector`: recebe landmarks do MediaPipe, detecta indicador+médio
estendidos com anelar+mínimo dobrados, exige estabilidade por N frames (~300ms) para não
disparar em transição de gesto. Puro, testável por unit test.

### `VoiceCommandController` (`input/`)

Máquina de estados: `IDLE → LISTENING → EXECUTING → IDLE`.

- Recebe o sinal do `VSignDetector` via `CursorPipeline` (mesmo caminho do
  `MediaGestureController`).
- Abre sessão do `SpeechRecognizer` com o locale configurado, janela de ~5s.
- Enquanto `LISTENING`, o `CursorPipeline` suprime os demais gestos (pinça/punho/swipe)
  para a mão não interagir com a tela durante a fala.
- Resultado vai ao `VoiceCommandParser`; a ação resultante é executada e o estado volta
  a `IDLE`.

### `VoiceCommandParser` (`input/`)

Função pura: texto reconhecido → ação. Regras, nesta ordem:

1. Frase exata na tabela de sinônimos (pt + en, normalizada: minúsculas, sem acento):
   - "voltar" / "back" → `GLOBAL_ACTION_BACK`
   - "início" / "home" → `GLOBAL_ACTION_HOME`
   - "recentes" / "apps recentes" / "recents" → `GLOBAL_ACTION_RECENTS`
   - "enviar" / "send" → ENTER no campo focado (`ACTION_IME_ENTER`)
   - "apagar tudo" / "clear" → limpa o campo focado (`ACTION_SET_TEXT` vazio)
2. Prefixo "abrir " / "open " → resto é nome de app (`AppLauncher`).
3. Prefixo "escrever " / "write " → resto é texto de ditado, inserido literal
   (`TextInserter`).
4. Nada casou → `NO_MATCH` (feedback de erro, fecha a janela).

### `AppLauncher` (`input/`)

Resolve o nome falado contra os labels dos apps instalados (`PackageManager`), com
normalização (minúsculas, sem acento) e match por igualdade → prefixo → contém. Lança o
intent no display do DeX (`ActivityOptions.setLaunchDisplayId` com o display atual do
`DexDisplayMonitor`) — validar no hardware; o DeX costuma resolver sozinho.

### `TextInserter` (`input/`)

- Acha o campo focado: `findFocus(FOCUS_INPUT)` no a11y service.
- **Anexa** o texto ditado ao conteúdo existente via `ACTION_SET_TEXT` (lê o texto atual
  e concatena).
- Fallback para apps que recusam `ACTION_SET_TEXT`: clipboard + `ACTION_PASTE`.
- "Enviar" = `ACTION_IME_ENTER` (API 30+; minSdk 34, ok).

## Integração com o existente

- `HandMouseAccessibilityService` cria o `VoiceCommandController` em `onServiceConnected`
  (junto do `MediaGestureController`) e o desfaz em `onDestroy` — é quem tem
  `performGlobalAction` e a árvore de acessibilidade.
- `CursorOverlay` ganha estados visuais: indicador de escuta (ícone de mic / cor
  diferente) enquanto `LISTENING`; flash verde ao executar, vermelho em erro/no-match.
  Sem janela nova — só estados no overlay existente.
- `MainActivity`: seletor de idioma do reconhecimento (sistema / PT / EN) persistido em
  `Prefs.voiceLanguage`; pedido de runtime permission `RECORD_AUDIO` junto das atuais.

## Configuração e permissões

- Manifest: `RECORD_AUDIO`.
- `accessibility_config.xml`: `canRetrieveWindowContent="true"` (necessário para o ditado
  achar o campo focado).
- `EyeCaptureService`: `foregroundServiceType` ganha `|microphone` (acesso ao mic em
  background, Android 11+).

## Tratamento de erro

Todos os casos: log + feedback vermelho no cursor + volta para `IDLE`. Nenhum erro derruba
o pipeline de tracking.

- Timeout sem fala; texto sem match; app não encontrado.
- "escrever"/"enviar"/"apagar" sem campo de texto focado.
- `SpeechRecognizer` indisponível/ocupado.
- Pacote de idioma não instalado no aparelho (ex.: usuário escolheu EN sem o pacote
  en-US): o recognizer falha ou exige rede — feedback de erro; solução manual é baixar o
  pacote nas configurações de voz do aparelho.

## Testes

- Unit (mesmo padrão dos existentes): `VSignDetectorTest` (fixtures de landmarks),
  `VoiceCommandParserTest` (tabela pt/en, prefixos, normalização de acentos),
  matcher do `AppLauncher`.
- Hardware: sessão do `SpeechRecognizer`, inserção de texto em apps reais, launch no
  display do DeX, seletor de idioma — validação no aparelho, como nas features
  anteriores.
