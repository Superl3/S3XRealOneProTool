package com.raphael.handmouse.remote

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

class RemoteShellBridge(private val context: Context) {
    /** [SENT]: handed to the worker thread (the result is logged there); [QUEUED]: waits for Shizuku. */
    enum class DispatchResult { SENT, QUEUED, UNAVAILABLE }

    companion object {
        private const val TAG = "RemoteShellBridge"
        private const val REQUEST_CODE = 0x5844
        private const val MAX_PENDING_COMMANDS = 4
        private const val PERMISSION_POLL_ATTEMPTS = 120
        private const val PERMISSION_POLL_INTERVAL_MS = 250L
    }

    @Volatile
    private var remote: IBinder? = null
    @Volatile
    private var bindRequested = false
    @Volatile
    private var destroyed = false
    private val pendingCommands = ArrayDeque<Array<String>>()

    // Every call into the shell service is a blocking Binder transaction that waits for a spawned
    // `input` process; it runs here, never on the main thread. Bounded: the oldest waiting key goes.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_COMMANDS),
        ThreadFactory { Thread(it, "RemoteShell").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionPollRemaining = 0
    private val permissionPoll = object : Runnable {
        override fun run() {
            if (destroyed || pendingCommands.isEmpty() || permissionPollRemaining <= 0) return
            permissionPollRemaining--
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind()
                return
            }
            mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MS)
        }
    }
    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, RemoteShellService::class.java.name),
        )
            .processNameSuffix("dex_remote")
            .daemon(false)
            .version(3) // 3: commands time out (RemoteCommandRunner); Shizuku restarts a service left at an older version
            .tag("dex_remote_shell")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindRequested = false
            if (destroyed) {
                service?.let(::shutdownRemote)
                runCatching { Shizuku.unbindUserService(args, this, true) }
                return
            }
            remote = service
            Log.d(TAG, "Shizuku remote shell connected")
            flushPending()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bindRequested = false
            pendingCommands.clear()
            Log.w(TAG, "Shizuku remote shell disconnected")
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (!destroyed && pendingCommands.isNotEmpty()) ensureReady()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        bindRequested = false
        pendingCommands.clear()
        stopPermissionPoll()
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        stopPermissionPoll()
        if (grantResult == PackageManager.PERMISSION_GRANTED && pendingCommands.isNotEmpty()) bind()
        else if (grantResult != PackageManager.PERMISSION_GRANTED) pendingCommands.clear()
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pendingCommands.clear()
        worker.shutdownNow()
        stopPermissionPoll()
        remote?.let(::shutdownRemote)
        if (Shizuku.pingBinder()) {
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

    private fun ensureReady(): Boolean {
        if (destroyed || !Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return bind()
        if (permissionPollRemaining > 0) return true
        if (Shizuku.shouldShowRequestPermissionRationale()) return false
        Shizuku.requestPermission(REQUEST_CODE)
        startPermissionPoll()
        return true
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
            true
        }.getOrElse {
            bindRequested = false
            Log.w(TAG, "bindUserService failed", it)
            false
        }
    }

    private fun flushPending() {
        while (!destroyed && remote != null && pendingCommands.isNotEmpty()) submit(pendingCommands.removeFirst())
    }

    private fun submit(command: Array<String>) {
        if (destroyed) return
        try {
            worker.execute {
                val (code, output) = exec(command)
                if (code != 0) Log.w(TAG, "${command.joinToString(" ")} failed ($code): ${output.trim()}")
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "shell worker stopped", e)
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
        } catch (e: Exception) {
            Log.w(TAG, "remote exec failed", e)
            remote = null
            bindRequested = false
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
