# Onboarding intuitivo + UI 100% inglês — design (2026-07-24)

## Problema

O app funciona, mas é hostil a quem nunca usou:
1. Toda a UI mistura português/inglês sem critério.
2. O card de Setup ("Conceder permissões" + "Conectar óculos e iniciar captura") não deixa
   claro que esses 2 passos só precisam ser feitos **uma vez** (até reinstalar) — quem abre o
   app na 2ª vez não sabe se precisa clicar de novo.
3. O card "Cursor no DeX" só tem um dot + botão "abrir configurações de acessibilidade" — não
   explica o que fazer DENTRO da tela de Acessibilidade do Android (Google não deixa ativar
   serviço de acessibilidade por Intent direto, é sempre manual).
4. Não existe, em lugar nenhum da UI, uma lista dos gestos e comandos de voz suportados — quem
   nunca usou não tem como descobrir sem ler o código.

## Escopo

- **Dentro**: strings.xml (textos fixos: botões, labels, eyebrows, status, countdown),
  `Prefs.kt` (2 chaves novas), `MainActivity.kt` (lógica de setup completo + instruções),
  `activity_main.xml` (2 cards novos colapsáveis + bloco de instruções no card do DeX).
- **Fora**: mensagens do painel de LOG (`appendLog(...)`, ~15 chamadas hardcoded em português
  no Kotlin) continuam em português — são diagnóstico técnico, não onboarding; usuário
  confirmou explicitamente que este escopo fica de fora. Comentários/Javadoc no código
  continuam em português (convenção do projeto, não é UI).

## 1. Tradução para inglês

Todas as strings fixas em `strings.xml` viram inglês. Nomes de idioma no seletor de voz mantêm
o endônimo nativo (`Português`/`English`) — convenção padrão de seletor de idioma mesmo em UI
já traduzida (ex.: iOS mantém "Português" num picker em inglês). Tabela completa de
antes/depois na seção "Novas strings" abaixo — nenhuma string existente fica em português.

## 2. Setup: uma vez só, com checkmark colapsável

### Novo estado persistido (`Prefs.kt`)

```kotlin
/** true assim que o pipeline chegou a STREAMING com todas as permissões do wizard concedidas
 * — nunca mais false (não há "desfazer setup" nesta versão). */
var setupCompleted: Boolean
    get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

/** Visibilidade do CONTEÚDO do card de Setup (os 2 botões + nota). Default true (mostrado) —
 * só é forçado a false UMA VEZ, no instante em que setupCompleted vira true (ver
 * MainActivity.onStateChanged). Depois disso segue 100% o toggle manual do usuário. */
var setupSectionExpanded: Boolean
    get() = prefs.getBoolean(KEY_SETUP_SECTION_EXPANDED, true)
    set(value) = prefs.edit().putBoolean(KEY_SETUP_SECTION_EXPANDED, value).apply()
```

### Trigger de conclusão (`MainActivity.onStateChanged`)

Quando `state == STREAMING` e `!prefs.setupCompleted` e `allWizardPermissionsGranted()`:
marca `prefs.setupCompleted = true`, `prefs.setupSectionExpanded = false` (auto-colapsa UMA
vez) e chama `applySetupSectionState()` (atualiza a UI na hora, sem esperar reabrir o app).

### UI do card de Setup (`activity_main.xml` + `MainActivity.kt`)

Mesmo padrão do card de Preview (header clicável + botão Show/Hide):
- Header vira uma linha horizontal: eyebrow "SETUP" (peso 1) + `TextView` de checkmark
  (`"✓ Complete"`, só visível quando `setupCompleted`) + `MaterialButton` "Show"/"Hide"
  (`btnToggleSetup`, mesmo texto/ids do `btnTogglePreview`).
- Conteúdo do card (nota explicativa + os 2 botões) dentro de um `View` cuja visibilidade seque
  `setupSectionExpanded` (`applySetupSectionState()`, análogo a `setPreviewVisible`).
