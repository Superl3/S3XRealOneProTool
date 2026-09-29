package com.raphael.handmouse.imu

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Reads the glasses' IMU over the USB network they expose (TCP 169.254.2.1:52998, no handshake —
 * see docs/superpowers/specs/2026-09-29-xreal-imu-protocol.md) on its own thread, and hands every
 * gyro/accel sample, already on the local clock ([ImuClock]), to [listener].
 *
 * The glasses' link-local network is not the default network, so the socket is made from that
 * [Network]'s socket factory (found like `Skarian/one-xr`: a network with a 169.254.x address).
 * The connection is retried every [RETRY_MS] while started — the glasses may be plugged in later.
 * The IMU streams while the Eye camera does (S25 Edge, 2026-09-29: 1000 Hz, nothing skipped).
 */
class XrealImuClient(
    context: Context,
    /** IMU thread, once per new failure: a retry that fails the same way is not reported again. */
    private val onFailure: (String) -> Unit,
    private val listener: Listener,
) {

    fun interface Listener {
        /** IMU thread. [localNs]: sample time on `System.nanoTime()`'s clock. */
        fun onImuSample(sample: ImuSample, localNs: Long)
    }

    companion object {
        private const val TAG = "XrealImu"
        const val HOST = "169.254.2.1"
        const val PORT = 52998
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val READ_TIMEOUT_MS = 700
        private const val RETRY_MS = 3000L
        private const val STATS_INTERVAL_NS = 10_000_000_000L

        /**
         * The app-log line for a connection failure. EPERM is a VPN that owns the app's traffic
         * refusing the bind to the glasses' network: under AdGuard a 4-minute recording's gyro
         * log came out empty with nothing on screen (S25 Edge, 2026-09-29).
         */
        fun failureMessage(failure: String): String =
            if ("EPERM" in failure) "안경 IMU 연결 실패: VPN이 막고 있습니다. VPN 앱(AdGuard 등)에서 이 앱을 제외해야 자이로 로그와 머리 보정이 동작합니다."
            else "Glasses IMU: $failure"
    }

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile private var thread: Thread? = null
    @Volatile private var socket: Socket? = null

    /** True while samples arrived within the last second (for the UI / logs). */
    @Volatile var isStreaming = false
        private set

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "XrealImu").apply { isDaemon = true; start() }
        Log.d(TAG, "IMU client started")
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        isStreaming = false
        Log.d(TAG, "IMU client stopped")
    }

    private fun run() {
        val me = Thread.currentThread()
        var lastFailure: String? = null
        while (thread === me) {
            val network = findGlassesNetwork()
            if (network == null) {
                if (lastFailure != "no network") Log.d(TAG, "No 169.254.x network (glasses USB network) yet")
                lastFailure = "no network"
            } else {
                try {
                    stream(network, me)
                    lastFailure = null
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    if (msg != lastFailure) {
                        Log.w(TAG, "IMU connection: $msg")
                        onFailure(msg)
                    }
                    lastFailure = msg
                }
            }
            isStreaming = false
            if (thread !== me) break
            try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun stream(network: Network, me: Thread) {
        val s = network.socketFactory.createSocket()
        socket = s
        s.use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            Log.i(TAG, "Connected to $HOST:$PORT")
            read(sock.getInputStream(), me)
        }
    }

    private fun read(input: InputStream, me: Thread) {
        val parser = XrealImuParser()
        val clock = ImuClock()
        val buf = ByteArray(4096)
        var samples = 0L
        var statsStart = System.nanoTime()
        var lastSampleNs = 0L
        var first = true
        while (thread === me) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                isStreaming = false
                continue
            }
            if (n < 0) throw java.io.EOFException("IMU stream closed by the glasses")
            val now = System.nanoTime()
            for (sample in parser.feed(buf, n)) {
                val local = clock.toLocal(sample.deviceTimeNs, now)
                if (first) {
                    first = false
                    Log.i(TAG, "First IMU report: gyro (%.4f, %.4f, %.4f) accel (%.3f, %.3f, %.3f) %.1f °C".format(
                        sample.gx, sample.gy, sample.gz, sample.ax, sample.ay, sample.az, sample.temperatureC))
                }
                listener.onImuSample(sample, local)
                samples++
                lastSampleNs = now
            }
            isStreaming = now - lastSampleNs < 1_000_000_000L
            if (now - statsStart >= STATS_INTERVAL_NS) {
                Log.d(TAG, "IMU %.0f Hz, skipped %d B".format(samples * 1e9 / (now - statsStart), parser.skippedBytes))
                samples = 0
                statsStart = now
            }
        }
    }

    /** The glasses' USB network: one with a 169.254.x link address, on the host's /24 first. */
    @Suppress("DEPRECATION") // allNetworks: the glasses' network is never the default one
    private fun findGlassesNetwork(): Network? {
        val candidates = connectivity.allNetworks.mapNotNull { n ->
            val lp = connectivity.getLinkProperties(n) ?: return@mapNotNull null
            val addrs = lp.linkAddresses.map { it.address.hostAddress.orEmpty() }
            val v4 = addrs.firstOrNull { it.startsWith("169.254.") } ?: return@mapNotNull null
            Triple(n, lp.interfaceName.orEmpty(), v4)
        }.sortedBy { it.second }
        return (candidates.firstOrNull { it.third.startsWith("169.254.2.") } ?: candidates.firstOrNull())?.first
    }
}
