package com.overdrive.app.ui.daemon

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.ZrokLauncher
import com.overdrive.app.launcher.TailscaleLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.telegram.config.UnifiedTelegramConfig
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.ui.viewmodel.DaemonsViewModel

class DaemonStartupManager(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel? = null
) {
    private val log = LogManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())

    // Dedicated looper for the 30s daemon health check. That tick does blocking
    // process/sentinel shell probes and runs with NO Activity present
    // (armed from DaemonKeepaliveService / KeepAliveAccessibilityService) for the
    // entire process lifetime in the default onAndOff mode. On the main looper
    // that stalls the app's main thread, which contends with system_server over
    // binder and steals frames from the native head-unit UI. Only the loop's
    // scheduling moves here; every callback that must touch the ViewModel still
    // hops to `handler` (main) explicitly — see doRelaunchDaemon.
    private val healthCheckThread =
        android.os.HandlerThread("DaemonHealthCheck").apply { start() }
    private val healthCheckHandler = Handler(healthCheckThread.looper)
    // Public so MainActivity / fragments / one-shot callers can route their
    // ADB-shell commands through this single shared launcher instead of
    // allocating fresh `AdbDaemonLauncher(this)` instances each call.
    // Each fresh AdbDaemonLauncher allocates a non-daemon single-thread
    // AdbShellExecutor + a tunnelLauncher.pollScheduler + nested launchers
    // that hold Activity Context refs — those leak when the caller never
    // calls closePersistentConnection().
    val adbLauncher = AdbDaemonLauncher(context)

    // Cached ZrokLauncher for the health-check tick. Allocating a fresh
    // ZrokLauncher + AdbShellExecutor + ScheduledExecutorService every 30s
    // (≈2880 instances per 24h park) burns heap with daemon-thread executors
    // that aren't promptly GC'd because the executor's worker is daemon-flagged
    // but still holds a reference to the launcher's Context. Lazy so we don't
    // pay for it when zrok isn't enabled. The companion @Volatile init flag
    // lets cleanup() decide whether shutdown is needed without forcing
    // allocation.
    @Volatile
    private var zrokLauncherInitialized = false
    @Volatile
    private var zrokAdbShellExecutor: AdbShellExecutor? = null
    private val zrokLauncherForHealthCheck: ZrokLauncher by lazy {
        val executor = AdbShellExecutor(context)
        zrokAdbShellExecutor = executor
        zrokLauncherInitialized = true
        ZrokLauncher(context, executor, log)
    }

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
            DaemonType.TAILSCALE_TUNNEL,
            DaemonType.TELEGRAM_DAEMON,
        )

        // Track intentional stops so health check doesn't fight the user.
        // Mutated from controller threads (markUserStopped/clearUserStopped)
        // and read from the main looper (runHealthCheck.contains). A plain
        // mutableSetOf is a LinkedHashSet — concurrent add/iterate throws
        // ConcurrentModificationException on the main thread. Wrap with
        // ConcurrentHashMap.newKeySet for thread-safe traversal without
        // an explicit lock.
        val userStoppedDaemons: MutableSet<DaemonType> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

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
            // NOTE: recoveryInProgress is deliberately NOT reset here. Resetting it
            // synchronously in startOnBoot loses the race it exists to win: the sibling
            // services' onStartCommand can run AFTER this reset but BEFORE clearParkedMarker's
            // async `rm` lands, so they'd see marker-present + flag-false and wrongly
            // self-stop on the recovery edge. Instead it is reset from clearParkedMarker's
            // rm-completion callback (below), i.e. only once the marker file is actually gone
            // — at which point the exists() check the gates use is itself already false.
            userStoppedDaemons.clear()
            val manager = DaemonStartupManager(context, null)
            bootManager = manager
            manager.initializeOnBoot()
        }

        /**
         * Stop the boot-scoped manager's 30s health-check (used by the onOnly parked
         * standdown so the health-check stops probing/relaunching while parked). The
         * MainActivity-scoped manager, if any, is stopped when the Activity is destroyed;
         * and even if a health-check keeps ticking, relaunchDaemon's marker gate prevents
         * any actual rebuild — so this is the compute-minimizing complement, not the
         * correctness guarantee. Safe/no-op if no boot manager exists.
         */
        fun stopHealthChecks() {
            try { bootManager?.cleanup() } catch (e: Exception) {
                android.util.Log.w(TAG, "stopHealthChecks failed: ${e.message}")
            }
            // DROP the reference: cleanup() now quitSafely()s the manager's
            // healthCheckThread, so the instance is permanently un-armable — its
            // looper is dead and startDaemonHealthCheck() would post into a queue
            // that never runs. Leaving bootManager pointing at a cleaned-up
            // instance would silently disable the health check for the rest of the
            // process if anything re-armed it. recoverFromPark() already nulls it
            // on the ACC-on edge; doing it here too means the guarantee no longer
            // depends on those two paths staying paired. A fresh startOnBoot()
            // builds a new manager (with a live thread), which is what we want.
            bootManager = null
        }

        /**
         * ACC-on / boot RECOVERY from an onOnly park. Two things must happen and BOTH are
         * load-bearing:
         *  1. Clear the parked-shutdown marker (so redeployed watchdogs don't immediately
         *     gate-exit on it).
         *  2. RESET the `bootStarted` guard so startOnBoot() actually redeploys the
         *     watchdogs. This is the subtle bug the naive path has: on this head unit the
         *     app process is kept resident by KeepAliveAccessibilityService across a park,
         *     so `bootStarted` (a process-lifetime static, never otherwise reset) is still
         *     true from the pre-park boot — and `startOnBoot(){ if(bootStarted) return }`
         *     would no-op, leaving the killed daemons permanently down. Resetting it here
         *     lets recovery rebuild the stack.
         * Called from BootReceiver.startDaemons on a recovery trigger. Ordered: clear marker
         * FIRST (async shell), then reset the guard, then the caller's startOnBoot redeploys.
         */
        /**
         * True from the instant an ACC-on recovery begins until the daemon stack has been
         * asked to redeploy. DaemonKeepaliveService.onStartCommand consults this to avoid
         * self-stopping on the recovery edge: clearParkedMarker's shell `rm` is async, so
         * a synchronous File(marker).exists() check in onStartCommand — which runs on the
         * main thread moments after recoverFromPark — can still see the marker present and
         * wrongly self-stop. This in-memory flag flips synchronously so the service knows
         * "recovery in progress, do not self-stop even if the marker file still lingers".
         */
        @JvmStatic
        @Volatile
        var recoveryInProgress = false
            private set

        fun recoverFromPark(context: Context) {
            recoveryInProgress = true
            clearParkedMarker(context)
            // Allow startOnBoot to run again for the fresh ON session. The daemons + their
            // watchdogs were killed by the reaper during the park, so a redeploy is exactly
            // what we need — the double-launch this guard normally prevents cannot happen
            // because nothing is currently running.
            bootStarted = false
            bootManager = null
            android.util.Log.i(TAG, "recoverFromPark: cleared marker + reset bootStarted for ACC-on redeploy")
        }

        /**
         * Static entry point to clear the "Vehicle ON only" parked-shutdown marker on the
         * ACC-on / boot recovery edge (called from BootReceiver.startDaemons). Uses a
         * short-lived launcher rather than requiring a live manager instance, since the
         * recovery path may run in a freshly-revived process with no manager yet. The
         * subsequent startOnBoot redeploys the watchdogs (marker now gone → they run).
         */
        fun clearParkedMarker(context: Context) {
            try {
                val launcher = AdbDaemonLauncher(context.applicationContext)
                launcher.executeShellCommand(
                    "rm -f ${com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH} 2>/dev/null; echo cleared",
                    object : AdbDaemonLauncher.LaunchCallback {
                        override fun onLog(message: String) {}
                        // Reset recoveryInProgress ONLY once the async rm has actually
                        // completed — at that point the marker file is gone, so any
                        // sibling-service onStartCommand gate (marker && !recoveryInProgress)
                        // that runs after this already reads exists()==false and stays up.
                        // Resetting it any earlier (e.g. synchronously in startOnBoot) loses
                        // the recovery-edge race the flag exists to win.
                        override fun onLaunched() { recoveryInProgress = false }
                        // On rm failure the marker may still be present; clearing the flag is
                        // still correct because the 24h stale-clear + next recovery cover it,
                        // and leaving it true forever would break the NEXT park's gate.
                        override fun onError(error: String) { recoveryInProgress = false }
                    }
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "clearParkedMarker(static) failed: ${e.message}")
                recoveryInProgress = false
            }
        }
    }

    fun initializeOnAppLaunch() {
        log.info(TAG, "=== Initializing daemon startup on app launch ===")
        log.info(TAG, "Waiting 2.5 seconds before starting daemons (system stabilization)...")

        // Hand off from any pre-existing bootManager (which was launched
        // before MainActivity attached). If we don't shut its scheduler
        // down, both managers fire 30s health checks in parallel — double
        // pkill cascades against the daemon family every tick. Treat
        // `this` as the new owner; drop the boot manager.
        val previousBootManager = bootManager
        if (previousBootManager != null && previousBootManager !== this) {
            log.info(TAG, "Handing off from bootManager → MainActivity-scoped manager")
            // Run cleanup on a background thread, NOT on the main looper.
            // cleanup() now calls adbLauncher.releasePerInstanceResources()
            // (per-instance executor + tunnel-poll scheduler shutdown only;
            // does NOT touch the process-wide shared Dadb that the new
            // manager has just started using). The shutdownNow() call
            // inside is bounded — it interrupts in-flight Runnables but
            // doesn't block on Socket I/O — so we COULD run this on the
            // main looper in principle. We still post to a background
            // thread defensively in case any future cleanup step adds
            // I/O; the cost is one short-lived daemon Thread per
            // handoff (one-time, not per-tick).
            //
            // Clear the static reference up-front so subsequent
            // initializeOnAppLaunch calls don't re-attempt the handoff,
            // and so the manager can't be re-used after we've started
            // tearing it down.
            bootManager = null
            Thread({
                try {
                    previousBootManager.cleanup()
                } catch (e: Exception) {
                    log.warn(TAG, "bootManager handoff cleanup failed: ${e.message}")
                }
            }, "bootManager-handoff").apply {
                isDaemon = true
                start()
            }
        }

        // Reset only the process-local cache. Durable UI/Telegram stop intent
        // lives in each daemon's .disabled sentinel and survives this launch.
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately (doesn't need delay)
        enableAccessibilityKeepAlive()

        // "Vehicle ON only" recovery: initializeOnAppLaunch only ever runs when the head
        // unit is powered — i.e. the car is on (the screen is the app's only display, so a
        // parked/off car can't launch the app) or booting. That is a recovery moment, so
        // clear any parked-shutdown marker a prior park left behind, letting the stack come
        // back up. Belt-and-suspenders with BootReceiver's ACC-on clear. No-op if absent.
        clearParkedMarker(context.applicationContext)

        // Keep manual-stop sentinels. Automatic startup classifies and clears
        // only machine-written markers immediately before starting a daemon.
        clearStaleSentinels()

        // Wait 2.5 seconds for system/ADB stabilization on app launch (instead of 45s cold-boot wait)
        handler.postDelayed({ startCoreDaemons() }, 2500)
        handler.postDelayed({ startOptionalDaemonsFromPreferences() }, 5000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({ startDaemonHealthCheck() }, 20000)
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
        
        // Reset only the process-local cache; durable manual stops survive boot.
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately on boot
        enableAccessibilityKeepAlive()

        // Keep manual-stop sentinels; see initializeOnAppLaunch.
        clearStaleSentinels()

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
            // Camera daemon defaults to private stream mode. Public exposure is opt-in
            // per-tunnel (cloudflared / zrok) via the Daemons settings, not a global mode.
            log.info(TAG, "Syncing camera daemon stream mode to: private")
            vm.cameraDaemonController.setStreamMode("private")
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
        ifNotUserStopped(DaemonType.CAMERA_DAEMON) {
            log.info(TAG, "Starting Camera Daemon...")
            vm.startDaemon(DaemonType.CAMERA_DAEMON, userInitiated = false)
        }
        
        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            ifNotUserStopped(DaemonType.SENTRY_DAEMON) {
                log.info(TAG, "Starting Sentry Daemon...")
                vm.startDaemon(DaemonType.SENTRY_DAEMON, userInitiated = false)
            }
        }, 5000)
        
        // Start ACC Sentry Daemon last
        handler.postDelayed({
            ifNotUserStopped(DaemonType.ACC_SENTRY_DAEMON) {
                log.info(TAG, "Starting ACC Sentry Daemon...")
                vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON, userInitiated = false)
            }
        }, 10000)
    }

    private fun startCoreDaemonsViaAdb() {
        log.info(TAG, "Starting core daemons via ADB (Camera first, then Sentry daemons)...")

        // Kill-then-launch, not check-then-skip: a daemon process surviving
        // an app update/reinstall (it's a detached shell-uid process, not
        // part of this app's own process tree, so `pm install`/data-clear
        // never touches it) keeps running whatever bytecode its JVM loaded
        // at its OWN launch time — an old app version's daemon can still be
        // alive and serving requests long after a newer APK is installed,
        // silently running stale code with none of that update's fixes.
        // The previous isDaemonRunning() check only asked "is a process
        // with this name alive," never "is it OUR version," so it would
        // happily leave a stale daemon in place forever. Unconditionally
        // killing first (best-effort — a daemon that isn't running is a
        // harmless no-op per killDaemon's own "not running" short-circuit)
        // guarantees every boot picks up whatever code the just-installed
        // APK actually contains.
        ifNotUserStopped(DaemonType.CAMERA_DAEMON) {
            log.info(TAG, "Boot: Ensuring a fresh Camera Daemon (killing any existing instance first)...")
            adbLauncher.killDaemon(DaemonType.CAMERA_DAEMON.processName, object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) { log.info(TAG, "Boot: [kill byd_cam_daemon] $message") }
                override fun onLaunched() { startFreshCameraDaemon() }
                override fun onError(error: String) {
                    log.warn(TAG, "Boot: kill byd_cam_daemon failed ($error) — launching anyway")
                    startFreshCameraDaemon()
                }
            })
        }

        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            ifNotUserStopped(DaemonType.SENTRY_DAEMON) {
                log.info(TAG, "Boot: Ensuring a fresh Sentry Daemon (killing any existing instance first)...")
                adbLauncher.killDaemon("sentry_daemon", object : AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) { log.info(TAG, "Boot: [kill sentry_daemon] $message") }
                    override fun onLaunched() { startFreshSentryDaemon() }
                    override fun onError(error: String) {
                        log.warn(TAG, "Boot: kill sentry_daemon failed ($error) — launching anyway")
                        startFreshSentryDaemon()
                    }
                })
            }
        }, 5000)

        // Start ACC Sentry Daemon last
        handler.postDelayed({
            ifNotUserStopped(DaemonType.ACC_SENTRY_DAEMON) {
                log.info(TAG, "Boot: Ensuring a fresh ACC Sentry Daemon (killing any existing instance first)...")
                adbLauncher.killDaemon("acc_sentry_daemon", object : AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) { log.info(TAG, "Boot: [kill acc_sentry_daemon] $message") }
                    override fun onLaunched() { startFreshAccSentryDaemon() }
                    override fun onError(error: String) {
                        log.warn(TAG, "Boot: kill acc_sentry_daemon failed ($error) — launching anyway")
                        startFreshAccSentryDaemon()
                    }
                })
            }
        }, 10000)
    }

    private fun startFreshCameraDaemon() {
        log.info(TAG, "Boot: Starting Camera Daemon...")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("CameraDaemon"))
    }

    private fun startFreshSentryDaemon() {
        log.info(TAG, "Boot: Starting Sentry Daemon...")
        adbLauncher.launchSentryDaemon(createLogCallback("SentryDaemon"))
    }

    private fun startFreshAccSentryDaemon() {
        log.info(TAG, "Boot: Starting ACC Sentry Daemon...")
        adbLauncher.launchAccSentryDaemon(
            onSuccess = { log.info(TAG, "Boot: ACC Sentry Daemon started") },
            onError = { error -> log.error(TAG, "Boot: ACC Sentry error: $error") }
        )
    }


    /**
     * Run [onAllowed] only when [type] has no durable manual-stop sentinel.
     * Both UI and Telegram stops write "disabled by ui/telegram"; machine
     * markers used by updates and ACC arbitration are removed here.
     *
     * Probe runs as the app's shared launcher (UID lets it stat
     * /data/local/tmp). Probe errors fail closed: preserving an explicit user
     * stop is more important than one automatic start attempt.
     */
    private fun ifNotUserStopped(type: DaemonType, retryCount: Int = 0, onAllowed: () -> Unit) {
        val probe =
            "S='${type.sentinelPath}'; " +
            "if [ ! -f \"\$S\" ]; then echo OK; " +
            "else R=\$(head -1 \"\$S\" 2>/dev/null); " +
            "case \"\$R\" in " +
            "'disabled by ui'*|'disabled by telegram'*|'disabled by user'*) echo STOPPED;; " +
            "'') echo STOPPED;; " +
            "*) rm -f \"\$S\" 2>/dev/null && echo MACHINE || echo STOPPED;; " +
            "esac; fi"
        adbLauncher.executeShellCommand(
            probe,
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    val out = message.trim()
                    when {
                        out.contains("STOPPED") ->
                            log.info(TAG, "Auto-start: ${type.displayName} is manually stopped — skipping")
                        out.contains("OK") || out.contains("MACHINE") -> {
                            if (out.contains("MACHINE")) {
                                log.info(TAG, "Auto-start: cleared machine stop for ${type.displayName}")
                            }
                            handler.post { onAllowed() }
                        }
                        else -> log.warn(TAG, "Auto-start sentinel probe returned no decision for " +
                            "${type.displayName} — leaving it stopped")
                    }
                }
                override fun onLaunched() {}
                override fun onError(error: String) {
                    if (retryCount < 3) {
                        log.warn(TAG, "Auto-start sentinel probe failed for ${type.displayName} ($error) — retrying in 2s (attempt ${retryCount + 1}/3)")
                        handler.postDelayed({
                            ifNotUserStopped(type, retryCount + 1, onAllowed)
                        }, 2000)
                    } else {
                        log.warn(TAG, "Auto-start sentinel probe failed for " +
                            "${type.displayName} ($error) after 3 retries — leaving it stopped")
                    }
                }
            }
        )
    }

    private fun startOptionalDaemonsFromPreferences() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startOptionalDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting optional daemons from preferences...")

        // Singbox starts iff the user enabled it AND hasn't stopped it via a
        // sentinel. Tunnels are independent toggles.
        if (PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) {
            vm.singboxController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Singbox already running, skipping start")
                    handler.postDelayed({ startTunnelFromPreferences(vm) }, 1000)
                } else {
                    ifNotUserStopped(DaemonType.SINGBOX_PROXY) {
                        log.info(TAG, "Starting Singbox (user enabled)...")
                        vm.startDaemon(DaemonType.SINGBOX_PROXY, userInitiated = false)
                    }
                    handler.postDelayed({ startTunnelFromPreferences(vm) }, 5000)
                }
            }
        } else {
            startTunnelFromPreferences(vm)
        }

        // Start Telegram Bot daemon if user enabled it and hasn't stopped it.
        if (PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON)) {
            syncTelegramEnabledToUnifiedConfig(true)
            handler.postDelayed({
                ifNotUserStopped(DaemonType.TELEGRAM_DAEMON) {
                    log.info(TAG, "Starting Telegram Bot daemon (user enabled)...")
                    vm.startDaemon(DaemonType.TELEGRAM_DAEMON, userInitiated = false)
                }
            }, 15000)
        }
    }

    /**
     * Mirror the app-private "Telegram enabled" preference into the world-readable
     * unified config so AccSentryDaemon (shell UID 2000) can see it on the
     * ACC-off edge — it cannot read our SharedPreferences.
     *
     * The app-side preference is the single source of truth: this function's only
     * job is to make it visible across the UID boundary. Called on every toggle
     * AND on app launch, so a mirror write that was lost (the write is a failable
     * socket IPC to the config daemon) is repaired on the next launch, and an
     * install that enabled the daemon before this key existed gets backfilled
     * without needing a re-toggle.
     *
     * Idempotent — no-ops when already in sync; updateSection merges, so it never
     * disturbs the rest of the telegram section.
     *
     * (An earlier revision took an `authoritative` flag and refused to repair a
     * disagreeing value, to protect a deliberate clear written by the web
     * preferences endpoint. That endpoint no longer touches this key — the web
     * surface is read-only for it — so the only states the guard could still
     * catch were lost writes, where suppressing the repair is exactly wrong.)
     */
    private fun syncTelegramEnabledToUnifiedConfig(enabled: Boolean) {
        try {
            if (UnifiedTelegramConfig.isDaemonEnabled() == enabled) return  // in sync
            UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_DAEMON_ENABLED, enabled)
            log.info(TAG, "Mirrored Telegram daemonEnabled=$enabled to unified config")
        } catch (e: Exception) {
            log.warn(TAG, "Failed to mirror Telegram daemonEnabled: ${e.message}")
        }
    }

    private fun startTunnelFromPreferences(vm: DaemonsViewModel) {
        val cloudflaredEnabled = PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)
        val tailscaleEnabled = PreferencesManager.isDaemonEnabled(DaemonType.TAILSCALE_TUNNEL)

        // Cloudflared and Zrok are mutually exclusive (both expose the dashboard publicly)
        if (cloudflaredEnabled) {
            vm.cloudflaredController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Cloudflared already running, skipping start")
                } else {
                    ifNotUserStopped(DaemonType.CLOUDFLARED_TUNNEL) {
                        log.info(TAG, "Starting Cloudflared (user enabled)...")
                        vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL, userInitiated = false)
                    }
                }
            }
        } else if (zrokEnabled) {
            vm.zrokController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Zrok already running, skipping start")
                } else {
                    ifNotUserStopped(DaemonType.ZROK_TUNNEL) {
                        log.info(TAG, "Starting Zrok (user enabled)...")
                        vm.startDaemon(DaemonType.ZROK_TUNNEL, userInitiated = false)
                    }
                }
            }
        } else if (!tailscaleEnabled) {
            log.info(TAG, "No tunnel enabled by user")
        }

        // Tailscale runs independently — it's private access, not a public dashboard tunnel
        if (tailscaleEnabled) {
            vm.tailscaleController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Tailscale already running, skipping start")
                } else {
                    ifNotUserStopped(DaemonType.TAILSCALE_TUNNEL) {
                        log.info(TAG, "Starting Tailscale (user enabled)...")
                        vm.startDaemon(DaemonType.TAILSCALE_TUNNEL, userInitiated = false)
                    }
                }
            }
        }
    }

    private fun startOptionalDaemonsViaAdb() {
        log.info(TAG, "Starting optional daemons via ADB...")
        try {
            // Singbox is gated by its own user toggle AND the disable sentinel
            // (a Telegram stop writes only the sentinel, never the pref).
            if (PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) {
                ifNotUserStopped(DaemonType.SINGBOX_PROXY) {
                    log.info(TAG, "Boot: Starting Singbox (user enabled)...")
                    adbLauncher.startSingbox(createLogCallback("Singbox"))
                }
            }

            val tunnelDelay = if (PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) 5_000L else 0L

            handler.postDelayed({
                // Cloudflared and Zrok are mutually exclusive
                if (PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)) {
                    ifNotUserStopped(DaemonType.CLOUDFLARED_TUNNEL) {
                        log.info(TAG, "Boot: Starting Cloudflared...")
                        adbLauncher.launchTunnel(object : AdbDaemonLauncher.TunnelCallback {
                            override fun onLog(message: String) { log.debug(TAG, "[Cloudflared] $message") }
                            override fun onTunnelUrl(url: String) { log.info(TAG, "Boot: Cloudflared URL: $url") }
                            override fun onError(error: String) { log.error(TAG, "Boot: Cloudflared error: $error") }
                        })
                    }
                } else if (PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)) {
                    ifNotUserStopped(DaemonType.ZROK_TUNNEL) {
                        log.info(TAG, "Boot: Starting Zrok...")
                        startZrokOnBoot()
                    }
                }

                // Tailscale runs independently of cloudflared/zrok
                if (PreferencesManager.isDaemonEnabled(DaemonType.TAILSCALE_TUNNEL)) {
                    ifNotUserStopped(DaemonType.TAILSCALE_TUNNEL) {
                        log.info(TAG, "Boot: Starting Tailscale...")
                        startTailscaleOnBoot()
                    }
                }
            }, tunnelDelay)

            // Start Telegram Bot daemon if user enabled it and hasn't stopped it.
            if (PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON)) {
                syncTelegramEnabledToUnifiedConfig(true)
                handler.postDelayed({
                    ifNotUserStopped(DaemonType.TELEGRAM_DAEMON) {
                        log.info(TAG, "Boot: Starting Telegram Bot daemon...")
                        adbLauncher.launchTelegramDaemon(createLogCallback("TelegramBot"))
                    }
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
        // Reuse the cached zrokLauncherForHealthCheck instead of allocating
        // a fresh ZrokLauncher + AdbShellExecutor + ScheduledExecutorService.
        // Each fresh allocation creates daemon threads that are never
        // shutdown(), so on a 24h park (with health-check relaunches) the
        // process accumulates ~hundreds of stranded executor threads.
        zrokLauncherForHealthCheck.launchZrok(object : ZrokLauncher.ZrokCallback {
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

    /**
     * Start Tailscale tunnel on boot using TailscaleLauncher directly.
     */
    private fun startTailscaleOnBoot() {
        // Reuse the shared adbLauncher's AdbShellExecutor instead of
        // allocating a fresh one. Each fresh AdbShellExecutor allocates a
        // non-daemon single-thread Executors.newSingleThreadExecutor() that
        // we never shutdown — leaks one parked thread per call.
        val tailscaleLauncher = TailscaleLauncher(context, adbLauncher.adbShellExecutor, log)

        tailscaleLauncher.launchTailscale(object : TailscaleLauncher.TailscaleCallback {
            override fun onLog(message: String) {
                log.debug(TAG, "[Tailscale Boot] $message")
            }

            override fun onTunnelUrl(url: String?) {
                log.info(TAG, "Boot: Tailscale URL: $url")
            }

            override fun onError(error: String) {
                log.error(TAG, "Boot: Tailscale error: $error")
            }
        })
    }


    /**
     * Restart tunnel if enabled. When forceRestart=true, kills existing tunnel first
     * so it can pick up new proxy settings (e.g., after singbox toggle).
     */
    private fun restartTunnelIfEnabled(vm: DaemonsViewModel, forceRestart: Boolean = false) {
        val cloudflaredEnabled = PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)
        val tailscaleEnabled = PreferencesManager.isDaemonEnabled(DaemonType.TAILSCALE_TUNNEL)

        // Cloudflared and Zrok are mutually exclusive
        if (cloudflaredEnabled) {
            vm.cloudflaredController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Cloudflared to apply new proxy settings...")
                    handler.post {
                        vm.stopDaemon(DaemonType.CLOUDFLARED_TUNNEL)
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

        // Tailscale runs independently of cloudflared/zrok
        if (tailscaleEnabled) {
            vm.tailscaleController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Tailscale to apply new proxy settings...")
                    handler.post {
                        vm.stopDaemon(DaemonType.TAILSCALE_TUNNEL)
                        handler.postDelayed({
                            log.info(TAG, "Starting Tailscale with new settings")
                            vm.startDaemon(DaemonType.TAILSCALE_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Tailscale (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.TAILSCALE_TUNNEL) }
                } else {
                    log.info(TAG, "Tailscale already running, no restart needed")
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
            // Telegram additionally needs a CROSS-UID copy of this intent.
            // PreferencesManager is app-private (UID 10xxx) and invisible to
            // AccSentryDaemon (shell UID 2000), which decides on the ACC-off
            // edge whether to bring the bot back up while parked. Mirrored to
            // its own key — NOT autoStartAccOff, which additionally means
            // "stop again on ACC-on" (parked-only mode) and would kill a
            // daemon the user asked to keep running. Writes route via daemon IPC.
            if (type == DaemonType.TELEGRAM_DAEMON) {
                syncTelegramEnabledToUnifiedConfig(enabled)
            }
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
        // Reuse the shared adbLauncher's AdbShellExecutor — see
        // startTailscaleOnBoot for why fresh allocation leaks a thread.
        val serviceLauncher = com.overdrive.app.launcher.ServiceLauncher(
            context,
            adbLauncher.adbShellExecutor,
            log
        )
        serviceLauncher.enableAccessibilityKeepAlive(object : com.overdrive.app.launcher.ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[A11y] $message") }
            override fun onLaunched() { log.info(TAG, "AccessibilityService keep-alive enabled") }
            override fun onError(error: String) { log.warn(TAG, "AccessibilityService enable failed: $error (non-fatal)") }
        })
    }

    // AtomicBoolean (not just @Volatile) because startDaemonHealthCheck does
    // a check-then-set: `if (!running) { running = true; schedule }`.
    // Plain @Volatile gives visibility but not atomicity — two callers can
    // both see false and both set true → two concurrent health-check
    // schedulers, double pkill cascades every 30s. compareAndSet collapses
    // both reads + the set into one atomic transition.
    private val healthCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Periodic health check: every 30s, verify all expected daemons are alive.
     * Core daemons are always restarted. Optional daemons only if user had them enabled.
     * Daemons intentionally stopped by the user are skipped.
     */
    private fun startDaemonHealthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) return
        log.info(TAG, "Daemon health check started (interval=${HEALTH_CHECK_INTERVAL_MS / 1000}s)")
        scheduleNextHealthCheck()
    }

    private fun scheduleNextHealthCheck() {
        // Scheduled on the dedicated health-check looper, NOT the main looper —
        // see healthCheckHandler. healthCheckRunning (AtomicBoolean) remains the
        // authoritative stop signal, so cleanup()/stopHealthChecks() still halt
        // the loop exactly as before; cadence and probe logic are unchanged.
        healthCheckHandler.postDelayed({
            if (healthCheckRunning.get()) {
                runHealthCheck()
                scheduleNextHealthCheck()
            }
        }, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun runHealthCheck() {
        // Build the candidate list first (cheap in-memory / file gates), then pay
        // for exactly ONE `ps -A` and test every candidate against that snapshot.
        //
        // Previously each candidate called isDaemonRunning() individually, and
        // because AdbShellExecutor.getOrCreateConnection() runs a
        // `dadb.shell("echo ok")` liveness probe before every command, each of
        // those cost TWO adb shell sessions plus a full /proc walk — all
        // serialized on a process-wide lock. `adbd` is a shared SYSTEM service,
        // so with 3+ daemons that load was being taken from the whole head unit
        // every 30s, forever. One snapshot cuts it ~8x.
        //
        // Semantics are unchanged: same candidates, same gates, same order, same
        // relaunch decisions (processAliveIn reproduces the old grep matching,
        // including sentry's acc_ exclusion).
        val candidates = ArrayList<DaemonType>(CORE_DAEMONS.size + OPTIONAL_DAEMONS.size)

        // Core daemons: always restart unless user explicitly stopped
        for (type in CORE_DAEMONS) {
            if (type in userStoppedDaemons) continue
            candidates.add(type)
        }

        // Optional daemons: only restart if user had them enabled in preferences.
        for (type in OPTIONAL_DAEMONS) {
            if (type in userStoppedDaemons) continue
            if (!PreferencesManager.isDaemonEnabled(type)) continue
            candidates.add(type)
        }

        if (candidates.isEmpty()) return

        // ZROK keeps its bespoke two-layer check (process-alive AND edge-stale
        // HTTP probe) — a `ps` snapshot cannot detect a stale zrok edge, so
        // routing it through the snapshot would silently lose the 8-9h 502
        // recovery. Dispatch it on its own path exactly as before.
        val zrokCandidate = candidates.remove(DaemonType.ZROK_TUNNEL)
        if (zrokCandidate) checkAndRelaunchDaemon(DaemonType.ZROK_TUNNEL)

        if (candidates.isEmpty()) return

        adbLauncher.snapshotProcessTable { snapshot ->
            if (snapshot == null) {
                // Probe failed (adb transport hiccup). Treat as UNKNOWN, not
                // dead: the old per-daemon path also reported false-on-error but
                // then funnelled through relaunchDaemon's sentinel probe, which
                // biases to relaunch. Skipping this tick entirely is the
                // conservative choice — the next tick is only 30s away, and the
                // shell-side watchdogs cover a genuinely dead daemon meanwhile.
                log.warn(TAG, "Health check: ps snapshot unavailable — skipping this tick")
                return@snapshotProcessTable
            }
            for (type in candidates) {
                if (!adbLauncher.processAliveIn(snapshot, type.processName)) {
                    log.warn(TAG, "Health check: ${type.displayName} is DEAD — relaunching...")
                    relaunchDaemon(type)
                }
            }
        }
    }

    private fun checkAndRelaunchDaemon(type: DaemonType) {
        // Zrok needs a more specific liveness probe than `ps -A | grep zrok`.
        // The shell watchdog (start_zrok.sh) ALSO matches that pattern, so a
        // stuck or sentinel-disabled watchdog with no share child would
        // silently pass the generic check and the user would see 502s
        // forever. Use ZrokLauncher.isTunnelRunning() — it greps for the
        // actual `zrok share` arg vector, not just any process name
        // containing "zrok".
        if (type == DaemonType.ZROK_TUNNEL) {
            // Two-layer liveness for zrok: (1) process-alive grep on
            // `'zrok share'` argv (catches dead-process), (2) HTTP probe
            // against the public URL (catches edge-session-stale = the
            // original 8–9hr 502 bug where the share process is alive
            // but zrok's edge has dropped the underlay session and
            // returns 502 to external clients).
            //
            // checkTunnelHealth combines both with a 2-strike stickiness
            // counter so a single transient blip doesn't trigger a
            // needless restart. EDGE_STALE on confirmed-stale → relaunch
            // the same way as a dead process.
            zrokLauncherForHealthCheck.checkTunnelHealth { health ->
                when (health) {
                    ZrokLauncher.TunnelHealth.PROCESS_DEAD -> {
                        log.warn(TAG, "Health check: Zrok process is DEAD — relaunching...")
                        relaunchDaemon(type)
                    }
                    ZrokLauncher.TunnelHealth.EDGE_STALE -> {
                        // Edge-stale recovery is a stop+start: the existing
                        // share process is alive, so the normal launchZrok
                        // fast path would short-circuit ("already running")
                        // and do nothing. We need to actively kill the
                        // alive-but-stale process first so the relaunch
                        // gets a fresh underlay session.
                        //
                        // Sequence the relaunch inside stopTunnel's
                        // callbacks rather than via a fixed 2s postDelayed:
                        // stopTunnel ps-kills the share + watchdog
                        // asynchronously, and a
                        // postDelayed only races them. With the callback
                        // form, the relaunch runs strictly after the kill
                        // script's exit.
                        log.warn(TAG, "Health check: Zrok edge session STALE — stopping alive-but-stale process, then relaunching")
                        zrokLauncherForHealthCheck.stopTunnel(
                            object : ZrokLauncher.ZrokCallback {
                                override fun onLog(message: String) {}
                                override fun onTunnelUrl(url: String) {
                                    handler.post {
                                        log.info(TAG, "Edge-stale recovery: relaunching Zrok after stop completed")
                                        relaunchDaemon(type)
                                    }
                                }
                                override fun onError(error: String) {
                                    // The kill script ran; re-check durable
                                    // stop intent before attempting recovery.
                                    log.warn(TAG, "stopTunnel during edge-stale recovery returned error: $error (continuing relaunch)")
                                    handler.post { relaunchDaemon(type) }
                                }
                            },
                            writeSentinel = false
                        )
                    }
                    ZrokLauncher.TunnelHealth.HEALTHY -> {
                        // No-op
                    }
                }
            }
            return
        }
        adbLauncher.isDaemonRunning(type.processName) { isRunning ->
            if (!isRunning) {
                log.warn(TAG, "Health check: ${type.displayName} is DEAD — relaunching...")
                relaunchDaemon(type)
            }
        }
    }

    private fun relaunchDaemon(type: DaemonType) {
        // FINAL cross-UID gate before any actual relaunch. The death we
        // detected might be a crash (no sentinel → revive, the whole point of
        // the health-check) OR a user-initiated stop from the Daemons UI or
        // Telegram (sentinel present → leave it down). This probe is the only
        // check that works regardless of which UID wrote the stop: the
        // sentinel is `chmod 666` in /data/local/tmp, readable by both the app
        // and the UID-2000 daemon family. The in-memory userStoppedDaemons set
        // is wiped on app relaunch, and the legacy Telegram .properties file is
        // unreadable across the UID boundary — so without this probe a
        // Telegram or post-restart stop gets resurrected within 30s.
        //
        // Every relaunch path (generic dead-process, zrok PROCESS_DEAD, zrok
        // EDGE_STALE, boot-path ADB fallback) funnels through here, so gating
        // once at this chokepoint covers them all.
        adbLauncher.executeShellCommand(
            "test -f ${type.sentinelPath} -o -f ${com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH} && echo STOPPED || echo OK",
            object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    if (message.trim().contains("STOPPED")) {
                        // STOPPED = user disable sentinel present, OR the "Vehicle ON only"
                        // parked-shutdown marker present. In onOnly the whole stack is
                        // terminated on park and MUST NOT be revived by the 30s health-check
                        // until the ACC-on edge clears the marker.
                        log.info(TAG, "Health check: ${type.displayName} is stopped " +
                            "(disable sentinel or parked-shutdown marker present) — NOT relaunching")
                    } else {
                        doRelaunchDaemon(type)
                    }
                }
                override fun onLaunched() {}
                override fun onError(error: String) {
                    log.warn(TAG, "Health check: sentinel probe failed for " +
                        "${type.displayName} ($error) — leaving it stopped this tick")
                }
            }
        )
    }

    private fun doRelaunchDaemon(type: DaemonType) {
        val vm = daemonsViewModel
        if (vm != null) {
            // userInitiated=false: a health-check revival must NOT clear the
            // disable sentinel, flip the enabled-pref, or run tunnel mutual-
            // exclusion. Those are destructive to durable stop intent — if the
            // sentinel probe upstream false-negatived a real user stop (e.g.
            // transient ADB error → defensive relaunch), the old unconditional
            // vm.startDaemon(type) would wipe the sentinel AND flip the pref
            // ON, making the false negative permanent for OPTIONAL daemons.
            // The non-user path relaunches the process only and re-gates on the
            // in-memory user-stopped set as a same-process race backstop.
            handler.post { vm.startDaemon(type, userInitiated = false) }
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
                DaemonType.ZROK_TUNNEL -> {
                    // Boot-path zrok recovery (no ViewModel). Without this
                    // branch, the health-check would log "no ADB fallback"
                    // and never restart zrok after a crash on the boot path.
                    log.info(TAG, "HealthCheck: relaunching Zrok tunnel via boot-path fallback")
                    zrokLauncherForHealthCheck.launchZrok(object : ZrokLauncher.ZrokCallback {
                        override fun onLog(message: String) { log.debug(TAG, "[Zrok HealthCheck] $message") }
                        override fun onTunnelUrl(url: String) { log.info(TAG, "HealthCheck: Zrok URL: $url") }
                        override fun onError(error: String) { log.error(TAG, "HealthCheck: Zrok restart failed: $error") }
                    })
                }
                else -> {
                    log.warn(TAG, "Health check: no ADB fallback for ${type.displayName}")
                }
            }
        }
    }

    /**
     * Fail-safe: force-clear the parked-shutdown marker if it is older than
     * [ParkedShutdown.MAX_AGE_MS]. The marker embeds its park epoch-millis; a marker that
     * outlives the max age can never be allowed to permanently suppress an active session
     * (e.g. an ACC-on edge that was somehow never delivered). Invoked from
     * [clearStaleSentinels] so it runs on every boot/launch. Shell-side age test keeps it
     * cross-UID and avoids reading the file into the app process.
     */
    fun clearParkedMarkerIfStale() {
        try {
            val marker = com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH
            val maxAgeSec = com.overdrive.app.ui.model.ParkedShutdown.MAX_AGE_MS / 1000
            // now - parkEpochSec > maxAgeSec  → stale → rm. Marker holds epoch MILLIS;
            // divide to seconds. Guard against a missing/garbage marker (non-numeric → skip).
            val script =
                "M=$marker; " +
                "if [ -f \"\$M\" ]; then " +
                "  TS=\$(cat \"\$M\" 2>/dev/null); " +
                "  NOW=\$(date +%s); " +
                "  case \"\$TS\" in *[!0-9]*|'') echo 'keep (unparseable)';; " +
                "  *) PARK=\$((TS/1000)); AGE=\$((NOW-PARK)); " +
                "     if [ \$AGE -gt $maxAgeSec ]; then rm -f \"\$M\" 2>/dev/null; echo 'cleared stale'; else echo keep; fi;; " +
                "  esac; " +
                "else echo 'no marker'; fi"
            adbLauncher.executeShellCommand(script, object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    if (message.contains("cleared stale")) {
                        log.warn(TAG, "Parked-shutdown marker exceeded max age — force-cleared (fail-safe)")
                    }
                }
                override fun onLaunched() {}
                override fun onError(error: String) { log.warn(TAG, "clearParkedMarkerIfStale onError: $error") }
            })
        } catch (e: Exception) {
            log.warn(TAG, "clearParkedMarkerIfStale threw: ${e.message}")
        }
    }

    fun clearStaleSentinels() {
        // Per-daemon sentinels are durable manual-stop intent. Machine-written
        // markers are classified and removed by ifNotUserStopped immediately
        // before an automatic start; never sweep user intent at process launch.
        clearParkedMarkerIfStale()
    }

    /**
     * Stops the health-check loop and QUITS its dedicated looper. Idempotent.
     *
     * Split out of [cleanup] so a caller that only needs to stop this manager's own
     * thread (e.g. MainActivity.onDestroy) does not also trigger
     * `adbLauncher.releasePerInstanceResources()` + the zrok launcher shutdown that
     * [cleanup] performs — this Activity's adbLauncher is shared with other
     * components. Without SOME caller doing this, every manager instance strands a
     * live OS thread for the process lifetime: instances are created per
     * MainActivity.onCreate as well as for the static bootManager, and Activity
     * recreates are routine (language picker, theme switch, any config change
     * outside the manifest's configChanges set) — each leaked thread also pins the
     * destroyed Activity via the Context it was built with.
     *
     * `healthCheckRunning=false` is set first, so quitting cannot cut short a tick
     * that would otherwise have run. Safe to call twice (quitSafely on an already-
     * quit looper is a no-op).
     */
    fun stopHealthCheckThread() {
        healthCheckRunning.set(false)
        healthCheckHandler.removeCallbacksAndMessages(null)
        try {
            healthCheckThread.quitSafely()
        } catch (e: Exception) {
            log.warn(TAG, "healthCheckThread quit failed: ${e.message}")
        }
    }

    fun cleanup() {
        healthCheckRunning.set(false)
        handler.removeCallbacksAndMessages(null)
        // The health-check loop now lives on its own looper, so clearing the main
        // handler above no longer reaches it — drop its pending tick too (the
        // healthCheckRunning flag already stops the re-post, this is the same
        // belt-and-braces the main-handler clear provided before).
        stopHealthCheckThread()
        // releasePerInstanceResources — NOT closePersistentConnection.
        // closePersistentConnection nulls the process-wide shared Dadb in
        // AdbShellExecutor's companion, which would force the new
        // MainActivity-scoped manager (which is reading the same shared
        // Dadb) to reconnect + re-auth on first use. Worse, any in-flight
        // shell command on this manager's still-pending postDelayed
        // tasks would observe a closed transport and surface as spurious
        // onError. We only need to release THIS manager's per-instance
        // executor + tunnel-poll scheduler — the shared Dadb stays alive
        // for the new owner.
        adbLauncher.releasePerInstanceResources()
        // Shutdown the cached ZrokLauncher's reconcile scheduler if it was
        // ever instantiated. Without this, every Activity teardown leaves
        // a stranded daemon thread for the lifetime of the process.
        // The flag avoids forcing allocation just to check.
        if (zrokLauncherInitialized) {
            try {
                zrokLauncherForHealthCheck.shutdown()
                // The AdbShellExecutor owned by this cached launcher needs
                // its own executor thread shutdown — without it the
                // single-thread executor parks indefinitely.
                zrokAdbShellExecutor?.shutdown()
            } catch (e: Exception) {
                log.warn(TAG, "ZrokLauncher shutdown failed: ${e.message}")
            }
        }
    }
}
