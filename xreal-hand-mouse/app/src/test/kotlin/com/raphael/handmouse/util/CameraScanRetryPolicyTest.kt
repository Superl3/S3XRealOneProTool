package com.raphael.handmouse.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD do teto de tentativas do scan de câmera (fix de revisão, achado Important I7): 30
 * tentativas permitidas (`shouldRetry` true pras tentativas 1..29 que falharam, false a partir
 * da 30ª falha em diante).
 */
class CameraScanRetryPolicyTest {

    @Test
    fun `primeira falha ainda pode tentar de novo`() {
        assertEquals(true, CameraScanRetryPolicy.shouldRetry(1))
    }

    @Test
    fun `tentativa logo abaixo do teto ainda pode tentar de novo`() {
        assertEquals(true, CameraScanRetryPolicy.shouldRetry(CameraScanRetryPolicy.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `tentativa exatamente no teto ja desiste`() {
        assertEquals(false, CameraScanRetryPolicy.shouldRetry(CameraScanRetryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `tentativa alem do teto continua desistindo`() {
        assertEquals(false, CameraScanRetryPolicy.shouldRetry(CameraScanRetryPolicy.MAX_ATTEMPTS + 5))
    }
}
