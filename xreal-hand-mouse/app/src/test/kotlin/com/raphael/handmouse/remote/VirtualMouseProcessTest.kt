package com.raphael.handmouse.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualMouseProcessTest {

    /** A uinput stand-in: records stdin, has no output, and exits when stdin closes unless [exitsOnEof] is false. */
    private class FakeProcess(private val exitsOnEof: Boolean = true) : Process() {
        @Volatile
        var alive = true
        @Volatile
        var destroyedForcibly = false
        val stdin = Stdin()

        inner class Stdin : ByteArrayOutputStream() {
            @Volatile
            var failWrites = false
            @Volatile
            var dieOnFlush = false
            @Volatile
            var closed = false

            override fun write(b: Int) {
                ensureWritable()
                super.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                ensureWritable()
                super.write(b, off, len)
            }

            override fun flush() {
                ensureWritable()
                if (dieOnFlush) alive = false
            }

            override fun close() {
                closed = true
                if (exitsOnEof) alive = false
            }

            fun text(): String = toString("UTF-8")

            private fun ensureWritable() {
                if (failWrites || closed) throw IOException("Broken pipe")
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException("running") else 0
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly = true
            alive = false
            return this
        }
    }

    private val started = mutableListOf<FakeProcess>()
    private var nextExitsOnEof = true
    private val logs = CopyOnWriteArrayList<String>()
    private val mouse = VirtualMouseProcess(log = { logs += it }) {
        FakeProcess(nextExitsOnEof).also { started += it }
    }

    private val move = intArrayOf(2, 0, 40, 2, 1, -40, 0, 0, 0)

    @Test
    fun registerJsonIsTheProbeFormatWithTheMouseConfiguration() {
        assertEquals(
            """{"id":1,"command":"register","name":"XrealRemote Mouse","vid":6353,"pid":47874,"bus":"usb",""" +
                """"configuration":[{"type":100,"data":[1,2]},{"type":101,"data":[272,273,274]},""" +
                """{"type":102,"data":[0,1,6,8]}]}""",
            VirtualMouseProcess.registerJson(),
        )
    }

    @Test
    fun onlyTheRegisteredNameIsTheVirtualMouseDevice() {
        assertTrue(VirtualMouseProcess.isDeviceName("XrealRemote Mouse"))
        assertFalse(VirtualMouseProcess.isDeviceName("XrealRemote Probe Mouse"))
        assertFalse(VirtualMouseProcess.isDeviceName("xrealremote mouse"))
        assertFalse(VirtualMouseProcess.isDeviceName(null))
    }

    @Test
    fun injectJsonListsTheEventsInOrder() {
        assertEquals(
            """{"id":1,"command":"inject","events":[2,0,40,2,1,-40,0,0,0]}""",
            VirtualMouseProcess.injectJson(move),
        )
    }

    @Test
    fun heldBackAirBatchesAreCountedButNotWritten() {
        mouse.open()
        val registered = started[0].stdin.text()
        mouse.countHeldBack()
        mouse.countHeldBack()
        assertEquals(registered, started[0].stdin.text())
        mouse.close()
        assertTrue(logs.toString(), logs.any { it.startsWith("injected 0 batches (rejected 0, air held back during touch 2)") })
    }

    @Test
    fun openWritesExactlyTheRegisterLine() {
        assertEquals(0 to "ok", mouse.open())
        assertTrue(mouse.isOpen)
        assertEquals(1, started.size)
        val text = started[0].stdin.text()
        assertEquals(VirtualMouseProcess.registerJson() + "\n", text)
        assertTrue(text, text.contains("\"name\":\"XrealRemote Mouse\""))
        assertTrue(text, text.contains("\"bus\":\"usb\""))
        assertTrue(text, text.contains("\"vid\":6353,\"pid\":47874"))
        assertTrue(text, text.contains("{\"type\":100,\"data\":[1,2]}"))
        assertTrue(text, text.contains("{\"type\":101,\"data\":[272,273,274]}"))
        assertTrue(text, text.contains("{\"type\":102,\"data\":[0,1,6,8]}"))
        assertEquals(1, text.count { it == '\n' })
    }

    @Test
    fun openTwiceStartsOneProcess() {
        assertEquals(0 to "ok", mouse.open())
        assertEquals(0 to "ok", mouse.open())
        assertEquals(1, started.size)
        assertEquals(VirtualMouseProcess.registerJson() + "\n", started[0].stdin.text())
    }

    @Test
    fun injectWritesTheInjectLineWithTheExactNumbers() {
        mouse.open()
        val before = started[0].stdin.size()
        assertTrue(mouse.inject(move))
        assertEquals(
            "{\"id\":1,\"command\":\"inject\",\"events\":[2,0,40,2,1,-40,0,0,0]}\n",
            started[0].stdin.text().substring(before),
        )
    }

    @Test
    fun injectBeforeOpenStartsNothingAndWritesNothing() {
        assertFalse(mouse.inject(move))
        assertFalse(mouse.isOpen)
        assertTrue(started.isEmpty())
    }

    @Test
    fun refusedBatchesWriteNothingAndAreLoggedOncePerHundred() {
        mouse.open()
        val before = started[0].stdin.size()
        val refused = listOf(
            intArrayOf(2, 0), // not whole triples
            intArrayOf(3, 0, 10, 0, 0, 0), // EV_ABS
            intArrayOf(1, 330, 1, 0, 0, 0), // BTN_TOUCH
            intArrayOf(2, 0, 5000, 0, 0, 0), // REL beyond 4096
            intArrayOf(1, 272, 2, 0, 0, 0), // key auto-repeat value
        )
        for (events in refused) assertFalse(events.joinToString(), mouse.inject(events))
        assertEquals(before, started[0].stdin.size())
        assertTrue(mouse.isOpen)

        repeat(100 - refused.size) { mouse.inject(intArrayOf(3, 0, 10)) }
        assertEquals(1, logs.count { it.startsWith("rejected") })
        mouse.inject(intArrayOf(3, 0, 10))
        assertEquals(2, logs.count { it.startsWith("rejected") })
        assertEquals(before, started[0].stdin.size())
    }

    @Test
    fun aWriteFailureClosesTheMouseAndTheNextOpenRestarts() {
        mouse.open()
        started[0].stdin.failWrites = true
        assertFalse(mouse.inject(move))
        assertFalse(mouse.isOpen)
        assertTrue(started[0].stdin.closed)
        assertFalse(mouse.inject(move))

        assertEquals(0 to "ok", mouse.open())
        assertEquals(2, started.size)
        assertEquals(VirtualMouseProcess.registerJson() + "\n", started[1].stdin.text())
        assertTrue(mouse.inject(move))
    }

    @Test
    fun aDeadProcessIsReplacedOnOpen() {
        mouse.open()
        started[0].alive = false
        assertFalse(mouse.isOpen)

        assertEquals(0 to "ok", mouse.open())
        assertEquals(2, started.size)
        assertTrue(started[0].stdin.closed)
        assertTrue(mouse.isOpen)
    }

    @Test
    fun injectAfterTheProcessDiedWritesNothing() {
        mouse.open()
        val before = started[0].stdin.size()
        started[0].alive = false
        assertFalse(mouse.inject(move))
        assertEquals(before, started[0].stdin.size())
        assertEquals(0 to "ok", mouse.open())
        assertEquals(2, started.size)
    }

    @Test
    fun closeClosesStdinAndKillsOnlyAProcessThatDoesNotExit() {
        mouse.open()
        mouse.close()
        assertTrue(started[0].stdin.closed)
        assertFalse(started[0].destroyedForcibly)
        assertFalse(mouse.isOpen)
        assertFalse(mouse.inject(move))

        nextExitsOnEof = false
        mouse.open()
        mouse.close()
        assertTrue(started[1].stdin.closed)
        assertTrue(started[1].destroyedForcibly)
    }

    @Test
    fun closeWithoutOpenDoesNothing() {
        mouse.close()
        assertTrue(started.isEmpty())
        assertFalse(mouse.isOpen)
    }

    @Test
    fun startAndWriteFailuresAreReportedWithTheirCodes() {
        val unstartable = VirtualMouseProcess(log = {}) { throw IOException("no uinput") }
        assertEquals(VirtualMouseProcess.START_FAILED to "no uinput", unstartable.open())
        assertFalse(unstartable.isOpen)

        val unwritable = FakeProcess().apply { stdin.failWrites = true }
        val broken = VirtualMouseProcess(log = {}) { unwritable }
        assertEquals(VirtualMouseProcess.WRITE_FAILED, broken.open().first)
        assertFalse(broken.isOpen)
        assertFalse(unwritable.alive)
    }

    @Test
    fun uinputThatExitsRightAfterRegisterIsReported() {
        val quitter = FakeProcess().apply { stdin.dieOnFlush = true }
        val shortLived = VirtualMouseProcess(log = {}) { quitter }
        assertEquals(VirtualMouseProcess.EXITED, shortLived.open().first)
        assertFalse(shortLived.isOpen)
    }

    @Test
    fun policyAcceptsWhatTheVirtualMouseCanEmit() {
        val allowed = listOf(
            intArrayOf(0, 0, 0),
            move,
            intArrayOf(1, 272, 1, 0, 0, 0, 1, 272, 0, 0, 0, 0),
            intArrayOf(1, 273, 1, 1, 274, 0, 0, 0, 0),
            intArrayOf(2, 8, -1, 0, 0, 0),
            intArrayOf(2, 6, 3, 0, 0, 0),
            intArrayOf(2, 0, 4096, 2, 1, -4096, 0, 0, 0),
            IntArray(MouseEventPolicy.MAX_EVENTS * 3), // 64 SYN_REPORTs
        )
        for (events in allowed) assertTrue(events.joinToString(), MouseEventPolicy.isAllowed(events))
    }

    @Test
    fun policyRefusesEverythingElse() {
        val refused = listOf(
            IntArray(0),
            intArrayOf(2, 0),
            intArrayOf(2, 0, 1, 0),
            IntArray(MouseEventPolicy.MAX_EVENTS * 3 + 3), // 65 events
            intArrayOf(3, 0, 10), // EV_ABS
            intArrayOf(4, 4, 1), // EV_MSC
            intArrayOf(1, 330, 1), // BTN_TOUCH
            intArrayOf(1, 30, 1), // KEY_A
            intArrayOf(1, 272, 2), // auto-repeat
            intArrayOf(1, 272, -1),
            intArrayOf(2, 0, 5000),
            intArrayOf(2, 1, -4097),
            intArrayOf(2, 0, Int.MIN_VALUE),
            intArrayOf(2, 2, 1), // REL_Z
            intArrayOf(2, 11, 120), // REL_WHEEL_HI_RES
            intArrayOf(0, 3, 0), // SYN_DROPPED
            intArrayOf(0, 0, 1),
            intArrayOf(2, 0, 10, 3, 0, 10, 0, 0, 0), // one bad event spoils the batch
        )
        for (events in refused) assertFalse(events.joinToString(), MouseEventPolicy.isAllowed(events))
    }
}
