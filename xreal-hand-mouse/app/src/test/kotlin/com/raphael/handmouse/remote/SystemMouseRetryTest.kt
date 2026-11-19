package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.MouseOpenResult.DENIED
import com.raphael.handmouse.remote.MouseOpenResult.DISCONNECTED
import com.raphael.handmouse.remote.MouseOpenResult.OPENED
import com.raphael.handmouse.remote.MouseOpenResult.SHELL_ERROR
import com.raphael.handmouse.remote.MouseOpenResult.TIMEOUT
import com.raphael.handmouse.remote.MouseOpenResult.UNAVAILABLE
import com.raphael.handmouse.remote.MouseRetryTrigger.PERMISSION_GRANTED
import com.raphael.handmouse.remote.MouseRetryTrigger.SHIZUKU_STARTED
import com.raphael.handmouse.remote.MouseRetryTrigger.TOUCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMouseRetryTest {

    @Test
    fun callCodesMapToResults() {
        assertEquals(OPENED, MouseOpenResult.fromCallCode(0))
        // The shell's own uinput errors.
        assertEquals(SHELL_ERROR, MouseOpenResult.fromCallCode(VirtualMouseProcess.START_FAILED))
        assertEquals(SHELL_ERROR, MouseOpenResult.fromCallCode(VirtualMouseProcess.WRITE_FAILED))
        assertEquals(SHELL_ERROR, MouseOpenResult.fromCallCode(VirtualMouseProcess.EXITED))
        // An older shell service without the mouse transactions.
        assertEquals(SHELL_ERROR, MouseOpenResult.fromCallCode(MouseOpenResult.UNKNOWN_TRANSACTION))
        // The call itself failed (dead binder, transaction error): the service may come back.
        assertEquals(DISCONNECTED, MouseOpenResult.fromCallCode(MouseOpenResult.CALL_FAILED))
    }

    @Test
    fun aShellErrorIsNeverRetried() {
        for (trigger in MouseRetryTrigger.values()) {
            for (granted in listOf(false, true)) {
                assertFalse("$trigger $granted", SystemMouseRetry.shouldRetry(SHELL_ERROR, trigger, granted))
            }
        }
    }

    @Test
    fun anOpenThatWaitedForShizukuIsRetriedWhenItStarts() {
        // Shizuku not running when DeX connected: starting it brings the open (and its first prompt).
        assertTrue(SystemMouseRetry.shouldRetry(UNAVAILABLE, SHIZUKU_STARTED, permissionGranted = false))
        assertTrue(SystemMouseRetry.shouldRetry(UNAVAILABLE, SHIZUKU_STARTED, permissionGranted = true))
        assertTrue(SystemMouseRetry.shouldRetry(UNAVAILABLE, PERMISSION_GRANTED, permissionGranted = true))
        // A touch only retries once no prompt is needed.
        assertTrue(SystemMouseRetry.shouldRetry(UNAVAILABLE, TOUCH, permissionGranted = true))
        assertFalse(SystemMouseRetry.shouldRetry(UNAVAILABLE, TOUCH, permissionGranted = false))
    }

    @Test
    fun aLateGrantRetriesAnOpenWhosePromptTimedOut() {
        assertTrue(SystemMouseRetry.shouldRetry(TIMEOUT, PERMISSION_GRANTED, permissionGranted = true))
        // Granted without the listener firing: the next touch notices.
        assertTrue(SystemMouseRetry.shouldRetry(TIMEOUT, TOUCH, permissionGranted = true))
        // Still not granted: no retry, so no second prompt.
        assertFalse(SystemMouseRetry.shouldRetry(TIMEOUT, TOUCH, permissionGranted = false))
        assertFalse(SystemMouseRetry.shouldRetry(TIMEOUT, SHIZUKU_STARTED, permissionGranted = false))
    }

    @Test
    fun aRefusalIsRetriedOnlyOnceThePermissionIsGranted() {
        assertFalse(SystemMouseRetry.shouldRetry(DENIED, TOUCH, permissionGranted = false))
        assertFalse(SystemMouseRetry.shouldRetry(DENIED, SHIZUKU_STARTED, permissionGranted = false))
        assertTrue(SystemMouseRetry.shouldRetry(DENIED, PERMISSION_GRANTED, permissionGranted = true))
        assertTrue(SystemMouseRetry.shouldRetry(DENIED, TOUCH, permissionGranted = true))
    }

    @Test
    fun aLostShellIsRetriedWhileThePermissionHolds() {
        assertTrue(SystemMouseRetry.shouldRetry(DISCONNECTED, TOUCH, permissionGranted = true))
        assertTrue(SystemMouseRetry.shouldRetry(DISCONNECTED, SHIZUKU_STARTED, permissionGranted = true))
        assertFalse(SystemMouseRetry.shouldRetry(DISCONNECTED, TOUCH, permissionGranted = false))
    }

    @Test
    fun anOpenThatSucceededIsNotAFailureToRetry() {
        for (trigger in MouseRetryTrigger.values()) {
            assertFalse(SystemMouseRetry.shouldRetry(OPENED, trigger, permissionGranted = true))
        }
    }
}
