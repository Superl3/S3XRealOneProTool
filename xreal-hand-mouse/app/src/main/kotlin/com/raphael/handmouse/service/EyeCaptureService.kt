package com.raphael.handmouse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.raphael.handmouse.BuildConfig
import com.raphael.handmouse.MainActivity
import com.raphael.handmouse.R
import com.raphael.handmouse.capture.GlassesConnection
import com.raphael.handmouse.capture.UsbPermissionRequests
import com.raphael.handmouse.capture.HevcDecoder
import com.raphael.handmouse.capture.MjpegDecoder
import com.raphael.handmouse.capture.PayloadFormat
import com.raphael.handmouse.capture.UvcCameraHelper
import com.raphael.handmouse.glasses.GlassesTransport
import com.raphael.handmouse.glasses.KotlinGlassesProtocol
import com.raphael.handmouse.imu.GyroHistory
import com.raphael.handmouse.imu.XrealImuClient
import com.raphael.handmouse.recording.DatasetRecorder
import com.raphael.handmouse.recording.EyeRecorder
import com.raphael.handmouse.recording.EyeSnapshot
import com.raphael.handmouse.recording.LandmarkLog
import com.raphael.handmouse.recording.RecordingOutput
import com.raphael.handmouse.tracking.FrameConverter
import com.raphael.handmouse.tracking.HandTracker
import com.raphael.handmouse.tracking.RgbaBufferRing
import com.raphael.handmouse.util.CameraScanRetryPolicy
import com.raphael.handmouse.util.KEY_IGNORE_BOTTOM
import com.raphael.handmouse.util.KEY_LANDMARK_LOG
import com.raphael.handmouse.util.KEY_DATASET_LABEL
import com.raphael.handmouse.util.KEY_HEAD_CALIBRATION
import com.raphael.handmouse.util.KEY_HEAD_COMP
import com.raphael.handmouse.util.KEY_REC_GYRO_LOG
import com.raphael.handmouse.util.KEY_CAM_ANTI_FLICKER
import com.raphael.handmouse.util.KEY_CAM_EXPOSURE
import com.raphael.handmouse.util.KEY_REC_STREAM
import com.raphael.handmouse.tracking.HandZone
import com.raphael.handmouse.util.KEY_TRACKING_ENABLED
import com.raphael.handmouse.util.Prefs
import com.raphael.handmouse.util.REC_STREAM_HEVC_NATIVE
import com.raphael.handmouse.util.REC_STREAM_MJPEG
import com.raphael.handmouse.util.ThermalMonitor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foreground service (`connectedDevice`) que orquestra o pipeline de captura + tracking:
 * [GlassesConnection] (ativação HID) → [UvcCameraHelper] (bulk UVC, thread própria) →
 * [HevcDecoder] (MediaCodec em modo ByteBuffer, thread própria; entrega a `Image` YUV_420_888
 * via [HevcDecoder.FrameListener]) → [FrameConverter] (downscale + RGBA) → [HandTracker]
 * (MediaPipe LIVE_STREAM/GPU).
 *
 * ## Decode em modo ByteBuffer (fix de crash em hardware — 2026-07-22, S25)
 * A abordagem anterior (Tarefa 3) fazia o `HevcDecoder` renderizar para o `Surface` de um
 * `ImageReader` (YUV_420_888) possuído por este serviço, e o `OnImageAvailableListener` rodava o
 * pipeline por frame na `HandlerThread` do [HandTracker]. Isso crashava na 1ª frame real em
 * hardware: o decoder HEVC da Samsung, via Surface→ImageReader, entrega buffers comprimidos
 * (AFBC) NÃO mapeáveis pra CPU — mesmo com `HardwareBuffer.USAGE_CPU_READ_OFTEN` (ignorado pela
 * Samsung nesse caminho) — e `image.planes` aborta o processo no CheckJNI
 * (`Image_createSurfacePlanes`, "non-zero capacity for nullptr pointer"). Ver Javadoc de
 * [HevcDecoder] ("Por que ByteBuffer e não Surface/ImageReader").
 *
 * Correção: eliminado o `ImageReader` e o caminho Surface. O [HevcDecoder] decodifica em modo
 * ByteBuffer (`COLOR_FormatYUV420Flexible`, `surface = null`) e lê cada frame via
 * `MediaCodec.getOutputImage()` (planos CPU-legíveis garantidos), entregando a `Image` a um
 * [HevcDecoder.FrameListener] ([decoderFrameListener]) chamado SÍNCRONO na **thread do decoder**
 * (não mais na `HandlerThread` do [HandTracker]). Esse listener roda o pipeline inteiro por
 * frame (throttle → conversão → `detectAsync` → preview). O preview visual é um `ImageView`
 * (na Activity) atualizado com o MESMO frame já convertido pra RGBA — uma conversão só,
 * reaproveitada pro MediaPipe E pro preview, às custas de mostrar a imagem já no tamanho
 * reduzido (768x576) em vez da resolução nativa; aceitável pra uma tela de debug (brief permite
 * essa simplificação explicitamente).
 *
 * Efeito colateral (bom): a decodificação não depende da Activity estar visível — o pipeline é
 * do serviço, então roda mesmo em background (a Activity só se inscreve como [TrackingListener]
 * pra exibir o debug).
 *
 * A Activity conecta/desconecta via singleton ([getInstance]) e recebe estado/frames/
 * resultados via [StateListener] e [TrackingListener].
 *
 * ## Múltiplos [TrackingListener] (Tarefa 4)
 * Até a Tarefa 3, só a `MainActivity` consumia [TrackingListener] (HUD de debug), então um
 * único campo mutável bastava. A partir desta tarefa, o [com.raphael.handmouse.tracking.CursorPipeline]
 * (dono: `HandMouseAccessibilityService`, roda com o app em BACKGROUND) também precisa
 * consumir os mesmos resultados — e a `MainActivity` já tem o hábito de fazer
 * `trackingListener = null` no `onPause` (correto pro HUD, que só existe com a Activity
 * visível), o que apagaria o listener do cursor se fosse o mesmo campo. Por isso
 * [addTrackingListener]/[removeTrackingListener] substituem o campo único por um registro
 * (`CopyOnWriteArraySet`, seguro pra iterar enquanto outra thread registra/remove) — cada
 * consumidor entra/sai independentemente. `stateListener` continua um campo único de
 * propósito: só a `MainActivity` o usa (status do wizard de conexão), sem esse conflito.
 *
 * ## Handshake com o `HandMouseAccessibilityService` (mesmo processo, singletons)
 * Os dois serviços podem (re)iniciar em qualquer ordem — o usuário pode habilitar a
 * Acessibilidade antes OU depois de já estar capturando. Em [onCreate], se o a11y service já
 * estiver conectado, este FGS se registra nele imediatamente
 * (`HandMouseAccessibilityService.instance?.attachTo(this)`); se ainda não estiver, é o a11y
 * service que se registra aqui quando conectar (`onServiceConnected`, procurando
 * `EyeCaptureService.getInstance()`). Sem acessibilidade ativa, o pipeline de captura roda
 * normalmente (frames processados sem consumidor de cursor) — a `MainActivity` mostra esse
 * estado claramente (status do a11y service + atalho pra Configurações de Acessibilidade).
 *
 * ## Wakelock (Tarefa 5 — corrige comentário incorreto da Tarefa 2)
 * A Tarefa 2 adquiria o `PARTIAL_WAKE_LOCK` com um teto de 30min e um comentário dizendo
 * "renovado enquanto o serviço estiver vivo" — SEM nenhum código de renovação por trás (mentira
 * inofensiva, mas mentira). Decisão desta tarefa (documentada aqui conforme pedido pelo brief):
 * adquirir SEM timeout (`acquire()`, sem argumento) e garantir a liberação estruturada em
 * [onDestroy] (via [releaseWakeLock]) — mais simples que implementar renovação real (que exigiria
 * um handler dedicado só pra isso, checando `isHeld`/expiração antes de cada re-acquire) e
 * aceitável aqui: se o processo morrer sem chamar [onDestroy] (kill duro do SO), o wakelock do
 * kernel morre junto — é ligado ao processo/uid que o detém, não "vaza" ligado pra sempre.
 *
 * ## Reconexão USB (Tarefa 5 — PLANO.md Fase 5)
 * [usbDetachReceiver] (`ACTION_USB_DEVICE_DETACHED`, dinâmico, registrado/removido no mesmo
 * padrão de [cameraPermissionReceiver]) para o PIPELINE (não o serviço — o FGS continua rodando,
 * notificação persistente, pronto pra retomar) e publica uma notificação full-screen-intent
 * pedindo pro usuário tocar pra reconectar. NÃO implementa retry automático de grant USB
 * (impossível sem a Activity em foreground) — o fluxo é notificação → 1 toque → `MainActivity`
 * (que já trata `ACTION_USB_DEVICE_ATTACHED` e chama `retryScan`) retoma o wizard.
 *
 * **Fix de revisão**: a notificação de desconexão usa [NOTIF_CHANNEL_ID_USB_DETACHED]
 * (`IMPORTANCE_HIGH`), um canal SEPARADO do de captura ([NOTIF_CHANNEL_ID], `IMPORTANCE_LOW`) —
 * a versão original postava no canal de captura, e no Android O+ é a **importância do canal**
 * (não `NotificationCompat.Builder.setPriority`) que governa se heads-up/full-screen-intent é
 * honrado; postar num canal `LOW` fazia o `setFullScreenIntent` nunca elevar de verdade, mesmo
 * com `setPriority(PRIORITY_HIGH)` no builder — silenciosamente virava uma notificação comum.
 *
 * ## Simetria `startPipeline`/`stopPipeline` (fix de revisão CRÍTICO, achado C1)
 * [stopPipeline] nasceu (Tarefa 2-4) como teardown de FIM DE VIDA do serviço, chamado só de
 * [onDestroy] — a Tarefa 5 passou a reusá-lo também pro detach USB ([usbDetachReceiver]), mas
 * nada devolvia o pipeline a um estado RECRIÁVEL: o `HandlerThread` do [handTracker] morria e
 * nunca mais era recriado (só [onCreate] chamava `handTracker.start`); o [frameConverter] tinha
 * seus buffers nativos (libyuv) liberados sem nunca
 * ser realocado; [glassesConnection].stop() cancelava o registro do receiver de permissão USB
 * dela sem que nada chamasse `start()` de novo; e o loop de retry de [findAndStartCamera] nunca
 * era cancelado. Resultado: qualquer desconexão USB destruía o pipeline PERMANENTEMENTE, mesmo
 * o usuário reconectando e tocando a notificação.
 *
 * Fix: [startPipeline] e [stopPipeline] agora são simétricos e idempotentes (guardados por
 * [pipelineActive]) — todo recurso de ESCOPO DE SESSÃO (recriável a cada conexão: `glassesConnection`
 * ligar/desligar, `frameConverter`, sessão do `handTracker`, o loop de retry de
 * scan) é criado em [startPipeline] e desfeito em [stopPipeline], SEMPRE em condições de ser
 * recriado pela PRÓXIMA chamada a [startPipeline]. [onCreate]/[onDestroy] cuidam só do que é de
 * ESCOPO DE SERVIÇO (sobrevive a qualquer nº de ciclos start/stop do pipeline): canais de
 * notificação, wakelock, `thermalMonitor`, `prefs`/[Prefs.captureActive], o registro de
 * [trackingListeners]/[stateListener] e os dois `BroadcastReceiver`s de nível de serviço
 * ([cameraPermissionReceiver], [usbDetachReceiver]).
 *
 * Fluxo de recuperação ponta a ponta: cabo cai → [usbDetachReceiver] chama [stopPipeline] (para
 * limpo) + notifica → usuário reconecta o cabo (dispara `ACTION_USB_DEVICE_ATTACHED`, tratado
 * pelo manifest da `MainActivity` — ver `usb_device_filter.xml`) → `MainActivity.onResume` chama
 * [retryScan] → como [pipelineActive] é `false`, [retryScan] chama [startPipeline] (não só
 * `glassesConnection.scanForGlasses()`) → captura retoma do zero.
 *
 * ## Fence contra corrida na liberação de buffers (fix de revisão Important, achado I1)
 * [stopPipeline] roda na main thread, mas a conversão YUV→RGBA
 * (`frameConverter.convert(image)`) agora roda na **thread do decoder**, dentro de
 * [decoderFrameListener] (`HevcDecoder.FrameListener.onFrame`) — não mais na `HandlerThread` do
 * [handTracker]. Sem sincronização, `frameConverter.release()` na main thread podia correr
 * CONCORRENTEMENTE com um `convert()` em andamento na thread do decoder, liberando buffers
 * nativos (libyuv) enquanto ainda em uso (use-after-free nativo).
 *
 * O que garante a segurança de `frameConverter.release()` agora é o **join** dentro de
 * `hevcDecoder.stop()` (ver Javadoc de [HevcDecoder.stop]): ele desarma o decoder e faz `join`
 * (com timeout) da thread do decoder, então quando `stop()` retorna nenhum `onFrame` — e portanto
 * nenhum `convert()` — está mais em voo. Por isso `hevcDecoder?.stop()` DEVE vir ANTES de
 * `frameConverter?.release()` em [stopPipeline].
 *
 * [awaitWorkerDrain] permanece, mas com propósito reduzido: dreno defensivo da
 * [HandTracker.workerHandler] (callbacks de resultado do `detectAsync`) antes de
 * `handTracker.stop()` encerrar aquela `HandlerThread` — não protege mais o `frameConverter`
 * (isso é o join do decoder acima). Posta uma barreira (`Runnable` vazio) no `Handler` do worker
 * e bloqueia (com timeout) até ela rodar; como um `Handler` processa mensagens em ordem (FIFO),
 * qualquer callback já enfileirado termina ANTES da barreira retornar.
 *
 * ## Térmica (Tarefa 5 — PLANO.md Fase 5)
 * [thermalMonitor] observa `PowerManager` durante a captura e ajusta `HandTracker.setMaxFps` via
 * `com.raphael.handmouse.util.ThermalFpsPolicy.targetFps` — reduz a carga de inferência sob
 * throttling térmico numa sessão longa, sem tocar no resto do pipeline (o cursor continua
 * fluido via `OneEuroFilter` mesmo com menos amostras/s).
 *
 * ## Pipeline MJPEG — 7ª rodada (2026-07-23)
 * O [UvcCameraHelper] passou a preferir o formato MJPEG da câmera (intra-only, ~4000 frames sem
 * stall em hardware, contra o HEVC nativo que engasga a cada 20-90s) e expõe qual formato negociou
 * em `activeFormatSubtype` (0x06 MJPEG / 0x10 HEVC). Em [uvcListener.onStreamStarted] o serviço
 * BIFURCA por esse subtipo: MJPEG cria um [MjpegDecoder] (entrega `Bitmap` ARGB já decodificado,
 * copiado pro anel [RgbaBufferRing] → [ByteBufferImageBuilder] → `detectAsync`, ver [mpImageFrom]),
 * HEVC segue o caminho ByteBuffer inalterado (entrega `Image` YUV, [FrameConverter] →
 * [ByteBufferImageBuilder]). Os dois caminhos terminam no MESMO tipo de container do MediaPipe,
 * alimentado por um anel de buffers reutilizados. Só um dos dois decoders existe por
 * stream; ambos compartilham o MESMO escopo de sessão e o MESMO ponto do fence em [stopPipeline]/
 * soft-restart. O throttle térmico do MJPEG é aplicado ANTES de decodificar, via
 * [MjpegDecoder.FrameGate] = `handTracker.shouldAcceptFrame` (barato: não decodifica o que vai
 * jogar fora).
 *
 * **Por que a falha FATAL de decode MJPEG rebaixa o formato em vez de morrer em `ERROR`:** o
 * [UvcCameraHelper] só enxerga BYTES fluindo pelo endpoint — ele não sabe decodificar, então uma
 * falha de DECODE (JPEG persistentemente indecodificável) é invisível pra ele; sua máquina de
 * fallback por candidato só avança em SILÊNCIO do endpoint, nunca por decode ruim. Quem percebe o
 * decode quebrado é o serviço (via [MjpegDecoder.ErrorListener]). Em vez de deixar a UI presa em
 * `ERROR` com o formato preferido morto, o serviço chama [UvcCameraHelper.demoteActiveFormat]
 * (avança o cursor de candidato: MJPEG → HEVC 1080p → HEVC nativo) e reinicia a sessão pelo MESMO
 * caminho do full restart interno do [uvcListener.onStreamStalled] — o próximo stream negocia o
 * HEVC funcional e o usuário recupera o tracking em segundos, sem tocar em nada.
 */
