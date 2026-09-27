package com.raphael.handmouse.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Decodificador HEVC (MediaCodec) em modo ByteBuffer: NÃO renderiza para nenhum `Surface`.
 * Decodifica para buffers de saída CPU-legíveis (YUV_420_888 flexível) e entrega cada frame ao
 * consumidor via [FrameListener.onFrame], chamado SÍNCRONO na thread do decoder.
 *
 * ## Por que ByteBuffer e não Surface/ImageReader (fix de crash em hardware — 2026-07-22, S25)
 * A abordagem anterior renderizava para o `Surface` de um `ImageReader` (YUV_420_888) e lia os
 * planos no `OnImageAvailableListener`. Em hardware real, o decoder HEVC da Samsung, alimentando
 * um `ImageReader` via Surface, produz buffers comprimidos (AFBC) / NÃO mapeáveis pra CPU —
 * mesmo pedindo `HardwareBuffer.USAGE_CPU_READ_OFTEN` (a Samsung ignora a flag no caminho
 * Surface→ImageReader). Ao acessar `image.planes`, um plano vem com ponteiro nulo e o CheckJNI
 * aborta o processo em `Image_createSurfacePlanes` ("non-zero capacity for nullptr pointer") —
 * crash na 1ª frame real. A correção elimina o caminho Surface por completo: configuramos o
 * `MediaCodec` com `COLOR_FormatYUV420Flexible` e `surface = null`, e lemos a `Image` direto do
 * codec via [MediaCodec.getOutputImage], que garante planos CPU-legíveis (YUV_420_888 flexível,
 * I420 ou NV12) — exatamente o que o `FrameConverter` (libyuv) precisa.
 *
 * Adaptado de `CameraActivity.kt` do repo Aloim (initDecoder/decodeDrainLoop/
 * processHevcAccessUnit), reorganizado em classe própria e usando [HevcNal] para o parsing
 * puro de NAL units. Roda em thread própria (HandlerThread) — a thread de leitura USB
 * ([UvcCameraHelper]) nunca deve bloquear esperando o decoder.
 *
 * ## Erros visíveis (fix de revisão, achado Important I2)
 * Antes deste fix, tanto a falha de [initDecoder] quanto qualquer exceção no
 * [decodeDrainLoop] só chegavam a `Log.e` — o `EyeCaptureService` já tinha transicionado o
 * estado pra `STREAMING` (em `onStreamStarted`, antes do decoder sequer existir) e nunca ficava
 * sabendo que o decoder morreu, então a UI continuava mostrando "Streaming" com uma tela preta
 * (nenhum frame sendo entregue via [FrameListener]). [errorListener] resolve isso:
 * - Falha em [initDecoder] (configure/start do `MediaCodec`) é SEMPRE fatal — sem decoder não
 *   há pipeline, então já reporta [ErrorListener.onError] com `fatal=true` na primeira falha.
 * - Falha no [decodeDrainLoop] é tratada com mais cautela: uma exceção isolada (glitch
 *   pontual de `dequeueInputBuffer`/`dequeueOutputBuffer`) é recuperável — o loop já reagenda a
 *   si mesmo (`decoderReady` continua `true`) e frequentemente se recupera sozinho no próximo
 *   ciclo, então reportar ERROR a cada uma seria alarme falso. Só ao acumular
 *   [MAX_CONSECUTIVE_DRAIN_FAILURES] falhas SEGUIDAS (sem nenhum ciclo bem-sucedido entre elas)
 *   é que consideramos o decoder persistentemente quebrado: reporta `fatal=true` e desarma
 *   `decoderReady` (o loop para de se reagendar sozinho — ver guarda no fim de
 *   [decodeDrainLoop]).
 */
class HevcDecoder {

    companion object {
        private const val TAG = "HevcDecoder"

        // Resolução decodificada reportada pelo repo (crop-right=2047, crop-bottom=1511).
        const val FRAME_WIDTH = 2048
        const val FRAME_HEIGHT = 1512

        private const val MAX_NALS_PER_DRAIN_CYCLE = 8

        /** Ver Javadoc da classe ("Erros visíveis") — quantas falhas SEGUIDAS do drain loop até
         * considerar o decoder persistentemente quebrado (fatal) em vez de um glitch pontual. */
        private const val MAX_CONSECUTIVE_DRAIN_FAILURES = 5
    }

