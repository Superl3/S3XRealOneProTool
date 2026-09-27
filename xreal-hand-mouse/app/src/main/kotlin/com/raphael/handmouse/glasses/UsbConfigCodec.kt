package com.raphael.handmouse.glasses

/** Estado das interfaces USB compostas dos óculos. */
data class UsbConfigState(
    val ncm: Int,
    val ecm: Int,
    val hidCtrl: Int,
    val uvc0: Int,
    val uvc1: Int,
)

/**
 * Codec da config USB.
 *
 * Os campos vêm empacotados em **2 bits cada**, não um byte por campo. Evidência (captura
 * 2026-07-23, correlacionada na mesma execução com o log do app):
 *   payload = 00 55 9a 00 00
 *   0x55 = 01|01|01|01 -> ncm=1 ecm=1 hidCtrl=1 uvc0=1
 *   0x9a = ......|10   -> uvc1=2
 *
 * LIMITE CONHECIDO: temos UMA amostra de configuração (o app sempre monta a mesma), então a
 * ordem exata dos campos de 2 bits casa com essa amostra mas não está provada contra
 * configurações alternativas. Pior ainda, essa amostra nem sequer distingue ordem LSB-first
 * de MSB-first, porque os dois bytes capturados são simétricos em blocos de 2 bits: 0x55 =
 * 01|01|01|01 (idêntico em qualquer ordem) e, em 0x9a, os 2 bits baixos (10 = 2) coincidem com
 * os 2 bits altos (10 = 2). Por isso LSB-first foi adotado por se encaixar, mas esta amostra
 * não prova essa ordem contra MSB-first; uma amostra futura menos simétrica (ex.: uvc1 ≠ 2)
 * é necessária para desambiguar. Para generalizar, capturar com configs variadas e diffar.
 * Para o objetivo atual (reproduzir esta configuração) isso basta.
 */
object UsbConfigCodec {

    /**
     * Payload que o SetUsbConfigAll envia para ligar a uvc0 — constante observada no tráfego
     * USB (captura black-box), não extraída da .so.
     */
    val SET_UVC0_PAYLOAD: ByteArray = byteArrayOf(0x45, 0x10, 0x01, 0x00)

    private fun field(b: Int, index: Int): Int = (b shr (index * 2)) and 0b11

    fun decode(payload: ByteArray): UsbConfigState {
        val b1 = if (payload.size > 1) payload[1].toInt() and 0xff else 0
        val b2 = if (payload.size > 2) payload[2].toInt() and 0xff else 0
        return UsbConfigState(
            ncm = field(b1, 0),
            ecm = field(b1, 1),
            hidCtrl = field(b1, 2),
            uvc0 = field(b1, 3),
            uvc1 = field(b2, 0),
        )
    }
}
