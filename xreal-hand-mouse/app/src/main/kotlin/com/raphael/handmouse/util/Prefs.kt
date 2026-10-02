package com.raphael.handmouse.util

import android.content.Context
import android.content.SharedPreferences
import com.raphael.handmouse.tracking.HandSettings

/**
 * `SharedPreferences` tipadas — flag "captura ativa" (usada pelo watchdog do
 * [com.raphael.handmouse.service.HandMouseAccessibilityService] — ver Javadoc dele). Os
 * thresholds do pinch ([com.raphael.handmouse.tracking.PinchDetector]) NÃO são configuráveis por
 * decisão explícita do brief — permanecem constantes de compilação, nada aqui os expõe.
 *
 * A calibração de 2 pontos que vivia aqui foi APOSENTADA em 2026-07-23 junto com o mapeamento
 * absoluto — o cursor agora é relativo ([com.raphael.handmouse.tracking.RelativeCursorMapper],
 * modo trackpad, zero configuração). As chaves `calibration_box_*` de instalações antigas ficam
 * órfãs no XML — inofensivas, ignoradas.
 *
 * Não testável em JVM puro sem Robolectric (depende de `Context`/`SharedPreferences` reais) —
 * mesma limitação já aceita nas Tarefas 2-4 pra classes equivalentes.
 */
class Prefs(context: Context) {