    /** Ver Javadoc da classe ("Erros visíveis", achado I2). Chamado na thread do decoder
     * ([decoderHandler]) — quem implementa deve fazer `post`/`handler.post` pra main thread se
     * for tocar UI/estado compartilhado (mesma convenção do resto do app). */
    interface ErrorListener {
        fun onError(message: String, fatal: Boolean)
    }

    var errorListener: ErrorListener? = null

    /** Callback de frame decodificado. Chamado na THREAD do decoder ([decoderHandler]) com a
     * [android.media.Image] YUV_420_888 obtida via [MediaCodec.getOutputImage]. A Image é válida
     * APENAS durante esta chamada — o decoder a invalida logo após onFrame retornar (fecha a
     * Image e libera o buffer de saída). O consumidor DEVE converter/copiar de forma SÍNCRONA
     * aqui e NÃO reter a Image nem chamá-la depois. NÃO chame `image.close()` no consumidor — o
     * decoder cuida disso. Quem implementar deve fazer `post`/`handler.post` pra main thread se
     * for tocar UI/estado compartilhado (mesma convenção do [ErrorListener]). */
    interface FrameListener {
        fun onFrame(image: android.media.Image)
    }

    var frameListener: FrameListener? = null

    private val hevcNal = HevcNal()
    private val nalQueue = ConcurrentLinkedQueue<ByteArray>()

    private var decoder: MediaCodec? = null

    @Volatile
    private var decoderReady = false

    private var decoderThread: HandlerThread? = null
    private var decoderHandler: Handler? = null
    private var decodedFrames = 0L

    // Ver Javadoc da classe ("Erros visíveis") — contagem de falhas seguidas do drain loop.
    private var consecutiveDrainFailures = 0

    fun start() {
        decoderThread = HandlerThread("HEVC-Decoder").also { it.start() }
        decoderHandler = Handler(decoderThread!!.looper)
    }

    /**
     * Para o decoder de forma síncrona: quando [stop] RETORNAR, garante que nenhuma chamada a
     * [FrameListener.onFrame] esteja mais em voo. O `EyeCaptureService` libera o `FrameConverter`
     * logo depois de chamar [stop] (ver "Fence contra corrida" no Javadoc daquele serviço), então
     * não pode haver conversão concorrente na thread do decoder — por isso fazemos um `join` (com
     * timeout) da thread do decoder ANTES de liberar o `MediaCodec` e nular referências.
     *
     * Roda na main thread; a drain loop (que chama `onFrame`) roda na thread do decoder. Ordem:
     * desarma `decoderReady` (a drain loop para de se reagendar), `quitSafely()` + `join()`
     * (espera a drain loop em andamento terminar e a thread encerrar) e só então
     * `decoder.stop()/release()`. Idempotente (pode ser chamado 2x): na 2ª vez `decoderThread` já
     * é `null` e `decoder` já é `null`, então tudo vira no-op seguro.
     */
    fun stop() {
        decoderReady = false
        nalQueue.clear()
        val thread = decoderThread
        decoderThread = null
        decoderHandler = null
        thread?.quitSafely()
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            decoder?.stop()
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
        consecutiveDrainFailures = 0
    }

    /**
     * Chamado pela thread de leitura USB com um access unit completo (um frame montado pelo
     * [FrameAssembler]). Deve retornar rápido — o parsing de NAL é leve; a decodificação em
     * si acontece na thread do decoder.
     */
    fun onAccessUnit(data: ByteArray) {
        val slices = hevcNal.processAccessUnit(data)

        // Réplica do gate real do repo Aloim (CameraActivity.kt:371/374/377): NALs de slice só
        // são enfileiradas se o decoder já estiver pronto (MediaCodec.configure/start já
        // bem-sucedidos) — não basta parameterSetsComplete. Sem esse gate, se initDecoder()
        // falhar (exceção capturada logo abaixo, decoderReady permanece false), toda access
        // unit subsequente continuaria enfileirando sem limite (achado de revisão — ver
        // task-2-report.md).
        if (decoderReady) {
            for (nal in slices) nalQueue.offer(nal)
        }

        if (!decoderReady && hevcNal.parameterSetsComplete) {
            decoderHandler?.post { initDecoder() }
        }
    }

