package com.overdrive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.daemon.*
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.SubprocessInfo
import com.overdrive.app.ui.model.parseUptimeToMillis
import com.overdrive.app.R

/**
 * ViewModel for managing daemon states.
 */
class DaemonsViewModel(app: Application) : AndroidViewModel(app) {
    
    private val adbLauncher = AdbDaemonLauncher(app)
    
    private val controllers: Map<DaemonType, DaemonController>
    
    private val _daemonStates = MutableLiveData<Map<DaemonType, DaemonState>>()
    val daemonStates: LiveData<Map<DaemonType, DaemonState>> = _daemonStates

    // Authoritative map for read-modify-write. postValue does NOT update
    // _daemonStates.value until the main thread drains, so deriving the base
    // from .value lets back-to-back background updates lose each other's keys
    // (an ERROR posted between two poll callbacks would silently vanish).
    private val authoritativeStates =
        java.util.concurrent.ConcurrentHashMap<DaemonType, DaemonState>()

    private fun appStr(id: Int, vararg args: Any): String {
        return getApplication<Application>().getString(id, *args)
    }

    private fun publishState(type: DaemonType, state: DaemonState) {
        authoritativeStates[type] = state
        _daemonStates.postValue(HashMap(authoritativeStates))
    }
    
    // Expose cloudflared controller for tunnel URL access
    val cloudflaredController: CloudflaredController
    
    // Expose zrok controller for tunnel URL access
    val zrokController: ZrokController

    // Expose tailscale controller for tunnel URL access
    val tailscaleController: TailscaleController
    
    // Expose camera daemon controller for startup manager
    val cameraDaemonController: CameraDaemonController
    
    // Expose singbox controller for startup manager
    val singboxController: SingboxController
    
    // Reference to startup manager (set by Activity after creation)
    private var startupManager: DaemonStartupManager? = null
    
    // Expose startup manager for preference saving
    val daemonStartupManager: DaemonStartupManager?
        get() = startupManager
    
    fun setStartupManager(manager: DaemonStartupManager) {
        startupManager = manager
    }

    // Dedicated looper + retained runnable for the tunnel status poll, so the
    // loop is both OFF the main thread and cancellable in onCleared().
    private var tunnelPollThread: android.os.HandlerThread? = null
    private var tunnelPollHandler: android.os.Handler? = null
    private var tunnelPollRunnable: Runnable? = null
    /** Set in onCleared so an already-executing tick does not re-arm itself. */
    @Volatile private var tunnelPollStopped = false

