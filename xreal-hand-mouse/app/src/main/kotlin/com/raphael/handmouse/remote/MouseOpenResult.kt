package com.raphael.handmouse.remote

/** The answer to [RemoteShellBridge.openMouse]: [OPENED], or why the virtual mouse did not open. */
enum class MouseOpenResult {
    /**
     * The shell service's uinput process runs and took the register line. The input device itself
     * may still be on its way (or fail to come): the caller confirms it ([VirtualMouseDeviceWatch]).
     */
    OPENED,

    /** Shizuku is not running, its binder has not arrived yet, or binding to the shell service failed. */
    UNAVAILABLE,

    /** The Shizuku permission was refused (now, or earlier with "don't ask again"). */
    DENIED,

    /** The Shizuku permission prompt was not answered while the bridge waited for it. */
    TIMEOUT,

    /** The shell service went away, the call failed, or a close or reconnect overtook the open. */
    DISCONNECTED,

    /**
     * The system's mouse pointer is on another display than the selected DeX display
     * ([PointerDisplayCheck]): DeX is not hosted there (yet), so the mouse was not opened.
     */
    WRONG_DISPLAY,

    /**
     * The shell service answered with a uinput error, does not know the mouse transactions (an older
     * version still runs), or the device never appeared after an open that answered "ok".
     */
    SHELL_ERROR;

    companion object {
        /** A mouse transaction that failed on the app side (no answer from the shell service). */
        const val CALL_FAILED = -1

        /** The shell service did not know the transaction (`transact` returned false). */
        const val UNKNOWN_TRANSACTION = -2

        /** The shell's answer when [PointerDisplayCheck] refuses the open. */
        const val WRONG_DISPLAY_CODE = 122

        /**
         * The result of one MOUSE_OPEN call by its code: 0 is [OPENED]; the shell's own error codes
         * (positive, see [VirtualMouseProcess]) and [UNKNOWN_TRANSACTION] are [SHELL_ERROR]; any
         * other code ([CALL_FAILED]) is [DISCONNECTED].
         */
        fun fromCallCode(code: Int): MouseOpenResult = when {
            code == 0 -> OPENED
            code == WRONG_DISPLAY_CODE -> WRONG_DISPLAY
            code > 0 || code == UNKNOWN_TRANSACTION -> SHELL_ERROR
            else -> DISCONNECTED
        }
    }
}

/** What may make a failed virtual-mouse open worth another try. */
enum class MouseRetryTrigger {
    /** A touch on the Remote panel (the caller rate-limits these). */
    TOUCH,

    /** Shizuku's binder arrived: Shizuku started, or restarted. */
    SHIZUKU_STARTED,

    /** The user granted the Shizuku permission (possibly after the bridge stopped waiting). */
    PERMISSION_GRANTED,
}

/**
 * Whether the Remote opens the virtual mouse again, within the same display session, after an open
 * that ended in a failure (spec 2026-11-17 section E). A retry must never bring up a Shizuku
 * permission prompt the user did not ask for again, and must not keep restarting a uinput that
 * cannot create the device:
 * - [MouseOpenResult.SHELL_ERROR]: never (the toggle or a new display session tries again).
 * - [MouseOpenResult.UNAVAILABLE]: when Shizuku starts (the open then asks for the permission the
 *   first time, as it would have), or whenever the permission is already granted.
 * - [MouseOpenResult.DENIED], [MouseOpenResult.TIMEOUT], [MouseOpenResult.DISCONNECTED]: only
 *   once the permission is granted, so no prompt can come from the retry.
 */
internal object SystemMouseRetry {
    fun shouldRetry(failure: MouseOpenResult, trigger: MouseRetryTrigger, permissionGranted: Boolean): Boolean =
        when (failure) {
            MouseOpenResult.OPENED, MouseOpenResult.SHELL_ERROR -> false
            MouseOpenResult.UNAVAILABLE -> permissionGranted || trigger == MouseRetryTrigger.SHIZUKU_STARTED
            MouseOpenResult.DENIED, MouseOpenResult.TIMEOUT, MouseOpenResult.DISCONNECTED -> permissionGranted
            // DeX may take over the display a little after it connects: a panel touch (rate-limited
            // by the caller) checks again.
            MouseOpenResult.WRONG_DISPLAY -> trigger == MouseRetryTrigger.TOUCH
        }
}
