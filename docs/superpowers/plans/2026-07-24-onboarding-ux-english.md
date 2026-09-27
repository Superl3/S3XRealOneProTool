# Onboarding UX + English UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `xreal-hand-mouse` app's main screen intuitive for a first-time user: full English UI, a Setup card that auto-collapses with a checkmark once the wizard has actually succeeded, clearer DeX accessibility instructions, and new collapsible "Gestures" / "Voice commands" reference sections.

**Architecture:** Pure UI + persisted-state change on top of the existing single-Activity, XML-View (no Compose) app. No changes to the tracking/voice pipeline (`CursorPipeline.kt`, `VoiceCommand.kt`, `VoiceCommandController.kt`) — those are read-only references for writing static UI text. Reuses the exact collapse pattern already in production for the Preview card (header row outside the card + `MaterialButton` Show/Hide + `View.GONE`/`VISIBLE`, persisted in `Prefs`).

**Tech Stack:** Kotlin, AppCompatActivity, Material Components for Android (`MaterialCardView`, `MaterialButton`), Android `SharedPreferences` (via the existing `Prefs` wrapper), Gradle (`gradlew.bat`).

**Spec:** `docs/superpowers/specs/2026-07-24-onboarding-ux-english-design.md`

## Global Constraints

- All new/changed **fixed screen text** (buttons, labels, section eyebrows, status/countdown text) must be in English. Exception 1: the Log panel (`appendLog(...)` calls in `MainActivity.kt`) stays Portuguese — explicitly out of scope, confirmed with the user. Exception 2: the literal spoken voice-command phrases (e.g. "Voltar", "Abrir") stay in their own language — they are words the user must say aloud, not UI chrome; only the English description after the em-dash (e.g. "— go back") is translated UI text.
- Voice-language picker keeps native endonyms (`Português`, `English`) — do not translate these.
- No new architecture, no new dependencies, no Compose migration. Follow the existing collapse pattern exactly (see Architecture above) for every new collapsible section.
- Build verification command (run from the `xreal-hand-mouse/` directory), PowerShell:
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  .\gradlew.bat :app:assembleDebug
  ```
  (Without this `JAVA_HOME`, Gradle picks up a 32-bit Java 8 from PATH and fails to reserve heap — documented project quirk.)
- `Prefs.kt` has no unit tests today (documented in its own KDoc: not JVM-testable without Robolectric, an accepted limitation). Do not add test infrastructure for it in this plan — verification for `Prefs`/UI changes is the Gradle build + the manual QA pass in Task 7.

---

### Task 1: New `Prefs` keys for onboarding state

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/util/Prefs.kt`

**Interfaces:**
- Produces: `Prefs.setupCompleted: Boolean` (default `false`), `Prefs.setupSectionExpanded: Boolean` (default `true`), `Prefs.gesturesVisible: Boolean` (default `true`), `Prefs.voiceCommandsVisible: Boolean` (default `true`) — all read/write, backed by `SharedPreferences`, same pattern as the existing `previewVisible`/`voiceLanguage` properties. Consumed by Tasks 3, 5, 6.

- [ ] **Step 1: Add the 4 new preference keys to the companion object**

In `Prefs.kt`, find:

```kotlin
    companion object {
        private const val PREFS_NAME = "hand_mouse_prefs"
        private const val KEY_CAPTURE_ACTIVE = "capture_active"
        private const val KEY_PREVIEW_VISIBLE = "preview_visible"
        private const val KEY_VOICE_LANGUAGE = "voice_language"

        /** Valor especial de [voiceLanguage]: usa o idioma do sistema (default). */
        const val VOICE_LANGUAGE_SYSTEM = "system"
    }
```

Replace with:

```kotlin
    companion object {
        private const val PREFS_NAME = "hand_mouse_prefs"
        private const val KEY_CAPTURE_ACTIVE = "capture_active"
        private const val KEY_PREVIEW_VISIBLE = "preview_visible"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SETUP_SECTION_EXPANDED = "setup_section_expanded"
        private const val KEY_GESTURES_VISIBLE = "gestures_visible"
        private const val KEY_VOICE_COMMANDS_VISIBLE = "voice_commands_visible"

        /** Valor especial de [voiceLanguage]: usa o idioma do sistema (default). */
        const val VOICE_LANGUAGE_SYSTEM = "system"
    }
```

- [ ] **Step 2: Add the 4 new properties at the end of the class**

Find:

```kotlin
    /** Idioma do reconhecimento de voz (2026-07-23, spec voice-control): "system" (default —
     * SpeechRecognizer usa o locale do aparelho), "pt-BR" ou "en-US" (forçados via
     * RecognizerIntent.EXTRA_LANGUAGE). A tabela de comandos é bilíngue independente disto —
     * a escolha afeta só a qualidade do reconhecimento de fala livre (ditado, nomes de app). */
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, VOICE_LANGUAGE_SYSTEM) ?: VOICE_LANGUAGE_SYSTEM
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()
}
```

Replace with:

