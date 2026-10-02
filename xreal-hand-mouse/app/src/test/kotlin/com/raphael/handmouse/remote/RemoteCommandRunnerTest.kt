package com.raphael.handmouse.remote

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandRunnerTest {

    /** A process that either finishes with [exit] and [text], or hangs until it is destroyed. */
    private class FakeProcess(private val hangs: Boolean, private val exit: Int = 0, text: String = "") : Process() {
        val killed = AtomicBoolean(false)
        private val destroyed = CountDownLatch(1)
        private val bytes = ByteArrayInputStream(text.toByteArray())
        private val out = object : InputStream() {
            override fun read(): Int {
                if (hangs) {
                    destroyed.await()
                    return -1
                }
                return bytes.read()
            }
        }

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
        }

        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !hangs
        override fun exitValue(): Int = exit
        override fun destroy() {
            killed.set(true)
            destroyed.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    @Test
    fun aFinishedCommandReturnsItsExitCodeAndOutput() {
        assertEquals(0 to "ok\n", RemoteCommandRunner.run(listOf("input")) { FakeProcess(hangs = false, text = "ok\n") })
        assertEquals(1, RemoteCommandRunner.run(listOf("input")) { FakeProcess(hangs = false, exit = 1) }.first)
    }

    @Test
    fun aHungCommandIsKilledAfterTheTimeoutAndTheCallerGetsAnswer() {
        val process = FakeProcess(hangs = true)
        val started = System.nanoTime()
        val result = RemoteCommandRunner.run(listOf("input"), timeoutMs = 100L) { process }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(124, result.first)
        assertTrue(result.second, result.second.startsWith("timeout"))
        assertTrue("process was not destroyed", process.killed.get())
        assertTrue("took $tookMs ms", tookMs < 2_000)
    }

    @Test
    fun anEmptyCommandAndAStartFailureAreReported() {
        assertEquals(127, RemoteCommandRunner.run(emptyList()).first)
        val failed = RemoteCommandRunner.run(listOf("input")) { throw IOException("boom") }
        assertEquals(126 to "boom", failed)
    }
}
