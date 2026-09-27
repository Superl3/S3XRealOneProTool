package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato do anel de buffers RGBA que alimenta o MediaPipe no caminho MJPEG (ver Javadoc de
 * [RgbaBufferRing]). O que importa aqui é o que a ingestão do MediaPipe exige e o que a corrida
 * decoder-vs-`HandTracker` exige: capacidade EXATA, buffer DIRETO, e rotação real entre frames
 * consecutivos (um buffer só voltaria a ser escrito depois de `size` frames).
 */
class RgbaBufferRingTest {

    @Test
    fun `buffer tem capacidade exata de w x h x 4 e é direto`() {
        val buffer = RgbaBufferRing().next(64, 32)

        // createImage() do MediaPipe rejeita capacity != width * 4 * height, e o JNI exige
        // endereço direto — as duas condições são o contrato desta classe.
        assertEquals(64 * 32 * 4, buffer.capacity())
        assertTrue("buffer precisa ser direto", buffer.isDirect)
    }

    @Test
    fun `frames consecutivos usam buffers distintos e o anel dá a volta`() {
        val ring = RgbaBufferRing(size = 3)

        val first = ring.next(8, 8)
        val second = ring.next(8, 8)
        val third = ring.next(8, 8)

        assertNotSame(first, second)
        assertNotSame(second, third)
        assertNotSame(first, third)
        assertSame("4ª chamada deve voltar ao 1º buffer", first, ring.next(8, 8))
    }

    @Test
    fun `buffer devolvido vem zerado mesmo depois de escrito`() {
        val ring = RgbaBufferRing(size = 2)

        ring.next(4, 4).apply { put(ByteArray(capacity())) } // consome o buffer inteiro
        ring.next(4, 4)

        val reused = ring.next(4, 4) // de volta ao primeiro, já com position no fim
        assertEquals(0, reused.position())
        assertEquals(reused.capacity(), reused.limit())
    }

    @Test
    fun `mudança de dimensão realoca o anel`() {
        val ring = RgbaBufferRing(size = 2)
        val small = ring.next(8, 8)

        val large = ring.next(16, 16)
        assertEquals(16 * 16 * 4, large.capacity())
        assertNotSame(small, large)

        // realocado do zero: volta a servir a partir do índice 0 do anel NOVO.
        assertNotSame(large, ring.next(16, 16))
        assertSame(large, ring.next(16, 16))
    }

    @Test
    fun `release solta o anel e a próxima chamada realoca`() {
        val ring = RgbaBufferRing(size = 2)
        val before = ring.next(8, 8)

        ring.release()

        assertNotSame(before, ring.next(8, 8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dimensão inválida é rejeitada`() {
        RgbaBufferRing().next(0, 32)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `anel de uma posição só é rejeitado (não protegeria contra a corrida)`() {
        RgbaBufferRing(size = 1)
    }
}
