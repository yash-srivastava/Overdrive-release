package com.overdrive.app.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ResultReceiver
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.LogManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.overdrive.app.util.ScratchPaths

/**
 * Single app-wide owner of the WiFi-hotspot feature: AP state, the SoftAp
 * callback registration, the tethered-client map, the session counters and the
 * data-cap watcher.
 *
 * Consumers (the native fragment, the HTTP API) only read [snapshot] and call
 * [enable] / [disable] / [applySettings]. There is deliberately no polling loop
 * anywhere else — a page-owned loop racing this one would fight over the AP.
 *
 * SINGLE-RADIO CONTRACT: SoftAP and WiFi-STA are mutually exclusive on this
 * chipset, so bringing the AP up tears down the station link. Four keep-alive
 * sites would re-enable WiFi within seconds and kill the AP, so the WiFi
 * keep-alive suppression flag is set BEFORE start and only cleared on stop when
 * the hotspot is the one that set it.
 */
object HotspotManager {

    private const val TAG = "HotspotManager"

    // getWifiApState() values on this firmware.
    const val AP_DISABLED = 11
    const val AP_ENABLING = 12
    const val AP_ENABLED = 13
    const val AP_FAILED = 14

    /** ConnectivityManager.TETHERING_WIFI. */
    private const val TETHERING_WIFI = 0

    /** Tethering result-receiver contract: 0 == SUCCESS. */
    private const val TETHER_RESULT_SUCCESS = 0

    /** Package name the framework attributes the tethering request to. */
    // NOTE: must be OUR package. AppOps resolves the name against the calling UID
    // ("Specified package X under uid N but it is really M"), and this runs in the
    // app process — passing a shell package name makes startTethering throw.

    /** Ceiling on the tether-result wait; polled, not parked. See startTethering. */
    private const val TETHER_RESULT_TIMEOUT_MS = 12_000L

    /** Interfaces the AP can land on here; ARP rows on anything else are the
     *  station link or USB and must not be listed as hotspot clients. */
    private val AP_IFACE_HINTS = listOf("wlan", "ap", "softap", "p2p")

    private const val SAMPLE_INTERVAL_MS = 5_000L
    private const val STATE_PUBLISH_INTERVAL_MS = 15_000L

    /** Persist the running total every 8 MB so a process kill loses at most that
     *  much from the data limit, without rewriting the config every tick. */
    private const val USAGE_CHECKPOINT_BYTES = 8L * 1024L * 1024L

