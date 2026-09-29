package com.raphael.handmouse.capture

import java.io.ByteArrayOutputStream

/**
 * Montagem pura de frames HEVC a partir de payloads UVC bulk — sem dependência de Android,
 * testável em JVM.
 *
 * Extraído/adaptado de `UvcCameraHelper.readAndDeliverFrames()` do repo Aloim, que é a fonte
 * de verdade do protocolo (ver task-2-report.md para divergências com o brief, em especial
 * sobre o tratamento de header inválido e do toggle de FID sem EOF).
 *
 * Header UVC (byte0 = comprimento do header 2-12, byte1 = bitfield FID/EOF/ERR).
 *
 * ## Payloads maiores que uma leitura (2026-09-29, "화면 깨짐")
 * A Eye manda cada frame MJPEG como UM payload UVC (um header) maior que o buffer de leitura
 * ([readSize], 65536): a 1ª leitura traz header + 65534 bytes e as seguintes são continuação SEM
 * header. Antes, toda leitura era tratada como início de payload — uma continuação cujo 1º byte
 * de dados caía em 2..12 (~5,8% em dados JPEG) perdia esses bytes como "header" e ainda tinha
 * bits de dado lidos como FID/EOF. Medido numa gravação de 6min50s: 173 frames com a parte de
 * baixo destruída exatamente depois de 65534·k bytes, nenhum frame < 65534 bytes quebrado, e em
 * frames intactos o byte 65534 quase nunca (6/10975) em 2..12. Agora um payload continua enquanto
 * as leituras vêm cheias (e não passou de [maxPayloadSize], quando conhecido); uma leitura curta,
 * vazia ou com timeout ([endPayload]) o encerra, e o EOF do header só fecha o frame no fim do
 * payload.
 */
class FrameAssembler(
    private val maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
    /** Buffer size of each bulk read — a read that fills it may continue the same payload. */
    private val readSize: Int = DEFAULT_READ_SIZE,
    /** Negotiated dwMaxPayloadTransferSize (0 = unknown: any full read continues the payload). */
    private val maxPayloadSize: Int = 0,
) {

    companion object {
        const val UVC_HEADER_FID = 0x01
        const val UVC_HEADER_EOF = 0x02

        /** dwMaxVideoFrameSize negociado no probe/commit (PLANO.md §3.1). */
        const val DEFAULT_MAX_FRAME_SIZE = 5_242_880

        const val DEFAULT_READ_SIZE = 65_536
    }

    private val frameAccum = ByteArrayOutputStream(256 * 1024)
    private var lastFid = -1

    /** The current payload continues into the next read (so that read has no header). */
    var inPayload: Boolean = false
        private set
    private var payloadBytes = 0L
    private var payloadEof = false

    /**
     * Processa um payload de um único `bulkTransfer()` (buffer[0 until length]).
     * Retorna 0, 1 ou 2 frames completos que este payload finalizou, na ordem em que
     * foram concluídos (é possível completar até dois: o frame anterior via toggle de FID
     * e, no mesmo payload, um novo frame de pacote único que já chega com EOF setado).
     */
    fun offerPayload(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return endPayload()

        if (inPayload) {
            // Continuation of the payload started by an earlier read: raw data, no header.
            appendCapped(buffer, 0, length)
            payloadBytes += length
            return if (continues(length)) emptyList() else endPayload()
        }

        val completed = mutableListOf<ByteArray>()
        val headerLen = buffer[0].toInt() and 0xFF
        val validHeader = length >= 2 && headerLen in 2..12 && headerLen <= length

        if (validHeader) {
            val headerInfo = buffer[1].toInt() and 0xFF
            val fid = headerInfo and UVC_HEADER_FID
            val eof = (headerInfo and UVC_HEADER_EOF) != 0

            if (lastFid >= 0 && fid != lastFid && frameAccum.size() > 0) {
                completed += takeFrame()
            }
            lastFid = fid

            val payloadLen = length - headerLen
            if (payloadLen > 0) {
                appendCapped(buffer, headerLen, payloadLen)
            }

            payloadBytes = length.toLong()
            payloadEof = eof
            if (continues(length)) {
                inPayload = true // EOF applies when this payload ends (endPayload)
            } else if (eof && frameAccum.size() > 0) {
                completed += takeFrame()
            }
        } else {
            // Header não reconhecível (payload curto demais, comprimento fora de 2..12, ou
            // maior que os bytes lidos): o repo Aloim NÃO descarta este payload, trata como
            // continuação bruta do frame em montagem (branch "else" de readAndDeliverFrames).
            appendCapped(buffer, 0, length)
        }

        return completed
    }

    /** Ends the current payload: a short/empty read, a timeout, or the payload size reached.
     * Returns the frame its EOF completes, if any. Safe to call when no payload is open. */
    fun endPayload(): List<ByteArray> {
        if (!inPayload) return emptyList()
        inPayload = false
        return if (payloadEof && frameAccum.size() > 0) listOf(takeFrame()) else emptyList()
    }

    private fun continues(length: Int): Boolean =
        length == readSize && (maxPayloadSize <= 0 || payloadBytes < maxPayloadSize)

    private fun appendCapped(buffer: ByteArray, offset: Int, len: Int) {
        frameAccum.write(buffer, offset, len)
        if (frameAccum.size() > maxFrameSize) {
            // Extensão em relação ao repo (que acumula sem limite): descarta frame estourado.
            frameAccum.reset()
        }
    }

    private fun takeFrame(): ByteArray {
        val bytes = frameAccum.toByteArray()
        frameAccum.reset()
        return bytes
    }
}
