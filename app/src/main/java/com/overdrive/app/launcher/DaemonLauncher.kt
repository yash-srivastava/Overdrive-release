package com.overdrive.app.launcher

import android.content.Context
import android.provider.Settings
import com.overdrive.app.logging.LogManager

/**
 * Launches daemon processes via ADB shell using app_process.
 * 
 * This class handles launching various daemon processes that run independently
 * of the app's lifecycle as shell user (UID 2000).
 * 
 * Note: This uses ADB shell (via AdbShellExecutor/Dadb) for launching daemons.
 * For system shell operations, see the shell/ package (PrivilegedShellSetup, etc.)
 * 
 * ProxyDaemon is launched via privileged shell (UID 1000) for elevated privileges.
 */
class DaemonLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "DaemonLauncher"
        
        // Log file paths for daemons
        private const val CAMERA_DAEMON_LOG = "/data/local/tmp/cam_daemon.log"
        private const val SENTRY_DAEMON_LOG = "/data/local/tmp/sentry_daemon.log"
        private const val SENTRY_DAEMON_LOG_SYSTEM = "/data/data/com.android.providers.settings/sentry_daemon.log"
        private const val ACC_SENTRY_DAEMON_LOG = "/data/local/tmp/acc_sentry_daemon.log"
        private const val PROXY_DAEMON_LOG = "/data/local/tmp/proxy_daemon.log"
        private const val TELEGRAM_DAEMON_LOG = "/data/local/tmp/telegrambotdaemon.log"
        
        // Process names for daemon identification
        private const val CAMERA_DAEMON_PROCESS = "byd_cam_daemon"
        private const val SENTRY_DAEMON_PROCESS = "sentry_daemon"
        private const val ACC_SENTRY_DAEMON_PROCESS = "acc_sentry_daemon"
        private const val PROXY_DAEMON_PROCESS = "sentry_proxy"
        private const val TELEGRAM_DAEMON_PROCESS = "telegram_bot_daemon"
        
        // Use privileged shell for proxy daemon
        private const val USE_PRIVILEGED_SHELL_FOR_PROXY = true
        
        // DISABLED: Privileged shell for sentry daemon causes BYD default dashcam
        // to show "no signal". Running as UID 1000 elevates camera priority via
        // setPkg2AccWhiteList, stealing AVMCamera feed from the dashcam app.
        private const val USE_PRIVILEGED_SHELL_FOR_SENTRY = false
        
        // ACC Sentry daemon MUST run via ADB shell (UID 2000) for screen control
        private const val USE_ADB_SHELL_FOR_ACC_SENTRY = true
        
        // Flag to prevent concurrent launch attempts (shared across all instances)
        @Volatile
        private var accSentryLaunchInProgress = false
        @Volatile
        private var cameraLaunchInProgress = false
    }
    
    interface LaunchCallback {
        fun onLog(message: String)
        fun onLaunched()
        fun onError(error: String)
    }
    
    /**
     * Get JVM proxy arguments from system settings.
     * This allows app_process daemons to honor the Android system's Global Proxy settings.
     */
    private fun getProxyArgs(): String {
        val sb = StringBuilder()
        try {
            // Read Global HTTP Proxy (standard for WiFi/Ethernet)
            val globalProxy = Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY)
            if (!globalProxy.isNullOrEmpty()) {
                val parts = globalProxy.split(":")
                if (parts.isNotEmpty()) {
                    val host = parts[0]
                    val port = if (parts.size > 1) parts[1] else "8080"
                    
                    // Add HTTP and HTTPS proxy flags
                    sb.append("-Dhttp.proxyHost=$host ")
                    sb.append("-Dhttp.proxyPort=$port ")
                    sb.append("-Dhttps.proxyHost=$host ")
                    sb.append("-Dhttps.proxyPort=$port ")
                    // Essential: Bypass proxy for localhost to avoid loopbacks
                    sb.append("-Dhttp.nonProxyHosts=\"localhost|127.*|[::1]\" ")
                    
                    logManager.debug(TAG, "Proxy args: host=$host, port=$port")
                }
            }
        } catch (e: Exception) {
            logManager.warn(TAG, "Failed to read proxy settings: ${e.message}")
        }
        return sb.toString()
    }
    
    /**
     * Launch the CameraDaemon via ADB shell.
     * The daemon will run independently of this app as shell user (UID 2000).
    */
    fun launchCameraDaemon(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        // Prevent concurrent launch attempts
        if (cameraLaunchInProgress) {
            logManager.info(TAG, "CameraDaemon launch already in progress, skipping")
            callback.onLog("Launch already in progress")
            callback.onLaunched()
            return
        }
        cameraLaunchInProgress = true
        
        logManager.info(TAG, "Launching CameraDaemon...")
        callback.onLog("Launching CameraDaemon...")
        
        // Check if already running using isDaemonRunning (handles zombies properly)
        isDaemonRunning(CAMERA_DAEMON_PROCESS) { isRunning ->
            if (isRunning) {
                logManager.info(TAG, "CameraDaemon already running")
                callback.onLog("CameraDaemon already running")
                callback.onLaunched()
                cameraLaunchInProgress = false
            } else {
                launchCameraDaemonInternal(outputDir, nativeLibDir, object : LaunchCallback {
                    override fun onLog(message: String) = callback.onLog(message)
                    override fun onLaunched() {
                        cameraLaunchInProgress = false
                        callback.onLaunched()
                    }
                    override fun onError(error: String) {
                        cameraLaunchInProgress = false
                        callback.onError(error)
                    }
                })
            }
        }
    }
    
    private fun launchCameraDaemonInternal(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val scriptPath = "/data/local/tmp/start_cam_daemon.sh"
        
        logManager.debug(TAG, "Deploying CameraDaemon watchdog script...")
        callback.onLog("Deploying watchdog script...")
        
        // Step 1: Kill old processes and clean up.
        // CRITICAL: Kill the watchdog script FIRST so it can't respawn the daemon
        // between the two pkill calls. Reversing the order here causes the old
        // watchdog to relaunch the daemon, and the fresh watchdog we're about
        // to start loses the singleton lock race ("Another CameraDaemon instance
        // is already running. Exiting.").
        val cleanupCmd = buildString {
            append("pkill -9 -f 'start_cam_daemon' 2>/dev/null; ")
            append("rm -f $scriptPath 2>/dev/null; ")
            append("sleep 1; ")
            append("pkill -9 -f '$CAMERA_DAEMON_PROCESS' 2>/dev/null; ")
            append("killall -9 $CAMERA_DAEMON_PROCESS 2>/dev/null; ")
            append("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null; ")
            append("sleep 1; echo done")
        }
        
        adbShellExecutor.execute(
            command = cleanupCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Old processes cleaned up, writing script...")
                    writeCamDaemonScript(apkPath, proxyArgs, outputDir, nativeLibDir, scriptPath, callback)
                }
                
                override fun onError(error: String) {
                    callback.onLog("Writing script...")
                    writeCamDaemonScript(apkPath, proxyArgs, outputDir, nativeLibDir, scriptPath, callback)
                }
            }
        )
    }
    
    private fun writeCamDaemonScript(
        apkPath: String, proxyArgs: String, outputDir: String, nativeLibDir: String,
        scriptPath: String, callback: LaunchCallback
    ) {
        val scriptLines = listOf(
            "#!/system/bin/sh",
            "# CameraDaemon Watchdog Script",
            "LOG_FILE=\"$CAMERA_DAEMON_LOG\"",
            "LOCK_FILE=\"/data/local/tmp/camera_daemon.lock\"",
            "MAX_RETRIES=5",
            "RETRY_COUNT=0",
            "",
            "while true; do",
            "  echo \"[\$(date)] Starting CameraDaemon...\" >> \"\$LOG_FILE\"",
            "",
            "  CLASSPATH=/system/framework/bmmcamera.jar:$apkPath app_process " +
                "-Djava.library.path=$nativeLibDir:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 " +
                "${proxyArgs}/system/bin " +
                "--nice-name=$CAMERA_DAEMON_PROCESS " +
                "com.overdrive.app.daemon.CameraDaemon " +
                "$outputDir $nativeLibDir >> \"\$LOG_FILE\" 2>&1",
            "",
            "  EXIT_CODE=\$?",
            "  if [ \$EXIT_CODE -eq 0 ]; then",
            "    echo \"[\$(date)] Daemon exited cleanly (code 0), restarting in 10s...\" >> \"\$LOG_FILE\"",
            "    RETRY_COUNT=0",
            "    sleep 10",
            "  elif [ \$EXIT_CODE -eq 1 ] || [ \$EXIT_CODE -eq 137 ] || [ \$EXIT_CODE -eq 134 ]; then",
            "    # Exit 1 = singleton lock conflict, 137 = SIGKILL (OOM), 134 = SIGABRT (native crash/font init)",
            "    # All are transient — retry with backoff",
            "    RETRY_COUNT=\$((RETRY_COUNT + 1))",
            "    if [ \$RETRY_COUNT -ge \$MAX_RETRIES ]; then",
            "      echo \"[\$(date)] Daemon exited with code \$EXIT_CODE, max retries (\$MAX_RETRIES) reached. Giving up.\" >> \"\$LOG_FILE\"",
            "      break",
            "    fi",
            "    DELAY=\$((RETRY_COUNT * 3))",
            "    echo \"[\$(date)] Daemon exited with code \$EXIT_CODE (attempt \$RETRY_COUNT/\$MAX_RETRIES), retrying in \${DELAY}s...\" >> \"\$LOG_FILE\"",
            "    # Clean stale lock file if process was killed or aborted",
            "    if [ \$EXIT_CODE -eq 137 ] || [ \$EXIT_CODE -eq 134 ]; then",
            "      rm -f \"\$LOCK_FILE\" 2>/dev/null",
            "    fi",
            "    sleep \$DELAY",
            "  else",
            "    echo \"[\$(date)] Daemon exited with code \$EXIT_CODE, NOT restarting.\" >> \"\$LOG_FILE\"",
            "    break",
            "  fi",
            "done"
        )
        
        // Write script using multiple echo commands (same proven approach as AccSentryDaemon)
        val writeCmd = buildString {
            append("rm -f $scriptPath 2>/dev/null; ")
            scriptLines.forEachIndexed { index, line ->
                val escapedLine = line
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\$", "\\$")
                    .replace("`", "\\`")
                if (index == 0) {
                    append("echo \"$escapedLine\" > $scriptPath; ")
                } else {
                    append("echo \"$escapedLine\" >> $scriptPath; ")
                }
            }
            append("chmod 755 $scriptPath")
        }
        
        adbShellExecutor.execute(
            command = writeCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "CameraDaemon script written successfully")
                    callback.onLog("Script ready, launching...")
                    launchCamDaemonScript(scriptPath, callback)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to write daemon script: $error")
                    callback.onLog("Script write failed, using fallback...")
                    launchCamDaemonFallback(callback)
                }
            }
        )
    }
    
    private fun launchCamDaemonScript(scriptPath: String, callback: LaunchCallback) {
        val launchCmd = "nohup sh $scriptPath > /dev/null 2>&1 &"
        
        adbShellExecutor.execute(
            command = launchCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "CameraDaemon watchdog launched")
                    callback.onLog("Watchdog active. Verifying daemon...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyDaemonRunning(CAMERA_DAEMON_PROCESS, "CameraDaemon", CAMERA_DAEMON_LOG, callback)
                    }, 1500)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch watchdog: $error")
                    callback.onLog("Watchdog launch failed, using fallback...")
                    launchCamDaemonFallback(callback)
                }
            }
        )
    }
    
    /**
     * Fallback: Launch CameraDaemon directly without watchdog (original simple method).
     */
    private fun launchCamDaemonFallback(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        
        val innerCmd = buildString {
            append("CLASSPATH=/system/framework/bmmcamera.jar:$apkPath ")
            append("app_process ")
            append("-Djava.library.path=$nativeLibDir:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 ")
            append(proxyArgs)
            append("/system/bin ")
            append("--nice-name=$CAMERA_DAEMON_PROCESS ")
            append("com.overdrive.app.daemon.CameraDaemon ")
            append("$outputDir ")
            append("$nativeLibDir")
        }
        
        val cmd = "nohup sh -c '$innerCmd' > $CAMERA_DAEMON_LOG 2>&1 &"
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "CameraDaemon launched (fallback, no watchdog)")
                    callback.onLog("Launch command sent, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyDaemonRunning(CAMERA_DAEMON_PROCESS, "CameraDaemon", CAMERA_DAEMON_LOG, callback)
                    }, 1500)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch CameraDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Launch the SentryDaemon via ADB shell.
     * Monitors ACC state and manages recording/location services.
     */
    fun launchSentryDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Launching SentryDaemon...")
        callback.onLog("Launching SentryDaemon...")
        
        // Use word boundary to avoid matching acc_sentry_daemon
        adbShellExecutor.execute(
            command = "ps -A | grep -w $SENTRY_DAEMON_PROCESS | grep -v grep | grep -v acc_",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "SentryDaemon already running: ${output.trim()}")
                        callback.onLog("SentryDaemon already running")
                        callback.onLaunched()
                        return
                    }
                    launchSentryDaemonInternal(callback)
                }
                
                override fun onError(error: String) {
                    launchSentryDaemonInternal(callback)
                }
            }
        )
    }
    
    private fun launchSentryDaemonInternal(callback: LaunchCallback) {
        callback.onLog("Granting bodywork permissions...")
        grantBodyworkPermissions(callback) {
            val apkPath = context.applicationInfo.sourceDir
            val proxyArgs = getProxyArgs()
            
            val innerCmd = buildString {
                append("CLASSPATH=$apkPath ")
                append("app_process ")
                append(proxyArgs)
                append("/system/bin ")
                append("--nice-name=$SENTRY_DAEMON_PROCESS ")
                append("com.overdrive.app.daemon.SentryDaemon")
            }
            
            logManager.debug(TAG, "SentryDaemon command: $innerCmd")
            callback.onLog("Executing daemon launch command...")
            
            // Try privileged shell first (UID 1000) for better permissions
            if (USE_PRIVILEGED_SHELL_FOR_SENTRY) {
                callback.onLog("Checking privileged shell availability...")
                
                // Use ADB to check if port 1234 is open and running as UID 1000
                adbShellExecutor.execute(
                    command = "echo 'id' | nc localhost 1234 2>/dev/null | head -1",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            if (output.contains("uid=1000")) {
                                logManager.info(TAG, "Privileged shell available (UID 1000 confirmed)")
                                callback.onLog("Using privileged shell (UID 1000)...")
                                // UID 1000 can write to /data/system, redirect logs there
                                val privCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG_SYSTEM 2>&1 &"
                                launchSentryDaemonViaPrivilegedShell(privCmd, innerCmd, callback)
                            } else if (output.contains("uid=")) {
                                logManager.warn(TAG, "Shell available but not UID 1000: $output")
                                callback.onLog("Shell not UID 1000, using ADB shell...")
                                val adbCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG 2>&1 &"
                                launchSentryDaemonViaAdb(adbCmd, callback)
                            } else {
                                logManager.info(TAG, "Privileged shell not available, using ADB shell")
                                callback.onLog("Privileged shell not available, using ADB shell...")
                                val adbCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG 2>&1 &"
                                launchSentryDaemonViaAdb(adbCmd, callback)
                            }
                        }
                        
                        override fun onError(error: String) {
                            logManager.info(TAG, "Privileged shell check failed: $error, using ADB shell")
                            callback.onLog("Using ADB shell...")
                            val adbCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG 2>&1 &"
                            launchSentryDaemonViaAdb(adbCmd, callback)
                        }
                    }
                )
            } else {
                val adbCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG 2>&1 &"
                launchSentryDaemonViaAdb(adbCmd, callback)
            }
        }
    }
    
    /**
     * Launch SentryDaemon via privileged shell (UID 1000).
     * This gives the daemon system-level privileges for better access to BYD services.
     */
    private fun launchSentryDaemonViaPrivilegedShell(cmd: String, innerCmd: String, callback: LaunchCallback) {
        // Escape single quotes for piping through nc
        // Replace ' with '\'' (end quote, escaped quote, start quote)
        val escapedCmd = cmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234"
        
        logManager.debug(TAG, "Executing SentryDaemon via privileged shell: $ncCmd")
        
        adbShellExecutor.execute(
            command = ncCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "SentryDaemon launch via privileged shell: $output")
                    callback.onLog("Launch command sent via privileged shell (UID 1000), verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Check both possible log locations
                        verifySentryDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Privileged shell launch failed: $error, falling back to ADB")
                    callback.onLog("Privileged shell failed, using ADB shell...")
                    val adbCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG 2>&1 &"
                    launchSentryDaemonViaAdb(adbCmd, callback)
                }
            }
        )
    }
    
    /**
     * Launch SentryDaemon via ADB shell (UID 2000).
     * Fallback method when privileged shell is not available.
     */
    private fun launchSentryDaemonViaAdb(cmd: String, callback: LaunchCallback) {
        logManager.debug(TAG, "Executing SentryDaemon via ADB: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "SentryDaemon launch command sent via ADB")
                    callback.onLog("Launch command sent via ADB shell (UID 2000), verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifySentryDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch SentryDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Verify SentryDaemon is running and report its UID.
     */
    private fun verifySentryDaemonRunning(callback: LaunchCallback) {
        // First check if process is running and get its UID
        // Use grep -v acc_ to exclude acc_sentry_daemon
        adbShellExecutor.execute(
            command = "ps -A -o PID,UID,ARGS | grep -w $SENTRY_DAEMON_PROCESS | grep -v grep | grep -v acc_ | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        // Parse PID and UID from output
                        val parts = output.trim().split(Regex("\\s+"))
                        val pid = parts.getOrNull(0) ?: "?"
                        val uid = parts.getOrNull(1) ?: "?"
                        
                        val uidName = when (uid) {
                            "1000" -> "system"
                            "2000" -> "shell"
                            "0" -> "root"
                            else -> "uid=$uid"
                        }
                        
                        logManager.info(TAG, "SentryDaemon running with PID: $pid, UID: $uid ($uidName)")
                        callback.onLog("SentryDaemon running with PID: $pid as $uidName")
                        callback.onLaunched()
                    } else {
                        // Process not found, check logs
                        checkSentryDaemonLogs(callback)
                    }
                }
                
                override fun onError(error: String) {
                    checkSentryDaemonLogs(callback)
                }
            }
        )
    }
    
    /**
     * Check SentryDaemon logs from both possible locations.
     */
    private fun checkSentryDaemonLogs(callback: LaunchCallback) {
        // Check both log locations:
        // - /data/data/com.android.providers.settings/sentry_daemon.log (UID 1000)
        // - /data/local/tmp/sentry_daemon.log (UID 2000)
        adbShellExecutor.execute(
            command = "cat $SENTRY_DAEMON_LOG_SYSTEM 2>/dev/null | tail -30; cat $SENTRY_DAEMON_LOG 2>/dev/null | tail -30",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    if (logContent.trim().isNotEmpty()) {
                        logManager.error(TAG, "SentryDaemon failed to start. Log: $logContent")
                        callback.onError("SentryDaemon failed to start. Log:\n$logContent")
                    } else {
                        logManager.error(TAG, "SentryDaemon process not found and no log output")
                        callback.onError("SentryDaemon process not found and no log output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "SentryDaemon process not found and couldn't read log: $error")
                    callback.onError("SentryDaemon process not found and no log output")
                }
            }
        )
    }
    
    /**
     * Launch the AccSentryDaemon via ADB shell (UID 2000).
     * 
     * This daemon handles:
     * - ACC state monitoring
     * - Screen control (input keyevent) - MUST run as UID 2000
     * - Surveillance enable/disable
     * 
     * System whitelisting is handled by SentryDaemon (UID 1000) separately.
     */
    fun launchAccSentryDaemon(callback: LaunchCallback) {
        // Prevent concurrent launch attempts
        if (accSentryLaunchInProgress) {
            logManager.info(TAG, "AccSentryDaemon launch already in progress, skipping")
            callback.onLog("Launch already in progress")
            callback.onLaunched()
            return
        }
        accSentryLaunchInProgress = true
        
        logManager.info(TAG, "Launching AccSentryDaemon (UID 2000)...")
        callback.onLog("Launching AccSentryDaemon (UID 2000 for screen control)...")
        
        // Check if daemon or watchdog process is running
        adbShellExecutor.execute(
            command = "ps -A | grep -E '($ACC_SENTRY_DAEMON_PROCESS|start_acc_sentry)' | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val hasDaemon = output.contains(ACC_SENTRY_DAEMON_PROCESS)
                    val hasWatchdog = output.contains("start_acc_sentry")
                    
                    if (hasDaemon) {
                        logManager.info(TAG, "AccSentryDaemon already running")
                        callback.onLog("AccSentryDaemon already running")
                        callback.onLaunched()
                        accSentryLaunchInProgress = false
                        return
                    }
                    
                    if (hasWatchdog) {
                        logManager.info(TAG, "Watchdog process running - daemon will spawn")
                        callback.onLog("Watchdog active, daemon will respawn")
                        callback.onLaunched()
                        accSentryLaunchInProgress = false
                        return
                    }
                    
                    launchAccSentryDaemonInternal(callback)
                }
                
                override fun onError(error: String) {
                    launchAccSentryDaemonInternal(callback)
                }
            }
        )
    }
    
    private fun launchAccSentryDaemonInternal(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val watchdogScriptPath = "/data/local/tmp/start_acc_sentry.sh"
        val lockFilePath = "/data/local/tmp/acc_sentry_daemon.lock"
        
        logManager.debug(TAG, "Deploying Immortal Watchdog Script for AccSentryDaemon...")
        callback.onLog("Deploying watchdog script via ADB (UID 2000)...")
        
        // Step 1: Kill EVERYTHING - daemon process, watchdog script, and any shell running the script
        // Use multiple kill strategies to ensure complete cleanup:
        // 1. pkill -f 'acc_sentry' - kills daemon by nice-name
        // 2. pkill -f 'start_acc_sentry.sh' - kills watchdog script by script name
        // 3. Kill any 'sh' process with the script in cmdline
        val cleanupCmd = buildString {
            append("pkill -9 -f 'acc_sentry_daemon' 2>/dev/null; ")
            append("pkill -9 -f 'start_acc_sentry.sh' 2>/dev/null; ")
            append("pkill -9 -f 'AccSentryDaemon' 2>/dev/null; ")
            append("rm -f $lockFilePath 2>/dev/null; ")
            append("rm -f $watchdogScriptPath 2>/dev/null; ")
            append("sleep 1; echo done")
        }
        
        adbShellExecutor.execute(
            command = cleanupCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Old processes cleaned up, writing watchdog script...")
                    writeWatchdogScript(apkPath, proxyArgs, watchdogScriptPath, callback)
                }
                
                override fun onError(error: String) {
                    // pkill returns error if no process found - that's OK
                    callback.onLog("Writing watchdog script...")
                    writeWatchdogScript(apkPath, proxyArgs, watchdogScriptPath, callback)
                }
            }
        )
    }
    
    /**
     * Write the watchdog script to /data/local/tmp/ using printf (more reliable than heredoc).
     */
    private fun writeWatchdogScript(apkPath: String, proxyArgs: String, scriptPath: String, callback: LaunchCallback) {
        // Build the script content line by line using printf
        // This avoids heredoc issues with ADB shell
        val lockFile = "/data/local/tmp/acc_sentry_daemon.lock"
        val scriptLines = listOf(
            "#!/system/bin/sh",
            "# AccSentryDaemon Watchdog Script",
            "APK_PATH=\"$apkPath\"",
            "CLS=\"com.overdrive.app.daemon.AccSentryDaemon\"",
            "PROCESS_NAME=\"$ACC_SENTRY_DAEMON_PROCESS\"",
            "LOG_FILE=\"$ACC_SENTRY_DAEMON_LOG\"",
            "LOCK_FILE=\"$lockFile\"",
            "PROXY_ARGS=\"$proxyArgs\"",
            "",
            "# Disable Phantom Process Killer (best effort)",
            "/system/bin/device_config put activity_manager max_phantom_processes 2147483647 > /dev/null 2>&1",
            "",
            "echo \"=== WATCHDOG STARTED ===\" > \$LOG_FILE",
            "",
            "# CRITICAL: Wait for system boot to complete before starting daemon",
            "# This prevents 'No service published for: power' errors that crash SystemUI",
            "echo \"[\$(date)] Waiting for system boot to complete...\" >> \$LOG_FILE",
            "BOOT_WAIT=0",
            "while [ \"\$(getprop sys.boot_completed)\" != \"1\" ] && [ \$BOOT_WAIT -lt 120 ]; do",
            "  sleep 2",
            "  BOOT_WAIT=\$((BOOT_WAIT + 2))",
            "done",
            "echo \"[\$(date)] Boot completed (waited \${BOOT_WAIT}s)\" >> \$LOG_FILE",
            "",
            "# Extra delay to ensure all system services are fully initialized",
            "sleep 5",
            "",
            "# Infinite respawn loop",
            "while true; do",
            "  # Log rotation: truncate if > 2MB",
            "  if [ -f \"\$LOG_FILE\" ]; then",
            "    SIZE=\$(stat -c%s \"\$LOG_FILE\" 2>/dev/null || echo 0)",
            "    if [ \"\$SIZE\" -gt 2097152 ]; then",
            "      echo \"[\$(date)] Log rotated...\" > \"\$LOG_FILE\"",
            "    fi",
            "  fi",
            "",
            "  # NOTE: Do NOT delete lock file here - Java FileLock handles stale locks automatically",
            "  # When a process dies, the OS releases the lock. Deleting the file causes race conditions.",
            "",
            "  echo \"[\$(date)] Starting Daemon...\" >> \"\$LOG_FILE\"",
            "",
            "  # Launch daemon (blocking call)",
            "  CLASSPATH=\"\$APK_PATH\" app_process \$PROXY_ARGS /system/bin --nice-name=\"\$PROCESS_NAME\" \"\$CLS\" >> \"\$LOG_FILE\" 2>&1",
            "",
            "  # Daemon exited - log and respawn",
            "  EXIT_CODE=\$?",
            "  echo \"[\$(date)] Daemon DIED (Code: \$EXIT_CODE). Respawning in 2s...\" >> \"\$LOG_FILE\"",
            "  sleep 2",
            "done"
        )
        
        // Write script using multiple echo commands (most reliable across Android shells)
        val writeCmd = buildString {
            append("rm -f $scriptPath 2>/dev/null; ")
            scriptLines.forEachIndexed { index, line ->
                val escapedLine = line
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\$", "\\$")
                    .replace("`", "\\`")
                if (index == 0) {
                    append("echo \"$escapedLine\" > $scriptPath; ")
                } else {
                    append("echo \"$escapedLine\" >> $scriptPath; ")
                }
            }
            append("chmod 755 $scriptPath")
        }
        
        adbShellExecutor.execute(
            command = writeCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Watchdog script written successfully")
                    callback.onLog("Watchdog script ready, launching...")
                    launchWatchdogScript(scriptPath, callback)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to write watchdog script: $error")
                    callback.onLog("Watchdog script failed, using fallback...")
                    launchAccSentryDaemonFallback(callback)
                }
            }
        )
    }
    
    /**
     * Launch the watchdog script in background using nohup.
     */
    private fun launchWatchdogScript(scriptPath: String, callback: LaunchCallback) {
        val launchCmd = "nohup sh $scriptPath > /dev/null 2>&1 &"
        
        adbShellExecutor.execute(
            command = launchCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Watchdog script launched successfully")
                    callback.onLog("Watchdog active. Verifying daemon...")
                    
                    // Watchdog has 5-second delay after boot wait before starting daemon
                    // Wait 8 seconds to ensure daemon has time to start
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyAccSentryDaemonRunning(callback)
                    }, 8000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch watchdog: $error")
                    callback.onLog("Watchdog launch failed, using fallback...")
                    launchAccSentryDaemonFallback(callback)
                }
            }
        )
    }
    
    /**
     * Fallback: Launch AccSentryDaemon directly without watchdog (original simple method).
     */
    private fun launchAccSentryDaemonFallback(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        
        val innerCmd = buildString {
            append("CLASSPATH=$apkPath ")
            append("app_process ")
            append(proxyArgs)
            append("/system/bin ")
            append("--nice-name=$ACC_SENTRY_DAEMON_PROCESS ")
            append("com.overdrive.app.daemon.AccSentryDaemon")
        }
        
        val cmd = "nohup sh -c '$innerCmd' > $ACC_SENTRY_DAEMON_LOG 2>&1 &"
        
        logManager.debug(TAG, "AccSentryDaemon fallback command: $cmd")
        callback.onLog("Launching via simple nohup (fallback)...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "AccSentryDaemon fallback launch sent")
                    callback.onLog("Launch command sent, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyAccSentryDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    accSentryLaunchInProgress = false  // Reset flag
                    logManager.error(TAG, "Failed to launch AccSentryDaemon (fallback): $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    private fun verifyAccSentryDaemonRunning(callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "ps -A -o PID,UID,ARGS | grep $ACC_SENTRY_DAEMON_PROCESS | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    accSentryLaunchInProgress = false  // Reset flag
                    if (output.trim().isNotEmpty()) {
                        val parts = output.trim().split(Regex("\\s+"))
                        val pid = parts.getOrNull(0) ?: "?"
                        val uid = parts.getOrNull(1) ?: "?"
                        
                        val uidName = when (uid) {
                            "2000" -> "shell (correct!)"
                            "1000" -> "system (WRONG - screen control won't work!)"
                            else -> "uid=$uid"
                        }
                        
                        logManager.info(TAG, "AccSentryDaemon running with PID: $pid, UID: $uid ($uidName)")
                        callback.onLog("AccSentryDaemon running with PID: $pid as $uidName")
                        callback.onLaunched()
                    } else {
                        // Check logs
                        adbShellExecutor.execute(
                            command = "cat $ACC_SENTRY_DAEMON_LOG 2>/dev/null | tail -30",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(logContent: String) {
                                    if (logContent.trim().isNotEmpty()) {
                                        logManager.error(TAG, "AccSentryDaemon failed. Log: $logContent")
                                        callback.onError("AccSentryDaemon failed:\n$logContent")
                                    } else {
                                        callback.onError("AccSentryDaemon not found and no log")
                                    }
                                }
                                
                                override fun onError(error: String) {
                                    callback.onError("AccSentryDaemon not found")
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    accSentryLaunchInProgress = false  // Reset flag
                    callback.onError("Failed to verify AccSentryDaemon: $error")
                }
            }
        )
    }
    
    /**
     * Stop the AccSentryDaemon and its watchdog script.
     * Uses pkill -9 -f 'acc_sentry' to kill both daemon and watchdog in one command.
     */
    fun stopAccSentryDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Stopping AccSentryDaemon and watchdog...")
        callback.onLog("Stopping AccSentryDaemon...")
        
        // Kill everything matching 'acc_sentry' pattern - daemon AND watchdog script
        adbShellExecutor.execute(
            command = "pkill -9 -f 'acc_sentry'; " +
                "rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
                "rm -f /data/local/tmp/start_acc_sentry.sh 2>/dev/null; " +
                "echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "AccSentryDaemon stopped")
                    callback.onLog("AccSentryDaemon stopped")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    // pkill returns error if no process found - that's fine
                    logManager.info(TAG, "AccSentryDaemon stopped (or was not running)")
                    callback.onLog("AccSentryDaemon stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    // ==================== TELEGRAM BOT DAEMON ====================
    
    /**
     * Launch the Telegram Bot daemon via ADB shell.
     * Handles Telegram bot polling and notifications.
     */
    fun launchTelegramDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Launching TelegramBotDaemon...")
        callback.onLog("Launching TelegramBotDaemon...")
        
        // Check if already running
        adbShellExecutor.execute(
            command = "ps -A | grep $TELEGRAM_DAEMON_PROCESS | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "TelegramBotDaemon already running: ${output.trim()}")
                        callback.onLog("TelegramBotDaemon already running")
                        callback.onLaunched()
                        return
                    }
                    launchTelegramDaemonInternal(callback)
                }
                
                override fun onError(error: String) {
                    launchTelegramDaemonInternal(callback)
                }
            }
        )
    }
    
    private fun launchTelegramDaemonInternal(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        
        // Write output_dir to telegram config so daemon knows where events are stored
        writeOutputDirToTelegramConfig()
        
        val innerCmd = buildString {
            append("CLASSPATH=$apkPath ")
            append("app_process ")
            append(proxyArgs)
            append("/system/bin ")
            append("--nice-name=$TELEGRAM_DAEMON_PROCESS ")
            append("com.overdrive.app.daemon.TelegramBotDaemon")
        }
        
        val cmd = "nohup sh -c '$innerCmd' > $TELEGRAM_DAEMON_LOG 2>&1 &"
        
        logManager.debug(TAG, "TelegramBotDaemon command: $cmd")
        callback.onLog("Launching via ADB shell...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "TelegramBotDaemon launch command sent via ADB")
                    callback.onLog("Launch command sent, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyTelegramDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch TelegramBotDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Write output_dir and apk_path to telegram config file so daemon can find event recordings
     * and AccSentryDaemon can launch telegram daemon with correct classpath.
     */
    private fun writeOutputDirToTelegramConfig() {
        try {
            val outputDir = context.getExternalFilesDir(null)?.absolutePath 
                ?: "/sdcard/DCIM/BYDCam"
            val apkPath = context.applicationInfo.sourceDir
            
            val configFile = java.io.File("/data/local/tmp/telegram_config.properties")
            val props = java.util.Properties()
            
            // Load existing config if present
            if (configFile.exists()) {
                java.io.FileInputStream(configFile).use { fis ->
                    props.load(fis)
                }
            }
            
            // Update output_dir and apk_path
            props.setProperty("output_dir", outputDir)
            props.setProperty("apk_path", apkPath)
            
            // Write back via ADB shell (daemon runs as shell user)
            val propsContent = buildString {
                props.forEach { key, value ->
                    append("$key=$value\n")
                }
            }
            
            // Use echo to write file (works with shell permissions)
            val escapedContent = propsContent.replace("\"", "\\\"").replace("\n", "\\n")
            adbShellExecutor.execute(
                command = "echo -e \"$escapedContent\" > /data/local/tmp/telegram_config.properties",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.debug(TAG, "Wrote output_dir and apk_path to telegram config")
                    }
                    override fun onError(error: String) {
                        logManager.warn(TAG, "Failed to write telegram config: $error")
                    }
                }
            )
        } catch (e: Exception) {
            logManager.warn(TAG, "Error writing telegram config: ${e.message}")
        }
    }
    
    private fun verifyTelegramDaemonRunning(callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "ps -A -o PID,UID,ARGS | grep $TELEGRAM_DAEMON_PROCESS | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        val parts = output.trim().split(Regex("\\s+"))
                        val pid = parts.getOrNull(0) ?: "?"
                        val uid = parts.getOrNull(1) ?: "?"
                        
                        logManager.info(TAG, "TelegramBotDaemon running with PID: $pid, UID: $uid")
                        callback.onLog("TelegramBotDaemon running with PID: $pid")
                        callback.onLaunched()
                    } else {
                        // Check logs
                        adbShellExecutor.execute(
                            command = "cat $TELEGRAM_DAEMON_LOG 2>/dev/null | tail -30",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(logContent: String) {
                                    if (logContent.trim().isNotEmpty()) {
                                        logManager.error(TAG, "TelegramBotDaemon failed. Log: $logContent")
                                        callback.onError("TelegramBotDaemon failed:\n$logContent")
                                    } else {
                                        callback.onError("TelegramBotDaemon not found and no log")
                                    }
                                }
                                
                                override fun onError(error: String) {
                                    callback.onError("TelegramBotDaemon not found")
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to verify TelegramBotDaemon: $error")
                }
            }
        )
    }
    
    /**
     * Stop the Telegram Bot daemon.
     */
    fun stopTelegramDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Stopping TelegramBotDaemon...")
        callback.onLog("Stopping TelegramBotDaemon...")
        
        adbShellExecutor.execute(
            command = "pkill -9 -f $TELEGRAM_DAEMON_PROCESS 2>/dev/null; rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "TelegramBotDaemon stopped")
                    callback.onLog("TelegramBotDaemon stopped")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    // pkill returns error if process not found, which is fine
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * Launch the proxy daemon via ADB shell.
     * Provides HTTP proxy on port 8118 and manages global proxy settings.
     */
    fun launchProxyDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Launching ProxyDaemon...")
        callback.onLog("Launching ProxyDaemon...")
        
        callback.onLog("Cleaning up old processes...")
        
        // Kill old processes using PID-based approach
        killProcessesByPattern(listOf(PROXY_DAEMON_PROCESS, "sing-box")) {
            // Clean up old config files via ADB
            adbShellExecutor.execute(
                command = "rm -f /data/local/tmp/singbox_config.json /data/local/tmp/start_singbox.sh 2>/dev/null; echo done",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        // Copy sing-box to /data/system/ via privileged shell (UID 1000)
                        // UID 1000 can read /data/local/tmp AND write to /data/system/
                        callback.onLog("Copying sing-box via privileged shell...")
                        copySingboxViaPrivilegedShell(callback) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                launchProxyDaemonInternal(callback)
                            }, 500)
                        }
                    }
                    
                    override fun onError(error: String) {
                        copySingboxViaPrivilegedShell(callback) {
                            launchProxyDaemonInternal(callback)
                        }
                    }
                }
            )
        }
    }
    
    /**
     * Copy sing-box binary to /data/local/tmp/ where daemon can access it.
     * The binary is stored as libsingbox.so in the APK's native library directory.
     */
    private fun copySingboxViaPrivilegedShell(callback: LaunchCallback, onComplete: () -> Unit) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libsingbox.so"
        val destPath = "/data/local/tmp/sing-box"
        
        logManager.info(TAG, "Installing sing-box from $srcPath to $destPath")
        callback.onLog("Installing sing-box binary...")
        
        // First check if already installed and executable
        adbShellExecutor.execute(
            command = "test -x $destPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        logManager.info(TAG, "sing-box already installed")
                        callback.onLog("sing-box ready")
                        onComplete()
                    } else {
                        // Copy from native lib dir
                        adbShellExecutor.execute(
                            command = "cp $srcPath $destPath && chmod 755 $destPath && ls -la $destPath",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(copyOutput: String) {
                                    logManager.info(TAG, "sing-box installed: $copyOutput")
                                    callback.onLog("sing-box installed")
                                    onComplete()
                                }
                                
                                override fun onError(error: String) {
                                    logManager.error(TAG, "Failed to install sing-box: $error")
                                    callback.onLog("⚠ sing-box install failed: $error")
                                    // Continue anyway - proxy daemon might work without sing-box
                                    onComplete()
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    // Try to install anyway
                    adbShellExecutor.execute(
                        command = "cp $srcPath $destPath && chmod 755 $destPath",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(copyOutput: String) {
                                logManager.info(TAG, "sing-box installed")
                                callback.onLog("sing-box installed")
                                onComplete()
                            }
                            
                            override fun onError(copyError: String) {
                                logManager.error(TAG, "Failed to install sing-box: $copyError")
                                callback.onLog("⚠ sing-box install failed")
                                onComplete()
                            }
                        }
                    )
                }
            }
        )
    }
    
    /**
     * Kill processes matching patterns using pkill.
     */
    private fun killProcessesByPattern(patterns: List<String>, onComplete: () -> Unit) {
        if (patterns.isEmpty()) {
            onComplete()
            return
        }
        
        // Build pkill commands for each pattern
        val killCmds = patterns.joinToString("; ") { "pkill -9 -f '$it' 2>/dev/null" }
        adbShellExecutor.execute(
            command = "$killCmds; sleep 1; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    onComplete()
                }
                
                override fun onError(error: String) {
                    // pkill returns non-zero if no process found - that's OK
                    onComplete()
                }
            }
        )
    }
    
    private fun launchProxyDaemonInternal(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        
        val innerCmd = buildString {
            append("CLASSPATH=$apkPath ")
            append("app_process ")
            append(proxyArgs)
            append("/system/bin ")
            append("--nice-name=$PROXY_DAEMON_PROCESS ")
            append("com.overdrive.app.daemon.GlobalProxyDaemon")
        }
        
        logManager.debug(TAG, "Executing: nohup sh -c '$innerCmd' > $PROXY_DAEMON_LOG 2>&1 &")
        callback.onLog("Executing daemon launch command...")
        
        // Check if privileged shell is available via ADB (more reliable than direct socket)
        if (USE_PRIVILEGED_SHELL_FOR_PROXY) {
            callback.onLog("Checking privileged shell availability...")
            
            // Use ADB to check if port 1234 is open
            adbShellExecutor.execute(
                command = "echo 'id' | nc localhost 1234 2>/dev/null | head -1",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        /*if (output.contains("uid=1000")) {
                            logManager.info(TAG, "Privileged shell available (UID 1000 confirmed)")
                            callback.onLog("Using privileged shell (UID 1000)...")
                            // For privileged shell, redirect to /dev/null since system user can't write to /data/local/tmp
                            val privCmd = "nohup sh -c '$innerCmd' > /dev/null 2>&1 &"
                            launchProxyDaemonViaPrivilegedShell(privCmd, callback)
                        } else*/ if (output.contains("uid=")) {
                            logManager.warn(TAG, "Shell available but not UID 1000: $output")
                            callback.onLog("Shell not UID 1000, using ADB shell...")
                            val adbCmd = "nohup sh -c '$innerCmd' > $PROXY_DAEMON_LOG 2>&1 &"
                            launchProxyDaemonViaAdb(adbCmd, callback)
                        } else {
                            logManager.info(TAG, "Privileged shell not available, using ADB shell")
                            callback.onLog("Privileged shell not available, using ADB shell...")
                            val adbCmd = "nohup sh -c '$innerCmd' > $PROXY_DAEMON_LOG 2>&1 &"
                            launchProxyDaemonViaAdb(adbCmd, callback)
                        }
                    }
                    
                    override fun onError(error: String) {
                        logManager.info(TAG, "Privileged shell check failed: $error, using ADB shell")
                        callback.onLog("Using ADB shell...")
                        val adbCmd = "nohup sh -c '$innerCmd' > $PROXY_DAEMON_LOG 2>&1 &"
                        launchProxyDaemonViaAdb(adbCmd, callback)
                    }
                }
            )
        } else {
            val adbCmd = "nohup sh -c '$innerCmd' > $PROXY_DAEMON_LOG 2>&1 &"
            launchProxyDaemonViaAdb(adbCmd, callback)
        }
    }
    
    private fun launchProxyDaemonViaPrivilegedShell(cmd: String, callback: LaunchCallback) {
        // Escape single quotes for piping through nc
        // Replace ' with '\'' (end quote, escaped quote, start quote)
        val escapedCmd = cmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234"
        
        logManager.debug(TAG, "Executing via privileged shell: $ncCmd")
        
        adbShellExecutor.execute(
            command = ncCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "ProxyDaemon launch via privileged shell: $output")
                    callback.onLog("Launch command sent via privileged shell, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyDaemonRunning(PROXY_DAEMON_PROCESS, "ProxyDaemon", PROXY_DAEMON_LOG, callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Privileged shell launch failed: $error, falling back to ADB")
                    callback.onLog("Privileged shell failed, using ADB shell...")
                    launchProxyDaemonViaAdb(cmd, callback)
                }
            }
        )
    }
    
    private fun launchProxyDaemonViaAdb(cmd: String, callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "ProxyDaemon launch command sent via ADB")
                    callback.onLog("Launch command sent, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyDaemonRunning(PROXY_DAEMON_PROCESS, "ProxyDaemon", PROXY_DAEMON_LOG, callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch ProxyDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Stop the proxy daemon and clear proxy settings.
     */
    fun stopProxyDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Stopping ProxyDaemon...")
        callback.onLog("Stopping ProxyDaemon...")
        
        // Kill process and clear proxy settings
        val cmd = "pkill -9 -f '$PROXY_DAEMON_PROCESS'; " +
                "pkill -9 -f 'sing-box'; " +
                "settings delete global http_proxy 2>/dev/null; " +
                "settings put global global_http_proxy_host '' 2>/dev/null; " +
                "settings put global global_http_proxy_port '' 2>/dev/null; " +
                "settings delete global global_http_proxy_exclusion_list 2>/dev/null; " +
                "echo done"
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "ProxyDaemon stopped and settings cleared")
                    callback.onLog("ProxyDaemon stopped and settings cleared")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    // pkill returns non-zero if no process found - that's OK
                    logManager.info(TAG, "ProxyDaemon stopped (may have been already stopped)")
                    callback.onLog("ProxyDaemon stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * Check if proxy daemon is running.
     */
    fun isProxyDaemonRunning(callback: (Boolean) -> Unit) {
        isDaemonRunning(PROXY_DAEMON_PROCESS, callback)
    }
    
    /**
     * Kill a daemon process by name.
     * Detects the UID of the running process and uses the appropriate shell:
     * - UID 1000 processes need to be killed via privileged shell
     * - UID 2000 processes can be killed via ADB shell
     */
    fun killDaemon(processName: String, callback: LaunchCallback) {
        logManager.info(TAG, "Killing daemon: $processName")
        callback.onLog("Checking $processName UID...")
        
        // First, detect the UID of the running process
        adbShellExecutor.execute(
            command = "ps -A -o UID,PID,ARGS | grep '$processName' | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isEmpty()) {
                        logManager.info(TAG, "$processName not running")
                        callback.onLog("$processName not running")
                        callback.onLaunched()
                        return
                    }
                    
                    // Parse UID from output (format: "UID PID ARGS...")
                    val parts = output.trim().split(Regex("\\s+"))
                    val uid = parts.getOrNull(0)?.toIntOrNull() ?: 2000
                    val pid = parts.getOrNull(1) ?: "?"
                    
                    logManager.info(TAG, "$processName running as UID $uid (PID $pid)")
                    callback.onLog("$processName running as UID $uid (PID $pid)")
                    
                    if (uid == 1000) {
                        // Process running as system - need privileged shell to kill
                        callback.onLog("Using privileged shell to kill UID 1000 process...")
                        killDaemonViaPrivilegedShell(processName, callback)
                    } else {
                        // Process running as shell or other - ADB can kill it
                        callback.onLog("Using ADB shell to kill process...")
                        killDaemonViaAdb(processName, callback)
                    }
                }
                
                override fun onError(error: String) {
                    // Can't detect UID, try both methods
                    logManager.warn(TAG, "Could not detect UID, trying both kill methods")
                    callback.onLog("Could not detect UID, trying both methods...")
                    killDaemonViaBothShells(processName, callback)
                }
            }
        )
    }
    
    /**
     * Kill daemon via privileged shell (for UID 1000 processes).
     */
    private fun killDaemonViaPrivilegedShell(processName: String, callback: LaunchCallback) {
        val killCmd = if (processName == CAMERA_DAEMON_PROCESS) {
            "pkill -9 -f 'start_cam_daemon'; rm -f /data/local/tmp/start_cam_daemon.sh; sleep 1; pkill -9 -f '$processName'; rm -f /data/local/tmp/camera_daemon.lock"
        } else {
            "pkill -9 -f '$processName'"
        }
        val escapedCmd = killCmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234"
        
        adbShellExecutor.execute(
            command = ncCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "$processName killed via privileged shell")
                    callback.onLog("$processName stopped (via privileged shell)")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    // Privileged shell failed, try ADB as fallback
                    logManager.warn(TAG, "Privileged shell kill failed: $error, trying ADB")
                    killDaemonViaAdb(processName, callback)
                }
            }
        )
    }
    
    /**
     * Kill daemon via ADB shell (for UID 2000 processes).
     */
    private fun killDaemonViaAdb(processName: String, callback: LaunchCallback) {
        // For AccSentryDaemon, use broader pattern 'acc_sentry' to kill both daemon AND watchdog
        val killCmd = if (processName == ACC_SENTRY_DAEMON_PROCESS) {
            "pkill -9 -f 'acc_sentry' 2>/dev/null; " +
            "rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
            "rm -f /data/local/tmp/start_acc_sentry.sh 2>/dev/null; " +
            "echo done"
        } else if (processName == CAMERA_DAEMON_PROCESS) {
            // CRITICAL: Kill watchdog FIRST, wait, then kill daemon, then clean up
            // If we kill daemon first, watchdog respawns it before we can kill the watchdog
            "pkill -9 -f 'start_cam_daemon' 2>/dev/null; " +
            "rm -f /data/local/tmp/start_cam_daemon.sh 2>/dev/null; " +
            "sleep 1; " +
            "pkill -9 -f '$processName' 2>/dev/null; " +
            "killall -9 $processName 2>/dev/null; " +
            "rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null; " +
            "echo done"
        } else {
            "pkill -9 -f '$processName' 2>/dev/null; killall -9 $processName 2>/dev/null; echo done"
        }
        
        adbShellExecutor.execute(
            command = killCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "$processName stopped via ADB")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    // killall returns error if no process - that's fine
                    logManager.info(TAG, "$processName stopped (or was not running)")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * Kill daemon via both shells (when UID is unknown).
     */
    private fun killDaemonViaBothShells(processName: String, callback: LaunchCallback) {
        // First try privileged shell
        val privKillCmd = if (processName == CAMERA_DAEMON_PROCESS) {
            "pkill -9 -f 'start_cam_daemon'; rm -f /data/local/tmp/start_cam_daemon.sh; sleep 1; pkill -9 -f '$processName'; rm -f /data/local/tmp/camera_daemon.lock"
        } else {
            "pkill -9 -f '$processName'"
        }
        val escapedCmd = privKillCmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234 2>/dev/null"
        
        // For AccSentryDaemon, use broader pattern 'acc_sentry' to kill both daemon AND watchdog
        val adbKillCmd = if (processName == ACC_SENTRY_DAEMON_PROCESS) {
            "pkill -9 -f 'acc_sentry' 2>/dev/null; " +
            "rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
            "rm -f /data/local/tmp/start_acc_sentry.sh 2>/dev/null"
        } else if (processName == CAMERA_DAEMON_PROCESS) {
            "pkill -9 -f 'start_cam_daemon' 2>/dev/null; " +
            "rm -f /data/local/tmp/start_cam_daemon.sh 2>/dev/null; " +
            "sleep 1; " +
            "pkill -9 -f '$processName' 2>/dev/null; " +
            "killall -9 $processName 2>/dev/null; " +
            "rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null"
        } else {
            "pkill -9 -f '$processName' 2>/dev/null; killall -9 $processName 2>/dev/null"
        }
        
        adbShellExecutor.execute(
            command = "$ncCmd; $adbKillCmd; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "$processName stopped (tried both shells)")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    logManager.info(TAG, "$processName stopped (or was not running)")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * Check if a daemon is running.
     * Uses ps with grep which is more reliable on Android than pgrep.
     */
    fun isDaemonRunning(processName: String, callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "ps -A | grep $processName | grep -v grep",
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
     * Get process uptime in human-readable format.
     * Returns null if process is not running.
     * 
     * Uses ps with grep to find the actual daemon process,
     * sorting by uptime to get the longest-running match (the actual daemon).
     */
    fun getProcessUptime(processName: String, callback: (String?) -> Unit) {
        // Use ps to find process and get its etime
        // Sort by etime descending to get the longest-running process (the actual daemon, not shell commands)
        adbShellExecutor.execute(
            command = "ps -eo etime,args 2>/dev/null | grep '$processName' | grep -v grep | grep -v pgrep | sort -t: -k1 -rn | head -1 | awk '{print \$1}' | tr -d ' '",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val etime = output.trim()
                    if (etime.isNotEmpty() && etime.contains(":")) {
                        callback(formatUptime(etime))
                    } else {
                        callback(null)
                    }
                }
                
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Format elapsed time from ps output (e.g., "01:23:45" or "1-02:03:04") to human readable.
     * Input formats:
     * - MM:SS (e.g., "00:30", "16:49")
     * - HH:MM:SS (e.g., "01:23:45")
     * - DD-HH:MM:SS (e.g., "1-02:03:04")
     */
    private fun formatUptime(etime: String): String {
        return try {
            // Check if it contains a day separator
            if (etime.contains("-")) {
                // DD-HH:MM:SS format
                val dayParts = etime.split("-")
                val days = dayParts[0].toInt()
                val timeParts = dayParts[1].split(":")
                val hours = timeParts[0].toInt()
                val mins = timeParts[1].toInt()
                
                return when {
                    days > 0 -> "${days}d ${hours}h"
                    hours > 0 -> "${hours}h ${mins}m"
                    else -> "${mins}m"
                }
            }
            
            // Time only format
            val parts = etime.split(":")
            when (parts.size) {
                2 -> { // MM:SS
                    val mins = parts[0].toInt()
                    val secs = parts[1].toInt()
                    when {
                        mins > 0 -> "${mins}m ${secs}s"
                        secs > 0 -> "${secs}s"
                        else -> "just started"
                    }
                }
                3 -> { // HH:MM:SS
                    val hours = parts[0].toInt()
                    val mins = parts[1].toInt()
                    val secs = parts[2].toInt()
                    when {
                        hours > 0 -> "${hours}h ${mins}m"
                        mins > 0 -> "${mins}m ${secs}s"
                        else -> "${secs}s"
                    }
                }
                else -> etime
            }
        } catch (e: Exception) {
            etime
        }
    }
    
    /**
     * Data class for subprocess info.
     */
    data class ProcessInfo(
        val name: String,
        val pid: Int,
        val uptime: String
    )
    
    /**
     * Get list of subprocesses for a daemon with their PIDs and uptimes.
     */
    fun getSubprocesses(processPatterns: List<String>, callback: (List<ProcessInfo>) -> Unit) {
        if (processPatterns.isEmpty()) {
            callback(emptyList())
            return
        }
        
        // Build grep pattern for all processes
        val grepPattern = processPatterns.joinToString("\\|")
        
        // Get PID, elapsed time, and command for matching processes
        adbShellExecutor.execute(
            command = "ps -eo pid,etime,args 2>/dev/null | grep -E '${processPatterns.joinToString("|")}' | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val processes = mutableListOf<ProcessInfo>()
                    output.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            // Parse: PID ETIME COMMAND...
                            val parts = trimmed.split(Regex("\\s+"), limit = 3)
                            if (parts.size >= 3) {
                                try {
                                    val pid = parts[0].toInt()
                                    val etime = formatUptime(parts[1])
                                    val cmd = parts[2]
                                    // Extract process name from command
                                    val name = extractProcessName(cmd, processPatterns)
                                    processes.add(ProcessInfo(name, pid, etime))
                                } catch (e: Exception) {
                                    // Skip malformed lines
                                }
                            }
                        }
                    }
                    callback(processes)
                }
                
                override fun onError(error: String) {
                    callback(emptyList())
                }
            }
        )
    }
    
    private fun extractProcessName(cmd: String, patterns: List<String>): String {
        // Try to match against known patterns and return friendly name
        for (pattern in patterns) {
            if (cmd.contains(pattern)) {
                return when {
                    pattern.contains("byd_cam_daemon") -> "Camera Daemon"
                    pattern.contains("sentry_daemon") -> "Sentry Daemon"
                    pattern.contains("sing-box") -> "Sing-box"
                    pattern.contains("cloudflared") -> "Cloudflared"
                    pattern.contains("ffmpeg") -> "FFmpeg"
                    pattern.contains("mediamtx") -> "MediaMTX"
                    else -> pattern.take(20)
                }
            }
        }
        // Fallback: extract binary name from path
        return cmd.split("/").lastOrNull()?.split(" ")?.firstOrNull() ?: cmd.take(20)
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun verifyDaemonRunning(
        processName: String,
        daemonName: String,
        logPath: String,
        callback: LaunchCallback
    ) {
        adbShellExecutor.execute(
            command = "pgrep -f '$processName'",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "$daemonName running with PID: ${output.trim()}")
                        callback.onLog("$daemonName running with PID: ${output.trim()}")
                        callback.onLaunched()
                    } else {
                        checkDaemonLog(logPath, daemonName, callback)
                    }
                }
                
                override fun onError(error: String) {
                    checkDaemonLog(logPath, daemonName, callback)
                }
            }
        )
    }
    
    private fun checkDaemonLog(logPath: String, daemonName: String, callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "cat $logPath 2>/dev/null | tail -30",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    if (logContent.trim().isNotEmpty()) {
                        logManager.error(TAG, "$daemonName failed to start. Log: $logContent")
                        callback.onError("$daemonName failed to start. Log:\n$logContent")
                    } else {
                        // Check if log file exists at all
                        adbShellExecutor.execute(
                            command = "ls -la $logPath 2>&1; echo '---'; dmesg | tail -10 2>/dev/null",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(debugOutput: String) {
                                    logManager.error(TAG, "$daemonName process not found. Debug: $debugOutput")
                                    callback.onError("$daemonName process not found and no log output.\nDebug: $debugOutput")
                                }
                                
                                override fun onError(error: String) {
                                    logManager.error(TAG, "$daemonName process not found and no log output")
                                    callback.onError("$daemonName process not found and no log output")
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "$daemonName process not found and couldn't read log: $error")
                    callback.onError("$daemonName process not found and no log output")
                }
            }
        )
    }
    
    private fun grantBodyworkPermissions(callback: LaunchCallback, onComplete: () -> Unit) {
        val packageName = "com.overdrive.app"
        val permissions = listOf(
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_BODYWORK_GET",
            "android.permission.BYDAUTO_BODYWORK_SET"
        )
        
        logManager.debug(TAG, "Granting bodywork permissions...")
        
        val commands = permissions.map { "pm grant $packageName $it 2>/dev/null || true" }
        
        executeCommandSequence(commands, 0) {
            logManager.info(TAG, "Bodywork permissions granted")
            onComplete()
        }
    }
    
    private fun executeCommandSequence(commands: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= commands.size) {
            onComplete()
            return
        }
        
        adbShellExecutor.execute(
            command = commands[index],
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    executeCommandSequence(commands, index + 1, onComplete)
                }
                
                override fun onError(error: String) {
                    executeCommandSequence(commands, index + 1, onComplete)
                }
            }
        )
    }
}
