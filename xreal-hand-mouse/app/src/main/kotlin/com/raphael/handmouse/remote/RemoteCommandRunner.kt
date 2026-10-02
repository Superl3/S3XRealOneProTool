package com.raphael.handmouse.remote

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Runs one command for [RemoteShellService]. A command that has not finished after [TIMEOUT_MS] is
 * killed and reported as exit 124, so a hung `input` cannot hold the caller for ever.
 */
internal object RemoteCommandRunner {
    const val TIMEOUT_MS = 3_000L
    private const val OUTPUT_WAIT_MS = 500L

    fun run(
        args: List<String>,
        timeoutMs: Long = TIMEOUT_MS,
        start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    ): Pair<Int, String> {
        if (args.isEmpty()) return 127 to "empty command"
        return try {
            val process = start(args)
            // The output is read on another thread: readText() returns only when the process closes
            // its output, so a hung command would never get as far as the timed waitFor.
            val output = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
            Thread(output, "remote-cmd-out").apply { isDaemon = true }.start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return 124 to "timeout after $timeoutMs ms"
            }
            val text = try { output.get(OUTPUT_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
            process.exitValue() to text
        } catch (e: Exception) {
            126 to (e.message ?: e.javaClass.simpleName)
        }
    }
}
