package com.raphael.handmouse.capture

import android.content.Context
import android.hardware.usb.UsbConstants
import android.os.SystemClock
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Negociação UVC (probe/commit) e leitura bulk da câmera Eye da XREAL One Pro.
 *
 * Adaptado de `UvcCameraHelper.kt` do repo Aloim (com.reveng26.xrcam), preservando a lógica
 * do protocolo: claim das interfaces classe 0x0E (Video) subclasses VideoControl/
 * VideoStreaming, probe/commit via controlTransfer, setInterface no alternate com endpoint
 * bulk IN, loop de bulkTransfer com buffer de 65536 bytes / timeout 500ms.
 *
 * Este app suporta apenas a câmera Eye/RGB da One Pro (VID 13080 / PID 1078) — os
 * dispositivos OV580 (glasses mais antigas) do repo original foram removidos por estarem
 * fora de escopo desta tarefa, não por divergência de protocolo.
 *
 * A montagem de frames em si (FID/EOF, header UVC) foi extraída para [FrameAssembler]
 * (classe pura, testada via TDD) — ver task-2-report.md para as divergências encontradas
 * entre o brief e o comportamento real do repo (tratamento de header inválido e de FID sem
 * EOF).
 */
class UvcCameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "UvcCameraHelper"

        const val USB_CLASS_VIDEO = 14
        const val UVC_SC_VIDEOCONTROL = 1
        const val UVC_SC_VIDEOSTREAMING = 2

        const val UVC_SET_CUR = 0x01
        const val UVC_GET_CUR = 0x81
        const val UVC_GET_DEF = 0x84

        const val VS_PROBE_CONTROL = 0x01
        const val VS_COMMIT_CONTROL = 0x02

        const val USB_RT_CLASS_IFACE_SET = 0x21
        const val USB_RT_CLASS_IFACE_GET = 0xA1

        private const val BULK_BUFFER_SIZE = 65536

        /** 500 → 125ms (2026-07-23, 3ª rodada de estabilidade): o timeout do bulkTransfer é o
         * "grão" da detecção de stall — com 500ms, o mínimo detectável era 1s de congelamento.
         * A 60fps um frame chega a cada ~16ms, então 125ms sem NENHUM byte já é anomalia; o
         * grão menor deixa detectar o stall em ~0,5s (4 timeouts) sem falso-positivo. Custo em
         * stream saudável: zero (as leituras retornam com dados muito antes do timeout). */
        private const val BULK_TIMEOUT_MS = 125

        /** Janelas de SILÊNCIO (tempo real, não contagem de leituras) antes de declarar stall —
         * 5ª rodada 2026-07-23 ("quero perto de zero resets"). A mudança de filosofia: os logs
         * de hardware mostraram que o CICLO DE RESTART em si desestabiliza a câmera (streams
         * recém-nascidos morrendo com 37-161 frames em cascata), então derrubar o stream ao
         * primeiro soluço era o remédio virando veneno. Agora um silêncio mid-stream é TOLERADO
         * por [STALL_SILENCE_MID_STREAM_MS] com o stream vivo — se a câmera retomar sozinha, o
         * cursor só congela esse instante e NADA é reconstruído (o log "soluço superado" mede
         * quantas vezes isso salva um restart). Só silêncio ACIMA da janela vira recuperação.
         *
         * - AQUECENDO ([STALL_SILENCE_STARTUP_MS]): antes de [WARMUP_FRAMES] frames contínuos —
         *   a pausa pós-GOP-inicial do encoder é normal (2-3 frames e ~1-2s mudo).
         * - QUENTE ([STALL_SILENCE_MID_STREAM_MS]): tolera o soluço; restart só se passar disto.
         *
         * ENDPOINT MORTO é outra coisa: bulkTransfer retornando -1 NA HORA (sem bloquear o
         * timeout de 125ms) — [DEAD_ENDPOINT_MIN_READS] falhas dentro de
         * [DEAD_ENDPOINT_WINDOW_MS] não são silêncio, são um endpoint defunto; aí sim sinaliza
         * recuperação imediatamente (esperar não ressuscita um endpoint morto). */
        private const val STALL_SILENCE_STARTUP_MS = 3000L
        private const val STALL_SILENCE_MID_STREAM_MS = 2000L
        private const val DEAD_ENDPOINT_MIN_READS = 8
        private const val DEAD_ENDPOINT_WINDOW_MS = 250L

        /** Silêncio superado ≥ isto é logado (diagnóstico: mede quantos restarts a tolerância
         * evitou de verdade). */
        private const val HICCUP_LOG_THRESHOLD_MS = 300L

        /** Largura mínima aceitável pra qualquer candidato de formato — o FrameConverter
         * downscala tudo pra 768x576 antes do MediaPipe, então qualquer frame >= 768 de largura
         * mantém a precisão de tracking atual. */
        private const val MIN_CANDIDATE_WIDTH = 768

        // Subtipos de descritores class-specific (bDescriptorType 0x24) da interface UVC
        // VideoStreaming — usados pra enumerar formatos/frames disponíveis (UVC 1.5, §3.9.2).
        private const val USB_DT_CS_INTERFACE = 0x24
        private val UVC_FORMAT_SUBTYPES = intArrayOf(0x04, 0x06, 0x10, 0x12) // UNCOMPRESSED/MJPEG/FRAME_BASED/STREAM_BASED
        private val UVC_FRAME_SUBTYPES = intArrayOf(0x05, 0x07, 0x11) // frames dos formatos acima

        /** Frames contínuos que provam que o stream "esquentou" (≈1s a 60fps) — antes disso a
         * janela de stall é a de aquecimento (3s), não a de mid-stream. Ver acima. */
        private const val WARMUP_FRAMES = 60
    }

    interface Listener {
        fun onCameraFound(description: String)
        fun onStreamStarted()
        fun onStreamStopped()
        fun onError(message: String)
        /** Stream chocou sem entregar NENHUM frame (ativação da câmera falhou silenciosamente) —
         * recuperável reiniciando o pipeline. Distinto de [onError] (que é terminal). */
        fun onStreamStalled()
        fun onLog(message: String)
        fun onFrameReceived(data: ByteArray)
    }

    var listener: Listener? = null
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var streamingConnection: UsbDeviceConnection? = null
    private var streamingInterface: UsbInterface? = null
    private var videoControlInterface: UsbInterface? = null

    @Volatile
    private var isStreaming = false
    private var streamThread: Thread? = null

    // ---- Seleção de formato por lista de candidatos + fallback (6ª rodada 2026-07-23) ----
    // Substitui a máquina de "frame experimental" único (5ª rodada) por uma LISTA ORDENADA de
    // candidatos, montada dos descritores USB em [startStream] via [buildFormatCandidates]:
    //   #1 MJPEG paisagem   #2 HEVC reduzido não-nativo   #3 HEVC nativo (último recurso).
    // Por que essa ordem: medido em hardware 2026-07-23 que fmt2 inteiro é MJPEG (payload começa
    // em FF D8) e rodou 4000 frames sem stall no teste — bem mais estável que o HEVC nativo, que
    // codifica 2048x1512@60 e sobrecarrega o chip dos óculos (que também faz o anchor), suspeito
    // de contribuir pros resets de link. O nativo fica só como rede de segurança. O pipeline
    // downscala tudo pra 768x576 antes do MediaPipe, então qualquer candidato com largura
    // >= MIN_CANDIDATE_WIDTH preserva a precisão de tracking atual.
    //
    // O cursor [formatCandidateCursor] aponta o candidato ativo e NUNCA regride (até o processo
    // reiniciar): silêncio persistente ou falha de decode só empurram pra frente, pro próximo
    // candidato (menos preferido) — nunca de volta pro que já se mostrou ruim nesta sessão.
    private var formatCandidates: List<UvcFrameDesc> = emptyList()

    /** Índice do candidato ATIVO em [formatCandidates]. Começa em 0 (o mais preferido) e só
     * avança. @Volatile porque [demoteActiveFormat] o mexe da thread do serviço, enquanto a
     * thread de stream o lê/avança no pós-loop de [readAndDeliverFrames]. */
    @Volatile
    private var formatCandidateCursor = 0

    /** Strikes de SILÊNCIO do candidato ATIVO (ver fim de [readAndDeliverFrames]): só um stream
     * que negociou OK mas ficou MUDO com 0 frames conta contra o candidato — endpoint morto é
     * falha de LINK (acontece igual em qualquer formato, verificado em hardware às 12:58:
     * reduzido e 2 nativos morreram idênticos) e NÃO conta. 2 strikes avançam o cursor pro
     * próximo candidato. Zera ao avançar (a semântica é "por candidato"). */
    private var activeSilenceStrikes = 0

    /** Subtipo de formato do candidato ATIVO, exposto pro serviço decidir qual decoder
     * instanciar (0x10 = HEVC/frame-based, 0x06 = MJPEG). Setado em [startStream] ao escolher o
     * candidato; default = HEVC nativo (usado quando os descritores falham/vazios). */
    @Volatile
    var activeFormatSubtype = 0x10
        private set

    /** Largura/altura do candidato ATIVO (o que a câmera vai entregar), exposto pro serviço.
     * Default = nativo 2048x1512. Setados junto com [activeFormatSubtype] em [startStream]. */
    @Volatile
    var activeFrameWidth = 2048
        private set

    @Volatile
    var activeFrameHeight = 1512
        private set

    /** Um frame descriptor UVC (classe-específica da interface VideoStreaming). [formatSubtype]
     * identifica a família do formato (0x04 uncompressed, 0x06 MJPEG, 0x10 frame-based —
     * H.264/HEVC — etc). */
    data class UvcFrameDesc(
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val formatSubtype: Int,
    )

    /**
     * Stream-format preference (Eye Tools fork). [FormatPreference.MJPEG_FIRST] is the upstream
     * order (most stable for tracking). The recorder asks for HEVC because HEVC access units can be
     * remuxed into small files with zero re-encoding; MJPEG stays in the list as the fallback.
     */
    enum class FormatPreference { MJPEG_FIRST, HEVC_REDUCED_FIRST, HEVC_NATIVE_FIRST }

    @Volatile
    var formatPreference = FormatPreference.MJPEG_FIRST

    /**
     * User camera settings (2026-09-29). The Eye's JPEG quality is fixed (~IJG 30, no UVC control
     * for it), so night footage can only be helped through exposure and anti-flicker, which the
     * camera exposes as standard UVC controls (xrprobe PROTOCOL.md; confirmed per device by the
     * diagnostics log). -1 / 0 leave the camera default untouched.
     */
    data class CameraControls(
        /** Processing Unit power-line frequency: -1 = camera default, 0 = off, 1 = 50 Hz, 2 = 60 Hz. */
        val powerLineFrequency: Int = -1,
        /** Manual exposure time in 100 µs units; 0 = auto exposure (camera default). */
        val manualExposure100us: Int = 0,
    )

    @Volatile
    var cameraControls = CameraControls()

    /** Configuration descriptor of the open camera — entity ids for [applyCameraControls]. */
    @Volatile
    private var rawDescriptorsCache: ByteArray? = null
    private var appliedControls: CameraControls? = null
        private set

    /** Changes the preferred order; takes effect at the next [startStream]. Returns true if it
     * changed (the caller then restarts the stream). Resets the fallback cursor: the new order
     * gets a fresh chance, the "never regress" rule applies within one preference. */
    fun setFormatPreference(pref: FormatPreference): Boolean {
        if (pref == formatPreference) return false
        formatPreference = pref
        formatCandidateCursor = 0
        activeSilenceStrikes = 0
        listener?.onLog("Format preference: $pref")
        return true
    }

    /** Procura o dispositivo composto XREAL com uma interface UVC Video Streaming exposta. */
    fun findCamera(): UsbDevice? {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "findCamera(): ${deviceList.size} dispositivos USB")

        for (dev in deviceList.values) {
            if (dev.vendorId == GlassesConnection.XREAL_VID) {
                for (i in 0 until dev.interfaceCount) {
                    val iface = dev.getInterface(i)
                    if (iface.interfaceClass == USB_CLASS_VIDEO && iface.interfaceSubclass == UVC_SC_VIDEOSTREAMING) {
                        listener?.onCameraFound("UVC Eye camera (IF#${iface.id}) em VID=${dev.vendorId} PID=${dev.productId}")
                        return dev
                    }
                }
            }
        }

        listener?.onLog("Camera not found yet")
        return null
    }

    /**
     * Enumera os frame descriptors UVC do descritor de configuração cru — caminho NÃO-cego pro
     * experimento de resolução: só tentamos um frameIndex que o hardware declara existir.
     * Parser mínimo: anda descritor a descritor (bLength/bDescriptorType); em CS_INTERFACE
     * (0x24), formatos carregam bFormatIndex no offset 3 e frames carregam bFrameIndex@3 +
     * wWidth@5 + wHeight@7 (little-endian) — layout comum a UNCOMPRESSED/MJPEG/FRAME_BASED.
     */
    private fun parseFrameDescriptors(raw: ByteArray): List<UvcFrameDesc> {
        val out = mutableListOf<UvcFrameDesc>()
        var currentFormat = -1
        var currentFormatSubtype = -1
        var i = 0
        while (i + 2 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            if ((raw[i + 1].toInt() and 0xFF) == USB_DT_CS_INTERFACE) {
                val sub = raw[i + 2].toInt() and 0xFF
                when {
                    sub in UVC_FORMAT_SUBTYPES && len > 3 -> {
                        currentFormat = raw[i + 3].toInt() and 0xFF
                        currentFormatSubtype = sub
                    }
                    sub in UVC_FRAME_SUBTYPES && len > 8 && currentFormat > 0 -> {
                        val frameIndex = raw[i + 3].toInt() and 0xFF
                        val w = (raw[i + 5].toInt() and 0xFF) or ((raw[i + 6].toInt() and 0xFF) shl 8)
                        val h = (raw[i + 7].toInt() and 0xFF) or ((raw[i + 8].toInt() and 0xFF) shl 8)
                        out.add(UvcFrameDesc(currentFormat, frameIndex, w, h, currentFormatSubtype))
                    }
                }
            }
            i += len
        }
        return out
    }

    /** Monta a LISTA ORDENADA de candidatos de formato a partir dos descritores UVC (6ª rodada
     * 2026-07-23). A ordem é a preferência de estabilidade medida em hardware:
     *   #1 MJPEG PAISAGEM — menor área com sub=0x06, largura >= [MIN_CANDIDATE_WIDTH] E
     *      largura > altura. Os retratos (1080x1920 / 720x1280) são descartados de propósito.
     *      Medido em hardware 2026-07-23: fmt2 inteiro é MJPEG (payload começa em FF D8) e rodou
     *      4000 frames sem stall no teste — o mais estável, por isso o preferido.
     *   #2 HEVC REDUZIDO — menor área com sub=0x10, largura >= [MIN_CANDIDATE_WIDTH], que NÃO
     *      seja o nativo (fmt1/frm1). Hoje: fmt1/frm2 1920x1080 (validado em hardware).
     *   #3 HEVC NATIVO — fmt1/frm1, sempre presente como último recurso; se os descritores
     *      falharem/vazios, entra o default 2048x1512 direto.
     * Candidatos ausentes nos descritores são simplesmente pulados; o nativo é sempre incluído,
     * então a lista nunca fica vazia. */
    private fun buildFormatCandidates(descs: List<UvcFrameDesc>): List<UvcFrameDesc> {
        // #1 MJPEG paisagem (o mais estável — ver Javadoc)
        val mjpeg = descs.filter {
            it.formatSubtype == 0x06 &&
                it.width >= MIN_CANDIDATE_WIDTH &&
                it.width > it.height
        }.minByOrNull { it.width * it.height }

        // #2 HEVC reduzido, não-nativo (o que o HevcDecoder atual fala, com menos carga)
        val reduced = descs.filter {
            it.formatSubtype == 0x10 &&
                it.width >= MIN_CANDIDATE_WIDTH &&
                !(it.formatIndex == 1 && it.frameIndex == 1)
        }.minByOrNull { it.width * it.height }

        // #3 HEVC nativo (último recurso — default se os descritores não o expõem)
        val native = descs.firstOrNull { it.formatIndex == 1 && it.frameIndex == 1 }
            ?: UvcFrameDesc(formatIndex = 1, frameIndex = 1, width = 2048, height = 1512, formatSubtype = 0x10)

        // Eye Tools fork: the order depends on [formatPreference]; the native HEVC stays the
        // last-resort safety net in every order (it is always present).
        val order = when (formatPreference) {
            FormatPreference.MJPEG_FIRST -> listOf(mjpeg, reduced, native)
            FormatPreference.HEVC_REDUCED_FIRST -> listOf(reduced, mjpeg, native)
            FormatPreference.HEVC_NATIVE_FIRST -> listOf(native, mjpeg)
        }
        return order.filterNotNull()
    }

    /** Descrição curta de um candidato pros logs. */
    private fun describeCandidate(c: UvcFrameDesc): String =
        "fmt${c.formatIndex}/frm${c.frameIndex} ${c.width}x${c.height} sub=0x%02x".format(c.formatSubtype)

    /** Avança o cursor pro próximo candidato de formato (nunca regride) e zera os strikes de
     * silêncio (semântica "por candidato"). Se já está no último candidato, apenas loga — não há
     * pra onde cair. */
    private fun advanceFormatCandidate(reason: String) {
        val lastIndex = formatCandidates.lastIndex.coerceAtLeast(0)
        if (formatCandidateCursor >= lastIndex) {
            listener?.onLog("Already at final format candidate (#$formatCandidateCursor) — no fallback remains ($reason)")
            return
        }
        formatCandidateCursor++
        activeSilenceStrikes = 0
        listener?.onLog("Trying format candidate #$formatCandidateCursor ($reason)")
    }

    /** Avança IMEDIATAMENTE pro próximo candidato de formato. Chamado pelo serviço quando o
     * DECODE do formato ativo falha de forma persistente — algo que o helper não enxerga (ele só
     * vê bytes fluindo pelo endpoint; se o decoder não consegue montar imagem, quem percebe é o
     * serviço). Se já está no último candidato, apenas loga. */
    fun demoteActiveFormat(reason: String) {
        advanceFormatCandidate("decode falhou: $reason")
    }

    fun startStream(device: UsbDevice) {
        if (isStreaming || streamThread?.isAlive == true) {
            listener?.onLog("UVC stream already active — ignoring duplicate start")
            return
        }
        listener?.onLog("Opening camera device...")

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            listener?.onError("Failed to open USB device")
            return
        }
        streamingConnection = conn

        var vcIface: UsbInterface? = null
        var vsIface: UsbInterface? = null
        var videoEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_VIDEO) {
                when (iface.interfaceSubclass) {
                    UVC_SC_VIDEOCONTROL -> if (vcIface == null) vcIface = iface
                    UVC_SC_VIDEOSTREAMING -> if (vsIface == null && iface.endpointCount > 0) {
                        vsIface = iface
                        for (e in 0 until iface.endpointCount) {
                            val ep = iface.getEndpoint(e)
                            if (ep.direction == UsbConstants.USB_DIR_IN) {
                                videoEndpoint = ep
                                val epType = when (ep.type) {
                                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                                    else -> "type=${ep.type}"
                                }
                                listener?.onLog("Stream EP: $epType maxPkt=${ep.maxPacketSize} addr=0x${ep.address.toString(16)}")
                                break
                            }
                        }
                    }
                }
            }
        }

        if (vsIface == null || videoEndpoint == null) {
            listener?.onError("No UVC streaming endpoint found")
            conn.close()
            return
        }

        videoControlInterface = vcIface
        streamingInterface = vsIface

        if (vcIface != null) {
            conn.claimInterface(vcIface, true)
            listener?.onLog("VC IF#${vcIface.id} claimed")
        }
        if (!conn.claimInterface(vsIface, true)) {
            listener?.onError("Failed to claim VS IF#${vsIface.id}")
            conn.close()
            return
        }
        listener?.onLog("VS IF#${vsIface.id} claimed")

        // Seleção de formato por candidatos (ver campos no topo): enumera o que a câmera DECLARA
        // suportar e loga tudo — mesmo sem alternativa útil, o log documenta o hardware. Monta a
        // lista ordenada de candidatos e escolhe o apontado pelo cursor (que só avança via
        // fallback de silêncio ou [demoteActiveFormat]).
        val frameDescs = try {
            parseFrameDescriptors(conn.rawDescriptors ?: ByteArray(0))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler descritores UVC (seguindo com nativa): ${e.message}")
            emptyList()
        }
        if (frameDescs.isNotEmpty()) {
            listener?.onLog("Available UVC frames: " + frameDescs.joinToString {
                "fmt${it.formatIndex}(sub=0x%02x)/frm${it.frameIndex}=${it.width}x${it.height}".format(it.formatSubtype)
            })
        }

        // 2026-09-29 (video quality): what the camera declares — per-frame bitrate/buffer and
        // the exposure/gain/brightness controls — plus their current values. Read-only.
        try {
            val raw = conn.rawDescriptors ?: ByteArray(0)
            UvcDiagnostics.describe(raw).forEach { listener?.onLog(it) }
            rawDescriptorsCache = raw
            appliedControls = null
            if (vcIface != null) {
                applyCameraControls(conn, vcIface, raw)
                logControlValues(conn, vcIface, raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "UVC diagnostics failed: ${e.message}")
        }

        formatCandidates = buildFormatCandidates(frameDescs)
        listener?.onLog("Format candidates (in order): " + formatCandidates.mapIndexed { idx, c ->
            "#$idx ${describeCandidate(c)}"
        }.joinToString())

        // Clamp defensivo: o cursor pode estar além do fim se a lista encolher entre streams (não
        // deveria, é o mesmo hardware) — nunca regride abaixo do que já demotou, só limita ao fim.
        val cursor = formatCandidateCursor.coerceAtMost(formatCandidates.lastIndex)
        val chosen = formatCandidates[cursor]
        val formatIndex = chosen.formatIndex
        val frameIndex = chosen.frameIndex
        activeFormatSubtype = chosen.formatSubtype
        activeFrameWidth = chosen.width
        activeFrameHeight = chosen.height
        listener?.onLog("Active format candidate: #$cursor ${describeCandidate(chosen)} (fmt=$formatIndex frm=$frameIndex)")

        isStreaming = true
        streamThread = Thread({
            try {
                negotiateStream(conn, vsIface, formatIndex, frameIndex)
                listener?.onStreamStarted()
                readAndDeliverFrames(conn, videoEndpoint)
            } catch (e: Exception) {
                listener?.onError("Stream error: ${e.message}")
                Log.e(TAG, "Stream error", e)
            }
        }, "UVC-Stream").also { it.start() }
    }

    /**
     * Réplica fiel de negotiateStream() do repo: SET_INTERFACE alt=1, GET_DEF (log),
     * SET_CUR probe de 34 bytes com fallback para 26 bytes se falhar, GET_CUR para ler o
     * probe negociado pelo dispositivo, e COMMIT com o probe negociado (ou o original, se o
     * GET_CUR falhar). [formatIndex]/[frameIndex] vêm do chamador (experimento de resolução —
     * ver [startStream]); o caminho clássico é (1, 1).
     */
    /** dwMaxPayloadTransferSize from the negotiated probe (0 = unknown) — tells [FrameAssembler]
     * how long a payload that spans several bulk reads can be. */
    @Volatile
    private var negotiatedMaxPayload = 0

    private fun negotiateStream(conn: UsbDeviceConnection, vsIface: UsbInterface, formatIndex: Int, frameIndex: Int) {
        val ifaceId = vsIface.id
        negotiatedMaxPayload = 0

        listener?.onLog("SET_INTERFACE alt=1 for IF#$ifaceId...")
        val setIfResult = conn.controlTransfer(0x01, 0x0B, 1, ifaceId, null, 0, 2000)
        listener?.onLog("SET_INTERFACE result: $setIfResult")

        val defProbe = uvcGetControl(conn, UVC_GET_DEF, VS_PROBE_CONTROL, ifaceId, 34)
        if (defProbe != null) {
            listener?.onLog("Probe default: ${formatProbe(defProbe)}")
        }

        // fps=60 (5ª rodada 2026-07-23): o probe pedia 30fps mas a câmera ENTREGA ~60fps de
        // verdade (medido: 1000 frames/16,8s) — commit descasado do que o encoder realmente
        // produz é um candidato a instabilidade. Pede 60 já no probe; o que o GET_CUR devolver
        // negociado continua sendo o que vai pro COMMIT, como sempre.
        var probeSize = 34
        var probe = buildProbeData(probeSize, formatIndex, frameIndex, fps = 60)

        listener?.onLog("SET_CUR probe (fmt=$formatIndex frm=$frameIndex 60fps)...")
        var result = uvcSetControl(conn, VS_PROBE_CONTROL, ifaceId, probe)
        if (result < 0) {
            probeSize = 26
            probe = buildProbeData(probeSize, formatIndex, frameIndex, fps = 60)
            result = uvcSetControl(conn, VS_PROBE_CONTROL, ifaceId, probe)
        }

        if (result >= 0) {
            listener?.onLog("Probe OK ($result)")
            val curProbe = uvcGetControl(conn, UVC_GET_CUR, VS_PROBE_CONTROL, ifaceId, probeSize)
            if (curProbe != null) {
                listener?.onLog("Negotiated: ${formatProbe(curProbe)}")
                probe = curProbe
                if (curProbe.size >= 26) {
                    negotiatedMaxPayload = (curProbe[22].toInt() and 0xFF) or ((curProbe[23].toInt() and 0xFF) shl 8) or
                        ((curProbe[24].toInt() and 0xFF) shl 16) or ((curProbe[25].toInt() and 0xFF) shl 24)
                }
            }

            result = uvcSetControl(conn, VS_COMMIT_CONTROL, ifaceId, probe)
            listener?.onLog("Commit: $result")
        } else {
            listener?.onLog("Probe failed ($result), reading raw...")
        }
    }

    /**
     * Lê bulk transfers com buffer fixo de 65536 bytes e delega a montagem de frames ao
     * [FrameAssembler] puro; frames completos são repassados ao listener.
     */
    private fun readAndDeliverFrames(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val buffer = ByteArray(BULK_BUFFER_SIZE)
        val assembler = FrameAssembler(readSize = BULK_BUFFER_SIZE, maxPayloadSize = negotiatedMaxPayload)

        var bulkReadCount = 0
        var frameCount = 0
        var timeoutCount = 0
        var silenceStartMs = 0L
        var stalled = false
        var deadEndpoint = false

        listener?.onLog("Starting bulk reads (buf=$BULK_BUFFER_SIZE maxPkt=${endpoint.maxPacketSize} maxPayload=$negotiatedMaxPayload)...")

        // 2026-09-29 (broken frames): how often a read does NOT start with a proper UVC payload
        // header. FrameAssembler takes byte0 in 2..12 as a header length without checking the
        // EOH bit (0x80 of byte1), so a headerless continuation whose first data byte happens to
        // be 2..12 loses those bytes from the JPEG. Counted only — behaviour is unchanged.
        var fullReads = 0
        var continuations = 0
        var headerEohClear = 0
        var headerErr = 0
        var noHeader = 0

        while (isStreaming) {
            val read = conn.bulkTransfer(endpoint, buffer, BULK_BUFFER_SIZE, BULK_TIMEOUT_MS)

            if (read <= 0) {
                // A payload never continues across an empty read or a timeout (2026-09-29).
                for (frame in assembler.endPayload()) {
                    if (PayloadFormat.detect(frame) != PayloadFormat.UNKNOWN) frameCount++
                    listener?.onFrameReceived(frame)
                }
                // Some USB stacks report a timed-out bulk read as 0 rather than -1.
                // Count both as silence so repeated empty reads cannot spin forever.
                if (read == -1 || read == 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (timeoutCount == 0) silenceStartMs = now
                    timeoutCount++
                    val silenceMs = now - silenceStartMs

                    // Endpoint MORTO: -1 voltando na hora, sem gastar o timeout — esperar não
                    // ajuda, sinaliza já. Ver Javadoc dos STALL_SILENCE_*/DEAD_ENDPOINT_*.
                    if (timeoutCount >= DEAD_ENDPOINT_MIN_READS && silenceMs < DEAD_ENDPOINT_WINDOW_MS) {
                        listener?.onLog("Endpoint stalled: $timeoutCount failures in ${silenceMs}ms ($frameCount frames in this stream) — recovering")
                        stalled = true
                        deadEndpoint = true
                        listener?.onStreamStalled()
                        break
                    }

                    // Silêncio REAL (endpoint vivo, sem dados): tolera até a janela da fase —
                    // se a câmera retomar antes, nenhum restart acontece (cursor só congela).
                    val silenceLimitMs = if (frameCount < WARMUP_FRAMES) STALL_SILENCE_STARTUP_MS else STALL_SILENCE_MID_STREAM_MS
                    if (silenceMs >= silenceLimitMs) {
                        listener?.onLog("Stall: ${silenceMs}ms without data ($frameCount frames in this stream) — recovering")
                        stalled = true
                        listener?.onStreamStalled()
                        break
                    }
                }
                continue
            }

            if (timeoutCount > 0) {
                val silenceMs = SystemClock.elapsedRealtime() - silenceStartMs
                if (silenceMs >= HICCUP_LOG_THRESHOLD_MS) {
                    // Diagnóstico da tolerância (5ª rodada): cada linha destas é um restart que
                    // NÃO aconteceu — a câmera retomou sozinha dentro da janela.
                    listener?.onLog("Recovered from ${silenceMs}ms pause without restart ($frameCount frames)")
                }
            }
            timeoutCount = 0
            bulkReadCount++

            if (bulkReadCount <= 10) {
                val hex = buffer.take(read.coerceAtMost(32)).joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Bulk #$bulkReadCount: $read bytes: $hex")
            }

            if (read == BULK_BUFFER_SIZE) fullReads++
            val hLen = buffer[0].toInt() and 0xFF
            if (assembler.inPayload) {
                continuations++
            } else if (read >= 2 && hLen in 2..12 && hLen <= read) {
                val info = buffer[1].toInt() and 0xFF
                if (info and 0x80 == 0) headerEohClear++
                if (info and 0x40 != 0) headerErr++
            } else {
                noHeader++
            }

            for (frame in assembler.offerPayload(buffer, read)) {
                // The Eye can send short all-zero UVC filler packets with EOF before it
                // produces any image. They must not satisfy warmup or suppress format fallback.
                if (PayloadFormat.detect(frame) != PayloadFormat.UNKNOWN) frameCount++
                listener?.onFrameReceived(frame)
            }

            if (bulkReadCount % 1000 == 0) {
                listener?.onLog("Stats: $bulkReadCount reads, $frameCount frames, full=$fullReads " +
                    "continuation=$continuations noHeader=$noHeader headerNoEOH=$headerEohClear headerERR=$headerErr")
            }
        }

        // Fallback entre candidatos de formato (mesma semântica do experimento anterior — ver
        // activeSilenceStrikes): só SILÊNCIO genuíno com 0 frames conta contra o candidato ativo
        // (negociação aceita mas a câmera nunca produziu). Endpoint morto é falha de LINK —
        // acontece igual em qualquer formato — e NÃO conta. Qualquer stream com >= 1 frame zera
        // os strikes do candidato ativo; 2 strikes avançam o cursor pro próximo candidato. Só faz
        // sentido enquanto não estamos no último candidato (o nativo é rede de segurança final).
        if (formatCandidateCursor < formatCandidates.lastIndex) {
            if (frameCount > 0) {
                activeSilenceStrikes = 0
            } else if (stalled && !deadEndpoint) {
                activeSilenceStrikes++
                if (activeSilenceStrikes >= 2) {
                    advanceFormatCandidate("candidato ativo mudo 2x seguidas")
                } else {
                    listener?.onLog("Active format candidate silent (strike $activeSilenceStrikes/2) — retrying")
                }
            }
        }

        listener?.onLog("Stream ended: $frameCount frames from $bulkReadCount bulk reads")
    }

    private fun buildProbeData(size: Int, formatIndex: Int, frameIndex: Int, fps: Int): ByteArray {
        val data = ByteArray(size)
        data[0] = 0x01; data[1] = 0x00 // bmHint
        data[2] = formatIndex.toByte()
        data[3] = frameIndex.toByte()
        val interval = 10_000_000 / fps
        data[4] = (interval and 0xFF).toByte()
        data[5] = ((interval shr 8) and 0xFF).toByte()
        data[6] = ((interval shr 16) and 0xFF).toByte()
        data[7] = ((interval shr 24) and 0xFF).toByte()
        return data
    }

    private fun formatProbe(data: ByteArray): String {
        if (data.size < 26) return "curto(${data.size}b)"
        val fmt = data[2].toInt() and 0xFF
        val frm = data[3].toInt() and 0xFF
        val interval = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) or
            ((data[6].toInt() and 0xFF) shl 16) or ((data[7].toInt() and 0xFF) shl 24)
        val fps = if (interval > 0) 10_000_000.0 / interval else 0.0
        val maxFrame = (data[18].toInt() and 0xFF) or ((data[19].toInt() and 0xFF) shl 8) or
            ((data[20].toInt() and 0xFF) shl 16) or ((data[21].toInt() and 0xFF) shl 24)
        val maxPayload = (data[22].toInt() and 0xFF) or ((data[23].toInt() and 0xFF) shl 8) or
            ((data[24].toInt() and 0xFF) shl 16) or ((data[25].toInt() and 0xFF) shl 24)
        val compQuality = (data[12].toInt() and 0xFF) or ((data[13].toInt() and 0xFF) shl 8)
        return "fmt=$fmt frm=$frm ${"%.1f".format(fps)}fps maxFrame=$maxFrame maxPayload=$maxPayload compQuality=$compQuality"
    }

    /** Re-applies [cameraControls] to the open camera (settings changed while streaming). */
    fun applyCameraControls() {
        val conn = streamingConnection ?: return
        val vc = videoControlInterface ?: return
        val raw = rawDescriptorsCache ?: return
        applyCameraControls(conn, vc, raw)
    }

    @Synchronized
    private fun applyCameraControls(conn: UsbDeviceConnection, vcIface: UsbInterface, raw: ByteArray) {
        val want = cameraControls
        val had = appliedControls
        if (want == had) return
        val entities = UvcDiagnostics.entities(raw)
        val ct = entities.firstOrNull { it.kind == UvcDiagnostics.Kind.CAMERA_TERMINAL }
        val pu = entities.firstOrNull { it.kind == UvcDiagnostics.Kind.PROCESSING_UNIT }
        fun supports(e: UvcDiagnostics.Entity?, bit: Int) = e != null && e.controls and (1L shl bit) != 0L
        fun set(e: UvcDiagnostics.Entity, selector: Int, data: ByteArray, what: String) {
            val r = conn.controlTransfer(USB_RT_CLASS_IFACE_SET, UVC_SET_CUR, selector shl 8, (e.id shl 8) or vcIface.id, data, data.size, 500)
            listener?.onLog("Camera control $what -> ${if (r == data.size) "OK" else "failed ($r)"}")
        }
        fun get(e: UvcDiagnostics.Entity, request: Int, selector: Int, size: Int): ByteArray? {
            val data = ByteArray(size)
            val r = conn.controlTransfer(USB_RT_CLASS_IFACE_GET, request, selector shl 8, (e.id shl 8) or vcIface.id, data, size, 500)
            return if (r == size) data else null
        }

        // Anti-flicker: only touched once the user picked a value (the camera default is kept otherwise).
        if (want.powerLineFrequency >= 0 && want.powerLineFrequency != had?.powerLineFrequency) {
            if (supports(pu, 10)) set(pu!!, 0x05, byteArrayOf(want.powerLineFrequency.toByte()), "PowerLineFrequency=${want.powerLineFrequency}")
            else listener?.onLog("Camera control PowerLineFrequency not supported")
        }
        // Exposure: manual time, or back to the camera's default AE mode when switched to auto.
        // On a new stream with "auto", the camera may still hold a manual mode set earlier (UVC
        // controls survive re-opening), so compare with its default instead of assuming.
        val staleManual = had == null && want.manualExposure100us == 0 && supports(ct, 1) &&
            get(ct!!, 0x81, 0x02, 1)?.let { cur -> get(ct, 0x87, 0x02, 1)?.let { def -> cur[0] != def[0] } } == true
        if (staleManual || want.manualExposure100us != (had?.manualExposure100us ?: 0) || (had == null && want.manualExposure100us > 0)) {
            if (!supports(ct, 1)) {
                listener?.onLog("Camera control AutoExposureMode not supported")
            } else if (want.manualExposure100us > 0) {
                set(ct!!, 0x02, byteArrayOf(0x01), "AutoExposureMode=manual")
                if (supports(ct, 3)) {
                    val v = want.manualExposure100us
                    set(ct, 0x04, byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()), "ExposureTimeAbsolute=$v")
                }
            } else {
                val def = get(ct!!, 0x87, 0x02, 1)
                if (def != null) set(ct, 0x02, def, "AutoExposureMode=default(${def[0].toInt() and 0xFF})")
            }
        }
        appliedControls = want
    }

    /** Logs GET_CUR/MIN/MAX/DEF of every exposure/gain/brightness-type control the camera
     * declares (2026-09-29, video quality). Read-only; a failed request is logged as -. */
    private fun logControlValues(conn: UsbDeviceConnection, vcIface: UsbInterface, raw: ByteArray) {
        for (entity in UvcDiagnostics.entities(raw)) {
            for (control in UvcDiagnostics.supportedControls(entity)) {
                val values = listOf("cur" to 0x81, "min" to 0x82, "max" to 0x83, "def" to 0x87).joinToString(" ") { (label, request) ->
                    val data = ByteArray(control.size)
                    val r = conn.controlTransfer(
                        USB_RT_CLASS_IFACE_GET, request, control.selector shl 8,
                        (entity.id shl 8) or vcIface.id, data, data.size, 500,
                    )
                    "$label=" + if (r == control.size) UvcDiagnostics.formatValue(data) else "-"
                }
                listener?.onLog("UVC ${entity.kind} id=${entity.id} ${control.name}: $values")
            }
        }
    }

    private fun uvcSetControl(conn: UsbDeviceConnection, selector: Int, ifaceId: Int, data: ByteArray): Int {
        return conn.controlTransfer(USB_RT_CLASS_IFACE_SET, UVC_SET_CUR, selector shl 8, ifaceId, data, data.size, 2000)
    }

    private fun uvcGetControl(conn: UsbDeviceConnection, request: Int, selector: Int, ifaceId: Int, size: Int): ByteArray? {
        val data = ByteArray(size)
        val result = conn.controlTransfer(USB_RT_CLASS_IFACE_GET, request, selector shl 8, ifaceId, data, data.size, 2000)
        return if (result >= 0) data else null
    }

    fun stopStream() {
        isStreaming = false
        streamThread?.interrupt()
        streamThread?.join(2000)
        streamThread = null

        // Higiene UVC (2026-07-23): SET_INTERFACE alt=0 é o "pare de streamar" formal do
        // protocolo — sem ele, a câmera ficava com o streaming armado entre um stall e a
        // re-negociação, e o stream seguinte frequentemente nascia morto (flapping de 19-69
        // frames observado em hardware). Best-effort: numa desconexão física o controlTransfer
        // só falha (-1) e seguimos pro release.
        val conn = streamingConnection
        val vsIface = streamingInterface
        if (conn != null && vsIface != null) {
            try {
                conn.controlTransfer(0x01, 0x0B, 0, vsIface.id, null, 0, 1000)
            } catch (e: Exception) {
                Log.w(TAG, "SET_INTERFACE alt=0 falhou no stop (ignorado): ${e.message}")
            }
        }

        streamingInterface?.let { streamingConnection?.releaseInterface(it) }
        videoControlInterface?.let { streamingConnection?.releaseInterface(it) }
        streamingConnection?.close()
        streamingConnection = null
        streamingInterface = null
        videoControlInterface = null

        listener?.onStreamStopped()
    }
}
