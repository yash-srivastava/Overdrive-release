package com.overdrive.app.remote

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * Private app service exposing an authenticated loopback socket to the shell
 * camera daemon. BYD's SELinux policy blocks shell -> app Unix-domain socket
 * connections, so each request is instead HMAC-authenticated with the existing
 * device secret, time-bounded, and replay-protected. Nothing is persisted and
 * no frame touches storage.
 */
class RemoteDevViewBridgeService : Service() {
    private val listener: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-dev-view-listener").apply { isDaemon = true }
    }
    // A fixed pool created by Executors.newFixedThreadPool has an unbounded
    // LinkedBlockingQueue. Loopback clients that connect and then stall can
    // therefore accumulate an unbounded number of queued sockets. Bound both
    // active and waiting clients, and explicitly close sockets on rejection.
    private val clients = ThreadPoolExecutor(
        CLIENT_THREADS,
        CLIENT_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(CLIENT_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "remote-dev-view-client").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val clientLock = Any()
    private val activeClients = HashSet<Socket>()
    @Volatile private var stopping = false
    private var listenerStarting = false
    @Volatile private var serverSocket: ServerSocket? = null
    private val usedNonces = LinkedHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        RemoteDevViewController.install(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureListening()
        // A service can be recreated while the Application stays alive.
        // Restore saved monitoring here as well as at process startup.
        com.overdrive.app.remoteaccess.KeyFobAccessMonitor.resumeIfEnabled(application)
        com.overdrive.app.remoteaccess.BluetoothAccessMonitor.resumeIfEnabled(application)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Neither monitor uses this bridge: both publish to the daemon over loopback
        // HTTP (DaemonHttpClient -> /api/remote-access/relay). Tearing them down here
        // meant that whenever the platform reaped this non-foreground START_NOT_STICKY
        // service, proximity sampling stopped and nothing re-armed it — the daemon's
        // bridge client can only connect to the socket, it cannot start the service.
        // The reported symptom was that unlock worked only while the proximity page
        // was open, because opening it is what issues the `apply` that restarts them.
        // Their real lifecycle is the persisted setting, which each collector loop
        // rechecks every tick, so an off/revoked feature still stops on its own.
        if (!com.overdrive.app.remoteaccess.RemoteAccessSettings.read().optBoolean("enabled")) {
            com.overdrive.app.remoteaccess.BluetoothAccessMonitor.stop()
            com.overdrive.app.remoteaccess.KeyFobAccessMonitor.stop()
        }
        RemoteDevVirtualDisplay.stop()
        synchronized(this) {
            stopping = true
            // Serialize with listener publication so a socket bound during
            // onDestroy cannot be published after the close check.
            try { serverSocket?.close() } catch (_: Exception) {}
        }
        synchronized(clientLock) {
            // Includes active handlers and sockets waiting in the bounded
            // queue. Closing them before shutdown unblocks reads and prevents
            // queued-but-never-run tasks from leaving accepted sockets open.
            activeClients.forEach { client ->
                try { client.close() } catch (_: Exception) {}
            }
            activeClients.clear()
        }
        listener.shutdownNow()
        clients.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun ensureListening() {
        if (stopping || serverSocket != null || listenerStarting) return
        listenerStarting = true
        try {
            listener.execute {
                var createdServer: ServerSocket? = null
                try {
                    val server = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), BACKLOG)
                    }
                    createdServer = server
                    val published = synchronized(this@RemoteDevViewBridgeService) {
                        if (stopping) false else {
                            serverSocket = server
                            true
                        }
                    }
                    if (!published) {
                        try { server.close() } catch (_: Exception) {}
                        return@execute
                    }
                    while (serverSocket === server && !stopping) {
                        val socket = try { server.accept() } catch (_: Exception) { break }
                        val tracked = synchronized(clientLock) {
                            if (stopping) false else activeClients.add(socket)
                        }
                        if (!tracked) {
                            try { socket.close() } catch (_: Exception) {}
                            break
                        }
                        try {
                            clients.execute {
                                try {
                                    handleClient(socket)
                                } finally {
                                    synchronized(clientLock) { activeClients.remove(socket) }
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            synchronized(clientLock) { activeClients.remove(socket) }
                            try { socket.close() } catch (_: Exception) {}
                            Log.w(TAG, "Bridge client capacity reached; rejected connection")
                        }
                    }
                } catch (error: Throwable) {
                    if (!stopping) Log.e(TAG, "Bridge listener stopped", error)
                } finally {
                    synchronized(this@RemoteDevViewBridgeService) {
                        if (serverSocket === createdServer) serverSocket = null
                        listenerStarting = false
                    }
                    try { createdServer?.close() } catch (_: Exception) {}
                }
            }
        } catch (error: RejectedExecutionException) {
            listenerStarting = false
            if (!stopping) Log.w(TAG, "Bridge listener could not be scheduled", error)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val response = JSONObject()
            var payload: ByteArray? = null
            try {
                val line = readLineLimited(client, MAX_REQUEST_BYTES)
                val authenticated = RemoteDevViewBridgeAuth.verify(line)
                rememberNonce(authenticated.nonce, authenticated.timestampMs)
                val request = authenticated.request
                when (request.optString("command", "")) {
                    "bluetoothStatus" -> {
                        // State only, including OFF: no SDK power read, reconciliation,
                        // phone search, connection request or monitor startup.
                        response.put("success", true)
                        response.put(
                            "bluetooth",
                            com.overdrive.app.monitor.BluetoothRadioState.readCurrent(),
                        )
                    }
                    "remoteAccess" -> {
                        val result = if (request.optString("operation") == "bluetoothKeepAlive") {
                            JSONObject().put("success", true).put(
                                "runtime",
                                com.overdrive.app.remoteaccess.SharedBluetoothKeepAliveController
                                    .reconcile(
                                        application,
                                        request.optBoolean("vehicleOff", false),
                                        request.optBoolean("vehicleOn", false),
                                        request.optBoolean("forceDisable", false),
                                        request.optBoolean("safeZoneDisable", false),
                                    ),
                            )
                        } else {
                            com.overdrive.app.remoteaccess.BluetoothAccessMonitor.command(
                                application, request,
                            )
                        }
                        val keys = result.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            response.put(key, result.get(key))
                        }
                    }
                    "launch" -> {
                        val launch = RemoteDevVirtualDisplay.start(application)
                        response.put("success", launch.success)
                        response.put("launchRequested", launch.success)
                        response.put("captureBackend", launch.backend)
                        response.put("compatibilityMode", launch.compatibilityMode)
                        response.put("displayId", launch.displayId)
                        if (launch.detail != null) response.put("detail", launch.detail)
                    }
                    "status" -> {
                        putInputResult(response, RemoteDevViewController.status())
                        response.put("captureBackend", RemoteDevVirtualDisplay.currentBackendName())
                        response.put(
                            "compatibilityMode",
                            RemoteDevVirtualDisplay.isCompatibilityMode(),
                        )
                        response.put("virtualDisplayRunning", RemoteDevVirtualDisplay.isRunning())
                        response.put("displayId", RemoteDevVirtualDisplay.displayId())
                    }
                    "stop" -> {
                        RemoteDevVirtualDisplay.stop()
                        response.put("success", true)
                        response.put("stopped", true)
                    }
                    "capture" -> {
                        val capture = RemoteDevViewController.capture(
                            request.optInt("maxWidth", DEFAULT_MAX_WIDTH)
                                .coerceIn(MIN_WIDTH, MAX_WIDTH),
                            request.optInt("quality", DEFAULT_QUALITY)
                                .coerceIn(MIN_QUALITY, MAX_QUALITY),
                            request.optString("format", "jpeg"),
                        )
                        response.put("success", capture.jpeg != null)
                        response.put("pixelCopyCode", capture.resultCode)
                        response.put("pixelCopyResult", capture.resultName)
                        response.put("width", capture.width)
                        response.put("height", capture.height)
                        response.put("activity", capture.activityName ?: JSONObject.NULL)
                        response.put("mimeType", capture.mimeType)
                        response.put("captureBackend", capture.backend)
                        response.put("frameSequence", capture.sequence)
                        if (capture.detail != null) response.put("detail", capture.detail)
                        payload = capture.jpeg
                    }
                    "touch" -> putInputResult(
                        response,
                        RemoteDevViewController.dispatchTouch(
                            request.optString("phase", ""),
                            request.optDouble("x", Double.NaN),
                            request.optDouble("y", Double.NaN),
                        ),
                    )
                    "key" -> putInputResult(
                        response,
                        RemoteDevViewController.dispatchKey(request.optString("key", "")),
                    )
                    "text" -> putInputResult(
                        response,
                        RemoteDevViewController.dispatchText(request.optString("text", "")),
                    )
                    else -> {
                        response.put("success", false)
                        response.put("error", "Unknown bridge command")
                    }
                }
            } catch (error: Throwable) {
                response.put("success", false)
                response.put("error", error.message ?: error.javaClass.simpleName)
            }

            writeResponse(client, response, payload)
        }
    }

    private fun writeResponse(client: Socket, response: JSONObject, payload: ByteArray?) {
        try {
            val bytes = payload ?: ByteArray(0)
            response.put("payloadBytes", bytes.size)
            val metadata = response.toString().toByteArray(StandardCharsets.UTF_8)
            val output = DataOutputStream(client.outputStream)
            output.writeInt(metadata.size)
            output.write(metadata)
            if (bytes.isNotEmpty()) output.write(bytes)
            output.flush()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to return bridge response", error)
        }
    }

    private fun putInputResult(target: JSONObject, result: RemoteDevViewController.InputResult) {
        target.put("success", result.success)
        target.put("handled", result.handled)
        target.put("activity", result.activityName ?: JSONObject.NULL)
        if (result.detail != null) target.put("detail", result.detail)
    }

    private fun readLineLimited(client: Socket, maxBytes: Int): String {
        val input = client.getInputStream()
        val output = ByteArrayOutputStream()
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_DEADLINE_MS)
        while (output.size() < maxBytes) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw SocketTimeoutException("Bridge request deadline exceeded")
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            client.soTimeout = minOf(SOCKET_TIMEOUT_MS.toLong(), remainingMs).toInt()
            val value = input.read()
            if (value == -1 || value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        if (output.size() >= maxBytes) throw IllegalArgumentException("Bridge request is too large")
        if (output.size() == 0) throw IllegalArgumentException("Empty bridge request")
        return output.toString(StandardCharsets.UTF_8.name())
    }

    @Synchronized
    private fun rememberNonce(nonce: String, timestampMs: Long) {
        val cutoff = System.currentTimeMillis() - RemoteDevViewBridgeAuth.MAX_CLOCK_SKEW_MS
        val iterator = usedNonces.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        if (usedNonces.put(nonce, timestampMs) != null) {
            throw SecurityException("Bridge request was already used")
        }
    }

    companion object {
        const val PORT = 19881

        private const val TAG = "RemoteDevViewBridge"
        private const val BACKLOG = 8
        private const val CLIENT_THREADS = 4
        private const val CLIENT_QUEUE_CAPACITY = 16
        private const val SOCKET_TIMEOUT_MS = 8_000
        private const val REQUEST_DEADLINE_MS = 8_000L
        private const val MAX_REQUEST_BYTES = 16 * 1024
        private const val MIN_WIDTH = 320
        private const val MAX_WIDTH = 1920
        private const val DEFAULT_MAX_WIDTH = 1280
        private const val MIN_QUALITY = 35
        private const val MAX_QUALITY = 90
        private const val DEFAULT_QUALITY = 72
    }
}
