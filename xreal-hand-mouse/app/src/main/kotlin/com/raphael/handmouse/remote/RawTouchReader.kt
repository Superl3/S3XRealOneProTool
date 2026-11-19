package com.raphael.handmouse.remote

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException

/**
 * Reads the phone's touchscreen straight from its evdev node, inside the Shizuku shell service
 * (uid shell is in group input). Android's accessibility input filter keeps one active input device:
 * when the Remote's virtual mouse sends while a finger is down, it cancels that finger's window
 * events and drops its MOVE and UP (spec 2026-11-17 section O). The kernel's own stream is not
 * filtered, so a pad drag read from it keeps going, and [holdsAir] tells the service the moment a
 * finger lands, before the app could know.
 *
 * The nodes are found once per process with `getevent -pl` ([RawTouchProbe]). One "raw-touch"
 * thread polls them together with a wake pipe; [stop] closes the pipe's write end, which wakes the
 * thread so it closes the nodes. No thread runs between [stop] and the next [start].
 *
 * [start] and [stop] may be called from any thread. The [Listener] is called under this reader's
 * lock, so its calls never interleave; it must not call back into [start] or [stop] from there.
 */
internal class RawTouchReader(
    private val listener: Listener,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val probe: () -> List<TouchNode>? = ::probeTouchscreens,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    interface Listener {
        /** The reader can deliver frames (true), or cannot: no readable touchscreen, or it failed. */
        fun onAvailability(available: Boolean)

        /**
         * A report changed the contacts. In order. A run that ends (a [stop], a failure) sends no
         * frame of its own: its contacts got no lift, and a failure's [onAvailability] false says so.
         */
        fun onFrame(frame: TouchFrame)
    }

    private val lock = Any()
    private var running = false
    private var generation = 0
    private var wakeWrite: FileDescriptor? = null
    private var nodes: List<TouchNode>? = null
    private var announced = false
    private var available: Boolean? = null
    private var current = TouchFrame(0L, emptyList())

    /** Until when [holdsAir] is true (uptime ms); a contact landing renews it, the last lift ends it. */
    @Volatile
    private var airHeldUntilMs = 0L

    /**
     * Whether air-mouse batches wait at [nowMs]: a finger is on the touchscreen and one landed less
     * than [MouseSource.AIR_HOLD_AFTER_LANDING_MS] ago. False while stopped. Any thread.
     */
    fun holdsAir(nowMs: Long): Boolean = nowMs < airHeldUntilMs

    /**
     * Runs [block] under the lock with the availability (null: not known yet) and the last frame,
     * so a new subscriber gets the state no listener call can overtake.
     */
    fun <T> withState(block: (available: Boolean?, frame: TouchFrame) -> T): T =
        synchronized(lock) { block(available, current) }

    /** Starts reading, unless it runs. */
    fun start() {
        synchronized(lock) {
            if (running) return
            val pipe = try {
                Os.pipe()
            } catch (e: ErrnoException) {
                log("raw touch: no wake pipe ($e)")
                setAvailable(false)
                return
            }
            running = true
            val gen = ++generation
            wakeWrite = pipe[1]
            Thread({ run(gen, pipe[0]) }, "raw-touch").apply { isDaemon = true }.start()
        }
    }

    /** Stops reading; a finger still down is forgotten (no frame). The availability is forgotten. */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            end()
            available = null
        }
    }

    /**
     * Under the lock: no more frames from the current run; wakes its thread. Contacts still down are
     * dropped without a frame: an empty one would read as a lift (a tap) to the app, which takes the
     * end of the frames ([Listener.onAvailability] false, or its own unsubscribe) as a cancel.
     */
    private fun end() {
        running = false
        generation++
        wakeWrite?.let(::closeQuietly)
        wakeWrite = null
        current = TouchFrame(now(), emptyList())
        airHeldUntilMs = 0L
    }

    private fun run(gen: Int, wakeRead: FileDescriptor) {
        val opened = ArrayList<Pair<TouchNode, FileDescriptor>>()
        try {
            for (node in touchscreens().orEmpty()) {
                try {
                    opened += node to Os.open(node.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                } catch (e: ErrnoException) {
                    log("raw touch: cannot open ${node.path} (${node.name}): ${e.message}")
                }
            }
            if (opened.isNotEmpty() && began(gen, opened.map { it.first })) poll(gen, wakeRead, opened)
        } catch (e: Throwable) {
            // Never let it escape: an uncaught exception would kill the shell service and its mouse.
            log("raw touch: reader failed ($e)")
        } finally {
            opened.forEach { closeQuietly(it.second) }
            closeQuietly(wakeRead)
            synchronized(lock) {
                // Still the current run, so it ended by itself (nothing readable, or a failure).
                if (gen == generation) {
                    end()
                    setAvailable(false)
                }
            }
        }
    }

    /**
     * The touchscreens, probed on the first run of this process that got an answer; null when
     * getevent did not list the devices (the next run probes again).
     */
    private fun touchscreens(): List<TouchNode>? {
        synchronized(lock) { nodes }?.let { return it }
        val found = probe() ?: return null
        synchronized(lock) { nodes = found }
        if (found.isEmpty()) log("raw touch: no touchscreen in getevent -pl; the Remote keeps window touch events")
        return found
    }

    /** Under the lock: the run is still wanted, so it is announced available. */
    private fun began(gen: Int, read: List<TouchNode>): Boolean {
        synchronized(lock) {
            if (gen != generation) return false
            if (!announced) {
                announced = true
                for (node in read) {
                    log(
                        "raw touch: reading ${node.path} (${node.name}) x ${node.minX}..${node.maxX} " +
                            "y ${node.minY}..${node.maxY}, ${node.slots} slots, ${recordSize()}-byte events",
                    )
                }
            }
            setAvailable(true)
            return true
        }
    }

    private fun poll(gen: Int, wakeRead: FileDescriptor, opened: List<Pair<TouchNode, FileDescriptor>>) {
        val recordSize = recordSize()
        val frames = RawTouchFrames(opened.map { it.first }, recordSize)
        val pollIn = OsConstants.POLLIN.toShort()
        val fds = Array(opened.size + 1) { i ->
            StructPollfd().apply {
                fd = if (i == 0) wakeRead else opened[i - 1].second
                events = pollIn
            }
        }
        val buffers = Array(opened.size) { ByteArray(recordSize * READ_EVENTS) }
        val filled = IntArray(opened.size)
        while (true) {
            try {
                Os.poll(fds, -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw e
            }
            // stop() closed the pipe's write end.
            if (fds[0].revents.toInt() != 0) return
            for (i in opened.indices) {
                val revents = fds[i + 1].revents.toInt()
                if (revents == 0) continue
                val (node, fd) = opened[i]
                if (revents and OsConstants.POLLIN == 0) throw IOException("${node.path} gone (revents $revents)")
                val buf = buffers[i]
                val read = Os.read(fd, buf, filled[i], buf.size - filled[i])
                if (read <= 0) throw IOException("${node.path} ended")
                val length = filled[i] + read
                val used = frames.feed(i, buf, length) { contacts, cancelLifts -> publish(gen, contacts, cancelLifts) }
                filled[i] = length - used
                if (filled[i] > 0) System.arraycopy(buf, used, buf, 0, filled[i])
            }
        }
    }

    private fun publish(gen: Int, contacts: List<TouchContact>, cancelLifts: Boolean) {
        synchronized(lock) {
            if (gen == generation) emit(contacts, cancelLifts)
        }
    }

    /** Under the lock. */
    private fun emit(contacts: List<TouchContact>, cancelLifts: Boolean) {
        val previous = current.contacts
        current = TouchFrame(now(), contacts, cancelLifts)
        airHeldUntilMs = MouseSource.airHoldUntil(previous, current, airHeldUntilMs)
        listener.onFrame(current)
    }

    /** Under the lock. */
    private fun setAvailable(value: Boolean) {
        if (available == value) return
        available = value
        listener.onAvailability(value)
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
            // Already closed: nothing left to release.
        }
    }

    private companion object {
        const val TAG = "RawTouch"

        /** Events one read may return: a whole report of a few fingers fits many times over. */
        const val READ_EVENTS = 128

        fun recordSize(): Int = Evdev.recordSize(android.os.Process.is64Bit())

        fun probeTouchscreens(): List<TouchNode>? {
            val (code, output) = RemoteCommandRunner.run(RawTouchProbe.COMMAND)
            val found = RawTouchProbe.result(code, output)
            if (found == null) Log.w(TAG, "getevent -pl failed ($code), probing again next time: ${output.take(200).trim()}")
            return found
        }
    }
}