```kotlin
    /** Idioma do reconhecimento de voz (2026-07-23, spec voice-control): "system" (default —
     * SpeechRecognizer usa o locale do aparelho), "pt-BR" ou "en-US" (forçados via
     * RecognizerIntent.EXTRA_LANGUAGE). A tabela de comandos é bilíngue independente disto —
     * a escolha afeta só a qualidade do reconhecimento de fala livre (ditado, nomes de app). */
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, VOICE_LANGUAGE_SYSTEM) ?: VOICE_LANGUAGE_SYSTEM
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()

    /** true assim que o pipeline chegou a STREAMING com todas as permissões do wizard concedidas
     * pelo menos uma vez (2026-07-24, onboarding UX — ver MainActivity.onStateChanged). Nunca é
     * limpa por este design (não há "refazer setup do zero" além de reinstalar o app). */
    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    /** Visibilidade do CONTEÚDO do card de Setup (nota + os 2 botões) — não confundir com
     * [setupCompleted]. Default true (mostrado); é forçada a false UMA VEZ, no instante em que
     * [setupCompleted] vira true (auto-colapsa a primeira vez) — depois disso segue só o toggle
     * manual do usuário (MainActivity.applySetupSectionState). */
    var setupSectionExpanded: Boolean
        get() = prefs.getBoolean(KEY_SETUP_SECTION_EXPANDED, true)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_SECTION_EXPANDED, value).apply()

    /** Visibilidade do card "Gestures" (2026-07-24, onboarding UX) — conteúdo estático, sem
     * efeito colateral no serviço; default true (mostrado). */
    var gesturesVisible: Boolean
        get() = prefs.getBoolean(KEY_GESTURES_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURES_VISIBLE, value).apply()

    /** Visibilidade do card "Voice commands" (2026-07-24, onboarding UX) — default true
     * (mostrado). */
    var voiceCommandsVisible: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COMMANDS_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_COMMANDS_VISIBLE, value).apply()
}
```

- [ ] **Step 3: Verify it compiles**

