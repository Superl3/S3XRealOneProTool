package com.raphael.handmouse.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Decodificador MJPEG: cada access unit é um JPEG completo e independente (intra-only). Decodifica
 * via [BitmapFactory] para um [Bitmap] ARGB_8888 e entrega ao consumidor via [FrameListener.onFrame],
 * chamado SÍNCRONO na thread do decoder. Substitui o caminho MediaCodec/HEVC ([HevcDecoder]) quando
 * o formato ativo da câmera dos óculos for MJPEG.
 *
 * ## Por que MJPEG em vez de HEVC (medido em hardware — 2026-07-23, XREAL One Pro)
 * O encoder HEVC dos óculos engasga (stall do stream) a cada 20-90s sob carga — provavelmente o
 * encoder de hardware satura mantendo a cadeia de predição inter-frame. O formato MJPEG é
 * intra-only: cada frame é um JPEG autônomo (payload começa em `FF D8`, ~40-150KB @ 60fps via USB
 * bulk), o que alivia o encoder (nada de referência entre frames) e torna QUALQUER frame
 * decodificável isoladamente — não há dependência de parameter sets nem de keyframes. Na mesma
 * sessão, o stream MJPEG rodou 4000 frames sem um único stall. Este decoder é o caminho preferido
 * quando a câmera expõe MJPEG.
 *
 * ## Thread própria (a leitura USB nunca bloqueia)
 * Roda numa [HandlerThread] própria ("MJPEG-Decoder"). [onAccessUnit] é chamado na thread do stream
 * USB ([UvcCameraHelper]) e deve retornar rápido: valida o cabeçalho, enfileira em fila bounded e
 * posta o dreno — a decodificação (custosa) acontece só na thread do decoder. Mesma disciplina do
 * [HevcDecoder].
 *
 * ## Fila bounded com descarte do mais antigo (contra atraso acumulado)
 * A fila tem no máximo [MAX_QUEUE_SIZE] frames. Se a decodificação não acompanha a chegada (throttle
 * térmico, GC, etc.), a fila enche; nesse caso descartamos o frame MAIS ANTIGO e mantemos o mais
 * novo — para hand-tracking, latência baixa importa mais que completude: um frame velho já não
 * reflete a posição atual da mão. Descartes são contados e logados a cada 100 (evita spam de log
 * mas registra que está acontecendo).
 *
 * 2026-09-28: the dreno also takes only the NEWEST queued frame. It used to pop the oldest, which
 * passed the throttle, and the newer frames behind it — popped a few ms later — were then
 * rejected by the throttle interval: under backlog the stale frame won. Each frame also carries
 * its ARRIVAL time (when [onAccessUnit] assembled it on the USB thread) instead of the time it
 * was popped, so throttle, MediaPipe timestamps, filter dt and the latency HUD no longer include
 * queue and decode delays.
 *
 * ## FrameGate: throttle barato ANTES de decodificar
 * Antes de gastar CPU decodificando, o dreno consulta [FrameGate.shouldDecode]. Se retornar `false`
 * (o serviço integrador está limitando fps por temperatura/orçamento), o frame é descartado SEM
 * decodificar — por design, essa é a via barata de reduzir carga (não adianta decodificar para
 * jogar fora). Gate `null` = decodifica tudo.
 *
 * ## Anel de Bitmaps reutilizados (anti-corrida + anti-alocação por frame)
 * `inBitmap` do [BitmapFactory] reaproveita a memória do bitmap em vez de alocar um novo a cada
 * frame (evita pressão de GC a 60fps). Mas reutilizar UM único bitmap teria a mesma corrida que o
 * anel de saída do `FrameConverter` corrigiu (SIGSEGV — 2026-07-23): [FrameListener.onFrame] entrega
 * o bitmap e o contrato exige que o consumidor copie DENTRO da chamada — um anel de
 * [BITMAP_RING_SIZE] posições dá folga de sobra contra qualquer cópia que escape um pouco da janela
 * síncrona. O anel é alocado na PRIMEIRA decodificação bem-sucedida, com
 * as dimensões REAIS retornadas (decode inicial sem `inBitmap`) — assim não precisamos adivinhar a
 * resolução da câmera. `inSampleSize = 2` reduz 1920x1080 → 960x540 já na decodificação (o MediaPipe
 * reduz mais internamente; 960x540 é folgado), economizando memória e tempo.
 *
 * ## Erros: falhas de decode CONSECUTIVAS (mesmo padrão do HevcDecoder)
 * Um decode que devolve `null` ou lança é contado como falha SEGUIDA. Uma falha isolada (JPEG
 * corrompido pontual) é recuperável — reporta [ErrorListener.onError] com `fatal=false` e segue. Só
 * ao acumular [MAX_CONSECUTIVE_DECODE_FAILURES] falhas SEGUIDAS (sem nenhum decode bem-sucedido entre
 * elas) consideramos o decoder persistentemente quebrado: reporta `fatal=true` e para de drenar.
 * Qualquer sucesso zera o contador.
 */
