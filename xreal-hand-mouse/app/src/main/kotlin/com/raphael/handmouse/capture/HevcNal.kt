package com.raphael.handmouse.capture

import java.io.ByteArrayOutputStream

/**
 * Parsing puro de NAL units HEVC (Annex B) — sem dependência de Android, testável em JVM.
 *
 * Extraído/adaptado da lógica de `CameraActivity.kt` do repo Aloim
 * (splitNalUnits + processHevcAccessUnit + montagem de csd-0 em initDecoder), que é a
 * fonte de verdade do protocolo (ver task-2-report.md para divergências com o brief).
 */
class HevcNal {

    companion object {
        const val TYPE_TRAIL_N = 0
        const val TYPE_TRAIL_R = 1
        const val TYPE_IDR_W_RADL = 19
        const val TYPE_IDR_N_LP = 20
        const val TYPE_VPS = 32
        const val TYPE_SPS = 33
        const val TYPE_PPS = 34

        /** HEVC: tipo de NAL = bits 1-6 do primeiro byte após o start code. */
        fun nalType(headerByte: Byte): Int = (headerByte.toInt() shr 1) and 0x3F

        /**
         * Divide um bitstream Annex B em NAL units individuais, procurando start codes
         * de 3 bytes (00 00 01) ou 4 bytes (00 00 00 01). Réplica fiel de
         * CameraActivity.splitNalUnits(): se nenhum start code for encontrado, o buffer
         * inteiro (se não vazio) é tratado como uma única unidade.
         */
        fun splitNalUnits(data: ByteArray): List<ByteArray> {
            val units = mutableListOf<ByteArray>()
            val startPositions = mutableListOf<Int>()

            var i = 0
            while (i < data.size - 3) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        startPositions.add(i)
                        i += 4
                        continue
                    }
                    if (data[i + 2] == 1.toByte()) {
                        startPositions.add(i)
                        i += 3
                        continue
                    }
                }
                i++
            }

            for (j in startPositions.indices) {
                val start = startPositions[j]
                val end = if (j + 1 < startPositions.size) startPositions[j + 1] else data.size
                if (end > start) {
                    units.add(data.copyOfRange(start, end))
                }
            }

            if (units.isEmpty() && data.isNotEmpty()) {
                units.add(data)
            }

            return units
        }

        /** csd-0 = VPS + SPS + PPS concatenados, cada um já incluindo seu start code. */
        fun buildCsd0(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(vps.size + sps.size + pps.size)
            out.write(vps)
            out.write(sps)
            out.write(pps)
            return out.toByteArray()
        }

        /**
         * Índice do byte de tipo dentro de uma NAL unit (após o start code), ou null se a
         * unidade for curta demais para conter um header válido. Réplica fiel do critério
         * usado em CameraActivity.processHevcAccessUnit (mínimo de 5 bytes, mesmo para
         * start code de 3 bytes).
         */
        private fun typeOf(nal: ByteArray): Int? {
            if (nal.size < 5) return null
            val typeIdx = if (nal[2] == 1.toByte()) 3 else 4
            if (typeIdx >= nal.size) return null
            return nalType(nal[typeIdx])
        }
    }

    var vps: ByteArray? = null
        private set
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    /** true assim que VPS, SPS e PPS já foram todos capturados ao menos uma vez. */
    val parameterSetsComplete: Boolean
        get() = vps != null && sps != null && pps != null

    /** csd-0 pronto para o MediaCodec, ou null enquanto [parameterSetsComplete] for false. */
    fun currentCsd0(): ByteArray? {
        val v = vps ?: return null
        val s = sps ?: return null
        val p = pps ?: return null
        return buildCsd0(v, s, p)
    }

    /**
     * Processa uma access unit HEVC completa (um frame montado pelo [FrameAssembler]).
     * Captura VPS/SPS/PPS internamente e retorna apenas as NALs de slice (TRAIL/IDR/outras)
     * prontas para o decoder — NALs de slice recebidas antes do primeiro conjunto completo
     * de parameter sets são descartadas (réplica do gate `if (decoderReady) nalQueue.offer(nal)`
     * do repo Aloim).
     */
    fun processAccessUnit(data: ByteArray): List<ByteArray> {
        val ready = mutableListOf<ByteArray>()
        for (nal in splitNalUnits(data)) {
            val type = typeOf(nal) ?: continue
            when (type) {
                TYPE_VPS -> vps = nal
                TYPE_SPS -> sps = nal
                TYPE_PPS -> pps = nal
                else -> if (parameterSetsComplete) ready.add(nal)
            }
        }
        return ready
    }
}
