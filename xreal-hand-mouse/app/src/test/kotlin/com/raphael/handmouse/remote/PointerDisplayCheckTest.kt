package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointerDisplayCheckTest {
    private fun runner(output: String): (List<String>) -> Pair<Int, String> = { 0 to output }

    @Test
    fun `parses the pointer display from dumpsys input`() {
        assertEquals(55, PointerDisplayCheck.parse("  Default mouse displayId: 55\n"))
        assertEquals(0, PointerDisplayCheck.parse("Default mouse displayId: 0"))
    }

    @Test
    fun `unset or missing pointer display parses as null`() {
        assertNull(PointerDisplayCheck.parse("  Default mouse displayId: <unset>"))
        assertNull(PointerDisplayCheck.parse(""))
    }

    @Test
    fun `same display passes`() {
        assertNull(PointerDisplayCheck.check(55, runner("  Default mouse displayId: 55")))
    }

    @Test
    fun `another display is refused with the wrong-display code`() {
        val result = PointerDisplayCheck.check(55, runner("  Default mouse displayId: 0"))
        assertEquals(MouseOpenResult.WRONG_DISPLAY_CODE, result?.first)
        assertEquals(MouseOpenResult.WRONG_DISPLAY, MouseOpenResult.fromCallCode(result!!.first))
    }

    @Test
    fun `unreadable output or no expected display lets the open go ahead`() {
        assertNull(PointerDisplayCheck.check(55, runner("")))
        assertNull(PointerDisplayCheck.check(55) { 124 to "timeout after 3000 ms" })
        var ran = false
        assertNull(PointerDisplayCheck.check(PointerDisplayCheck.NO_DISPLAY) { ran = true; 0 to "" })
        assertEquals(false, ran)
    }
}