class MjpegDecoder(private val dumpDir: File? = null) {

    companion object {
        private const val TAG = "MjpegDecoder"

        /** Fila bounded — ver Javadoc da classe ("Fila bounded com descarte do mais antigo"). */
        private const val MAX_QUEUE_SIZE = 4

        /** Cadência de log dos descartes por fila cheia (evita spam a 60fps). */
        private const val DROP_LOG_EVERY = 100L

        /** Tamanho do anel de Bitmaps reutilizados via `inBitmap` — ver Javadoc da classe. */
        private const val BITMAP_RING_SIZE = 3

        /** Fator de subamostragem do JPEG na decodificação (1920x1080 → 960x540). */
        private const val DECODE_SAMPLE_SIZE = 2

        /** Quantos frames válidos gravar em disco por [start] quando [dumpDir] != null. */
        private const val DUMP_FRAME_COUNT = 3

        /** Ver Javadoc da classe ("Erros") — falhas de decode SEGUIDAS até considerar fatal. */
        private const val MAX_CONSECUTIVE_DECODE_FAILURES = 5

        /** Desistência do stream (7ª rodada): se o remontador já descartou isto de bytes SEM
         * extrair um único frame, o stream não é JPEG de verdade (ex.: a câmera aceitou o commit
         * MJPEG mas segue mandando HEVC) — reporta fatal pra o serviço rebaixar o formato.
         * ~512KB ≈ 1s de stream: rápido o bastante pra recuperação ágil, folgado o bastante pra
         * nunca disparar num stream JPEG legítimo (que extrai frames o tempo todo). */
        private const val GIVEUP_DISCARDED_BYTES = 524_288L
    }

    /**
     * Portão de throttle consultado ANTES de decodificar cada frame (ver Javadoc da classe,
     * "FrameGate"). Chamado na thread do decoder com [SystemClock.uptimeMillis]. `null` = decodifica
     * tudo. Deve ser barato e não bloquear (roda por frame na thread do decoder).
     */
    interface FrameGate {
        fun shouldDecode(nowMs: Long): Boolean
    }

    var frameGate: FrameGate? = null

    /**
     * Callback de frame decodificado. Chamado na THREAD do decoder de forma SÍNCRONA. O [bitmap]
     * pertence ao anel interno e só é VÁLIDO durante esta chamada — o decoder o reutiliza (`inBitmap`)
     * nos próximos frames. O consumidor DEVE copiar/consumir de forma síncrona aqui e NÃO reter o
     * [bitmap] nem chamar `recycle()` nele. Quem implementar deve fazer `post`/`handler.post` pra
     * main thread se for tocar UI/estado compartilhado.
     *
     * ATENÇÃO (fix de 2026-07-26): "entregar ao MediaPipe" NÃO conta como consumo síncrono. Um
     * `BitmapImageBuilder(bitmap)` só EMBRULHA o bitmap — a ingestão (cópia) acontece depois, na
     * `HandlerThread` do `HandTracker` (`detectAsync` posta pra lá), e o `MPImage` resultante vira
     * DONO do bitmap: `MPImage.close()` chama `Bitmap.recycle()` nele. Passar um bitmap do anel
     * direto pro `BitmapImageBuilder` portanto reciclava o anel por baixo do decoder
     * ("Cannot reuse a recycled Bitmap" a cada frame) além de entregar imagem já sobrescrita.
     */
    interface FrameListener {
        fun onFrame(bitmap: Bitmap, timestampMs: Long)
    }

