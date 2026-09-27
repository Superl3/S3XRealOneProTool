package com.raphael.handmouse.tracking

import android.media.Image
import android.os.SystemClock
import io.github.crow_misia.libyuv.AbgrBuffer
import io.github.crow_misia.libyuv.FilterMode
import io.github.crow_misia.libyuv.I420Buffer
import io.github.crow_misia.libyuv.Nv12Buffer
import io.github.crow_misia.libyuv.PlaneNative
import io.github.crow_misia.libyuv.asPlane
import java.nio.ByteBuffer

/**
 * YUV_420_888 (`ImageReader`, resolução nativa do decoder — 2048x1512) -> downscale -> RGBA
 * `ByteBuffer`, pronto pra virar `MPImage` via `ByteBufferImageBuilder` — PLANO.md §6.1, brief
 * Tarefa 3. Conversão via libyuv (`io.github.crow-misia.libyuv:libyuv-android:0.43.2`).
 *
 * ## Layout de memória (I420 vs NV12) — PLANO.md/brief pedem tratamento explícito
 * `image.planes[1].pixelStride` decide o caminho:
 * - `1`: planos U/V verdadeiramente planares (I420) — raro em saída de decoder de hardware,
 *   mas documentado como possível (alguns decoders/emuladores); tratado via [I420Buffer].
 * - `2`: planos U/V semi-planares intercalados — o caso quase universal de saída
 *   `YUV_420_888` de `MediaCodec`/`ImageReader` para decodificação HEVC/H.264 por hardware é
 *   **NV12** (Y + UV intercalado, U antes de V no mesmo bloco de memória) — convenção
 *   consolidada da indústria de vídeo, diferente do NV21 legado de Camera1/Camera2. **Não
 *   verificável sem hardware nesta sessão**: se o teste manual da Fase 2 mostrar cores
 *   erradas (U/V trocados — tons azuis/vermelhos invertidos), o fallback documentado é trocar
 *   para NV21 (usar `planes[2]` como âncora do bloco intercalado em vez de `planes[1]`, ver
 *   `Nv21Buffer`/`toNv21Buffer` da própria lib).
 *
 * ## Byte order: ABGR da lib == RGBA de verdade (armadilha de nomenclatura)
 * O tipo `RgbaBuffer` desta lib tem layout de bytes em memória A,B,G,R (Javadoc da própria
 * classe: "RGBA little endian (abgr in memory)") — **não** é o que queremos. O tipo
 * [AbgrBuffer] tem layout R,G,B,A em memória (Javadoc: "ABGR little endian (rgba in memory)")
 * — **esse** é o que bate com `MPImage.IMAGE_FORMAT_RGBA` e com `Bitmap.Config.ARGB_8888`
 * (`AbgrBuffer.asBitmap()` usa `ARGB_8888` diretamente). Mesma armadilha que o PLANO.md §6.1
 * já documentava sobre o libyuv nativo puro ("I420ToABGR ≈ RGBA little-endian").
 *
 * Não foi possível usar a extensão de conveniência `Image.toI420Buffer()` do pacote `.ext` da
 * própria lib: o pacote `io.github.crow_misia.libyuv.ext` também contém `ImageProxyExt`, que
 * referencia `androidx.camera.core.ImageProxy` (CameraX) — dependência que este projeto não
 * declara (não usamos CameraX; a captura é via USB Host/UVC, não Camera2). Isso quebra a
 * resolução do PACOTE inteiro pro compilador Kotlin (`Unresolved reference: toI420Buffer`,
 * mesmo essa função não usando CameraX) — comportamento verificado nesta sessão. Contornado
 * construindo os buffers manualmente a partir de `PlaneNative`/`asPlane` (pacote base da lib,
 * sem essa dependência quebrada) — mesma lógica que `Image.toI420Buffer()`/`toNv12Buffer()`
 * usam internamente (código-fonte da lib inspecionado para confirmar).
 *
 * Buffers de destino são alocados sob demanda NA PRIMEIRA chamada e reutilizados em todo frame
 * seguinte (pool, sem alocação por frame) — só o wrapper leve dos planos de origem
 * (`PlaneNative`) é recriado a cada chamada (inevitável: `Image` é um objeto novo por frame).
 *
 * ## Anel de buffers de saída (fix do SIGSEGV em hardware — 2026-07-23)
 * O buffer RGBA de saída era ÚNICO: `convert()` (thread do decoder) sobrescrevia o mesmo bloco
 * nativo que o MediaPipe ainda estava copiando (`nativeCreateCpuImage`/memmove na HandlerThread
 * do `HandTracker` — `detectAsync` posta a ingestão pra lá, ela NÃO acontece na thread de quem
 * chama `convert`). Resultado observado no S25: frames corrompidos alimentando o tracking (ruído
 * de landmarks → gestos fantasma) e, no teardown, SIGSEGV (`SEGV_MAPERR` em
 * `__memmove_aarch64`) lendo o buffer já liberado. Fix em duas partes: (1) AQUI, um anel de
 * [OUTPUT_POOL_SIZE] buffers de saída — cada `convert()` escreve no próximo buffer do anel, então
 * o conteúdo devolvido permanece intacto pelos próximos [OUTPUT_POOL_SIZE]-1 frames (folga de
 * sobra pra ingestão do MediaPipe, que consome dentro de 1 frame); (2) no `EyeCaptureService`,
 * `release()` só roda DEPOIS do dreno da HandlerThread do `HandTracker` (ver
 * `stopPipeline`) — nenhuma cópia em voo quando os buffers nativos são fechados.
 */
