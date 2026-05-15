package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.util.PreferencesManager


/**
 * Launches tunnel processes via ADB shell for remote access.
 * 
 * Currently supports Cloudflared tunnel only.
 * Uses AdbShellExecutor for shell operations.
 */

@JvmField
var CLOUDFLARED_TUNNEL_URL="";



class TunnelLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "TunnelLauncher"
        
        // Cloudflared paths
        private const val CLOUDFLARED_TMP_PATH = "/data/local/tmp/cloudflared"
        private const val CLOUDFLARED_LOG = "/data/local/tmp/cloudflared.log"
        
        // Process name for identification
        private const val CLOUDFLARED_PROCESS = "cloudflared"
    }
    
    interface TunnelCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }
    
    /**
     * Launch Cloudflared tunnel via ADB shell.
     * Creates a public URL that forwards to local port 8080.
     * Reuses existing tunnel if already running.
     * 
     * NOTE: Cloudflared and Zrok are mutually exclusive - this will kill zrok first.
     */
    fun launchCloudflared(callback: TunnelCallback) {
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
                        adbShellExecutor.execute(
                            command = "killall -9 zrok 2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) {
                                    Thread.sleep(300)
                                    onComplete()
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
        val isPaid = PreferencesManager.isCloudflarePaid()
        val token = PreferencesManager.getCloudflareToken()

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
            append("$CLOUDFLARED_TMP_PATH tunnel ")
            if (isPaid && token.isNotEmpty()) {
                callback.onLog("Launching paid tunnel with token...")
                append("run --token $token --url http://127.0.0.1:8080")
            } else {
                callback.onLog("Launching free quick tunnel (trycloudflare)...")
                append("--url http://127.0.0.1:8080 ")
                //append("--edge-ip-version 4 --protocol http2 --no-autoupdate ")
                //append("--retries 20 --grace-period 45s")
            }
            append("' > $CLOUDFLARED_LOG 2>&1 &")

        }
        
        logManager.debug(TAG, "Executing: $cmd")
        
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
        
        Thread.sleep(5000)
        val isPaid = PreferencesManager.isCloudflarePaid()
        val token = PreferencesManager.getCloudflareToken()

        if (isPaid && token.isNotEmpty()) {
            adbShellExecutor.execute(
                command = "cat $CLOUDFLARED_LOG | grep --line-buffered -iE 'ingress|hostname'",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        val cfUrlPattern = Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
                        val match = cfUrlPattern.find(output)
                        //logManager.info(TAG, output)


                        if (match != null) {
                            val tunnelUrl = "https://"+match.value
                            logManager.info(com.overdrive.app.launcher.TunnelLauncher.Companion.TAG, "Tunnel established: $tunnelUrl")
                            callback.onLog("Tunnel established: $tunnelUrl")
                            callback.onTunnelUrl(tunnelUrl)
                            return
                        }

                        // Check for proxy errors
                        if (output.contains("proxyconnect") ||
                            (output.contains("proxy") && output.contains("refused"))) {
                            logManager.error(com.overdrive.app.launcher.TunnelLauncher.Companion.TAG, "Proxy error - is sing-box running?")
                            callback.onError("Proxy Error: Is sing-box running on port 8119?\n${output.takeLast(200)}")
                            return
                        }

                        // Check for connection errors
                        if (output.contains("connection refused") || output.contains("dial tcp")) {
                            logManager.error(com.overdrive.app.launcher.TunnelLauncher.Companion.TAG, "Connection error: $output")
                            callback.onError("Cloudflared connection error: ${output.takeLast(300)}")
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
            return
        }
        else{
            adbShellExecutor.execute(
                command = "cat $CLOUDFLARED_LOG 2>/dev/null",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(logContent: String) {
                        // URL pattern: https://xxx-xxx.trycloudflare.com
                        val cfUrlPattern = Regex("https://([a-z0-9]+-[a-z0-9-]+)\\.trycloudflare\\.com")
                        val match = cfUrlPattern.find(logContent)

                        if (match != null) {
                            val tunnelUrl = match.value
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

        val isPaid = PreferencesManager.isCloudflarePaid()
        val token = PreferencesManager.getCloudflareToken()

        if (isPaid && token.isNotEmpty()) {

            val pattern = "[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
            val keywords = "ingress|hostname"
            val filterOut = "127.0.0.1"

            val extractCmd = "grep --line-buffered -iE '$keywords' $CLOUDFLARED_LOG 2>/dev/null | grep --line-buffered -oE '$pattern' | grep -vE '$filterOut' | tail -1"
            //command = "grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' $CLOUDFLARED_LOG 2>/dev/null | grep -v 'api\\.' | head -1",
            adbShellExecutor.execute(
                command = extractCmd,
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        val url = "https://"+output.trim()
                        if (url.isNotEmpty()) {
                            logManager.info(com.overdrive.app.launcher.TunnelLauncher.Companion.TAG, "Found tunnel URL: $url")
                            com.overdrive.app.launcher.CLOUDFLARED_TUNNEL_URL =url
                            callback(url)
                        } else {
                            logManager.debug(com.overdrive.app.launcher.TunnelLauncher.Companion.TAG, "No tunnel URL found in log")
                            com.overdrive.app.launcher.CLOUDFLARED_TUNNEL_URL =""
                            callback(null)
                        }
                    }

                    override fun onError(error: String) {
                        // Log file doesn't exist - tunnel needs restart to get URL
                        logManager.warn(com.overdrive.app.launcher.TunnelLauncher.Companion.TAG, "Cloudflared log not found - tunnel may need restart")
                        callback(null)
                    }
                }
            )
        }
        else {
            // Use grep to find URL directly instead of loading entire log
            // This eliminates large memory allocations from reading log files
            adbShellExecutor.execute(
                command = "grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' $CLOUDFLARED_LOG 2>/dev/null | grep -v 'api\\.' | head -1",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        val url = output.trim()
                        if (url.isNotEmpty() && url.startsWith("https://") && url.contains("-")) {
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