    var frameListener: FrameListener? = null

    /**
     * Erros do decoder. Chamado na thread do decoder. `fatal=true` significa que o pipeline morreu
     * (o dreno parou) — o serviço integrador deve reagir (ex.: transicionar estado, reiniciar).
     * `fatal=false` é um glitch isolado, informativo. Quem implementar deve fazer `post` pra main
     * thread se for tocar UI/estado compartilhado.
     */
    interface ErrorListener {
        fun onError(message: String, fatal: Boolean)
    }

    var errorListener: ErrorListener? = null

    // --- Estado da thread (main thread controla start/stop; USB thread lê handler/draining) ---

    private var decoderThread: HandlerThread? = null

    @Volatile
    private var decoderHandler: Handler? = null

    /** `true` entre [start] e [stop] (e enquanto não houve falha fatal). Gate de enfileiramento e
     * do dreno. Lido na thread USB, escrito na main e na thread do decoder (em falha fatal). */
    @Volatile
    private var draining = false

    // --- Fila bounded (guardada por queueLock; produtor=USB, consumidor=decoder) ---

    private class QueuedFrame(val data: ByteArray, val arrivedAtMs: Long)

    private val queueLock = Any()
    private val queue = ArrayDeque<QueuedFrame>()
    private var droppedFrames = 0L

    // --- Remontagem + contadores (thread USB apenas) ---

    /** Ver [MjpegStreamAssembler] (7ª rodada): os chunks do [FrameAssembler] NÃO respeitam as
     * fronteiras de frame do MJPEG (truncava em 65534b + fragmentos soltos) — a remontagem real
     * é por marcadores SOI/EOI, aqui. */
    private val streamAssembler = MjpegStreamAssembler()
    private var gaveUpOnStream = false
    private var dumpedFrames = 0

    // --- Estado do decoder (thread do decoder apenas; o anel é solto pro GC no stop(), NÃO
    //     reciclado — ver Javadoc de stop) ---

    private var bitmapRing: Array<Bitmap>? = null
    private var ringIndex = 0
    private var consecutiveDecodeFailures = 0

    fun start() {
        val thread = HandlerThread("MJPEG-Decoder").also { it.start() }
        decoderThread = thread
        decoderHandler = Handler(thread.looper)
        synchronized(queueLock) { queue.clear() }
        streamAssembler.reset()
        gaveUpOnStream = false
        dumpedFrames = 0
        consecutiveDecodeFailures = 0
        draining = true
    }