Run (from `xreal-hand-mouse/`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/util/Prefs.kt
git commit -m "Add setupCompleted/setupSectionExpanded/gesturesVisible/voiceCommandsVisible prefs"
```

---

### Task 2: Translate `strings.xml` to English + add new onboarding strings

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/res/values/strings.xml` (full replacement)

**Interfaces:**
- Produces: string resources `setup_complete_label`, `setup_onetime_note`, `accessibility_steps`, `section_gestures`, `gestures_list`, `section_voice_commands`, `voice_commands_list_system`, `voice_commands_list_pt`, `voice_commands_list_en` — consumed by Tasks 3-6's layout/Kotlin. All previously-Portuguese fixed strings are now English (exact new values below), same resource names (no renames, so no other file needs touching).

- [ ] **Step 1: Replace the full file content**

Replace the entire contents of `strings.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Hand Mouse</string>
    <string name="app_subtitle">Gesture mouse for XREAL + DeX</string>
    <string name="notif_channel_capture">Eye camera capture</string>
    <string name="notif_capture_text">Capturing eye camera</string>
    <string name="status_idle">Waiting for permissions…</string>
    <string name="btn_grant_permissions">1. Grant permissions</string>
    <string name="btn_start_capture">2. Connect glasses &amp; start capture</string>
    <string name="usb_prompt_warning">Connect the glasses and accept both USB permission prompts as quickly as possible — the handshake times out if it takes too long.</string>
    <string name="btn_open_accessibility_settings">Open Accessibility Settings</string>
    <string name="accessibility_status_active">Accessibility service (DeX cursor): active</string>
    <string name="accessibility_status_inactive">Accessibility service (DeX cursor): inactive — enable it below</string>
    <string name="dex_display_status_none">DeX display: not found</string>

    <!-- Eyebrows (section labels) — uppercase by visual convention of an instrument panel -->
    <string name="section_setup">SETUP</string>
    <string name="section_cursor">CURSOR ON DEX</string>
    <string name="section_battery">SAMSUNG ANTI-KILL</string>
    <string name="btn_preview_show">Show</string>
    <string name="btn_preview_hide">Hide</string>
    <string name="section_preview">PREVIEW · TRACKING</string>
    <string name="section_log">LOG</string>

    <!-- Onboarding UX (2026-07-24): collapsible setup + accessibility steps + gestures/voice
         command reference lists. See docs/superpowers/specs/2026-07-24-onboarding-ux-english-design.md -->
    <string name="setup_complete_label">✓ Complete</string>
    <string name="setup_onetime_note">One-time setup — after granting access and connecting once, the app remembers and reconnects automatically.</string>
    <string name="accessibility_steps">1. Tap \"Open Accessibility Settings\" below\n2. Find \"Hand Mouse\" in the list\n3. Enable it and confirm</string>
    <string name="section_gestures">GESTURES</string>
    <string name="gestures_list">Pinch (thumb + index) — tap to click, hold + move to drag\nOpen palm + swipe — media control (left/right = seek, up/down = volume); cursor freezes\nClosed fist, hold 2s — recenter cursor\nThumbs up, hold 1s — hide/show cursor\nV sign, hold 0.5s — start voice command listening</string>
    <string name="section_voice_commands">VOICE COMMANDS</string>
    <string name="voice_commands_list_system">Back / Voltar — go back\nHome / Início / Close / Fechar — go to home screen\nRecents / Recentes — open recent apps\nSend / Enviar — press Enter\nClear / Apagar tudo — clear text\nOpen &lt;app name&gt; / Abrir &lt;nome do app&gt; — launch an app\nWrite &lt;text&gt; / Escrever &lt;texto&gt; — dictate text</string>
    <string name="voice_commands_list_en">Back — go back\nHome / Close — go to home screen\nRecents — open recent apps\nSend — press Enter\nClear — clear text\nOpen &lt;app name&gt; — launch an app\nWrite &lt;text&gt; — dictate text</string>
    <string name="voice_commands_list_pt">Voltar — go back\nInício / Fechar — go to home screen\nRecentes — open recent apps\nEnviar — press Enter\nApagar tudo — clear text\nAbrir &lt;nome do app&gt; — launch an app\nEscrever &lt;texto&gt; — dictate text</string>

    <!-- Tarefa 5: notificações de watchdog / reconexão USB -->
    <string name="notif_channel_watchdog">Virtual mouse alerts</string>
    <string name="notif_watchdog_title">Capture stopped</string>
    <string name="notif_watchdog_text">Tap to reopen the app and resume capture</string>
    <!-- Canal IMPORTANCE_HIGH separado do de captura (fix de revisão) — ver Javadoc de
         EyeCaptureService ("Reconexão USB") sobre por que a importância do canal importa. -->
    <string name="notif_channel_usb_detached">Glasses disconnected</string>
    <string name="notif_usb_detached_title">Glasses disconnected</string>
    <string name="notif_usb_detached_text">Tap to reconnect</string>

    <!-- Tarefa 5: card de checklist anti-kill Samsung (PLANO.md §7 item 4) — best-effort,
         versão mínima (não o checklist completo, fora de escopo desta tarefa) -->
    <string name="battery_checklist_title">To keep the app from being killed in the background on Samsung, manually check each item below:</string>
    <string name="btn_battery_app_settings">1. App battery: set to Unrestricted</string>
    <string name="btn_battery_background_usage">2. Apps that never sleep</string>
    <string name="btn_battery_device_settings">3. Device battery settings</string>

    <!-- Controle por voz (2026-07-23) -->
    <string name="voice_language_label">Voice recognition language</string>
    <string name="voice_language_system">System</string>
    <string name="voice_language_pt">Português</string>
    <string name="voice_language_en">English</string>
</resources>
```

- [ ] **Step 2: Verify the build still resolves resources**

Run (from `xreal-hand-mouse/`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` (this compiles Kotlin *and* merges/links resources, so it will catch any stray reference to a string name that no longer exists — there are none, since every original name was kept).

- [ ] **Step 3: Commit**

```bash
git add xreal-hand-mouse/app/src/main/res/values/strings.xml
git commit -m "Translate all fixed UI strings to English, add onboarding strings"
```

---

### Task 3: Setup card — collapsible with checkmark after first successful connection

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/res/layout/activity_main.xml`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt`

**Interfaces:**
- Consumes: `Prefs.setupCompleted`, `Prefs.setupSectionExpanded` (Task 1); `R.string.btn_preview_show`/`btn_preview_hide`, `R.string.setup_complete_label`, `R.string.setup_onetime_note` (Task 2).
- Produces: layout ids `setupCompleteLabel`, `btnToggleSetup`, `setupCard`, `setupOnetimeNote`; `MainActivity.applySetupSectionState(): Unit` — consumed by no other task, but is the pattern Tasks 5/6 mirror.

- [ ] **Step 1: Restructure the Setup card in `activity_main.xml`**

Find (the entire `CARD: SETUP` block):

```xml
        <!-- ===================== CARD: SETUP ===================== -->
        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            app:cardBackgroundColor="@color/hm_surface"
            app:cardCornerRadius="16dp"
            app:cardElevation="0dp"
            app:strokeColor="@color/hm_stroke"
            app:strokeWidth="1dp"
            app:contentPadding="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical">

                <TextView
                    style="@style/Hm.Eyebrow"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/section_setup" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnGrantPermissions"
                    style="@style/Hm.Button.Filled"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="@string/btn_grant_permissions" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnStartCapture"
                    style="@style/Hm.Button.Filled"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:enabled="false"
                    android:text="@string/btn_start_capture" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>
```

Replace with:

```xml
        <!-- ===================== SETUP (colapsável após 1ª conexão — 2026-07-24) ===================== -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                style="@style/Hm.Eyebrow"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/section_setup" />

            <TextView
                android:id="@+id/setupCompleteLabel"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:visibility="gone"
                android:textSize="12sp"
                android:textColor="@color/hm_accent"
                android:text="@string/setup_complete_label" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnToggleSetup"
                style="@style/Hm.Button.Outlined"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:minWidth="0dp"
                android:text="@string/btn_preview_hide" />
        </LinearLayout>

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/setupCard"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            app:cardBackgroundColor="@color/hm_surface"
            app:cardCornerRadius="16dp"
            app:cardElevation="0dp"
            app:strokeColor="@color/hm_stroke"
            app:strokeWidth="1dp"
            app:contentPadding="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/setupOnetimeNote"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginBottom="10dp"
                    android:textSize="12sp"
                    android:textColor="@color/hm_text_secondary"
                    android:lineSpacingExtra="2dp"
                    android:text="@string/setup_onetime_note" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnGrantPermissions"
                    style="@style/Hm.Button.Filled"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="@string/btn_grant_permissions" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnStartCapture"
                    style="@style/Hm.Button.Filled"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:enabled="false"
                    android:text="@string/btn_start_capture" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>
```

- [ ] **Step 2: Add the new `lateinit var`s in `MainActivity.kt`**

Find:

```kotlin
    private lateinit var btnGrantPermissions: Button
    private lateinit var btnStartCapture: Button
    private lateinit var previewImage: ImageView
```

Replace with:

```kotlin
    private lateinit var btnGrantPermissions: Button
    private lateinit var btnStartCapture: Button
    private lateinit var setupCard: View
    private lateinit var setupOnetimeNote: View
    private lateinit var setupCompleteLabel: View
    private lateinit var btnToggleSetup: Button
    private lateinit var previewImage: ImageView
```

- [ ] **Step 3: Wire the new views in `onCreate`**

Find:

```kotlin
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnStartCapture = findViewById(R.id.btnStartCapture)
        previewImage = findViewById(R.id.previewImage)
```

Replace with:

```kotlin
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnStartCapture = findViewById(R.id.btnStartCapture)
        setupCard = findViewById(R.id.setupCard)
        setupOnetimeNote = findViewById(R.id.setupOnetimeNote)
        setupCompleteLabel = findViewById(R.id.setupCompleteLabel)
        btnToggleSetup = findViewById(R.id.btnToggleSetup)
        previewImage = findViewById(R.id.previewImage)
```

- [ ] **Step 4: Add the toggle click listener**

Find:

```kotlin
        btnGrantPermissions.setOnClickListener { runPermissionWizard() }
        btnStartCapture.setOnClickListener { startCapture() }
        btnTogglePreview.setOnClickListener { setPreviewVisible(!prefs.previewVisible) }
```

Replace with:

```kotlin
        btnGrantPermissions.setOnClickListener { runPermissionWizard() }
        btnStartCapture.setOnClickListener { startCapture() }
        btnToggleSetup.setOnClickListener {
            prefs.setupSectionExpanded = !prefs.setupSectionExpanded
            applySetupSectionState()
        }
        btnTogglePreview.setOnClickListener { setPreviewVisible(!prefs.previewVisible) }
```

- [ ] **Step 5: Apply the initial state at the end of `onCreate`**

Find:

```kotlin

        updatePermissionsButtonState()
    }
```

Replace with:

```kotlin

        updatePermissionsButtonState()
        applySetupSectionState()
    }
