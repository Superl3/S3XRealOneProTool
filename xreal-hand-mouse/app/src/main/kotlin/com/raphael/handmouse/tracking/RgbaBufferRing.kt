package com.raphael.handmouse.tracking

import java.nio.ByteBuffer

/**
 * Anel de [ByteBuffer]s RGBA DIRETOS reutilizados, pronto pra alimentar o `ByteBufferImageBuilder`
 * do MediaPipe sem alocar por frame. É o equivalente, pro caminho MJPEG, do anel de saída que o
 * [FrameConverter] já mantém pro caminho HEVC — mesma motivação e mesma disciplina.
 *
 * ## Por que um ANEL e não um buffer só
 * A ingestão do MediaPipe (`nativeCreateCpuImage`, um memmove do conteúdo do buffer) NÃO acontece
 * na thread que chama `detectAsync`: essa posta o trabalho pra `HandlerThread` do `HandTracker`.
 * Um buffer único seria sobrescrito pelo frame seguinte enquanto a ingestão ainda lê o anterior —
 * exatamente a corrida que produziu frames corrompidos e o SIGSEGV de 2026-07-23 no caminho HEVC
 * (ver Javadoc do [FrameConverter], "Anel de buffers de saída"). Com [DEFAULT_SIZE] posições, o
 * conteúdo devolvido por [next] permanece intacto pelos próximos [DEFAULT_SIZE]-1 frames — folga de
 * sobra pra uma ingestão que normalmente consome dentro de 1 frame.
 *
 * ## Capacidade EXATA (exigência do MediaPipe)
 * `PacketCreator.createImage(buffer, w, h, 4)` valida `buffer.capacity() == w * 4 * h` e passa o
 * endereço direto do buffer pro JNI — por isso os buffers são alocados com [ByteBuffer.allocateDirect]
 * no tamanho exato, sem folga, e o `widthStep` assumido é `w * 4` (sem padding de linha). Trocar de
 * dimensão (fonte mudou de resolução entre sessões) realoca o anel inteiro.
 *
 * Memória GERENCIADA (ao contrário dos buffers nativos do libyuv no [FrameConverter]): [release] só
 * solta as referências pro GC, não há `free` que possa correr contra uma ingestão em voo.
 *
 * NÃO é thread-safe: use de uma única thread (a do decoder), como o [FrameConverter].
 */
class RgbaBufferRing(private val size: Int = DEFAULT_SIZE) {

    companion object {
        /** Ver Javadoc da classe ("Por que um ANEL") — mesmo valor do anel do [FrameConverter]. */
        const val DEFAULT_SIZE = 3

        private const val BYTES_PER_PIXEL = 4
    }

    private var buffers: Array<ByteBuffer>? = null
    private var width = 0
    private var height = 0
    private var index = 0

    init {
        require(size >= 2) { "anel precisa de pelo menos 2 posições (recebido: $size)" }
    }

    /**
     * Devolve o próximo buffer do anel, zerado (`position = 0`, `limit = capacity`) e com
     * capacidade EXATA de `width * height * 4` bytes. Aloca o anel na primeira chamada e o realoca
     * se as dimensões mudarem.
     */
    fun next(width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0) { "dimensões inválidas: ${width}x$height" }

        val pool = buffers?.takeIf { width == this.width && height == this.height } ?: allocate(width, height)
        val buffer = pool[index]
        index = (index + 1) % pool.size
        buffer.clear()
        return buffer
    }

    /** Solta o anel pro GC. Idempotente; a próxima [next] realoca. */
    fun release() {
        buffers = null
        width = 0
        height = 0
        index = 0
    }

    private fun allocate(width: Int, height: Int): Array<ByteBuffer> {
        val bytes = width * height * BYTES_PER_PIXEL
        val pool = Array(size) { ByteBuffer.allocateDirect(bytes) }
        buffers = pool
        this.width = width
        this.height = height
        index = 0
        return pool
    }
}