    private val log = LogManager.getInstance()

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hotspot-mgr").apply { isDaemon = true }
    }
    private var callbackThread: HandlerThread? = null

    @Volatile private var appContext: Context? = null
    private val started = AtomicBoolean(false)
    private val bootAutoStartArmed = AtomicBoolean(false)

    // ---- Live state (guarded by `lock`) ----
    private val lock = Any()
    private var apState: Int = AP_DISABLED
    private var startedAtMs: Long = 0L
    private var tetherIface: String? = null
    private var baselineRx: Long = -1L
    private var baselineTx: Long = -1L
    private var sessionRx: Long = 0L
    private var sessionTx: Long = 0L
    /** How much of the current session is already inside the persisted
     *  cumulative total, so periodic checkpoints never double-count. */
    private var foldedBytes: Long = 0L
    private var lastError: String? = null
    private var capTripped: Boolean = false
    /** Null until an AP-config write has been attempted; false if the OS refused it. */
    private var apConfigWritable: Boolean? = null
    /** Latched once the WRITE_SETTINGS app-op has been granted this process. */
    @Volatile private var appopGranted: Boolean = false
    /** Last applied state of the client tunnel inbound, to avoid pointless restarts. */
    @Volatile private var clientTunnelApplied: Boolean = false

    // The vehicle owns the AP name and key and refuses writes, so they cannot change
    // under us. Cache them: snapshot() runs every 5s while the screen is open and
    // each read is a shell round-trip over self-ADB.
    @Volatile private var cachedSsid: String? = null
    @Volatile private var cachedApPassword: String? = null
    // Latched once a lookup has been attempted, even if it found nothing: a vehicle
    // without these properties must not re-run the shell probe on every snapshot.
    @Volatile private var credsResolved: Boolean = false
    /** MAC (lower-case) -> client row. Populated by SoftAp callbacks, enriched
     *  with IPs from the ARP table. */
    private val clients = LinkedHashMap<String, ClientRow>()
    private var softApCallback: Any? = null
    private var sampler: java.util.concurrent.ScheduledFuture<*>? = null
    private var lastStatePublishMs: Long = 0L

    data class ClientRow(
        val mac: String,
        var ip: String? = null,
        var name: String? = null,
        val firstSeenMs: Long = System.currentTimeMillis(),
        var lastSeenMs: Long = System.currentTimeMillis(),
    )

    /**
     * Attach the process context and restore persisted state. Restoration runs
     * BEFORE the SoftAp callback is registered so a restored value is never
     * mistaken for a user action. Does NOT auto-start — see [armBootAutoStart],
     * which only the boot path calls so a passive broadcast can't trigger it.
     */
    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        // Cheap and idempotent: opening the screen must populate the credentials even
        // when the manager was already started and the hotspot has never run.
        if (!credsResolved) worker.execute { resolveCredentialsOnce(); publishState() }
        if (!started.compareAndSet(false, true)) return
        worker.execute {
            try {
                // Off the snapshot path: one shell probe here instead of three per
                // 5s tick while the Network screen is open.
                resolveCredentialsOnce()
                synchronized(lock) { apState = readApState() }
                if (apState == AP_ENABLED) {
                    // The AP survived a process restart; adopt it rather than
                    // reporting a fresh session with a zeroed uptime. The SoftAp
                    // listener is only worth its thread while an AP is actually
                    // up — with the AP off the sampler's readApState() is the
                    // documented fallback, so registration is deferred to
                    // enableInternal.
                    registerSoftApCallback()
                    adoptRunningSession()
                    ensureSampler()
                }
            } catch (t: Throwable) {
                log.warn(TAG, "init failed: ${t.message}")
            }
        }
    }

    /**
     * One-shot at boot / power-up: honour the autoStartBoot switch. Separate
     * from [init] and latched per process so repeated boot-ish broadcasts can't
     * re-fire it.
     */
    @JvmStatic
    fun armBootAutoStart(context: Context) {
        if (!bootAutoStartArmed.compareAndSet(false, true)) return
        // This runs on EVERY ACC-on, so decide with a cached config read before
        // touching init(): a device that never uses the hotspot must not pay for
        // the worker thread, the callback thread or the SoftAp registration.
        val wantAutoStart = try {
            UnifiedConfigManager.getHotspot().optBoolean("autoStartBoot", false)
        } catch (t: Throwable) {
            false
        }
        if (!wantAutoStart) return
        init(context)
        worker.execute {
            try {
                if (readApState() == AP_ENABLED) return@execute
                log.info(TAG, "auto-start at boot armed; enabling hotspot")
                enableInternal(null)
            } catch (t: Throwable) {
                log.warn(TAG, "armBootAutoStart failed: ${t.message}")
            }
        }
    }

    // ==================== Public API ====================

    /** Current observed state + settings as JSON. Cheap after the first call: the
     *  AP credentials are cached (the vehicle owns them and refuses writes), so
     *  repeated snapshots do no shell work. */
    @JvmStatic
    fun snapshot(): org.json.JSONObject {
        val cfg = try { UnifiedConfigManager.getHotspot() } catch (t: Throwable) { org.json.JSONObject() }
        val out = org.json.JSONObject()
        synchronized(lock) {
            out.put("apState", apState)
            out.put("enabled", apState == AP_ENABLED)
            out.put("transitioning", apState == AP_ENABLING)
            out.put("iface", tetherIface ?: "")
            out.put("uptimeSeconds", if (startedAtMs > 0L) (System.currentTimeMillis() - startedAtMs) / 1000L else 0L)
            out.put("rxBytes", sessionRx)
            out.put("txBytes", sessionTx)
            out.put("capTripped", capTripped)
            out.put("lastError", lastError ?: "")
            val arr = org.json.JSONArray()
            clients.values.forEach { c ->
                arr.put(org.json.JSONObject().apply {
                    put("mac", c.mac)
                    put("ip", c.ip ?: "")
                    put("name", c.name ?: friendlyName(c.mac))
                    put("connectedSeconds", (System.currentTimeMillis() - c.firstSeenMs) / 1000L)
                })
            }
            out.put("clients", arr)
            out.put("clientCount", clients.size)
        }
        // Prefer the name the radio really uses; the stored value is only the request.
        // Resolved once and reused below — this is a cached read, but calling it
        // twice per snapshot would still be pointless work.
        // Cached-only reads: the resolver owns the shell probe (worker thread), so a
        // 5s snapshot tick can never turn into a shell round-trip.
        val liveSsid = cachedSsid
        out.put("ssid", liveSsid ?: cfg.optString("ssid", ""))
        out.put("requestedSsid", cfg.optString("ssid", ""))
        out.put("hasPassword", cfg.optString("password", "").isNotEmpty())
        out.put("dataCapMb", cfg.optLong("dataCapMb", 0L))
        out.put("dataUsedBytes", cfg.optLong("dataUsedBytes", 0L))
        out.put("proxySystemWide", cfg.optBoolean("proxySystemWide", false))
        out.put("proxyForClients", cfg.optBoolean("proxyForClients", false))
        out.put("autoStartBoot", cfg.optBoolean("autoStartBoot", false))
        out.put("keepAlive", cfg.optBoolean("keepAlive", false))
        out.put("warnAck", cfg.optBoolean("warnAck", false))
        // Report the live gateway so the client-proxy hint matches reality if the
        // framework ever picks a subnet other than the usual 192.168.43.x.
        out.put("gateway", apSubnetPrefix()?.let { "$it.1" } ?: AP_GATEWAY)
        out.put("proxyPort", com.overdrive.app.daemon.proxy.ProxyConfiguration.PROXY_PORT)
        // Cellular-bound relay: the only path that gives clients internet on this
        // PDN. Report the exact host:port to enter as the client's WiFi proxy.
        out.put("relayPort", CellularRelay.PORT)
        out.put("relayRunning", CellularRelay.isRunning())
        out.put("clientTunnel", cfg.optBoolean("clientTunnel", false))
        out.put("clientTunnelPort",
            com.overdrive.app.daemon.proxy.ProxyConfiguration.CLIENT_TUNNEL_PORT)
        // What the radio actually broadcasts, plus whether this firmware lets us
        // change it. The AP name is owned by the OEM unless the write is permitted,
        // so the UI must show the real name rather than an intent we couldn't apply.
        out.put("activeSsid", liveSsid ?: "")
        // The OEM's own key, readable even though it cannot be written. Surfaced so
        // the user can actually join the AP instead of guessing.
        out.put("activePassword", cachedApPassword ?: "")
        synchronized(lock) {
            apConfigWritable?.let { out.put("ssidWritable", it) }
        }
        return out
    }

    /**
     * Turn the hotspot on. Runs off the caller's thread; [onResult] is invoked
     * on the manager's worker with (ok, message).
     */
    @JvmStatic
    fun enable(onResult: ((Boolean, String) -> Unit)?) {
        worker.execute { enableInternal(onResult) }
    }

    /** Turn the hotspot off and restore the WiFi keep-alive if we suppressed it. */
    @JvmStatic
    fun disable(onResult: ((Boolean, String) -> Unit)?) {
        worker.execute { disableInternal("user", onResult) }
    }

    /**
     * Persist settings from the UI. Each key is written independently so the two
     * persisted switches (keepAlive, autoStartBoot) never overwrite each other.
     * Side effects (proxy re-application) are applied for the keys present.
     */
    @JvmStatic
    fun applySettings(values: Map<String, Any>, onResult: ((Boolean, String) -> Unit)?) {
        worker.execute {
            try {
                if (values.isNotEmpty()) UnifiedConfigManager.updateHotspot(values)
                // Renaming/repasswording the AP only takes effect on the next
                // start; pushing it live would drop every connected client.
                if (values.containsKey("ssid") || values.containsKey("password")) {
                    applyApConfiguration()
                }
                if (values.containsKey("proxySystemWide")) {
                    applySystemWideProxy(values["proxySystemWide"] == true)
                }
                // Toggling either client-path switch rewrites the sing-box config,
                // so the tunnel inbound appears or DISAPPEARS immediately rather
                // than lingering until the next hotspot cycle.
                if (values.containsKey("proxyForClients") || values.containsKey("clientTunnel")) {
                    syncClientRelay()
                    syncClientTunnel()
                }
                if (values.containsKey("dataCapMb")) {
                    // A raised cap must un-trip the latch or the hotspot could
                    // never be re-enabled without a process restart.
                    synchronized(lock) { capTripped = false }
                }
                onResult?.invoke(true, "saved")
            } catch (t: Throwable) {
                log.warn(TAG, "applySettings failed: ${t.message}")
                onResult?.invoke(false, t.message ?: "failed")
            }
        }
    }

    /**
     * Re-apply side effects from whatever is currently persisted. Used when the
     * settings were written by ANOTHER process (the daemon's HTTP handler), so
     * this process never saw which keys moved.
     */
    @JvmStatic
    fun reapplyPersistedSettings() {
        worker.execute {
            try {
                UnifiedConfigManager.forceReload()
                applyApConfiguration()
                applySystemWideProxy(UnifiedConfigManager.isHotspotProxySystemWide())
                syncClientRelay()
                syncClientTunnel()
                // A raised cap must un-trip the latch, else the hotspot could
                // never be re-enabled without a process restart.
                if (!capReached()) synchronized(lock) { capTripped = false }
                publishState()
            } catch (t: Throwable) {
                log.warn(TAG, "reapplyPersistedSettings failed: ${t.message}")
            }
        }
    }

    /**
     * Start or stop the cellular-bound client relay to match the opt-in and the
     * live AP state. The relay only makes sense while an AP is serving, and it
     * holds a cellular network request, so it must not linger once the AP is down.
     */
    private fun syncClientRelay() {
        val wanted = try {
            UnifiedConfigManager.isHotspotProxyForClients()
        } catch (t: Throwable) {
            false
        }
        val apUp = synchronized(lock) { apState == AP_ENABLED || apState == AP_ENABLING }
        val ctx = appContext
        if (wanted && apUp && ctx != null) {
            if (!CellularRelay.isRunning()) CellularRelay.start(ctx)
        } else if (CellularRelay.isRunning()) {
            CellularRelay.stop()
        }
    }

    /**
     * Rewrite the sing-box config so the tethered-client tunnel inbound is present
     * only while it is wanted, then restart sing-box to pick it up.
     *
     * Turning it OFF is the important direction: the inbound must stop listening
     * and the tunnel definition must leave the config on disk, so a client that
     * still has the proxy set simply fails to connect instead of being routed
     * through a stale path. Restarting also drops any established tunnels.
     */
    private fun syncClientTunnel() {
        val ctx = appContext ?: return
        val wanted = try {
            UnifiedConfigManager.isHotspotProxyForClients() &&
                UnifiedConfigManager.isHotspotClientTunnel()
        } catch (t: Throwable) {
            false
        }
        val apUp = synchronized(lock) { apState == AP_ENABLED || apState == AP_ENABLING }
        val enable = wanted && apUp && CellularRelay.isRunning()
        if (enable == clientTunnelApplied) return
        val relayPort = if (enable) CellularRelay.PORT else null
        val cfg = try {
            com.overdrive.app.daemon.proxy.ProxyConfiguration.createConfig(
                clientRelaySocksPort = relayPort
            )
        } catch (t: Throwable) {
            log.warn(TAG, "client tunnel config build failed: ${t.message}")
            return
        }
        val cfgPath = com.overdrive.app.daemon.proxy.ProxyConfiguration.CONFIG_PATH
        val bin = com.overdrive.app.daemon.proxy.ProxyConfiguration.SINGBOX_PATH
        // Match on the config path, never the binary name: `pkill -f sing-box`
        // would also kill anything else launched from the same binary.
        val script = buildString {
            append("cat > $cfgPath <<'__OD_SB_EOF__'\n")
            append(cfg)
            append("\n__OD_SB_EOF__\n")
            append("MY_PID=\$\$\n")
            append("ps -A -o PID,ARGS 2>/dev/null | grep -F '$cfgPath' | grep -v grep")
            append(" | awk '{print \$1}' | while read pid; do\n")
            append("  if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi\n")
            append("done\n")
            append("if [ -f " + ScratchPaths.getDir() + "/singbox.disabled ]; then echo configured-stopped; exit 0; fi\n")
            append("test -x $bin || exit 3\n")
            append("nohup $bin run -c $cfgPath >/dev/null 2>&1 &\n")
            // `nohup ... &` reports success on fork, so verify the process survived —
            // a bad config or a bind clash would otherwise be reported as applied
            // while the SHARED :8119 proxy is actually down.
            append("sleep 2\n")
            append("ps -A -o ARGS 2>/dev/null | grep -F '$cfgPath' | grep -qv grep && echo applied\n")
        }
        val out = runShellScript(script)
        val ok = out != null &&
            (out.contains("applied") || out.contains("configured-stopped"))
        if (ok) clientTunnelApplied = enable
        log.info(TAG, "client tunnel ${if (enable) "enabled" else "disabled"} -> $ok")
    }

    /**
     * Force the tunnel inbound out of the config, regardless of the persisted
     * opt-in. Used on teardown paths where the AP is already going away: the
     * user's preference must stay as it is, but nothing may keep listening.
     */
    private fun syncClientTunnelOff() {
        if (!clientTunnelApplied) return
        val cfg = try {
            com.overdrive.app.daemon.proxy.ProxyConfiguration.createConfig()
        } catch (t: Throwable) {
            log.warn(TAG, "tunnel-off config build failed: ${t.message}")
            return
        }
        val cfgPath = com.overdrive.app.daemon.proxy.ProxyConfiguration.CONFIG_PATH
        val bin = com.overdrive.app.daemon.proxy.ProxyConfiguration.SINGBOX_PATH
        val script = buildString {
            append("cat > $cfgPath <<'__OD_SB_EOF__'\n")
            append(cfg)
            append("\n__OD_SB_EOF__\n")
            append("MY_PID=\$\$\n")
            append("ps -A -o PID,ARGS 2>/dev/null | grep -F '$cfgPath' | grep -v grep")
            append(" | awk '{print \$1}' | while read pid; do\n")
            append("  if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi\n")
            append("done\n")
            append("if [ -f " + ScratchPaths.getDir() + "/singbox.disabled ]; then echo cleared-stopped; exit 0; fi\n")
            append("test -x $bin || exit 3\n")
            append("nohup $bin run -c $cfgPath >/dev/null 2>&1 &\n")
            append("sleep 2\n")
            append("ps -A -o ARGS 2>/dev/null | grep -F '$cfgPath' | grep -qv grep && echo cleared\n")
        }
        val out = runShellScript(script)
        val ok = out != null &&
            (out.contains("cleared") || out.contains("cleared-stopped"))
        // Only drop the flag once the rewrite is confirmed, so a failed teardown is
        // retried rather than remembered as done while :8122 still listens.
        if (ok) clientTunnelApplied = false
        log.info(TAG, "client tunnel cleared -> $ok")
    }

    /** Zero the session counters and the persisted cumulative usage. */
    @JvmStatic
    fun resetUsage(onResult: ((Boolean, String) -> Unit)?) {
        worker.execute {
            try {
                synchronized(lock) {
                    sessionRx = 0L
                    sessionTx = 0L
                    foldedBytes = 0L
                    capTripped = false
                    // Zero the relay tally too: it is the dominant component here, so
                    // without this the next tick restores the pre-reset figure.
                    try { CellularRelay.resetCounters() } catch (t: Throwable) { }
                    val iface = tetherIface
                    if (iface != null) {
                        val now = readIfaceCounters(iface)
                        if (now != null) {
                            baselineRx = now.first
                            baselineTx = now.second
                        }
                    }
                }
                UnifiedConfigManager.updateHotspot(mapOf("dataUsedBytes" to 0L))
                onResult?.invoke(true, "reset")
            } catch (t: Throwable) {
                onResult?.invoke(false, t.message ?: "failed")
            }
        }
    }

    // ==================== Enable / disable ====================

    private fun enableInternal(onResult: ((Boolean, String) -> Unit)?) {
        val ctx = appContext
        if (ctx == null) {
            onResult?.invoke(false, "not initialised")
            return
        }
        synchronized(lock) {
            if (apState == AP_ENABLED) {
                onResult?.invoke(true, "already on")
                return
            }
            lastError = null
        }

        // Cap check before we touch the radio: an exceeded cap must not be
        // bypassed by toggling off and on.
        val capMb = try { UnifiedConfigManager.getHotspot().optLong("dataCapMb", 0L) } catch (t: Throwable) { 0L }
        val usedBytes = try { UnifiedConfigManager.getHotspot().optLong("dataUsedBytes", 0L) } catch (t: Throwable) { 0L }
        if (capMb > 0L && isOverCap(usedBytes, capMb)) {
            synchronized(lock) { lastError = "data limit reached" }
            // Publish so a web client sees the reason too — it reads the mirrored
            // state, not this in-process result callback.
            publishState()
            onResult?.invoke(false, "data limit reached — raise or reset the limit first")
            return
        }

        // ORDER IS LOAD-BEARING: take the hotspot's independent suppression marker
        // BEFORE startTethering, or keep-alive can re-enable the station link and kill
        // the AP. Always take ownership even when the user currently keeps WiFi off:
        // that preference may be changed while the hotspot is still active.
        val suppressed = try {
            UnifiedConfigManager.updateHotspot(mapOf("suppressedByHotspot" to true))
        } catch (t: Throwable) {
            log.warn(TAG, "keep-alive suppression write threw: ${t.message}")
            false
        }
        if (!suppressed) {
            synchronized(lock) { lastError = "could not suppress the WiFi keep-alive" }
            publishState()
            onResult?.invoke(false, "could not prepare the radio — try again")
            return
        }

        // Publish ENABLING before the blocking work below. The daemon's stale-
        // suppression reconciler decides ownership from the published apState, and
        // this worker is about to block for up to ~27s (svc + tether latch) with no
        // chance to publish — without this marker a keep-alive tick would read the
        // pre-enable state, clear our suppression and re-enable WiFi, killing the
        // AP mid-startup on a single-radio chip.
        synchronized(lock) { apState = AP_ENABLING }
        publishState()

        // The framework's tethering check reads the WRITE_SETTINGS app-op, so grant it
        // HERE rather than relying on the caller: the native toggle used to enable
        // without it and every attempt failed with "could not start the hotspot".
        grantWriteSettingsAppop()

        // Cellular is the upstream once the station link is gone.
        runShell("svc data enable")
        // Push any pending SSID/password before the AP comes up.
        applyApConfiguration()

        // Attach the SoftAp listener now, so the coming transition and its client
        // events arrive by push. Idempotent, and deferred until here so a device
        // that never enables the hotspot never allocates the callback thread.
        registerSoftApCallback()

        val ok = startTethering(ctx)
        if (!ok) {
            // Never strand the hotspot-owned guard; user intent is a separate key.
            UnifiedConfigManager.updateHotspot(mapOf("suppressedByHotspot" to false))
            // Re-read the radio: we published ENABLING before blocking, and leaving
            // that behind would pin both UIs on "Starting…" with the toggle stuck on,
            // since a cold failure arms no sampler and no callback to correct it.
            synchronized(lock) {
                apState = readApState()
                lastError = "start failed"
            }
            // Nothing came up, so don't leave the listener thread behind.
            unregisterSoftApCallback()
            publishState()
            onResult?.invoke(false, "could not start the hotspot")
            return
        }

        UnifiedConfigManager.updateHotspot(mapOf("enabled" to true))
        beginSession()
        if (UnifiedConfigManager.isHotspotProxySystemWide()) applySystemWideProxy(true)
        // Clients have no route of their own on this PDN, so bring the bound relay
        // up with the AP when the user opted into it.
        syncClientRelay()
        syncClientTunnel()
        ensureSampler()
        publishState()
        onResult?.invoke(true, "hotspot starting")
    }

    private fun disableInternal(reason: String, onResult: ((Boolean, String) -> Unit)?) {
        val ctx = appContext
        if (ctx == null) {
            onResult?.invoke(false, "not initialised")
            return
        }
        val ok = stopTethering(ctx)
        // Order matters: drop the tunnel inbound BEFORE the relay goes away, so the
        // config is rewritten while its detour target still exists.
        syncClientTunnelOff()
        // Release the relay's cellular request with the AP; leaving it would pin
        // the data call up for nothing.
        CellularRelay.stop()

        // Clear only the hotspot marker; user/automation intent must survive teardown.
        if (UnifiedConfigManager.didHotspotSuppressWifiKeepAlive()) {
            UnifiedConfigManager.updateHotspot(mapOf("suppressedByHotspot" to false))
        }
        UnifiedConfigManager.updateHotspot(mapOf("enabled" to false))
        endSession()
        synchronized(lock) { apState = readApState() }
        publishState()
        log.info(TAG, "hotspot disabled (reason=$reason ok=$ok)")
        onResult?.invoke(ok, if (ok) "hotspot stopped" else "stop reported a failure")
    }

    /**
     * `startTethering` is not on the public ConnectivityManager here, so reach
     * the internal binder and invoke it there. resultCode 0 == SUCCESS.
     */
    private fun startTethering(ctx: Context): Boolean {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) ?: return false
            val svcField = cm.javaClass.getDeclaredField("mService")
            svcField.isAccessible = true
            val svc = svcField.get(cm) ?: return false

            val latch = java.util.concurrent.CountDownLatch(1)
            val code = java.util.concurrent.atomic.AtomicInteger(-1)
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    code.set(resultCode)
                    latch.countDown()
                }
            }
            val method = svc.javaClass.getMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
            method.invoke(svc, TETHERING_WIFI, receiver, false, ctx.packageName)
            // Poll while waiting instead of parking for the full timeout. The
            // receiver often never fires on this firmware, and this call holds the
            // single worker — so every other queued mutation (notably the user's
            // disable tap) would sit behind it. Bailing as soon as the radio
            // reports up cuts the common case from 12s to about a second.
            var answered = false
            val deadline = System.currentTimeMillis() + TETHER_RESULT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    answered = true
                    break
                }
                if (readApState() == AP_ENABLED) {
                    log.info(TAG, "startTethering: AP observed up before any result")
                    return true
                }
            }
            if (!answered) {
                log.warn(TAG, "startTethering: no result within ${TETHER_RESULT_TIMEOUT_MS}ms")
                // The AP sometimes comes up without the receiver firing; trust the
                // observed state rather than reporting a false failure.
                val observed = readApState()
                return observed == AP_ENABLED || observed == AP_ENABLING
            }
            val ok = code.get() == TETHER_RESULT_SUCCESS
            if (!ok) log.warn(TAG, "startTethering rejected: resultCode=${code.get()}")
            return ok
        } catch (t: Throwable) {
            log.warn(TAG, "startTethering failed: ${t.message}")
            return false
        }
    }

    private fun stopTethering(ctx: Context): Boolean {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) ?: return false
            val method = cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
            method.invoke(cm, TETHERING_WIFI)
            return true
        } catch (t: Throwable) {
            log.warn(TAG, "stopTethering failed: ${t.message}")
            return false
        }
    }

    // ==================== SoftAp push callbacks ====================

    /**
     * Register the hidden SoftApCallback so client and state changes arrive as
     * pushes. Built through a dynamic proxy because the interface is not in the
     * public SDK.
     */
    private fun registerSoftApCallback() {
        val ctx = appContext ?: return
        if (softApCallback != null) return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val cbClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val thread = HandlerThread("hotspot-softap").apply { isDaemon = true; start() }
            callbackThread = thread
            val handler = Handler(thread.looper)

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                cbClass.classLoader, arrayOf(cbClass)
            ) { _, method, args ->
                try { dispatchSoftApCallback(method.name, args) }
                catch (t: Throwable) { log.warn(TAG, "softap callback ${method.name}: ${t.message}") }
                null
            }

            val register = wm.javaClass.getMethod("registerSoftApCallback", cbClass, Handler::class.java)
            register.invoke(wm, proxy, handler)
            softApCallback = proxy
            log.info(TAG, "SoftAp callback registered")
        } catch (t: Throwable) {
            log.warn(TAG, "registerSoftApCallback unavailable: ${t.message}")
            callbackThread?.quitSafely()
            callbackThread = null
        }
    }

    /**
     * Release the SoftAp listener and its thread once the AP has settled off, so
     * a finished session leaves nothing running. Re-registered by the next enable.
     */
    private fun unregisterSoftApCallback() {
        val proxy = softApCallback ?: return
        val ctx = appContext
        try {
            val wm = ctx?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val cbClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            wm?.javaClass?.getMethod("unregisterSoftApCallback", cbClass)?.invoke(wm, proxy)
        } catch (t: Throwable) {
            log.warn(TAG, "unregisterSoftApCallback failed: ${t.message}")
        }
        // Drop our references regardless: a listener we can't detach must not keep
        // pinning the thread, and a stale proxy would block re-registration.
        softApCallback = null
        callbackThread?.quitSafely()
        callbackThread = null
    }

    private fun dispatchSoftApCallback(name: String, args: Array<out Any?>?) {
        when (name) {
            "onStateChanged" -> {
                val newState = (args?.getOrNull(0) as? Int) ?: return
                onApStateChanged(newState)
            }
            "onStaConnected" -> {
                val mac = (args?.getOrNull(0) as? String) ?: return
                onClientConnected(mac)
            }
            "onStaDisconnected" -> {
                val mac = (args?.getOrNull(0) as? String) ?: return
                onClientDisconnected(mac)
            }
            "onNumClientsChanged" -> {
                // Count-only signal; the MAC-level callbacks carry identity, so
                // just refresh the ARP enrichment.
                worker.execute { enrichClientIps() }
            }
        }
    }

    private fun onApStateChanged(newState: Int) {
        val previous: Int
        synchronized(lock) {
            previous = apState
            apState = newState
        }
        if (newState == AP_ENABLED && previous != AP_ENABLED) {
            worker.execute {
                resolveCredentialsOnce()
                beginSession()
                // Re-run both syncs now the AP is really serving. enableInternal may
                // have run them while still ENABLING, when the relay wasn't listening
                // yet and the tunnel start was therefore skipped; without this retry
                // nothing would bring them up for the rest of the session.
                syncClientRelay()
                syncClientTunnel()
                ensureSampler()
                publishState()
            }
        } else if (newState == AP_DISABLED && previous == AP_ENABLED) {
            worker.execute { onApDroppedUnexpectedly() }
        } else if (newState == AP_FAILED) {
            synchronized(lock) { lastError = "access point failed" }
            worker.execute { publishState() }
        }
    }

    /**
     * The AP went down without a user request. Honour the keepAlive switch by
     * re-asserting once; otherwise settle into the off state (and release the
     * WiFi suppression so the station link can come back).
     */
    private fun onApDroppedUnexpectedly() {
        val cfg = try { UnifiedConfigManager.getHotspot() } catch (t: Throwable) { org.json.JSONObject() }
        val userWantsOn = cfg.optBoolean("enabled", false)
        val keepAlive = cfg.optBoolean("keepAlive", false)
        endSession()
        if (userWantsOn && keepAlive && !capReached()) {
            log.info(TAG, "AP dropped; keep-alive re-asserting")
            enableInternal(null)
            return
        }
        if (UnifiedConfigManager.didHotspotSuppressWifiKeepAlive()) {
            UnifiedConfigManager.updateHotspot(mapOf("suppressedByHotspot" to false))
        }
        publishState()
    }

    private fun onClientConnected(mac: String) {
        val key = mac.lowercase()
        synchronized(lock) {
            val row = clients[key]
            if (row == null) {
                clients[key] = ClientRow(mac = key, name = friendlyName(key))
            } else {
                row.lastSeenMs = System.currentTimeMillis()
            }
        }
        worker.execute { enrichClientIps(); publishState() }
    }

    private fun onClientDisconnected(mac: String) {
        synchronized(lock) { clients.remove(mac.lowercase()) }
        worker.execute { publishState() }
    }

    // ==================== Sampling / data cap ====================

    /**
     * Arm the sampler. Only called on the AP-up edge, so a head unit that never
     * uses the hotspot reads no /proc stats and schedules no wakeups — the
     * zero-overhead-when-off contract the rest of the app follows.
     */
    private fun ensureSampler() {
        synchronized(lock) {
            if (sampler?.isDone == false) return
            sampler = worker.scheduleWithFixedDelay(
                { sampleTick() }, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
        }
    }

    /** Stop sampling once the AP is down; re-armed on the next up edge. */
    private fun stopSampler() {
        synchronized(lock) {
            sampler?.cancel(false)
            sampler = null
        }
    }

    /**
     * Expensive tick (>= 5 s): re-read counters, enrich client IPs, enforce the
     * data cap. The 1 Hz uptime tick lives in the UI and does no I/O.
     */
    private fun sampleTick() {
        try {
            val state = readApState()
            val wasEnabled: Boolean
            synchronized(lock) {
                wasEnabled = apState == AP_ENABLED
                apState = state
            }
            // Keep sampling through ENABLING: this tick is also the fallback that
            // notices the AP came up when SoftApCallback registration failed.
            if (state == AP_ENABLING) return
            if (state != AP_ENABLED) {
                // onApDroppedUnexpectedly may re-arm the AP (keep-alive), so only
                // disarm when it settled in the off state.
                if (wasEnabled) onApDroppedUnexpectedly()
                val settled = readApState()
                if (settled != AP_ENABLED && settled != AP_ENABLING) {
                    stopSampler()
                    // Runs on `worker`, never on the callback thread, so detaching
                    // the listener here cannot quit the looper under itself.
                    unregisterSoftApCallback()
                    syncClientTunnelOff()
                    CellularRelay.stop()
                }
                return
            }
            // First tick after the AP actually came up with no callback to tell
            // us — establish the session baseline before measuring.
            if (!wasEnabled && startedAtMs == 0L) beginSession()
            if (tetherIface == null) resolveTetherIface()
            sampleCounters()
            enrichClientIps()
            enforceDataCap()
            maybePublishState()
        } catch (t: Throwable) {
            log.warn(TAG, "sampleTick: ${t.message}")
        }
    }

    private fun sampleCounters() {
        // Relayed traffic terminates in this process, so it never appears in the AP
        // interface's forwarded counters — take it from the relay and add it on.
        val relayed = try {
            CellularRelay.relayedBytes()
        } catch (t: Throwable) {
            0L to 0L
        }
        val iface = synchronized(lock) { tetherIface }
        val now = if (iface != null) readIfaceCounters(iface) else null
        if (now == null) {
            // No iface counters yet (AP still coming up): still surface relay bytes
            // so usage and the data cap are not stuck at zero.
            synchronized(lock) {
                sessionRx = maxOf(sessionRx, relayed.first)
                sessionTx = maxOf(sessionTx, relayed.second)
            }
            return
        }
        synchronized(lock) {
            if (baselineRx < 0L || baselineTx < 0L) {
                baselineRx = now.first
                baselineTx = now.second
                return
            }
            // /proc counters are monotonic per interface but reset when the
            // interface is recreated — a negative delta means re-baseline. The
            // bytes measured so far are already persisted by the checkpoints, so
            // clear foldedBytes along with the session or the next checkpoint
            // would subtract a stale figure.
            if (now.first < baselineRx || now.second < baselineTx) {
                baselineRx = now.first
                baselineTx = now.second
                sessionRx = 0L
                sessionTx = 0L
                foldedBytes = 0L
                return
            }
            // The AP interface counter already includes relayed traffic: /proc/net/dev
            // is a DEVICE counter, so it counts locally-terminated bytes too (verified
            // on-device — a locally terminated 1MB transfer moved wlan0 rx by ~1.11MB).
            // Adding the relay tally on top would bill every byte twice and trip the
            // data limit at half its setting, so the iface delta is authoritative here.
            val ifaceRx = now.first - baselineRx
            val ifaceTx = now.second - baselineTx
            // Keep the relay tally as a floor: if the iface counters are unavailable or
            // reset, it is the only record of traffic that actually happened.
            sessionRx = maxOf(ifaceRx, relayed.first)
            sessionTx = maxOf(ifaceTx, relayed.second)
        }
    }

    private fun enforceDataCap() {
        val cfg = try { UnifiedConfigManager.getHotspot() } catch (t: Throwable) { return }
        val capMb = cfg.optLong("dataCapMb", 0L)
        val persisted = cfg.optLong("dataUsedBytes", 0L)
        val (session, unfolded) = synchronized(lock) {
            val s2 = sessionRx + sessionTx
            s2 to (s2 - foldedBytes)
        }
        // dataUsedBytes ALREADY includes foldedBytes (checkpointUsage folds as it
        // persists), so only the not-yet-folded remainder may be added — adding the
        // whole session again would double-count and trip the limit at half.
        val total = persisted + unfolded
        // Checkpoint the running session periodically. Without this, a process
        // death (parked terminate, OOM) loses every byte since the last stop and
        // the limit silently resets. Throttled by volume so the config isn't
        // rewritten on every tick.
        checkpointUsage(persisted, session)
        if (capMb <= 0L) return
        if (!isOverCap(total, capMb)) return
        val alreadyTripped = synchronized(lock) {
            val t = capTripped
            capTripped = true
            t
        }
        if (alreadyTripped) return
        log.info(TAG, "data limit of ${capMb}MB reached — disabling hotspot")
        // Surface the reason in the status line too, not just the notification —
        // otherwise the hotspot simply appears to switch itself off.
        synchronized(lock) { lastError = "data limit reached" }
        notifyCapReached(capMb, total)
        // disableInternal -> endSession() folds the session bytes into the
        // persisted total; persisting here too would double-count them.
        disableInternal("data limit", null)
    }

    /**
     * Fold the not-yet-persisted part of the session into the cumulative total
     * every [USAGE_CHECKPOINT_BYTES]. [foldedBytes] tracks what is already in
     * the persisted figure so nothing is double-counted, and the session RX/TX
     * the UI shows is left untouched.
     */
    private fun checkpointUsage(persisted: Long, session: Long) {
        // foldedBytes is written by sampleCounters/endSession under `lock`, so read
        // and advance it under the same lock — an unsynchronised 64-bit read could
        // tear and double-count (or skip) a checkpoint.
        val unfolded = synchronized(lock) {
            val u = session - foldedBytes
            if (u < USAGE_CHECKPOINT_BYTES) return
            u
        }
        // Advance foldedBytes ONLY after the write lands. The app-UID write path can
        // defer and return false; marking bytes folded that were never persisted
        // would silently shrink the cumulative total and stop the cap ever tripping.
        val ok = try {
            UnifiedConfigManager.updateHotspot(mapOf("dataUsedBytes" to (persisted + unfolded)))
        } catch (t: Throwable) {
            log.warn(TAG, "usage checkpoint failed: ${t.message}")
            false
        }
        if (ok) synchronized(lock) { foldedBytes = session }
    }

    private fun capReached(): Boolean {
        val cfg = try { UnifiedConfigManager.getHotspot() } catch (t: Throwable) { return false }
        val capMb = cfg.optLong("dataCapMb", 0L)
        if (capMb <= 0L) return false
        // Same invariant as enforceDataCap: the persisted total already covers
        // foldedBytes, so only the unfolded remainder is added.
        val unfolded = synchronized(lock) { (sessionRx + sessionTx) - foldedBytes }
        return isOverCap(cfg.optLong("dataUsedBytes", 0L) + unfolded, capMb)
    }

    /**
     * Compare bytes against a limit expressed in MB without ever computing
     * `capMb * 1MB` — that product overflows Long for absurd inputs and flips the
     * comparison, which would refuse to start the hotspot while claiming the limit
     * was reached. Dividing instead cannot overflow.
     */
    private fun isOverCap(usedBytes: Long, capMb: Long): Boolean {
        if (capMb <= 0L) return false
        if (usedBytes <= 0L) return false
        return usedBytes / (1024L * 1024L) >= capMb
    }

    private fun notifyCapReached(capMb: Long, usedBytes: Long) {
        try {
            val data = org.json.JSONObject()
                .put("capMb", capMb)
                .put("usedBytes", usedBytes)
            com.overdrive.app.notifications.NotificationBus.get().publish(
                com.overdrive.app.notifications.NotificationEvent(
                    "network.hotspot.limit",
                    com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                    "Hotspot data limit reached",
                    "The hotspot used its ${capMb} MB limit and has been turned off.",
                    "hotspot-limit",
                    "/network.html",
                    data
                )
            )
        } catch (t: Throwable) {
            log.warn(TAG, "cap notification failed: ${t.message}")
        }
    }

    // ==================== Session bookkeeping ====================

    private fun beginSession() {
        resolveTetherIface()
        // Zero the relay's tally too, or a new session starts with the previous
        // session's relayed bytes already on the clock.
        try { CellularRelay.resetCounters() } catch (t: Throwable) { }
        synchronized(lock) {
            if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
            val iface = tetherIface
            val now = if (iface != null) readIfaceCounters(iface) else null
            baselineRx = now?.first ?: -1L
            baselineTx = now?.second ?: -1L
            sessionRx = 0L
            sessionTx = 0L
            foldedBytes = 0L
        }
    }

    /** Adopt an AP that was already up when this process started. */
    private fun adoptRunningSession() {
        resolveTetherIface()
        val persistedStart = try {
            UnifiedConfigManager.getHotspotState().optLong("startedAt", 0L)
        } catch (t: Throwable) { 0L }
        synchronized(lock) {
            startedAtMs = if (persistedStart > 0L) persistedStart else System.currentTimeMillis()
            val iface = tetherIface
            val now = if (iface != null) readIfaceCounters(iface) else null
            baselineRx = now?.first ?: -1L
            baselineTx = now?.second ?: -1L
            sessionRx = 0L
            sessionTx = 0L
            foldedBytes = 0L
        }
        enrichClientIps()
    }

    /** Fold whatever the checkpoints have not already persisted, then clear. */
    private fun endSession() {
        val unfolded = synchronized(lock) {
            val remaining = (sessionRx + sessionTx) - foldedBytes
            startedAtMs = 0L
            sessionRx = 0L
            sessionTx = 0L
            foldedBytes = 0L
            baselineRx = -1L
            baselineTx = -1L
            clients.clear()
            // Drop the iface too: it is the same wlan0 the station uses, so keeping
            // it would let the gateway/subnet helpers read the home-network address
            // once the station reconnects. Re-resolved on the next enable.
            tetherIface = null
            remaining
        }
        if (unfolded > 0L) {
            try {
                val persisted = UnifiedConfigManager.getHotspot().optLong("dataUsedBytes", 0L)
                UnifiedConfigManager.updateHotspot(mapOf("dataUsedBytes" to (persisted + unfolded)))
            } catch (t: Throwable) {
                log.warn(TAG, "usage persist failed: ${t.message}")
            }
        }
    }

    private fun maybePublishState() {
        val now = System.currentTimeMillis()
        if (now - lastStatePublishMs < STATE_PUBLISH_INTERVAL_MS) return
        publishState()
    }

    /** Mirror observed state into its own config section for cross-process reads. */
    private fun publishState() {
        lastStatePublishMs = System.currentTimeMillis()
        try {
            // Resolved before taking the lock: it does a native interface lookup,
            // which must not run while the SoftAp callback thread is waiting.
            val gateway = apSubnetPrefix()?.let { "$it.1" } ?: AP_GATEWAY
            val values = synchronized(lock) {
                val roster = org.json.JSONArray()
                clients.values.forEach { c ->
                    roster.put(org.json.JSONObject().apply {
                        put("mac", c.mac)
                        put("ip", c.ip ?: "")
                        put("name", c.name ?: friendlyName(c.mac))
                    })
                }
                mapOf(
                    "apState" to apState,
                    "startedAt" to startedAtMs,
                    "iface" to (tetherIface ?: ""),
                    "rxBytes" to sessionRx,
                    "txBytes" to sessionTx,
                    "sessionBytes" to (sessionRx + sessionTx),
                    "clients" to clients.size,
                    "clientList" to roster,
                    "lastError" to (lastError ?: ""),
                    // Mirrored so the daemon's HTTP surface can report the real
                    // gateway instead of assuming the default subnet.
                    "gateway" to gateway,
                    // The daemon is a different process and cannot read the radio or
                    // the OEM props, so the credentials must travel through here or
                    // the web page shows a blank name/password.
                    "activeSsid" to (cachedSsid ?: ""),
                    "activePassword" to (cachedApPassword ?: ""),
                    "updatedAt" to System.currentTimeMillis(),
                    // Monotonic companion to updatedAt, for UI age display. NOT used
                    // to judge owner liveness — the daemon reads the radio directly,
                    // because no timestamp survives clock steps and short reboots.
                    "updatedAtElapsed" to android.os.SystemClock.elapsedRealtime()
                )
            }
            UnifiedConfigManager.updateHotspotState(values)
        } catch (t: Throwable) {
            log.warn(TAG, "publishState failed: ${t.message}")
        }
    }

    // ==================== AP configuration ====================

    private fun readApState(): Int {
        try {
            val ctx = appContext ?: return AP_DISABLED
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return AP_DISABLED
            val m = wm.javaClass.getMethod("getWifiApState")
            return (m.invoke(wm) as? Int) ?: AP_DISABLED
        } catch (t: Throwable) {
            return AP_DISABLED
        }
    }

    /**
     * The AP passphrase this firmware beacons with. Same story as the SSID: the
     * framework getter demands OVERRIDE_WIFI_CONFIG, but the OEM property is
     * readable, and it is the value the radio is actually using.
     */
    private fun currentApPassword(): String? {
        if (credsResolved) return cachedApPassword
        return try {
            runShell("getprop persist.sys.ap.password")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.also { cachedApPassword = it }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The name the AP actually broadcasts. `getWifiApConfiguration` is NOT usable
     * here — reading it also demands OVERRIDE_WIFI_CONFIG and throws for us — so
     * take the OEM's own property, which is what this firmware beacons.
     */
    private fun currentSsid(): String? {
        if (credsResolved) return cachedSsid
        // Try the framework first: on a build that does permit the read this is the
        // authoritative answer, and it costs one reflective call.
        try {
            val ctx = appContext
            val wm = ctx?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val m = wm.javaClass.getMethod("getWifiApConfiguration")
                (m.invoke(wm) as? WifiConfiguration)?.SSID?.let {
                    if (it.isNotEmpty()) { cachedSsid = it; return it }
                }
            }
        } catch (t: Throwable) {
            // Expected without OVERRIDE_WIFI_CONFIG — fall through to the property.
        }
        return try {
            val v = runShell("getprop persist.sys.ap.ssid")?.trim()
            if (v.isNullOrEmpty()) null else v.also { cachedSsid = it }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Resolve the vehicle-owned AP credentials ONCE, off the snapshot path. The
     * values cannot change (writes are refused by this firmware), so latching the
     * attempt — success or not — keeps [snapshot] free of shell round-trips even on
     * a vehicle that lacks these properties.
     */
    private fun resolveCredentialsOnce() {
        if (credsResolved) return
        val ssid = currentSsid()
        val pw = currentApPassword()
        // Latch only when the probe actually produced something. init() runs once per
        // process and may land before the loopback shell is reachable; latching a
        // failed read would blank the credentials for the whole session. A retry
        // happens on the next AP-up edge, which is when they matter.
        if (!ssid.isNullOrEmpty() || !pw.isNullOrEmpty()) credsResolved = true
    }

    /**
     * Push the persisted SSID/password onto the AP config. Only called on the
     * start path (and after an explicit save) since a live change would drop
     * every connected client.
     */
    private fun applyApConfiguration() {
        val ctx = appContext ?: return
        val cfg = try { UnifiedConfigManager.getHotspot() } catch (t: Throwable) { return }
        val ssid = cfg.optString("ssid", "")
        val password = cfg.optString("password", "")
        if (ssid.isEmpty() && password.isEmpty()) return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val get = wm.javaClass.getMethod("getWifiApConfiguration")
            val current = get.invoke(wm) as? WifiConfiguration ?: WifiConfiguration()
            if (ssid.isNotEmpty()) current.SSID = ssid
            if (password.length >= 8) {
                current.preSharedKey = password
                current.allowedKeyManagement.clear()
                current.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            val set = wm.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
            val ok = set.invoke(wm, current) as? Boolean ?: false
            if (!ok) {
                log.warn(TAG, "setWifiApConfiguration returned false")
                synchronized(lock) { apConfigWritable = false }
            } else {
                synchronized(lock) { apConfigWritable = true }
            }
        } catch (t: Throwable) {
            // The framework gates this on OVERRIDE_WIFI_CONFIG (signature|privileged),
            // which an unprivileged app cannot hold and `pm grant` cannot give — the
            // shell UID does not have it either. So the AP keeps whatever name the OEM
            // configured. Record it so the UI can say so instead of silently showing a
            // name the radio never broadcasts.
            log.warn(TAG, "applyApConfiguration rejected (needs OVERRIDE_WIFI_CONFIG): ${t.message}")
            synchronized(lock) { apConfigWritable = false }
        }
    }

    /** Trust getTetheredIfaces(); fall back to the known AP interface name. */
    private fun resolveTetherIface() {
        val ctx = appContext ?: return
        val resolved = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
            val m = cm?.javaClass?.getMethod("getTetheredIfaces")
            @Suppress("UNCHECKED_CAST")
            val ifaces = m?.invoke(cm) as? Array<String>
            ifaces?.firstOrNull { name -> AP_IFACE_HINTS.any { name.startsWith(it) } }
        } catch (t: Throwable) {
            null
        }
        synchronized(lock) { tetherIface = resolved ?: tetherIface ?: DEFAULT_AP_IFACE }
    }

    // ==================== /proc readers ====================

    /** Per-interface rx/tx byte counters from /proc/net/dev. */
    private fun readIfaceCounters(iface: String): Pair<Long, Long>? {
        try {
            val prefix = "$iface:"
            return File("/proc/net/dev").useLines { lines ->
                var found: Pair<Long, Long>? = null
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith(prefix)) continue
                    // After the "iface:" label: field 1 = rx bytes, field 9 = tx bytes.
                    val fields = line.substring(prefix.length).trim().split(Regex("\\s+"))
                    if (fields.size < 9) break
                    val rx = fields[0].toLongOrNull() ?: break
                    val tx = fields[8].toLongOrNull() ?: break
                    found = rx to tx
                    break
                }
                found
            }
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * Enrich known MACs with IPs from the ARP table. MACs come from the SoftAp
     * callbacks, so ARP is only ever an enrichment source — never the roster.
     */
    private fun enrichClientIps() {
        val rows = readArpTable().ifEmpty { readIpNeigh() }
        if (rows.isEmpty()) return
        synchronized(lock) {
            rows.forEach { (mac, ip) ->
                val row = clients[mac]
                if (row != null) {
                    row.ip = ip
                    row.lastSeenMs = System.currentTimeMillis()
                } else if (apState == AP_ENABLED) {
                    // A client the callbacks missed (e.g. it associated before
                    // this process started) still belongs in the list.
                    clients[mac] = ClientRow(mac = mac, ip = ip, name = friendlyName(mac))
                }
            }
        }
    }

    /**
     * True for addresses on the tethering subnet. Read from the live AP interface
     * rather than assumed: the framework normally hands out 192.168.43.1/24, but it
     * can pick another 192.168.x range on collision, and hard-coding one would make
     * the client list silently empty. [AP_GATEWAY] is only the fallback.
     */
    private fun isApSubnet(ip: String, prefix: String): Boolean = ip.startsWith(prefix)

    /**
     * Resolve the subnet prefix ONCE per table read — [apSubnetPrefix] hits a native
     * interface lookup, which must not run per ARP row.
     */
    private fun currentApPrefix(): String =
        (apSubnetPrefix() ?: AP_GATEWAY.substringBeforeLast('.')) + "."

    /** First three octets of the tethered iface's IPv4 address, or null. */
    private fun apSubnetPrefix(): String? {
        val iface = synchronized(lock) { tetherIface } ?: return null
        return try {
            java.net.NetworkInterface.getByName(iface)
                ?.inetAddresses
                ?.toList()
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.map { it.hostAddress ?: "" }
                ?.firstOrNull { it.startsWith("192.168.") }
                ?.substringBeforeLast('.')
        } catch (t: Throwable) {
            null
        }
    }

    /** /proc/net/arp: `IP HWtype Flags HWaddress Mask Device`. */
    private fun readArpTable(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val apPrefix = currentApPrefix()
        try {
            File("/proc/net/arp").useLines { lines ->
                lines.drop(1).forEach { raw ->
                    val f = raw.trim().split(Regex("\\s+"))
                    if (f.size < 6) return@forEach
                    val ip = f[0]
                    val flags = f[2]
                    val mac = f[3].lowercase()
                    val device = f[5]
                    if (flags == "0x0") return@forEach            // incomplete entry
                    if (mac == "00:00:00:00:00:00") return@forEach
                    if (AP_IFACE_HINTS.none { device.startsWith(it) }) return@forEach
                    // The tethered iface IS wlan0, which is also the station iface,
                    // so the name alone can't tell an AP client from a home-network
                    // neighbour. Require the AP subnet — station leases never match.
                    if (!isApSubnet(ip, apPrefix)) return@forEach
                    out[mac] = ip
                }
            }
        } catch (t: Throwable) {
            log.warn(TAG, "arp read failed: ${t.message}")
        }
        return out
    }

    /** Fallback neighbour source when /proc/net/arp is empty. */
    private fun readIpNeigh(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val apPrefix = currentApPrefix()
        val text = runShell("ip neigh show") ?: return out
        text.lineSequence().forEach { raw ->
            // 192.168.43.42 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
            val f = raw.trim().split(Regex("\\s+"))
            if (f.size < 5) return@forEach
            val ip = f[0]
            val devIdx = f.indexOf("dev")
            val macIdx = f.indexOf("lladdr")
            if (devIdx < 0 || macIdx < 0 || macIdx + 1 >= f.size) return@forEach
            val device = f.getOrNull(devIdx + 1) ?: return@forEach
            if (AP_IFACE_HINTS.none { device.startsWith(it) }) return@forEach
            val mac = f[macIdx + 1].lowercase()
            if (mac == "00:00:00:00:00:00") return@forEach
            // Same reason as readArpTable: filter by the AP subnet, not the name.
            if (!isApSubnet(ip, apPrefix)) return@forEach
            out[mac] = ip
        }
        return out
    }

    // ==================== Proxy wiring ====================

    /**
     * Route the head unit's OWN traffic through the local proxy using the
     * system-wide http-proxy settings. Tethered clients are unaffected — see
     * the client-proxy option, which is strictly opt-in on each client.
     */
    private fun applySystemWideProxy(on: Boolean) {
        val port = com.overdrive.app.daemon.proxy.ProxyConfiguration.PROXY_PORT
        val exclusions = try {
            com.overdrive.app.daemon.proxy.ProxyConfiguration.PROXY_EXCLUSIONS
        } catch (t: Throwable) { "" }
        val script = if (on) buildString {
            append("settings put global global_http_proxy_host 127.0.0.1\n")
            append("settings put global global_http_proxy_port $port\n")
            if (exclusions.isNotEmpty()) {
                append("settings put global global_http_proxy_exclusion_list '$exclusions'\n")
            }
            append("settings put global http_proxy 127.0.0.1:$port\n")
        } else buildString {
            append("settings delete global global_http_proxy_host\n")
            append("settings delete global global_http_proxy_port\n")
            append("settings delete global global_http_proxy_exclusion_list\n")
            append("settings delete global http_proxy\n")
        }
        runShellScript(script)
        log.info(TAG, "system-wide proxy ${if (on) "applied" else "cleared"}")
    }

    // ==================== Shell ====================

    /**
     * One-shot privileged shell. The app UID cannot run `settings put global`
     * or `svc`, so these go through the UID-2000 shell bridge.
     */
    private fun runShell(command: String): String? {
        val ctx = appContext ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val sb = StringBuilder()
        try {
            val launcher = com.overdrive.app.launcher.AdbDaemonLauncher(ctx)
            launcher.executeShellCommand(command, object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) { sb.append(message) }
                override fun onLaunched() { latch.countDown() }
                override fun onError(error: String) {
                    log.warn(TAG, "shell failed ($command): $error")
                    latch.countDown()
                }
            })
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            launcher.releasePerInstanceResources()
        } catch (t: Throwable) {
            log.warn(TAG, "shell dispatch failed: ${t.message}")
            return null
        }
        return sb.toString()
    }

    private fun runShellScript(body: String): String? {
        val ctx = appContext ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val sb = StringBuilder()
        try {
            val launcher = com.overdrive.app.launcher.AdbDaemonLauncher(ctx)
            launcher.executeShellScript(body, object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) { sb.append(message) }
                override fun onLaunched() { latch.countDown() }
                override fun onError(error: String) {
                    log.warn(TAG, "shell script failed: $error")
                    latch.countDown()
                }
            })
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
            launcher.releasePerInstanceResources()
        } catch (t: Throwable) {
            log.warn(TAG, "shell script dispatch failed: ${t.message}")
            return null
        }
        return sb.toString()
    }

    /**
     * One-time grant of the appop the tethering call is gated on. Idempotent and
     * cheap; called from the settings screen so a fresh install works without
     * the user hunting through system settings.
     */
    @JvmStatic
    fun ensureWriteSettingsAppop() {
        worker.execute { grantWriteSettingsAppop() }
    }

    /**
     * Grant ourselves the WRITE_SETTINGS app-op, synchronously on the caller.
     *
     * This is the gate the framework's tethering permission check actually reads
     * (`config_mobile_hotspot_provision_app` is empty on this firmware, so
     * enforceTetherChangePermission falls through to the app-op rather than
     * TETHER_PRIVILEGED). Without it startTethering throws and the enable reports
     * "could not start the hotspot", so [enableInternal] must not run before this.
     */
    private fun grantWriteSettingsAppop() {
        val pkg = appContext?.packageName ?: return
        if (appopGranted) return
        // Set then READ BACK in one round-trip. A bare `appops set` prints nothing on
        // success, and runShell also yields "" when the shell itself failed, so the
        // output alone cannot distinguish the two — only the readback can.
        val out = runShell("appops set $pkg WRITE_SETTINGS allow >/dev/null 2>&1; " +
            "appops get $pkg WRITE_SETTINGS")
        appopGranted = out != null && out.contains("allow", ignoreCase = true)
        if (!appopGranted) log.warn(TAG, "WRITE_SETTINGS app-op not granted (got: ${out?.trim()})")
    }

    // ==================== Helpers ====================

    /** Friendly label from the MAC suffix when the client offers no hostname. */
    private fun friendlyName(mac: String): String {
        val parts = mac.split(":")
        return if (parts.size >= 2) {
            "Device ${parts[parts.size - 2]}:${parts[parts.size - 1]}"
        } else {
            "Device"
        }
    }

    const val AP_GATEWAY = "192.168.43.1"
    private const val DEFAULT_AP_IFACE = "wlan0"
}
