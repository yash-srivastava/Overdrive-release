package com.overdrive.app.util

import com.overdrive.app.auth.AuthManager
import com.overdrive.app.daemon.CameraDaemon
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Native-side HTTP client for talking to the in-process daemon.
 *
 * Auto-attaches the JWT session cookie so requests are authenticated even when
 * the loopback bypass on the daemon side has been removed (post-tunnel-hardening).
 *
 * The JWT is generated on-demand via AuthManager and cached for a short window
 * to amortize HMAC cost across high-frequency callers (e.g. the status pill polling
 * /status at ~1Hz). Cache lifetime is well under the JWT's actual expiry, so a
 * stale cached value is never delivered.
 *
 * Always uses Proxy.NO_PROXY because sing-box may be running on this device — we
 * never want our own daemon traffic routed through a system proxy.
 */
object DaemonHttpClient {

    private val BASE_URL: String

        get() = "http://127.0.0.1:${CameraDaemon.HTTP_PORT}"
    // Cache JWT for ~4 minutes. JWT real expiry is 1 year, so this is a comfort
    // window for performance. We regenerate before this if AuthManager state was
    // invalidated externally (e.g. token regenerated via /auth API).
    private const val JWT_CACHE_TTL_MS = 4 * 60 * 1000L

    @Volatile private var cachedJwt: String? = null
    @Volatile private var cachedAt: Long = 0
    // Pin the cached JWT to the AuthManager state version it was minted from.
    // If the underlying secret reconciles to the daemon's value (fresh-install
    // race), AuthManager bumps stateVersion and we mint a new JWT immediately
    // instead of waiting out the TTL with a stale signature.
    @Volatile private var cachedStateVersion: Long = -1

    /**
     * Open an authenticated connection to a daemon endpoint.
     *
     * @param path URL path starting with "/" (e.g. "/api/performance/soh/reset")
     * @param method HTTP method ("GET", "POST", ...)
     * @param connectTimeoutMs connect timeout, default 3s
     * @param readTimeoutMs read timeout, default 5s
     */
    @JvmStatic
    @JvmOverloads
    fun open(
        path: String,
        method: String = "GET",
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 5000
    ): HttpURLConnection {
        val url = URL(BASE_URL + path)
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs

        attachAuth(conn)
        return conn
    }

    /**
     * Attach the daemon's JWT session cookie to an existing connection.
     * Public so callers that build their own HttpURLConnection (e.g. snapshot
     * decoders that need streaming response) can still authenticate.
     */
    @JvmStatic
    fun attachAuth(conn: HttpURLConnection) {
        val jwt = currentJwt() ?: return
        conn.setRequestProperty("Cookie", "byd_session=$jwt")
    }

    /** Force a refresh on next call (e.g. after token regeneration). */
    @JvmStatic
    fun invalidate() {
        cachedJwt = null
        cachedAt = 0
        cachedStateVersion = -1
    }

    private fun currentJwt(): String? {
        val now = System.currentTimeMillis()
        val cur = cachedJwt
        val curVersion = AuthManager.getStateVersion()
        if (cur != null && (now - cachedAt) < JWT_CACHE_TTL_MS && cachedStateVersion == curVersion) return cur

        // Mint a fresh JWT. AuthManager handles its own initialization race
        // (returns null on cold-start before deviceSecret is loaded). On null,
        // we don't cache — the next call will retry.
        val fresh = try {
            if (AuthManager.getState() == null) AuthManager.initialize()
            AuthManager.generateJwt()
        } catch (e: Exception) {
            null
        }
        if (fresh != null) {
            cachedJwt = fresh
            cachedAt = now
            cachedStateVersion = AuthManager.getStateVersion()
        }
        return fresh
    }
}
