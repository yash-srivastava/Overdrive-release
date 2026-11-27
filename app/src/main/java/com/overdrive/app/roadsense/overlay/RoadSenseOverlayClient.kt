package com.overdrive.app.roadsense.overlay

import android.os.SystemClock
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.roadsense.warn.OverlayState
import com.overdrive.app.roadsense.warn.RoadSenseOverlayChannel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * App-side reader of the daemon's live RoadSense overlay state ([RoadSenseOverlayChannel])
 * over one persistent connection to the loopback IPC. Each poll sends the last etag; an
 * unchanged state answers with only the publish time.
 *
 * Until the daemon has answered once, reads fall back to the legacy `roadSense.overlayState`
 * config key (an older daemon still writing it during an update, or the daemon not up yet).
 * After that the daemon no longer writes the key, so a failed poll keeps the last state and
 * its timestamp goes stale exactly like a daemon that stopped publishing.
 *
 * Not thread-safe: one instance per polling thread. [close] may be called from any thread.
 */
class RoadSenseOverlayClient {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null
    private var etag = ""
    private var cached: JSONObject? = null
    private var answered = false
    private var retryAtMs = 0L
    @Volatile private var closed = false

    /** The overlay state in the [OverlayState] codec, or null. Valid until the next call. */
    fun read(): JSONObject? {
        val now = SystemClock.elapsedRealtime()
        if (!closed && now >= retryAtMs) {
            // The daemon drops a connection idle for 30 s: a reused socket gets one retry on a fresh one.
            val reused = socket != null
            val reply = exchange() ?: if (reused) exchange() else null
            if (reply != null && reply.has("v")) {
                answered = true
                return apply(reply)
            }
            // no reply, or an older daemon without the command: try again shortly
            disconnect()
            retryAtMs = now + RETRY_MS
        }
        return if (answered) cached else legacy()
    }

    fun close() {
        closed = true
        disconnect()
    }

    private fun apply(reply: JSONObject): JSONObject? {
        if (!reply.has("ts")) {
            // nothing published since the daemon started: the last drive's state, as before
            cached = null
            etag = ""
            return legacy()
        }
        reply.optJSONObject("state")?.let { cached = it; etag = reply.optString("v") }
        val c = cached
        if (c == null || etag != reply.optString("v")) {
            // the daemon withheld a state we don't hold; refetch next poll
            etag = ""
            return c
        }
        c.put("ts", reply.optLong("ts"))
        return c
    }

    private fun exchange(): JSONObject? = try {
        if (socket == null) connect()
        val request = JSONObject().put("command", RoadSenseOverlayChannel.COMMAND).put("v", etag)
        out!!.write((request.toString() + "\n").toByteArray())
        out!!.flush()
        val line = reader!!.readLine() ?: throw java.io.EOFException()
        JSONObject(line)
    } catch (_: Throwable) {
        disconnect()
        null
    }

    private fun connect() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", DAEMON_IPC_PORT), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            reader = BufferedReader(InputStreamReader(s.getInputStream()))
            out = s.getOutputStream()
            socket = s
        } catch (t: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            throw t
        }
        // close() raced the connect: don't leave this socket behind
        if (closed) disconnect()
    }

    private fun disconnect() {
        val s = socket
        socket = null
        reader = null
        out = null
        try { s?.close() } catch (_: Throwable) {}
    }

    private fun legacy(): JSONObject? = try {
        UnifiedConfigManager.loadConfig().optJSONObject(OverlayState.SECTION)?.optJSONObject(OverlayState.KEY)
    } catch (_: Throwable) { null }

    companion object {
        private const val DAEMON_IPC_PORT = 19877
        private const val CONNECT_TIMEOUT_MS = 500
        private const val READ_TIMEOUT_MS = 1000
        private const val RETRY_MS = 5_000L
    }
}
