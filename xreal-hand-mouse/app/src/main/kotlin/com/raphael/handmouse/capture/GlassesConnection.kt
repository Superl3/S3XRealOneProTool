package com.raphael.handmouse.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.raphael.handmouse.R
import com.raphael.handmouse.glasses.GlassesCommands
import com.raphael.handmouse.glasses.GlassesFrame
import com.raphael.handmouse.glasses.GlassesTransport
import com.raphael.handmouse.glasses.UsbConfigCodec
import com.raphael.handmouse.glasses.UsbConfigState
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ativação da câmera Eye/RGB da XREAL One Pro ("Gina") em **Kotlin puro**, sem nenhuma `.so`
 * proprietária. O protocolo de controle USB (frame, CRC, comandos, transporte BULK/interrupt no
 * canal HID) foi mapeado por captura black-box do próprio tráfego USB e reimplementado em
 * `com.raphael.handmouse.glasses.*` — validado byte-a-byte contra a lib nativa antes de removê-la
 * (ver docs/superpowers/specs/2026-07-23-glasses-usb-protocol.md).
 *
 * Sequência: WaitPilotReady (poll) → GetUsbConfigAll → (pula se uvc0==1 E vídeo já exposto, senão
 * SetUsbConfigAll → sleep 3s) → GetCameraStatus.
 *
 * Atribuição: o CONTROL-FLOW de ativação (ordem das operações, skip-if-active, forceReconfigure)
 * foi adaptado do repo do Aloim (MIT, Copyright 2026 Aloim — ver NOTICE na raiz do repo). Os
 * bytes do protocolo NÃO vêm de lá (a captura black-box falsificou a tabela HID de terceiro).
 */
class GlassesConnection(private val context: Context) {

    companion object {
        private const val TAG = "GlassesConnection"
        const val ACTION_USB_PERMISSION = "com.raphael.handmouse.USB_PERMISSION"
        const val XREAL_VID = 13080 // 0x3318
        const val XREAL_EYE_PID = 1078 // 0x0436 ("Gina Kernel")
        private const val PILOT_READY_TIMEOUT_MS = 10000
        private const val REENUMERATION_WAIT_MS = 3000L

        // Robustez: logo após uma (re-)enumeração o openDevice/transfer pode falhar
        // transitoriamente até o device assentar (visto em hardware) — 10× / 500ms cobre.
        private const val OPEN_RETRY_COUNT = 10
        private const val RETRY_SLEEP_MS = 500L
        private const val PILOT_POLL_SLEEP_MS = 200L

        // Classe/subclasse da interface UVC VideoStreaming (a câmera Eye). Mesmos valores que
        // UvcCameraHelper usa (USB_CLASS_VIDEO=14, UVC_SC_VIDEOSTREAMING=2).
        private const val USB_CLASS_VIDEO = 14
        private const val UVC_SC_VIDEOSTREAMING = 2

        /**
         * Decisão pura: reconfigurar a USB (mandar SetUsbConfigAll + re-enumerar)?
         *
         * Só pula se: sem force, uvc0 já == 1, E a interface de vídeo JÁ está exposta. O último termo
         * e o que a validacao em hardware (2026-07-23) mostrou ser obrigatorio: o device as vezes
         * atacha com uvc0=1 no registro MAS sem a interface de video (class=14/subclass=2) exposta —
         * e so o SetUsbConfigAll (com re-enumeracao) a expoe. Pular baseado so em uvc0==1 deixava o
         * pipeline preso pra sempre em "procurando UVC". `videoInterfacePresent` amarra a decisao a
         * condicao OBSERVAVEL (a interface existe), nao ao flag uvc0 (proxy furado, ainda mais com o
         * decode de amostra unica do UsbConfigCodec).
         */
        fun shouldReconfigure(
            cfg: UsbConfigState?,
            forceReconfigure: Boolean,
            videoInterfacePresent: Boolean,
        ): Boolean =
            forceReconfigure || cfg == null || cfg.uvc0 != 1 || !videoInterfacePresent
    }

    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onLog(message: String)
        fun onCameraEnabled()
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    // Escritos na main thread/receivers, lidos pelo activationExecutor — @Volatile garante
    // visibilidade cross-thread.
    @Volatile private var device: UsbDevice? = null
    @Volatile private var connectedDeviceName: String? = null
    @Volatile private var pendingPermissionDeviceName: String? = null
    var listener: Listener? = null
    @Volatile var isConnected = false
        private set

