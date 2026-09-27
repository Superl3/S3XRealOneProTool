package com.raphael.handmouse.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class HevcAccessUnitAssemblerTest {

    /** NAL with a 4-byte start code: header (type, layer 0, tid 1) + payload. */
    private fun nal(type: Int, firstSlice: Boolean = true, payload: Int = 20, fourByte: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        if (fourByte) out.write(0)
        out.write(0); out.write(0); out.write(1)
        out.write((type shl 1) and 0x7E)
        out.write(1)
        if (type in 0..31) out.write(if (firstSlice) 0x80 else 0x00)
        repeat(payload) { out.write(0x40 + (it % 32)) } // never forms a start code
        return out.toByteArray()
    }

    private fun stream(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val vps = nal(32)
    private val sps = nal(33)
    private val pps = nal(34)
    private val idr = nal(19, payload = 300)
    private val p1 = nal(1, payload = 80)
    private val p2slice0 = nal(1, firstSlice = true, payload = 50)
    private val p2slice1 = nal(1, firstSlice = false, payload = 50, fourByte = false)
    private val p3 = nal(1, payload = 60)

    private fun feedAll(data: ByteArray, chunkSizes: Sequence<Int>): List<HevcAccessUnitAssembler.AccessUnit> {
        val asm = HevcAccessUnitAssembler()
        val out = ArrayList<HevcAccessUnitAssembler.AccessUnit>()
        var i = 0
        val it = chunkSizes.iterator()
        var t = 0L
        while (i < data.size) {
            val n = minOf(it.next(), data.size - i)
            out += asm.feed(data.copyOfRange(i, i + n), t++)
            i += n
        }
        out += asm.flush()
        return out
    }

    @Test
    fun groupsParameterSetsWithKeyframeAndSplitsPictures() {
        val data = stream(vps, sps, pps, idr, p1, p2slice0, p2slice1, p3)
        val aus = feedAll(data, generateSequence { 64 })
        assertEquals(4, aus.size)
        assertEquals(4, aus[0].nals.size) // VPS SPS PPS IDR
        assertTrue(aus[0].isIrap)
        assertFalse(aus[1].isIrap)
        assertEquals(2, aus[2].nals.size) // two slices of the same picture
        assertArrayEquals(p3, aus[3].nals.single())
    }

    @Test
    fun resultIsIndependentOfChunkBoundaries() {
        val data = stream(vps, sps, pps, idr, p1, p2slice0, p2slice1, p3, vps, sps, pps, idr, p1)
        val reference = feedAll(data, generateSequence { data.size })
        val rnd = Random(42)
        repeat(50) {
            val aus = feedAll(data, generateSequence { 1 + rnd.nextInt(40) })
            assertEquals(reference.size, aus.size)
            for (k in aus.indices) {
                assertEquals(reference[k].isIrap, aus[k].isIrap)
                assertArrayEquals(
                    reference[k].nals.reduce { a, b -> a + b },
                    aus[k].nals.reduce { a, b -> a + b },
                )
            }
        }
        assertEquals(6, reference.size)
    }

    @Test
    fun garbageBeforeFirstStartCodeIsDiscarded() {
        val asm = HevcAccessUnitAssembler()
        val garbage = ByteArray(100) { 0x55 }
        val aus = asm.feed(garbage + stream(vps, sps, pps, idr, p1), 0) + asm.flush()
        assertEquals(2, aus.size)
        assertTrue(aus[0].isIrap)
        assertTrue(asm.discardedBytes >= 100)
    }

    @Test
    fun timestampIsArrivalOfFirstNal() {
        val asm = HevcAccessUnitAssembler()
        val out = ArrayList<HevcAccessUnitAssembler.AccessUnit>()
        out += asm.feed(stream(vps, sps, pps, idr), 1000)
        out += asm.feed(p1, 2000)
        out += asm.feed(p3, 3000)
        out += asm.flush()
        assertEquals(listOf(1000L, 2000L, 3000L), out.map { it.timestampUs })
    }

    @Test
    fun resetDropsPartialData() {
        val asm = HevcAccessUnitAssembler()
        asm.feed(stream(vps, sps, pps, idr).copyOfRange(0, 50), 0)
        asm.reset()
        val aus = asm.feed(stream(vps, sps, pps, idr, p1), 1) + asm.flush()
        assertEquals(2, aus.size)
        assertEquals(4, aus[0].nals.size)
    }
}