    private fun initDecoder() {
        if (decoderReady) return
        val csd0 = hevcNal.currentCsd0() ?: return

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, FRAME_WIDTH, FRAME_HEIGHT)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            // Modo ByteBuffer com formato de cor flexível: garante que getOutputImage() devolva
            // planos CPU-legíveis (YUV_420_888 flexível, I420/NV12) — ver "Por que ByteBuffer e
            // não Surface/ImageReader" no Javadoc da classe.
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.configure(format, null, null, 0) // surface null = modo ByteBuffer
            codec.start()
            decoder = codec
            decoderReady = true
            Log.d(TAG, "Decoder HEVC inicializado (csd-0=${csd0.size}b)")

            scheduleDecodeDrain()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar decoder", e)
            // Fatal sempre (ver Javadoc da classe): sem decoder inicializado não há pipeline,
            // e decoderReady permanece false — nenhum frame será processado a partir daqui.
            errorListener?.onError("Falha ao inicializar decoder HEVC: ${e.message}", fatal = true)
        }
    }

    private fun scheduleDecodeDrain() {
        decoderHandler?.post { decodeDrainLoop() }
    }

    private fun decodeDrainLoop() {
        val codec = decoder ?: return
        if (!decoderReady) return

        try {
            var fed = 0
            while (fed < MAX_NALS_PER_DRAIN_CYCLE) {
                val nal = nalQueue.poll() ?: break
                val inIndex = codec.dequeueInputBuffer(0)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex) ?: break
                    inputBuffer.clear()
                    inputBuffer.put(nal)
                    val pts = decodedFrames * 33333L
                    codec.queueInputBuffer(inIndex, 0, nal.size, pts, 0)
                    decodedFrames++
                    fed++
                } else {
                    nalQueue.offer(nal)
                    break
                }
            }

            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex >= 0) {
                    // Modo ByteBuffer: obtém a Image CPU-legível, entrega ao listener de forma
                    // SÍNCRONA (a Image só é válida durante a chamada), fecha a Image e libera o
                    // buffer com render=false (não há Surface pra renderizar).
                    val image = try { codec.getOutputImage(outIndex) } catch (e: Exception) { null }
                    if (image != null) {
                        try {
                            frameListener?.onFrame(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro no frameListener.onFrame", e)
                        } finally {
                            image.close()
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Formato de saída do decoder: ${codec.outputFormat}")
                } else {
                    break
                }
            }
            // Ciclo completou sem exceção: zera a contagem de falhas seguidas (ver Javadoc da
            // classe) — um glitch isolado no passado não deve contribuir pro teto se o decoder
            // já se recuperou sozinho.
            consecutiveDrainFailures = 0
        } catch (e: Exception) {
            consecutiveDrainFailures++
            Log.e(TAG, "Erro no decoder (falha seguida #$consecutiveDrainFailures): ${e.message}")
            if (consecutiveDrainFailures >= MAX_CONSECUTIVE_DRAIN_FAILURES) {
                Log.e(TAG, "Decoder falhando persistentemente ($consecutiveDrainFailures ciclos seguidos) — desistindo")
                decoderReady = false
                errorListener?.onError(
                    "Decoder HEVC falhando persistentemente (${consecutiveDrainFailures}x seguidas): ${e.message}",
                    fatal = true,
                )
            }
        }

        if (decoderReady) {
            val delay = if (nalQueue.isNotEmpty()) 0L else 1L
            decoderHandler?.postDelayed({ decodeDrainLoop() }, delay)
        }
    }
}