```

- [ ] **Step 6: Add the `applySetupSectionState()` function**

Find:

```kotlin
    private fun updatePermissionsButtonState() {
        val ready = allWizardPermissionsGranted()
        btnStartCapture.isEnabled = ready
        // Só escreve o texto do wizard quando o pipeline NÃO tem estado próprio a exibir (fix
        // 2026-07-24, "status volta pra 'Permissões OK' com o stream vivo"): este método roda
        // no onCreate e em todo callback de permissão, e sobrescrevia incondicionalmente o
        // texto real ([STREAMING]/[ERROR]...) que onStateChanged mantém.
        val pipelineState = EyeCaptureService.getInstance()?.state
        if (ready && (pipelineState == null || pipelineState == EyeCaptureService.PipelineState.IDLE)) {
            statusText.text = "Permissões OK — pronto para conectar os óculos"
        }
    }

    // --- Captura ---
```

Replace with:

```kotlin
    private fun updatePermissionsButtonState() {
        val ready = allWizardPermissionsGranted()
        btnStartCapture.isEnabled = ready
        // Só escreve o texto do wizard quando o pipeline NÃO tem estado próprio a exibir (fix
        // 2026-07-24, "status volta pra 'Permissões OK' com o stream vivo"): este método roda
        // no onCreate e em todo callback de permissão, e sobrescrevia incondicionalmente o
        // texto real ([STREAMING]/[ERROR]...) que onStateChanged mantém.
        val pipelineState = EyeCaptureService.getInstance()?.state
        if (ready && (pipelineState == null || pipelineState == EyeCaptureService.PipelineState.IDLE)) {
            statusText.text = "Permissões OK — pronto para conectar os óculos"
        }
    }

    /** Aplica o estado colapsado/expandido do card de Setup (2026-07-24, onboarding UX):
     * botão Show/Hide, checkmark "✓ Complete" (só quando [Prefs.setupCompleted]) e a nota de
     * "one-time setup" (some assim que o setup completou, mesmo se o usuário reabrir o card
     * manualmente depois). Chamado no onCreate, no toggle manual e ao completar o setup pela
     * primeira vez (ver [onStateChanged]). */
    private fun applySetupSectionState() {
        val expanded = prefs.setupSectionExpanded
        setupCard.visibility = if (expanded) View.VISIBLE else View.GONE
        btnToggleSetup.text = getString(if (expanded) R.string.btn_preview_hide else R.string.btn_preview_show)
        setupCompleteLabel.visibility = if (prefs.setupCompleted) View.VISIBLE else View.GONE
        setupOnetimeNote.visibility = if (prefs.setupCompleted) View.GONE else View.VISIBLE
    }

    // --- Captura ---
