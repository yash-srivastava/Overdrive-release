package com.overdrive.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.overdrive.app.logging.LogManager
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP-CONNECT / SOCKS5 proxy for tethered clients whose OUTBOUND sockets are
 * explicitly bound to the CELLULAR network.
 *
 * Why this exists: on this head unit, tethered clients cannot reach the internet
 * by ordinary means. The carrier PDN carries no global IPv4 (only the RFC-7335
 * CLAT stub) and, while the AP is up, the device has no default network at all —
 * kernel NAT forwarding drops forwarded packets, and an unbound userspace proxy
 * fails with "missing default interface". Asking ConnectivityManager for the
 * cellular Network and binding each socket to it is the one path that works;
 * DNS is resolved on the same Network so NAT64 synthesis applies.
 *
 * Consequence for callers: this only serves clients configured to use a proxy.
 * It cannot make the platform's connectivity probe pass, so a client will still
 * show no "WiFi internet" indicator even while browsing through it.
 */
object CellularRelay {

    private const val TAG = "CellularRelay"

    /** Listen port advertised to clients. */
    const val PORT = 8121

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val IO_TIMEOUT_MS = 60_000
    private const val BUF = 16 * 1024

    /** Hard cap on concurrent tunnels; a parked head unit must not fork forever. */
    private const val MAX_TUNNELS = 64

    private val log = LogManager.getInstance()
    private val running = AtomicBoolean(false)
    // Admission is reserved by the accept thread, not by workers after dispatch.
    // This bounds both the active/queued work and the cached pool's thread count.
    private val admissions = RelayAdmissionGate(MAX_TUNNELS)
    private val lifecycleLock = Any()
    private val generation = AtomicLong(0L)
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    // Relayed traffic never traverses the AP interface as forwarded packets — it
    // terminates in this process and re-egresses on a cellular socket — so the
    // /proc counters for wlan0 cannot see it. Count it here instead, or the data
    // limit would never trip and the UI would sit at 0 B.
    private val relayedRx = java.util.concurrent.atomic.AtomicLong(0)
    private val relayedTx = java.util.concurrent.atomic.AtomicLong(0)

    // No credentials, so never every interface: loopback for sing-box's client tunnel,
    // plus the hotspot's own IPv4 once it has one (bindHotspot).
    @Volatile private var server: ServerSocket? = null
    @Volatile private var apServer: ServerSocket? = null
    @Volatile private var apAddress: String? = null
    @Volatile private var accept: Thread? = null
    @Volatile private var cell: Network? = null
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var lastRefusalLogMs = 0L