    init {
        cloudflaredController = CloudflaredController(app, adbLauncher)
        zrokController = ZrokController(app, adbLauncher)
        tailscaleController = TailscaleController(app, adbLauncher)
        cameraDaemonController = CameraDaemonController(app, adbLauncher)
        singboxController = SingboxController(adbLauncher)
        
        controllers = mapOf(
            DaemonType.CAMERA_DAEMON to cameraDaemonController,
            DaemonType.SENTRY_DAEMON to SentryDaemonController(adbLauncher),
            DaemonType.ACC_SENTRY_DAEMON to AccSentryDaemonController(adbLauncher),
            DaemonType.SINGBOX_PROXY to singboxController,
            DaemonType.CLOUDFLARED_TUNNEL to cloudflaredController,
            DaemonType.ZROK_TUNNEL to zrokController,
            DaemonType.TAILSCALE_TUNNEL to tailscaleController,
            DaemonType.TELEGRAM_DAEMON to TelegramDaemonController(adbLauncher)
        )
        
        // Initialize all states as stopped
        val initialStates = DaemonType.values().associateWith { DaemonState.stopped(it) }
        authoritativeStates.putAll(initialStates)
        _daemonStates.value = initialStates
        
        // Refresh all statuses after a short delay to ensure ADB connection is ready
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            LogManager.getInstance().info("Daemons", "Initial daemon status refresh...")
            refreshAllStatuses(logResults = true)
        }, 1500)
        
        // Periodic refresh for tunnel daemons (every 30 seconds).
        //
        // Runs on a DEDICATED background looper, not the main looper, and the
        // runnable is retained so onCleared() can cancel it. Previously this
        // allocated a fresh main-thread Handler every tick and kept no
        // reference, so (a) it was un-cancellable and ticked for the entire
        // process lifetime — backgrounded, screen-off, parked — and (b) each
        // tick did its config reads (loadConfig → possible file read + JSON
        // parse under a blocking flock that a UID-2000 daemon can hold) ON THE
        // APP MAIN THREAD. A stalled app main thread contends with system_server
        // via binder and steals frames from the native head-unit UI, which is
        // the whole-system lag we're chasing.
        //
        // Behaviour is otherwise IDENTICAL: same 30s cadence, same three probes,
        // same order, same results. Safe off-main because every status update
        // goes through LiveData.postValue (thread-safe by contract); the only
        // `.value =` assignment is the init-time seed above, still on main.
        tunnelPollThread = android.os.HandlerThread("DaemonsVM-TunnelPoll").apply { start() }
        tunnelPollHandler = android.os.Handler(tunnelPollThread!!.looper)
        val poll = object : Runnable {
            override fun run() {
                if (tunnelPollStopped) return
                // Only refresh tunnel statuses periodically
                refreshDaemonStatus(DaemonType.CLOUDFLARED_TUNNEL)
                refreshDaemonStatus(DaemonType.ZROK_TUNNEL)
                refreshDaemonStatus(DaemonType.TAILSCALE_TUNNEL)
                if (!tunnelPollStopped) tunnelPollHandler?.postDelayed(this, 30000)
            }
        }
        tunnelPollRunnable = poll
        tunnelPollHandler?.postDelayed(poll, 30000)
    }

    
    /**
     * Start (or revive) a daemon.
     *
     * @param userInitiated true when the USER asked to start it (UI tap, or a
     *   Telegram start). This is the only case that may CLEAR durable stop
     *   intent: it rm's the disable sentinel, flips the optional-daemon
     *   enabled-pref back ON, clears the in-memory user-stopped flag, and
     *   applies tunnel mutual-exclusion.
     *
     *   false when the HEALTH-CHECK is reviving a daemon it believes crashed.
     *   A revival must NEVER clear stop intent: if the death was actually a
     *   user stop that a probe false-negatived (transient ADB error), wiping
     *   the sentinel + flipping the pref would make that false negative
     *   PERMANENT (the daemon would never re-suppress). So a non-user start
     *   relaunches the process ONLY — it leaves the sentinel, the pref, and
     *   the user-stopped set untouched. The relaunch is gated upstream in
     *   DaemonStartupManager.relaunchDaemon (sentinel probe) AND re-gated here
     *   on the in-memory set as a same-process race backstop.
     */
    @JvmOverloads
    fun startDaemon(type: DaemonType, userInitiated: Boolean = true) {
        val controller = controllers[type] ?: return

        if (userInitiated) {
            // Clear user-stopped flag so health check can manage this daemon
            DaemonStartupManager.clearUserStopped(type)

            // Clear the durable disable sentinel — the user is explicitly
            // (re)starting this daemon, so the watchdog + health-check should
            // be free to keep it alive again. Centralized here so EVERY UI
            // start path clears it uniformly, regardless of whether the
            // per-daemon controller's start flow also does its own sentinel
            // rm. See DaemonType.sentinelPath for the cross-UID rationale.
            clearDisableSentinel(type)

            // Cloudflared and Zrok are mutually exclusive - stop the other one
            // first. ONLY on a user start: a health-check revival must not
            // silently kill the sibling tunnel.
            if (type == DaemonType.CLOUDFLARED_TUNNEL) {
                // Stop zrok if running before starting cloudflared
                val zrokState = authoritativeStates[DaemonType.ZROK_TUNNEL]
                if (zrokState?.status == DaemonStatus.RUNNING) {
                    LogManager.getInstance().info("Daemons", "Stopping Zrok (mutually exclusive with Cloudflared)")
                    stopDaemonSilent(DaemonType.ZROK_TUNNEL)
                    // Also update preference for zrok since we're stopping it
                    startupManager?.onDaemonToggled(DaemonType.ZROK_TUNNEL, false)
                }
            } else if (type == DaemonType.ZROK_TUNNEL) {
                // Stop cloudflared if running before starting zrok
                val cloudflaredState = authoritativeStates[DaemonType.CLOUDFLARED_TUNNEL]
                if (cloudflaredState?.status == DaemonStatus.RUNNING) {
                    LogManager.getInstance().info("Daemons", "Stopping Cloudflared (mutually exclusive with Zrok)")
                    stopDaemonSilent(DaemonType.CLOUDFLARED_TUNNEL)
                    // Also update preference for cloudflared since we're stopping it
                    startupManager?.onDaemonToggled(DaemonType.CLOUDFLARED_TUNNEL, false)
                }
            }

            // For optional daemons, save the enabled state so they auto-start
            // on app restart.
            if (type in DaemonStartupManager.OPTIONAL_DAEMONS) {
                startupManager?.onDaemonToggled(type, true)
            }
        } else {
            // Health-check revival. Same-process race backstop: if the user
            // tapped Stop after this tick's probe was queued but before this
            // relaunch runs, markUserStopped() is already set synchronously on
            // the UI thread — honor it and abort the revival rather than
            // resurrect the daemon the user just stopped. (The cross-UID
            // sentinel probe in relaunchDaemon covers the cross-process case;
            // this covers the in-process TOCTOU.)
            if (type in DaemonStartupManager.userStoppedDaemons) {
                LogManager.getInstance().info("Daemons",
                    "Skipping health-check revival of ${type.displayName} — user-stopped flag set")
                return
            }
        }

        updateState(type, DaemonStatus.STARTING, appStr(R.string.daemon_status_starting))
        
        controller.start(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }
            
            override fun onError(error: String) {
                updateState(type, DaemonStatus.ERROR, error)
            }
        })
    }
    
    /**
     * Plant the durable, cross-UID "user stopped it" sentinel for [type].
     * Written `chmod 666` so the UID-2000 daemon family (watchdog scripts)
     * and the app's own health-check probe can both read it. See
     * [DaemonType.sentinelPath]. Fire-and-forget — the kill that follows is
     * the user-visible action; the sentinel just gates future auto-restart.
     */
    private fun writeDisableSentinel(type: DaemonType) {
        val path = type.sentinelPath
        adbLauncher.executeShellCommand(
            "echo \"disabled by ui at \$(date)\" > $path; chmod 666 $path 2>/dev/null; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {
                    LogManager.getInstance().warn("Daemons",
                        "Failed to write disable sentinel for ${type.displayName}: $error")
                }
            }
        )
    }

    /**
     * Remove [type]'s disable sentinel so the watchdog + health-check are
     * free to keep it alive again. Called on every UI start.
     *
     * If this rm fails (transient ADB transport hiccup) the daemon still
     * starts, but a STALE .disabled file is now left on disk — and for
     * daemons whose launch path does NOT rm their own sentinel (sentry,
     * singbox, tailscale, cloudflared, unlike camera/acc which re-rm it in
     * their watchdog deploy) that stale file will make the health-check
     * relaunchDaemon gate refuse to revive a LATER crash, wedging the daemon
     * dead for the rest of the session. We can't block the start on this
     * fire-and-forget rm, so at minimum WARN-log the failure so the wedge is
     * diagnosable rather than silent.
     */
    private fun clearDisableSentinel(type: DaemonType) {
        adbLauncher.executeShellCommand(
            "rm -f ${type.sentinelPath} 2>/dev/null; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {
                    LogManager.getInstance().warn("Daemons",
                        "Failed to clear disable sentinel for ${type.displayName}: $error" +
                            " — a later crash of this daemon may not auto-restart until next app launch")
                }
            }
        )
    }

    /**
     * Stop daemon silently (used for mutual exclusion between tunnels).
     * Doesn't update preferences or show stopping state.
     */
    private fun stopDaemonSilent(type: DaemonType) {
        val controller = controllers[type] ?: return
        controller.stop(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }
            override fun onError(error: String) {
                // Ignore errors for silent stop
                updateState(type, DaemonStatus.STOPPED, "Stopped")
            }
        })
    }
    
    fun stopDaemon(type: DaemonType) {
        val controller = controllers[type] ?: return

        // Mark as user-stopped so the in-session health check doesn't fight
        // the user (fast, in-memory; cleared on app relaunch).
        DaemonStartupManager.markUserStopped(type)

        // Plant the DURABLE, cross-UID disable sentinel BEFORE the controller
        // kills the process. This is what makes a user stop survive across an
        // app restart and — critically — what the health-check now checks
        // before relaunching (the in-memory set above is wiped on relaunch,
        // and the old Telegram .properties file is unreadable across the UID
        // boundary). Writing it before the kill also means any watchdog that
        // races the kill sees the sentinel on its next loop and exits instead
        // of respawning. Per-controller stop flows still write their own
        // sentinel too where they already did; this is idempotent.
        writeDisableSentinel(type)

        updateState(type, DaemonStatus.STOPPING, "Stopping daemon and related processes...")

        // For optional daemons, save the disabled state so they don't auto-start on app restart
        if (type in DaemonStartupManager.OPTIONAL_DAEMONS) {
            startupManager?.onDaemonToggled(type, false)
        }

        controller.stop(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }
            
            override fun onError(error: String) {
                // Stop failed - refresh actual status
                updateState(type, DaemonStatus.ERROR, appStr(R.string.daemon_status_stop_failed, error))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    refreshDaemonStatus(type)
                }, 1000)
            }
        })
    }
    
    fun refreshDaemonStatus(type: DaemonType, logResult: Boolean = false) {
        val controller = controllers[type] ?: return
        
        // Special handling for Zrok - check token first
        if (type == DaemonType.ZROK_TUNNEL) {
            zrokController.hasEnableToken { hasToken ->
                if (!hasToken) {
                    // No token configured - show needs config state
                    updateZrokNeedsConfig(appStr(R.string.daemon_config_no_token))
                    if (logResult) {
                        LogManager.getInstance().debug("Daemons", "${type.name}: No token configured")
                    }
                    return@hasEnableToken
                }
                
                // Token exists, proceed with normal status check
                doRefreshDaemonStatus(type, controller, logResult)
            }
            return
        } else if (type == DaemonType.CLOUDFLARED_TUNNEL) {
            if (!com.overdrive.app.config.CloudflaredPaidConfig.isConfigured()) {
                updateCloudflaredNeedsConfig(appStr(R.string.daemon_config_paid_token))
                if (logResult) {
                    LogManager.getInstance().debug("Daemons", "${type.name}: Paid version requires a token")
                }
                return
            }
        } else if (type == DaemonType.TAILSCALE_TUNNEL) {
            tailscaleController.needsLogin { needsLogin ->
                if (needsLogin) {
                    // No token configured - show needs config state
                    updateTailscaleNeedsLogin(appStr(R.string.daemon_config_not_logged_in))
                    if (logResult) {
                        LogManager.getInstance().debug("Daemons", "${type.name}: Not logged in")
                    }
                    return@needsLogin
                }

                // User logged in, proceed with normal status check
                doRefreshDaemonStatus(type, controller, logResult)
            }
            return
        }
        
        doRefreshDaemonStatus(type, controller, logResult)
    }
    
    private fun doRefreshDaemonStatus(type: DaemonType, controller: DaemonController, logResult: Boolean) {
        
        controller.isRunning { isRunning ->
            if (logResult) {
                LogManager.getInstance().debug("Daemons", "refreshDaemonStatus: ${type.name} isRunning=$isRunning")
            }
            
            if (isRunning) {
                // Get process uptime and subprocesses
                val processName = getProcessName(type)
                val subprocessPatterns = getSubprocessPatterns(type)
                
                adbLauncher.getProcessUptime(processName) { uptime ->
                    // Get subprocess info
                    adbLauncher.getSubprocesses(subprocessPatterns) { processes ->
                        val subprocesses = processes.map { p ->
                            SubprocessInfo(p.name, p.pid, p.uptime)
                        }
                        
                        // For cloudflared, also fetch the tunnel URL
                        if (type == DaemonType.CLOUDFLARED_TUNNEL) {
                            cloudflaredController.refreshTunnelUrl { url ->
                                val statusText = url ?: appStr(R.string.daemon_status_running)
                                updateStateWithSubprocesses(type, DaemonStatus.RUNNING, statusText, uptime, subprocesses)
                                if (logResult) {
                                    val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                                    LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr" + (url?.let { " - $it" } ?: ""))
                                    subprocesses.forEach { sp ->
                                        LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                                    }
                                }
                            }
                        } else if (type == DaemonType.ZROK_TUNNEL) {
                            // For zrok, also fetch the tunnel URL
                            zrokController.refreshTunnelUrl { url ->
                                val statusText = url ?: appStr(R.string.daemon_status_running)
                                updateStateWithSubprocesses(type, DaemonStatus.RUNNING, statusText, uptime, subprocesses)
                                if (logResult) {
                                    val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                                    LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr" + (url?.let { " - $it" } ?: ""))
                                    subprocesses.forEach { sp ->
                                        LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                                    }
                                }
                            }
                        } else if (type == DaemonType.TAILSCALE_TUNNEL) {
                            // For tailscale, also fetch the tunnel URL and proxy state
                            tailscaleController.refreshTunnelUrl { url ->
                                tailscaleController.isProxyEnabled { proxyOn ->
                                    val base = url ?: appStr(R.string.daemon_status_running)
                                    val statusText = if (proxyOn) {
                                        appStr(R.string.daemon_status_proxy_on, base)
                                    } else base
                                    updateStateWithSubprocesses(type, DaemonStatus.RUNNING, statusText, uptime, subprocesses)
                                    if (logResult) {
                                        val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                                        val proxyStr = if (proxyOn) " [proxy ON]" else ""
                                        LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr$proxyStr" + (url?.let { " - $it" } ?: ""))
                                        subprocesses.forEach { sp ->
                                            LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                                        }
                                    }
                                }
                            }
                        } else {
                            updateStateWithSubprocesses(type, DaemonStatus.RUNNING, "Running", uptime, subprocesses)
                            if (logResult) {
                                val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                                LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr")
                                subprocesses.forEach { sp ->
                                    LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                                }
                            }
                        }
                    }
                }
            } else {
                updateState(type, DaemonStatus.STOPPED, "Not running")
                if (logResult) {
                    LogManager.getInstance().debug("Daemons", "${type.name}: Not running")
                }
            }
        }
    }
    
    private fun getProcessName(type: DaemonType): String {
        return when (type) {
            DaemonType.CAMERA_DAEMON -> "byd_cam_daemon"
            DaemonType.SENTRY_DAEMON -> "sentry_daemon"
            DaemonType.ACC_SENTRY_DAEMON -> "acc_sentry_daemon"
            DaemonType.SINGBOX_PROXY -> "sing-box"
            DaemonType.CLOUDFLARED_TUNNEL -> "cloudflared tunnel"
            DaemonType.ZROK_TUNNEL -> "zrok share"
            DaemonType.TAILSCALE_TUNNEL -> "tailscaled"
            DaemonType.TELEGRAM_DAEMON -> "telegram_bot_daemon"
        }
    }
    
    private fun getSubprocessPatterns(type: DaemonType): List<String> {
        return when (type) {
            DaemonType.CAMERA_DAEMON -> listOf("byd_cam_daemon", "ffmpeg", "mediamtx")
            DaemonType.SENTRY_DAEMON -> listOf("sentry_daemon")
            DaemonType.ACC_SENTRY_DAEMON -> listOf("acc_sentry_daemon")
            DaemonType.SINGBOX_PROXY -> listOf("sing-box")
            DaemonType.CLOUDFLARED_TUNNEL -> listOf("cloudflared")
            DaemonType.ZROK_TUNNEL -> listOf("zrok")
            DaemonType.TAILSCALE_TUNNEL -> listOf("tailscaled")
            DaemonType.TELEGRAM_DAEMON -> listOf("telegram_bot_daemon")
        }
    }
    
    private fun updateStateWithSubprocesses(
        type: DaemonType, 
        status: DaemonStatus, 
        message: String,
        uptime: String?,
        subprocesses: List<SubprocessInfo>
    ) {
        val startTime = uptime?.let { System.currentTimeMillis() - parseUptimeToMillis(it) }
        publishState(type, DaemonState(type, status, message, uptime, startTime, subprocesses))
    }
    
    fun refreshAllStatuses(logResults: Boolean = false) {
        if (logResults) {
            LogManager.getInstance().info("Daemons", "Checking daemon statuses...")
        }
        DaemonType.values().forEach { type ->
            refreshDaemonStatus(type, logResults)
        }
    }
    
    fun cleanupAll() {
        controllers.values.forEach { it.cleanup() }
        
        // Reset all states to stopped
        val stoppedStates = DaemonType.values().associateWith { DaemonState.stopped(it) }
        authoritativeStates.putAll(stoppedStates)
        _daemonStates.postValue(HashMap(authoritativeStates))
    }
    
    private fun updateState(type: DaemonType, status: DaemonStatus, message: String) {
        publishState(type, DaemonState(type, status, message))
    }
    
    // Reads the authoritative map, not LiveData.value: postValue leaves .value
    // stale until the main thread drains, so a caller could act on old state.
    fun getState(type: DaemonType): DaemonState? = authoritativeStates[type]
    
    /**
     * Update Zrok state to indicate configuration is needed.
     */
    fun updateZrokNeedsConfig(message: String) {
        publishState(DaemonType.ZROK_TUNNEL,
            DaemonState.needsConfig(DaemonType.ZROK_TUNNEL, message))
    }

    /**
     * Update Cloudflared state to indicate configuration is needed.
     */
    fun updateCloudflaredNeedsConfig(message: String) {
        publishState(DaemonType.CLOUDFLARED_TUNNEL,
            DaemonState.needsConfig(DaemonType.CLOUDFLARED_TUNNEL, message))
    }

    /**
     * Update Tailscale state to indicate needs login.
     */
    fun updateTailscaleNeedsLogin(message: String) {
        publishState(DaemonType.TAILSCALE_TUNNEL,
            DaemonState.needsConfig(DaemonType.TAILSCALE_TUNNEL, message))
    }
    
    /**
     * Start Location Sidecar service via ADB (grants permissions first).
     * This reuses the existing adbLauncher to avoid multiple ADB auth popups.
     */
    fun startLocationSidecarService(callback: AdbDaemonLauncher.LaunchCallback) {
        adbLauncher.startLocationSidecarService(callback)
    }
    
    override fun onCleared() {
        super.onCleared()
        // Stop the tunnel status poll and its looper. Previously this loop was
        // un-cancellable (new main-thread Handler per tick, no retained
        // reference) and outlived the ViewModel for the whole process lifetime.
        // Order matters: set the stop flag, drop the queued tick, THEN quit the
        // looper, and only null the fields last. Nulling the handler before the
        // quit left an in-flight tick's `tunnelPollHandler?.postDelayed(...)`
        // re-post silently no-oping on one interleaving and targeting a
        // just-quit looper on the other — benign either way, but the flag makes
        // the intent explicit rather than luck-dependent.
        tunnelPollStopped = true
        tunnelPollRunnable?.let { tunnelPollHandler?.removeCallbacks(it) }
        try { tunnelPollThread?.quitSafely() } catch (e: Exception) {
            // best-effort; a failed quit must not skip the releases below
        }
        tunnelPollRunnable = null
        tunnelPollHandler = null
        tunnelPollThread = null
        // Release per-controller threads (e.g. ZrokController's dedicated
        // reconcileScheduler + AdbShellExecutor) WITHOUT killing the
        // daemons themselves. Daemons run in their own UID 2000 processes
        // and are intentionally persistent across app teardown — only
        // user-initiated stop() should kill them.
        controllers.values.forEach {
            try { it.releaseResources() } catch (e: Exception) {
                // best-effort; one failure must not skip the rest
            }
        }
        // releasePerInstanceResources — NOT closePersistentConnection.
        // closePersistentConnection nulls the process-wide shared Dadb in
        // AdbShellExecutor's companion. Other AdbDaemonLauncher instances
        // (DaemonStartupManager.adbLauncher's static if still alive,
        // AdbConsoleFragment, AppUpdater) share the same Dadb and would
        // see closed-transport errors on every in-flight task. We only
        // own this ViewModel's per-instance executor + tunnel-poll
        // scheduler; release just those.
        adbLauncher.releasePerInstanceResources()
    }
}
