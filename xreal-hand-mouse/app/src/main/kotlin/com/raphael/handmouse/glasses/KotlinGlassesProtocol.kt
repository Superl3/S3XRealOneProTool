package com.raphael.handmouse.glasses

/**
 * As 4 operações de ativação da câmera, em Kotlin puro — SEM a .so proprietária.
 *
 * FASE DE PARIDADE: reproduz fielmente o que a .so faz, incluindo o sleep cego de 3 s após o
 * SetUsbConfigAll. NÃO otimizar aqui — trocar o sleep por poll é a fase seguinte, e fazer isso
 * agora invalidaria o diff byte-a-byte que valida esta implementação.
 */
class KotlinGlassesProtocol(private val transport: GlassesTransport) {

    companion object {
        /** Mesmo valor que GlassesConnection usa após o SetUsbConfigAll (paridade). */
        const val REENUMERATION_WAIT_MS = 3000L
    }

    fun enableEyeCamera(log: (String) -> Unit): Boolean {
        val seq = GlassesCommands.enableCameraSequence()
        var lastConfig: UsbConfigState? = null
        seq.forEachIndexed { index, msg ->
            val cmd = msg[15].toInt() and 0xff
            val resp = runCatching { transport.request(msg) }.getOrElse { e ->
                log("Command #${index + 1} failed (0x%02x): ${e.message}".format(cmd))
                return false
            }
            when (cmd) {
                GlassesCommands.CMD_GET_PROPERTY -> {
                    val txt = resp.payload.toString(Charsets.US_ASCII)
                    log("Pilot ready: ${txt.contains(GlassesCommands.PILOT_READY_MARKER)}")
                }
                GlassesCommands.CMD_GET_USB_CONFIG -> {
                    lastConfig = UsbConfigCodec.decode(resp.payload)
                    log("Current: $lastConfig")
                }
                GlassesCommands.CMD_SET_USB_CONFIG -> {
                    val code = resp.payload.firstOrNull()?.toInt() ?: -1
                    log("Result: $code")
                    Thread.sleep(REENUMERATION_WAIT_MS)   // paridade com a .so
                }
                GlassesCommands.CMD_GET_CAMERA_STATUS ->
                    log("Camera status: ${resp.payload.joinToString(" ") { "%02x".format(it) }}")
            }
        }
        return true
    }
}