```

- [ ] **Step 7: Trigger completion in `onStateChanged`**

Find:

```kotlin
    override fun onStateChanged(state: EyeCaptureService.PipelineState, message: String) {
        statusText.text = "[$state] $message"
        appendLog(message)

        // Dot da status pill: verde = streaming OK, vermelho = erro, âmbar = qualquer estado
        // transitório do handshake (mesma paleta do esqueleto/HUD).
        tintDot(statusDot, when (state) {
            EyeCaptureService.PipelineState.STREAMING -> R.color.hm_accent
            EyeCaptureService.PipelineState.ERROR -> R.color.hm_error
            else -> R.color.hm_warning
        })

        when (state) {
            EyeCaptureService.PipelineState.CAMERA_ENABLING,
            EyeCaptureService.PipelineState.REQUESTING_CAMERA_PERMISSION -> startHandshakeCountdown()
            EyeCaptureService.PipelineState.STREAMING,
            EyeCaptureService.PipelineState.ERROR -> stopHandshakeCountdown()
            else -> {}
        }
    }
```

Replace with:

```kotlin
    override fun onStateChanged(state: EyeCaptureService.PipelineState, message: String) {
        statusText.text = "[$state] $message"
        appendLog(message)

        // Dot da status pill: verde = streaming OK, vermelho = erro, âmbar = qualquer estado
        // transitório do handshake (mesma paleta do esqueleto/HUD).
        tintDot(statusDot, when (state) {
            EyeCaptureService.PipelineState.STREAMING -> R.color.hm_accent
            EyeCaptureService.PipelineState.ERROR -> R.color.hm_error
            else -> R.color.hm_warning
        })

        // Onboarding UX (2026-07-24): 1ª vez que o pipeline chega a STREAMING com as permissões
        // do wizard OK, marca o setup como completo e colapsa o card automaticamente — dali em
        // diante o usuário controla show/hide manualmente (ver applySetupSectionState).
        if (state == EyeCaptureService.PipelineState.STREAMING && !prefs.setupCompleted && allWizardPermissionsGranted()) {
            prefs.setupCompleted = true
            prefs.setupSectionExpanded = false
            applySetupSectionState()
        }

        when (state) {
            EyeCaptureService.PipelineState.CAMERA_ENABLING,
            EyeCaptureService.PipelineState.REQUESTING_CAMERA_PERMISSION -> startHandshakeCountdown()
            EyeCaptureService.PipelineState.STREAMING,
            EyeCaptureService.PipelineState.ERROR -> stopHandshakeCountdown()
            else -> {}
        }
    }
```

- [ ] **Step 8: Build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add xreal-hand-mouse/app/src/main/res/layout/activity_main.xml xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt
git commit -m "Setup card: collapsible with checkmark after first successful stream"
```

---

### Task 4: Cursor-on-DeX card — numbered accessibility instructions

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `R.string.accessibility_steps` (Task 2).
- Produces: layout id `accessibilitySteps` (no Kotlin wiring needed — static text, always visible).

- [ ] **Step 1: Insert the instructions `TextView` before the button**

Find (inside the `CURSOR NO DEX` card):

```xml
                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnOpenAccessibilitySettings"
                    style="@style/Hm.Button.Outlined"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:text="@string/btn_open_accessibility_settings" />
```

Replace with:

```xml
                <TextView
                    android:id="@+id/accessibilitySteps"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:textSize="12sp"
                    android:textColor="@color/hm_text_secondary"
                    android:lineSpacingExtra="2dp"
                    android:text="@string/accessibility_steps" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnOpenAccessibilitySettings"
                    style="@style/Hm.Button.Outlined"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:text="@string/btn_open_accessibility_settings" />
```