    private val activationExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "GlassesActivation") }
    private val activationGen = AtomicInteger(0)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val callbackDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val pendingName = pendingPermissionDeviceName
                UsbPermissionRequests.finish(callbackDevice?.deviceName ?: pendingName)
                if (pendingName != null && callbackDevice != null && callbackDevice.deviceName != pendingName) return
                pendingPermissionDeviceName = null
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    listener?.onLog("USB permission granted")
                    device?.takeIf { it.deviceName == (callbackDevice?.deviceName ?: pendingName) && usbManager.hasPermission(it) }
                        ?.let { onDeviceReady(it) }
                } else {
                    listener?.onError("USB permission denied")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }

        // Limpeza one-time: a .so removida copiava firmware (nr_ota_default) pro dataDir do app.
        // Apaga resíduos proprietários deixados por instalações anteriores.
        runCatching { java.io.File(context.dataDir, "nr_ota_default").deleteRecursively() }

        scanForGlasses()
    }

    fun stop() {
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        disconnect()
    }

    /** Desliga o [activationExecutor] de vez. SÓ no fim da vida do dono
     * (`EyeCaptureService.onDestroy`) — [stop] roda a cada ciclo de pipeline e o executor
     * precisa sobreviver entre ciclos. Tarefas já em execução terminam normalmente. */
    fun shutdown() {
        activationExecutor.shutdown()
    }

    fun scanForGlasses() {
        listener?.onLog("Looking for XREAL glasses...")
        for (dev in usbManager.deviceList.values) {
            if (dev.vendorId == XREAL_VID) {
                listener?.onLog("Found: VID=${dev.vendorId} PID=${dev.productId}")
                device = dev
                if (usbManager.hasPermission(dev)) {
                    UsbPermissionRequests.finish(dev.deviceName)
                    pendingPermissionDeviceName = null
                    onDeviceReady(dev)
                } else {
                    if (!UsbPermissionRequests.begin(dev.deviceName)) {
                        listener?.onError(context.getString(R.string.conn_usb_permission_busy))
                        return
                    }
                    pendingPermissionDeviceName = dev.deviceName
                    listener?.onLog("Requesting USB permission (1/2)...")
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    try {
                        usbManager.requestPermission(dev, PendingIntent.getBroadcast(context, 0, intent, flags))
                    } catch (e: Exception) {
                        UsbPermissionRequests.finish(dev.deviceName)
                        pendingPermissionDeviceName = null
                        listener?.onError("USB permission request failed: ${e.message}")
                    }
                }
                return
            }
        }
        listener?.onLog("No XREAL glasses found.")
    }

    private fun onDeviceReady(dev: UsbDevice) {
        if (isConnected && connectedDeviceName == dev.deviceName) {
            Log.d(TAG, "Ignoring duplicate ready callback for ${dev.deviceName}")
            return
        }
        Log.d(TAG, "Device ready: VID=${dev.vendorId} PID=${dev.productId} ifaces=${dev.interfaceCount}")
        connectedDeviceName = dev.deviceName
        isConnected = true
        listener?.onConnected("XREAL Gina (PID=${dev.productId})")
    }

    fun disconnect() {
        activationGen.incrementAndGet()
        UsbPermissionRequests.finish(pendingPermissionDeviceName)
        pendingPermissionDeviceName = null
        device = null
        connectedDeviceName = null
        isConnected = false
    }

    /**
     * Ativa a câmera Eye/RGB (uvc0) via protocolo Kotlin puro ([GlassesTransport]). Roda no
     * [activationExecutor] — o handshake + espera de re-enumeração (3s) bloqueiam. Dispara a 2ª
     * solicitação de permissão USB indiretamente (o SO trata o dispositivo re-enumerado como novo).
     *
     * [forceReconfigure]: aplica SetUsbConfigAll MESMO com uvc0 já reportando ativa
     * (4ª rodada 2026-07-23): a câmera às vezes trava PROFUNDO — o firmware diz uvc0=1 mas o
     * endpoint bulk nasce morto em todo stream novo — e o único reset que a revive é o ciclo
     * completo de reconfiguração/re-enumeração que o atalho normal pula. O `EyeCaptureService`
     * passa `true` depois de streams natimortos consecutivos (ver `consecutiveDeadStreams`).
     *
     * Em falha da ativação (raro pós-fix; sem permissão, sem device, transfers falhando após os
     * retries), NÃO trava em ERROR: loga e chama [Listener.onCameraEnabled] para o
     * `findAndStartCamera` (com sua própria política de retry/ERROR) assumir a recuperação.
     */
    fun enableEyeCamera(forceReconfigure: Boolean = false) {
        if (!isConnected) { listener?.onError("Not connected"); return }
        val gen = activationGen.incrementAndGet()
        try {
            activationExecutor.execute {
                try {
                    enableEyeCameraKotlin(forceReconfigure, gen)
                } catch (e: Exception) {
                    Log.e(TAG, "Ativação Kotlin falhou", e)
                    listener?.onLog("Activation failed (${e.message}) — camera scan will try")
                    if (gen == activationGen.get()) listener?.onCameraEnabled()
                }
            }
        } catch (e: RejectedExecutionException) {
            // Só possível depois de shutdown() — o serviço está morrendo; nada a ativar.
            Log.w(TAG, "Ativação #$gen descartada — executor desligado (serviço encerrando)")
        }
    }

    /** Ativação via protocolo Kotlin puro. Lança em falha → o chamador loga e delega ao scan. */
    private fun enableEyeCameraKotlin(forceReconfigure: Boolean, gen: Int) {
        Log.i(TAG, "Ativação: caminho KOTLIN (force=$forceReconfigure)")
        val dev = device ?: usbManager.deviceList.values.firstOrNull { it.vendorId == XREAL_VID }
            ?: throw IllegalStateException("nenhum device XREAL")
        if (!usbManager.hasPermission(dev)) throw IllegalStateException("sem permissão USB")
        val found = GlassesTransport.find(dev) ?: throw IllegalStateException("interface de controle não encontrada")
        val conn = openWithRetry(dev) ?: throw IllegalStateException("openDevice falhou após retries")
        val transport = GlassesTransport(conn, found.first, found.second, found.third)
        var skipTaken = false
        try {
            if (!transport.claim()) throw IllegalStateException("claimInterface falhou")

            val ready = waitPilotReady(transport)
            listener?.onLog("Pilot ready: $ready")

            // Payload CRU logado (auditoria do decode de amostra unica — achado I1 da review).
            val rawConfig = runCatching {
                runOperation(transport, GlassesCommands.getUsbConfigMessages()).payload
            }.getOrNull()
            val cfg = rawConfig?.let { runCatching { UsbConfigCodec.decode(it) }.getOrNull() }
            val rawHex = rawConfig?.joinToString(" ") { "%02x".format(it) } ?: "?"
            val videoPresent = hasVideoInterface(dev)
            listener?.onLog("Current: $cfg (raw=$rawHex, videoIface=$videoPresent)")

            if (!shouldReconfigure(cfg, forceReconfigure, videoPresent)) {
                listener?.onLog("uvc0 active and video interface present — skipping USB reconfiguration")
                skipTaken = true
            } else {
                val resp = runOperation(transport, GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD))
                val code = resp.payload.firstOrNull()?.toInt() ?: -1
                listener?.onLog("Result: $code")
                if (code != 0) throw IllegalStateException("SetUsbConfigAll code=$code")
            }
        } finally {
            transport.release(); conn.close()
        }

        // Uma ativação mais nova já entrou na fila → aborta antes do sleep/status.
        if (gen != activationGen.get()) { Log.d(TAG, "ativação #$gen superada — abortando"); return }

        if (!skipTaken) Thread.sleep(REENUMERATION_WAIT_MS)   // paridade; só após um Set real
        bestEffortCameraStatus()
        // Re-checa DEPOIS do sleep de re-enumeração (fix da revisão final): superada aqui =
        // não re-notificar — um onCameraEnabled obsoleto dispararia findAndStartCamera por
        // cima da ativação mais nova.
        if (gen != activationGen.get()) { Log.d(TAG, "ativação #$gen superada pós-sleep — abortando"); return }
        listener?.onCameraEnabled()
    }

    /** WaitPilotReady como POLL de 200ms: consulta `ro.bsp.app_prepare_done` até "true" ou
     * timeout de 10s (mesmo comportamento observado do handshake original, que bloqueava). */
    private fun waitPilotReady(transport: GlassesTransport): Boolean {
        val deadline = System.nanoTime() + PILOT_READY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val resp = runCatching { runOperation(transport, GlassesCommands.waitPilotReadyMessages()) }.getOrNull()
            val txt = resp?.payload?.toString(Charsets.US_ASCII) ?: ""
            if (txt.contains(GlassesCommands.PILOT_READY_MARKER)) return true
            Thread.sleep(PILOT_POLL_SLEEP_MS)
        }
        return false
    }

    /** A interface UVC VideoStreaming (câmera Eye) já está exposta no descritor deste device? */
    private fun hasVideoInterface(dev: android.hardware.usb.UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_VIDEO && iface.interfaceSubclass == UVC_SC_VIDEOSTREAMING) return true
        }
        return false
    }

    /** GetCameraStatus best-effort: conexão NOVA, re-lookup do device, só loga. */
    private fun bestEffortCameraStatus() {
        val dev = usbManager.deviceList.values.firstOrNull { it.vendorId == XREAL_VID } ?: return
        if (!usbManager.hasPermission(dev)) { listener?.onLog("Status: USB permission pending after re-enumeration"); return }
        val found = GlassesTransport.find(dev) ?: return
        val conn = usbManager.openDevice(dev) ?: return
        val transport = GlassesTransport(conn, found.first, found.second, found.third)
        try {
            if (!transport.claim()) return
            val resp = runOperation(transport, GlassesCommands.getCameraStatusMessages())
            listener?.onLog("Camera status: ${resp.payload.joinToString(" ") { "%02x".format(it) }}")
        } catch (e: Exception) {
            listener?.onLog("Camera status unavailable: ${e.message}")
        } finally {
            transport.release(); conn.close()
        }
    }

    /** Envia todas as mensagens da operação (preâmbulos + comando), devolve a resposta da última. */
    private fun runOperation(transport: GlassesTransport, messages: List<ByteArray>): GlassesFrame.Parsed {
        var last: GlassesFrame.Parsed? = null
        for (m in messages) last = requestWithRetry(transport, m)
        return last ?: throw IllegalStateException("operação vazia")
    }

    private fun requestWithRetry(transport: GlassesTransport, message: ByteArray): GlassesFrame.Parsed {
        var lastErr: Exception? = null
        repeat(OPEN_RETRY_COUNT) {
            try { return transport.request(message) } catch (e: Exception) { lastErr = e; Thread.sleep(RETRY_SLEEP_MS) }
        }
        throw lastErr ?: IllegalStateException("request falhou")
    }

    private fun openWithRetry(dev: android.hardware.usb.UsbDevice): UsbDeviceConnection? {
        repeat(OPEN_RETRY_COUNT) {
            usbManager.openDevice(dev)?.let { return it }
            Thread.sleep(RETRY_SLEEP_MS)
        }
        return null
    }
}
