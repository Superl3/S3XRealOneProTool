package com.raphael.handmouse.input

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.raphael.handmouse.overlay.CursorOverlay
import com.raphael.handmouse.util.Prefs

/**
 * Controle por VOZ (2026-07-23, spec `2026-07-23-voice-control-design.md`): o [startListening]
 * (chamado pelo `CursorPipeline` quando o sinal de "V" é segurado) abre UMA sessão do
 * [SpeechRecognizer] nativo; o melhor resultado passa pelo [VoiceCommandParser] e a ação é
 * executada (global actions / [AppLauncher] / [TextInserter]). Estados: IDLE → LISTENING →
 * (executa) → IDLE — sem fila: um novo gesto durante LISTENING é ignorado ([isListening]).
 *
 * ## Threading
 * [startListening] chega na HandlerThread do HandTracker (pipeline); TODO o resto roda na main
 * thread ([mainHandler]) — o SpeechRecognizer EXIGE main thread (criação, startListening,
 * callbacks do [RecognitionListener] e destroy). [isListening] é @Volatile: escrito na main,
 * lido no pipeline a cada frame (congela gestos enquanto ouve).
 *
 * ## Idioma
 * [Prefs.voiceLanguage] selects "ko-KR" or "en-US" through EXTRA_LANGUAGE. A language model missing on the phone
 * cai no onError (ERROR_LANGUAGE_NOT_SUPPORTED/UNAVAILABLE) → feedback vermelho.
 *
 * ## Feedback visual (via [CursorOverlay], sempre na main)
 * setListening(true) no início (cursor amarelo), flashVoiceResult(true/false) no fim (verde =
 * ação executada, vermelho = timeout/no-match/erro). Timeouts em dois estágios (tuning
 * 2026-07-23): [NO_SPEECH_TIMEOUT_MS] até a fala começar, [MAX_SPEECH_TIMEOUT_MS] depois —
 * quem encerra o ditado é o endpointing por silêncio ([COMPLETE_SILENCE_MS]), não o relógio.
 *
 * Erros NUNCA propagam pro pipeline — tudo logado + feedback e volta a IDLE.
 */
