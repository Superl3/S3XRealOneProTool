package com.raphael.handmouse.remote

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * What the Remote's virtual mouse may emit: only events the device registered by
 * [VirtualMouseProcess.registerJson] can produce. Everything else is refused, so the shell-uid
 * service cannot be used to type keys, touch the screen or flood the input pipeline.
 */
internal object MouseEventPolicy {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_REL = 2
    const val SYN_REPORT = 0
    const val BTN_LEFT = 272
    const val BTN_RIGHT = 273
    const val BTN_MIDDLE = 274
    const val REL_X = 0
    const val REL_Y = 1
    const val REL_HWHEEL = 6
    const val REL_WHEEL = 8

    /** Most (type, code, value) events in one batch. */
    const val MAX_EVENTS = 64

    /** Largest |value| of one relative event. */
    const val MAX_REL = 4096

    /** True when [events] is 1..[MAX_EVENTS] whole (type, code, value) triples and every one is allowed. */
    fun isAllowed(events: IntArray): Boolean {
        if (events.isEmpty() || events.size % 3 != 0 || events.size > MAX_EVENTS * 3) return false
        for (i in events.indices step 3) {
            if (!isAllowed(events[i], events[i + 1], events[i + 2])) return false
        }
        return true
    }

    private fun isAllowed(type: Int, code: Int, value: Int): Boolean = when (type) {
        EV_SYN -> code == SYN_REPORT && value == 0
        EV_KEY -> (code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE) && (value == 0 || value == 1)
        // A range check, not abs(): abs(Int.MIN_VALUE) is still negative.
        EV_REL -> (code == REL_X || code == REL_Y || code == REL_HWHEEL || code == REL_WHEEL) &&
            value in -MAX_REL..MAX_REL
        else -> false
    }
}

/**
 * One `/system/bin/uinput -` process that owns the Remote's virtual mouse. It runs inside the Shizuku
 * user service, whose shell uid may open `/dev/uinput`. [open] registers the device by writing one JSON
 * line to the process's stdin and [inject] writes event batches the same way. When stdin closes (on
 * [close], or because this process died) uinput exits and the device disappears.
 *
 * Every method is synchronized: binder threads call them concurrently.
 *
 * @param log where lifecycle messages and uinput's own output go (injectable so JVM tests need no Log).
 * @param start starts the uinput process with stderr merged into stdout.
 */