- [ ] **Step 2: Build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add xreal-hand-mouse/app/src/main/res/layout/activity_main.xml
git commit -m "Cursor-on-DeX card: add numbered accessibility setup steps"
```

---

### Task 5: New "Gestures" collapsible card

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/res/layout/activity_main.xml`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt`

**Interfaces:**
- Consumes: `Prefs.gesturesVisible` (Task 1); `R.string.section_gestures`, `R.string.gestures_list`, `R.string.btn_preview_show`/`btn_preview_hide` (Task 2).
- Produces: layout ids `gesturesCard`, `btnToggleGestures`; `MainActivity.setGesturesVisible(visible: Boolean): Unit`. Task 6 anchors its own edits on the exact text this task inserts, so this task must land first.

- [ ] **Step 1: Insert the Gestures header + card in `activity_main.xml`**

Find (boundary between the Cursor-on-DeX card and the Battery card — unaffected by Task 4's edit, which only touches lines *inside* the Cursor card):

```xml
        </com.google.android.material.card.MaterialCardView>

        <!-- ===================== CARD: BATERIA (SAMSUNG) ===================== -->
```

Replace with:

```xml
        </com.google.android.material.card.MaterialCardView>

        <!-- ===================== GESTURES (colapsável — 2026-07-24) ===================== -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="8dp"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                style="@style/Hm.Eyebrow"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/section_gestures" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnToggleGestures"
                style="@style/Hm.Button.Outlined"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:minWidth="0dp"
                android:text="@string/btn_preview_hide" />
        </LinearLayout>

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/gesturesCard"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:cardBackgroundColor="@color/hm_surface"
            app:cardCornerRadius="16dp"
            app:cardElevation="0dp"
            app:strokeColor="@color/hm_stroke"
            app:strokeWidth="1dp"
            app:contentPadding="16dp">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="13sp"
                android:textColor="@color/hm_text_primary"
                android:lineSpacingExtra="4dp"
                android:text="@string/gestures_list" />
        </com.google.android.material.card.MaterialCardView>

        <!-- ===================== CARD: BATERIA (SAMSUNG) ===================== -->
```

- [ ] **Step 2: Add the new `lateinit var`s in `MainActivity.kt`**

Find:

```kotlin
    private lateinit var accessibilityStatusText: TextView
    private lateinit var btnOpenAccessibilitySettings: Button
    private lateinit var dexDisplayStatusText: TextView
    private lateinit var voiceLangGroup: RadioGroup
```

Replace with:

```kotlin
    private lateinit var accessibilityStatusText: TextView
    private lateinit var btnOpenAccessibilitySettings: Button
    private lateinit var dexDisplayStatusText: TextView
    private lateinit var voiceLangGroup: RadioGroup
    private lateinit var gesturesCard: View
    private lateinit var btnToggleGestures: Button
```

- [ ] **Step 3: Wire the new views in `onCreate`**

Find:

```kotlin
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
        dexDisplayStatusText = findViewById(R.id.dexDisplayStatusText)
        voiceLangGroup = findViewById(R.id.voiceLangGroup)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        previewCard = findViewById(R.id.previewCard)
```

Replace with:

```kotlin
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
        dexDisplayStatusText = findViewById(R.id.dexDisplayStatusText)
        voiceLangGroup = findViewById(R.id.voiceLangGroup)
        gesturesCard = findViewById(R.id.gesturesCard)
        btnToggleGestures = findViewById(R.id.btnToggleGestures)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        previewCard = findViewById(R.id.previewCard)
