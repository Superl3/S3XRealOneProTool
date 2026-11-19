package com.raphael.handmouse.remote

/**
 * Runs in the shell service before the virtual mouse opens: the system's mouse pointer must be on
 * the DeX display the Remote selected, or every motion would move a cursor on another display
 * (the phone screen, when an external display is not hosting DeX yet).
 *
 * The pointer display is read from `dumpsys input` ("Default mouse displayId: N" of
 * PointerChoreographer), which the shell may read and the app may not. Measured 2026-11-17 on the
 * SM-S937N: with DeX on the glasses it read 54 / 55 (the glasses' display), without them 0. A
 * Samsung display category check was tried first and failed there: the DeX display on the glasses
 * was not in `getDisplays(DESKTOP)`.
 */
internal object PointerDisplayCheck {
    /** Sent in MOUSE_OPEN when the caller does not ask for the check. */
    const val NO_DISPLAY = -1

    val COMMAND = listOf("/system/bin/sh", "-c", "dumpsys input | grep 'Default mouse displayId'")

    private val PATTERN = Regex("Default mouse displayId:\\s*(-?\\d+)")

    /** The pointer display in [dumpsysOutput], or null when it is not there (or `<unset>`). */
    fun parse(dumpsysOutput: String): Int? = PATTERN.find(dumpsysOutput)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Null when the open may go ahead: [expected] is [NO_DISPLAY] (or negative), the pointer display
     * could not be read, or it is [expected]. Otherwise the (code, message) MOUSE_OPEN answers with,
     * code [MouseOpenResult.WRONG_DISPLAY_CODE].
     */
    fun check(expected: Int, run: (List<String>) -> Pair<Int, String> = { RemoteCommandRunner.run(it) }): Pair<Int, String>? {
        if (expected < 0) return null
        val (_, output) = run(COMMAND)
        val actual = parse(output) ?: return null
        if (actual == expected) return null
        return MouseOpenResult.WRONG_DISPLAY_CODE to "mouse pointer display is $actual, not the DeX display $expected"
    }
}