internal class VirtualMouseProcess(
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val start: () -> Process = { ProcessBuilder(UINPUT, "-").redirectErrorStream(true).start() },
) {
    private var process: Process? = null

    /** Non-null only after the register line went out; cleared on any write failure or close. */
    private var stdin: OutputStream? = null
    private var rejected = 0

    // inject() outcome counts since statsSinceNs (null: no batch since the last stats line).
    private var statsSinceNs: Long? = null
    private var statsInjected = 0
    private var statsRejected = 0
    private var statsUnwritten = 0
    private var statsHeldBack = 0

    /** True while the uinput process runs and its stdin still takes writes. */
    val isOpen: Boolean
        @Synchronized get() = stdin != null && process?.isAlive == true

    /**
     * Starts uinput and writes the register line, unless it is already open (then this only answers
     * "ok"). A process that died or broke since is cleaned up and replaced. "ok" does not prove the
     * device exists yet: uinput creates it (or fails to) a few hundred ms later.
     *
     * @return 0 to "ok", or [START_FAILED], [WRITE_FAILED] or [EXITED] with a message.
     */
    @Synchronized
    fun open(): Pair<Int, String> {
        if (stdin != null && process?.isAlive == true) return 0 to "ok"
        shutDown(CLOSE_WAIT_MS)
        val started = try {
            start()
        } catch (e: Exception) {
            log("uinput start failed: $e")
            return START_FAILED to (e.message ?: e.javaClass.simpleName)
        }
        process = started
        drain(started)
        val out = started.outputStream
        try {
            out.write(line(registerJson()))
            out.flush()
        } catch (e: IOException) {
            log("uinput register write failed: $e")
            shutDown(0L)
            return WRITE_FAILED to (e.message ?: e.javaClass.simpleName)
        }
        stdin = out
        // Only a uinput that cannot run at all exits this soon. The tool is an app_process JVM that
        // reads the register line, and fails to create the device, only after it has booted; so
        // "ok" means "started", and the app confirms the device itself (VirtualMouseDeviceWatch).
        if (exitedWithin(started, OPEN_SETTLE_MS)) {
            val exit = try { started.exitValue() } catch (_: IllegalThreadStateException) { -1 }
            log("uinput exited right after register ($exit)")
            shutDown(0L)
            return EXITED to "uinput exited ($exit)"
        }
        log("virtual mouse registered")
        return 0 to "ok"
    }

    /**
     * Writes one batch of (type, code, value) triples. False when [MouseEventPolicy] refuses it (logged
     * once per [REJECT_LOG_EVERY] refusals), when the mouse is not open, or when the write fails (then
     * the mouse is closed and the next [open] starts a new process). The outcomes are counted and
     * logged at most once per [STATS_LOG_INTERVAL_NS] (diagnostics, spec 2026-11-17 section J).
     */
    @Synchronized
    fun inject(events: IntArray): Boolean {
        val allowed = MouseEventPolicy.isAllowed(events)
        if (!allowed && rejected++ % REJECT_LOG_EVERY == 0) log("rejected event batch #$rejected (${events.size} ints)")
        val written = allowed && write(events)
        countBatch(allowed, written)
        return written
    }

    /**
     * Counts one air batch the service held back because a finger was on the touchscreen
     * ([MouseSource.heldBackWhileTouched]); it shows in the same per-second line as [inject].
     */
    @Synchronized
    fun countHeldBack() {
        statsHeldBack++
        logStatsWhenDue()
    }

    /** Counts one [inject] outcome; logs the counts once the window that began with its first batch is a second old. */
    private fun countBatch(allowed: Boolean, written: Boolean) {
        when {
            written -> statsInjected++
            !allowed -> statsRejected++
            else -> statsUnwritten++
        }
        logStatsWhenDue()
    }

    private fun logStatsWhenDue() {
        val now = System.nanoTime()
        val since = statsSinceNs ?: now.also { statsSinceNs = it }
        if (now - since >= STATS_LOG_INTERVAL_NS) logStats(now)
    }

    /** Logs the [inject] counts since the window began (if any) and starts a new window. */
    private fun logStats(now: Long) {
        val since = statsSinceNs ?: return
        val unwritten = if (statsUnwritten > 0) ", not written $statsUnwritten" else ""
        val heldBack = if (statsHeldBack > 0) ", air held back during touch $statsHeldBack" else ""
        log("injected $statsInjected batches (rejected $statsRejected$unwritten$heldBack) in ${(now - since) / 1_000_000} ms")
        statsSinceNs = null
        statsInjected = 0
        statsRejected = 0
        statsUnwritten = 0
        statsHeldBack = 0
    }

    /** Writes an allowed batch; see [inject]. */
    private fun write(events: IntArray): Boolean {
        val out = stdin ?: return false
        if (process?.isAlive != true) {
            log("uinput is gone; dropping events until the next open")
            shutDown(0L)
            return false
        }
        return try {
            out.write(line(injectJson(events)))
            out.flush()
            true
        } catch (e: IOException) {
            log("uinput write failed: $e")
            shutDown(0L)
            false
        }
    }

    /** Closes stdin so uinput removes the device and exits; kills it if it is still running after 500 ms. */
    @Synchronized
    fun close() {
        // The counts of the last burst, which no later batch will log.
        logStats(System.nanoTime())
        if (process == null) return
        shutDown(CLOSE_WAIT_MS)
        log("virtual mouse closed")
    }

    private fun shutDown(waitMs: Long) {
        val current = process ?: return
        val out = stdin ?: current.outputStream
        process = null
        stdin = null
        try {
            out.close()
        } catch (_: IOException) {
            // A broken pipe is already closed as far as uinput is concerned.
        }
        if (!exitedWithin(current, waitMs)) current.destroyForcibly()
    }

    private fun exitedWithin(process: Process, waitMs: Long): Boolean = try {
        process.waitFor(waitMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Reads uinput's output (stderr is merged into it) on a daemon thread so the pipe never fills and
     * blocks uinput. Each line is logged, cut to [MAX_LOG_LINE] chars; memory stays bounded.
     */
    private fun drain(process: Process) {
        Thread({
            val text = StringBuilder()
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val c = reader.read()
                        if (c < 0) break
                        if (c == '\n'.code) {
                            if (text.isNotBlank()) log("uinput: $text")
                            text.setLength(0)
                        } else if (text.length < MAX_LOG_LINE) {
                            text.append(c.toChar())
                        }
                    }
                }
            } catch (_: IOException) {
                // The stream closes under us when the process is destroyed.
            }
            if (text.isNotBlank()) log("uinput: $text")
        }, "uinput-out").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "VirtualMouse"
        private const val UINPUT = "/system/bin/uinput"

        const val DEVICE_NAME = "XrealRemote Mouse"

        /** True when an input device named [name] is this virtual mouse. */
        fun isDeviceName(name: String?): Boolean = name == DEVICE_NAME

        /** The uinput tool's id for the device; every inject names it. */
        const val DEVICE_ID = 1
        const val VENDOR_ID = 0x18d1 // 6353
        const val PRODUCT_ID = 0xbb02 // 47874

        /** [open] results besides 0. */
        const val START_FAILED = 126
        const val WRITE_FAILED = 125
        const val EXITED = 123

        private const val CLOSE_WAIT_MS = 500L
        private const val OPEN_SETTLE_MS = 100L
        private const val REJECT_LOG_EVERY = 100
        private const val MAX_LOG_LINE = 200

        /** Least time between two [inject] count lines: 1 s. */
        private const val STATS_LOG_INTERVAL_NS = 1_000_000_000L

        // The uinput tool's configuration types: the UI_SET_*BIT ioctls.
        private const val UI_SET_EVBIT = 100
        private const val UI_SET_KEYBIT = 101
        private const val UI_SET_RELBIT = 102

        /** The register command, as one compact JSON object (the format the device probe used). */
        fun registerJson(): String {
            fun config(type: Int, vararg data: Int) = data.joinToString(",", "{\"type\":$type,\"data\":[", "]}")
            val configuration = listOf(
                config(UI_SET_EVBIT, MouseEventPolicy.EV_KEY, MouseEventPolicy.EV_REL),
                config(UI_SET_KEYBIT, MouseEventPolicy.BTN_LEFT, MouseEventPolicy.BTN_RIGHT, MouseEventPolicy.BTN_MIDDLE),
                config(
                    UI_SET_RELBIT,
                    MouseEventPolicy.REL_X,
                    MouseEventPolicy.REL_Y,
                    MouseEventPolicy.REL_HWHEEL,
                    MouseEventPolicy.REL_WHEEL,
                ),
            ).joinToString(",")
            return "{\"id\":$DEVICE_ID,\"command\":\"register\",\"name\":\"$DEVICE_NAME\"," +
                "\"vid\":$VENDOR_ID,\"pid\":$PRODUCT_ID,\"bus\":\"usb\",\"configuration\":[$configuration]}"
        }

        /** The inject command for [events] (type, code, value, ...), as one compact JSON object. */
        fun injectJson(events: IntArray): String =
            events.joinToString(",", "{\"id\":$DEVICE_ID,\"command\":\"inject\",\"events\":[", "]}")

        private fun line(json: String): ByteArray = (json + "\n").toByteArray(Charsets.UTF_8)
    }
}