class EyeCaptureService : Service() {

    companion object {
        private const val TAG = "EyeCaptureService"
        private const val NOTIF_CHANNEL_ID = "eye_capture"
        // Canal SEPARADO, IMPORTANCE_HIGH — fix de revisão da Tarefa 5 (achado Important):
        // heads-up/full-screen só é honrado pelo sistema conforme a IMPORTÂNCIA DO CANAL (API
        // 26+), não pela NotificationCompat.Builder.setPriority (que só importa em API<26, sem
        // efeito nenhum quando há canal). notifyDisconnected() postava em NOTIF_CHANNEL_ID, que é
        // IMPORTANCE_LOW (canal da notificação persistente de captura) — o setFullScreenIntent
        // nunca elevava de verdade, silenciosamente. Ver Javadoc da classe ("Reconexão USB").
        private const val NOTIF_CHANNEL_ID_USB_DETACHED = "usb_detached"
        private const val NOTIF_ID = 1
        private const val NOTIF_ID_USB_DETACHED = 3
        private const val ACTION_CAMERA_USB_PERMISSION = "com.raphael.handmouse.CAMERA_USB_PERMISSION"
        private const val CAMERA_SCAN_RETRY_DELAY_MS = 2000L
        private const val FORCE_CAPTURE_DEBOUNCE_MS = 10_000L

        /** Carência entre o DETACH dos óculos e a notificação de "desconectado" (2026-07-23):
         * a ativação da câmera Eye (SetUsbConfigAll com uvc0=1) RE-ENUMERA o dispositivo —
         * um detach+attach que faz parte do handshake NORMAL. Notificar/alarmar nesse ciclo era
         * parte da instabilidade de conexão relatada em hardware; agora o detach só vira
         * notificação se os óculos continuarem fora do barramento passada a carência. */
        private const val DETACH_NOTIFY_GRACE_MS = 5000L
        private const val AUTO_CONNECT_DELAY_MS = 4_000L

        // Auto-recuperação de stall do stream (ver uvcListener.onStreamStalled).
        // 4 → 6 (7ª rodada 2026-07-23): a janela ruim PÓS-CONEXÃO da câmera cospe 4-5 streams
        // natimortos seguidos antes de engatar — com teto 4, duas sessões do dia esgotaram o
        // orçamento na inicialização e pagaram o desvio de ERROR + backoff de 15s (+ poll) à toa;
        // 6 tentativas cobrem a janela medida e o engate sai ~15s mais cedo.
        private const val MAX_STREAM_RESTART_ATTEMPTS = 6
        private const val MAX_FAILED_RECOVERY_CYCLES = 2
        private const val STREAM_RESTART_DELAY_MS = 1500L

        /** Streams mortos JOVENS consecutivos (morreram antes de [STREAM_STABLE_FRAME_COUNT]
         * frames) até escalar pra reconfiguração USB forçada da câmera (4ª rodada 2026-07-23;
         * critério ampliado na 6ª): quando a câmera trava profundo, o firmware ainda reporta
         * uvc0=1 e o atalho de reconexão pula o único reset que funciona. O critério original
         * (só natimortos de 0 frames) deixava escapar o padrão real das janelas ruins medidas
         * em hardware — streams morrendo com 36-161 frames em cascata, cada um ZERANDO o
         * contador — e a escalada nunca disparava; o app ciclava restarts normais até a sorte.
         * Morte jovem agora conta; só um stream que chegou a ESTABILIZAR zera. Depois deste nº
         * seguidos, o próximo ciclo passa `forceReconfigure=true` pro
         * [GlassesConnection.enableEyeCamera] (re-enumeração completa; o dance detach→attach
         * resultante é absorvido pelos receivers). */
        private const val DEAD_STREAMS_FORCE_RECONFIGURE = 3

        /** Poll de PRESENÇA dos óculos (6ª rodada 2026-07-23, "tracking morreu e não voltou"):
         * um reset forte do link USB faz os óculos SUMIREM do barramento ("Nenhum óculos XREAL
         * encontrado", 12:35 em hardware) — e se o broadcast de ATTACH da volta se perder (ou
         * chegar sem permissão, com a tela apagada), nada mais religava a captura: o serviço
         * ficava vivo e IDLE pra sempre. Este poll é a rede de segurança ativa: com captura
         * desejada ([Prefs.captureActive]) e pipeline parado, checa o barramento a cada tick —
         * óculos presentes COM permissão religam sozinhos; presentes SEM permissão disparam a
         * notificação de reconexão (1 toque) UMA vez por episódio. Barato: uma consulta ao
         * UsbManager a cada 10s, nada quando o pipeline está rodando. */
        private const val GLASSES_POLL_INTERVAL_MS = 10_000L

        /** Backoff do ciclo completo de recuperação depois de esgotar
         * [MAX_STREAM_RESTART_ATTEMPTS] (4ª rodada 2026-07-23): longo o bastante pra não virar
         * loop agressivo contra uma câmera engasgada, curto o bastante pra experiência se
         * recuperar sozinha sem o usuário mexer em nada. */
        private const val STREAM_ERROR_RETRY_DELAY_MS = 15_000L

        /** Pausa entre o teardown do stream estagnado e a re-negociação no SOFT restart
         * (2026-07-23, 3ª rodada): reconectar imediatamente (~30ms) flapava — o stream novo
         * nascia morto ou morria com 19-69 frames (a câmera ainda "engasgada" do stall) e a
         * recuperação total levava 4-7s em várias tentativas. 400ms de settle deixam o encoder
         * da câmera respirar; com o alt=0 no stopStream, o stream seguinte tende a engatar de
         * primeira. */
        private const val SOFT_RESTART_SETTLE_MS = 400L

        /** Frames contínuos que provam que o stream ESTABILIZOU (não foi só o primeiro GOP antes de
         * travar) — só então o orçamento de restart é renovado. ~90 frames ≈ 3s a 30fps. Sem isto,
         * um stream que sempre entrega o 1º GOP e trava resetaria o contador a cada tentativa e
         * reiniciaria pra sempre. */
        private const val STREAM_STABLE_FRAME_COUNT = 90

        /** While recording, the notification is re-posted only this often (for the file size);
         * the elapsed time is a system-drawn chronometer. */
        private const val NOTIF_SIZE_REFRESH_MS = 60_000L

        /** Idle inference (Eye Tools fork): with no hand in view for this long, MediaPipe drops
         * to [IDLE_INFERENCE_FPS] (palm detection only) until a hand shows up again — hands
         * are down most of the time, so this removes most of the GPU/decoder heat. */
        private const val IDLE_AFTER_MS = 8000L
        private const val IDLE_INFERENCE_FPS = 12

        /** Hand-loss grace (2026-09-28): listeners hear onHandLost only after MediaPipe has
         * found no hand for this long. Before, ONE hand-less frame reset the whole cursor
         * pipeline — a drag was dropped where it was and a pressed click cancelled — and
         * detection drops cluster exactly in pinches and fists, where fingers hide each other.
         * During the grace the cursor, pinch and drag simply hold. */
        private const val HAND_LOST_GRACE_MS = 250L

        /** Dataset images are saved only while MediaPipe saw a hand this recently (the result
         * arrives a frame or two after the image). */
        private const val DATASET_HAND_RECENT_MS = 300L

        /**
         * Eye Tools fork: the single entry point for every recorder/tracking command (tile,
         * notification, hardware button, assistant shortcut, app screen). Tracking is a setting,
         * so it works without the service; record-start also starts capture if needed (the
         * recorder waits for the stream); other commands need capture running. Commands are
         * serialized on the control thread.
         */
        fun perform(context: Context, action: EyeAction) {
            val prefs = Prefs(context)
            when (action) {
                EyeAction.TRACKING_ON -> prefs.trackingEnabled = true
                EyeAction.TRACKING_OFF -> prefs.trackingEnabled = false
                EyeAction.TRACKING_TOGGLE -> prefs.trackingEnabled = !prefs.trackingEnabled
                EyeAction.CAPTURE_STOP -> stop(context)
                else -> {
                    val service = instance
                    when {
                        service != null -> service.controlHandler.post { service.handle(action) }
                        action == EyeAction.RECORD_START || action == EyeAction.RECORD_TOGGLE ->
                            start(context, EyeAction.RECORD_START)
                        else -> Log.w(TAG, "perform($action): capture is not running")
                    }
                }
            }
        }

        /** Recorder status for the UI (main thread). Same companion pattern as [stateListener]. */
        @Volatile
        var recorderListener: RecorderStatusListener? = null

        @Volatile
        private var instance: EyeCaptureService? = null

        fun getInstance(): EyeCaptureService? = instance

        /** Listener de estado da UI — NO COMPANION, não na instância (fix 2026-07-24, "status
         * preso em 'Permissões OK'" caso 2): a MainActivity registrava via
         * `getInstance()?.stateListener = this`, que é NO-OP SILENCIOSO enquanto o serviço não
         * materializou (`startForegroundService` é assíncrono — o onCreate do serviço roda
         * depois do frame atual da main thread). Uma Activity que inicia o serviço e permanece
         * resumida nunca ganhava outro onResume pra tentar de novo → nenhuma transição chegava
         * e o status congelava no texto do wizard. Estático = registrar independe da vida do
         * serviço; a MainActivity seta no onResume/startCaptureService e limpa no onPause. */
        @Volatile
        var stateListener: StateListener? = null

        /** Chamado pela `MainActivity` (botão "iniciar captura") e pelo watchdog do
         * `HandMouseAccessibilityService` (Tarefa 5) ao tentar reiniciar depois de uma morte
         * inesperada do processo. [Prefs.captureActive] = true ANTES de pedir o
         * `startForegroundService` — é essa flag que o watchdog consulta pra saber "a captura
         * deveria estar rodando" (ver Javadoc de [Prefs.captureActive]). */
        fun start(context: Context, action: EyeAction? = null) {
            // Guard (2026-07-23): sem óculos no USB — OU sem a permissão USB deles concedida —
            // o startForeground(connectedDevice) seria negado (SecurityException — ver
            // onStartCommand: a isenção "USB Device" do FGS type exige um dispositivo com
            // permissão JÁ concedida ao app, não só presente). Não inicia nada: quem chama
            // (MainActivity, que agora pede a permissão ANTES de iniciar; watchdog; ATTACH)
            // tenta de novo quando a pré-condição existir.
            if (!isGlassesReady(context)) {
                Log.w(TAG, "start(): sem óculos XREAL com permissão USB — aguardando (FGS connectedDevice seria negado)")
                return
            }
            Prefs(context).captureActive = true
            val intent = Intent(context, EyeCaptureService::class.java).setAction(action?.intentAction)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Há um dispositivo XREAL no barramento USB agora? (Presença apenas — pra saber se um
         * ATTACH futuro é o que falta.) */
        fun isGlassesAttached(context: Context): Boolean {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return usb.deviceList.values.any { it.vendorId == GlassesConnection.XREAL_VID }
        }

        /** Há um dispositivo XREAL no USB **com permissão concedida ao app**? Guard do FGS
         * `connectedDevice` (2026-07-23): a isenção "USB Device" do tipo de FGS só vale com a
         * permissão do dispositivo em mãos — presença sozinha estourava SecurityException no
         * startForeground (crash observado em hardware às 00:46 de 23/07). Usado por [start] e
         * pelo watchdog do `HandMouseAccessibilityService`. */
        fun isGlassesReady(context: Context): Boolean {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return usb.deviceList.values.any {
                it.vendorId == GlassesConnection.XREAL_VID && usb.hasPermission(it)
            }
        }

        /** **Fix de revisão (achado Important I6)**: antes deste fix, [Prefs.captureActive]
         * nunca era limpa em lugar NENHUM do app — nem aqui (este método sequer chamava
         * `Prefs(context).captureActive = false`), nem em [onDestroy] (de propósito — ver
         * Javadoc de [Prefs.captureActive]). Como este app hoje não tem nenhum botão "parar
         * captura" na UI, [stop] fica sem chamador em produção por enquanto, mas precisa estar
         * CORRETO pra qualquer uso futuro (programático ou de uma UI nova): espelha [start] —
         * limpa a flag ANTES de pedir `stopService`, pra que o watchdog do
         * `HandMouseAccessibilityService`, se rodar bem nesse meio-tempo, já veja
         * `captureActive=false` e não tente "recuperar" uma parada intencional (armadilha de
         * ressurreição — o watchdog reiniciaria a captura que o usuário/código pediu pra
         * parar). */
        fun stop(context: Context) {
            Prefs(context).captureActive = false
            context.stopService(Intent(context, EyeCaptureService::class.java))
        }
    }

    enum class PipelineState {
        IDLE, CONNECTING_HID, CAMERA_ENABLING, FINDING_CAMERA, REQUESTING_CAMERA_PERMISSION, STREAMING, ERROR
    }

    interface StateListener {
        fun onStateChanged(state: PipelineState, message: String)
        fun onLog(message: String)
    }

    fun interface RecorderStatusListener {
        fun onRecorderStatus(status: EyeRecorder.Status)
    }

    /** Frames convertidos + resultados de tracking — sempre entregues na main thread. */
    interface TrackingListener {
        fun onFrameConverted(bitmap: Bitmap, conversionMs: Double)
        fun onHandResult(result: HandTracker.Result)
        fun onHandLost()
        fun onTrackingError(message: String)
    }

    var state: PipelineState = PipelineState.IDLE
        private set

    /** Gate do preview de debug (2026-07-23, 3ª rodada): [decoderFrameListener] criava um
     * `Bitmap` NOVO de ~1,7MB por frame mesmo com o preview recolhido e o app em background —
     * a 60fps isso é ~106MB/s de lixo pro GC, pressão que compete com o pipeline inteiro.
     * Só a `MainActivity` consome os bitmaps ([TrackingListener.onFrameConverted] é ignorado
     * pelo `CursorPipeline`), então ela liga/desliga este gate junto com a visibilidade do
     * preview; com ele desligado o frame convertido segue direto pro MediaPipe sem NENHUMA
     * alocação de UI. Escrito na main thread, lido na thread do decoder. */
    @Volatile
    var previewFramesEnabled = false

    // Ver Javadoc da classe ("Múltiplos TrackingListener") sobre por que isto não é um único
    // campo mutável como stateListener.
    private val trackingListeners = java.util.concurrent.CopyOnWriteArraySet<TrackingListener>()

    fun addTrackingListener(listener: TrackingListener) {
        trackingListeners.add(listener)
    }

    fun removeTrackingListener(listener: TrackingListener) {
        trackingListeners.remove(listener)
    }

    private val handler = Handler(Looper.getMainLooper())

    // ## Thread de controle do pipeline (fix de ANR em hardware — 2026-07-23)
    // stopPipeline() faz JOINs (thread do stream USB até 2s, thread do decoder até 1s +
    // MediaCodec.stop/release — que trava por segundos num codec Samsung emperrado — + dreno do
    // worker 500ms). Isso rodava na MAIN THREAD, disparado por detach de USB, stall do stream e
    // teardown — e este processo também hospeda o HandMouseAccessibilityService, então cada
    // bloqueio da main virava lag de input/congelamento perceptível no DeX inteiro (ANR real
    // registrado às 01:15 de 23/07: "Input dispatching timed out... Waited 10000ms"). TODA a
    // orquestração de ciclo de vida do pipeline (start/stop/scan/retries/stall-restart) roda
    // agora nesta HandlerThread dedicada, serializada; a main thread nunca mais faz join. Os
    // Runnables de retry (findCameraRunnable/streamRestartRunnable/detachNotifyRunnable) são
    // agendados NELA. Estado de sessão (pipelineActive, pendingCameraDevice, contadores) é
    // confinado a esta thread.
    private lateinit var controlThread: android.os.HandlerThread
    private lateinit var controlHandler: Handler

    private var wakeLock: PowerManager.WakeLock? = null

    // Ver Javadoc da classe ("Simetria startPipeline/stopPipeline", achado C1) — de escopo de
    // SERVIÇO, sobrevive a qualquer nº de ciclos start/stop do pipeline.
    private lateinit var glassesConnection: GlassesConnection
    private lateinit var uvcCameraHelper: UvcCameraHelper
    private lateinit var thermalMonitor: ThermalMonitor
    private val handTracker = HandTracker()

    // ---- Eye Tools fork: recorder branch + settings (service scope) ----
    private lateinit var prefs: Prefs
    private lateinit var recorder: EyeRecorder
    private lateinit var snapshot: EyeSnapshot

    /** Last photo result, shown as the notification sub-text (no pop-up over DeX). */
    private var lastPhotoText: String? = null

    /** Hand tracking on/off (settings). Off = no decoder, no MediaPipe: recording-only mode.
     * Written on the main thread (prefs listener), read on the USB thread. */
    @Volatile
    private var trackingEnabled = true

    @Volatile
    var recorderStatus = EyeRecorder.Status()
        private set

    private var foregroundStarted = false

    @Volatile
    private var thermalFps = HandTracker.MAX_INFERENCE_FPS
    @Volatile
    private var lastHandSeenMs = SystemClock.uptimeMillis()
    @Volatile
    private var idleInference = false

    private fun applyInferenceFps() {
        handTracker.setMaxFps(if (idleInference) minOf(IDLE_INFERENCE_FPS, thermalFps) else thermalFps)
    }

    /** "Ignore hands in the bottom of the view" (riding) — applied here, before a hand counts as
     * seen, so hands on the handlebars also let inference drop to the idle rate. */
    @Volatile
    private var ignoreBottomFraction = 0f

    /** Per-frame landmark log (setting, off by default) — open while the pipeline runs. */
    @Volatile
    private var landmarkLog: LandmarkLog? = null

    private fun applyLandmarkLogPreference() {
        val want = pipelineActive && prefs.landmarkLog
        if (want && landmarkLog == null) landmarkLog = LandmarkLog.open(this)
        if (!want) {
            landmarkLog?.close()
            landmarkLog = null
        }
        landmarkLog?.label = prefs.datasetLabel
    }

    /** Gesture images for retraining (setting "hm_dataset_label") — open while the pipeline
     * runs with a label chosen. Control thread; read on the MJPEG decoder thread. */
    @Volatile
    private var datasetRecorder: DatasetRecorder? = null

    private fun applyDatasetPreference() {
        val label = if (pipelineActive) prefs.datasetLabel else null
        if (datasetRecorder?.label != label) {
            datasetRecorder?.close()
            datasetRecorder = label?.let { DatasetRecorder.open(this, it) }
        }
        landmarkLog?.label = label
    }

    // ---- Glasses IMU (2026-09-29) ----
    /** Last ~2 s of the glasses' gyro on `System.nanoTime()`'s clock — the cursor's head-motion
     * compensation reads it ([com.raphael.handmouse.tracking.HeadMotionCompensator]). */
    val gyroHistory = GyroHistory()

    private val imuClient by lazy {
        XrealImuClient(this, { log(XrealImuClient.failureMessage(it)) }) { sample, localNs ->
            recorder.onImuSample(sample, localNs)
            gyroHistory.add(localNs, sample.gx, sample.gy, sample.gz)
        }
    }

    /** Control thread. The IMU link runs only while something uses it: a recording with the
     * gyro log on, or the calibrated cursor compensation. */
    private fun applyImuState() {
        val forRecording = recorder.isRecording && prefs.recordGyroLog
        val forCursor = trackingEnabled && prefs.headCompensation && prefs.headCalibration != null
        if (pipelineActive && (forRecording || forCursor)) {
            imuClient.start()
        } else {
            imuClient.stop()
            gyroHistory.clear()
        }
    }

    /** See [HAND_LOST_GRACE_MS]. Posted on [handler] (main) once per hand-less stretch. */
    private val handLostPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val handLostGraceRunnable = Runnable {
        handLostPending.set(false)
        trackingListeners.forEach { it.onHandLost() }
    }

    private var lastNotifUpdateMs = 0L

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_TRACKING_ENABLED -> {
                trackingEnabled = prefs.trackingEnabled
                controlHandler.post { applyFormatPreference(); applyImuState() }
                refreshNotification()
            }
            KEY_REC_GYRO_LOG, KEY_HEAD_COMP, KEY_HEAD_CALIBRATION -> controlHandler.post { applyImuState() }
            KEY_DATASET_LABEL -> controlHandler.post { applyDatasetPreference() }
            KEY_REC_STREAM -> controlHandler.post { applyFormatPreference() }
            KEY_IGNORE_BOTTOM -> ignoreBottomFraction = prefs.ignoreBottomPct / 100f
            KEY_LANDMARK_LOG -> controlHandler.post { applyLandmarkLogPreference() }
            KEY_CAM_ANTI_FLICKER, KEY_CAM_EXPOSURE -> {
                uvcCameraHelper.cameraControls = prefs.cameraControls
                controlHandler.post { uvcCameraHelper.applyCameraControls() }
            }
        }
    }

    private val recorderStatusListener = object : EyeRecorder.Listener {
        override fun onRecorderStatus(status: EyeRecorder.Status) {
            handler.post { onRecorderStatusMain(status) }
        }
    }

    /** Format sniffed from the first frame of the current stream (UVC thread). */
    @Volatile
    private var payloadFormat = PayloadFormat.UNKNOWN

    private fun formatName(f: Int) = when (f) {
        PayloadFormat.MJPEG -> "MJPEG"
        PayloadFormat.HEVC -> "HEVC"
        else -> "unknown"
    }

    // Recursos de escopo de SESSÃO (ver mesmo Javadoc) — criados em startPipeline(), fechados/
    // nulados em stopPipeline(), sempre em condição de ser recriados pela PRÓXIMA sessão.
    // frameConverter precisa ser um `var` (instância NOVA a cada sessão): FrameConverter.release()
    // fecha buffers nativos (libyuv) que não podem ser "reabertos" na mesma instância.
    private var hevcDecoder: HevcDecoder? = null
    // Decoder MJPEG — irmão do hevcDecoder, MESMO escopo/ciclo de sessão (ver Javadoc da classe,
    // "Pipeline MJPEG — 7ª rodada"): só um dos dois existe por stream, escolhido em
    // onStreamStarted pelo formato ATIVO do UvcCameraHelper. Parado/nulado no mesmo ponto do fence
    // em stopPipeline e no soft-restart do onStreamStalled.
    private var mjpegDecoder: MjpegDecoder? = null
    private var frameConverter: FrameConverter? = null

    // Anel RGBA do caminho MJPEG — o análogo do anel de saída do frameConverter (que serve o
    // caminho HEVC). Ver [mjpegFrameListener]. Memória gerenciada: release() aqui é só soltar
    // pro GC, mas segue o MESMO ponto do fence que o frameConverter, por disciplina.
    private var mjpegRgbaRing: RgbaBufferRing? = null

    // Log de uma vez só do fallback de padding de linha (ver [mpImageFrom]) — a 60fps, logar
    // por frame viraria spam. Tocado só na thread do decoder MJPEG.
    private var loggedMjpegStrideFallback = false
    private var pendingCameraDevice: android.hardware.usb.UsbDevice? = null

    // Guarda de idempotência: startPipeline()/stopPipeline() nunca duplicam/duplo-liberam
    // recursos de sessão, mesmo se onStartCommand for chamado de novo com o pipeline já ativo
    // (START_STICKY permite isso) ou stopPipeline for chamado 2x seguidas (onDestroy depois de
    // um usbDetachReceiver, por ex.).
    private var pipelineActive = false

    // Auto-recuperação de stall do stream (ver uvcListener.onStreamStalled) — reinicia a
    // ativação da câmera sozinho quando ela choca sem entregar frames, em vez de deixar o preview
    // congelado. Renovado quando o stream prova estabilidade (STREAM_STABLE_FRAME_COUNT).
    // framesSinceStreamStart é @Volatile: escrito na thread do stream USB (onFrameReceived),
    // lido na thread de controle (onStreamStalled decide soft vs full restart por ele).
    private var streamRestartAttempts = 0
    @Volatile private var failedRecoveryCycles = 0
    private var waitingForUsbReattach = false
    @Volatile
    private var framesSinceStreamStart = 0

    /** Ver [DEAD_STREAMS_FORCE_RECONFIGURE]. @Volatile: incrementado na thread de controle
     * (onStreamStalled), lido em glassesListener.onConnected (controle OU main, via receiver de
     * permissão USB). */
    @Volatile
    private var consecutiveDeadStreams = 0

    // freshBudget=false: restart INTERNO de auto-recuperação — NÃO zera o orçamento de
    // tentativas (fix 4ª rodada: o reset incondicional em startPipeline fazia o próprio loop de
    // recuperação renovar o orçamento a cada ciclo — restart infinito a cada ~1,5s com a câmera
    // morta, sem nunca chegar no backoff de 15s).
    private val streamRestartRunnable = Runnable { startPipeline(freshBudget = false) }

    // Ver GLASSES_POLL_INTERVAL_MS. reconnectNudgeSent: a notificação "toque pra reconectar" do
    // caso sem-permissão sai UMA vez por episódio de desconexão (rearmada quando os óculos
    // somem do barramento ou o pipeline volta a rodar).
    private var reconnectNudgeSent = false
    private var autoAttachPending = false
    private val autoAttachRunnable = Runnable {
        autoAttachPending = false
        if (!prefs.manualConnect && !pipelineActive && !waitingForUsbReattach &&
            isGlassesAttached(this)) {
            maybeAutoRecord()
            startPipeline()
        }
    }
    private val glassesPresencePoll = object : Runnable {
        override fun run() {
            // A stall recovery already scheduled by the control thread owns the next start.
            // The presence poll must not jump ahead and reset its retry budget.
            if (!pipelineActive && !autoAttachPending && !waitingForUsbReattach && !prefs.manualConnect &&
                !controlHandler.hasCallbacks(streamRestartRunnable) &&
                Prefs(this@EyeCaptureService).captureActive) {
                when {
                    isGlassesReady(this@EyeCaptureService) -> {
                        log("Glasses present with permission — resuming capture")
                        reconnectNudgeSent = false
                        startPipeline()
                    }
                    isGlassesAttached(this@EyeCaptureService) -> {
                        if (!reconnectNudgeSent) {
                            reconnectNudgeSent = true
                            Log.w(TAG, "Óculos no barramento sem permissão USB — notificando reconexão (1 toque)")
                            notifyDisconnected()
                        }
                    }
                    else -> reconnectNudgeSent = false // sumiram de novo — rearma o aviso
                }
            }
            controlHandler.postDelayed(this, GLASSES_POLL_INTERVAL_MS)
        }
    }

    // Ver Javadoc da classe ("Reconexão USB") e CameraScanRetryPolicy (achado I7) — Runnable
    // NOMEADO (não uma lambda inline) pra poder ser cancelado via handler.removeCallbacks em
    // stopPipeline; contador de tentativas resetado a cada startPipeline().
    private val findCameraRunnable = Runnable { findAndStartCamera() }
    private var cameraScanAttempts = 0

    private val cameraPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_CAMERA_USB_PERMISSION) return
            val callbackDevice = usbDeviceFrom(intent)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            controlHandler.post {
                UsbPermissionRequests.finish(callbackDevice?.deviceName ?: pendingCameraDevice?.deviceName)
                val device = pendingCameraDevice
                if (device == null || (callbackDevice != null && device.deviceName != callbackDevice.deviceName)) return@post
                pendingCameraDevice = null
                if (!pipelineActive) return@post
                if (granted) {
                    uvcCameraHelper.startStream(device)
                } else {
                    setState(PipelineState.ERROR, "Camera USB permission denied")
                }
            }
        }
    }

    /** Extrai o [android.hardware.usb.UsbDevice] de um broadcast ATTACH/DETACH do UsbManager. */
    private fun usbDeviceFrom(intent: Intent): android.hardware.usb.UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    // Notificação de desconexão ADIADA (ver DETACH_NOTIFY_GRACE_MS): só alarma se os óculos
    // continuarem fora do USB passada a carência — o detach da re-enumeração do handshake não
    // pode virar alarme. Agendado/cancelado SEMPRE na controlHandler.
    private val detachNotifyRunnable = Runnable {
        if (!isGlassesAttached(this)) {
            setState(PipelineState.IDLE, "Glasses disconnected")
            if (prefs.manualConnect) {
                // Manual connection: unplugging ends the session; nothing waits in the background.
                log("Manual connection: glasses unplugged — session ended")
                Prefs(this).captureActive = false
                stopSelf()
            } else {
                notifyDisconnected()
            }
        }
    }

    // Ver Javadoc da classe ("Reconexão USB", Tarefa 5) + fixes de estabilidade 2026-07-23:
    // (1) filtra pelo VID da XREAL — antes QUALQUER detach USB (teclado, hub...) derrubava o
    // pipeline inteiro; (2) a notificação respeita a carência de re-enumeração (ver
    // detachNotifyRunnable); (3) o corpo roda na thread de controle, não na main.
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            if (usbDeviceFrom(intent)?.vendorId != GlassesConnection.XREAL_VID) return
            UsbPermissionRequests.finish(usbDeviceFrom(intent)?.deviceName)
            controlHandler.post {
                controlHandler.removeCallbacks(autoAttachRunnable)
                autoAttachPending = false
                // A real disconnect is the only known recovery from a camera that keeps
                // returning zero-filled frames after format fallback and USB reconfiguration.
                if (waitingForUsbReattach) {
                    waitingForUsbReattach = false
                    failedRecoveryCycles = 0
                }
                Log.w(TAG, "USB_DEVICE_DETACHED (XREAL) — parando pipeline; carência de ${DETACH_NOTIFY_GRACE_MS}ms antes de notificar")
                stopPipeline()
                controlHandler.removeCallbacks(detachNotifyRunnable)
                controlHandler.postDelayed(detachNotifyRunnable, DETACH_NOTIFY_GRACE_MS)
            }
        }
    }

    // Reconexão AUTOMÁTICA no ATTACH (2026-07-23): antes, retomar após um detach dependia da
    // MainActivity estar em foreground pra chamar retryScan() — com o app em background o usuário
    // tinha que plugar/desplugar várias vezes e reabrir o app. O próprio serviço (que continua
    // vivo como FGS após um detach) agora observa o ATTACH dos óculos e religa o pipeline sozinho
    // — sem novo startForeground (o FGS nunca parou), então não há guard de permissão aqui.
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            if (usbDeviceFrom(intent)?.vendorId != GlassesConnection.XREAL_VID) return
            controlHandler.post {
                if (waitingForUsbReattach) {
                    Log.d(TAG, "USB attach ignored until a detach confirms physical reconnection")
                    return@post
                }
                controlHandler.removeCallbacks(detachNotifyRunnable)
                NotificationManagerCompat.from(this@EyeCaptureService).cancel(NOTIF_ID_USB_DETACHED)
                if (!pipelineActive) {
                    if (prefs.manualConnect) {
                        log(getString(R.string.conn_manual_attached))
                    } else {
                        autoAttachPending = true
                        controlHandler.removeCallbacks(autoAttachRunnable)
                        controlHandler.postDelayed(autoAttachRunnable, AUTO_CONNECT_DELAY_MS)
                    }
                    return@post
                }
                Log.d(TAG, "USB_DEVICE_ATTACHED (XREAL) — cancelando alarme de desconexão e retomando pipeline")
                when {
                    state == PipelineState.CONNECTING_HID -> glassesConnection.scanForGlasses()
                    // An attach during camera re-enumeration is expected. Starting a second HID
                    // activation here used to race the first UVC stream on the same endpoint.
                    else -> Log.d(TAG, "USB attach while $state — current pipeline owns recovery")
                }
            }
        }
    }

    // ─── Debug receivers (spike-only): executam ações manuais de teste ───

    // ─── SPIKE (debug-only): valida o decoder do IOCTLTAP com um GET_DESCRIPTOR conhecido ───
    private val validateTapReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = usb.deviceList.values.firstOrNull {
                it.vendorId == GlassesConnection.XREAL_VID
            } ?: run { Log.w(TAG, "VALIDATE_TAP: óculos não encontrados"); return }
            if (!usb.hasPermission(dev)) { Log.w(TAG, "VALIDATE_TAP: sem permissão USB"); return }
            val conn = usb.openDevice(dev) ?: run { Log.w(TAG, "VALIDATE_TAP: openDevice falhou"); return }
            try {
                val buf = ByteArray(18)
                // GET_DESCRIPTOR device: bmRequestType=0x80 bRequest=6 wValue=0x0100 wIndex=0 wLength=18
                val n = conn.controlTransfer(0x80, 0x06, 0x0100, 0, buf, buf.size, 1000)
                Log.i(TAG, "VALIDATE_TAP: controlTransfer ret=$n (esperado 18)")
            } finally { conn.close() }
        }
    }

    // ─── SPIKE (debug-only): força a sequência completa de ativação (WaitPilotReady→Set/GetUsbConfig) ───
    private var lastForceCaptureAcceptedMs = 0L
    private val forceCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            val timeSinceLastMs = now - lastForceCaptureAcceptedMs
            if (timeSinceLastMs < FORCE_CAPTURE_DEBOUNCE_MS) {
                val waitMs = FORCE_CAPTURE_DEBOUNCE_MS - timeSinceLastMs
                Log.w(TAG, "FORCE_CAPTURE ignorado: sequência anterior ainda em andamento (aguarde ${waitMs}ms)")
                return
            }
            lastForceCaptureAcceptedMs = now
            Log.i(TAG, "FORCE_CAPTURE: reexecutando enableEyeCamera(forceReconfigure=true)")
            // Serializa com as chamadas do próprio pipeline (ver controlThread, "fix de ANR") —
            // sem isto, a re-enumeração disparada por este enableEyeCamera direto podia correr
            // CONCORRENTEMENTE com o enableEyeCamera de glassesListener.onConnected (disparado
            // pelo ATTACH que este próprio FORCE_CAPTURE causa), duas threads GlassesEnableCamera
            // driblando o mesmo fd/lib nativa ao mesmo tempo.
            controlHandler.post { glassesConnection.enableEyeCamera(forceReconfigure = true) }
        }
    }

    // ─── SPIKE (debug-only): roda a sequência de ativação da câmera em Kotlin puro (sem a .so
    // proprietária) — compartilha o debounce de 10s com forceCaptureReceiver (mesmo
    // lastForceCaptureAcceptedMs), já que as duas rodam a MESMA re-enumeração física do
    // dispositivo e não fazem sentido em paralelo. ───
    private val forceCaptureKotlinReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            val since = now - lastForceCaptureAcceptedMs
            if (since < FORCE_CAPTURE_DEBOUNCE_MS) {
                Log.w(TAG, "FORCE_CAPTURE_KOTLIN ignorado: aguarde ${FORCE_CAPTURE_DEBOUNCE_MS - since}ms")
                return
            }
            lastForceCaptureAcceptedMs = now
            Log.i(TAG, "FORCE_CAPTURE_KOTLIN: rodando a sequencia em Kotlin puro")
            controlHandler.post {
                val usb = getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList.values.firstOrNull {
                    it.vendorId == GlassesConnection.XREAL_VID
                } ?: run { Log.w(TAG, "KOTLIN: óculos não encontrados"); return@post }
                if (!usb.hasPermission(dev)) { Log.w(TAG, "KOTLIN: sem permissão USB"); return@post }
                val found = GlassesTransport.find(dev)
                    ?: run { Log.w(TAG, "KOTLIN: interface de controle (ep 0x01/0x81) não encontrada"); return@post }
                val conn = usb.openDevice(dev) ?: run { Log.w(TAG, "KOTLIN: openDevice falhou"); return@post }
                val transport = GlassesTransport(conn, found.first, found.second, found.third)
                try {
                    if (!transport.claim()) { Log.w(TAG, "KOTLIN: claimInterface falhou"); return@post }
                    val ok = KotlinGlassesProtocol(transport).enableEyeCamera { Log.i(TAG, "KOTLIN: $it") }
                    Log.i(TAG, "KOTLIN: sequencia concluida ok=$ok")
                } finally {
                    transport.release()
                    conn.close()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        // Thread de controle do pipeline — ver comentário do campo (fix de ANR 2026-07-23).
        controlThread = android.os.HandlerThread("PipelineControl").apply { start() }
        controlHandler = Handler(controlThread.looper)

        // Escopo de SERVIÇO — ver Javadoc da classe ("Simetria startPipeline/stopPipeline").
        glassesConnection = GlassesConnection(this)
        glassesConnection.listener = glassesListener

        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, validateTapReceiver,
                IntentFilter("com.raphael.handmouse.VALIDATE_TAP"),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, forceCaptureReceiver,
                IntentFilter("com.raphael.handmouse.FORCE_CAPTURE"),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, forceCaptureKotlinReceiver,
                IntentFilter("com.raphael.handmouse.FORCE_CAPTURE_KOTLIN"),
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        uvcCameraHelper = UvcCameraHelper(this)
        uvcCameraHelper.listener = uvcListener

        prefs = Prefs(this)
        uvcCameraHelper.cameraControls = prefs.cameraControls
        trackingEnabled = prefs.trackingEnabled
        ignoreBottomFraction = prefs.ignoreBottomPct / 100f
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        recorder = EyeRecorder(this, recorderStatusListener)
        recorder.recoverPending()
        RecordTileService.refresh(this)
        snapshot = EyeSnapshot(this) { _, name, error ->
            handler.post {
                lastPhotoText = if (name != null) getString(R.string.notif_photo_saved, name)
                else getString(R.string.notif_photo_failed, error ?: "")
                log(lastPhotoText!!)
                refreshNotification()
            }
        }

        handTracker.resultListener = handTrackerResultListener
        handTracker.errorHandler = handTrackerErrorHandler

        thermalMonitor = ThermalMonitor(this) { fps ->
            thermalFps = fps
            applyInferenceFps()
        }

        val filter = IntentFilter(ACTION_CAMERA_USB_PERMISSION)
        val usbDetachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val usbAttachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
            registerReceiver(usbDetachReceiver, usbDetachFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(usbAttachReceiver, usbAttachFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(cameraPermissionReceiver, filter)
            registerReceiver(usbDetachReceiver, usbDetachFilter)
            registerReceiver(usbAttachReceiver, usbAttachFilter)
        }

        // Handshake com o HandMouseAccessibilityService (Tarefa 4) — ver Javadoc da classe.
        // Se a acessibilidade já estiver ativa (reinício deste FGS com a11y ligada), registra o
        // CursorPipeline dela já; senão, ela mesma se registra aqui quando conectar.
        HandMouseAccessibilityService.instance?.attachTo(this)

        // Rede de segurança de reconexão — ver GLASSES_POLL_INTERVAL_MS (6ª rodada).
        controlHandler.postDelayed(glassesPresencePoll, GLASSES_POLL_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recorder actions (notification buttons / UI) on an already-running service: handled
        // without re-entering startForeground — that call needs the glasses present, and a
        // momentary detach must never kill an ongoing recording.
        EyeAction.fromIntent(intent)?.let { action ->
            perform(this, action)
            if (foregroundStarted) return START_STICKY
        }
        // Guard (2026-07-23): FGS `connectedDevice` só é autorizado pelo sistema se houver um
        // dispositivo USB associado ao app NO MOMENTO do startForeground. Iniciar a captura sem
        // os óculos no USB (ex.: watchdog religando após reinstalação, com o cabo fora) estourava
        // SecurityException, derrubava o processo INTEIRO e — pior — punha o a11y service no
        // backoff de serviço crashado do Android (restart agendado pra 2h). Encerrar
        // graciosamente aqui (stopSelf ANTES do timeout de startForegroundService é o escape
        // documentado) deixa o app vivo esperando o próximo ATTACH. [start] e o watchdog também
        // checam a presença dos óculos antes de chamar — este catch é a última linha de defesa
        // (corrida attach/detach entre a checagem do chamador e o startForeground real).
        // Type microphone (2026-07-23, controle por voz) SÓ quando RECORD_AUDIO já foi
        // concedida — declarar o type sem a permissão lança SecurityException no start. Sem o
        // type, o SpeechRecognizer falha com o app em background (mic bloqueado no Android 14+).
        // Nota: reinícios do watchdog EM BACKGROUND podem ser recusados por causa do type de
        // mic (restrição do Android 14) — o catch de ForegroundServiceStartNotAllowedException
        // do watchdog já cobre (notificação pro usuário reabrir). VALIDAR EM HARDWARE.
        val micType = if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or micType,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground(connectedDevice) negado (óculos ausentes?) — encerrando sem crash: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!foregroundStarted) maybeAutoRecord() // first start of this service = a new connection
        foregroundStarted = true
        acquireWakeLock()
        thermalMonitor.start()
        controlHandler.post { startPipeline() } // orquestração fora da main — ver controlThread
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Teardown na thread de controle (fix de ANR — ver controlThread): posta o stopPipeline
        // e encerra a thread com quitSafely() — mensagens já na fila (o próprio stopPipeline)
        // rodam antes de a thread morrer; os retries com delay pendentes morrem junto (correto:
        // o serviço acabou).
        // glassesConnection.shutdown() vai NO MESMO post, depois do stopPipeline: garante que
        // toda ativação já enfileirada no controlHandler rodou antes de o executor fechar
        // (submissão pós-shutdown seria rejeitada — enableEyeCamera trata, mas nem chega a
        // acontecer com essa ordem).
        controlHandler.post { stopPipeline(); glassesConnection.shutdown(); imuClient.stop() }
        controlThread.quitSafely()
        // Recorder: closes the open segment cleanly (MKV sizes + cues) before we die.
        // foregroundStarted=false first: the final status it posts must not re-post the
        // foreground notification after the service is gone (it would linger as an orphan).
        foregroundStarted = false
        if (::prefs.isInitialized) prefs.raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::recorder.isInitialized) recorder.release()
        if (::snapshot.isInitialized) snapshot.release()
        // thermalMonitor.stop() fica FORA de stopPipeline() de propósito: stopPipeline() também
        // roda no meio da vida do serviço (ver usbDetachReceiver — desconexão de cabo não mata o
        // FGS, só o pipeline), e nada re-chama thermalMonitor.start() nesse caso (só
        // onStartCommand chama) — pará-lo ali deixaria a térmica desligada pra sempre até o
        // FGS inteiro reiniciar. Aqui em onDestroy não tem esse problema: o serviço acabou.
        if (::thermalMonitor.isInitialized) thermalMonitor.stop()
        try { unregisterReceiver(cameraPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbDetachReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(validateTapReceiver) }
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(forceCaptureReceiver) }
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(forceCaptureKotlinReceiver) }
        releaseWakeLock()
        instance = null
        // The Activity only hears transitions; without this it kept showing the last live state.
        state = PipelineState.IDLE
        handler.post { stateListener?.onStateChanged(PipelineState.IDLE, getString(R.string.conn_capture_stopped)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Resume only a stopped or failed pipeline. USB attach and Activity resume can arrive
     * during camera re-enumeration; rescanning HID then starts a competing UVC session. */
    fun retryScan(userInitiated: Boolean = false) {
        controlHandler.post {
            if (userInitiated && waitingForUsbReattach) {
                // An explicit Connect gets one more bounded attempt instead of being ignored.
                waitingForUsbReattach = false
                failedRecoveryCycles = 0
            }
            if (waitingForUsbReattach) {
                Log.d(TAG, "retryScan: waiting for a USB disconnect before retrying")
                return@post
            }
            if (userInitiated || !pipelineActive || state == PipelineState.IDLE || state == PipelineState.ERROR) {
                controlHandler.removeCallbacks(autoAttachRunnable)
                autoAttachPending = false
                if (pipelineActive) stopPipeline()
                controlHandler.removeCallbacks(streamRestartRunnable)
                Log.d(TAG, "retryScan: restarting stopped pipeline")
                startPipeline()
            } else {
                Log.d(TAG, "retryScan ignored while $state")
            }
        }
    }

    /** Resume a HID scan when the Activity owned the USB permission dialog. */
    fun onUsbPermissionResolved() {
        controlHandler.post {
            if (pipelineActive && state == PipelineState.CONNECTING_HID) {
                glassesConnection.scanForGlasses()
            }
        }
    }

    /** Re-declara os types do FGS com o type de MICROFONE recomputado (fix de revisão final
     * 2026-07-23): o type era computado UMA vez no onStartCommand — capture iniciada ANTES do
     * grant de RECORD_AUDIO ficava sem o type até reiniciar o serviço (toda sessão de voz em
     * background falhava no mic). Chamado pela MainActivity (foreground) quando o grant chega
     * com a captura já rodando — startForeground de novo com a MESMA notificação é legal e
     * só atualiza os types. */
    fun refreshForegroundServiceTypes() {
        val micType = if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or micType,
            )
            Log.d(TAG, "FGS types atualizados (mic=${micType != 0})")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao atualizar FGS types (inofensivo — vale no próximo start): ${e.message}")
        }
    }

    /** Ver Javadoc da classe ("Simetria startPipeline/stopPipeline", achado C1). Idempotente —
     * chamar de novo com o pipeline já ativo é um no-op (evita duplicar a sessão do `handTracker`/
     * `frameConverter` se [onStartCommand] rodar 2x, ex.: `START_STICKY` + `MainActivity`
     * chamando `EyeCaptureService.start()` de novo com o serviço já rodando). */
    private fun startPipeline(freshBudget: Boolean = true) {
        if (pipelineActive) {
            Log.d(TAG, "startPipeline: pipeline já ativo — ignorando chamada duplicada")
            return
        }
        pipelineActive = true
        cameraScanAttempts = 0
        framesSinceStreamStart = 0
        reconnectNudgeSent = false // episódio de desconexão encerrado — rearma o aviso do poll
        // Orçamento de restart NOVO só em inícios "de verdade" (usuário/attach/retryScan) — fix
        // 4ª rodada 2026-07-23 em duas partes: (a) sem reset NENHUM, um esgotamento antigo
        // deixava streamRestartAttempts >= MAX pra sempre (todo stall futuro ia direto pra
        // ERROR); (b) com reset INCONDICIONAL, o próprio loop de auto-recuperação renovava o
        // orçamento a cada ciclo (restart infinito com a câmera morta, sem nunca chegar no
        // backoff). freshBudget=false nos restarts internos resolve os dois.
        if (freshBudget) streamRestartAttempts = 0

        // handTracker.start() recria a HandlerThread/Handler internos (ver Javadoc de
        // HandTracker.start/stop). HandlerThread.start() + Handler(thread.looper) bloqueiam até o
        // Looper existir, então workerHandler já não é nulo logo depois desta chamada retornar.
        handTracker.start(this)
        // start() resets the tracker's fps cap — re-apply thermal/idle state (Eye Tools fork).
        lastHandSeenMs = SystemClock.uptimeMillis()
        idleInference = false
        applyInferenceFps()
        applyLandmarkLogPreference()
        applyDatasetPreference()
        applyImuState()

        // Instância NOVA a cada sessão (ver comentário do campo) — a anterior, se existia, já
        // foi `release()`ada em stopPipeline(). Consumido em [decoderFrameListener], na thread do
        // decoder (ver Javadoc da classe, "Decode em modo ByteBuffer").
        frameConverter = FrameConverter()

        // Idem pro caminho MJPEG (só um dos dois alimenta o MediaPipe por sessão; os dois alocam
        // seus buffers sob demanda no 1º frame, então criar ambos aqui não custa memória).
        mjpegRgbaRing = RgbaBufferRing()

        // Eye Tools fork: pick the stream format up-front (HEVC when a recording is running or
        // auto-record is armed) so no extra restart is needed once the stream is up.
        applyFormatPreference()

        setState(PipelineState.CONNECTING_HID, "Connecting to glasses...")
        glassesConnection.start()
    }

    /** Ver Javadoc da classe ("Simetria startPipeline/stopPipeline" e "Fence contra corrida",
     * achados C1/I1). Idempotente — seguro chamar 2x seguidas (ex.: [onDestroy] depois de um
     * [usbDetachReceiver] que já rodou isto). */
    private fun stopPipeline() {
        if (!pipelineActive) return
        pipelineActive = false
        UsbPermissionRequests.finish(pendingCameraDevice?.deviceName)
        pendingCameraDevice = null

        // Cancela o retry de scan ANTES de mais nada — sem isto, um retry já agendado podia
        // disparar depois do teardown e recriar estado parcial (achado C1: "a 2s retry loop is
        // never cancelled").
        controlHandler.removeCallbacks(findCameraRunnable)
        // Idem para o restart de auto-recuperação: um teardown genuíno (cabo desconectado,
        // onDestroy) NÃO deve deixar um startPipeline agendado disparar depois. O fluxo de
        // auto-recuperação (onStreamStalled) chama stopPipeline() e SÓ ENTÃO reagenda, então o
        // cancelamento aqui não atrapalha a recuperação — só barra restart após stop de verdade.
        controlHandler.removeCallbacks(streamRestartRunnable)

        // Para a FONTE de frames primeiro: sem stream USB, nenhum access unit novo chega ao
        // decoder a partir daqui.
        uvcCameraHelper.stopStream()

        // hevcDecoder.stop() faz join da thread do decoder (ver Javadoc de HevcDecoder.stop):
        // quando retorna, nenhum onFrame/convert()/detectAsync-post novo está mais em voo. O
        // mjpegDecoder segue a MESMA disciplina (join síncrono no stop): depois dele nenhum onFrame
        // novo posta detectAsync. Só um dos dois existe por stream; o outro stop() é no-op seguro.
        // Ambos ANTES do awaitWorkerDrain abaixo (mesmo ponto do fence): o join garante que não
        // entram MPImages novos, e o awaitWorkerDrain drena os já enfileirados no HandTracker.
        // No caminho MJPEG nada do anel do decoder sobrevive a este ponto: o que foi entregue ao
        // MediaPipe é sempre uma CÓPIA feita dentro do onFrame (ver [mjpegFrameListener]).
        hevcDecoder?.stop()
        hevcDecoder = null
        mjpegDecoder?.stop()
        mjpegDecoder = null
        glassesConnection.stop()

        // Fence (achado I1, REORDENADA no fix do SIGSEGV 2026-07-23): o dreno da HandlerThread
        // do HandTracker precisa vir ANTES de frameConverter.release(). A ingestão do MediaPipe
        // (nativeCreateCpuImage — memmove do buffer RGBA do FrameConverter) roda NAQUELA thread
        // (detectAsync posta pra lá), não na thread do decoder — o join do decoder acima NÃO a
        // cobre. Liberar os buffers nativos antes do dreno era exatamente o SEGV_MAPERR
        // registrado em hardware às 01:05 de 23/07 (crash nativo em __memmove_aarch64 na thread
        // "HandTracker").
        awaitWorkerDrain()

        frameConverter?.release()
        frameConverter = null

        // Mesmo ponto do fence, embora aqui não haja risco de use-after-free: os buffers do anel
        // MJPEG são memória gerenciada, então uma ingestão em voo os mantém vivos sozinha.
        mjpegRgbaRing?.release()
        mjpegRgbaRing = null

        // handTracker.stop() por ÚLTIMO: encerra a HandlerThread usada pela fence acima — se
        // rodasse antes, awaitWorkerDrain não teria mais um Handler vivo pra postar a barreira.
        handTracker.stop()
        applyLandmarkLogPreference() // pipelineActive=false → closes the log
        applyDatasetPreference()
        applyImuState() // → IMU link closed
    }

    /** Ver Javadoc da classe ("Fence contra corrida", achado I1). Bloqueia a thread chamadora
     * (a de controle — [stopPipeline] só é chamado dela) até a `HandlerThread` do [handTracker]
     * processar uma barreira vazia, ou até [timeoutMs] esgotar (nunca trava [stopPipeline] pra
     * sempre se o worker estiver preso por algum outro motivo — timeout generoso o bastante pra
     * um `convert()`/`detectAsync` legítimo terminar, curto o bastante pra não travar
     * perceptivelmente quem chama [stopPipeline] a partir de um `BroadcastReceiver`). */
    private fun awaitWorkerDrain(timeoutMs: Long = 500L) {
        val workerHandler = handTracker.workerHandler ?: return
        val latch = CountDownLatch(1)
        workerHandler.post { latch.countDown() }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "awaitWorkerDrain: timeout de ${timeoutMs}ms — seguindo mesmo assim")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "awaitWorkerDrain: interrompido", e)
        }
    }

    private fun setState(newState: PipelineState, message: String) {
        state = newState
        Log.d(TAG, "[$newState] $message")
        handler.post { stateListener?.onStateChanged(newState, message) }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        handler.post { stateListener?.onLog(message) }
    }

    private val glassesListener = object : GlassesConnection.Listener {
        override fun onConnected(deviceName: String) {
            setState(PipelineState.CAMERA_ENABLING, "Connected: $deviceName — enabling Eye camera...")
            // Escalada (ver DEAD_STREAMS_FORCE_RECONFIGURE): depois de N streams natimortos, o
            // atalho "uvc0 já ativa" está mentindo — força a reconfiguração/re-enumeração que
            // reseta a câmera de verdade. Consome o contador ao escalar.
            val force = consecutiveDeadStreams >= DEAD_STREAMS_FORCE_RECONFIGURE
            if (force) {
                log("$consecutiveDeadStreams early stream failures — reconfiguring camera USB")
                consecutiveDeadStreams = 0
            }
            glassesConnection.enableEyeCamera(forceReconfigure = force)
        }

        override fun onDisconnected() {
            setState(PipelineState.IDLE, "Disconnected")
        }

        override fun onError(message: String) {
            setState(PipelineState.ERROR, message)
        }

        override fun onLog(message: String) {
            log(message)
        }

        override fun onCameraEnabled() {
            log("Camera enabled — waiting for USB permission after re-enumeration...")
            // Chamado na thread "GlassesEnableCamera" — orquestração sempre na de controle.
            controlHandler.post { findAndStartCamera() }
        }
    }

    /** Ver [CameraScanRetryPolicy] (achado Important I7) — antes deste fix, reagendava a si
     * mesma PARA SEMPRE enquanto a câmera não fosse encontrada. Agora cobra um teto de
     * [CameraScanRetryPolicy.MAX_ATTEMPTS] tentativas antes de desistir com `ERROR` e uma
     * mensagem acionável (em vez de deixar a UI presa em "Procurando interface UVC..."
     * indefinidamente). */
    private fun findAndStartCamera() {
        // O pipeline pode ter sido derrubado (detach/stall) enquanto a thread de enableEyeCamera
        // ainda corria — um onCameraEnabled atrasado não pode ressuscitar um scan órfão.
        if (!pipelineActive) return
        setState(PipelineState.FINDING_CAMERA, "Looking for UVC interface...")
        val device = uvcCameraHelper.findCamera()
        if (device == null) {
            cameraScanAttempts++
            if (CameraScanRetryPolicy.shouldRetry(cameraScanAttempts)) {
                controlHandler.postDelayed(findCameraRunnable, CAMERA_SCAN_RETRY_DELAY_MS)
            } else {
                setState(
                    PipelineState.ERROR,
                    "Eye camera not found after $cameraScanAttempts attempts — check the USB cable " +
                        "and enable the Eye camera in the official Glasses Control app."
                )
            }
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            UsbPermissionRequests.finish(device.deviceName)
            pendingCameraDevice = null
            uvcCameraHelper.startStream(device)
        } else {
            setState(PipelineState.REQUESTING_CAMERA_PERMISSION, "Requesting USB permission (2/2)...")
            if (pendingCameraDevice?.deviceName == device.deviceName) {
                Log.d(TAG, "Camera USB permission already pending for ${device.deviceName}")
                return
            }
            if (!UsbPermissionRequests.begin(device.deviceName)) {
                setState(PipelineState.ERROR, getString(R.string.conn_usb_permission_busy))
                return
            }
            pendingCameraDevice = device
            val permIntent = Intent(ACTION_CAMERA_USB_PERMISSION).apply { setPackage(packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            try {
                usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 1, permIntent, flags))
            } catch (e: Exception) {
                UsbPermissionRequests.finish(device.deviceName)
                pendingCameraDevice = null
                setState(PipelineState.ERROR, "Camera USB permission request failed: ${e.message}")
            }
        }
    }

    private val uvcListener = object : UvcCameraHelper.Listener {
        override fun onCameraFound(description: String) {
            log(description)
        }

        override fun onStreamStarted() {
            framesSinceStreamStart = 0 // contagem por-stream, pra decidir estabilidade (ver onFrameReceived)
            // Eye Tools fork: decoder / recorder / photo wait for the first real frame, whose
            // bytes say what the camera actually sends (see PayloadFormat).
            payloadFormat = PayloadFormat.UNKNOWN
            setState(PipelineState.CAMERA_ENABLING, "USB ready — waiting for camera video...")
        }

        override fun onStreamStopped() {
            setState(PipelineState.IDLE, "Stream stopped")
        }

        override fun onError(message: String) {
            setState(PipelineState.ERROR, message)
        }

        /** Stall do stream (a câmera Eye para de entregar bytes — soluço intermitente medido em
         * hardware: acontece até no MEIO de um stream saudável, p.ex. após 5056 frames). Chamado
         * na thread do stream — tudo agendado na [controlHandler] (nunca na main — ver
         * [controlThread]). Duas rotas (2026-07-23, fix do "às vezes dá uma congelada"):
         *
         * - **Soft restart** (stream JÁ estava estável — [STREAM_STABLE_FRAME_COUNT]+ frames):
         *   reinicia SÓ a camada UVC + decoder, imediatamente e sem derrubar HandTracker/
         *   FrameConverter/sessão HID. Congelamento total ≈ 1s de detecção + ~0,5s de
         *   re-negociação, contra ~4s do caminho antigo (teardown completo + 1,5s de delay +
         *   handshake HID + re-init do MediaPipe).
         * - **Full restart** (stall na ATIVAÇÃO, 0..89 frames): o caminho original — a ativação
         *   emperrada às vezes precisa mesmo do ciclo completo de enableEyeCamera. */
        override fun onStreamStalled() {
            controlHandler.post {
                if (!pipelineActive) return@post

                // Escalada de câmera travada profundo (ver DEAD_STREAMS_FORCE_RECONFIGURE):
                // morte JOVEM (antes de estabilizar) conta; só stream que chegou a estabilizar
                // zera — o padrão de janela ruim é uma cascata de streams de 30-160 frames.
                if (framesSinceStreamStart < STREAM_STABLE_FRAME_COUNT) {
                    consecutiveDeadStreams++
                } else {
                    consecutiveDeadStreams = 0
                }

                if (streamRestartAttempts >= MAX_STREAM_RESTART_ATTEMPTS) {
                    failedRecoveryCycles++
                    stopPipeline()
                    if (prefs.manualConnect) {
                        // Manual connection: no background retry loop; the user decides.
                        setState(PipelineState.ERROR, getString(R.string.conn_manual_camera_failed))
                        return@post
                    }
                    if (failedRecoveryCycles >= MAX_FAILED_RECOVERY_CYCLES) {
                        waitingForUsbReattach = true
                        setState(PipelineState.ERROR, getString(R.string.camera_reconnect_required))
                        return@post
                    }
                    setState(PipelineState.ERROR,
                        "Camera did not start after ${MAX_STREAM_RESTART_ATTEMPTS} attempts — retrying in ${STREAM_ERROR_RETRY_DELAY_MS / 1000}s.")
                    controlHandler.removeCallbacks(streamRestartRunnable)
                    controlHandler.postDelayed(streamRestartRunnable, STREAM_ERROR_RETRY_DELAY_MS)
                    return@post
                }
                streamRestartAttempts++

                if (framesSinceStreamStart >= STREAM_STABLE_FRAME_COUNT) {
                    log("Stable stream stalled — restarting stream (attempt $streamRestartAttempts/$MAX_STREAM_RESTART_ATTEMPTS)")
                    uvcCameraHelper.stopStream() // a thread do stream já saiu do loop (join rápido)
                    hevcDecoder?.stop() // decoder NOVO por stream (onStreamStarted cria outro)
                    hevcDecoder = null
                    mjpegDecoder?.stop() // idem — só um dos dois existe; o outro stop() é no-op
                    mjpegDecoder = null
                    setState(PipelineState.CAMERA_ENABLING, "Reconnecting camera stream...")
                    // Settle antes de renegociar (ver SOFT_RESTART_SETTLE_MS) — reusa o
                    // findCameraRunnable, que stopPipeline já sabe cancelar.
                    controlHandler.removeCallbacks(findCameraRunnable)
                    controlHandler.postDelayed(findCameraRunnable, SOFT_RESTART_SETTLE_MS)
                    return@post
                }

                log("Stream delivered no frames — restarting activation (attempt $streamRestartAttempts/$MAX_STREAM_RESTART_ATTEMPTS)")
                setState(PipelineState.CAMERA_ENABLING, "Restarting camera (attempt $streamRestartAttempts)...")
                stopPipeline()
                controlHandler.removeCallbacks(streamRestartRunnable)
                controlHandler.postDelayed(streamRestartRunnable, STREAM_RESTART_DELAY_MS)
            }
        }

        override fun onLog(message: String) {
            log(message)
        }

        override fun onFrameReceived(data: ByteArray) {
            // Roteia pro decoder ATIVO. Só um dos dois é não-nulo por stream (bifurcação em
            // onStreamStarted por activeFormatSubtype) — MJPEG e HEVC nunca coexistem, então as
            // duas chamadas null-safe custam nada e mantêm o roteamento sem um branch extra.
            // Eye Tools fork: the recorder branch gets the SAME chunk (enqueue only, no copy).
            if (payloadFormat == PayloadFormat.UNKNOWN) {
                payloadFormat = PayloadFormat.detect(data)
                if (payloadFormat == PayloadFormat.UNKNOWN) return // filler before the first frame
                if (payloadFormat != uvcCameraHelper.activeFormatSubtype) {
                    log("Camera sends ${formatName(payloadFormat)} although ${formatName(uvcCameraHelper.activeFormatSubtype)} was negotiated")
                }
                recorder.onStreamStarted(payloadFormat, uvcCameraHelper.activeFrameWidth, uvcCameraHelper.activeFrameHeight)
                snapshot.onStreamStarted(payloadFormat)
                if (!trackingEnabled) setState(PipelineState.STREAMING, "Streaming ${formatName(payloadFormat)} (hand tracking off)")
            }
            // Count only chunks after a real codec signature has established a stream.
            // UVC EOF packets containing zeros are not video and cannot prove stability.
            if (framesSinceStreamStart < STREAM_STABLE_FRAME_COUNT) {
                framesSinceStreamStart++
                if (framesSinceStreamStart >= STREAM_STABLE_FRAME_COUNT) {
                    streamRestartAttempts = 0
                    failedRecoveryCycles = 0
                }
            }
            recorder.onChunk(data)
            snapshot.onChunk(data)

            if (trackingEnabled) {
                if (mjpegDecoder == null && hevcDecoder == null) createDecoderForActiveFormat()
                mjpegDecoder?.onAccessUnit(data)
                hevcDecoder?.onAccessUnit(data)
            } else if (mjpegDecoder != null || hevcDecoder != null) {
                // Tracking switched off mid-stream: drop the decode/inference load right away.
                hevcDecoder?.stop()
                hevcDecoder = null
                mjpegDecoder?.stop()
                mjpegDecoder = null
                handler.post { trackingListeners.forEach { it.onHandLost() } }
                setState(PipelineState.STREAMING, "Streaming (hand tracking off)")
            }
        }
    }

    /**
     * Creates the decoder for the ACTIVE stream format (UVC thread) — upstream onStreamStarted
     * body, extracted so tracking can be switched on mid-stream (Eye Tools fork).
     */
    private fun createDecoderForActiveFormat() {
        // Bifurcação pelo formato REAL do payload (Eye Tools fork — ver PayloadFormat: o
        // descritor pode anunciar HEVC e a câmera mandar JPEG). Só um decoder por stream.
        if (payloadFormat == PayloadFormat.MJPEG) {
            setState(PipelineState.STREAMING, "Streaming MJPEG...")
            mjpegDecoder = MjpegDecoder(dumpDir = File(filesDir, "mjpeg-dump")).apply {
                // O MESMO throttle térmico/fps do caminho HEVC (shouldAcceptFrame), só que ANTES
                // de decodificar — frame excedente é descartado sem gastar CPU no JPEG.
                frameGate = object : MjpegDecoder.FrameGate {
                    override fun shouldDecode(nowMs: Long): Boolean = handTracker.shouldAcceptFrame(nowMs)
                }
                frameListener = mjpegFrameListener
                errorListener = mjpegErrorListener
                start()
            }
        } else {
            setState(PipelineState.STREAMING, "Streaming — waiting for VPS/SPS/PPS...")
            hevcDecoder = HevcDecoder().apply {
                // Achado Important I2 — ver Javadoc de HevcDecoder ("Erros visíveis").
                errorListener = object : HevcDecoder.ErrorListener {
                    override fun onError(message: String, fatal: Boolean) {
                        if (fatal) setState(PipelineState.ERROR, message) else log(message)
                    }
                }
                frameListener = decoderFrameListener
                start()
            }
        }
    }

    // ================= Eye Tools fork: recorder control =================

    /** Starts a recording session (any thread). The stream switches to the recording format
     * (HEVC by default) if needed; the recorder begins at the next keyframe. */
    fun startRecording() {
        if (recorder.isRecording) return
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (prefs.recordAudio && !micGranted) log("Microphone permission missing — recording without audio")
        recorder.start(
            EyeRecorder.Config(
                splitMinutes = prefs.recordSplitMinutes,
                audio = prefs.recordAudio && micGranted,
                storage = if (prefs.recordStorage == "app") RecordingOutput.Storage.APP else RecordingOutput.Storage.GALLERY,
                maxFps = prefs.recordFps,
                gyroLog = prefs.recordGyroLog,
            ),
        )
        log("Recording started")
        controlHandler.post { applyFormatPreference(); applyImuState() }
    }

    fun stopRecording() {
        if (!recorder.isRecording) return
        recorder.stop()
        log("Recording stopped")
        controlHandler.post { applyFormatPreference(); applyImuState() }
    }

    val isRecording: Boolean get() = ::recorder.isInitialized && recorder.isRecording

    /** Still photo from the live stream (any thread). Result → notification sub-text. */
    fun takePhoto() {
        if (state != PipelineState.STREAMING) {
            lastPhotoText = getString(R.string.notif_photo_failed, getString(R.string.recorder_not_running))
            handler.post { refreshNotification() }
            return
        }
        snapshot.request()
    }

    /** Runs a recorder command (control thread, via [perform]). */
    private fun handle(action: EyeAction) {
        when (action) {
            EyeAction.RECORD_START -> startRecording()
            EyeAction.RECORD_STOP -> stopRecording()
            EyeAction.RECORD_TOGGLE -> if (isRecording) stopRecording() else startRecording()
            EyeAction.PHOTO -> takePhoto()
            else -> {} // tracking / capture-stop never reach the service
        }
    }

    /** Auto-record, once per connection (service start or glasses re-attached). The recorder
     * waits for the stream by itself; a manual stop stays stopped until the next connection. */
    private fun maybeAutoRecord() {
        if (prefs.recordAutoStart && !recorder.isRecording) {
            log("Auto-record: new connection — starting the recording")
            startRecording()
        }
    }

    private fun desiredFormatPreference(): UvcCameraHelper.FormatPreference {
        if (!recorder.isRecording) {
            // Tracking is most stable on MJPEG; with tracking off there is no reason to pay a
            // stream restart just to switch back.
            return if (trackingEnabled) UvcCameraHelper.FormatPreference.MJPEG_FIRST else uvcCameraHelper.formatPreference
        }
        return when (prefs.recordStream) {
            REC_STREAM_MJPEG -> UvcCameraHelper.FormatPreference.MJPEG_FIRST
            REC_STREAM_HEVC_NATIVE -> UvcCameraHelper.FormatPreference.HEVC_NATIVE_FIRST
            else -> UvcCameraHelper.FormatPreference.HEVC_REDUCED_FIRST
        }
    }

    /** Control thread. Applies the preferred stream order; restarts a live stream if it changed
     * (same path as the soft restart: UVC + decoders only, ~1.5 s). */
    private fun applyFormatPreference() {
        if (!uvcCameraHelper.setFormatPreference(desiredFormatPreference())) return
        if (!pipelineActive || state != PipelineState.STREAMING) return
        log("Switching the camera stream format (${uvcCameraHelper.formatPreference})")
        uvcCameraHelper.stopStream()
        hevcDecoder?.stop()
        hevcDecoder = null
        mjpegDecoder?.stop()
        mjpegDecoder = null
        setState(PipelineState.CAMERA_ENABLING, "Switching camera format...")
        controlHandler.removeCallbacks(findCameraRunnable)
        controlHandler.postDelayed(findCameraRunnable, SOFT_RESTART_SETTLE_MS)
    }

    /** Main thread: recorder status → UI listener + foreground notification. */
    private fun onRecorderStatusMain(status: EyeRecorder.Status) {
        val previous = recorderStatus
        recorderStatus = status
        recorderListener?.onRecorderStatus(status)
        val changed = status.recording != previous.recording || status.segmentIndex != previous.segmentIndex ||
            status.error != previous.error
        if (status.recording != previous.recording) RecordTileService.refresh(this)
        if (changed || (status.recording && SystemClock.elapsedRealtime() - lastNotifUpdateMs >= NOTIF_SIZE_REFRESH_MS)) {
            refreshNotification()
        }
    }

    /**
     * Roda na THREAD do decoder ([HevcDecoder.FrameListener.onFrame], ver Javadoc da classe
     * "Decode em modo ByteBuffer"). A `image` chega como parâmetro (obtida via
     * `MediaCodec.getOutputImage`) e é válida APENAS durante esta chamada — o decoder a fecha
     * logo após retornar, então NÃO chamamos `image.close()` aqui e a consumimos de forma
     * SÍNCRONA (convert copia os pixels pro buffer RGBA do pool).
     *
     * Throttle ANTES da conversão YUV->RGBA (`shouldAcceptFrame`, requisito do brief): frames
     * excedentes são pulados sem gastar CPU convertendo (o decoder libera o buffer de saída
     * assim que onFrame retorna).
     */
    private val decoderFrameListener = object : HevcDecoder.FrameListener {
        override fun onFrame(image: Image) {
            // frameConverter é de escopo de SESSÃO (ver campo) — pode já ter sido `release()`ado
            // e nulado por stopPipeline(). A checagem é defensiva: stopPipeline() faz join da
            // thread do decoder DENTRO de hevcDecoder.stop() ANTES de release()ar o frameConverter
            // (ver "Fence contra corrida"), então na prática este callback nunca vê fc==null, mas
            // custa nada garantir que nunca operamos num FrameConverter morto. NÃO fechar image
            // (o decoder cuida disso).
            val fc = frameConverter ?: return

            val now = SystemClock.uptimeMillis()
            if (!handTracker.shouldAcceptFrame(now)) return

            try {
                val converted = fc.convert(image)
                val mpImage = ByteBufferImageBuilder(
                    converted.rgba,
                    converted.width,
                    converted.height,
                    MPImage.IMAGE_FORMAT_RGBA,
                ).build()
                handTracker.detectAsync(mpImage, now)

                // Ver [previewFramesEnabled] — sem preview visível, nenhuma alocação de UI.
                if (previewFramesEnabled) {
                    val previewBitmap = previewBitmapFrom(converted)
                    val conversionMs = fc.averageConversionMs
                    handler.post { trackingListeners.forEach { it.onFrameConverted(previewBitmap, conversionMs) } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao converter frame", e)
            }
        }
    }

    /**
     * Bitmap NOVO por frame (não pooled, ao contrário dos buffers do [FrameConverter]):
     * evita corrida entre esta thread (que escreveria os pixels) e a UI thread (que desenha o
     * Bitmap anterior) caso reaproveitássemos um único Bitmap mutável entre as duas threads.
     * É só o preview de debug — os buffers que alimentam o MediaPipe SÃO pooled (requisito do
     * brief), este aqui é uma concessão deliberada à simplicidade/segurança da UI de debug.
     */
    private fun previewBitmapFrom(converted: FrameConverter.ConvertedFrame): Bitmap {
        val bitmap = Bitmap.createBitmap(converted.width, converted.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(converted.rgba.duplicate())
        return bitmap
    }

    /**
     * Roda na THREAD do decoder MJPEG ([MjpegDecoder.FrameListener.onFrame], SÍNCRONO). O `bitmap`
     * pertence ao anel interno do decoder e só é VÁLIDO durante esta chamada — o decoder o reutiliza
     * (`inBitmap`) nos próximos frames, então NÃO o retemos nem chamamos `recycle()` nele.
     *
     * Sem etapa de conversão YUV->RGBA aqui (ao contrário do caminho HEVC): o JPEG já vira `Bitmap`
     * ARGB_8888 no próprio decoder — só copiamos os pixels pro anel RGBA (ver [mpImageFrom]).
     *
     * O throttle térmico/fps NÃO é reavaliado aqui: já foi aplicado ANTES do decode via
     * [MjpegDecoder.FrameGate] (shouldAcceptFrame), então todo frame que chega aqui já passou pelo
     * portão — reavaliar seria decodificar de graça um frame pra descartar.
     */
    private val mjpegFrameListener = object : MjpegDecoder.FrameListener {
        override fun onFrame(bitmap: Bitmap, timestampMs: Long) {
            // Escopo de SESSÃO (ver campo): no teardown o anel pode já ter sido solto. Mesma
            // checagem defensiva do frameConverter em [decoderFrameListener] — na prática nunca
            // dispara (o join do mjpegDecoder.stop() vem antes), e descartar um frame durante o
            // teardown não tem consequência.
            val ring = mjpegRgbaRing ?: return
            try {
                handTracker.detectAsync(mpImageFrom(bitmap, ring), timestampMs)
                datasetRecorder?.offer(bitmap, timestampMs, SystemClock.uptimeMillis() - lastHandSeenMs < DATASET_HAND_RECENT_MS)

                // Ver [previewFramesEnabled] — sem preview visível, nenhuma alocação de UI. A CÓPIA
                // é obrigatória: o bitmap do anel é reutilizado no próximo frame, então não pode ir
                // pra main thread (que o desenharia depois). conversionMs=0.0: não há conversão.
                if (previewFramesEnabled) {
                    // The source 1080p JPEG includes eight noisy bottom rows. The decoder
                    // samples by two, so hide the corresponding four rows in the preview.
                    val previewCopy = if (bitmap.width == 960 && bitmap.height == 540) {
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, 536)
                    } else {
                        Bitmap.createBitmap(bitmap)
                    }
                    handler.post { trackingListeners.forEach { it.onFrameConverted(previewCopy, 0.0) } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar frame MJPEG", e)
            }
        }
    }

    /**
     * Embrulha o `bitmap` do anel do [MjpegDecoder] num [MPImage] SEM alocar por frame: copia os
     * pixels (`copyPixelsToBuffer`, um memcpy) pro próximo buffer do [ring] e entrega esse buffer
     * ao `ByteBufferImageBuilder` — o MESMO tipo de container que o caminho HEVC já usa.
     * `Bitmap.Config.ARGB_8888` tem layout R,G,B,A em memória, que é exatamente
     * `MPImage.IMAGE_FORMAT_RGBA` (o próprio MediaPipe faz esse mapeamento ao ingerir um Bitmap).
     *
     * ## Por que NÃO entregar o bitmap (nem uma cópia dele) pro `BitmapImageBuilder`
     * `BitmapImageBuilder(b)` faz o `MPImage` DONO do bitmap: `MPImage.close()` — chamado por
     * [HandTracker.onMediaPipeResult] no callback do MediaPipe — executa `Bitmap.recycle()` nele.
     * Entregar o bitmap do anel direto reciclava o anel por baixo do decoder ("Cannot reuse a
     * recycled Bitmap" a cada frame, visto em hardware — PR #1) e ainda entregava um slot que o
     * decoder já podia ter sobrescrito, porque a ingestão do MediaPipe não roda aqui e sim na
     * `HandlerThread` do [handTracker] (`detectAsync` posta pra lá). Entregar uma CÓPIA do bitmap
     * conserta a corretude, mas alocava ~1MB POR FRAME a até 60fps — justamente a pressão de GC
     * que o `inBitmap` do decoder existe pra evitar. Com o container de `ByteBuffer` o `close()` é
     * no-op: o buffer volta pro anel e o custo por frame vira só o memcpy.
     *
     * ## Fallback: padding de linha
     * `PacketCreator.createImage` assume `widthStep = width * 4` (e exige capacidade EXATA). Skia
     * não põe padding em bitmaps ARGB_8888, mas se algum dispositivo devolver `rowBytes` maior,
     * `copyPixelsToBuffer` copiaria o padding junto e a imagem sairia cisalhada — nesse caso
     * caímos no caminho da cópia de bitmap (correto, só mais caro), logando UMA vez.
     */
    private fun mpImageFrom(bitmap: Bitmap, ring: RgbaBufferRing): MPImage {
        if (bitmap.rowBytes != bitmap.width * 4) {
            if (!loggedMjpegStrideFallback) {
                loggedMjpegStrideFallback = true
                Log.w(
                    TAG,
                    "Bitmap MJPEG com padding de linha (rowBytes=${bitmap.rowBytes}, " +
                        "width=${bitmap.width}) — caindo na cópia de bitmap por frame",
                )
            }
            return BitmapImageBuilder(Bitmap.createBitmap(bitmap)).build()
        }

        val buffer = ring.next(bitmap.width, bitmap.height)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        return ByteBufferImageBuilder(
            buffer,
            bitmap.width,
            bitmap.height,
            MPImage.IMAGE_FORMAT_RGBA,
        ).build()
    }

    /**
     * Erros do [MjpegDecoder] (chamado na thread do decoder MJPEG). Ver Javadoc da classe,
     * "Pipeline MJPEG — 7ª rodada": o UvcCameraHelper NÃO enxerga falha de DECODE (só vê bytes
     * fluindo pelo endpoint), então quem reage a um decode persistentemente quebrado é o serviço.
     *
     * `fatal=true` → na controlHandler (nunca na main — ver [controlThread]): rebaixa o formato
     * ativo pro próximo candidato (MJPEG → HEVC 1080p → HEVC nativo) e reinicia a sessão pelo MESMO
     * caminho do full restart interno do [uvcListener.onStreamStalled] (stopPipeline +
     * streamRestartRunnable com [STREAM_RESTART_DELAY_MS]). Assim o usuário ganha o HEVC funcional
     * em segundos, sem intervenção, em vez de a UI morrer em ERROR. `fatal=false` → só log.
     */
    private val mjpegErrorListener = object : MjpegDecoder.ErrorListener {
        override fun onError(message: String, fatal: Boolean) {
            if (!fatal) {
                log(message)
                return
            }
            controlHandler.post {
                if (!pipelineActive) return@post
                Log.e(TAG, "Decode MJPEG fatal — rebaixando formato e reiniciando a sessão: $message")
                uvcCameraHelper.demoteActiveFormat("decode MJPEG falhou persistentemente: $message")
                stopPipeline()
                controlHandler.removeCallbacks(streamRestartRunnable)
                controlHandler.postDelayed(streamRestartRunnable, STREAM_RESTART_DELAY_MS)
            }
        }
    }

    private val handTrackerResultListener = object : HandTracker.ResultListener {
        override fun onResult(result: HandTracker.Result) {
            landmarkLog?.logResult(result)
            if (HandZone.isInBottomZone(result.points, ignoreBottomFraction)) {
                handAbsent() // hand in the ignored zone (handlebars / desk) = no hand
                return
            }
            lastHandSeenMs = SystemClock.uptimeMillis()
            if (idleInference) {
                idleInference = false
                applyInferenceFps()
            }
            if (handLostPending.getAndSet(false)) handler.removeCallbacks(handLostGraceRunnable)
            handler.post { trackingListeners.forEach { it.onHandResult(result) } }
        }

        override fun onHandLost() {
            landmarkLog?.logLost(SystemClock.uptimeMillis())
            handAbsent()
        }

        private fun handAbsent() {
            // Idle inference: checked here (called for every hand-less inference) instead of a timer.
            if (!idleInference && SystemClock.uptimeMillis() - lastHandSeenMs > IDLE_AFTER_MS) {
                idleInference = true
                applyInferenceFps()
                Log.d(TAG, "No hand for ${IDLE_AFTER_MS}ms — idle inference at ${IDLE_INFERENCE_FPS}fps")
            }
            if (handLostPending.compareAndSet(false, true)) {
                handler.postDelayed(handLostGraceRunnable, HAND_LOST_GRACE_MS)
            }
        }
    }

    private val handTrackerErrorHandler = object : HandTracker.ErrorHandler {
        override fun onError(error: RuntimeException) {
            val message = error.message ?: error.toString()
            Log.e(TAG, "Erro de tracking: $message", error)
            handler.post { trackingListeners.forEach { it.onTrackingError(message) } }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:capture").apply {
            setReferenceCounted(false)
            acquire() // sem timeout — decisão documentada no Javadoc da classe ("Wakelock", T5)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    getString(R.string.notif_channel_capture),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            // IMPORTANCE_HIGH de propósito — ver comentário de NOTIF_CHANNEL_ID_USB_DETACHED
            // (fix de revisão da T5: é a importância do CANAL que governa heads-up/full-screen).
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID_USB_DETACHED,
                    getString(R.string.notif_channel_usb_detached),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun actionIntent(action: EyeAction, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, EyeCaptureService::class.java).setAction(action.intentAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private val openAppIntent by lazy {
        PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val recordStartIntent by lazy { actionIntent(EyeAction.RECORD_START, 11) }
    private val recordStopIntent by lazy { actionIntent(EyeAction.RECORD_STOP, 14) }
    private val photoIntent by lazy { actionIntent(EyeAction.PHOTO, 13) }
    private val trackingIntent by lazy { actionIntent(EyeAction.TRACKING_TOGGLE, 12) }

    private fun buildNotification(): Notification {
        val st = if (::recorder.isInitialized) recorderStatus else EyeRecorder.Status()
        val recording = st.recording
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(if (recording) R.string.notif_recording_title else R.string.app_name))
            .setContentText(
                if (recording) android.text.format.Formatter.formatShortFileSize(this, st.sessionBytes)
                else getString(R.string.notif_capture_text),
            )
            .setSmallIcon(if (recording) android.R.drawable.presence_video_online else android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .setSubText(lastPhotoText)
            .addAction(
                if (recording) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (recording) R.string.btn_record_stop else R.string.btn_record_start),
                if (recording) recordStopIntent else recordStartIntent,
            )
            .addAction(android.R.drawable.ic_menu_camera, getString(R.string.btn_photo), photoIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                getString(if (trackingEnabled) R.string.notif_tracking_off else R.string.notif_tracking_on),
                trackingIntent,
            )
        if (recording) {
            // elapsed time drawn by the system — no re-post every second
            val startedWallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - st.sessionStartElapsedMs)
            builder.setUsesChronometer(true).setWhen(startedWallMs).setShowWhen(true)
        }
        return builder.build()
    }

    private fun refreshNotification() {
        if (!foregroundStarted) return
        lastNotifUpdateMs = SystemClock.elapsedRealtime()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    /** Ver Javadoc da classe ("Reconexão USB", Tarefa 5) — notificação full-screen-intent
     * pedindo pro usuário tocar pra reabrir a `MainActivity` e retomar o wizard de reconexão. */
    private fun notifyDisconnected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 2, intent, flags)

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID_USB_DETACHED)
            .setContentTitle(getString(R.string.notif_usb_detached_title))
            .setContentText(getString(R.string.notif_usb_detached_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // O poll de presença pode re-postar esta notificação (mesmo ID) — alerta sonoro/
            // heads-up só na primeira, atualizações ficam silenciosas na bandeja.
            .setOnlyAlertOnce(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_USB_DETACHED, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Sem permissão POST_NOTIFICATIONS pra avisar desconexão USB", e)
        }
    }
}