```

- [ ] **Step 4: Add the toggle click listener + apply initial state**

Find:

```kotlin
        btnOpenAccessibilitySettings.setOnClickListener {
            appendLog("Abrindo configurações de acessibilidade")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
```

Replace with:

```kotlin
        btnOpenAccessibilitySettings.setOnClickListener {
            appendLog("Abrindo configurações de acessibilidade")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnToggleGestures.setOnClickListener { setGesturesVisible(!prefs.gesturesVisible) }
        setGesturesVisible(prefs.gesturesVisible)
```

- [ ] **Step 5: Add the `setGesturesVisible` function**

Find:

```kotlin
    private fun setPreviewVisible(visible: Boolean) {
        prefs.previewVisible = visible
        previewCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnTogglePreview.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
        val service = EyeCaptureService.getInstance() ?: return
        // previewFramesEnabled: gate de alocação por frame no serviço (ver Javadoc lá) — segue
        // a visibilidade do preview 1:1.
        service.previewFramesEnabled = visible
        if (visible) service.addTrackingListener(this) else service.removeTrackingListener(this)
    }

    // --- EyeCaptureService.StateListener ---
```

Replace with:

```kotlin
    private fun setPreviewVisible(visible: Boolean) {
        prefs.previewVisible = visible
        previewCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnTogglePreview.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
        val service = EyeCaptureService.getInstance() ?: return
        // previewFramesEnabled: gate de alocação por frame no serviço (ver Javadoc lá) — segue
        // a visibilidade do preview 1:1.
        service.previewFramesEnabled = visible
        if (visible) service.addTrackingListener(this) else service.removeTrackingListener(this)
    }

    /** Colapsa/expande o card de gestos (2026-07-24, onboarding UX) — mesmo padrão do preview,
     * sem efeito colateral no serviço (é conteúdo estático). */
    private fun setGesturesVisible(visible: Boolean) {
        prefs.gesturesVisible = visible
        gesturesCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnToggleGestures.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
    }

    // --- EyeCaptureService.StateListener ---
```

- [ ] **Step 6: Build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add xreal-hand-mouse/app/src/main/res/layout/activity_main.xml xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt
git commit -m "Add collapsible Gestures reference card"
```

---

### Task 6: New "Voice commands" collapsible card, language-aware

**Files:**
- Modify: `xreal-hand-mouse/app/src/main/res/layout/activity_main.xml`
- Modify: `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt`

**Interfaces:**
- Consumes: `Prefs.voiceCommandsVisible`, `Prefs.voiceLanguage` (existing); `R.string.section_voice_commands`, `R.string.voice_commands_list_system/pt/en` (Task 2); the exact text Task 5 inserted (anchor for this task's edits — Task 5 must land first).
- Produces: layout ids `voiceCommandsCard`, `btnToggleVoiceCommands`, `voiceCommandsText`; `MainActivity.setVoiceCommandsVisible(visible: Boolean): Unit`, `MainActivity.updateVoiceCommandsText(): Unit`.

- [ ] **Step 1: Insert the Voice Commands header + card in `activity_main.xml`**

Find (this exact sequence now appears after the Gestures card that Task 5 just inserted, not after the Cursor card as before):

```xml
        </com.google.android.material.card.MaterialCardView>

        <!-- ===================== CARD: BATERIA (SAMSUNG) ===================== -->
```

Replace with:

```xml
        </com.google.android.material.card.MaterialCardView>

        <!-- ===================== VOICE COMMANDS (colapsável, sensível ao idioma — 2026-07-24) ===================== -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="8dp"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                style="@style/Hm.Eyebrow"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/section_voice_commands" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnToggleVoiceCommands"
                style="@style/Hm.Button.Outlined"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:minWidth="0dp"
                android:text="@string/btn_preview_hide" />
        </LinearLayout>

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/voiceCommandsCard"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:cardBackgroundColor="@color/hm_surface"
            app:cardCornerRadius="16dp"
            app:cardElevation="0dp"
            app:strokeColor="@color/hm_stroke"
            app:strokeWidth="1dp"
            app:contentPadding="16dp">

            <TextView
                android:id="@+id/voiceCommandsText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="13sp"
                android:textColor="@color/hm_text_primary"
                android:lineSpacingExtra="4dp"
                android:text="@string/voice_commands_list_system" />
        </com.google.android.material.card.MaterialCardView>

        <!-- ===================== CARD: BATERIA (SAMSUNG) ===================== -->
```

- [ ] **Step 2: Add the new `lateinit var`s in `MainActivity.kt`**

Find:

```kotlin
    private lateinit var gesturesCard: View
    private lateinit var btnToggleGestures: Button
```

Replace with:

```kotlin
    private lateinit var gesturesCard: View
    private lateinit var btnToggleGestures: Button
    private lateinit var voiceCommandsCard: View
    private lateinit var btnToggleVoiceCommands: Button
    private lateinit var voiceCommandsText: TextView
```

- [ ] **Step 3: Wire the new views in `onCreate`**

Find:

```kotlin
        gesturesCard = findViewById(R.id.gesturesCard)
        btnToggleGestures = findViewById(R.id.btnToggleGestures)
```

Replace with:

```kotlin
        gesturesCard = findViewById(R.id.gesturesCard)
        btnToggleGestures = findViewById(R.id.btnToggleGestures)
        voiceCommandsCard = findViewById(R.id.voiceCommandsCard)
        btnToggleVoiceCommands = findViewById(R.id.btnToggleVoiceCommands)
        voiceCommandsText = findViewById(R.id.voiceCommandsText)
```

- [ ] **Step 4: Add the toggle click listener + apply initial state**

Find:

```kotlin
        btnToggleGestures.setOnClickListener { setGesturesVisible(!prefs.gesturesVisible) }
        setGesturesVisible(prefs.gesturesVisible)
```

Replace with:

```kotlin
        btnToggleGestures.setOnClickListener { setGesturesVisible(!prefs.gesturesVisible) }
        setGesturesVisible(prefs.gesturesVisible)

        btnToggleVoiceCommands.setOnClickListener { setVoiceCommandsVisible(!prefs.voiceCommandsVisible) }
        setVoiceCommandsVisible(prefs.voiceCommandsVisible)
        updateVoiceCommandsText()
```

- [ ] **Step 5: Refresh the text when the voice-language radio changes**

Find:

```kotlin
        voiceLangGroup.setOnCheckedChangeListener { _, id ->
            prefs.voiceLanguage = when (id) {
                R.id.voiceLangPt -> "pt-BR"
                R.id.voiceLangEn -> "en-US"
                else -> com.raphael.handmouse.util.Prefs.VOICE_LANGUAGE_SYSTEM
            }
            appendLog("Idioma do reconhecimento de voz: ${prefs.voiceLanguage}")
        }
```

Replace with:

```kotlin
        voiceLangGroup.setOnCheckedChangeListener { _, id ->
            prefs.voiceLanguage = when (id) {
                R.id.voiceLangPt -> "pt-BR"
                R.id.voiceLangEn -> "en-US"
                else -> com.raphael.handmouse.util.Prefs.VOICE_LANGUAGE_SYSTEM
            }
            appendLog("Idioma do reconhecimento de voz: ${prefs.voiceLanguage}")
            updateVoiceCommandsText()
        }
```

- [ ] **Step 6: Add `setVoiceCommandsVisible` and `updateVoiceCommandsText`**

Find:

```kotlin
    private fun setGesturesVisible(visible: Boolean) {
        prefs.gesturesVisible = visible
        gesturesCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnToggleGestures.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
    }

    // --- EyeCaptureService.StateListener ---
```

Replace with:

```kotlin
    private fun setGesturesVisible(visible: Boolean) {
        prefs.gesturesVisible = visible
        gesturesCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnToggleGestures.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
    }

    /** Colapsa/expande o card de comandos de voz (2026-07-24, onboarding UX) — mesmo padrão do
     * card de gestos. */
    private fun setVoiceCommandsVisible(visible: Boolean) {
        prefs.voiceCommandsVisible = visible
        voiceCommandsCard.visibility = if (visible) View.VISIBLE else View.GONE
        btnToggleVoiceCommands.text =
            getString(if (visible) R.string.btn_preview_hide else R.string.btn_preview_show)
    }

    /** Troca o texto do card de comandos de voz conforme [Prefs.voiceLanguage] (2026-07-24):
     * "system" mostra as duas formas (pt+en), "pt-BR"/"en-US" mostram só a forma do idioma
     * escolhido — o parser ([com.raphael.handmouse.input.VoiceCommandParser]) sempre aceita as
     * duas independente desta escolha (que só afeta a qualidade do reconhecimento de fala), mas
     * a LISTA exibida reflete o idioma selecionado pra não confundir o usuário com frases que
     * ele não pediu pra usar. */
    private fun updateVoiceCommandsText() {
        voiceCommandsText.text = getString(
            when (prefs.voiceLanguage) {
                "pt-BR" -> R.string.voice_commands_list_pt
                "en-US" -> R.string.voice_commands_list_en
                else -> R.string.voice_commands_list_system
            }
        )
    }

    // --- EyeCaptureService.StateListener ---
```

- [ ] **Step 7: Build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add xreal-hand-mouse/app/src/main/res/layout/activity_main.xml xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/MainActivity.kt
git commit -m "Add collapsible Voice Commands card, language-aware content"
```

---

### Task 7: Full verification — unit tests, build, manual QA on device

**Files:** none (verification only)

**Interfaces:** none.

- [ ] **Step 1: Run the existing unit test suite (regression check)**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` — none of these tasks touched `CursorPipeline.kt`, `VoiceCommand.kt`, or any detector, so every existing test (`VoiceCommandParserTest`, `FistDetectorTest`, `ThumbsUpDetectorTest`, `VSignDetectorTest`, etc.) must still pass unchanged.

- [ ] **Step 2: Full debug build**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, produces `app\build\outputs\apk\debug\app-debug.apk`.

- [ ] **Step 3: Install on the connected device**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <DEVICE-IP>:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```

(IP may have changed since it was last recorded — check `adb devices` if this fails to connect.)

- [ ] **Step 4: Manual QA checklist (requires the phone unlocked — screencap is blocked on the keyguard, so this must be eyeballed live, not screenshotted)**

Ask the user to unlock the phone, open the app, and confirm:

1. Every visible label/button/status reads in English (Setup, Cursor on DeX, Gestures, Voice commands, Samsung anti-kill, Preview, Log eyebrows; both Setup buttons; the accessibility button; the battery checklist).
2. On a fresh app (or after clearing app data), the Setup card is **expanded** and shows the one-time note above the two buttons; no checkmark is visible.
3. After successfully granting permissions, connecting the glasses, and reaching a live video stream, the Setup card **auto-collapses** to a single "SETUP ✓ Complete" header row.
4. Closing and reopening the app keeps the Setup card collapsed (state persisted).
5. Tapping "Show" on the collapsed Setup header re-expands it (buttons still present and functional, no onetime note reappears).
6. The Cursor-on-DeX card shows the 3 numbered steps above the "Open Accessibility Settings" button.
7. The Gestures card lists all 5 gestures and can be collapsed/expanded via its own Show/Hide button.
8. The Voice Commands card lists all 7 commands; switching the voice-language radio between System/Português/English changes the card's text immediately to match (System shows both languages, Português shows only Portuguese phrasing, English shows only English phrasing — all description text after the em-dash stays in English in every case).
9. No regression in existing functionality: cursor still moves/clicks/drags on DeX, media gestures still work, voice commands still work, battery checklist buttons still open their settings screens, Preview toggle still works.

- [ ] **Step 5: Report results to the user**

Summarize pass/fail for each checklist item above before considering the feature done. Do not mark the overall task complete if any item fails — fix and re-verify.