    /**
     * Para o decoder de forma síncrona: quando [stop] RETORNAR, garante que nenhuma chamada a
     * [FrameListener.onFrame] esteja mais em voo. Ordem importa: desarma `draining` (o dreno para de
     * processar), `quitSafely()` + `join()` (espera o dreno em andamento terminar e a thread
     * encerrar) e então solta o anel de Bitmaps pro GC (só nula a referência — NÃO recicla).
     *
     * ## Por que NÃO reciclar o anel aqui
     * O `join()` acima garante que nenhum `onFrame` NOVO será chamado — mas NÃO garante que o
     * consumidor já terminou de usar os bitmaps que ele JÁ recebeu, e este decoder não tem como
     * saber. Bitmaps são memória GERENCIADA: o GC recolhe o anel assim que ninguém mais o
     * referencia, e 3 bitmaps de ~2MB por sessão é custo irrelevante perto do risco de reciclar
     * um bitmap que um consumidor mal-comportado ainda esteja lendo (a mesma classe de corrida do
     * SIGSEGV registrado em hardware em 23/07: liberar memória de frame antes do dreno do worker).
     * Nada aqui depende do integrador atual — o contrato de [FrameListener.onFrame] (cópia
     * síncrona) já garante que nenhum bitmap do anel escapa da thread do decoder.
     *
     * Idempotente: na 2ª chamada `decoderThread` já é `null`, o anel já é `null`, e tudo vira no-op
     * seguro.
     */
    fun stop() {
        draining = false
        val thread = decoderThread
        decoderThread = null
        decoderHandler = null
        synchronized(queueLock) { queue.clear() }
        thread?.quitSafely()
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Thread encerrada (join): nenhum onFrame NOVO será postado a partir daqui. Mas NÃO
        // reciclamos o anel — apenas soltamos a referência pro GC (ver Javadoc deste método, "Por
        // que NÃO reciclar o anel aqui"): um MPImage construído de um bitmap do anel pode ainda
        // estar na fila da HandlerThread do HandTracker, a ser copiado pelo MediaPipe no dreno que
        // o serviço faz DEPOIS (awaitWorkerDrain); reciclar aqui = "Can't copy recycled bitmap".
        bitmapRing = null
        ringIndex = 0
        consecutiveDecodeFailures = 0
    }

    /**
     * Chamado pela thread de leitura USB com um CHUNK do stream (a "montagem" do [FrameAssembler]
     * não respeita fronteiras de frame MJPEG — ver [MjpegStreamAssembler]). Deve retornar rápido:
     * alimenta o remontador, agenda dump/enfileira os frames COMPLETOS extraídos e posta o dreno.
     * A decodificação em si acontece na thread do decoder.
     */
    fun onAccessUnit(data: ByteArray) {
        if (!draining || gaveUpOnStream) return

        val frames = streamAssembler.feed(data)
        val arrivedAtMs = SystemClock.uptimeMillis()

        if (frames.isEmpty()) {
            // Desistência (ver GIVEUP_DISCARDED_BYTES): muito lixo sem UM frame sequer = o stream
            // não é JPEG (câmera ignorou o commit MJPEG). Fatal → o serviço rebaixa o formato.
            // (O caso "lixo que por acaso contém FF D8/FF D9" produz pseudo-frames que falham no
            // DECODE — coberto pelo contador de falhas consecutivas; os dois caminhos terminam na
            // mesma demoção.)
            if (streamAssembler.framesExtracted == 0L &&
                streamAssembler.discardedBytes > GIVEUP_DISCARDED_BYTES
            ) {
                gaveUpOnStream = true
                draining = false
                Log.e(TAG, "Stream não contém JPEGs (${streamAssembler.discardedBytes}b descartados sem um frame) — desistindo do formato")
                errorListener?.onError(
                    "Stream MJPEG sem frames JPEG (${streamAssembler.discardedBytes}b de lixo) — a câmera não honrou o formato",
                    fatal = true,
                )
            }
            return
        }

        for (frame in frames) {
            // Dump de diagnóstico dos 3 primeiros frames COMPLETOS de cada start() (para validar
            // FOV/orientação via `adb pull`). Postado pra thread do decoder (sem IO na thread USB).
            if (dumpDir != null && dumpedFrames < DUMP_FRAME_COUNT) {
                val index = dumpedFrames
                dumpedFrames++
                decoderHandler?.post { dumpRawFrame(index, frame) }
            }
            enqueue(QueuedFrame(frame, arrivedAtMs))
        }
        decoderHandler?.post { drain() }
    }

    private fun enqueue(data: QueuedFrame) {
        synchronized(queueLock) {
            while (queue.size >= MAX_QUEUE_SIZE) {
                queue.removeFirst() // descarta o MAIS ANTIGO (ver Javadoc da classe)
                droppedFrames++
                if (droppedFrames % DROP_LOG_EVERY == 0L) {
                    Log.w(TAG, "Fila MJPEG cheia — $droppedFrames frames antigos descartados no total")
                }
            }
            queue.addLast(data)
        }
    }

