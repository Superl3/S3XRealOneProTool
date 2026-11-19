package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.BTN_LEFT
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.BTN_MIDDLE
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.BTN_RIGHT
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.EV_KEY
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.EV_REL
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.EV_SYN
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.REL_HWHEEL
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.REL_WHEEL
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.REL_X
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.REL_Y
import com.raphael.handmouse.remote.SystemMouseEncoder.Companion.SYN_REPORT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SystemMouseEncoderTest {

    private val syn = intArrayOf(EV_SYN, SYN_REPORT, 0)

    private fun ev(type: Int, code: Int, value: Int) = intArrayOf(type, code, value)

    private fun events(vararg parts: IntArray): IntArray = parts.fold(IntArray(0)) { acc, p -> acc + p }

    /**
     * The shell's MouseEventPolicy (spec E), rewritten here on purpose so the test does not
     * depend on the shell-side class: size a non-zero multiple of 3, at most 64 events, only the
     * virtual mouse's events, |REL| <= 4096, key values 0/1. Also checks the encoder's own
     * promise that the array ends with SYN_REPORT.
     */
    private fun assertPolicy(events: IntArray) {
        assertTrue("size ${events.size} is not a non-zero multiple of 3", events.isNotEmpty() && events.size % 3 == 0)
        assertTrue("${events.size / 3} events > 64", events.size / 3 <= 64)
        for (i in events.indices step 3) {
            val type = events[i]
            val code = events[i + 1]
            val value = events[i + 2]
            when (type) {
                EV_SYN -> assertTrue("bad SYN at $i", code == SYN_REPORT && value == 0)
                EV_KEY -> {
                    assertTrue("bad key code $code", code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE)
                    assertTrue("bad key value $value", value == 0 || value == 1)
                }
                EV_REL -> {
                    assertTrue("bad rel code $code", code == REL_X || code == REL_Y || code == REL_WHEEL || code == REL_HWHEEL)
                    assertTrue("bad rel value $value", value != 0 && abs(value) <= 4096)
                }
                else -> throw AssertionError("bad event type $type at $i")
            }
        }
        assertArrayEquals("must end with SYN_REPORT", syn, events.copyOfRange(events.size - 3, events.size))
    }

    /** Sum of the values of every `EV_REL` [code] event in [events]. */
    private fun relSum(events: IntArray, code: Int): Long {
        var sum = 0L
        for (i in events.indices step 3) {
            if (events[i] == EV_REL && events[i + 1] == code) sum += events[i + 2]
        }
        return sum
    }

    @Test
    fun move_accumulatesSubPixelMotionAndKeepsTheRemainder() {
        val e = SystemMouseEncoder()
        assertNull(e.move(0.4f, 0f))
        assertNull(e.move(0.4f, 0f))
        assertArrayEquals(events(ev(EV_REL, REL_X, 1), syn), e.move(0.4f, 0f))
        // 0.2 is kept: 0.2 + 0.3 is still below a pixel, + 0.5 reaches it.
        assertNull(e.move(0.3f, 0f))
        assertArrayEquals(events(ev(EV_REL, REL_X, 1), syn), e.move(0.5f, 0f))
    }

    @Test
    fun move_handlesNegativeMotionSymmetrically() {
        val e = SystemMouseEncoder()
        assertArrayEquals(events(ev(EV_REL, REL_X, -2), syn), e.move(-2.5f, -0.6f))
        // X: -0.5 kept + -0.5 = -1; Y: -0.6 kept + -0.6 = -1.2 → -1.
        assertArrayEquals(
            events(ev(EV_REL, REL_X, -1), ev(EV_REL, REL_Y, -1), syn),
            e.move(-0.5f, -0.6f),
        )
    }

    @Test
    fun move_leavesOutAnAxisWithoutAWholePixel() {
        val e = SystemMouseEncoder()
        assertArrayEquals(events(ev(EV_REL, REL_Y, 3), syn), e.move(0f, 3f))
        assertArrayEquals(events(ev(EV_REL, REL_X, 7), syn), e.move(7.2f, 0.5f))
        assertNull(e.move(0f, 0f))
    }

    @Test
    fun move_splitsStepsBeyondTheRelLimitIntoSeveralReports() {
        val out = SystemMouseEncoder().move(10_000f, -5_000f)
        assertArrayEquals(
            events(
                ev(EV_REL, REL_X, 4096), ev(EV_REL, REL_Y, -4096), syn,
                ev(EV_REL, REL_X, 4096), ev(EV_REL, REL_Y, -904), syn,
                ev(EV_REL, REL_X, 1808), syn,
            ),
            out,
        )
        assertPolicy(out!!)
        assertEquals(10_000L, relSum(out, REL_X))
        assertEquals(-5_000L, relSum(out, REL_Y))
    }

    @Test
    fun move_clampsAtSixtyFourEventsAndDropsTheExcess() {
        val e = SystemMouseEncoder()
        val out = e.move(1e7f, -1e7f)!!
        assertPolicy(out)
        // 21 reports of (X, Y, SYN) = 63 events; a 22nd would exceed 64.
        assertEquals(63, out.size / 3)
        assertEquals(21L * 4096, relSum(out, REL_X))
        assertEquals(-21L * 4096, relSum(out, REL_Y))
        // The excess is not carried into the next call.
        assertNull(e.move(0.5f, 0f))

        val xOnly = SystemMouseEncoder().move(-1e9f, 0f)!!
        assertPolicy(xOnly)
        assertEquals(64, xOnly.size / 3) // 32 reports of (X, SYN)
        assertEquals(-32L * 4096, relSum(xOnly, REL_X))
    }

    @Test
    fun move_ignoresNonFiniteInputWithoutPoisoningTheRemainder() {
        val e = SystemMouseEncoder()
        assertNull(e.move(Float.NaN, 0f))
        assertNull(e.move(0f, Float.POSITIVE_INFINITY))
        assertArrayEquals(events(ev(EV_REL, REL_X, 1), ev(EV_REL, REL_Y, -1), syn), e.move(1f, -1f))
    }

    @Test
    fun reset_dropsTheKeptRemainder() {
        val e = SystemMouseEncoder()
        assertNull(e.move(0.9f, -0.9f))
        e.reset()
        assertNull(e.move(0.5f, -0.5f)) // would be 1.4 / -1.4 without the reset
    }

    @Test
    fun click_isDownSynUpSyn() {
        val e = SystemMouseEncoder()
        assertArrayEquals(
            events(ev(EV_KEY, BTN_LEFT, 1), syn, ev(EV_KEY, BTN_LEFT, 0), syn),
            e.click(BTN_LEFT),
        )
        assertArrayEquals(
            events(ev(EV_KEY, BTN_RIGHT, 1), syn, ev(EV_KEY, BTN_RIGHT, 0), syn),
            e.click(BTN_RIGHT),
        )
    }

    @Test
    fun button_pressAndReleaseEachEndWithSyn() {
        val e = SystemMouseEncoder()
        assertArrayEquals(events(ev(EV_KEY, BTN_MIDDLE, 1), syn), e.button(BTN_MIDDLE, true))
        assertArrayEquals(events(ev(EV_KEY, BTN_LEFT, 0), syn), e.button(BTN_LEFT, false))
    }

    @Test
    fun button_rejectsCodesTheVirtualMouseDoesNotHave() {
        val e = SystemMouseEncoder()
        assertThrows(IllegalArgumentException::class.java) { e.button(275, true) }
        assertThrows(IllegalArgumentException::class.java) { e.click(0) }
    }

    @Test
    fun wheel_keepsTheSignAndIsNullForZero() {
        val e = SystemMouseEncoder()
        assertNull(e.wheel(0))
        assertNull(e.hwheel(0))
        assertArrayEquals(events(ev(EV_REL, REL_WHEEL, 3), syn), e.wheel(3))
        assertArrayEquals(events(ev(EV_REL, REL_WHEEL, -5), syn), e.wheel(-5))
        assertArrayEquals(events(ev(EV_REL, REL_HWHEEL, -1), syn), e.hwheel(-1))
        assertArrayEquals(events(ev(EV_REL, REL_HWHEEL, 2), syn), e.hwheel(2))
    }

    @Test
    fun wheel_splitsLargeValuesAndClampsAtSixtyFourEvents() {
        val e = SystemMouseEncoder()
        assertArrayEquals(
            events(ev(EV_REL, REL_WHEEL, -4096), syn, ev(EV_REL, REL_WHEEL, -4096), syn, ev(EV_REL, REL_WHEEL, -1808), syn),
            e.wheel(-10_000),
        )
        val huge = e.hwheel(Int.MAX_VALUE)!!
        assertPolicy(huge)
        assertEquals(64, huge.size / 3)
        assertEquals(32L * 4096, relSum(huge, REL_HWHEEL))
        val hugeDown = e.wheel(Int.MIN_VALUE)!!
        assertPolicy(hugeDown)
        assertEquals(-32L * 4096, relSum(hugeDown, REL_WHEEL))
    }

    @Test
    fun everyOutputObeysTheShellPolicy() {
        val e = SystemMouseEncoder()
        val rnd = Random(17)
        val buttons = intArrayOf(BTN_LEFT, BTN_RIGHT, BTN_MIDDLE)
        repeat(5_000) {
            val scale = when (rnd.nextInt(4)) {
                0 -> 1f
                1 -> 50f
                2 -> 5_000f
                else -> 1e6f
            }
            e.move((rnd.nextFloat() * 2f - 1f) * scale, (rnd.nextFloat() * 2f - 1f) * scale)?.let { assertPolicy(it) }
            e.wheel(rnd.nextInt(-20_000, 20_000))?.let { assertPolicy(it) }
            e.hwheel(rnd.nextInt(-20_000, 20_000))?.let { assertPolicy(it) }
            val b = buttons[rnd.nextInt(buttons.size)]
            assertPolicy(e.button(b, rnd.nextBoolean()))
            assertPolicy(e.click(b))
        }
        for (extreme in floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, 4096.9f, -4097f)) {
            e.move(extreme, -extreme)?.let { assertPolicy(it) }
        }
        assertNotNull(e.wheel(Int.MAX_VALUE)?.also { assertPolicy(it) })
    }
}