    companion object {
        private const val KEY_CAPTURE_ACTIVE = "capture_active"
        private const val KEY_PREVIEW_VISIBLE = "preview_visible"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SETUP_SECTION_EXPANDED = "setup_section_expanded"
        private const val KEY_USAGE_VISIBLE = "usage_visible"

    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    init {
        // This fork previously defaulted to 10-minute files. Move existing installs to the
        // requested 30-minute size once, while preserving an explicit 30/60-minute choice.
        if (!prefs.getBoolean("rec_split_30_migrated", false)) {
            val old = prefs.getString(KEY_REC_SPLIT, null)
            val editor = prefs.edit().putBoolean("rec_split_30_migrated", true)
            if (old == null || old == "10") editor.putString(KEY_REC_SPLIT, "30")
            editor.apply()
        }
    }

    /** Preview da câmera + esqueleto na MainActivity visível? Default FALSE (recolhido) de
     * propósito (2026-07-23, pedido do usuário): com o preview recolhido a Activity se
     * DESREGISTRA do fluxo de frames (`removeTrackingListener`) — zero trabalho de UI por frame
     * (setImageBitmap + redraw do esqueleto a ~30fps). A conversão RGBA em si continua no
     * serviço (o MediaPipe precisa dela) — a economia é só o lado da UI, mas é real. */
    var previewVisible: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_VISIBLE, false)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEW_VISIBLE, value).apply()

    /** "O usuário/sistema pretende que a captura esteja rodando" — setada em
     * `EyeCaptureService.start()`, consultada pelo watchdog do a11y service. NUNCA limpa em
     * `EyeCaptureService.onDestroy()` de propósito: um `onDestroy()` inesperado (kill do sistema)
     * é exatamente o cenário que o watchdog existe pra detectar e recuperar — limpar a flag ali
     * quebraria essa detecção. Este app não tem hoje um botão "parar captura" explícito na UI
     * (ver MainActivity) — uma vez iniciada, a intenção normal é permanecer ativa.
     *
     * **Fix de revisão (achado Important I6)**: só é limpa por `EyeCaptureService.stop()` (a
     * PARADA INTENCIONAL — espelha o `= true` de `start()`), que hoje não tem chamador em
     * produção mas precisa estar correto pra qualquer uso futuro; se ficasse quebrado (setando
     * `true` sem nunca setar `false` em NENHUM caminho), o companion `stop()` seria uma
     * "armadilha de ressurreição": chamar `stop()` pararia o serviço, mas o watchdog do a11y
     * service continuaria vendo `captureActive=true` e reiniciaria a captura sozinho segundos
     * depois — o "parar" nunca pegaria de verdade. */
    var captureActive: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_ACTIVE, value).apply()

    /** Recognition language is Korean or English. Older saved values fall back to the phone
     * language when Korean, and to English otherwise. */
    var voiceLanguage: String
        get() = when (prefs.getString(KEY_VOICE_LANGUAGE, null)) {
            "ko-KR" -> "ko-KR"
            "en-US" -> "en-US"
            else -> if (java.util.Locale.getDefault().language == "ko") "ko-KR" else "en-US"
        }
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, if (value == "ko-KR") "ko-KR" else "en-US").apply()

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

    /** Visibilidade do card "Usage" (gestos + comandos de voz, 2026-09-30) — conteúdo estático,
     * sem efeito colateral no serviço; default FALSE (recolhido). Substitui as chaves
     * `gestures_visible`/`voice_commands_visible` (default true), que ficam órfãs no arquivo. */
    var usageVisible: Boolean
        get() = prefs.getBoolean(KEY_USAGE_VISIBLE, false)
        set(value) = prefs.edit().putBoolean(KEY_USAGE_VISIBLE, value).apply()

    // ================= Eye Tools fork: settings screen (res/xml/preferences.xml) =================
    // Keys are shared with SettingsActivity (PreferenceFragmentCompat on the same prefs file).
    // SeekBar/Switch preferences store Int/Boolean; ListPreference stores String.

    val raw: SharedPreferences get() = prefs

    // ---- Hand mouse ----
    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()

    /** Ignore hands in the bottom N % of the camera image (0 = off) — applied by the capture
     * service, before a hand counts as seen. */
    val ignoreBottomPct: Int get() = prefs.getInt(KEY_IGNORE_BOTTOM, 0)

    /** Camera anti-flicker / exposure ([com.raphael.handmouse.capture.UvcCameraHelper.CameraControls]). */
    val cameraControls: com.raphael.handmouse.capture.UvcCameraHelper.CameraControls get() =
        com.raphael.handmouse.capture.UvcCameraHelper.CameraControls(
            powerLineFrequency = (prefs.getString(KEY_CAM_ANTI_FLICKER, "-1") ?: "-1").toIntOrNull() ?: -1,
            manualExposure100us = (prefs.getString(KEY_CAM_EXPOSURE, "0") ?: "0").toIntOrNull() ?: 0,
        )

    /** Write every hand-tracking result to a JSONL file ([com.raphael.handmouse.recording.LandmarkLog]). */
    val landmarkLog: Boolean get() = prefs.getBoolean(KEY_LANDMARK_LOG, false)

    /** All hand-mouse tuning, resolved in one place (defaults = preferences.xml). */
    fun handSettings(): HandSettings = HandSettings.fromUi(
        sensitivityPct = prefs.getInt(KEY_CURSOR_SENSITIVITY, 100),
        pinch = prefs.getInt(KEY_PINCH_SENSITIVITY, 50),
        smoothing = prefs.getInt(KEY_SMOOTHING, 50),
        deadZoneTenthsPx = prefs.getInt(KEY_DEAD_ZONE, 15),
        holdMs = prefs.getInt(KEY_PINCH_HOLD_MS, 400),
        debounceFrames = prefs.getInt(KEY_CLICK_DEBOUNCE, 3),
        palmMenu = prefs.getBoolean(KEY_PALM_MENU, true),
        magneticClick = prefs.getBoolean(KEY_MAGNETIC_CLICK, true),
        debugOverlay = prefs.getBoolean(KEY_DEBUG_OVERLAY, false),
        fistRecenter = prefs.getBoolean(KEY_FIST_RECENTER, false),
        thumbsUpMute = prefs.getBoolean(KEY_THUMBS_UP_MUTE, true),
        vSignVoice = prefs.getBoolean(KEY_V_SIGN_VOICE, false),
        worldPinch = prefs.getBoolean(KEY_WORLD_PINCH, false),
        layerStepThousandths = prefs.getInt(KEY_LAYER_STEP, 45),
        gestureHints = prefs.getBoolean(KEY_GESTURE_HINTS, true),
        fistTouch = prefs.getBoolean(KEY_FIST_TOUCH, false),
        headCompensation = headCompensation,
        headCalibration = headCalibration,
        edgeBlock = prefs.getBoolean(KEY_EDGE_BLOCK, false),
    )

    /** Cursor head-motion compensation (off by default; no effect without [headCalibration]). */
    val headCompensation: Boolean get() = prefs.getBoolean(KEY_HEAD_COMP, false)

    /** IMU→camera fit written by the enhancer ([com.raphael.handmouse.imu.ImuCameraCalibration]). */
    var headCalibration: com.raphael.handmouse.imu.ImuCameraCalibration?
        get() = com.raphael.handmouse.imu.ImuCameraCalibration.decode(prefs.getString(KEY_HEAD_CALIBRATION, null))
        set(value) = prefs.edit().putString(KEY_HEAD_CALIBRATION, value?.encode()).apply()

    /** Dataset label while collecting gesture images ([com.raphael.handmouse.recording.DatasetRecorder]); null = off. */
    val datasetLabel: String? get() = (prefs.getString(KEY_DATASET_LABEL, DATASET_OFF) ?: DATASET_OFF).takeIf { it != DATASET_OFF }

    // ---- Eye recorder ----
    /** Stream used while recording: "mjpeg" (default — on current firmware the UVC "HEVC" modes also deliver JPEG), "hevc_1080" or "hevc_native". */
    val recordStream: String get() = prefs.getString(KEY_REC_STREAM, REC_STREAM_MJPEG) ?: REC_STREAM_MJPEG
    /** Recording frame-rate cap: 30 (default, half the size) or 60. */
    val recordFps: Int get() = (prefs.getString(KEY_REC_FPS, "30") ?: "30").toIntOrNull() ?: 30
    val recordAudio: Boolean get() = (prefs.getString(KEY_REC_AUDIO, "off") ?: "off") == "mic"
    val recordSplitMinutes: Int get() = (prefs.getString(KEY_REC_SPLIT, "30") ?: "30").toIntOrNull() ?: 30
    /** "gallery" (Movies/XrealEye, default) or "app" (app-private folder). */
    val recordStorage: String get() = prefs.getString(KEY_REC_STORAGE, "gallery") ?: "gallery"
    val recordAutoStart: Boolean get() = prefs.getBoolean(KEY_REC_AUTO_START, false)
    /** Gyroflow `.gcsv` of the glasses' IMU next to every recording (on by default). */
    val recordGyroLog: Boolean get() = prefs.getBoolean(KEY_REC_GYRO_LOG, true)
    /** The enhancer deletes the MKV (and its `.gcsv`) once the MP4 checked out (on by default). */
    val enhanceDeleteOriginal: Boolean get() = prefs.getBoolean(KEY_ENHANCE_DELETE_ORIGINAL, true)
    /** Height of the enhanced MP4, px: 720 (default) or 1080 (the recording's own size). */
    val enhanceOutputHeight: Int get() = (prefs.getString(KEY_ENHANCE_HEIGHT, "720") ?: "720").toIntOrNull() ?: 720

    // ---- Display ----
    val dimAuto: Boolean get() = prefs.getBoolean(KEY_DIM_AUTO, true)
    val dimMaxPct: Int get() = prefs.getInt(KEY_DIM_MAX, 60)
    val dimStartLux: Int get() = prefs.getInt(KEY_DIM_START_LUX, 20)

    // ---- Background ----
    /** Manual connection (default): capture starts and stops only from the Connect / Disconnect
     * buttons (or a tile / shortcut). No reconnect on plug-in, no watchdog, no background retry
     * loop; unplugging ends the session. */
    val manualConnect: Boolean get() = prefs.getBoolean(KEY_CONN_MANUAL, true)
    val keepAlive: Boolean get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
    val startOnConnect: Boolean get() = prefs.getBoolean(KEY_START_ON_CONNECT, true)
}

