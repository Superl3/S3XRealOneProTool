package com.raphael.handmouse.remote

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * @param onShizukuEvent called on the main thread when Shizuku's binder arrives
 *   ([MouseRetryTrigger.SHIZUKU_STARTED]) or the user grants its permission
 *   ([MouseRetryTrigger.PERMISSION_GRANTED]), also when nothing waits here any more: an owner whose
 *   mouse open failed for want of either may try again. Never called after [destroy].
 * @param serviceName names the shell service's process suffix and its Shizuku tag. Bridges with
 *   the same name share one service (and [destroy] shuts it down for all of them), so an owner that
 *   must not disturb the Remote's service (its virtual mouse) uses a name of its own.
 */
class RemoteShellBridge(
    private val context: Context,
    private val onShizukuEvent: (MouseRetryTrigger) -> Unit = {},
    private val serviceName: String = "dex_remote",
) {
    /** [SENT]: handed to the worker thread (the result is logged there); [QUEUED]: waits for Shizuku. */
    enum class DispatchResult { SENT, QUEUED, UNAVAILABLE }

    companion object {
        private const val TAG = "RemoteShellBridge"
        private const val REQUEST_CODE = 0x5844
        private const val MAX_PENDING_COMMANDS = 4
        private const val PERMISSION_POLL_ATTEMPTS = 120
        private const val PERMISSION_POLL_INTERVAL_MS = 250L
        private const val MOUSE_FAILURE_LOG_EVERY = 100

        /**
         * Longest wait for the shell service after bindUserService. A bind that stalls (a callback
         * that never comes, a starved device) then answers what waited for it, instead of leaving
         * window actions waiting and later presses ignored (diagnosis 2026-11-18,
         * shell-single-point-1118). The service usually connects in well under a second.
         */
        private const val BIND_TIMEOUT_MS = 5_000L

        /**
         * [runCommand]'s exit code when the command never reached the shell: Shizuku is not there,
         * its permission was refused or not answered, or the service went away first. The output
         * then names the reason.
         */
        const val COMMAND_NOT_RUN = -2
    }

    /**
     * A worker task whose caller waits for it (the virtual mouse, [runCommand]). The full-queue
     * policy never drops one to make room for a key.
     */
    private class MouseTask(private val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    @Volatile
    private var remote: IBinder? = null
    @Volatile
    private var bindRequested = false
    @Volatile
    private var destroyed = false
    private val pendingCommands = ArrayDeque<Array<String>>()

    /** [runCommand] calls waiting for the shell service. Main thread. */
    private class PendingRun(val command: Array<String>, val onResult: (Int, String) -> Unit)
    private val pendingRuns = ArrayList<PendingRun>()

    // Virtual mouse state. The open callbacks and the generation are touched on the main thread only;
    // mouseOpened is also read (and cleared on death) by mouseEvents on the caller's thread.
    @Volatile
    private var mouseOpened = false
    private var mouseGeneration = 0
    private val pendingMouseOpens = ArrayList<(MouseOpenResult) -> Unit>()

    /** The DeX display of the latest [openMouse]; sent with MOUSE_OPEN. Main thread. */
    @Volatile
    private var mouseDisplayId = PointerDisplayCheck.NO_DISPLAY
    private var mouseFailures = 0

    /**
     * The [subscribeTouches] in effect, or null. Main thread. Its [TouchSubscription.callback] is the
     * binder the shell calls; a new subscription gets a new one, so calls meant for an old one are
     * told apart and dropped.
     */
    private var touches: TouchSubscription? = null

    private inner class TouchSubscription(
        val onFrame: (TouchFrame) -> Unit,
        val onStatus: (available: Boolean) -> Unit,
    ) {
        val callback = TouchCallback(this)

        /** The service binder that has this subscription; null while it waits for one. */
        var subscribedTo: IBinder? = null

        /** The last [onStatus] given; null before the first. */
        var status: Boolean? = null
    }

    /**
     * The shell calls this (oneway, on a binder thread) with frames and status; both are posted to
     * the main thread in arrival order and dropped once [owner] is no longer the subscription. Only
     * the bridge's own shell service is ever handed this binder.
     */
    private inner class TouchCallback(private val owner: TouchSubscription) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                RemoteShellProtocol.CALLBACK_TOUCH_FRAME -> {
                    data.enforceInterface(RemoteShellProtocol.CALLBACK_DESCRIPTOR)
                    val frame = TouchFrameCodec.decode(data.createIntArray()) ?: return true
                    mainHandler.post { if (!destroyed && touches === owner) owner.onFrame(frame) }
                    return true
                }
                RemoteShellProtocol.CALLBACK_TOUCH_STATUS -> {
                    data.enforceInterface(RemoteShellProtocol.CALLBACK_DESCRIPTOR)
                    val available = data.readInt() != 0
                    mainHandler.post { if (!destroyed && touches === owner) touchStatus(owner, available) }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    // Every call into the shell service is a blocking Binder transaction that waits for a spawned
    // `input` process; it runs here, never on the main thread. Bounded: when full, the oldest waiting
    // key goes. A waiting MouseTask is never the one dropped; a MouseTask that finds no key to drop
    // (or a stopped worker) is rejected with RejectedExecutionException so its caller can report it.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_COMMANDS),
        ThreadFactory { Thread(it, "RemoteShell").apply { isDaemon = true } },
        RejectedExecutionHandler { task, executor ->
            val oldestKey = if (executor.isShutdown) null else executor.queue.firstOrNull { it !is MouseTask }
            when {
                oldestKey != null && executor.queue.remove(oldestKey) -> executor.execute(task)
                task is MouseTask -> throw RejectedExecutionException("shell worker full or stopped")
                else -> Unit // a key with nowhere to go is dropped, as DiscardOldestPolicy did
            }
        },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionPollRemaining = 0
    private val permissionPoll = object : Runnable {
        override fun run() {
            if (destroyed || !hasPending() || permissionPollRemaining <= 0) return
            permissionPollRemaining--
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (!bind()) failPendingMouseOpens(MouseOpenResult.UNAVAILABLE)
                return
            }
            if (permissionPollRemaining == 0) {
                // Nobody answered the prompt in time. Keys stay queued as before; a mouse open (or a
                // runCommand) gets its "no". A later grant still arrives through permissionResult
                // (onShizukuEvent).
                failPendingMouseOpens(MouseOpenResult.TIMEOUT)
                return
            }
            mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MS)
        }
    }

    /** See [BIND_TIMEOUT_MS]. Posted by [bind], removed when the service connects. */
    private val bindTimeout: Runnable = Runnable {
        if (destroyed || remote != null || !bindRequested) return@Runnable
        Log.w(TAG, "shell service did not connect within $BIND_TIMEOUT_MS ms; answering what waited for it")
        // Drop the stalled binding, so the next request binds afresh (as after a death).
        runCatching { Shizuku.unbindUserService(args, connection, false) }
            .onFailure { Log.w(TAG, "unbindUserService after bind timeout failed", it) }
        bindRequested = false
        if (pendingCommands.isNotEmpty()) Log.w(TAG, "dropped ${pendingCommands.size} queued key(s)")
        pendingCommands.clear()
        failPendingMouseOpens(MouseOpenResult.UNAVAILABLE)
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, RemoteShellService::class.java.name),
        )
            .processNameSuffix(serviceName)
            .daemon(false)
            // 7: touch frames carry the cancel flag (a 6 without it was installed on a device on
            // 2026-11-18); 6: raw touchscreen reader (TOUCH_* transactions) and the
            // MOUSE_EVENTS source, whose AIR batches wait after a finger lands; 5: MOUSE_OPEN carries
            // the DeX display and checks the pointer is there; 4: virtual uinput mouse (MOUSE_* transactions);
            // 3: commands time out (RemoteCommandRunner).
            // Shizuku restarts a service left at an older version.
            .version(7)
            .tag("${serviceName}_shell")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindRequested = false
            mainHandler.removeCallbacks(bindTimeout)
            if (destroyed) {
                // Not ours to stop: destroy() already removed the service it bound, and a callback
                // that still reaches this connection (Shizuku shares one connection object per tag)
                // may carry the service a newer bridge just bound. Removing or shutting that down
                // would take it from under the newer bridge.
                Log.d(TAG, "remote shell connected after destroy; left alone")
                return
            }
            remote = service
            Log.d(TAG, "Shizuku remote shell connected")
            flushPending()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (destroyed) return
            remote = null
            bindRequested = false
            mainHandler.removeCallbacks(bindTimeout)
            pendingCommands.clear()
            resetMouse()
            touchesLost()
            failPendingMouseOpens(MouseOpenResult.DISCONNECTED)
            Log.w(TAG, "Shizuku remote shell disconnected")
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (destroyed) return@OnBinderReceivedListener
        if (hasOneShotPending()) {
            startBinding()?.let(::failPendingMouseOpens)
        } else if (touchesWaiting() && permissionGranted) {
            // A touch subscription alone binds only without a prompt (see subscribeTouches).
            bind()
        }
        onShizukuEvent(MouseRetryTrigger.SHIZUKU_STARTED)
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        bindRequested = false
        mainHandler.removeCallbacks(bindTimeout)
        pendingCommands.clear()
        resetMouse()
        touchesLost()
        failPendingMouseOpens(MouseOpenResult.UNAVAILABLE)
        stopPermissionPoll()
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (destroyed || requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        stopPermissionPoll()
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            if (hasPending() && !bind()) failPendingMouseOpens(MouseOpenResult.UNAVAILABLE)
            // Also when the poll gave up before the user answered: the owner may retry its open.
            onShizukuEvent(MouseRetryTrigger.PERMISSION_GRANTED)
        } else {
            pendingCommands.clear()
            failPendingMouseOpens(MouseOpenResult.DENIED)
        }
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    /** True while the shell service is connected and its virtual mouse is open: [mouseEvents] then sends. */
    val mouseReady: Boolean
        get() = !destroyed && remote != null && mouseOpened

    /** Shizuku runs and this app holds its permission, so opening needs no prompt. Main thread. */
    val permissionGranted: Boolean
        get() = !destroyed && runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pendingCommands.clear()
        // No callbacks after destroy: the owner is going away. (SHUTDOWN ends the touch reader too.)
        pendingRuns.clear()
        pendingMouseOpens.clear()
        touches = null
        resetMouse()
        worker.shutdownNow()
        stopPermissionPoll()
        mainHandler.removeCallbacks(bindTimeout)
        remote?.let(::shutdownRemote)
        if (Shizuku.pingBinder()) {
            // remove=false first: it is the call that empties and drops Shizuku's cached connection
            // object for this tag (remove=true leaves it), so a bridge made next binds afresh
            // instead of sharing callbacks with this destroyed one.
            runCatching { Shizuku.unbindUserService(args, connection, false) }
                .onFailure { Log.w(TAG, "unbindUserService (detach) failed", it) }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
                .onFailure { Log.w(TAG, "unbindUserService failed", it) }
        }
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        remote = null
        bindRequested = false
    }

    fun keyEvent(displayId: Int, keyCode: String): DispatchResult {
        val command = arrayOf(
            "/system/bin/input",
            "-d",
            displayId.toString(),
            "keyevent",
            keyCode,
        )
        if (remote != null) {
            submit(command)
            return DispatchResult.SENT
        }

        if (pendingCommands.size >= MAX_PENDING_COMMANDS) pendingCommands.removeFirst()
        pendingCommands.addLast(command)
        if (!ensureReady()) {
            pendingCommands.removeLast()
            return DispatchResult.UNAVAILABLE
        }
        return DispatchResult.QUEUED
    }

    /**
     * Runs [argv] in the shell service, on the worker like a key, and posts its exit code and output
     * to the main thread. Call on the main thread. Before Shizuku is connected the command waits like
     * [openMouse] (binding, or asking for the permission). It is answered [COMMAND_NOT_RUN] when
     * Shizuku is not there, its permission is refused or not answered in time, the service goes away
     * first or the worker cannot take it, and -1 when the call to the service fails. Never answered
     * after [destroy].
     */
    fun runCommand(argv: Array<String>, onResult: (exitCode: Int, output: String) -> Unit) {
        if (destroyed) return
        val run = PendingRun(argv.copyOf(), onResult)
        if (remote != null) {
            submitRun(run)
            return
        }
        pendingRuns += run
        startBinding()?.let(::failPendingMouseOpens)
    }

    /**
     * Opens the shell service's virtual uinput mouse (the DeX system cursor). Call on the main thread.
     * [onResult] is posted to the main thread with [MouseOpenResult.OPENED] when [mouseReady] became
     * true, or why not. Before Shizuku is connected the request waits (binding, or asking for the
     * permission, like [keyEvent]) and runs from onServiceConnected. It is answered
     * [MouseOpenResult.UNAVAILABLE] when Shizuku is not there or binding fails,
     * [MouseOpenResult.DENIED] / [MouseOpenResult.TIMEOUT] when the permission is refused / not
     * answered in time, [MouseOpenResult.DISCONNECTED] when the service goes away, [closeMouse]
     * cancels it, the worker cannot take it or the call fails, and [MouseOpenResult.SHELL_ERROR]
     * when the shell side reports an error. Never answered after [destroy].
     */
    fun openMouse(displayId: Int, onResult: (MouseOpenResult) -> Unit) {
        if (destroyed) return
        // The latest request names the display; the shell checks the pointer is there (PointerDisplayCheck).
        mouseDisplayId = displayId
        if (remote != null) {
            submitMouseOpen(listOf(onResult))
            return
        }
        pendingMouseOpens += onResult
        startBinding()?.let(::failPendingMouseOpens)
    }

    /**
     * Sends one batch of (type, code, value) triples to the virtual mouse as a oneway transaction on the
     * caller's thread (it does not block). Pointer motion is never queued: false means dropped, because
     * the mouse is not open or the transaction failed. The shell side drops batches its policy refuses,
     * and [MouseSource.AIR] batches without a button event for a while after a finger lands on the
     * touchscreen (true is still returned for those: the shell decides when the batch arrives).
     *
     * @param source a [MouseSource] constant: [MouseSource.AIR] for air-mouse motion that may wait,
     *   [MouseSource.PAD] for the touchpad and for anything sent on purpose during a press.
     */
    fun mouseEvents(events: IntArray, source: Int = MouseSource.OTHER): Boolean {
        val binder = remote
        if (binder == null || !mouseOpened || destroyed) return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RemoteShellProtocol.DESCRIPTOR)
            data.writeIntArray(events)
            data.writeInt(source)
            binder.transact(RemoteShellProtocol.TRANSACTION_MOUSE_EVENTS, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: DeadObjectException) {
            mouseOpened = false
            // Only the first caller to see this binder die handles it (batches may race here).
            if (remote === binder) {
                Log.w(TAG, "remote shell died during mouse events", e)
                onRemoteDied(binder)
            }
            false
        } catch (e: Exception) {
            if (mouseFailures++ % MOUSE_FAILURE_LOG_EVERY == 0) Log.w(TAG, "mouse events failed (#$mouseFailures)", e)
            false
        } finally {
            data.recycle()
        }
    }

    /**
     * Removes the virtual mouse (sent on the worker, so after any open still queued there) and answers a
     * waiting [openMouse] with [MouseOpenResult.DISCONNECTED]. Call on the main thread.
     */
    fun closeMouse() {
        resetMouse()
        answerPendingMouseOpens(MouseOpenResult.DISCONNECTED)
        if (destroyed || remote == null) return
        try {
            worker.execute(MouseTask {
                remote?.let { binder ->
                    val (code, message) = mouseCall(binder, RemoteShellProtocol.TRANSACTION_MOUSE_CLOSE)
                    if (code != 0) Log.w(TAG, "virtual mouse close failed ($code): $message")
                }
            })
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "virtual mouse close not queued", e)
        }
    }

    /**
     * Reads the phone's touchscreen from the kernel through the shell service: the touchpad's input
     * when the accessibility input filter would cancel the window's own touch events (see
     * [RawTouchReader]). Call on the main thread; it replaces an earlier subscription. Both callbacks
     * run on the main thread, in order, until [unsubscribeTouches] or [destroy].
     *
     * [onStatus] says whether raw frames flow: true once the shell reads the touchscreen; false when
     * Shizuku is not there, its permission is refused or not answered, the bind times out, the service
     * goes away, or the shell finds no readable touchscreen. It is only called when that changes, and
     * may change many times; until the first true the caller keeps its window touch events. A false
     * ends the frames: contacts the last frame had got no lift and count as cancelled (no empty frame
     * is made up for them, which would read as a lift and could click). After a later true, a finger
     * still down keeps its id.
     *
     * [onFrame] gets every report that added, moved or lifted a contact ([TouchFrame]).
     *
     * A subscription made before the service is connected binds, and is sent once it connects; it
     * never asks for the Shizuku permission (the virtual mouse's [openMouse] does that), so without
     * the permission it waits for the grant. It is sent again whenever the service connects anew,
     * but a lost service is not rebound for it alone: the next [openMouse], key or command, Shizuku
     * starting, or the permission being granted, binds again.
     */
    fun subscribeTouches(onFrame: (TouchFrame) -> Unit, onStatus: (available: Boolean) -> Unit) {
        if (destroyed) return
        unsubscribeTouches()
        val subscription = TouchSubscription(onFrame, onStatus)
        touches = subscription
        val binder = remote
        if (binder != null) {
            sendTouchSubscribe(binder, subscription)
            return
        }
        if (!permissionGranted || !bind()) postTouchStatus(subscription, false)
    }

    /** Ends the [subscribeTouches] in effect; no callback of it runs afterwards. Main thread. */
    fun unsubscribeTouches() {
        val subscription = touches ?: return
        touches = null
        val binder = subscription.subscribedTo ?: return
        if (binder !== remote) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(RemoteShellProtocol.DESCRIPTOR)
            data.writeStrongBinder(subscription.callback)
            binder.transact(RemoteShellProtocol.TRANSACTION_TOUCH_UNSUBSCRIBE, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            // A dead service has dropped the subscription with everything else.
            Log.w(TAG, "touch unsubscribe failed", e)
        } finally {
            data.recycle()
        }
    }

    /** Main thread. Oneway, so a service that cannot read the touchscreen answers with a status. */
    private fun sendTouchSubscribe(binder: IBinder, subscription: TouchSubscription) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(RemoteShellProtocol.DESCRIPTOR)
            data.writeStrongBinder(subscription.callback)
            binder.transact(RemoteShellProtocol.TRANSACTION_TOUCH_SUBSCRIBE, data, null, IBinder.FLAG_ONEWAY)
            subscription.subscribedTo = binder
        } catch (e: DeadObjectException) {
            Log.w(TAG, "remote shell died during touch subscribe", e)
            postTouchStatus(subscription, false)
            onRemoteDied(binder)
        } catch (e: Exception) {
            Log.w(TAG, "touch subscribe failed", e)
            postTouchStatus(subscription, false)
        } finally {
            data.recycle()
        }
    }

    /** Main thread. The service that had the subscription is gone; the next connection gets it again. */
    private fun touchesLost() {
        val subscription = touches ?: return
        subscription.subscribedTo = null
        postTouchStatus(subscription, false)
    }

    /** Posted like the other answers, so an owner's callback never runs inside a bridge call. */
    private fun postTouchStatus(subscription: TouchSubscription, available: Boolean) {
        mainHandler.post { if (!destroyed && touches === subscription) touchStatus(subscription, available) }
    }

    /** Main thread. Calls [TouchSubscription.onStatus] when [available] differs from the last one given. */
    private fun touchStatus(subscription: TouchSubscription, available: Boolean) {
        if (subscription.status == available) return
        subscription.status = available
        subscription.onStatus(available)
    }

    /** A [subscribeTouches] waits for the service. */
    private fun touchesWaiting(): Boolean = touches?.let { it.subscribedTo == null } == true

    /** Requests that are answered once each: they may bind again after a death. */
    private fun hasOneShotPending(): Boolean =
        pendingCommands.isNotEmpty() || pendingRuns.isNotEmpty() || pendingMouseOpens.isNotEmpty()

    private fun hasPending(): Boolean = hasOneShotPending() || touchesWaiting()

    /**
     * Main thread. Marks the mouse closed; an open answer still in flight is then reported as
     * [MouseOpenResult.DISCONNECTED].
     */
    private fun resetMouse() {
        mouseOpened = false
        mouseGeneration++
    }

    /**
     * What waited for the shell service will not get it, for [reason]: answers the waiting
     * [runCommand] calls ([COMMAND_NOT_RUN]) and [openMouse] requests, and tells a waiting
     * [subscribeTouches] that no frames flow.
     */
    private fun failPendingMouseOpens(reason: MouseOpenResult) {
        failPendingRuns(reason)
        answerPendingMouseOpens(reason)
        // A waiting touch subscription stays in effect; it only hears that frames do not flow.
        touches?.takeIf { it.subscribedTo == null }?.let { postTouchStatus(it, false) }
    }

    private fun failPendingRuns(reason: MouseOpenResult) {
        if (pendingRuns.isEmpty()) return
        val runs = pendingRuns.toList()
        pendingRuns.clear()
        mainHandler.post { if (!destroyed) runs.forEach { it.onResult(COMMAND_NOT_RUN, "not run: $reason") } }
    }

    /** Answers the waiting [openMouse] requests only: [closeMouse] cancels those, not [runCommand] calls. */
    private fun answerPendingMouseOpens(reason: MouseOpenResult) {
        if (pendingMouseOpens.isEmpty()) return
        val callbacks = pendingMouseOpens.toList()
        pendingMouseOpens.clear()
        mainHandler.post { if (!destroyed) callbacks.forEach { it(reason) } }
    }

    private fun submitMouseOpen(callbacks: List<(MouseOpenResult) -> Unit>) {
        val generation = mouseGeneration
        val displayId = mouseDisplayId
        val task = MouseTask {
            val binder = remote
            val (code, message) = if (binder == null) {
                MouseOpenResult.CALL_FAILED to "not connected"
            } else {
                mouseCall(binder, RemoteShellProtocol.TRANSACTION_MOUSE_OPEN, displayId)
            }
            if (code != 0) Log.w(TAG, "virtual mouse open failed ($code): ${message.trim()}")
            mainHandler.post {
                if (destroyed) return@post
                // A close, disconnect or reconnect since the request makes this answer stale.
                val stale = generation != mouseGeneration || remote !== binder
                val result = if (stale) MouseOpenResult.DISCONNECTED else MouseOpenResult.fromCallCode(code)
                if (result == MouseOpenResult.OPENED) mouseOpened = true
                callbacks.forEach { it(result) }
            }
        }
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "virtual mouse open not queued", e)
            mainHandler.post { if (!destroyed) callbacks.forEach { it(MouseOpenResult.DISCONNECTED) } }
        }
    }

    /**
     * A blocking mouse transaction on the worker. Returns the service's code + message (0 = ok), or
     * [MouseOpenResult.UNKNOWN_TRANSACTION] / [MouseOpenResult.CALL_FAILED] from this side.
     */
    private fun mouseCall(binder: IBinder, code: Int, arg: Int? = null): Pair<Int, String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RemoteShellProtocol.DESCRIPTOR)
            if (arg != null) data.writeInt(arg)
            // false: the service does not know this code (an older version is still running), and an
            // empty reply would otherwise read as code 0.
            if (!binder.transact(code, data, reply, 0)) {
                return MouseOpenResult.UNKNOWN_TRANSACTION to "unknown transaction $code"
            }
            reply.readException()
            if (code == RemoteShellProtocol.TRANSACTION_MOUSE_OPEN) {
                reply.readInt() to reply.readString().orEmpty()
            } else {
                0 to ""
            }
        } catch (e: DeadObjectException) {
            Log.w(TAG, "remote shell died during mouse call", e)
            onRemoteDied(binder)
            MouseOpenResult.CALL_FAILED to (e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            Log.w(TAG, "mouse call failed", e)
            MouseOpenResult.CALL_FAILED to (e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun ensureReady(): Boolean = startBinding() == null

    /**
     * Binds the shell service, or asks for the Shizuku permission (once; then polls for it). Null when
     * that is under way, otherwise why it cannot be: [MouseOpenResult.UNAVAILABLE] (no Shizuku, or
     * binding failed) or [MouseOpenResult.DENIED] (refused with "don't ask again").
     */
    private fun startBinding(): MouseOpenResult? {
        if (destroyed || !Shizuku.pingBinder()) return MouseOpenResult.UNAVAILABLE
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return if (bind()) null else MouseOpenResult.UNAVAILABLE
        }
        if (permissionPollRemaining > 0) return null
        if (Shizuku.shouldShowRequestPermissionRationale()) return MouseOpenResult.DENIED
        Shizuku.requestPermission(REQUEST_CODE)
        startPermissionPoll()
        return null
    }

    private fun startPermissionPoll() {
        mainHandler.removeCallbacks(permissionPoll)
        permissionPollRemaining = PERMISSION_POLL_ATTEMPTS
        mainHandler.postDelayed(permissionPoll, PERMISSION_POLL_INTERVAL_MS)
    }

    private fun stopPermissionPoll() {
        permissionPollRemaining = 0
        mainHandler.removeCallbacks(permissionPoll)
    }

    private fun bind(): Boolean {
        if (destroyed) return false
        if (remote != null || bindRequested) return true
        bindRequested = true
        return runCatching {
            Shizuku.bindUserService(args, connection)
            mainHandler.removeCallbacks(bindTimeout)
            mainHandler.postDelayed(bindTimeout, BIND_TIMEOUT_MS)
            true
        }.getOrElse {
            bindRequested = false
            Log.w(TAG, "bindUserService failed", it)
            false
        }
    }

    private fun flushPending() {
        while (!destroyed && remote != null && pendingCommands.isNotEmpty()) submit(pendingCommands.removeFirst())
        while (!destroyed && remote != null && pendingRuns.isNotEmpty()) submitRun(pendingRuns.removeAt(0))
        if (!destroyed && remote != null && pendingMouseOpens.isNotEmpty()) {
            val callbacks = pendingMouseOpens.toList()
            pendingMouseOpens.clear()
            submitMouseOpen(callbacks)
        }
        val waiting = touches?.takeIf { it.subscribedTo == null }
        if (!destroyed && waiting != null) remote?.let { sendTouchSubscribe(it, waiting) }
    }

    private fun submit(command: Array<String>) {
        if (destroyed) return
        try {
            worker.execute {
                val (code, output) = exec(command)
                if (code != 0) Log.w(TAG, "${command.joinToString(" ")} failed ($code): ${output.trim()}")
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "shell worker stopped", e)
        }
    }

    private fun submitRun(run: PendingRun) {
        if (destroyed) return
        val task = MouseTask {
            val (code, output) = exec(run.command)
            mainHandler.post { if (!destroyed) run.onResult(code, output) }
        }
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "command not queued", e)
            mainHandler.post { if (!destroyed) run.onResult(COMMAND_NOT_RUN, "shell worker full or stopped") }
        }
    }

    private fun shutdownRemote(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RemoteShellProtocol.DESCRIPTOR)
            binder.transact(RemoteShellProtocol.TRANSACTION_SHUTDOWN, data, reply, 0)
            reply.readException()
            Log.d(TAG, "remote shell shutdown requested")
        } catch (e: Exception) {
            Log.w(TAG, "remote shell shutdown failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun exec(command: Array<String>): Pair<Int, String> {
        val binder = remote ?: return -1 to "not connected"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RemoteShellProtocol.DESCRIPTOR)
            data.writeStringArray(command)
            binder.transact(RemoteShellProtocol.TRANSACTION_EXEC, data, reply, 0)
            reply.readException()
            reply.readInt() to reply.readString().orEmpty()
        } catch (e: DeadObjectException) {
            Log.w(TAG, "remote shell died during exec", e)
            onRemoteDied(binder)
            -1 to (e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            // One failed transaction does not mean the service is gone: keep the connection.
            Log.w(TAG, "remote exec failed", e)
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * The shell process behind [binder] is gone (any thread). Drop the binding too, so the next bind()
     * gets a fresh onServiceConnected instead of re-binding a connection Shizuku still holds; requests
     * that queued up meanwhile bind again. A touch subscription alone does not: a service that dies
     * on every start would otherwise be restarted for ever.
     */
    private fun onRemoteDied(binder: IBinder) {
        mouseOpened = false
        if (remote === binder) remote = null
        mainHandler.post {
            resetMouse()
            if (touches?.subscribedTo === binder) touchesLost()
            if (!destroyed && remote == null) {
                runCatching { Shizuku.unbindUserService(args, connection, false) }
                    .onFailure { Log.w(TAG, "unbindUserService after death failed", it) }
                bindRequested = false
                if (hasOneShotPending()) startBinding()?.let(::failPendingMouseOpens)
            }
        }
    }
}
