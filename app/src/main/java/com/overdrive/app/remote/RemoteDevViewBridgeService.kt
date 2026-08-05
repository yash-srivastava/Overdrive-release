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
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
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
    private val clients: ExecutorService = Executors.newFixedThreadPool(CLIENT_THREADS) { runnable ->
        Thread(runnable, "remote-dev-view-client").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    private val usedNonces = LinkedHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        RemoteDevViewController.install(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureListening()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RemoteDevVirtualDisplay.stop()
        try { serverSocket?.close() } catch (_: Exception) {}
        listener.shutdownNow()
        clients.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun ensureListening() {
        if (serverSocket != null) return
        listener.execute {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), BACKLOG)
                }
                serverSocket = server
                while (serverSocket === server) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    clients.execute { handleClient(socket) }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Bridge listener stopped", error)
            } finally {
                serverSocket = null
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val response = JSONObject()
            var payload: ByteArray? = null
            try {
                val line = readLineLimited(client.getInputStream(), MAX_REQUEST_BYTES)
                val authenticated = RemoteDevViewBridgeAuth.verify(line)
                rememberNonce(authenticated.nonce, authenticated.timestampMs)
                val request = authenticated.request
                when (request.optString("command", "")) {
                    "launch" -> {
                        val launch = RemoteDevVirtualDisplay.start(application)
                        response.put("success", launch.success)
                        response.put("launchRequested", launch.success)
                        response.put("captureBackend", RemoteDevVirtualDisplay.BACKEND_NAME)
                        response.put("displayId", launch.displayId)
                        if (launch.detail != null) response.put("detail", launch.detail)
                    }
                    "status" -> {
                        putInputResult(response, RemoteDevViewController.status())
                        response.put("captureBackend", RemoteDevVirtualDisplay.BACKEND_NAME)
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

    private fun readLineLimited(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        while (output.size() < maxBytes) {
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
        private const val SOCKET_TIMEOUT_MS = 8_000
        private const val MAX_REQUEST_BYTES = 16 * 1024
        private const val MIN_WIDTH = 320
        private const val MAX_WIDTH = 1920
        private const val DEFAULT_MAX_WIDTH = 1280
        private const val MIN_QUALITY = 35
        private const val MAX_QUALITY = 90
        private const val DEFAULT_QUALITY = 72
    }
}