class FrameConverter(
    private val targetWidth: Int = DEFAULT_TARGET_WIDTH,
) {
    companion object {
        const val DEFAULT_TARGET_WIDTH = 768

        /** Tamanho do anel de buffers RGBA de saída — ver Javadoc da classe ("Anel de buffers"). */
        private const val OUTPUT_POOL_SIZE = 3

        // média móvel exponencial do tempo de conversão, pro HUD de métricas
        private const val CONVERSION_TIME_EMA_ALPHA = 0.2
    }

    data class ConvertedFrame(val rgba: ByteBuffer, val width: Int, val height: Int)

    // Altura do destino derivada da PROPORÇÃO da fonte no 1º frame (experimento de resolução,
    // 2026-07-23): o alvo era fixo em 768x576 (4:3, feito pra nativa 2048x1512) — quando a
    // câmera passou a entregar 1920x1080 (16:9), o scale ESPREMIA a imagem ~31% na horizontal
    // antes do MediaPipe (geometria da mão distorcida, velocidade horizontal do cursor errada).
    // Agora targetHeight = targetWidth * srcH/srcW (arredondado pra PAR — exigência comum de
    // planos 4:2:0), calculado no 1º convert(); a instância é por-sessão, a fonte não muda no
    // meio (ver EyeCaptureService, "escopo de sessão").
    private var targetHeight = 0

    // Anel de saída (ver Javadoc da classe): convert() escreve no próximo índice a cada chamada.
    // Alocado no 1º convert(), junto com o targetHeight.
    private var dstPool: Array<AbgrBuffer>? = null
    private var dstIndex = 0

    // pool dos intermediários escalados — alocado sob demanda (só o caminho (I420 ou NV12)
    // realmente usado pelo dispositivo é instanciado; o layout não muda frame a frame).
    private var scaledI420: I420Buffer? = null
    private var scaledNv12: Nv12Buffer? = null

    @Volatile
    var averageConversionMs: Double = 0.0
        private set

    /**
     * Converte [image] (YUV_420_888) para RGBA no tamanho [targetWidth]x[targetHeight].
     * O [ByteBuffer] devolvido é um buffer do anel de saída — seu conteúdo permanece válido pelas
     * próximas [OUTPUT_POOL_SIZE]-1 chamadas a [convert] (ver Javadoc da classe, "Anel de
     * buffers"); o consumidor assíncrono (ingestão do MediaPipe) deve consumi-lo nessa janela.
     */
    fun convert(image: Image): ConvertedFrame {
        val startNanos = SystemClock.elapsedRealtimeNanos()

        // 1ª chamada: resolve a altura pela proporção da fonte e aloca o anel (ver targetHeight).
        val pool = dstPool ?: run {
            targetHeight = ((targetWidth * image.height / image.width) + 1) and -2 // par
            Array(OUTPUT_POOL_SIZE) { AbgrBuffer.Factory.allocate(targetWidth, targetHeight) }
                .also { dstPool = it }
        }
        val dstAbgr = pool[dstIndex]
        dstIndex = (dstIndex + 1) % OUTPUT_POOL_SIZE

        val planes = image.planes
        val planeY = PlaneNative(planes[0])
        when (val pixelStrideUV = planes[1].pixelStride) {
            1 -> {
                val srcI420 = I420Buffer.Factory.wrap(
                    planeY, PlaneNative(planes[1]), PlaneNative(planes[2]),
                    image.width, image.height,
                )
                val dst = scaledI420 ?: I420Buffer.Factory.allocate(targetWidth, targetHeight).also { scaledI420 = it }
                srcI420.use { it.scale(dst, FilterMode.BILINEAR) }
                dst.convertTo(dstAbgr)
            }
            2 -> {
                // NV12: planes[1] ("U") é a âncora do bloco Y-seguido-de-UV-intercalado —
                // estende a capacidade declarada pra cobrir U E V juntos (mesma técnica de
                // Image.toNv12Buffer() da própria lib).
                val uBuffer = planes[1].buffer
                val planeUV = planes[1].asPlane(uBuffer.capacity() + uBuffer.capacity() % 2)
                val srcNv12 = Nv12Buffer.Factory.wrap(planeY, planeUV, image.width, image.height)
                val dst = scaledNv12 ?: Nv12Buffer.Factory.allocate(targetWidth, targetHeight).also { scaledNv12 = it }
                srcNv12.use { it.scale(dst, FilterMode.BILINEAR) }
                dst.convertTo(dstAbgr)
            }
            else -> error("pixelStride de U/V não suportado: $pixelStrideUV (esperado 1 ou 2)")
        }

        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
        averageConversionMs = if (averageConversionMs == 0.0) {
            elapsedMs
        } else {
            CONVERSION_TIME_EMA_ALPHA * elapsedMs + (1 - CONVERSION_TIME_EMA_ALPHA) * averageConversionMs
        }

        return ConvertedFrame(dstAbgr.asBuffer(), targetWidth, targetHeight)
    }

    /** Libera os buffers do pool — chamar quando o pipeline for encerrado, e SÓ depois de
     * garantir que nenhuma cópia assíncrona (ingestão do MediaPipe) está em voo (ver Javadoc da
     * classe, "Anel de buffers"). */
    fun release() {
        dstPool?.forEach { it.close() }
        dstPool = null
        scaledI420?.close()
        scaledNv12?.close()
    }
}
