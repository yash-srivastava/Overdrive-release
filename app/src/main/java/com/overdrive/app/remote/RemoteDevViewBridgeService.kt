package com.overdrive.app.remote

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.ui.MainActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DUMP-permission-protected, loopback-only bridge into Overdrive's UI process.
 * The shell camera daemon supplies a fresh in-memory secret every time it starts
 * the bridge. Nothing is persisted and no frame is written to storage.
 */
class RemoteDevViewBridgeService : Service() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-dev-view-bridge").apply { isDaemon = true }
    }
    @Volatile private var secret: String? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var lastAuthorizedAtElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        RemoteDevViewController.install(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val supplied = intent?.getStringExtra(EXTRA_BRIDGE_SECRET)
        if (supplied.isNullOrBlank() || supplied.length < 32) {
            Log.w(TAG, "Rejected bridge start without a valid ephemeral secret")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        secret = supplied
        lastAuthorizedAtElapsedMs = SystemClock.elapsedRealtime()
        ensureListening()
        if (intent.getBooleanExtra(EXTRA_LAUNCH_ACTIVITY, false)) launchOverdrive()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        secret = null
        try { serverSocket?.close() } catch (_: Exception) {}
        worker.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun ensureListening() {
        if (serverSocket?.isClosed == false) return
        worker.execute {
            try {
                val server = ServerSocket(PORT, 4, InetAddress.getByName(HOST))
                server.reuseAddress = true
                server.soTimeout = ACCEPT_POLL_MS
                serverSocket = server
                while (!server.isClosed) {
                    if (SystemClock.elapsedRealtime() - lastAuthorizedAtElapsedMs > BRIDGE_IDLE_MS) {
                        stopSelf()
                        break
                    }
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    handleClient(socket)
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
                val request = JSONObject(line)
                if (!secretMatches(request.optString("secret", ""))) {
                    response.put("success", false)
                    response.put("error", "Unauthorized bridge request")
                } else {
                    lastAuthorizedAtElapsedMs = SystemClock.elapsedRealtime()
                    when (request.optString("command", "")) {
                        "status" -> putInputResult(response, RemoteDevViewController.status())
                        "capture" -> {
                            val capture = RemoteDevViewController.capture(
                                request.optInt("maxWidth", DEFAULT_MAX_WIDTH)
                                    .coerceIn(MIN_WIDTH, MAX_WIDTH),
                                request.optInt("quality", DEFAULT_QUALITY)
                                    .coerceIn(MIN_QUALITY, MAX_QUALITY),
                            )
                            response.put("success", capture.jpeg != null)
                            response.put("pixelCopyCode", capture.resultCode)
                            response.put("pixelCopyResult", capture.resultName)
                            response.put("width", capture.width)
                            response.put("height", capture.height)
                            response.put("activity", capture.activityName ?: JSONObject.NULL)
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
                        "shutdown" -> {
                            response.put("success", true)
                            response.put("stopping", true)
                        }
                        else -> {
                            response.put("success", false)
                            response.put("error", "Unknown bridge command")
                        }
                    }
                }
            } catch (error: Throwable) {
                response.put("success", false)
                response.put("error", error.message ?: error.javaClass.simpleName)
            }

            try {
                val bytes = payload ?: ByteArray(0)
                response.put("payloadBytes", bytes.size)
                val metadata = response.toString().toByteArray(StandardCharsets.UTF_8)
                val output = DataOutputStream(client.getOutputStream())
                output.writeInt(metadata.size)
                output.write(metadata)
                if (bytes.isNotEmpty()) output.write(bytes)
                output.flush()
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to return bridge response", error)
            }

            if (response.optBoolean("stopping", false)) {
                secret = null
                stopSelf()
            }
        }
    }

    private fun launchOverdrive() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to launch MainActivity for developer view", error)
        }
    }

    private fun secretMatches(candidate: String): Boolean {
        val expected = secret ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8),
        )
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

    companion object {
        const val ACTION_START = "com.overdrive.app.remote.START_DEV_VIEW_BRIDGE"
        const val EXTRA_BRIDGE_SECRET = "bridge_secret"
        const val EXTRA_LAUNCH_ACTIVITY = "launch_activity"
        const val HOST = "127.0.0.1"
        const val PORT = 19881

        private const val TAG = "RemoteDevViewBridge"
        private const val SOCKET_TIMEOUT_MS = 8_000
        private const val ACCEPT_POLL_MS = 30_000
        private const val BRIDGE_IDLE_MS = 6 * 60 * 1000L
        private const val MAX_REQUEST_BYTES = 16 * 1024
        private const val MIN_WIDTH = 320
        private const val MAX_WIDTH = 1920
        private const val DEFAULT_MAX_WIDTH = 1280
        private const val MIN_QUALITY = 35
        private const val MAX_QUALITY = 90
        private const val DEFAULT_QUALITY = 72
    }
}
