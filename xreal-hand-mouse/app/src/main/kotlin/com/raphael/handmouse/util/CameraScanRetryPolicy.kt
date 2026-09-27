package com.raphael.handmouse.util

/**
 * Política PURA do teto de tentativas do scan de câmera UVC (fix de revisão, achado Important
 * I7) — separada de `EyeCaptureService.findAndStartCamera` pra ser testável em JVM puro, no
 * mesmo espírito de [ThermalFpsPolicy].
 *
 * Antes deste fix, `findAndStartCamera` reagendava a si mesma a cada
 * `CAMERA_SCAN_RETRY_DELAY_MS` (2s) PARA SEMPRE enquanto `UvcCameraHelper.findCamera()`
 * retornasse `null` — óculos desligados, cabo com mau contato ou o usuário nunca reconectando
 * deixavam o `Handler` do serviço reagendando indefinidamente em segundo plano, sem jamais
 * expor um erro acionável pro usuário (a UI ficava presa em "Procurando interface UVC..." pra
 * sempre).
 */
object CameraScanRetryPolicy {

    /** Nº máximo de tentativas (contando a primeira) antes de desistir — a
     * `CAMERA_SCAN_RETRY_DELAY_MS` (2s) cada, dá ~1 minuto de tentativas antes do ERROR final. */
    const val MAX_ATTEMPTS = 30

    /** [attempt] = número da tentativa que ACABOU de falhar (1-based: a primeira chamada de
     * `findAndStartCamera` que não acha a câmera é a tentativa 1). `true` = ainda vale
     * reagendar mais uma tentativa; `false` = teto atingido, hora de desistir com ERROR. */
    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_ATTEMPTS
}
