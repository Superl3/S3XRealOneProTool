package com.raphael.handmouse.tracking

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Wrapper do `HandLandmarker` (MediaPipe Tasks Vision) — PLANO.md §3.2/§6.1, brief Tarefa 3.
 * `RunningMode.LIVE_STREAM`, `Delegate.GPU`, `setNumHands(1)`, confidences 0.5. Criado numa
 * `HandlerThread` dedicada — o delegate GPU fica preso à thread de criação, então tanto a
 * criação (`start`) quanto toda chamada a `detectAsync` acontecem nessa mesma thread.
 *
 * Throttle de 30 inferências/s: [shouldAcceptFrame] decide ANTES da conversão YUV->RGBA se o
 * frame deve ser processado — o orquestrador (quem lê o `ImageReader`, hoje
 * `EyeCaptureService`) deve chamá-lo e só converter o frame se retornar `true`. Também garante
 * timestamps estritamente crescentes (`SystemClock.uptimeMillis()`; dois frames no mesmo ms →
 * o segundo é descartado).
 *
 * Para evitar uma corrida entre o buffer RGBA reutilizado (pool, ver [FrameConverter]) e a
 * ingestão assíncrona do MediaPipe, o orquestrador deve rodar TODO o caminho por frame
 * (callback do ImageReader -> throttle -> conversão -> `detectAsync`) na mesma thread exposta
 * por [workerHandler] — isso serializa "converter o próximo frame" depois de "MediaPipe já
 * consumiu o buffer atual" (a documentação do MediaPipe Tasks afirma que o conteúdo da imagem
 * só precisa permanecer válido até `detectAsync` retornar; não verificável sem hardware nesta
 * sessão — ver limitações no relatório).
 */
class HandTracker {

    companion object {
        private const val TAG = "HandTracker"

        const val MODEL_ASSET_PATH = "hand_landmarker.task"
        const val NUM_HANDS = 1
        const val MIN_CONFIDENCE = 0.5f

        /** 30 → 60 (2026-07-23, fluidez): a câmera Eye entrega ~60fps reais (medido em
         * hardware); o teto de 30 descartava metade das amostras e o cursor andava a 30Hz num
         * display de 60Hz+. O [com.raphael.handmouse.util.ThermalFpsPolicy] continua derrubando
         * o alvo sob throttling térmico (60/40/24). */
        const val MAX_INFERENCE_FPS = 60
    }

    /** Resultado já "traduzido" pra fora do MediaPipe (ainda usa os tipos do MediaPipe pros
     * landmarks/handedness — a fronteira de desacoplamento fica no [PinchDetector], que exige
     * [HandPoint]; quem consumir este resultado converte). */
    data class Result(
        val landmarks: List<NormalizedLandmark>,
        val handedness: List<Category>,
        val timestampMs: Long,
        val latencyMs: Long,
        val inferenceFps: Double,
        /** Eye Tools fork: [landmarks] as [HandPoint]s, converted once on the worker thread. */
        val points: List<HandPoint> = landmarks.map { HandPoint(it.x(), it.y(), it.z()) },
        /** Metric 3D landmarks (metres, hand-centred) — rotation-robust pinch (Eye Tools fork).
         * Empty when the model did not provide them. */
        val worldLandmarks: List<HandPoint> = emptyList(),
        /** Size of the image MediaPipe saw (0 when unknown). */
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        /** [points] with y rescaled to image-WIDTH units (y × height/width), so a distance means
         * the same physical length in any direction (2026-09-28). Normalized x is divided by the
         * width and y by the height; on the Eye's 16:9 stream a vertical distance came out 1.78×
         * a horizontal one of the same length, so ratios mixing directions (pinch, thumb) swung
         * with hand orientation. MediaPipe's z is already on the x scale. Geometry (detectors,
         * cursor, menu) uses this; image-space consumers (bottom zone, debug skeleton) keep
         * [points]. */
        val isoPoints: List<HandPoint> = isotropic(points, imageWidth, imageHeight),
    ) {
        companion object {
            fun isotropic(points: List<HandPoint>, width: Int, height: Int): List<HandPoint> {
                if (width <= 0 || height <= 0) return points
                val yScale = height.toFloat() / width
                return points.map { HandPoint(it.x, it.y * yScale, it.z) }
            }
        }
    }

    interface ResultListener {
        fun onResult(result: Result)

        /** Nenhuma mão detectada neste frame. */
        fun onHandLost()
    }

    interface ErrorHandler {
        fun onError(error: RuntimeException)
    }

    var resultListener: ResultListener? = null
    var errorHandler: ErrorHandler? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var landmarker: HandLandmarker? = null

    @Volatile
    private var lastAcceptedTimestampMs: Long = -1L

    /** Teto de inferência em runtime (Tarefa 5, brief/PLANO.md Fase 5: throttle da Tarefa 3
     * parametrizado por [com.raphael.handmouse.util.ThermalMonitor]) — default [MAX_INFERENCE_FPS]. */
    @Volatile
    private var minFrameIntervalMs: Long = throttleIntervalMs(MAX_INFERENCE_FPS)

    @Volatile
    private var lastResultTimestampMs: Long = -1L

    @Volatile
    private var inferenceFpsEma: Double = 0.0

    /** Handler da HandlerThread dedicada — o orquestrador deve rodar o pipeline por-frame
     * (ImageReader -> [shouldAcceptFrame] -> conversão -> [detectAsync]) nela (ver doc da
     * classe). `null` até [start] retornar. */
    val workerHandler: Handler?
        get() = handler

    fun start(context: Context) {
        val thread = HandlerThread("HandTracker").apply { start() }
        handlerThread = thread
        val newHandler = Handler(thread.looper)
        handler = newHandler
        newHandler.post { initLandmarker(context.applicationContext) }
    }

