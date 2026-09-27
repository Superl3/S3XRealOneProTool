package com.raphael.handmouse.glasses

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Transporte do canal de controle dos óculos.
 *
 * O canal de controle são os endpoints 0x01 (OUT) e 0x81 (IN). No descritor real do One Pro
 * (verificado em hardware via `dumpsys usb`, captura black-box) esses endpoints são do tipo
 * INTERRUPT (type=3) numa interface de classe HID (class=3), com max_packet=1024. A lib nativa
 * faz transferências bulk-style neles via USBDEVFS_BULK (o kernel roteia bulk em endpoint
 * interrupt), e `UsbDeviceConnection.bulkTransfer` faz o mesmo — daí a paridade no fio. Por isso
 * a seleção NÃO filtra por tipo de endpoint; casa pelo endereço exato.
 *
 * ATENÇÃO: o endpoint 0x89 é o stream de vídeo MJPEG. Reivindicar a interface dele derrubaria a
 * câmera — por isso a seleção é por endereço de endpoint exato, não "o primeiro que achar".
 */
class GlassesTransport(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
) {
    companion object {
        private const val TAG = "GlassesTransport"
        const val EP_CONTROL_OUT = 0x01
        const val EP_CONTROL_IN = 0x81
        const val RESPONSE_BUFFER_SIZE = 1024

        fun isControlOut(address: Int) = address == EP_CONTROL_OUT
        fun isControlIn(address: Int) = address == EP_CONTROL_IN

        /**
         * Acha a interface cujos endpoints são exatamente 0x01 (OUT) e 0x81 (IN).
         *
         * NÃO filtra por tipo de endpoint de propósito: no One Pro esses endpoints são INTERRUPT
         * numa interface HID (ver KDoc da classe), e um filtro `type == BULK` os descartaria — foi
         * exatamente o bug que a validação diferencial em hardware pegou. O endereço já é único
         * (o vídeo é 0x89; as interfaces CDC-data usam 0x02/0x82/0x03/0x04), então casar só pelo
         * endereço é seguro e não confunde com o vídeo.
         */
        fun find(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var out: UsbEndpoint? = null
                var inp: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (isControlOut(ep.address)) out = ep
                    if (isControlIn(ep.address)) inp = ep
                }
                if (out != null && inp != null) return Triple(iface, out, inp)
            }
            return null
        }
    }

    /** Envia uma mensagem e devolve a resposta parseada. Lança IllegalArgumentException em envio incompleto ou resposta vazia. */
    fun request(message: ByteArray, timeoutMs: Int = 1000): GlassesFrame.Parsed {
        val sent = conn.bulkTransfer(epOut, message, message.size, timeoutMs)
        require(sent == message.size) { "envio incompleto: $sent de ${message.size}" }
        val buf = ByteArray(RESPONSE_BUFFER_SIZE)
        val got = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        require(got > 0) { "sem resposta (bulkTransfer devolveu $got)" }
        return GlassesFrame.parse(buf, got)
    }

    fun claim(): Boolean = conn.claimInterface(iface, true).also {
        if (!it) Log.w(TAG, "claimInterface falhou na interface ${iface.id}")
    }

    fun release() {
        runCatching { conn.releaseInterface(iface) }
    }
}