class VoiceCommandController(
    private val service: AccessibilityService,
    private val appLauncher: AppLauncher,
    private val textInserter: TextInserter,
    private val prefs: Prefs,
    private val overlay: CursorOverlay,
    /** Closes the app window under the cursor on the given display ([WindowController.closeApp]). */
    private val closeWindow: (displayId: Int) -> Boolean,
) {

    companion object {
        private const val TAG = "VoiceCommandController"

        /** Guarda pra sessão que nunca produz NEM começo de fala (recognizer travado/mudo).
         * Tuning de hardware (2026-07-23, "não consigo gravar mensagens longas"): o timeout
         * único de 7s antigo contava a partir do START da sessão — incluindo ~2s de
         * inicialização do recognizer — e disparava MESMO com fala em andamento, cortando o
         * ditado com ~3-5s úteis. Agora este guarda curto só vale ATÉ [onBeginningOfSpeech];
         * dali em diante quem manda é o endpointing ([COMPLETE_SILENCE_MS]) com o teto longo
         * [MAX_SPEECH_TIMEOUT_MS] só contra sessão travada. */
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L

        /** Teto absoluto DEPOIS que a fala começa — ditado longo é legítimo; isto só pega
         * recognizer que nunca encerra. */
        private const val MAX_SPEECH_TIMEOUT_MS = 30_000L

        /** Silêncio que encerra o ditado (endpointing). O default do recognizer é curto demais
         * pra ditar frases com pausa de respiração — 2s deixa pensar sem perder a sessão. */
        private const val COMPLETE_SILENCE_MS = 2_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var displayId: Int = -1

    // Preferência pelo recognizer ON-DEVICE (2ª rodada de tuning 2026-07-23, "não usa ? nas
    // perguntas"): EXTRA_ENABLE_FORMATTING (pontuação automática) é documentado como suportado
    // SÓ pelo reconhecedor on-device — o padrão (via serviço da Google) ignora o extra e
    // devolve texto cru. Se uma sessão on-device falhar por IDIOMA (modelo pt-BR não instalado),
    // [onDeviceUnavailable] cai pra true e as sessões seguintes usam o recognizer padrão (sem
    // pontuação, mas funcionais). Ambos os campos só são tocados na main thread.
    private var onDeviceUnavailable = false
    private var sessionOnDevice = false

    @Volatile
    var isListening: Boolean = false
        private set

    /** Fix de revisão (2026-07-23): shutdown() só POSTA o teardown pra main — sem esta flag
     * SÍNCRONA, um frame em voo do pipeline antigo (rebind do a11y service) ainda podia abrir
     * uma sessão NOVA numa instância já descartada (recognizer órfão segurando o mic por até
     * LISTEN_TIMEOUT_MS, overlay obsoleto). Uma vez desligada, a instância nunca volta — a
     * reconexão cria um controller novo. */
    @Volatile
    private var isShutDown = false

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Timeout da sessão de voz sem resultado — cancelando")
        finish(success = false)
    }

    /** Chamado pelo CursorPipeline (HandlerThread) quando o "V" completa o hold. Idempotente
     * durante uma sessão ativa. [displayId]: display do DeX pra onde "abrir <app>" lança. */
    fun startListening(displayId: Int) {
        if (isListening || isShutDown) return
        isListening = true
        this.displayId = displayId
        mainHandler.post { startSessionOnMain() }
    }

    /** Encerra qualquer sessão e libera o recognizer (onDestroy do a11y service). */
    fun shutdown() {
        isShutDown = true
        mainHandler.post {
            mainHandler.removeCallbacks(timeoutRunnable)
            recognizer?.destroy()
            recognizer = null
            overlay.setListening(false)
            isListening = false
        }
    }

    // --- Tudo abaixo roda na MAIN THREAD ---

    private fun startSessionOnMain() {
        if (isShutDown) return // fix de revisão final 2026-07-23: post em corrida com shutdown()
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO não concedida — abra o app e rode o wizard de permissões")
            finish(success = false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            Log.w(TAG, "Reconhecimento de voz indisponível neste aparelho")
            finish(success = false)
            return
        }

        try {
            // On-device quando disponível (pontuação — ver onDeviceUnavailable); padrão caso
            // contrário ou após um erro de idioma do on-device.
            sessionOnDevice = !onDeviceUnavailable && SpeechRecognizer.isOnDeviceRecognitionAvailable(service)
            val r = if (sessionOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(service)
            } else {
                SpeechRecognizer.createSpeechRecognizer(service)
            }
            recognizer = r
            r.setRecognitionListener(recognitionListener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Pontuação automática (tuning de hardware 2026-07-23, "não inclui ? ! ."):
                // sem isto o recognizer devolve texto cru, sem pontuar. API 33+; best-effort
                // (depende da implementação do recognizer — o on-device da Google honra).
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                // Endpointing tolerante a pausa de respiração no ditado — ver COMPLETE_SILENCE_MS.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.voiceLanguage)
            }

            overlay.setListening(true)
            Log.d(TAG, "Escutando (idioma=${prefs.voiceLanguage}, display=$displayId, onDevice=$sessionOnDevice)...")
            r.startListening(intent)
            mainHandler.postDelayed(timeoutRunnable, NO_SPEECH_TIMEOUT_MS)
        } catch (e: Exception) {
            // Fix de revisão (2026-07-23): createSpeechRecognizer/startListening podem lançar em
            // builds OEM (recognizer ocupado, mic revogado no meio) — sem este catch, finish()
            // nunca rodava e isListening ficava presa em true PARA SEMPRE (feature morta até
            // reiniciar o serviço), violando o contrato "erros nunca propagam" do Javadoc.
            Log.e(TAG, "Falha ao abrir sessão do SpeechRecognizer", e)
            finish(success = false)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.d(TAG, "Resultados: ${phrases.size} hipóteses (${phrases.map { it.length }} chars)") // fala não vai pro logcat (privacidade — fix de revisão final 2026-07-23)
            // Tenta cada hipótese do recognizer em ordem — a primeira que casar executa.
            val command = phrases.asSequence()
                .map { VoiceCommandParser.parse(it) }
                .firstOrNull { it != VoiceCommand.NoMatch } ?: VoiceCommand.NoMatch
            execute(command)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer onError=$error (onDevice=$sessionOnDevice)")
            // On-device sem o modelo do idioma (12=NOT_SUPPORTED, 13=UNAVAILABLE): não insiste —
            // as próximas sessões voltam pro recognizer padrão (ver onDeviceUnavailable).
            if (sessionOnDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            ) {
                Log.w(TAG, "Recognizer on-device sem o idioma — sessões seguintes usarão o padrão (sem pontuação)")
                onDeviceUnavailable = true
            }
            finish(success = false)
        }

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            // Fala começou: troca o guarda curto (NO_SPEECH_TIMEOUT_MS) pelo teto longo — quem
            // encerra o ditado agora é o ENDPOINTING (silêncio), não um relógio; o teto de 30s
            // existe só contra sessão que nunca encerra. Ver Javadoc de NO_SPEECH_TIMEOUT_MS.
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, MAX_SPEECH_TIMEOUT_MS)
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun execute(command: VoiceCommand) {
        val ok = when (command) {
            VoiceCommand.Back -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            VoiceCommand.Home -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            VoiceCommand.Recents -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            VoiceCommand.CloseApp -> closeWindow(displayId)
            VoiceCommand.SendEnter -> textInserter.pressEnter()
            VoiceCommand.ClearText -> textInserter.clearAll()
            is VoiceCommand.OpenApp -> appLauncher.launch(command.query, displayId)
            is VoiceCommand.Dictate -> textInserter.appendText(command.text)
            VoiceCommand.NoMatch -> false
        }
        Log.d(TAG, "Comando $command → ${if (ok) "OK" else "FALHOU"}")
        finish(success = ok)
    }

    private fun finish(success: Boolean) {
        mainHandler.removeCallbacks(timeoutRunnable)
        recognizer?.destroy()
        recognizer = null
        overlay.setListening(false)
        overlay.flashVoiceResult(success)
        isListening = false
    }
}