- Nota explicativa nova, só aparece **antes** de `setupCompleted` (dentro do conteúdo
  expandido): *"One-time setup — after granting access and connecting once, the app remembers
  and reconnects automatically."*
- Quando colapsado, o card continua existindo (não some da árvore) — só o conteúdo interno
  fica `GONE`; a linha de header com o checkmark sempre visível é o que dá o feedback "setup
  ok" sem precisar expandir.

## 3. Cursor no DeX: passo a passo de acessibilidade

Acima do botão `btnOpenAccessibilitySettings`, novo `TextView` (string
`accessibility_steps`, sempre visível, mesmo quando o serviço já está ativo — não atrapalha e
serve de referência caso o usuário desative sem querer):

```
1. Tap "Open Accessibility Settings" below
2. Find "Hand Mouse" in the list
3. Enable it and confirm
```

Textos de status (`accessibility_status_active/inactive`) reescritos em inglês mais direto
(ver seção "Novas strings").

## 4. Novo card "Gestures" (colapsável, expandido por padrão)

Posição: logo depois do card "Cursor no DeX", antes de "Voice commands". Mesmo padrão de
header clicável (eyebrow "GESTURES" + botão Show/Hide, `btnToggleGestures`), persistido em
`Prefs.gesturesVisible` (default `true`).

Conteúdo — lista estática (`string/gestures_list`), extraída 1:1 dos detectores reais em
`CursorPipeline.kt` (não é aspiracional, é o que o pipeline já faz hoje):

```
Pinch (thumb + index) — tap to click, hold + move to drag
Open palm + swipe — media control (left/right = seek, up/down = volume); cursor freezes
Closed fist, hold 2s — recenter cursor
Thumbs up, hold 1s — hide/show cursor
V sign, hold 0.5s — start voice command listening
```

## 5. Novo card "Voice commands" (colapsável, expandido por padrão, sensível ao idioma)

Posição: logo depois de "Gestures". Mesmo padrão de header (eyebrow "VOICE COMMANDS" + botão
Show/Hide, `btnToggleVoiceCommands`), persistido em `Prefs.voiceCommandsVisible` (default
`true`).

Conteúdo depende do `RadioGroup` de idioma de voz já existente (`voiceLangGroup`) — 3 blocos de
string estáticos, extraídos da tabela real em `VoiceCommand.kt` (`EXACT` map + verbos
`abrir`/`escrever`):

- `voice_commands_list_system` (idioma = Sistema): mostra as duas formas juntas.
- `voice_commands_list_pt` (idioma = Português): só a forma em português.
- `voice_commands_list_en` (idioma = English): só a forma em inglês.

Exemplo do bloco `system`:

```
Back / Voltar — go back
Home / Início / Fechar — go to home screen
Recents / Recentes — open recent apps
Send / Enviar — press Enter
Clear / Apagar tudo — clear text
Open <app name> / Abrir <nome do app> — launch an app
Write <text> / Escrever <texto> — dictate text
```

`MainActivity.kt` já tem o listener do `voiceLangGroup` (linha ~190) — adiciona ali a troca do
texto do `TextView` do card de Voice Commands (mesmo `when` que já decide `prefs.voiceLanguage`,
sem lógica nova de parsing). Uma função `updateVoiceCommandsText()` chamada no listener e no
`onCreate` (pra refletir o valor persistido ao abrir o app).

## Riscos e não-riscos

- Nenhuma mudança de comportamento do pipeline de tracking/voz — só UI e persistência de
  estado de exibição. Risco de regressão é baixo.
- `setupCompleted` nunca é limpo por este design (não há "refazer setup do zero" além de
  reinstalar o app, que já limpa `SharedPreferences`) — combinado com o usuário, fora de
  escopo.
- Se `EyeCaptureService` nunca chegar a `STREAMING` (ex.: óculos com defeito), o card de Setup
  nunca colapsa sozinho — comportamento correto (setup não terminou de verdade).