    fun stop() {
        val h = handler
        h?.post {
            try {
                landmarker?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao fechar HandLandmarker", e)
            }
            landmarker = null
        }
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        lastAcceptedTimestampMs = -1L
        lastResultTimestampMs = -1L
        inferenceFpsEma = 0.0
        minFrameIntervalMs = throttleIntervalMs(MAX_INFERENCE_FPS) // próximo start() volta ao default
    }

    /** Intervalo mínimo do throttle pra um alvo de [fps] — com FOLGA de 3ms (fix de fluidez
     * 2026-07-23, 4ª rodada): o intervalo "exato" (1000/60 = 16ms) sofria aliasing contra os
     * frames reais da câmera (~16,6ms COM jitter de chegada) — qualquer frame que chegasse
     * 1ms "cedo demais" era descartado e virava um buraco de 33ms no cursor (o motivo de 60fps
     * ainda não PARECEREM 60). A folga deixa passar todo frame legítimo; quem garante o teto
     * médio continua sendo a cadência da própria câmera. */
    private fun throttleIntervalMs(fps: Int): Long = (1000L / fps - 3L).coerceAtLeast(1L)

    /**
     * Decide, ANTES de converter YUV->RGBA, se o frame atual deve ser processado: throttle de
     * [minFrameIntervalMs] (default [MAX_INFERENCE_FPS], ajustável via [setMaxFps]) + timestamp
     * estritamente crescente. Se `true`, reserva [nowMs] como o último timestamp aceito (chamar
     * no máximo uma vez por frame candidato).
     */
    @Synchronized
    fun shouldAcceptFrame(nowMs: Long): Boolean {
        if (nowMs <= lastAcceptedTimestampMs) return false // não-monotônico: pula
        if (lastAcceptedTimestampMs >= 0 && nowMs - lastAcceptedTimestampMs < minFrameIntervalMs) {
            return false
        }
        lastAcceptedTimestampMs = nowMs
        return true
    }

    /** Ajusta o teto de inferência em runtime — usado pelo
     * [com.raphael.handmouse.util.ThermalMonitor] pra reduzir carga sob throttling térmico
     * (brief Tarefa 5; PLANO.md Fase 5). `fps <= 0` é inválido e ignorado (logado). */
    @Synchronized
    fun setMaxFps(fps: Int) {
        if (fps <= 0) {
            Log.w(TAG, "setMaxFps ignorado: fps inválido ($fps)")
            return
        }
        minFrameIntervalMs = throttleIntervalMs(fps)
        Log.d(TAG, "maxFps ajustado para $fps (intervalo mínimo ${minFrameIntervalMs}ms)")
    }

    /**
     * Envia [mpImage] (já convertido) pra inferência assíncrona. Só deve ser chamado após
     * [shouldAcceptFrame] retornar `true` pro mesmo [timestampMs]. Fecha [mpImage] se a
     * submissão falhar (sucesso: fechado no result listener interno, após consumo — ver
     * [onMediaPipeResult]).
     */
    fun detectAsync(mpImage: MPImage, timestampMs: Long) {
        val h = handler
        if (h == null) {
            mpImage.close()
            return
        }
        h.post {
            val lm = landmarker
            if (lm == null) {
                mpImage.close()
                return@post
            }
            try {
                lm.detectAsync(mpImage, timestampMs)
            } catch (e: Exception) {
                mpImage.close()
                errorHandler?.onError(RuntimeException("Falha em detectAsync", e))
            }
        }
    }

    private fun initLandmarker(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(Delegate.GPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(NUM_HANDS)
                .setMinHandDetectionConfidence(MIN_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_CONFIDENCE)
                .setMinTrackingConfidence(MIN_CONFIDENCE)
                .setResultListener(::onMediaPipeResult)
                .setErrorListener(::onMediaPipeError)
                .build()

            landmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "HandLandmarker inicializado (GPU, LIVE_STREAM, numHands=$NUM_HANDS)")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar HandLandmarker", e)
            errorHandler?.onError(RuntimeException("Falha ao inicializar HandLandmarker", e))
        }
    }

    private fun onMediaPipeResult(result: HandLandmarkerResult, image: MPImage) {
        try {
            val now = SystemClock.uptimeMillis()
            val prev = lastResultTimestampMs
            if (prev >= 0 && now > prev) {
                val instantFps = 1000.0 / (now - prev)
                inferenceFpsEma = if (inferenceFpsEma == 0.0) instantFps else 0.2 * instantFps + 0.8 * inferenceFpsEma
            }
            lastResultTimestampMs = now

            val allLandmarks = result.landmarks()
            if (allLandmarks.isEmpty() || allLandmarks[0].isEmpty()) {
                resultListener?.onHandLost()
                return
            }

            val latency = now - result.timestampMs()
            val handedness = result.handedness().firstOrNull().orEmpty()
            val world = result.worldLandmarks().firstOrNull()
                ?.map { HandPoint(it.x(), it.y(), it.z()) }
                .orEmpty()
            resultListener?.onResult(
                Result(
                    landmarks = allLandmarks[0],
                    handedness = handedness,
                    timestampMs = result.timestampMs(),
                    latencyMs = latency,
                    inferenceFps = inferenceFpsEma,
                    worldLandmarks = world,
                    imageWidth = image.width,
                    imageHeight = image.height,
                )
            )
        } finally {
            image.close()
        }
    }

    private fun onMediaPipeError(error: RuntimeException) {
        Log.e(TAG, "Erro do MediaPipe: ${error.message}", error)
        errorHandler?.onError(error)
    }
}
