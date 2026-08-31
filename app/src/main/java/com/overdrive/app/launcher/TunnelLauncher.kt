package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import com.overdrive.app.util.ScratchPaths

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
        private val CLOUDFLARED_TMP_PATH = ScratchPaths.path("cloudflared")
        private val CLOUDFLARED_LOG = ScratchPaths.path("cloudflared.log")
        
        // Process name for identification
        private const val CLOUDFLARED_PROCESS = "cloudflared"

        private val launchLock = Any()
        private var activeLaunch: LaunchFlight? = null
        private val launchGuardScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "cloudflared-launch-guard").apply { isDaemon = true }
            }
    }
    
    interface TunnelCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }

    private class LaunchFlight(callback: TunnelCallback) {
        val callbacks = mutableListOf(callback)
    }

    /**
     * Dedicated scheduler for delayed work (post-kill settles, URL polls).
     * Without this we'd `Thread.sleep` inside ADB-callback handlers, parking
     * the shared single-thread ADB executor for the sleep duration. Same
     * pattern as ZrokLauncher.reconcileScheduler.
     */
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
     * 
     * NOTE: Cloudflared and Zrok are mutually exclusive - this will kill zrok first.
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
                it.onError("Cloudflared launch timed out")
            }
        }, LAUNCH_GUARD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

        val guardedCallback = object : TunnelCallback {
            override fun onLog(message: String) {
                notifyCallbacks(callbacksFor(flight)) { it.onLog(message) }
            }

            override fun onTunnelUrl(url: String) {
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
                it.onError("Cloudflared launch failed: ${e.message}")
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
        
        // Kill zrok first (mutually exclusive)
        killZrokIfRunning {
            // Then check if cloudflared tunnel is already running
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
                                    override fun onError(error: String) { launchCloudflaredInternal(callback) }
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
                    // Not running, check if binary is installed
                    callback.onLog("Setting up cloudflared...")
                    adbShellExecutor.execute(
                        command = "test -x $CLOUDFLARED_TMP_PATH && echo yes || echo no",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(output: String) {
                                if (output.trim() == "yes") {
                                    launchCloudflaredInternal(callback)
                                } else {
                                    installCloudflared(callback)
                                }
                            }
                            
                            override fun onError(error: String) {
                                installCloudflared(callback)
                            }
                        }
                    )
                }
            }
        }
    }
    
    /**
     * Kill zrok if running (mutual exclusion).
     */
    private fun killZrokIfRunning(onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "pgrep -f zrok",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "Killing zrok (mutually exclusive with cloudflared)")
                        // Use the script-via-tmpfile form so toybox `pkill -f
                        // 'zrok'` can't self-match the calling shell's argv.
                        // Order: sentinel + chmod + rm script BEFORE the
                        // pkill cascade, so even if pkill suicides nothing
                        // important is dropped (defense-in-depth even though
                        // the script form already prevents the suicide).
                        val killScript =
                            "[ -f " + ScratchPaths.getDir() + "/zrok.disabled ] || " +
                            "echo \"disabled — cloudflared starting at \$(date)\" > " + ScratchPaths.getDir() + "/zrok.disabled\n" +
                            "chmod 666 " + ScratchPaths.getDir() + "/zrok.disabled 2>/dev/null\n" +
                            "rm -f " + ScratchPaths.getDir() + "/start_zrok.sh 2>/dev/null\n" +
                            com.overdrive.app.launcher.DaemonLauncher.psAwkKillLine("zrok") +
                            "killall -9 zrok 2>/dev/null\n" +
                            "echo done\n"
                        adbShellExecutor.executeScript(
                            scriptBody = killScript,
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) {
                                    // Settle on pollScheduler so the ADB
                                    // executor stays free during the 300ms wait.
                                    pollScheduler.schedule({ onComplete() }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }
                                override fun onError(e: String) { onComplete() }
                            }
                        )
                    } else {
                        onComplete()
                    }
                }
                override fun onError(error: String) { onComplete() }
            }
        )
    }
    
    private fun installCloudflared(callback: TunnelCallback) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libcloudflared.so"
        
        callback.onLog("Installing cloudflared...")
        
        // Check if source exists
        adbShellExecutor.execute(
            command = "test -f $srcPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() != "yes") {
                        logManager.error(TAG, "libcloudflared.so not found")
                        callback.onError("libcloudflared.so not found. Add it to jniLibs/arm64-v8a/")
                        return
                    }
                    
                    // Copy and make executable
                    adbShellExecutor.execute(
                        command = "cp $srcPath $CLOUDFLARED_TMP_PATH && chmod +x $CLOUDFLARED_TMP_PATH",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(copyOutput: String) {
                                callback.onLog("cloudflared installed")
                                launchCloudflaredInternal(callback)
                            }
                            
                            override fun onError(error: String) {
                                logManager.error(TAG, "Failed to install cloudflared: $error")
                                callback.onError("Failed to install cloudflared: $error")
                            }
                        }
                    )
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to check cloudflared source: $error")
                    callback.onError("Failed to check cloudflared source: $error")
                }
            }
        )
    }
    
    private fun launchCloudflaredInternal(callback: TunnelCallback) {
        callback.onLog("Starting cloudflared tunnel...")
        
        // Check if sing-box proxy is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    // Sing-box is running, use proxy
                    val useProxy = output.trim().isNotEmpty()
                    launchCloudflaredWithConfig(callback, useProxy)
                }
                
                override fun onError(error: String) {
                    // Sing-box not running, launch without proxy
                    logManager.info(TAG, "Sing-box not running, launching cloudflared without proxy")
                    launchCloudflaredWithConfig(callback, false)
                }
            }
        )
    }
    
    private fun launchCloudflaredWithConfig(callback: TunnelCallback, useProxy: Boolean) {
        val cmd = buildString {
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

// FIX: Removed invalid flags. Added 'retries' and 'grace-period'.
// --grace-period 45s: Waits 45s before panicking (Covers the 24s blackout)
// --retries 20: Keeps trying to reconnect for a long time
            append("$CLOUDFLARED_TMP_PATH ${com.overdrive.app.config.CloudflaredPaidConfig.getArgs()}")
            append("' > $CLOUDFLARED_LOG 2>&1 &")
        }
        
        // Redact the cloudflared paid token before logging — the command can
        // contain `--token <secret>`, and DEBUG logs are written to logcat and
        // (in LOG_CAPTURE builds) uploaded.
        logManager.debug(TAG, "Executing: ${cmd.replace(Regex("--token\\s+\\S+"), "--token ***")}")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Cloudflared launch command sent")
                    callback.onLog("Waiting for tunnel URL...")
                    waitForTunnelUrl(callback, 1)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch cloudflared: $error")
                    callback.onError("Launch failed: $error")
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
                        callback.onError("Failed to get URL. Log tail:\n${output.takeLast(500)}")
                    }

                    override fun onError(error: String) {
                        callback.onError("Timed out waiting for tunnel URL")
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
                        callback.onError("Proxy Error: Is sing-box running on port 8119?\n${logContent.takeLast(200)}")
                        return
                    }
                    
                    // Check for connection errors
                    if (logContent.contains("connection refused") || logContent.contains("dial tcp")) {
                        logManager.error(TAG, "Connection error: $logContent")
                        callback.onError("Cloudflared connection error: ${logContent.takeLast(300)}")
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
        
        // Simple approach - just kill cloudflared and consider it done
        adbShellExecutor.execute(
            command = "killall -9 cloudflared 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tunnel stopped")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }
                
                override fun onError(error: String) {
                    // killall returns error if no process - that's fine
                    logManager.info(TAG, "Tunnel stopped")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
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