    private val pool: ThreadPoolExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "cell-relay").apply { isDaemon = true }
    } as ThreadPoolExecutor

    /** True while the listener is accepting. */
    fun isRunning(): Boolean = running.get()

    /**
     * Start the listener and hold a cellular network request. Idempotent.
     * Requesting the network keeps the radio's data call up even when the AP has
     * taken the WiFi interface, which is what makes the bound sockets routable.
     */
    fun start(context: Context) {
        synchronized(lifecycleLock) {
            if (running.get()) return
            val epoch = generation.incrementAndGet()
            running.set(true)
            try {
                val ctx = context.applicationContext
                val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: throw IllegalStateException("no ConnectivityManager")
                cm = manager
                val ss = ServerSocket()
                server = ss // publish before bind so even bind failures close this socket
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", PORT))
                requestCellular(manager, epoch)

                val t = Thread({ acceptLoop(ss, epoch) }, "cell-relay-accept").apply { isDaemon = true }
                accept = t
                t.start()
                log.info(TAG, "relay listening on 127.0.0.1:$PORT")
            } catch (t: Throwable) {
                log.warn(TAG, "relay start failed: ${t.message}")
                stop()
            }
        }
    }

    /** Revoke the session, close both ends of every tunnel and release the cellular request. */
    fun stop() {
        synchronized(lifecycleLock) {
            running.set(false)
            generation.incrementAndGet() // invalidate old accept threads and network callbacks
            try { server?.close() } catch (_: Throwable) { }
            server = null
            try { apServer?.close() } catch (_: Throwable) { }
            apServer = null
            apAddress = null
            accept = null
            // Closing sockets wakes blocking reads; otherwise tunnels could
            // continue relaying for up to IO_TIMEOUT_MS after hotspot shutdown.
            for (socket in openSockets) {
                try { socket.close() } catch (_: Throwable) { }
            }
            openSockets.clear()
            val manager = cm
            val cb = netCallback
            netCallback = null
            cm = null
            cell = null
            if (manager != null && cb != null) {
                try { manager.unregisterNetworkCallback(cb) } catch (_: Throwable) { }
            }
            log.info(TAG, "relay stopped")
        }
    }

    /** Also listen on the hotspot's own IPv4 (null closes that listener). Idempotent. */
    fun bindHotspot(address: String?) {
        synchronized(lifecycleLock) {
            if (!running.get()) return
            if (address == apAddress && apServer?.isClosed == false) return
            try { apServer?.close() } catch (_: Throwable) { }
            apServer = null
            apAddress = null
            if (address == null) return
            val epoch = generation.get()
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(address, PORT))
                apServer = ss
                apAddress = address
                Thread({ acceptLoop(ss, epoch) }, "cell-relay-accept-ap").apply { isDaemon = true }.start()
                log.info(TAG, "relay listening on $address:$PORT")
            } catch (t: Throwable) {
                try { ss.close() } catch (_: Throwable) { }
                log.warn(TAG, "relay hotspot bind failed on $address: ${t.message}")
            }
        }
    }

    /** True once the hotspot-address listener is up. */
    fun isHotspotBound(): Boolean = apServer?.isClosed == false

    private fun isCurrent(epoch: Long): Boolean = running.get() && generation.get() == epoch

    /** Registration and stop share a lock so a stopped session cannot add new sockets. */
    private fun trackSocket(socket: Socket, epoch: Long): Boolean = synchronized(lifecycleLock) {
        if (!isCurrent(epoch)) false else openSockets.add(socket)
    }

    private fun closeTracked(socket: Socket) {
        openSockets.remove(socket)
        try { socket.close() } catch (_: Throwable) { }
    }

    /** Endpoint string for the UI, e.g. "192.168.43.1:8121". */
    fun endpoint(gateway: String): String = "$gateway:$PORT"

    /** Bytes relayed this session: first = to clients, second = from clients. */
    fun relayedBytes(): Pair<Long, Long> = relayedRx.get() to relayedTx.get()

    /** Zero the relay counters, e.g. when a new hotspot session begins. */
    fun resetCounters() {
        relayedRx.set(0)
        relayedTx.set(0)
    }

    private fun requestCellular(manager: ConnectivityManager, epoch: Long) {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lifecycleLock) {
                    if (isCurrent(epoch)) {
                        cell = network
                        log.info(TAG, "cellular available for relay")
                    }
                }
            }
            override fun onLost(network: Network) {
                synchronized(lifecycleLock) {
                    if (isCurrent(epoch)) {
                        if (cell == network) cell = null
                        log.info(TAG, "cellular lost for relay")
                    }
                }
            }
        }
        netCallback = cb
        manager.requestNetwork(req, cb)
    }

    /**
     * Best cellular handle available. The callback is authoritative, but fall back
     * to a scan so a tunnel opened during the AP transition isn't refused just
     * because the callback hasn't landed yet.
     */
    private fun cellular(epoch: Long): Network? {
        if (!isCurrent(epoch)) return null
        cell?.let { return it }
        val manager = cm ?: return null
        return try {
            manager.allNetworks.firstOrNull { n ->
                val nc = manager.getNetworkCapabilities(n)
                nc != null &&
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }?.also { discovered ->
                synchronized(lifecycleLock) {
                    if (isCurrent(epoch)) cell = discovered
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun acceptLoop(ss: ServerSocket, epoch: Long) {
        while (isCurrent(epoch)) {
            val client = try {
                ss.accept()
            } catch (t: Throwable) {
                if (isCurrent(epoch) && ss !== server) {
                    // Only the hotspot listener ended (replaced, or its address went away):
                    // drop it and let the next hotspot sync bind again.
                    synchronized(lifecycleLock) { if (apServer === ss) { apServer = null; apAddress = null } }
                    try { ss.close() } catch (_: Throwable) { }
                    return
                }
                if (isCurrent(epoch)) {
                    log.warn(TAG, "accept failed: ${t.message}")
                    // A broken listener must not strand the cellular network request.
                    synchronized(lifecycleLock) { if (isCurrent(epoch)) stop() }
                }
                return
            }
            if (!isCurrent(epoch)) {
                try { client.close() } catch (_: Throwable) { }
                return
            }
            // (hotspot devices and this head unit only: not the car's own client Wi-Fi, nor a cellular address)
            if (!HotspotManager.isHotspotClient(client.inetAddress)) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRefusalLogMs > 60_000L) {
                    lastRefusalLogMs = now
                    log.warn(TAG, "refused a client outside the hotspot: ${client.inetAddress?.hostAddress}")
                }
                try { client.close() } catch (e: Throwable) { }
                continue
            }
            if (!admissions.tryAcquire()) {
                log.warn(TAG, "tunnel cap reached; dropping client")
                try { client.close() } catch (_: Throwable) { }
                continue
            }
            if (!trackSocket(client, epoch)) {
                admissions.release()
                try { client.close() } catch (_: Throwable) { }
                return
            }
            try {
                pool.execute { serve(client, epoch) }
            } catch (t: java.util.concurrent.RejectedExecutionException) {
                closeTracked(client)
                admissions.release()
                log.warn(TAG, "could not dispatch relay tunnel: ${t.message}")
            }
        }
    }

    private fun serve(client: Socket, epoch: Long) {
        var upstream: Socket? = null
        try {
            if (!isCurrent(epoch)) return
            client.soTimeout = IO_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val first = input.read()
            if (first < 0 || !isCurrent(epoch)) return
            upstream = if (first == 0x05) {
                socks5(client, input, epoch)
            } else {
                httpProxy(client, input, first, epoch)
            }
            if (upstream == null || !isCurrent(epoch)) return
            // Pump both directions; each side half-closes on EOF so neither peer
            // waits on its own timeout for a completed response.
            val up = upstream
            // rx = bytes delivered TO the client, tx = bytes sent by the client.
            val other = Thread({ pipe(up, client, relayedRx) }, "cell-relay-rx").apply { isDaemon = true }
            other.start()
            pipe(client, up, relayedTx)
            other.join(IO_TIMEOUT_MS.toLong())
        } catch (t: Throwable) {
            log.debug(TAG, "tunnel ended: ${t.message}")
        } finally {
            upstream?.let { closeTracked(it) }
            closeTracked(client)
            admissions.release()
        }
    }

    /**
     * Internet egress only. A loopback connection from the relay is trusted as this head unit by
     * AuthMiddleware, so a hotspot client must never reach 127.0.0.1 (or 0.0.0.0) through it.
     * Hotspot and other private addresses stay reachable: there the source is not loopback.
     */
    internal fun isAllowedDestination(addr: java.net.InetAddress): Boolean =
        !(addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isMulticastAddress)

    /** Resolve over cellular and track an in-progress connect for immediate shutdown. */
    private fun dial(host: String, port: Int, epoch: Long): Socket? {
        val net = cellular(epoch)
        if (net == null) {
            log.warn(TAG, "no cellular network; refusing $host:$port")
            return null
        }
        // Resolve ON the cellular network for NAT64-synthesised addresses.
        val addr = try { net.getAllByName(host).firstOrNull() } catch (t: Throwable) {
            log.warn(TAG, "DNS for $host failed: ${t.message}")
            null
        } ?: return null
        if (host.equals("localhost", ignoreCase = true) || !isAllowedDestination(addr)) {
            log.warn(TAG, "refusing non-public destination $host ($addr)")
            return null
        }
        val socket = Socket()
        if (!trackSocket(socket, epoch)) {
            try { socket.close() } catch (_: Throwable) { }
            return null
        }
        return try {
            net.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            if (!isCurrent(epoch)) {
                closeTracked(socket)
                null
            } else {
                socket
            }
        } catch (t: Throwable) {
            closeTracked(socket) // do not leak sockets when bind/connect fails
            log.warn(TAG, "dial $host:$port failed: ${t.message}")
            null
        }
    }

    // ==================== HTTP ====================

    /**
     * Handle CONNECT (tunnel) and absolute-URI GET/POST (plain proxy). [firstByte]
     * was already consumed by the caller's protocol sniff.
     */
    private fun httpProxy(client: Socket, input: InputStream, firstByte: Int, epoch: Long): Socket? {
        val head = StringBuilder().append(firstByte.toChar())
        var consecutive = 0
        while (consecutive < 2) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            head.append(c)
            when {
                c == '\n' -> consecutive++
                c == '\r' -> { /* part of the CRLF pair */ }
                else -> consecutive = 0
            }
            if (head.length > 32 * 1024) return null   // malformed / hostile
        }
        val text = head.toString()
        val requestLine = text.substringBefore("\r\n")
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        if (parts[0].equals("CONNECT", ignoreCase = true)) {
            val hp = parts[1]
            val idx = hp.lastIndexOf(':')
            if (idx <= 0) return null
            val host = hp.substring(0, idx)
            val port = hp.substring(idx + 1).toIntOrNull() ?: return null
            val up = dial(host, port, epoch) ?: run {
                client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                return null
            }
            client.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            return up
        }

        // Plain proxying: the request line carries an absolute URI.
        val uri = try { URI(parts[1]) } catch (t: Throwable) { null } ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        // Validate and remove proxy-only secrets BEFORE opening an outbound socket.
        val originRequest = originRequestWithoutProxyHeaders(text) ?: return null
        val up = dial(host, port, epoch) ?: run {
            client.getOutputStream().write(
                "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            return null
        }
        up.getOutputStream().write(originRequest.toByteArray(Charsets.ISO_8859_1))
        up.getOutputStream().flush()
        return up
    }

    /**
     * Strip proxy-only headers before forwarding a plain HTTP request to its origin.
     * Reject malformed/folded headers instead of risking a secret reaching the origin.
     * The CONNECT path never forwards its handshake to the destination.
     */
    internal fun originRequestWithoutProxyHeaders(head: String): String? {
        if (!head.endsWith("\r\n\r\n")) return null
        val lines = head.split("\r\n")
        if (lines.size < 3 || lines[0].isEmpty()) return null
        val out = StringBuilder().append(lines[0]).append("\r\n")
        for (i in 1 until lines.size - 2) {
            val line = lines[i]
            if (line.isEmpty() || line[0] == ' ' || line[0] == '\t') return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val field = line.substring(0, colon)
            // A space/tab before the colon is not a valid field name.
            if (field != field.trim()) return null
            if (!field.equals("Proxy-Authorization", ignoreCase = true) &&
                !field.equals("Proxy-Connection", ignoreCase = true)) {
                out.append(line).append("\r\n")
            }
        }
        return out.append("\r\n").toString()
    }

    // ==================== SOCKS5 ====================

    /** Minimal SOCKS5 CONNECT, no auth. The 0x05 version byte is already read. */
    private fun socks5(client: Socket, input: InputStream, epoch: Long): Socket? {
        val out = client.getOutputStream()
        val methodCount = input.read()
        if (methodCount <= 0) return null
        repeat(methodCount) { if (input.read() < 0) return null }
        out.write(byteArrayOf(0x05, 0x00))   // no authentication
        out.flush()

        if (input.read() != 0x05) return null
        val cmd = input.read()
        input.read()                          // reserved
        val type = input.read()
        val host: String = when (type) {
            0x01 -> {   // IPv4
                val b = ByteArray(4)
                if (!readFully(input, b)) return null
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {   // hostname
                val len = input.read()
                if (len <= 0) return null
                val b = ByteArray(len)
                if (!readFully(input, b)) return null
                String(b, Charsets.ISO_8859_1)
            }
            0x04 -> {   // IPv6
                val b = ByteArray(16)
                if (!readFully(input, b)) return null
                java.net.InetAddress.getByAddress(b).hostAddress ?: return null
            }
            else -> return null
        }
        val p = ByteArray(2)
        if (!readFully(input, p)) return null
        val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)

        // 0x01 == CONNECT; this relay intentionally supports nothing else.
        val up = if (cmd == 0x01) dial(host, port, epoch) else null
        if (up == null) {
            // reply=0x05 connection refused, with a zeroed BND address
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            return null
        }
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
        return up
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    // ==================== plumbing ====================

    /**
     * Copy until EOF then half-close, so the peer observes the end of stream.
     * Without the shutdown a client sits on its own timeout even though the whole
     * response already arrived.
     */
    private fun pipe(from: Socket, to: Socket, counter: java.util.concurrent.atomic.AtomicLong?) {
        try {
            val i: InputStream = from.getInputStream()
            val o: OutputStream = to.getOutputStream()
            val buf = ByteArray(BUF)
            while (true) {
                val r = i.read(buf)
                if (r <= 0) break
                o.write(buf, 0, r)
                o.flush()
                counter?.addAndGet(r.toLong())
            }
        } catch (t: Throwable) {
            // Normal when either end closes.
        }
        try { to.shutdownOutput() } catch (t: Throwable) { }
        try { from.shutdownInput() } catch (t: Throwable) { }
    }
}