// Button assignments (Eye Tools fork): Android key codes captured by "learn" mode; 0 = none.
const val KEY_BTN_RECORD = "btn_record"
const val KEY_BTN_PHOTO = "btn_photo"
const val KEY_BTN_TRACKING = "btn_tracking"

// Top-level so SettingsActivity/services can reference them without an instance.
const val KEY_TRACKING_ENABLED = "hm_tracking_enabled"
const val KEY_CURSOR_SENSITIVITY = "hm_cursor_sensitivity"
const val KEY_PINCH_SENSITIVITY = "hm_pinch_sensitivity"
const val KEY_SMOOTHING = "hm_smoothing"
const val KEY_DEAD_ZONE = "hm_dead_zone"
const val KEY_PINCH_HOLD_MS = "hm_pinch_hold_ms"
const val KEY_CLICK_DEBOUNCE = "hm_click_debounce"
const val KEY_PALM_MENU = "hm_palm_menu"
const val KEY_MAGNETIC_CLICK = "hm_magnetic_click"
const val KEY_DEBUG_OVERLAY = "hm_debug_overlay"
const val KEY_FIST_RECENTER = "hm_fist_recenter"
const val KEY_FIST_TOUCH = "hm_fist_touch"
const val KEY_HEAD_COMP = "hm_head_comp"
/** "hm_" so the accessibility service re-applies the hand settings when the enhancer stores a fit. */
const val KEY_HEAD_CALIBRATION = "hm_head_calibration"
const val KEY_DATASET_LABEL = "hm_dataset_label"
const val DATASET_OFF = "off"
const val KEY_REC_GYRO_LOG = "rec_gyro_log"
const val KEY_ENHANCE_DELETE_ORIGINAL = "rec_enhance_delete_original"
const val KEY_ENHANCE_HEIGHT = "rec_enhance_height"
const val KEY_THUMBS_UP_MUTE = "hm_thumbs_up_mute"
const val KEY_V_SIGN_VOICE = "hm_v_sign_voice"
const val KEY_IGNORE_BOTTOM = "hm_ignore_bottom"
const val KEY_WORLD_PINCH = "hm_world_pinch"
const val KEY_LANDMARK_LOG = "hm_landmark_log"
const val KEY_CAM_ANTI_FLICKER = "cam_anti_flicker"
const val KEY_CAM_EXPOSURE = "cam_exposure"
const val KEY_GESTURE_HINTS = "hm_gesture_hints"
const val KEY_EDGE_BLOCK = "hm_edge_block"
const val KEY_REC_STREAM = "rec_stream"
const val KEY_REC_FPS = "rec_fps"
const val KEY_REC_AUDIO = "rec_audio"
const val KEY_REC_SPLIT = "rec_split_minutes"
const val KEY_REC_STORAGE = "rec_storage"
const val KEY_REC_AUTO_START = "rec_auto_start"
const val KEY_KEEP_ALIVE = "bg_keep_alive"
const val KEY_CONN_MANUAL = "bg_manual_connect"
const val KEY_DIM_AUTO = "dim_auto"
const val KEY_DIM_MAX = "dim_max"
const val KEY_DIM_START_LUX = "dim_start_lux"
const val KEY_START_ON_CONNECT = "bg_start_on_connect"

const val KEY_LAYER_STEP = "hm_layer_step"
const val REC_STREAM_HEVC_1080 = "hevc_1080"
const val REC_STREAM_HEVC_NATIVE = "hevc_native"
const val REC_STREAM_MJPEG = "mjpeg"

/** Name of the SharedPreferences file (also used by SettingsActivity). */
const val PREFS_FILE_NAME = "hand_mouse_prefs"
