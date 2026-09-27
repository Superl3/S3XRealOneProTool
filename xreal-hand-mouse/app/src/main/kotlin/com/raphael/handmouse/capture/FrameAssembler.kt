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
 */
class FrameAssembler(private val maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE) {

    companion object {
        const val UVC_HEADER_FID = 0x01
        const val UVC_HEADER_EOF = 0x02

        /** dwMaxVideoFrameSize negociado no probe/commit (PLANO.md §3.1). */
        const val DEFAULT_MAX_FRAME_SIZE = 5_242_880
    }

    private val frameAccum = ByteArrayOutputStream(256 * 1024)
    private var lastFid = -1

    /**
     * Processa um payload de um único `bulkTransfer()` (buffer[0 until length]).
     * Retorna 0, 1 ou 2 frames completos que este payload finalizou, na ordem em que
     * foram concluídos (é possível completar até dois: o frame anterior via toggle de FID
     * e, no mesmo payload, um novo frame de pacote único que já chega com EOF setado).
     */
    fun offerPayload(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()

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

            if (eof && frameAccum.size() > 0) {
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