    /** Newest queued frame; older ones are dropped (see class Javadoc, 2026-09-28). */
    private fun pollNewest(): QueuedFrame? = synchronized(queueLock) {
        val newest = queue.removeLastOrNull() ?: return@synchronized null
        if (queue.isNotEmpty()) {
            droppedFrames += queue.size
            queue.clear()
        }
        newest
    }

    private fun drain() {
        if (!draining) return
        while (draining) {
            val frame = pollNewest() ?: break
            val gate = frameGate
            if (gate != null && !gate.shouldDecode(frame.arrivedAtMs)) {
                // Throttle: descarta SEM decodificar (a via barata por design — ver Javadoc).
                continue
            }
            decodeAndDeliver(frame.data, frame.arrivedAtMs)
        }
    }

    private fun decodeAndDeliver(data: ByteArray, timestampMs: Long) {
        val bitmap: Bitmap? = try {
            decodeReusing(data)
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao decodificar JPEG: ${e.message}")
            null
        }

        if (bitmap == null) {
            onDecodeFailure("decode devolveu null ou lançou exceção")
            return
        }

        consecutiveDecodeFailures = 0

        try {
            frameListener?.onFrame(bitmap, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no frameListener.onFrame", e)
        }

        // Avança o anel só depois de o consumidor ter usado o bitmap atual.
        bitmapRing?.let { ringIndex = (ringIndex + 1) % it.size }
    }

    /**
     * Decodifica [data] reaproveitando o anel de Bitmaps via `inBitmap`. Na primeira vez (anel
     * `null`) decodifica SEM `inBitmap` para descobrir as dimensões reais e aloca o anel. Se um
     * decode com `inBitmap` lançar [IllegalArgumentException] (a fonte mudou de dimensão e o bitmap
     * reutilizado não comporta a nova saída), refaz UMA vez sem `inBitmap` e realoca o anel. Devolve
     * o bitmap decodificado (que é `bitmapRing[ringIndex]`), ou `null` se o JPEG for indecodificável.
     */
    private fun decodeReusing(data: ByteArray): Bitmap? {
        val ring = bitmapRing
        if (ring == null) {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, baseOptions()) ?: return null
            allocateRing(bmp)
            return bmp
        }

        val opts = baseOptions().apply { inBitmap = ring[ringIndex] }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "inBitmap incompatível (dimensão da fonte mudou?) — realocando o anel: ${e.message}")
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, baseOptions()) ?: return null
            reallocateRing(bmp)
            bmp
        }
    }

    private fun baseOptions() = BitmapFactory.Options().apply {
        inSampleSize = DECODE_SAMPLE_SIZE
        inMutable = true // exigido pra que o bitmap possa ser reutilizado como inBitmap depois
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /** Aloca o anel com [first] na posição 0 e mais [BITMAP_RING_SIZE]-1 bitmaps das mesmas dims. */
    private fun allocateRing(first: Bitmap) {
        bitmapRing = Array(BITMAP_RING_SIZE) { i ->
            if (i == 0) first else Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        }
        ringIndex = 0
    }

    private fun reallocateRing(first: Bitmap) {
        recycleRing()
        allocateRing(first)
    }

    private fun recycleRing() {
        bitmapRing?.forEach { if (!it.isRecycled) it.recycle() }
        bitmapRing = null
        ringIndex = 0
    }

    private fun onDecodeFailure(reason: String) {
        consecutiveDecodeFailures++
        if (consecutiveDecodeFailures >= MAX_CONSECUTIVE_DECODE_FAILURES) {
            Log.e(TAG, "Decoder MJPEG falhando persistentemente ($consecutiveDecodeFailures ciclos seguidos) — desistindo")
            draining = false
            errorListener?.onError(
                "Decoder MJPEG falhando persistentemente (${consecutiveDecodeFailures}x seguidas): $reason",
                fatal = true,
            )
        } else {
            Log.e(TAG, "Falha isolada de decode JPEG (#$consecutiveDecodeFailures): $reason")
            errorListener?.onError("Falha isolada ao decodificar frame MJPEG: $reason", fatal = false)
        }
    }

    private fun dumpRawFrame(index: Int, data: ByteArray) {
        val dir = dumpDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "frame-$index.jpg")
            FileOutputStream(file).use { it.write(data) }
            Log.d(TAG, "Dump de diagnóstico salvo: ${file.absolutePath} (${data.size}b)")
        } catch (e: Exception) {
            // Dump é só diagnóstico — falha de IO nunca pode derrubar o decoder.
            Log.w(TAG, "Falha ao salvar dump frame-$index.jpg (ignorado): ${e.message}")
        }
    }
}

