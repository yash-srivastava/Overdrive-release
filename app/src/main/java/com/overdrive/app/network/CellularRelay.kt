package com.overdrive.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.overdrive.app.logging.LogManager
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.MessageDigest
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

    @Volatile private var server: ServerSocket? = null
    @Volatile private var accept: Thread? = null
    @Volatile private var cell: Network? = null
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cm: ConnectivityManager? = null

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
    /**
     * Per-session client credential. When non-null, SOCKS5 requires username/password
     * method 0x02 with this token as the password and HTTP requires
     * `Proxy-Authorization: Bearer <token>`. Set by HotspotManager from the persisted
     * hotspot.clientProxyToken. SECURITY (run-2 finding CellularRelay.kt:0.0.0.0:8121):
     * the relay was an unauthenticated open proxy on a wildcard bind.
     */
    @Volatile internal var authToken: String? = null   // internal for unit tests

    fun start(context: Context, authToken: String? = null) {
        synchronized(lifecycleLock) {
            if (running.get()) return
            val epoch = generation.incrementAndGet()
            running.set(true)
            this.authToken = authToken
            try {
                val ctx = context.applicationContext
                val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: throw IllegalStateException("no ConnectivityManager")
                cm = manager
                // Fail closed before requesting a radio network if the AP gateway
                // is unavailable. Any later startup failure is rolled back by stop().
                val bindAddr = apGatewayAddress()
                    ?: throw IllegalStateException("no AP gateway address; fail closed")
                val ss = ServerSocket()
                server = ss // publish before bind so even bind failures close this socket
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(bindAddr, PORT))
                requestCellular(manager, epoch)

                val t = Thread({ acceptLoop(ss, epoch) }, "cell-relay-accept").apply { isDaemon = true }
                accept = t
                t.start()
                log.info(TAG, "relay listening on ${bindAddr.hostAddress}:$PORT (AP bind, auth=${authToken != null})")
            } catch (t: Throwable) {
                log.warn(TAG, "relay start failed: ${t.message}")
                stop() // Closes listener and unregisters a partially established network request.
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
            authToken = null
            if (manager != null && cb != null) {
                try { manager.unregisterNetworkCallback(cb) } catch (_: Throwable) { }
            }
            log.info(TAG, "relay stopped")
        }
    }

    private fun isCurrent(epoch: Long): Boolean = running.get() && generation.get() == epoch

    /** Registration and stop share a lock so a stopped session cannot add new sockets. */
    private fun trackSocket(socket: Socket, epoch: Long): Boolean = synchronized(lifecycleLock) {
        if (!isCurrent(epoch)) false else openSockets.add(socket)
    }

    private fun closeTracked(socket: Socket) {
        openSockets.remove(socket)
        try { socket.close() } catch (_: Throwable) { }
    }


    // ==================== SECURITY helpers (run-2 fix) ====================

    private val AP_IFACE_HINTS = listOf("wlan", "ap", "softap", "p2p")

    /**
     * The SoftAP gateway address (the head unit's own address on the tethering
     * subnet, normally 192.168.43.1), or null when no AP interface address is
     * visible. Used as the relay's bind address so the port is reachable from
     * tethered clients but never from other interfaces.
     */
    internal fun apGatewayAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.asSequence()
                ?.filter { AP_IFACE_HINTS.any { h -> it.name.startsWith(h) } }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.mapNotNull { runCatching { InetAddress.getByAddress(it.address) }.getOrNull() }
                ?.firstOrNull { it.hostAddress?.startsWith("192.168.") == true }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Destination policy for relayed connections: internet egress only. Blocks
     * loopback, link-local, site-local (RFC1918), ULA (fc00::/7), multicast and
     * unspecified addresses so the relay cannot be used to reach the head unit
     * itself or other private networks. Pure function (unit-testable).
     */
    fun isAllowedDestination(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isMulticastAddress || addr.isSiteLocalAddress) {
            return false
        }
        if (addr is java.net.Inet6Address) {
            val b = addr.address
            if (b.isNotEmpty() && (b[0].toInt() and 0xFE) == 0xFC) return false   // fc00::/7 ULA
        }
        return true
    }

    /** Constant-time comparison of a client-supplied credential against the token. */
    fun authTokenMatches(provided: String?): Boolean {
        val expected = authToken ?: return true   // auth disabled only when never configured
        if (provided == null) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8))
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

        // SECURITY: when a client token is configured, plain proxying requires it.
        if (authToken != null) {
            val authLine = text.lineSequence()
                .firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
                ?: run {
                    client.getOutputStream().write(
                        "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Bearer realm=\"overdrive\"\r\nConnection: close\r\n\r\n".toByteArray())
                    client.getOutputStream().flush()
                    return null
                }
            val cred = authLine.substringAfter(':').trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            if (!authTokenMatches(cred)) {
                client.getOutputStream().write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nConnection: close\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                return null
            }
        }

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
        var offeredUserPass = false
        repeat(methodCount) {
            val m = input.read()
            if (m < 0) return null
            if (m == 0x02) offeredUserPass = true
        }
        // SECURITY: when a client token is configured, only username/password
        // (0x02) is acceptable — never fall back to no-auth.
        if (authToken != null) {
            out.write(byteArrayOf(0x05, if (offeredUserPass) 0x02 else 0xFF.toByte()))
            out.flush()
            if (!offeredUserPass) return null
            // RFC 1929 username/password sub-negotiation
            val ver = input.read()
            if (ver != 0x01) return null
            val ulen = input.read()
            if (ulen <= 0) return null
            val uname = ByteArray(ulen)
            if (!readFully(input, uname)) return null
            val plen = input.read()
            if (plen <= 0) return null
            val passwd = ByteArray(plen)
            if (!readFully(input, passwd)) return null
            val ok = authTokenMatches(String(passwd, Charsets.ISO_8859_1))
            out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
            out.flush()
            if (!ok) {
                log.warn(TAG, "SOCKS5 auth failed from ${client.inetAddress?.hostAddress}")
                return null
            }
        } else {
            out.write(byteArrayOf(0x05, 0x00))   // no authentication (legacy, token not configured)
            out.flush()
        }

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
