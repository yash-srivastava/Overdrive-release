package com.overdrive.app.launcher
import com.overdrive.app.util.ScratchPaths

import android.content.Context
import com.overdrive.app.BuildConfig
import com.overdrive.app.logging.LogManager
import com.overdrive.app.mqtt.ProxyHelper
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Launches Tailscale tunnel processes via ADB shell for remote access.
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class TailscaleLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "TailscaleLauncher"

        // Tailscale paths
        private const val DEPLOYMENT_CURRENT = "current"
        private const val DEPLOYMENT_STALE = "stale"

        private val tailscaleHome: String
            get() = ScratchPaths.path(".tailscale")
        private val TAILSCALE_LOG: String
            get() = "${tailscaleHome}/tailscale.log"
        private val TAILSCALE_PATH: String
            get() = "${tailscaleHome}/tailscale"
        private val TAILSCALED_PATH: String
            get() = "${tailscaleHome}/tailscaled"
        // Records the app versionCode the deployed binary was copied from, so an app
        // update that ships a new libtailscale.so actually redeploys it. Without this,
        // checkAndInstallTailscale only ever reinstalled when the binary was missing —
        // so an existing install kept its old binary forever, and shipped fixes (e.g.
        // the ACME-enabled rebuild) silently never reached updating users.
        private val TAILSCALE_VERSION_FILE: String
            get() = "${tailscaleHome}/installed_version"
        private const val TAILSCALE_COMMUNICATION_PORT = "8532"

        private val TAILSCALE_PROXY_FILE: String

            get() = "${tailscaleHome}/proxy_enabled"
        private const val TAILSCALE_PROXY_PORT = "8539"

        // Dashboard ingress. Tailscale userspace networking forwards an
        // unconfigured tailnet port to the same localhost port, which makes a
        // remote peer look identical to a trusted in-device caller. Publishing
        // 8080 through TCP Serve with PROXY v1 gives HttpServer a trustworthy
        // tunnel marker while preserving the existing http://100.x:8080 URL.
        private const val DASHBOARD_PORT = "8080"
        private const val DASHBOARD_BACKEND = "tcp://127.0.0.1:$DASHBOARD_PORT"
        private const val DASHBOARD_DENY_BACKEND = "tcp://127.0.0.1:1"

        // Retry through a short tailscaled startup race before failing closed.
        private const val DASHBOARD_SERVE_REPLAY_ATTEMPTS = 3
        private const val DASHBOARD_SERVE_REPLAY_DELAY_MS = 1000L

        // A login URL returns before the user approves the node. Poll briefly
        // so the secure Serve route is installed immediately after approval,
        // rather than waiting for the 30-second UI refresh.
        private const val DASHBOARD_SERVE_WATCH_ATTEMPTS = 300
        private const val DASHBOARD_SERVE_WATCH_DELAY_MS = 1000L

        // Proxy settings for sing-box (socks5 for tailscale)
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 8119

        // Remote-ADB opt-in sentinel + the adbd port we forward to. Same
        // sentinel-file pattern as TAILSCALE_PROXY_FILE so the UID-2000 shell
        // side and the app agree on state across restarts.
        private val TAILSCALE_ADB_FILE: String
            get() = "${tailscaleHome}/adb_enabled"
        private const val ADB_PORT = "5555"

        // Tailnet HTTPS is opt-in. Standard HTTPS Serve terminates TLS and
        // injects X-Forwarded-* identity before proxying to HttpServer. The
        // server treats those headers as tunnel markers, so a remote request
        // cannot regain the direct-loopback authentication fallback.
        private val TAILSCALE_HTTPS_FILE: String
            get() = "${tailscaleHome}/https_enabled"
        private const val HTTPS_PORT = "443"
        private const val HTTPS_BACKEND = "http://127.0.0.1:$DASHBOARD_PORT"
        private const val LEGACY_HTTPS_BACKEND = "127.0.0.1:$DASHBOARD_PORT"

        // Retries after the initial replay attempt, ~2s apart — covers a slow
        // tailscaled cold start without spinning if serve is genuinely broken.
        private const val ADB_SERVE_REPLAY_ATTEMPTS = 3
        private const val ADB_SERVE_REPLAY_DELAY_MS = 2000L

        // Past the whole replay ladder (attempts * delay) plus slack, so the
        // confirmation sweep runs after any in-flight replay could have landed.
        private const val ADB_SERVE_WITHDRAW_SWEEP_MS =
            ADB_SERVE_REPLAY_DELAY_MS * (ADB_SERVE_REPLAY_ATTEMPTS + 1) + 2000L

        // Daemon thread: only ever holds short retry/watch tasks, and must not
        // keep the JVM alive. Shared across instances — replays are idempotent.
        private val replayScheduler: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TailscaleServeReplay").apply { isDaemon = true }
            }

        // Shared across launcher instances so a stale retry from a boot-time
        // launcher cannot mutate or stop a newer daemon started by the UI.
        private val dashboardLifecycleGeneration = AtomicLong(0)

        internal fun invalidateDashboardLifecycle() {
            dashboardLifecycleGeneration.incrementAndGet()
        }

        /**
         * Shared command text for every tailscaled launch path. Keep the
         * Telegram daemon path on the same security contract as the UI/boot
         * launcher instead of duplicating Serve flags in two processes.
         */
        @JvmStatic
        fun secureDashboardServeArgs(): String =
            "serve --bg --proxy-protocol=1 --tcp=$DASHBOARD_PORT $DASHBOARD_BACKEND"

        @JvmStatic
        fun denyDashboardServeArgs(): String =
            "serve --bg --tcp=$DASHBOARD_PORT $DASHBOARD_DENY_BACKEND"

        @JvmStatic
        fun secureHttpsServeArgs(): String =
            "serve --bg --https=$HTTPS_PORT $HTTPS_BACKEND"

        @JvmStatic
        fun disableHttpsServeArgs(): String =
            "serve --https=$HTTPS_PORT off"

        internal fun disableLegacyHttpsServeArgs(): String =
            "serve --tls-terminated-tcp=$HTTPS_PORT off"

        private fun jsonObjectFromOutput(output: String): JSONObject? {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start < 0 || end < start) return null
            return try {
                JSONObject(output.substring(start, end + 1))
            } catch (_: Exception) {
                null
            }
        }

        private fun configUsesWebPort(config: JSONObject, port: String): Boolean {
            val web = config.optJSONObject("Web") ?: return false
            val keys = web.keys()
            while (keys.hasNext()) {
                if (keys.next().endsWith(":$port")) return true
            }
            return false
        }

        private fun configUsesTcpPort(config: JSONObject, port: String): Boolean {
            return config.optJSONObject("TCP")?.optJSONObject(port) != null
        }

        private fun ownedHttpsWebDomain(config: JSONObject): String? {
            val web = config.optJSONObject("Web") ?: return null
            var ownedDomain: String? = null
            val keys = web.keys()
            while (keys.hasNext()) {
                val hostPort = keys.next()
                if (!hostPort.endsWith(":$HTTPS_PORT")) continue
                if (ownedDomain != null) return null

                val domain = hostPort
                    .removeSuffix(":$HTTPS_PORT")
                    .trim()
                    .removePrefix("[")
                    .removeSuffix("]")
                    .trimEnd('.')
                if (domain.isEmpty()) return null

                val rootHandler = web.optJSONObject(hostPort)
                    ?.optJSONObject("Handlers")
                    ?.optJSONObject("/")
                    ?: return null
                val proxy = rootHandler.optString("Proxy", "").trim().trimEnd('/')
                if (proxy != HTTPS_BACKEND.trimEnd('/')) return null
                if (rootHandler.optString("Path", "").isNotEmpty()
                    || rootHandler.optString("Text", "").isNotEmpty()
                    || rootHandler.optString("Redirect", "").isNotEmpty()
                ) {
                    return null
                }
                ownedDomain = domain
            }
            return ownedDomain
        }

        internal fun parseHttpsServeSnapshot(output: String): HttpsServeSnapshot {
            if (output.trim() == "null") {
                return HttpsServeSnapshot(HttpsServeOwnership.FREE)
            }
            val config = jsonObjectFromOutput(output)
                ?: return HttpsServeSnapshot(HttpsServeOwnership.UNKNOWN)
            val handler = config.optJSONObject("TCP")?.optJSONObject(HTTPS_PORT)
            if (handler != null) {
                val forward = handler.optString("TCPForward", "")
                val domain = handler.optString("TerminateTLS", "").trim().trimEnd('.')
                val proxyProtocol = handler.optInt("ProxyProtocol", 0)
                val isLegacyOwned =
                    forward == LEGACY_HTTPS_BACKEND &&
                        domain.isNotEmpty() &&
                        proxyProtocol == 1 &&
                        !handler.optBoolean("HTTPS", false) &&
                        !handler.optBoolean("HTTP", false) &&
                        !configUsesWebPort(config, HTTPS_PORT)
                if (isLegacyOwned) {
                    return HttpsServeSnapshot(
                        HttpsServeOwnership.LEGACY_OWNED,
                        domain
                    )
                }

                val webDomain = ownedHttpsWebDomain(config)
                val isCurrentOwned =
                    handler.optBoolean("HTTPS", false) &&
                        !handler.optBoolean("HTTP", false) &&
                        forward.isEmpty() &&
                        domain.isEmpty() &&
                        proxyProtocol == 0 &&
                        webDomain != null
                return if (isCurrentOwned) {
                    HttpsServeSnapshot(HttpsServeOwnership.OWNED, webDomain)
                } else {
                    HttpsServeSnapshot(HttpsServeOwnership.CONFLICT)
                }
            }
            if (configUsesWebPort(config, HTTPS_PORT)) {
                return HttpsServeSnapshot(HttpsServeOwnership.CONFLICT)
            }

            // A foreground rule is owned by another live CLI session. Never overwrite or
            // withdraw it, even if it happens to point at the same local backend.
            val foreground = config.optJSONObject("Foreground")
            if (foreground != null) {
                val sessions = foreground.keys()
                while (sessions.hasNext()) {
                    val session = foreground.optJSONObject(sessions.next()) ?: continue
                    if (configUsesTcpPort(session, HTTPS_PORT)
                        || configUsesWebPort(session, HTTPS_PORT)
                    ) {
                        return HttpsServeSnapshot(HttpsServeOwnership.CONFLICT)
                    }
                }
            }
            return HttpsServeSnapshot(HttpsServeOwnership.FREE)
        }

        /**
         * Shell-side deployment probe shared by every tailscaled launch path.
         *
         * The version stamp is written only after a successful copy. Existing
         * installs without a stamp therefore self-heal once, while a partial
         * redeploy remains stale and is retried.
         */
        @JvmStatic
        fun deploymentStatusCommand(): String {
            val expected = BuildConfig.VERSION_CODE.toLong()
            return "if test -x $TAILSCALE_PATH && test -x $TAILSCALED_PATH && " +
                "[ \"\$(cat $TAILSCALE_VERSION_FILE 2>/dev/null)\" = \"$expected\" ]; then " +
                "printf '$DEPLOYMENT_CURRENT\\n'; else printf '$DEPLOYMENT_STALE\\n'; fi"
        }

        /**
         * Detached guard used by the Telegram-side direct tailscaled launcher.
         *
         * It waits through daemon startup/login, installs the same persistent
         * PROXY-v1 route as [applyDashboardServe], and fails closed if that
         * route cannot be installed. The process exits as soon as the route is
         * secured; it is not a permanent watchdog.
         */
        @JvmStatic
        fun buildDashboardServeGuardScript(): List<String> {
            val totalSecureAttempts = DASHBOARD_SERVE_REPLAY_ATTEMPTS + 1
            return listOf(
                "#!/system/bin/sh",
                "TAILSCALE='$TAILSCALE_PATH'",
                "SOCKET='127.0.0.1:$TAILSCALE_COMMUNICATION_PORT'",
                "HTTPS_FILE='$TAILSCALE_HTTPS_FILE'",
                "START_TRIES=0",
                "PIDS=''",
                "while [ \"\$START_TRIES\" -lt 30 ]; do",
                "  PIDS=\"\$(pidof tailscaled 2>/dev/null)\"",
                "  [ -n \"\$PIDS\" ] && break",
                "  START_TRIES=\$((START_TRIES + 1))",
                "  sleep 1",
                "done",
                "[ -n \"\$PIDS\" ] || exit 1",
                "if ! \"\$TAILSCALE\" serve --help 2>&1 | grep -q -- '--proxy-protocol'; then",
                "  for PID in \$PIDS; do",
                "    case \"\$PID\" in ''|*[!0-9]*) continue;; esac",
                "    kill -9 \"\$PID\" 2>/dev/null",
                "  done",
                "  exit 1",
                "fi",
                "WAIT_TRIES=0",
                "while :; do",
                "  CURRENT_PIDS=\"\$(pidof tailscaled 2>/dev/null)\"",
                "  [ -n \"\$CURRENT_PIDS\" ] || exit 0",
                "  STATUS=\"\$(\"\$TAILSCALE\" --socket \"\$SOCKET\" status --json 2>/dev/null)\"",
                "  if printf '%s' \"\$STATUS\" | grep -Eq '\"BackendState\"[[:space:]]*:[[:space:]]*\"Running\"'; then",
                "    ATTEMPT=0",
                "    while [ \"\$ATTEMPT\" -lt $totalSecureAttempts ]; do",
                "      if \"\$TAILSCALE\" --socket \"\$SOCKET\" ${secureDashboardServeArgs()} >/dev/null 2>&1; then",
                "        if [ \"\$(cat \"\$HTTPS_FILE\" 2>/dev/null)\" = true ] && " +
                    "printf '%s' \"\$STATUS\" | grep -q '\\.ts\\.net'; then",
                "          \"\$TAILSCALE\" --socket \"\$SOCKET\" ${secureHttpsServeArgs()} >/dev/null 2>&1 || true",
                "        fi",
                "        exit 0",
                "      fi",
                "      ATTEMPT=\$((ATTEMPT + 1))",
                "      [ \"\$ATTEMPT\" -ge $totalSecureAttempts ] || sleep 1",
                "    done",
                "    \"\$TAILSCALE\" --socket \"\$SOCKET\" ${denyDashboardServeArgs()} >/dev/null 2>&1 && exit 1",
                "    LATEST_PIDS=\"\$(pidof tailscaled 2>/dev/null)\"",
                "    if [ \"\$LATEST_PIDS\" != \"\$CURRENT_PIDS\" ]; then",
                "      WAIT_TRIES=0",
                "      continue",
                "    fi",
                "    for PID in \$LATEST_PIDS; do",
                "      case \"\$PID\" in ''|*[!0-9]*) continue;; esac",
                "      kill -9 \"\$PID\" 2>/dev/null",
                "    done",
                "    exit 1",
                "  fi",
                "  WAIT_TRIES=\$((WAIT_TRIES + 1))",
                "  if [ \"\$WAIT_TRIES\" -lt $DASHBOARD_SERVE_WATCH_ATTEMPTS ]; then",
                "    sleep 1",
                "  else",
                "    sleep 5",
                "  fi",
                "done"
            )
        }
    }

    private enum class BackendState {
        RUNNING,
        NOT_READY,
        UNKNOWN
    }

    internal enum class HttpsServeOwnership {
        OWNED,
        LEGACY_OWNED,
        FREE,
        CONFLICT,
        UNKNOWN
    }

    internal data class HttpsServeSnapshot(
        val ownership: HttpsServeOwnership,
        val domain: String? = null
    )

    @Volatile
    private var securedDashboardDaemon: String? = null

    private val dashboardServeWatchActive = AtomicBoolean(false)
    private val dashboardServeWatchGeneration = AtomicLong(-1L)

    interface TailscaleCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String?)
        fun onError(error: String)
    }

    fun launchTailscale(callback: TailscaleCallback) {
        // Check the deployed payload BEFORE the running-daemon fast path. A
        // package update does not stop the UID-2000 tailscaled process, so the
        // old ordering skipped redeployment forever on always-on installs.
        isDeploymentCurrent { deploymentCurrent ->
            getTailscaledFingerprint { fingerprint ->
                when {
                    fingerprint != null && deploymentCurrent ->
                        reportRunningTunnel(callback)

                    fingerprint != null ->
                        redeployRunningTailscale(fingerprint, callback)

                    deploymentCurrent ->
                        launchInstalledTailscale(callback)

                    else ->
                        installTailscale(callback) {
                            launchInstalledTailscale(callback)
                        }
                }
            }
        }
    }

    private fun reportRunningTunnel(callback: TailscaleCallback) {
        getTunnelUrl { url ->
            if (url != null) {
                logManager.info(TAG, "Tailscale already running at $url")
                callback.onLog("Tailscale already running at $url")
                callback.onTunnelUrl(url)
            } else {
                startDashboardServeWatch()
                logManager.error(TAG, "Failed to get tailscale url. Are you logged in?")
                callback.onError("Failed to get tailscale url. Are you logged in?")
                callback.onTunnelUrl(null)
            }
        }
    }

    private fun launchInstalledTailscale(callback: TailscaleCallback) {
        val useProxy = ProxyHelper.probePort(PROXY_PORT)
        isProxyEnabled { enableProxy ->
            launchTailscaleDaemon(useProxy, enableProxy, callback)
        }
    }

    private fun isDeploymentCurrent(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = deploymentStatusCommand(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().lineSequence().lastOrNull() == DEPLOYMENT_CURRENT)
                }

                override fun onError(error: String) {
                    // Fail toward redeployment. A probe failure must never bless
                    // an unknown payload as current.
                    callback(false)
                }
            }
        )
    }

    /**
     * Upgrade an app-independent tailscaled process without touching its state
     * directory or persisted proxy/ADB/HTTPS opt-ins.
     */
    private fun redeployRunningTailscale(
        daemonFingerprint: String,
        callback: TailscaleCallback,
        attempt: Int = 0
    ) {
        if (attempt >= 3) {
            val error = "Could not stop the stale tailscale daemon for update"
            logManager.error(TAG, error)
            callback.onError(error)
            return
        }

        invalidateDashboardLifecycle()
        cancelDashboardServeWatch()
        securedDashboardDaemon = null
        callback.onLog("Updating tailscale for this app version...")

        adbShellExecutor.execute(
            command = buildKillFingerprintCommand(daemonFingerprint) + "; sleep 1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    // A Telegram or watchdog start can race the stop. Re-check
                    // both deployment and PID identity before overwriting.
                    isDeploymentCurrent { deploymentCurrent ->
                        getTailscaledFingerprint { currentFingerprint ->
                            when {
                                currentFingerprint == null ->
                                    installTailscale(callback) {
                                        launchInstalledTailscale(callback)
                                    }

                                deploymentCurrent ->
                                    reportRunningTunnel(callback)

                                else ->
                                    redeployRunningTailscale(
                                        currentFingerprint,
                                        callback,
                                        attempt + 1
                                    )
                            }
                        }
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to stop stale tailscale daemon: $error")
                    callback.onError("Failed to update tailscale: $error")
                }
            }
        )
    }

    fun launchTailscaleDaemon(useProxy: Boolean, enableProxy: Boolean, callback: TailscaleCallback) {
        invalidateDashboardLifecycle()
        val launchGeneration = dashboardLifecycleGeneration.get()
        securedDashboardDaemon = null
        cancelDashboardServeWatch()
        val cmd = buildString {
            append("nohup sh -c '")

            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            append(TAILSCALED_PATH)
            // Userspace networking required for android
            append(" --tun userspace-networking")
            // Where to store tailscale data
            append(" --statedir $tailscaleHome")
            // Communication port to listen to for tailscale commands
            append(" --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT")

            // Optionally start socks5 proxy to access other tailscale devices
            if (enableProxy) {
                append(" --socks5-server 127.0.0.1:$TAILSCALE_PROXY_PORT")
            }

            append("' > $TAILSCALE_LOG 2>&1 &")
        }
        // Arm the watcher before starting the child. It now tolerates the PID
        // not existing on its first probes, which minimizes the one-time
        // migration window before a persistent secure Serve config exists.
        startDashboardServeWatch()
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale daemon started")
                    callback.onLog("Tailscale daemon started")
                    // Protect the dashboard before replaying optional remote
                    // ADB. A freshly authenticated userspace daemon otherwise
                    // exposes raw localhost:8080 during this startup window.
                    getTunnelUrl { url ->
                        if (url == null) startDashboardServeWatch()
                        isAdbEnabled { adbOn ->
                            if (adbOn) replayAdbServe(0)
                            callback.onLog("Connect to tailscale to access $url")
                            callback.onTunnelUrl(url)
                        }
                    }
                }

                override fun onError(error: String) {
                    finishDashboardServeWatch(launchGeneration)
                    logManager.error(TAG, "Failed to start tailscale daemon: $error")
                    callback.onError("Failed to start tailscale daemon: $error")
                }
            }
        )
    }

    private fun installTailscale(callback: TailscaleCallback, onComplete: () -> Unit) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libtailscale.so"
        val expectedVersion = BuildConfig.VERSION_CODE.toLong()

        callback.onLog("Installing tailscale...")

        adbShellExecutor.execute(
            command = "test -f $srcPath && mkdir -p $tailscaleHome && " +
                "cp -f $srcPath $TAILSCALE_PATH && " +
                "ln -sf $TAILSCALE_PATH $TAILSCALED_PATH && " +
                "chmod +x $TAILSCALE_PATH && " +
                "printf '$expectedVersion\\n' > $TAILSCALE_VERSION_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Tailscale installed")
                    onComplete()
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to install tailscale: $error")
                    callback.onError("Failed to install tailscale: $error")
                }
            }
        )
    }

    fun generateLoginUrl(loginUrl: (String?) -> Unit) {
        launchTailscale(object : TailscaleCallback {
            override fun onLog(message: String) {}
            override fun onTunnelUrl(url: String?) {
                // The login command waits for login completion. Instead, call it with a 1ms timeout so we can get the login url from the status command
                runTailscaleCommand(
                    cmd = "login --hostname overdrive --timeout 1ms || echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            waitForLoginUrl(0, loginUrl)
                        }

                        override fun onError(error: String) {}
                    }
                )
            }
            override fun onError(error: String) {}
        })
    }

    fun waitForLoginUrl(attempt: Int, loginUrl: (String?) -> Unit) {
        if (attempt > 20) {
            loginUrl(null)
            return
        }

        Thread.sleep(2000)

        runTailscaleCommand(
            cmd = "status || echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val loginPattern = Regex("Log in at: (\\S+)", RegexOption.IGNORE_CASE)
                    val match = loginPattern.find(output)

                    if (match != null) {
                        val url = match.groupValues[1]
                        logManager.info(TAG, "Fetched login URL: $url")
                        loginUrl(url)
                        startDashboardServeWatch()
                    } else {
                        waitForLoginUrl(attempt + 1, loginUrl)
                    }
                }

                override fun onError(error: String) {}
            }
        )
    }

    fun needsLogin(callback: (Boolean) -> Unit) {
        isTunnelRunning { isRunning ->
            if (isRunning) {
                runTailscaleCommand(
                    cmd = "status || echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            if (output.contains("Logged out", ignoreCase = true)) {
                                callback(true)
                            } else if (output.contains("Tailscale is stopped", ignoreCase = true)) {
                                // This should never happen but can if the user runs tailscale down manually
                                callback(true)
                            } else {
                                callback(false)
                            }
                        }

                        override fun onError(error: String) {
                            // MUST complete the chain (issue #209). This used to be a
                            // no-op: when the status command failed (e.g. shell/socket
                            // still stale right after ACC-on), callers wedged until a
                            // full app restart. Preserve the legacy fail-open answer
                            // for UI/ADB callers; getTunnelUrl uses the stricter JSON
                            // backend-state probe before exposing the dashboard.
                            logManager.warn(TAG, "needsLogin: status check failed ($error), assuming logged in")
                            callback(false)
                        }
                    }
                )
            } else {
                callback(false)
            }
        }
    }

    fun runTailscaleCommand(cmd: String, callback: AdbShellExecutor.ShellCallback) {
        adbShellExecutor.execute(
            command = "$TAILSCALE_PATH --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT $cmd",
            callback = callback
        )
    }

    /**
     * Publish the dashboard through Tailscale's TCP Serve listener.
     *
     * PROXY v1 is the security boundary: HttpServer consumes the preamble and
     * disables its localhost auth fallback for that connection. A plain
     * `serve --tcp` forwarder would preserve the vulnerability because the
     * backend socket would still appear to originate from 127.0.0.1.
     */
    private fun applyDashboardServe(
        daemonFingerprint: String,
        attempt: Int,
        generation: Long = dashboardLifecycleGeneration.get(),
        callback: (Boolean) -> Unit
    ) {
        if (generation != dashboardLifecycleGeneration.get()) {
            callback(false)
            return
        }
        if (securedDashboardDaemon == daemonFingerprint) {
            callback(true)
            return
        }

        runTailscaleCommand(
            cmd = secureDashboardServeArgs(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (generation != dashboardLifecycleGeneration.get()) {
                        callback(false)
                        return
                    }
                    securedDashboardDaemon = daemonFingerprint
                    logManager.info(
                        TAG,
                        "Tailscale dashboard secured on tailnet :$DASHBOARD_PORT"
                    )
                    callback(true)
                }

                override fun onError(error: String) {
                    if (generation != dashboardLifecycleGeneration.get()) {
                        callback(false)
                        return
                    }
                    if (attempt < DASHBOARD_SERVE_REPLAY_ATTEMPTS) {
                        replayScheduler.schedule(
                            {
                                applyDashboardServe(
                                    daemonFingerprint,
                                    attempt + 1,
                                    generation,
                                    callback
                                )
                            },
                            DASHBOARD_SERVE_REPLAY_DELAY_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                        )
                        return
                    }

                    logManager.error(
                        TAG,
                        "Secure dashboard Serve failed after ${attempt + 1} attempts: $error"
                    )
                    // A direct Telegram/update restart happens in another
                    // process and cannot bump our in-memory generation. Do not
                    // let an error callback from the old PID disable or kill
                    // the replacement daemon; secure the replacement instead.
                    getTailscaledFingerprint { currentFingerprint ->
                        if (generation != dashboardLifecycleGeneration.get()) {
                            callback(false)
                        } else if (currentFingerprint == null) {
                            callback(false)
                        } else if (currentFingerprint != daemonFingerprint) {
                            applyDashboardServe(
                                currentFingerprint,
                                0,
                                generation,
                                callback
                            )
                        } else {
                            failCloseDashboardServe(
                                daemonFingerprint,
                                generation,
                                callback
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * Keep the tailnet port intercepted if secure Serve cannot be installed.
     *
     * Removing the Serve rule would restore userspace networking's raw
     * localhost forward and reopen the auth bypass. Pointing the listener at a
     * closed privileged port preserves Tailscale proxy/ADB functionality while
     * making the dashboard unavailable. If even that rule cannot be applied,
     * stop tailscaled rather than leave port 8080 exposed.
     */
    private fun failCloseDashboardServe(
        daemonFingerprint: String,
        generation: Long,
        callback: (Boolean) -> Unit
    ) {
        if (generation != dashboardLifecycleGeneration.get()) {
            callback(false)
            return
        }
        securedDashboardDaemon = null
        runTailscaleCommand(
            cmd = denyDashboardServeArgs(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (generation != dashboardLifecycleGeneration.get()) {
                        callback(false)
                        return
                    }
                    logManager.error(
                        TAG,
                        "Tailscale dashboard disabled because secure Serve is unavailable"
                    )
                    callback(false)
                }

                override fun onError(error: String) {
                    if (generation != dashboardLifecycleGeneration.get()) {
                        callback(false)
                        return
                    }
                    logManager.error(
                        TAG,
                        "Could not install dashboard deny route; stopping tailscaled: $error"
                    )
                    // Kill only the PID(s) whose secure configuration failed.
                    // A blanket pkill here can race a Telegram/update restart
                    // and terminate the fresh, correctly guarded daemon.
                    adbShellExecutor.execute(
                        command = buildKillFingerprintCommand(daemonFingerprint),
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(output: String) {
                                finishDashboardServeWatch(generation)
                                callback(false)
                            }

                            override fun onError(stopError: String) {
                                finishDashboardServeWatch(generation)
                                logManager.error(
                                    TAG,
                                    "Failed to stop unsafe tailscaled instance: $stopError"
                                )
                                callback(false)
                            }
                        }
                    )
                }
            }
        )
    }

    private fun buildKillFingerprintCommand(daemonFingerprint: String): String {
        // getTailscaledFingerprint returns only normalized decimal PIDs.
        // Keep a defensive empty fallback so no untrusted shell text can ever
        // reach this command if process enumeration behaves unexpectedly.
        val safePids = daemonFingerprint
            .split(' ')
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        if (safePids.isEmpty()) return "echo no-safe-tailscaled-pid"

        return buildString {
            append("for pid in ")
            append(safePids.joinToString(" "))
            append("; do ")
            append("if [ -r /proc/\$pid/cmdline ] && ")
            append("tr '\\000' ' ' < /proc/\$pid/cmdline | grep -q '/tailscaled'; ")
            append("then kill -9 \$pid 2>/dev/null; fi; ")
            append("done; echo stopped")
        }
    }

    private fun getBackendState(callback: (BackendState) -> Unit) {
        runTailscaleCommand(
            cmd = "status --json",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(parseBackendState(output))
                }

                override fun onError(error: String) {
                    callback(BackendState.UNKNOWN)
                }
            }
        )
    }

    private fun parseBackendState(output: String): BackendState {
        return try {
            when (jsonObjectFromOutput(output)?.optString("BackendState")?.lowercase()) {
                "running" -> BackendState.RUNNING
                "needslogin", "needsmachineauth", "stopped" -> BackendState.NOT_READY
                else -> BackendState.UNKNOWN
            }
        } catch (_: Exception) {
            BackendState.UNKNOWN
        }
    }

    private fun parseHttpsCertDomain(output: String): String? {
        val status = jsonObjectFromOutput(output) ?: return null
        val domains = status.optJSONArray("CertDomains")
            ?: status.optJSONObject("Self")?.optJSONArray("CertDomains")
            ?: return null
        val selfDomain = status.optJSONObject("Self")
            ?.optString("DNSName", "")
            ?.trim()
            ?.trimEnd('.')
            .orEmpty()

        // The Serve CLI terminates TLS for Self.DNSName, not an arbitrary first entry in
        // CertDomains. Prefer that exact name when it is provisionable so read-back
        // verification remains correct on tailnets that expose more than one certificate name.
        if (selfDomain.isNotEmpty()) {
            for (i in 0 until domains.length()) {
                val domain = domains.optString(i, "").trim().trimEnd('.')
                if (domain.equals(selfDomain, ignoreCase = true)) return selfDomain
            }
        }
        for (i in 0 until domains.length()) {
            val domain = domains.optString(i, "").trim().trimEnd('.')
            if (domain.isNotEmpty()) return domain
        }
        return null
    }

    private fun getHttpsCertDomain(callback: (String?) -> Unit) {
        runTailscaleCommand(
            cmd = "status --json",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(parseHttpsCertDomain(output))
                }

                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }

    private fun getHttpsServeSnapshot(callback: (HttpsServeSnapshot) -> Unit) {
        runTailscaleCommand(
            cmd = "serve status --json",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(parseHttpsServeSnapshot(output))
                }

                override fun onError(error: String) {
                    callback(HttpsServeSnapshot(HttpsServeOwnership.UNKNOWN))
                }
            }
        )
    }

    private fun getTailscaledFingerprint(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "pidof tailscaled 2>/dev/null || " +
                "ps -A | grep tailscaled | grep -v grep | awk '{print \$2}'",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val pids = output
                        .trim()
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                        .distinct()
                        .sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE }
                    callback(pids.joinToString(" ").takeIf { it.isNotEmpty() })
                }

                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }

    private fun cancelDashboardServeWatch() {
        dashboardServeWatchGeneration.set(-1L)
        dashboardServeWatchActive.set(false)
    }

    private fun finishDashboardServeWatch(generation: Long) {
        if (dashboardServeWatchGeneration.compareAndSet(generation, -1L)) {
            dashboardServeWatchActive.set(false)
        }
    }

    private fun isDashboardServeWatchCurrent(generation: Long): Boolean {
        return dashboardServeWatchActive.get() &&
            dashboardServeWatchGeneration.get() == generation
    }

    private fun startDashboardServeWatch() {
        val generation = dashboardLifecycleGeneration.get()
        if (!dashboardServeWatchActive.compareAndSet(false, true)) return
        dashboardServeWatchGeneration.set(generation)
        watchForDashboardServe(
            attempt = 0,
            generation = generation,
            sawDaemon = false
        )
    }

    private fun watchForDashboardServe(
        attempt: Int,
        generation: Long,
        sawDaemon: Boolean
    ) {
        if (!isDashboardServeWatchCurrent(generation)) return
        if (generation != dashboardLifecycleGeneration.get()) {
            finishDashboardServeWatch(generation)
            return
        }

        getTailscaledFingerprint { fingerprint ->
            if (generation != dashboardLifecycleGeneration.get()) {
                finishDashboardServeWatch(generation)
                return@getTailscaledFingerprint
            }
            if (fingerprint == null) {
                // Right after `nohup ... &` succeeds, process enumeration can
                // beat the child becoming visible. Retry that startup race,
                // but stop watching if a daemon we already observed exits.
                if (sawDaemon || attempt >= DASHBOARD_SERVE_WATCH_ATTEMPTS) {
                    finishDashboardServeWatch(generation)
                    return@getTailscaledFingerprint
                }
                replayScheduler.schedule(
                    {
                        watchForDashboardServe(
                            attempt + 1,
                            generation,
                            sawDaemon = false
                        )
                    },
                    DASHBOARD_SERVE_WATCH_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                return@getTailscaledFingerprint
            }

            getBackendState { state ->
                if (generation != dashboardLifecycleGeneration.get()) {
                    finishDashboardServeWatch(generation)
                    return@getBackendState
                }
                if (state == BackendState.RUNNING) {
                    applyDashboardServe(fingerprint, 0, generation) { secured ->
                        if (secured) replayHttpsServe(0)
                        finishDashboardServeWatch(generation)
                    }
                    return@getBackendState
                }

                if (attempt >= DASHBOARD_SERVE_WATCH_ATTEMPTS) {
                    finishDashboardServeWatch(generation)
                    logManager.warn(
                        TAG,
                        "Dashboard Serve watch expired before Tailscale login completed"
                    )
                    return@getBackendState
                }

                replayScheduler.schedule(
                    {
                        watchForDashboardServe(
                            attempt + 1,
                            generation,
                            sawDaemon = true
                        )
                    },
                    DASHBOARD_SERVE_WATCH_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        }
    }

    fun saveProxySettings(enabled: Boolean, callback: ((Boolean?) -> Unit)? = null) {
        isProxyEnabled { isEnabled ->
            if (enabled != isEnabled) {
                adbShellExecutor.execute(
                    command = "mkdir -p $tailscaleHome && echo $enabled > $TAILSCALE_PROXY_FILE && chmod 666 $TAILSCALE_PROXY_FILE",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            logManager.info(TAG, "Proxy settings saved to $TAILSCALE_PROXY_FILE")
                            callback?.invoke(true)
                        }
                        override fun onError(error: String) {
                            logManager.info(TAG, "Proxy settings failed to save to $TAILSCALE_PROXY_FILE")
                            callback?.invoke(false)
                        }
                    }
                )
            } else {
                callback?.invoke(null)
            }
        }
    }

    /**
     * Expose adbd to the tailnet via `tailscale serve --tcp`.
     *
     * tailscaled must run with `--tun userspace-networking` (no root => no TUN
     * device), so the kernel has no tailnet interface and an inbound connection
     * to the tailnet IP cannot reach adbd on its own. `serve --tcp` makes
     * tailscaled itself accept the inbound port and forward it into loopback,
     * which is the only root-free path to remote ADB.
     */
    fun applyAdbServe(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        if (enabled) {
            runTailscaleCommand(
                cmd = "serve --bg --tcp $ADB_PORT tcp://127.0.0.1:$ADB_PORT",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.info(TAG, "remote ADB exposed on tailnet :$ADB_PORT")
                        callback?.invoke(true)
                    }
                    override fun onError(error: String) {
                        logManager.warn(TAG, "serve --tcp failed: $error")
                        callback?.invoke(false)
                    }
                }
            )
            return
        }
        // `--tcp <port> off` with no positional target: adding the target makes the
        // CLI read it as the retired `serve <spec> off` form and reject it before
        // it ever contacts the daemon. Deliberately never `serve reset` (drops ALL
        // serve config, including the HTTP share this launcher may publish) nor
        // `serve clear` (scoped to Tailscale Services, not node serve config).
        runTailscaleCommand(
            cmd = "serve --tcp $ADB_PORT off",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "remote ADB withdrawn from tailnet")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "serve --tcp off failed: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    private fun HttpsServeOwnership.isOverdriveOwned(): Boolean {
        return this == HttpsServeOwnership.OWNED ||
            this == HttpsServeOwnership.LEGACY_OWNED
    }

    /**
     * Withdraw only an HTTPS rule that the read-back parser proved belongs to Overdrive.
     *
     * The legacy command is kept solely for upgrading installations that already persisted
     * the old TLS-terminated TCP rule. Unrelated port-443 rules are never changed.
     */
    private fun withdrawOwnedHttpsServe(
        current: HttpsServeSnapshot,
        callback: (HttpsServeSnapshot) -> Unit
    ) {
        val command = when (current.ownership) {
            HttpsServeOwnership.OWNED -> disableHttpsServeArgs()
            HttpsServeOwnership.LEGACY_OWNED -> disableLegacyHttpsServeArgs()
            HttpsServeOwnership.FREE,
            HttpsServeOwnership.CONFLICT,
            HttpsServeOwnership.UNKNOWN -> {
                callback(current)
                return
            }
        }

        runTailscaleCommand(
            cmd = command,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    getHttpsServeSnapshot(callback)
                }

                override fun onError(error: String) {
                    // A concurrent caller may already have removed or replaced the rule.
                    // Read back before reporting failure and never broaden the cleanup.
                    getHttpsServeSnapshot { after ->
                        if (after.ownership.isOverdriveOwned()) {
                            logManager.warn(TAG, "HTTPS withdrawal failed: $error")
                        }
                        callback(after)
                    }
                }
            }
        )
    }

    private fun installHttpsServe(
        certDomain: String,
        callback: (Boolean) -> Unit
    ) {
        runTailscaleCommand(
            cmd = secureHttpsServeArgs(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    // Exit status is not authoritative. Verify the HTTPS listener, root
                    // handler, exact loopback backend and certificate domain from Serve JSON.
                    getHttpsServeSnapshot { after ->
                        val verified =
                            after.ownership == HttpsServeOwnership.OWNED &&
                                after.domain.equals(certDomain, ignoreCase = true)
                        if (verified) {
                            logManager.info(TAG, "web UI published at https://$certDomain")
                            callback(true)
                            return@getHttpsServeSnapshot
                        }

                        logManager.warn(
                            TAG,
                            "HTTPS command returned success without the expected Serve rule"
                        )
                        if (!after.ownership.isOverdriveOwned()) {
                            callback(false)
                            return@getHttpsServeSnapshot
                        }

                        // Keep enable transactional. If the CLI installed an app-shaped rule
                        // under an unexpected identity, remove only that exact owned rule.
                        withdrawOwnedHttpsServe(after) { cleanup ->
                            if (cleanup.ownership.isOverdriveOwned()
                                || cleanup.ownership == HttpsServeOwnership.UNKNOWN
                            ) {
                                logManager.warn(
                                    TAG,
                                    "Could not withdraw unexpected HTTPS Serve rule"
                                )
                            }
                            callback(false)
                        }
                    }
                }

                override fun onError(error: String) {
                    logManager.warn(TAG, "HTTPS Serve failed: $error")
                    callback(false)
                }
            }
        )
    }

    private fun replaceOwnedHttpsServe(
        certDomain: String,
        current: HttpsServeSnapshot,
        callback: (Boolean) -> Unit
    ) {
        withdrawOwnedHttpsServe(current) { after ->
            when {
                after.ownership == HttpsServeOwnership.OWNED &&
                    after.domain.equals(certDomain, ignoreCase = true) -> callback(true)
                after.ownership == HttpsServeOwnership.FREE ->
                    installHttpsServe(certDomain, callback)
                else -> {
                    logManager.warn(
                        TAG,
                        "HTTPS migration stopped because port $HTTPS_PORT is no longer free"
                    )
                    callback(false)
                }
            }
        }
    }

    /**
     * Publish the dashboard on trusted tailnet HTTPS without weakening HttpServer authentication.
     *
     * Standard HTTPS Serve terminates TLS and supplies forwarding metadata to the loopback
     * backend. HttpServer treats that metadata as tunnel-originated, disabling only its local
     * no-token fallback while preserving normal JWT authentication and WebSocket behavior.
     */
    fun applyHttpsServe(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        if (!enabled) {
            getHttpsServeSnapshot { current ->
                withdrawOwnedHttpsServe(current) { after ->
                    val removed =
                        !after.ownership.isOverdriveOwned() &&
                            after.ownership != HttpsServeOwnership.UNKNOWN
                    if (removed && current.ownership.isOverdriveOwned()) {
                        logManager.info(TAG, "web UI withdrawn from tailnet HTTPS")
                    }
                    callback?.invoke(removed)
                }
            }
            return
        }

        // Non-interactive capability preflight. `serve --https` can exit 0 before applying
        // anything when HTTPS needs admin approval; requiring a real CertDomains entry avoids
        // both that false success and an interactive CLI wait on the single shell executor.
        getHttpsCertDomain { certDomain ->
            if (certDomain == null) {
                logManager.warn(
                    TAG,
                    "HTTPS unavailable: tailnet has no provisionable certificate domain"
                )
                callback?.invoke(false)
                return@getHttpsCertDomain
            }
            getHttpsServeSnapshot { current ->
                when (current.ownership) {
                    HttpsServeOwnership.CONFLICT -> {
                        logManager.warn(
                            TAG,
                            "HTTPS not enabled: tailnet port $HTTPS_PORT already has another Serve rule"
                        )
                        callback?.invoke(false)
                    }
                    HttpsServeOwnership.UNKNOWN -> callback?.invoke(false)
                    HttpsServeOwnership.OWNED -> {
                        if (current.domain.equals(certDomain, ignoreCase = true)) {
                            callback?.invoke(true)
                        } else {
                            replaceOwnedHttpsServe(
                                certDomain,
                                current
                            ) { callback?.invoke(it) }
                        }
                    }
                    HttpsServeOwnership.LEGACY_OWNED -> {
                        logManager.info(
                            TAG,
                            "migrating legacy Tailscale HTTPS Serve rule"
                        )
                        replaceOwnedHttpsServe(
                            certDomain,
                            current
                        ) { callback?.invoke(it) }
                    }
                    HttpsServeOwnership.FREE ->
                        installHttpsServe(certDomain) { callback?.invoke(it) }
                }
            }
        }
    }

    private fun replayHttpsServe(attempt: Int) {
        isHttpsEnabled { stillEnabled ->
            if (!stillEnabled) return@isHttpsEnabled
            applyHttpsServe(true) { ok ->
                if (ok) return@applyHttpsServe
                if (attempt >= ADB_SERVE_REPLAY_ATTEMPTS) {
                    logManager.warn(TAG, "HTTPS replay gave up after ${attempt + 1} attempts")
                    return@applyHttpsServe
                }
                replayScheduler.schedule(
                    { replayHttpsServe(attempt + 1) },
                    ADB_SERVE_REPLAY_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        }
    }

    private fun sweepWithdrawHttpsIfStillDisabled() {
        isHttpsEnabled { enabledNow ->
            if (!enabledNow) applyHttpsServe(false)
        }
    }

    fun saveHttpsSettings(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        isTunnelRunning { running ->
            if (!enabled) {
                writeHttpsSentinel(false) { persisted ->
                    if (!persisted || !running) {
                        callback?.invoke(persisted)
                        return@writeHttpsSentinel
                    }
                    applyHttpsServe(false) { applied ->
                        callback?.invoke(applied)
                        replayScheduler.schedule(
                            { sweepWithdrawHttpsIfStillDisabled() },
                            ADB_SERVE_WITHDRAW_SWEEP_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                        )
                    }
                }
                return@isTunnelRunning
            }

            // Do not store a speculative opt-in while logged out/stopped: the setting would read
            // ON even though no capability or concrete Serve rule had ever been verified.
            if (!running) {
                callback?.invoke(false)
                return@isTunnelRunning
            }
            applyHttpsServe(true) { applied ->
                if (!applied) {
                    callback?.invoke(false)
                    return@applyHttpsServe
                }
                writeHttpsSentinel(true) { persisted ->
                    if (persisted) {
                        callback?.invoke(true)
                    } else {
                        applyHttpsServe(false) { callback?.invoke(false) }
                    }
                }
            }
        }
    }

    private fun writeHttpsSentinel(enabled: Boolean, callback: ((Boolean) -> Unit)?) {
        adbShellExecutor.execute(
            command = "mkdir -p $tailscaleHome && echo $enabled > $TAILSCALE_HTTPS_FILE && chmod 600 $TAILSCALE_HTTPS_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback?.invoke(true)
                }

                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to persist HTTPS setting: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    fun isHttpsEnabled(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_HTTPS_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "true")
                }

                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    private fun resolveServedHttpsUrl(callback: (String?) -> Unit) {
        isHttpsEnabled { enabled ->
            if (!enabled) {
                callback(null)
                return@isHttpsEnabled
            }
            getHttpsCertDomain { certDomain ->
                if (certDomain == null) {
                    callback(null)
                    return@getHttpsCertDomain
                }
                getHttpsServeSnapshot { current ->
                    val active =
                        current.ownership == HttpsServeOwnership.OWNED &&
                            current.domain.equals(certDomain, ignoreCase = true)
                    callback(if (active) "https://$certDomain" else null)
                }
            }
        }
    }

    /**
     * Re-apply the persisted serve config after a daemon start, retrying while
     * tailscaled is still coming up. The launch shell returns as soon as `nohup
     * ... &` forks, so the first attempt can beat the daemon binding its socket;
     * without a retry remote ADB would stay down until the next restart.
     */
    private fun replayAdbServe(attempt: Int) {
        // Re-read the opt-in before EVERY attempt. The retry window spans seconds,
        // and a user who disables ADB inside it would otherwise be silently
        // re-exposed by a later attempt carrying the stale "on" decision.
        isAdbEnabled { stillEnabled ->
            if (!stillEnabled) {
                logManager.info(TAG, "remote ADB replay aborted — opt-in withdrawn")
                return@isAdbEnabled
            }
            applyAdbServe(true) { ok ->
                if (ok) return@applyAdbServe
                if (attempt >= ADB_SERVE_REPLAY_ATTEMPTS) {
                    logManager.warn(TAG, "remote ADB replay gave up after ${attempt + 1} attempts")
                    return@applyAdbServe
                }
                // Back off on a dedicated timer, never by sleeping here: this callback
                // runs on the single shell executor that also serves the settings-dialog
                // probes, and blocking it would let the dialog read stale switch state.
                replayScheduler.schedule(
                    { replayAdbServe(attempt + 1) },
                    ADB_SERVE_REPLAY_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /**
     * Confirmation withdrawal, run after the replay ladder can no longer fire.
     * Re-reads the opt-in first: the user may have re-enabled ADB in the meantime,
     * and an unconditional withdrawal here would silently kill that fresh enable.
     */
    private fun sweepWithdrawIfStillDisabled() {
        isAdbEnabled { enabledNow ->
            if (!enabledNow) applyAdbServe(false)
        }
    }

    /**
     * Persist the remote-ADB opt-in and apply it when the tunnel is already up.
     * Remote ADB grants UID-2000 shell to anyone on the tailnet, so this stays
     * opt-in and defaults off.
     */
    fun saveAdbSettings(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        // Ordering is deliberately ASYMMETRIC, always failing toward "not exposed":
        // an enable is applied before it is persisted (so a failed apply can't
        // silently expose ADB on a later restart), a disable is persisted before it
        // is applied (so a failed withdrawal can't re-arm on the next restart).
        isTunnelRunning { running ->
            if (!enabled) {
                writeAdbSentinel(false) { persisted ->
                    if (!running) {
                        callback?.invoke(persisted)
                    } else {
                        applyAdbServe(false) { applied ->
                            callback?.invoke(persisted && applied)
                            // A replay attempt that read the sentinel just before it
                            // flipped can still land after this withdrawal; sweep once
                            // more past the retry window so it can't outlive the toggle.
                            replayScheduler.schedule(
                                { sweepWithdrawIfStillDisabled() },
                                ADB_SERVE_WITHDRAW_SWEEP_MS,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )
                        }
                    }
                }
                return@isTunnelRunning
            }
            if (!running) {
                // Nothing live to apply to — the sentinel is the whole state and
                // launchTailscaleDaemon replays it on next start.
                writeAdbSentinel(true, callback)
                return@isTunnelRunning
            }
            applyAdbServe(true) { applied ->
                if (!applied) {
                    callback?.invoke(false)
                    return@applyAdbServe
                }
                writeAdbSentinel(true) { persisted ->
                    if (persisted) {
                        callback?.invoke(true)
                    } else {
                        // Couldn't record the opt-in, so withdraw what we just
                        // exposed: the UI reads the sentinel and would otherwise
                        // show OFF with ADB actually reachable and no way to revoke.
                        applyAdbServe(false) { callback?.invoke(false) }
                    }
                }
            }
        }
    }

    private fun writeAdbSentinel(enabled: Boolean, callback: ((Boolean) -> Unit)?) {
        // 600 rather than the 666 used by the sibling proxy flag — least privilege
        // only. It is NOT a security boundary: this file is owned by the shell UID
        // and any shell-UID process keeps write access via the owner bits, so a
        // local foothold can still arm this. The real gate is ADB key auth.
        adbShellExecutor.execute(
            command = "mkdir -p $tailscaleHome && echo $enabled > $TAILSCALE_ADB_FILE && chmod 600 $TAILSCALE_ADB_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to persist ADB setting: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    fun isAdbEnabled(callback: ((Boolean) -> Unit)) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_ADB_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "true")
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    /**
     * Whether tailscaled is really forwarding the ADB port right now. Falls back
     * to the stored opt-in if the status probe itself fails, so a transient shell
     * error doesn't hide a working endpoint.
     */
    private fun isAdbServeActive(callback: (Boolean) -> Unit) {
        isAdbEnabled { adbOn ->
            if (!adbOn) {
                callback(false)
                return@isAdbEnabled
            }
            runTailscaleCommand(
                cmd = "serve status",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        // ":5555" rather than a bare "5555" so a stray digit run
                        // elsewhere in the status output can't read as a live
                        // forwarder. With no serve config the output names no port.
                        callback(output.contains(":$ADB_PORT"))
                    }
                    // Probe failed, not "not serving" — defer to the stored opt-in
                    // rather than hiding an endpoint that may well be working.
                    override fun onError(error: String) = callback(true)
                }
            )
        }
    }

    /**
     * Tailnet address to point `adb connect` at, or null when unavailable.
     * Gated on the daemon's reported serve config rather than the stored opt-in
     * alone, so a replay that never took doesn't advertise a dead address. The
     * gate fails open if the status probe itself errors — see [isAdbServeActive].
     */
    fun getAdbEndpoint(callback: (String?) -> Unit) {
        isAdbServeActive { serving ->
            if (!serving) {
                callback(null)
                return@isAdbServeActive
            }
            isTunnelRunning { isRunning ->
                needsLogin { needsLogin ->
                    if (isRunning && !needsLogin) {
                        runTailscaleCommand(
                            cmd = "ip --1",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(output: String) {
                                    val ip = output.trim()
                                    callback(if (ip.isEmpty()) null else "$ip:$ADB_PORT")
                                }
                                override fun onError(error: String) = callback(null)
                            }
                        )
                    } else {
                        callback(null)
                    }
                }
            }
        }
    }

    fun isProxyEnabled(callback: ((Boolean) -> Unit)) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_PROXY_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val enabledText = output.trim()
                    if (enabledText == "true") {
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        getTailscaledFingerprint { callback(it != null) }
    }

    fun stopTunnel(callback: TailscaleCallback) {
        invalidateDashboardLifecycle()
        cancelDashboardServeWatch()
        securedDashboardDaemon = null
        logManager.info(TAG, "Stopping tailscale tunnel...")
        callback.onLog("Stopping tailscale tunnel...")

        adbShellExecutor.execute(
            command = "pkill 'tailscaled' 2>/dev/null; rm -f $TAILSCALE_LOG; echo stopped",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale tunnel stopped")
                    callback.onLog("Tailscale tunnel stopped")
                    callback.onTunnelUrl(null)
                }

                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.info(TAG, "Tailscale tunnel stopped (with warning: $error)")
                    callback.onLog("Tailscale tunnel stopped")
                    callback.onTunnelUrl(null)
                }
            }
        )
    }

    fun getTunnelUrl(callback: (String?) -> Unit) {
        getTailscaledFingerprint { fingerprint ->
            if (fingerprint == null) {
                callback(null)
                return@getTailscaledFingerprint
            }

            getBackendState { state ->
                if (state != BackendState.RUNNING) {
                    callback(null)
                    return@getBackendState
                }

                applyDashboardServe(fingerprint, 0) { secured ->
                    if (!secured) {
                        callback(null)
                        return@applyDashboardServe
                    }
                    isHttpsEnabled { httpsEnabled ->
                        if (!httpsEnabled) {
                            resolvePlainTunnelUrl(callback)
                            return@isHttpsEnabled
                        }
                        applyHttpsServe(true) { applied ->
                            if (!applied) {
                                resolvePlainTunnelUrl(callback)
                                return@applyHttpsServe
                            }
                            resolveServedHttpsUrl { httpsUrl ->
                                if (httpsUrl != null) callback(httpsUrl)
                                else resolvePlainTunnelUrl(callback)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolvePlainTunnelUrl(callback: (String?) -> Unit) {
        runTailscaleCommand(
            cmd = "ip --1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val ip = output.trim()
                    callback(if (ip.isEmpty()) null else "http://$ip:$DASHBOARD_PORT")
                }

                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }

    /**
     * Disable tailscale environment (cleanup).
     * WARNING: This will not remove the device from the tailscale console but will disconnect
     */
    fun disableEnvironment(callback: TailscaleCallback? = null) {
        invalidateDashboardLifecycle()
        cancelDashboardServeWatch()
        securedDashboardDaemon = null
        logManager.warn(TAG, "⚠️ Disabling tailscale environment - will need to login again!")
        callback?.onLog("⚠️ Disabling environment (will need login again)...")

        adbShellExecutor.execute(
            command = "pkill 'tailscaled' 2>/dev/null; rm -rf $tailscaleHome; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale environment disabled")
                    callback?.onLog("Environment disabled")
                    callback?.onTunnelUrl(null)
                }

                override fun onError(error: String) {}
            }
        )
    }
}