/**
 * Validação pura (sem Android) de payloads JPEG — testável em JVM. Vive no mesmo arquivo que o
 * [MjpegDecoder] pelo mesmo motivo que `HevcNal` vive junto do `HevcDecoder`: parsing puro,
 * separado do que depende do runtime Android.
 */
object MjpegFrames {

    /**
     * Heurística barata de "isto parece um JPEG?": exige tamanho mínimo plausível (>= 4 bytes) e o
     * marcador SOI `FF D8` no início (todo JPEG começa por ele).
     *
     * ## Leniência: NÃO exigimos o EOI (`FF D9`) no fim — de propósito
     * O payload MJPEG que sai da câmera dos óculos pode vir com bytes de padding após o EOI (pela
     * granularidade do transporte USB bulk) ou, em casos de borda, com o EOI ausente/deslocado. Exigir
     * `FF D9` exatamente no fim rejeitaria frames perfeitamente decodificáveis. O [BitmapFactory] do
     * decode real tolera padding pós-EOI, então esta validação também tolera: verifica só o prefixo. É
     * uma triagem barata para descartar lixo óbvio (ex.: um access unit HEVC `00 00 00 01`), não um
     * validador de conformidade JPEG.
     */
    fun isLikelyJpeg(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
    }
}

/**
 * Remontador de frames JPEG a partir de chunks arbitrários — PURO (sem Android), testável em JVM.
 *
 * ## Por que ele existe (medido em hardware — 2026-07-23, 7ª rodada)
 * O [FrameAssembler] (montagem por headers UVC FID/EOF) foi calibrado pro stream HEVC e NÃO honra
 * o framing do stream MJPEG: em hardware, cada "frame" emitido era exatamente 65534 bytes (um
 * payload de bulk read) — JPEGs de ~70-100KB chegavam TRUNCADOS (decodificavam com a faixa
 * inferior cinza) e o restante virava fragmentos de 3-12KB descartados como "não parece JPEG"
 * (698 num único stream), até o tracking morrer. Em vez de ensinar o FrameAssembler sobre MJPEG
 * (e arriscar o caminho HEVC estável), este remontador ignora POR COMPLETO o framing UVC:
 * acumula os chunks como um fluxo de bytes e recorta frames pelos próprios marcadores JPEG —
 * SOI (`FF D8`) até EOI (`FF D9`) — que é a única delimitação em que dá pra confiar.
 *
 * Seguro contra falso-EOI: dentro do entropy-coded data do JPEG, bytes `FF` são escapados como
 * `FF 00` (byte stuffing) e os únicos marcadores que aparecem são os restarts `FF D0-D7` — um
 * `FF D9` genuíno só ocorre no fim do frame. (MJPEG de câmera UVC não embute thumbnail EXIF, que
 * seria a exceção com EOI interno.)
 *
 * Lixo antes do SOI (fragmentos de um frame cuja cabeça se perdeu, padding) é descartado e
 * contado em [discardedBytes]. O acumulador tem teto [MAX_BUFFER_BYTES] — estourar (nunca achou
 * EOI num acúmulo absurdo = stream não é JPEG de verdade) zera o buffer e conta o descarte, e o
 * chamador pode usar [discardedBytes]/[framesExtracted] pra decidir desistir do formato.
 *
 * Single-thread por contrato (a thread do stream USB) — sem locks.
 */
class MjpegStreamAssembler(private val maxBufferBytes: Int = MAX_BUFFER_BYTES) {

