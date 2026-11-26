package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.R
import com.overdrive.app.logging.LogManager

/**
 * Launches tunnel processes via ADB shell for remote access.
 * 
 * Currently supports Cloudflared tunnel only.
 * Uses AdbShellExecutor for shell operations.
 */
class TunnelLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "TunnelLauncher"
        private const val LAUNCH_GUARD_TIMEOUT_SECONDS = 90L
        
        // Cloudflared paths
        private val CLOUDFLARED_TMP_PATH: String get() = com.overdrive.app.util.ScratchRoot.path("cloudflared")
        private val CLOUDFLARED_LOG: String get() = com.overdrive.app.util.ScratchRoot.path("cloudflared.log")
        private val CLOUDFLARED_DISABLED_SENTINEL: String get() = com.overdrive.app.util.ScratchRoot.path("cloudflared.disabled")
        
        private val launchLock = Any()
        private var activeLaunch: LaunchFlight? = null
        private val launchGuardScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "cloudflared-launch-guard").apply { isDaemon = true }
            }

        // Automatic restarts back off after the 2nd consecutive failed launch: 2, 4, then 5 min.
        private const val AUTO_RETRY_BASE_MS = 2 * 60_000L
        private const val AUTO_RETRY_MAX_MS = 5 * 60_000L
        private var consecutiveFailures = 0 // guarded by launchLock
        private var nextAutoStartAtMs = 0L // guarded by launchLock

        /** Milliseconds until an automatic (non-user) start may run; 0 when due. */
        fun autoStartDelayMs(): Long = synchronized(launchLock) {
            if (consecutiveFailures < 2) 0L else (nextAutoStartAtMs - monotonicNowMs()).coerceAtLeast(0L)
        }

        fun resetAutoStartBackoff() = synchronized(launchLock) {
            consecutiveFailures = 0
            nextAutoStartAtMs = 0L
        }

        // Monotonic: head units often correct their wall clock after boot.
        private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

        private fun markActiveLaunchCancelled() = synchronized(launchLock) {
            activeLaunch?.cancelled = true
        }

        private fun autoRetryDelayMs(failures: Int): Long =
            if (failures < 2) 0L
            else minOf(AUTO_RETRY_MAX_MS, AUTO_RETRY_BASE_MS shl minOf(failures - 2, 4))
    }
    
    interface TunnelCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }

    private class LaunchFlight(callback: TunnelCallback) {
        val callbacks = mutableListOf(callback)
        @Volatile var urlDelivered = false
        @Volatile var cancelled = false
    }

    /**
     * Dedicated scheduler for delayed work (post-kill settles, URL polls).
     * Without this we'd `Thread.sleep` inside ADB-callback handlers, parking
     * the shared single-thread ADB executor for the sleep duration. Same
     * pattern as ZrokLauncher.reconcileScheduler.
     */
    private val strings: Context get() = com.overdrive.app.util.AppLocaleContext.of(context)

    private val pollScheduler: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "tunnel-poll").apply { isDaemon = true }
            }

    fun shutdown() {
        try { pollScheduler.shutdownNow() } catch (e: Exception) {
            logManager.warn(TAG, "pollScheduler shutdown failed: ${e.message}")
        }
    }
    
    /**
     * Launch Cloudflared tunnel via ADB shell.
     * Creates a public URL that forwards to local port 8080.
     * Reuses existing tunnel if already running.
     */
    fun launchCloudflared(callback: TunnelCallback) {
        val (flight, ownsLaunch) = registerLaunch(callback)
        if (!ownsLaunch) {
            logManager.info(TAG, "Cloudflared launch already in progress; joining existing launch")
            notifyCallbacks(listOf(callback)) { it.onLog("Tunnel launch already in progress") }
            return
        }

        // A shared scheduler keeps the process-wide guard safe even if the
        // TunnelLauncher instance that started the work is shut down.
        launchGuardScheduler.schedule({
            notifyCallbacks(finishLaunch(flight)) {
                it.onError(strings.getString(R.string.cloudflared_error_launch_timeout))
            }
        }, LAUNCH_GUARD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

        val guardedCallback = object : TunnelCallback {
            override fun onLog(message: String) {
                notifyCallbacks(callbacksFor(flight)) { it.onLog(message) }
            }

            override fun onTunnelUrl(url: String) {
                if (url.isNotBlank()) flight.urlDelivered = true
                notifyCallbacks(finishLaunch(flight)) { it.onTunnelUrl(url) }
            }

            override fun onError(error: String) {
                notifyCallbacks(finishLaunch(flight)) { it.onError(error) }
            }
        }

        try {
            launchCloudflaredGuarded(guardedCallback)
        } catch (e: Exception) {
            notifyCallbacks(finishLaunch(flight)) {
                it.onError(strings.getString(R.string.cloudflared_error_launch_failed, e.message))
            }
        }
    }

    private fun registerLaunch(callback: TunnelCallback): Pair<LaunchFlight, Boolean> =
        synchronized(launchLock) {
            val current = activeLaunch
            if (current != null) {
                current.callbacks.add(callback)
                current to false
            } else {
                val created = LaunchFlight(callback)
                activeLaunch = created
                created to true
            }
        }

    private fun callbacksFor(flight: LaunchFlight): List<TunnelCallback> =
        synchronized(launchLock) {
            if (activeLaunch === flight) flight.callbacks.toList() else emptyList()
        }

    private fun finishLaunch(flight: LaunchFlight): List<TunnelCallback> =
        synchronized(launchLock) {
            if (activeLaunch !== flight) {
                emptyList()
            } else {
                val callbacks = flight.callbacks.toList()
                flight.callbacks.clear()
                activeLaunch = null
                if (flight.urlDelivered) {
                    consecutiveFailures = 0
                    nextAutoStartAtMs = 0L
                } else if (!flight.cancelled) {
                    consecutiveFailures++
                    nextAutoStartAtMs = monotonicNowMs() + autoRetryDelayMs(consecutiveFailures)
                }
                callbacks
            }
        }

    private fun notifyCallbacks(
        callbacks: List<TunnelCallback>,
        action: (TunnelCallback) -> Unit
    ) {
        callbacks.forEach {
            try {
                action(it)
            } catch (e: Exception) {
                logManager.warn(TAG, "Tunnel callback failed: ${e.message}")
            }
        }
    }

    private fun launchCloudflaredGuarded(callback: TunnelCallback) {
        logManager.info(TAG, "Launching Cloudflared tunnel...")
        callback.onLog("Checking for existing tunnel...")
        
        isTunnelRunning { isRunning ->
            if (isRunning) {
                // Tunnel already running, try to get existing URL
                logManager.info(TAG, "Cloudflared already running, checking for URL...")
                callback.onLog("Tunnel already running, getting URL...")
                getTunnelUrl { existingUrl ->
                    if (existingUrl != null) {
                        // Check if current tunnel matches configured version
                        val isPaid = com.overdrive.app.config.CloudflaredPaidConfig.isPaidVersion()
                        val isFreeUrl = existingUrl.contains("trycloudflare.com")
                        
                        if (isPaid && isFreeUrl) {
                            logManager.info(TAG, "Running tunnel is free but paid version selected. Restarting...")
                            callback.onLog("Restarting tunnel for paid version...")
                            stopTunnel(object : TunnelCallback {
                                override fun onLog(message: String) {}
                                override fun onTunnelUrl(url: String) { launchCloudflaredInternal(callback) }
                                // Never launch a second cloudflared next to one that is still running.
                                override fun onError(error: String) {
                                    callback.onError("Could not stop the free tunnel before switching to paid: $error")
                                }
                            })
                            return@getTunnelUrl
                        }

                        logManager.info(TAG, "Reusing existing tunnel: $existingUrl")
                        callback.onLog("Reusing existing tunnel")
                        callback.onTunnelUrl(existingUrl)
                    } else {
                        // Running but no URL - wait for it
                        logManager.info(TAG, "Tunnel running but no URL yet, waiting...")
                        callback.onLog("Waiting for tunnel URL...")
                        waitForTunnelUrl(callback, 1)
                    }
                }
            } else {
                callback.onLog("Setting up cloudflared...")
                prepareCloudflared(callback)
            }
        }
    }

    private fun prepareCloudflared(callback: TunnelCallback) {
        val srcPath = "${context.applicationInfo.nativeLibraryDir}/libcloudflared.so"
        adbShellExecutor.execute(
            command = prepareCloudflaredCommand(srcPath),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    when {
                        output.contains("refreshed") || output.contains("existing") ->
                            launchCloudflaredInternal(callback)
                        output.contains("nosrc") -> {
                            logManager.error(TAG, "libcloudflared.so not found")
                            callback.onError(strings.getString(R.string.cloudflared_error_binary_missing))
                        }
                        else -> {
                            logManager.error(TAG, "Failed to install cloudflared: ${output.trim()}")
                            callback.onError(strings.getString(R.string.cloudflared_error_install_failed))
                        }
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to install cloudflared: $error")
                    callback.onError(strings.getString(R.string.cloudflared_error_install_failed_detail, error))
                }
            }
        )
    }

    // Re-copied from the APK (atomic mv) when the installed binary is missing, empty or a different size;
    // an existing non-empty binary is the fallback when the copy fails.
    private fun prepareCloudflaredCommand(srcPath: String): String =
        "D=$CLOUDFLARED_TMP_PATH; S=$srcPath; rm -f \"\$D\".new.* 2>/dev/null; " +
        "if test -x \"\$D\" && test -s \"\$D\" && test -f \"\$S\" && " +
        "[ \"\$(wc -c < \"\$D\" | tr -d ' ')\" = \"\$(wc -c < \"\$S\" | tr -d ' ')\" ]; then echo existing; else " +
        "STAGED=\"\$D.new.\$\$\"; " +
        "if test -f \"\$S\" && cp \"\$S\" \"\$STAGED\" 2>/dev/null && chmod +x \"\$STAGED\" && mv -f \"\$STAGED\" \"\$D\"; then " +
        "echo refreshed; elif test -x \"\$D\" && test -s \"\$D\"; then rm -f \"\$STAGED\"; echo existing; " +
        "elif test -f \"\$S\"; then rm -f \"\$STAGED\"; echo copyfail; else echo nosrc; fi; fi"
    
    private fun launchCloudflaredInternal(callback: TunnelCallback) {
        callback.onLog("Starting cloudflared tunnel...")
        
        // Port probe, not `pgrep -f sing-box`: that also matches its own sh -c wrapper.
        launchCloudflaredWithConfig(callback, com.overdrive.app.mqtt.ProxyHelper.probePort(8119))
    }
    
    private fun launchCloudflaredWithConfig(callback: TunnelCallback, useProxy: Boolean) {
        val cmd = buildString {
            // A stop or park that lands while this launch is in flight wins over it.
            append("if [ -f $CLOUDFLARED_DISABLED_SENTINEL ] || [ -f ${com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH} ]; ")
            append("then echo CF_START_CANCELLED; else ")
            append("nohup sh -c '")

            if (useProxy) {
                val proxyUrl = "http://127.0.0.1:8119"
                append("export http_proxy=$proxyUrl && ")
                append("export https_proxy=$proxyUrl && ")
                append("export HTTP_PROXY=$proxyUrl && ")
                append("export HTTPS_PROXY=$proxyUrl && ")
                append("export no_proxy=\"localhost,127.0.0.1,::1\" && ")
                append("export NO_PROXY=\"localhost,127.0.0.1,::1\" && ")
                callback.onLog("Using sing-box proxy...")
            } else {
                callback.onLog("Direct connection (no proxy)...")
            }

            // --retries caps the reconnect backoff, it does not limit attempts (see CloudflaredPaidConfig).
            append("$CLOUDFLARED_TMP_PATH ${com.overdrive.app.config.CloudflaredPaidConfig.getArgs()}")
            append("' > $CLOUDFLARED_LOG 2>&1 & fi")
        }
        
        // Redact the cloudflared paid token before logging — the command can
        // contain `--token <secret>`, and DEBUG logs are written to logcat and
        // (in LOG_CAPTURE builds) uploaded.
        logManager.debug(TAG, "Executing: ${cmd.replace(Regex("--token\\s+\\S+"), "--token ***")}")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.contains("CF_START_CANCELLED")) {
                        logManager.info(TAG, "Cloudflared start cancelled: disable sentinel or parked marker present")
                        markActiveLaunchCancelled()
                        callback.onError(strings.getString(R.string.cloudflared_start_cancelled))
                        return
                    }
                    logManager.info(TAG, "Cloudflared launch command sent")
                    callback.onLog("Waiting for tunnel URL...")
                    waitForTunnelUrl(callback, 1)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch cloudflared: $error")
                    callback.onError(strings.getString(R.string.cloudflared_error_spawn_failed, error))
                }
            }
        )
    }
    
    private fun waitForTunnelUrl(callback: TunnelCallback, attempt: Int) {
        if (attempt > 30) {
            // Timeout - get final log
            adbShellExecutor.execute(
                command = "cat $CLOUDFLARED_LOG 2>/dev/null",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.error(TAG, "Cloudflared timed out. Log: ${output.takeLast(500)}")
                        callback.onError(
                            if (output.isBlank()) strings.getString(R.string.cloudflared_no_output)
                            else strings.getString(R.string.cloudflared_error_no_url, output.takeLast(500))
                        )
                    }

                    override fun onError(error: String) {
                        callback.onError(strings.getString(R.string.cloudflared_error_url_timeout))
                    }
                }
            )
            return
        }

        // Schedule the next poll on pollScheduler instead of Thread.sleep
        // here. This function runs from inside an ADB-executor callback;
        // a 1s sleep would park the shared single-thread executor and
        // serialize every other queued shell command behind it. Across
        // 30 attempts that's up to 30s of executor blackout while a
        // single cloudflared handshake completes.
        pollScheduler.schedule({
            doWaitForTunnelUrlPoll(callback, attempt)
        }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun doWaitForTunnelUrlPoll(callback: TunnelCallback, attempt: Int) {
        adbShellExecutor.execute(
            command = com.overdrive.app.config.CloudflaredPaidConfig.getUrlExtractionCommand(CLOUDFLARED_LOG),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    val tunnelUrl = com.overdrive.app.config.CloudflaredPaidConfig.parseUrl(logContent)
                    
                    if (tunnelUrl != null) {
                        logManager.info(TAG, "Tunnel established: $tunnelUrl")
                        callback.onLog("Tunnel established: $tunnelUrl")
                        callback.onTunnelUrl(tunnelUrl)
                        return
                    }
                    
                    // Check for proxy errors
                    if (logContent.contains("proxyconnect") ||
                        (logContent.contains("proxy") && logContent.contains("refused"))) {
                        logManager.error(TAG, "Proxy error - is sing-box running?")
                        callback.onError(strings.getString(R.string.cloudflared_error_proxy, logContent.takeLast(200)))
                        return
                    }
                    
                    // Check for connection errors
                    if (logContent.contains("connection refused") || logContent.contains("dial tcp")) {
                        logManager.error(TAG, "Connection error: $logContent")
                        callback.onError(strings.getString(R.string.cloudflared_error_connection, logContent.takeLast(300)))
                        return
                    }
                    
                    callback.onLog("Waiting... ($attempt/30)")
                    waitForTunnelUrl(callback, attempt + 1)
                }
                
                override fun onError(error: String) {
                    callback.onLog("Waiting... ($attempt/30)")
                    waitForTunnelUrl(callback, attempt + 1)
                }
            }
        )
    }
    
    /**
     * Stop the tunnel.
     */
    fun stopTunnel(callback: TunnelCallback) {
        logManager.info(TAG, "Stopping tunnel...")
        callback.onLog("Stopping tunnel...")
        
        // Only an explicit STOPPED marker counts as stopped.
        adbShellExecutor.execute(
            command = DaemonLauncher.killCloudflaredAndVerify(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    when {
                        output.contains(DaemonLauncher.CLOUDFLARED_STILL_RUNNING) -> {
                            logManager.error(TAG, "Tunnel stop failed: cloudflared still running after SIGKILL")
                            callback.onError("cloudflared is still running after kill")
                        }
                        output.contains(DaemonLauncher.CLOUDFLARED_STOPPED) -> {
                            logManager.info(TAG, "Tunnel stopped")
                            callback.onLog("Tunnel stopped")
                            callback.onTunnelUrl("")
                        }
                        else -> {
                            logManager.error(TAG, "Tunnel stop not verified: no marker in output '${output.take(200)}'")
                            callback.onError("Could not verify that the tunnel stopped")
                        }
                    }
                }

                override fun onError(error: String) {
                    // The ADB command failed, so whether cloudflared still runs is unknown.
                    logManager.error(TAG, "Tunnel stop failed: $error")
                    callback.onError("Failed to stop tunnel: $error")
                }
            }
        )
    }
    

    /**
     * Check if tunnel is running.
     */
    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "ps -A | grep cloudflared | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().isNotEmpty())
                }
                
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Get current tunnel URL from log file.
     * Returns null if log doesn't exist (tunnel may need restart).
     * SOTA FIX: Use grep instead of cat to avoid loading entire log into memory.
     */
    fun getTunnelUrl(callback: (String?) -> Unit) {
        // Use grep to find URL directly instead of loading entire log
        // This eliminates large memory allocations from reading log files
        adbShellExecutor.execute(
            command = com.overdrive.app.config.CloudflaredPaidConfig.getGrepUrlCommand(CLOUDFLARED_LOG),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    var url = output.trim()
                    if (url.isNotEmpty()) {
                        // Ensure it has protocol
                        if (!url.startsWith("https://") && !url.startsWith("http://")) {
                            url = "https://$url"
                        }

                        logManager.info(TAG, "Found tunnel URL: $url")
                        callback(url)
                    } else {
                        logManager.debug(TAG, "No tunnel URL found in log")
                        callback(null)
                    }
                }
                
                override fun onError(error: String) {
                    // Log file doesn't exist - tunnel needs restart to get URL
                    logManager.warn(TAG, "Cloudflared log not found - tunnel may need restart")
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Check if log file exists.
     */
    fun hasLogFile(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -f $CLOUDFLARED_LOG && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }
                
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
}
