package com.overdrive.app.ui.daemon

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.ZrokLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.model.AccessMode
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.ui.viewmodel.DaemonsViewModel

class DaemonStartupManager(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel? = null
) {
    private val log = LogManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val adbLauncher = AdbDaemonLauncher(context)

    companion object {
        private const val TAG = "DaemonStartup"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        val CORE_DAEMONS: List<DaemonType> = listOf(
            DaemonType.CAMERA_DAEMON,
            DaemonType.SENTRY_DAEMON,
            DaemonType.ACC_SENTRY_DAEMON,
        )

        val OPTIONAL_DAEMONS: List<DaemonType> = listOf(
            DaemonType.SINGBOX_PROXY,
            DaemonType.CLOUDFLARED_TUNNEL,
            DaemonType.ZROK_TUNNEL,
            DaemonType.TELEGRAM_DAEMON,
        )

        // Track intentional stops so health check doesn't fight the user
        val userStoppedDaemons = mutableSetOf<DaemonType>()

        fun markUserStopped(type: DaemonType) {
            userStoppedDaemons.add(type)
        }

        fun clearUserStopped(type: DaemonType) {
            userStoppedDaemons.remove(type)
        }

        // Keep strong reference to prevent GC during delayed startup
        @Volatile
        private var bootManager: DaemonStartupManager? = null
        
        @Volatile
        private var bootStarted = false

        fun startOnBoot(context: Context) {
            if (bootStarted) return
            bootStarted = true
            userStoppedDaemons.clear()
            val manager = DaemonStartupManager(context, null)
            bootManager = manager
            manager.initializeOnBoot()
        }
    }

    fun initializeOnAppLaunch() {
        log.info(TAG, "=== Initializing daemon startup on app launch ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")

        // Reset user-stopped flags on app launch (fresh start = auto-manage)
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately (doesn't need delay)
        enableAccessibilityKeepAlive()

        // Wait 45 seconds for system to fully stabilize before starting any daemons
        handler.postDelayed({ startCoreDaemons() }, 45000)
        handler.postDelayed({ startOptionalDaemonsFromPreferences() }, 60000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({ startDaemonHealthCheck() }, 90000)
    }

    /**
     * Setup privileged shell (UID 1000) on app launch.
     * This enables system-level operations like granting permissions and running daemons as system user.
     */
    /*private fun setupPrivilegedShell(onComplete: () -> Unit) {
        PrivilegedShellSetup.init(context)
        
        // Check if already available
        if (PrivilegedShellSetup.isShellAvailable()) {
            log.info(TAG, "Privileged shell already available (UID 1000)")
            onComplete()
            return
        }
        
        log.info(TAG, "Setting up privileged shell...")
        PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
            override fun onSuccess() {
                log.info(TAG, "Privileged shell ready (UID 1000)")
                onComplete()
            }
            
            override fun onFailure(reason: String) {
                log.warn(TAG, "Privileged shell setup failed: $reason - continuing with normal startup")
                onComplete()
            }
            
            override fun onProgress(message: String) {
                log.debug(TAG, "Shell setup: $message")
            }
        })
    }*/

    private fun initializeOnBoot() {
        log.info(TAG, "=== Initializing daemon startup on boot ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")
        
        // Reset user-stopped flags on boot
        userStoppedDaemons.clear()
        
        // Enable AccessibilityService keep-alive immediately on boot
        enableAccessibilityKeepAlive()
        
        // Wait 45 seconds for system to fully stabilize before starting any daemons
        handler.postDelayed({ startCoreDaemonsViaAdb() }, 45000)
        handler.postDelayed({ startOptionalDaemonsViaAdb() }, 60000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({ startDaemonHealthCheck() }, 90000)
    }


    fun checkAllDaemonStatuses() {
        log.info(TAG, "=== Checking all daemon statuses ===")
        daemonsViewModel?.let { vm ->
            DaemonType.values().forEach { type -> vm.refreshDaemonStatus(type, logResult = true) }
            val savedMode = PreferencesManager.getAccessMode()
            val streamMode = if (savedMode == AccessMode.PUBLIC) "public" else "private"
            log.info(TAG, "Syncing camera daemon stream mode to: $streamMode")
            vm.cameraDaemonController.setStreamMode(streamMode)
        }
    }

    private fun startCoreDaemons() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startCoreDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting core daemons (Camera first, then Sentry daemons)...")
        
        // Start Camera Daemon FIRST
        log.info(TAG, "Starting Camera Daemon...")
        vm.startDaemon(DaemonType.CAMERA_DAEMON)
        
        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            log.info(TAG, "Starting Sentry Daemon...")
            vm.startDaemon(DaemonType.SENTRY_DAEMON)
        }, 5000)
        
        // Start ACC Sentry Daemon last
        handler.postDelayed({
            log.info(TAG, "Starting ACC Sentry Daemon...")
            vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON)
        }, 10000)
    }

    private fun startCoreDaemonsViaAdb() {
        log.info(TAG, "Starting core daemons via ADB (Camera first, then Sentry daemons)...")
        
        // Start Camera Daemon FIRST
        adbLauncher.isDaemonRunning("camera_daemon") { running ->
            if (!running) {
                log.info(TAG, "Boot: Starting Camera Daemon...")
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("CameraDaemon"))
            } else {
                log.info(TAG, "Boot: Camera Daemon already running")
            }
        }
        
        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            adbLauncher.isSentryDaemonRunning { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting Sentry Daemon...")
                    adbLauncher.launchSentryDaemon(createLogCallback("SentryDaemon"))
                } else {
                    log.info(TAG, "Boot: Sentry Daemon already running")
                }
            }
        }, 5000)
        
        // Start ACC Sentry Daemon last
        handler.postDelayed({
            adbLauncher.isDaemonRunning("acc_sentry_daemon") { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting ACC Sentry Daemon...")
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "Boot: ACC Sentry Daemon started") },
                        onError = { error -> log.error(TAG, "Boot: ACC Sentry error: $error") }
                    )
                } else {
                    log.info(TAG, "Boot: ACC Sentry Daemon already running")
                }
            }
        }, 10000)
    }


    private fun startOptionalDaemonsFromPreferences() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startOptionalDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting optional daemons from preferences...")
        val accessMode = PreferencesManager.getAccessMode()
        log.info(TAG, "Current access mode: $accessMode")
        
        // PUBLIC mode ALWAYS requires singbox, or user explicitly enabled it
        if (accessMode == AccessMode.PUBLIC || PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) {
            vm.singboxController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Singbox already running, skipping start")
                    // Start tunnel after confirming singbox is running
                    handler.postDelayed({ startTunnelFromPreferences(vm) }, 1000)
                } else {
                    log.info(TAG, "Starting Singbox (required for PUBLIC mode)...")
                    handler.post { vm.startDaemon(DaemonType.SINGBOX_PROXY) }
                    // Wait for singbox to start, then start tunnel
                    handler.postDelayed({ startTunnelFromPreferences(vm) }, 5000)
                }
            }
        } else {
            // PRIVATE mode - just start tunnel if enabled
            startTunnelFromPreferences(vm)
        }
        
        // Start Telegram Bot daemon if user enabled it
        if (PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON)) {
            handler.postDelayed({
                log.info(TAG, "Starting Telegram Bot daemon (user enabled)...")
                vm.startDaemon(DaemonType.TELEGRAM_DAEMON)
            }, 15000)
        }
    }

    private fun startTunnelFromPreferences(vm: DaemonsViewModel) {
        val cloudflaredEnabled = PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)
        if (cloudflaredEnabled) {
            // Do real-time check if already running before starting
            vm.cloudflaredController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Cloudflared already running, skipping start")
                } else {
                    log.info(TAG, "Starting Cloudflared (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL) }
                }
            }
        } else if (zrokEnabled) {
            // Do real-time check if already running before starting
            vm.zrokController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Zrok already running, skipping start")
                } else {
                    log.info(TAG, "Starting Zrok (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.ZROK_TUNNEL) }
                }
            }
        } else {
            log.info(TAG, "No tunnel enabled by user")
        }
    }

    private fun startOptionalDaemonsViaAdb() {
        log.info(TAG, "Starting optional daemons via ADB...")
        try {
            val accessMode = PreferencesManager.getAccessMode()
            
            // PUBLIC mode ALWAYS requires Singbox
            if (accessMode == AccessMode.PUBLIC) {
                log.info(TAG, "Boot: Starting Singbox (required for PUBLIC mode)...")
                adbLauncher.startSingbox(createLogCallback("Singbox"))
            } else if (PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) {
                log.info(TAG, "Boot: Starting Singbox (user enabled)...")
                adbLauncher.startSingbox(createLogCallback("Singbox"))
            }
            
            // Start tunnel after singbox (if in PUBLIC mode, wait for singbox)
            val tunnelDelay = if (accessMode == AccessMode.PUBLIC) 5000L else 0L
            
            handler.postDelayed({
                if (PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)) {
                    log.info(TAG, "Boot: Starting Cloudflared...")
                    adbLauncher.launchTunnel(object : AdbDaemonLauncher.TunnelCallback {
                        override fun onLog(message: String) { log.debug(TAG, "[Cloudflared] $message") }
                        override fun onTunnelUrl(url: String) { log.info(TAG, "Boot: Cloudflared URL: $url") }
                        override fun onError(error: String) { log.error(TAG, "Boot: Cloudflared error: $error") }
                    })
                } else if (PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)) {
                    log.info(TAG, "Boot: Starting Zrok...")
                    startZrokOnBoot()
                }
            }, tunnelDelay)
            
            // Start Telegram Bot daemon if user enabled it
            if (PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON)) {
                handler.postDelayed({
                    log.info(TAG, "Boot: Starting Telegram Bot daemon...")
                    adbLauncher.launchTelegramDaemon(createLogCallback("TelegramBot"))
                }, 15000) // Start after core daemons are up
            }
        } catch (e: Exception) {
            log.error(TAG, "Error starting optional daemons: ${e.message}")
        }
    }
    
    /**
     * Start Zrok tunnel on boot using ZrokLauncher directly.
     */
    private fun startZrokOnBoot() {
        val adbShellExecutor = AdbShellExecutor(context)
        val zrokLauncher = ZrokLauncher(context, adbShellExecutor, log)
        
        zrokLauncher.launchZrok(object : ZrokLauncher.ZrokCallback {
            override fun onLog(message: String) {
                log.debug(TAG, "[Zrok Boot] $message")
            }
            
            override fun onTunnelUrl(url: String) {
                log.info(TAG, "Boot: Zrok URL: $url")
            }
            
            override fun onError(error: String) {
                log.error(TAG, "Boot: Zrok error: $error")
            }
        })
    }


    fun onAccessModeChanged(newMode: AccessMode) {
        val vm = daemonsViewModel ?: return
        when (newMode) {
            AccessMode.PRIVATE -> {
                log.info(TAG, "Switched to PRIVATE mode")
                vm.cameraDaemonController.setStreamMode("private") { success ->
                    if (success) log.info(TAG, "Camera daemon set to PRIVATE mode")
                }
                // Check if singbox is running and stop it (not needed in PRIVATE mode)
                vm.singboxController.isRunning { isRunning ->
                    if (isRunning) {
                        log.info(TAG, "Stopping Sing-box (not needed in PRIVATE mode)")
                        handler.post { vm.stopDaemon(DaemonType.SINGBOX_PROXY) }
                        // Restart tunnels without proxy after singbox stops
                        handler.postDelayed({ restartTunnelIfEnabled(vm, forceRestart = true) }, 2000)
                    }
                    // If singbox wasn't running, tunnels are already running without proxy - no restart needed
                }
            }
            AccessMode.PUBLIC -> {
                log.info(TAG, "Switched to PUBLIC mode")
                vm.cameraDaemonController.setStreamMode("public") { success ->
                    if (success) log.info(TAG, "Camera daemon set to PUBLIC mode")
                }
                
                // PUBLIC mode ALWAYS requires singbox - start it regardless of user preference
                // Then restart tunnels to pick up the proxy
                vm.singboxController.isRunning { singboxWasRunning ->
                    log.info(TAG, "Singbox running check result: $singboxWasRunning")
                    if (singboxWasRunning) {
                        // Singbox already running - tunnels already have proxy, just ensure tunnel is started
                        log.info(TAG, "Sing-box already running, ensuring tunnel is started")
                        startTunnelIfEnabled(vm)
                    } else {
                        // Singbox NOT running - MUST start it for PUBLIC mode, then restart tunnels
                        log.info(TAG, "PUBLIC mode requires Sing-box - starting it now...")
                        handler.post { vm.startDaemon(DaemonType.SINGBOX_PROXY) }
                        // Wait for singbox to start, then FORCE restart tunnels with proxy
                        handler.postDelayed({
                            log.info(TAG, "Sing-box should be up, FORCE restarting tunnels with proxy...")
                            restartTunnelIfEnabled(vm, forceRestart = true)
                        }, 5000)
                    }
                }
            }
        }
    }

    /**
     * Restart tunnel if enabled. When forceRestart=true, kills existing tunnel first
     * so it can pick up new proxy settings (e.g., when switching to PUBLIC mode).
     */
    private fun restartTunnelIfEnabled(vm: DaemonsViewModel, forceRestart: Boolean = false) {
        val cloudflaredEnabled = PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)
        
        if (cloudflaredEnabled) {
            vm.cloudflaredController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Cloudflared to apply new proxy settings...")
                    handler.post { 
                        vm.stopDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                        // Wait for stop, then start
                        handler.postDelayed({
                            log.info(TAG, "Starting Cloudflared with new settings")
                            vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Cloudflared (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL) }
                } else {
                    log.info(TAG, "Cloudflared already running, no restart needed")
                }
            }
        } else if (zrokEnabled) {
            vm.zrokController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Zrok to apply new proxy settings...")
                    handler.post { 
                        vm.stopDaemon(DaemonType.ZROK_TUNNEL)
                        // Wait for stop, then start
                        handler.postDelayed({
                            log.info(TAG, "Starting Zrok with new settings")
                            vm.startDaemon(DaemonType.ZROK_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Zrok (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.ZROK_TUNNEL) }
                } else {
                    log.info(TAG, "Zrok already running, no restart needed")
                }
            }
        }
    }
    
    private fun startTunnelIfEnabled(vm: DaemonsViewModel) {
        restartTunnelIfEnabled(vm, forceRestart = false)
    }

    fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        if (type in OPTIONAL_DAEMONS) {
            val state = if (enabled) "ON" else "OFF"
            log.info(TAG, "User toggled ${type.displayName} to $state - saving preference")
            PreferencesManager.setDaemonEnabled(type, enabled)
        }
    }

    private fun createLogCallback(name: String): AdbDaemonLauncher.LaunchCallback {
        return object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[$name] $message") }
            override fun onLaunched() { log.info(TAG, "[$name] Started successfully") }
            override fun onError(error: String) { log.error(TAG, "[$name] Error: $error") }
        }
    }

    /**
     * Enable the KeepAliveAccessibilityService via ADB settings.
     * This gives the app the highest process priority — BYD's firmware
     * will not kill an active AccessibilityService even after 24+ hours.
     */
    private fun enableAccessibilityKeepAlive() {
        // Check if already running in-process first
        if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) {
            log.info(TAG, "AccessibilityService already running")
            return
        }

        log.info(TAG, "Enabling AccessibilityService keep-alive via ADB...")
        val serviceLauncher = com.overdrive.app.launcher.ServiceLauncher(
            context,
            com.overdrive.app.launcher.AdbShellExecutor(context),
            log
        )
        serviceLauncher.enableAccessibilityKeepAlive(object : com.overdrive.app.launcher.ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[A11y] $message") }
            override fun onLaunched() { log.info(TAG, "AccessibilityService keep-alive enabled") }
            override fun onError(error: String) { log.warn(TAG, "AccessibilityService enable failed: $error (non-fatal)") }
        })
    }

    private var healthCheckRunning = false

    /**
     * Periodic health check: every 30s, verify all expected daemons are alive.
     * Core daemons are always restarted. Optional daemons only if user had them enabled.
     * Daemons intentionally stopped by the user are skipped.
     */
    private fun startDaemonHealthCheck() {
        if (healthCheckRunning) return
        healthCheckRunning = true
        log.info(TAG, "Daemon health check started (interval=${HEALTH_CHECK_INTERVAL_MS / 1000}s)")
        scheduleNextHealthCheck()
    }

    private fun scheduleNextHealthCheck() {
        handler.postDelayed({
            if (healthCheckRunning) {
                runHealthCheck()
                scheduleNextHealthCheck()
            }
        }, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun runHealthCheck() {
        // Core daemons: always restart unless user explicitly stopped
        for (type in CORE_DAEMONS) {
            if (type in userStoppedDaemons) continue
            if (isDaemonStoppedViaTelegram(type)) continue
            checkAndRelaunchDaemon(type)
        }

        // Optional daemons: only restart if user had them enabled in preferences
        for (type in OPTIONAL_DAEMONS) {
            if (type in userStoppedDaemons) continue
            if (isDaemonStoppedViaTelegram(type)) continue
            if (!PreferencesManager.isDaemonEnabled(type)) continue
            checkAndRelaunchDaemon(type)
        }
    }

    /**
     * Check if a daemon was stopped via Telegram bot.
     * Reads the shared state file written by DaemonCommandHandler.
     */
    private fun isDaemonStoppedViaTelegram(type: DaemonType): Boolean {
        val telegramName = when (type) {
            DaemonType.CAMERA_DAEMON -> "camera"
            DaemonType.SENTRY_DAEMON -> "sentry"
            DaemonType.ACC_SENTRY_DAEMON -> "acc"
            DaemonType.TELEGRAM_DAEMON -> "telegram"
            DaemonType.CLOUDFLARED_TUNNEL -> "cloudflared"
            DaemonType.ZROK_TUNNEL -> "zrok"
            DaemonType.SINGBOX_PROXY -> "singbox"
        }
        return try {
            com.overdrive.app.daemon.telegram.DaemonCommandHandler.isDaemonStoppedViaTelegram(telegramName)
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAndRelaunchDaemon(type: DaemonType) {
        adbLauncher.isDaemonRunning(type.processName) { isRunning ->
            if (!isRunning) {
                log.warn(TAG, "Health check: ${type.displayName} is DEAD — relaunching...")
                relaunchDaemon(type)
            }
        }
    }

    private fun relaunchDaemon(type: DaemonType) {
        val vm = daemonsViewModel
        if (vm != null) {
            handler.post { vm.startDaemon(type) }
        } else {
            // Fallback: ADB-only launch for when ViewModel is not available (boot path)
            when (type) {
                DaemonType.CAMERA_DAEMON -> {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                    adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("HealthCheck-Camera"))
                }
                DaemonType.SENTRY_DAEMON -> {
                    adbLauncher.launchSentryDaemon(createLogCallback("HealthCheck-Sentry"))
                }
                DaemonType.ACC_SENTRY_DAEMON -> {
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "HealthCheck: ACC Sentry restarted") },
                        onError = { e -> log.error(TAG, "HealthCheck: ACC Sentry restart failed: $e") }
                    )
                }
                else -> {
                    log.warn(TAG, "Health check: no ADB fallback for ${type.displayName}")
                }
            }
        }
    }

    fun cleanup() {
        healthCheckRunning = false
        handler.removeCallbacksAndMessages(null)
        adbLauncher.closePersistentConnection()
    }
}