    companion object {
        /** Teto do acumulador: ~10x um frame 1080p típico (~100KB) — muito acima de qualquer
         * frame legítimo, baixo o bastante pra abortar rápido um stream que não é JPEG. */
        const val MAX_BUFFER_BYTES = 1_048_576
    }

    // Growable buffer: buf[0 until len] = bytes not yet emitted. Eye Tools fork: the previous
    // `buffer + chunk` / copyOfRange per chunk (and rescanning from the SOI every time) cost
    // O(chunks²) copies per frame; this appends once, resumes the EOI search where it stopped and
    // compacts once per frame.
    private var buf = ByteArray(256 * 1024)
    private var len = 0
    private var eoiScanFrom = -1 // where to resume the EOI search in the current frame (-1 = no SOI yet)

    /** Total de bytes descartados (lixo pré-SOI + estouros de buffer) — diagnóstico/desistência. */
    var discardedBytes: Long = 0L
        private set

    /** Total de frames completos extraídos — diagnóstico/desistência. */
    var framesExtracted: Long = 0L
        private set

    /**
     * Alimenta um chunk (qualquer fronteira — pode conter frame parcial, múltiplos frames, lixo)
     * e devolve os frames JPEG COMPLETOS (SOI..EOI inclusive) extraíveis até aqui, em ordem.
     * Lista vazia = nada completo ainda (frame ainda atravessando chunks).
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (len + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + chunk.size))
        System.arraycopy(chunk, 0, buf, len, chunk.size)
        len += chunk.size

        val frames = ArrayList<ByteArray>(1)
        var start = 0 // first byte not yet consumed
        while (true) {
            if (eoiScanFrom < 0) {
                val soi = indexOfMarker(buf, len, start, 0xD8)
                if (soi < 0) {
                    // Sem SOI: tudo é lixo (mantém só o último byte — pode ser um FF de um SOI
                    // partido entre chunks).
                    val keepFrom = maxOf(start, len - 1)
                    discardedBytes += keepFrom - start
                    start = keepFrom
                    break
                }
                discardedBytes += soi - start // lixo entre o último frame e este SOI
                start = soi
                eoiScanFrom = soi + 2
            }
            // Eye Tools fork: a new SOI before the EOI means this frame lost its tail on the USB
            // link (seen on hardware: merged 2-frame "JPEGs" that decode with errors) — drop the
            // broken head and restart at the new frame. FF D8 cannot occur inside entropy data.
            val eoi = indexOfMarker(buf, len, eoiScanFrom, 0xD9)
            val nextSoi = indexOfMarker(buf, if (eoi < 0) len else eoi, eoiScanFrom, 0xD8)
            if (nextSoi >= 0) {
                discardedBytes += nextSoi - start
                start = nextSoi
                eoiScanFrom = nextSoi + 2
                continue
            }
            if (eoi < 0) {
                eoiScanFrom = maxOf(start + 2, len - 1) // resume here (FF may be split)
                if (len - start > maxBufferBytes) {
                    // Nunca fecha um frame num acúmulo absurdo — stream não é JPEG de verdade.
                    discardedBytes += len - start
                    start = len
                    eoiScanFrom = -1
                }
                break
            }
            frames.add(buf.copyOfRange(start, eoi + 2))
            framesExtracted++
            start = eoi + 2
            eoiScanFrom = -1
        }
        // compact once
        if (start > 0) {
            System.arraycopy(buf, start, buf, 0, len - start)
            len -= start
            if (eoiScanFrom >= 0) eoiScanFrom -= start
        }
        return frames
    }

    /** Zera buffer e contadores (novo stream). */
    fun reset() {
        len = 0
        eoiScanFrom = -1
        discardedBytes = 0L
        framesExtracted = 0L
    }

    /** Índice do marcador `FF <second>` em data[0 until end] a partir de [from], ou -1. */
    private fun indexOfMarker(data: ByteArray, end: Int, from: Int, second: Int): Int {
        var i = maxOf(0, from)
        val secondByte = second.toByte()
        while (i + 1 < end) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == secondByte) return i
            i++
        }
        return -1
    }
}
