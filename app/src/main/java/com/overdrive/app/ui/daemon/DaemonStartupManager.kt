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

        // Floor between reconnect-triggered health checks. STATIC on purpose:
        // the boot-scoped manager and the Activity-scoped manager can both be
        // armed at once, and one adbd restart must not fan out into one
        // immediate check per manager instance.
        private const val RECONNECT_CHECK_MIN_INTERVAL_MS = 10_000L
        @Volatile
        private var lastReconnectHealthCheckAtMs = 0L

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

        /**
         * True once this process has observed the "Vehicle ON only" parked-shutdown
         * marker (any gate that declined to start because of it). It lets
         * [startOnBoot] tell a genuine park-end apart from an ordinary duplicate
         * call: when the marker is gone again after having been seen, the parked
         * window ended — the ACC judge (acc_sentry_daemon) or a recovery trigger
         * erased it — and the stack must be rebuilt even though `bootStarted` is
         * still true from the pre-park session (the app process is kept resident by
         * the accessibility keep-alive across a park).
         */
        @JvmStatic
        @Volatile
        var parkObserved = false
            private set

        @JvmStatic
        fun noteParkObserved() {
            parkObserved = true
        }

        /**
         * The MainActivity-scoped manager, from initializeOnAppLaunch until its health
         * check thread is stopped (Activity destroy). While alive, its pending +45/+60 s
         * start timers or its 30 s health check (which keeps ticking through a park,
         * gated by the marker) relaunch every dead daemon once the marker disappears —
         * so a park-end must NOT also create a boot-scoped manager: two managers mean
         * two health checks and double pkill cascades against the daemon family.
         */
        @Volatile
        private var activityManager: DaemonStartupManager? = null

        /** Last park-END breadcrumb epoch this process acted on (see [ParkedShutdown.endedPath]). */
        @Volatile
        private var consumedParkEndStamp: Long? = null

        private fun readParkEndedStamp(): Long? {
            return try {
                val f = java.io.File(com.overdrive.app.ui.model.ParkedShutdown.endedPath())
                if (!f.isFile || f.length() > 32) return null
                f.readText().trim().toLongOrNull()
            } catch (e: Exception) {
                null
            }
        }

        @Synchronized
        fun startOnBoot(context: Context) {
            // ABSOLUTE parked gate — a plain file check, independent of any config
            // read (those fail open, and a callback scheduled while ON must not be
            // able to execute after ACC OFF). Every automatic startup path funnels
            // here or through ifNotUserStopped/relaunchDaemon, which carry the same
            // gate. The marker is only ever erased by the ACC judge on a real
            // ACC-on, by a recovery trigger after a VERIFIED erase, or by an explicit
            // user start. In onAndOff the marker never exists, so this is inert.
            //
            // The one thing that MAY run while parked is the judge itself: it is the
            // only process that can end the park, so a start request that finds the
            // marker makes sure acc_sentry_daemon is alive and does nothing else.
            if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.markerPath()).exists()) {
                parkObserved = true
                android.util.Log.i(TAG, "startOnBoot: parked-shutdown marker present — not starting (stay asleep)")
                ensureAccSentryJudgeRunning(context)
                return
            }
            val endedStamp = readParkEndedStamp()
            if (bootStarted) {
                val breadcrumbIsNew = endedStamp != null && endedStamp != consumedParkEndStamp
                if (!parkObserved && !breadcrumbIsNew) return
                // A park ended (this process saw the marker and it is now gone, or the
                // ACC judge left a fresh park-end breadcrumb): the daemons and their
                // watchdogs were killed by the reaper, so the stack must come back even
                // though the process-lifetime guard is still set from the pre-park session.
                parkObserved = false
                if (endedStamp != null) consumedParkEndStamp = endedStamp
                if (activityManager != null) {
                    // Alive = its +45/+60 s start timers are still pending, or its 30 s
                    // health check is ticking; either relaunches the stack now that the
                    // marker is gone. A second manager would only double the cascades.
                    android.util.Log.i(TAG, "startOnBoot: parked window ended — MainActivity-scoped manager "
                        + "is alive and relaunches the stack itself (not creating a second manager)")
                    return
                }
                android.util.Log.i(TAG, "startOnBoot: parked window ended (marker gone after a park) — rebuilding stack")
                try { bootManager?.cleanup() } catch (e: Exception) {
                    android.util.Log.w(TAG, "startOnBoot: previous boot manager cleanup failed: ${e.message}")
                }
                bootManager = null
                bootStarted = false
            }
            parkObserved = false
            if (endedStamp != null) consumedParkEndStamp = endedStamp
            bootStarted = true
            // NOTE: recoveryInProgress is deliberately NOT reset here. It is owned by
            // recoverFromPark, which only lets the caller relaunch once the marker
            // erase has been VERIFIED — at which point the exists() check the gates
            // use is itself already false.
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
         * True from the instant an ACC-on recovery begins until the marker erase has been
         * verified (or given up). DaemonKeepaliveService.onStartCommand consults this to
         * avoid self-stopping on the recovery edge: the erase runs over an async shell, so
         * a synchronous File(marker).exists() check in onStartCommand — which can run on
         * the main thread moments after recoverFromPark — may still see the marker present
         * and wrongly self-stop. This in-memory flag flips synchronously so the service
         * knows "recovery in progress, do not self-stop even if the marker still lingers".
         */
        @JvmStatic
        @Volatile
        var recoveryInProgress = false
            private set

        // The erase runs over the ADB shell lane. On a head-unit boot adbd and its
        // auth handshake can take well over a minute to come up, and NOTHING starts
        // until the erase is verified — so the horizon must comfortably cover a boot
        // (20 × 5 s = 100 s), not just an adbd blip.
        private const val MARKER_CLEAR_ATTEMPTS = 20
        private const val MARKER_CLEAR_RETRY_MS = 5_000L

        /**
         * ACC-on / boot RECOVERY from an onOnly park. Ordered, and every step is
         * load-bearing:
         *  1. Erase the parked-shutdown marker and VERIFY it is gone (with a short retry
         *     while ADB reconnects). The previous fire-and-forget `rm` masked failures
         *     behind an unconditional `echo`, so the stack was relaunched into a still-
         *     present marker: every redeployed watchdog gate-exited on it immediately and
         *     the health check then honoured the stale marker forever.
         *  2. Only once the erase is confirmed, RESET the `bootStarted` guard (the app
         *     process is kept resident across a park, so the process-lifetime guard is
         *     still true from the pre-park session and startOnBoot would otherwise no-op).
         *  3. Then hand control back to the caller via [onRecovered], which relaunches.
         * If the marker cannot be erased, NOTHING is started: the stack stays down and the
         * next ACC-on / boot edge retries. Starting into a present marker is never useful.
         */
        fun recoverFromPark(context: Context, onRecovered: () -> Unit) {
            if (recoveryInProgress) {
                // A recovery is already erasing the marker; its callback will launch.
                android.util.Log.i(TAG, "recoverFromPark: recovery already in progress — not starting a second erase")
                return
            }
            recoveryInProgress = true
            val appCtx = context.applicationContext
            clearParkedMarkerVerified(appCtx, 1) { cleared ->
                // The shell callback arrives on the ADB executor thread. Every other
                // caller of startOnBoot (BootReceiver, the keepalive service, the
                // accessibility service) runs on the main looper, and startOnBoot's
                // check-then-set on the boot guard is only safe if all callers share
                // one thread — so hop before touching the guards or launching.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (cleared) {
                        // Do NOT tear down a live MainActivity-scoped manager here:
                        // startOnBoot (called by onRecovered) defers to it when it is
                        // alive, and only creates a boot manager otherwise.
                        if (activityManager == null) {
                            try { bootManager?.cleanup() } catch (e: Exception) {
                                android.util.Log.w(TAG, "recoverFromPark: boot manager cleanup failed: ${e.message}")
                            }
                            bootManager = null
                            bootStarted = false
                        } else {
                            // Keep the guard: the live manager restores the stack, and
                            // startOnBoot must not create a second manager.
                            bootStarted = true
                        }
                        parkObserved = false
                        consumedParkEndStamp = readParkEndedStamp()
                        recoveryInProgress = false
                        android.util.Log.i(TAG, "recoverFromPark: marker erase verified — relaunching stack")
                        onRecovered()
                    } else {
                        recoveryInProgress = false
                        android.util.Log.w(TAG, "recoverFromPark: parked-shutdown marker could NOT be erased after "
                            + "$MARKER_CLEAR_ATTEMPTS attempts — staying parked; retrying when ADB reconnects, "
                            + "and on the next ACC-on / boot edge")
                        // Two independent legs so a parked reboot with a slow adbd is not a
                        // cliff: (1) re-run this recovery the moment the ADB transport comes
                        // back; (2) make sure the ACC judge is up — it erases the marker
                        // itself on a definitive ON and kicks the app.
                        armRecoveryRetryOnAdbReconnect(appCtx, onRecovered)
                        ensureAccSentryJudgeRunning(appCtx)
                    }
                }
            }
        }

        @Volatile
        private var pendingRecoveryRearm: AdbShellExecutor.ConnectionReestablishedListener? = null

        /**
         * One-shot: when the shared ADB transport is re-established, retry the parked
         * recovery. Uses the same listener hook the health check uses for its
         * reconnect-triggered tick. Replaces any earlier pending re-arm.
         */
        private fun armRecoveryRetryOnAdbReconnect(appCtx: Context, onRecovered: () -> Unit) {
            pendingRecoveryRearm?.let { AdbShellExecutor.removeConnectionReestablishedListener(it) }
            val listener = AdbShellExecutor.ConnectionReestablishedListener { generation ->
                pendingRecoveryRearm?.let { AdbShellExecutor.removeConnectionReestablishedListener(it) }
                pendingRecoveryRearm = null
                android.util.Log.i(TAG, "ADB reconnected (gen=$generation) — retrying parked-marker recovery")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    recoverFromPark(appCtx, onRecovered)
                }
            }
            pendingRecoveryRearm = listener
            AdbShellExecutor.addConnectionReestablishedListener(listener)
        }

        /**
         * Erase the "Vehicle ON only" parked-shutdown marker and report whether it is
         * actually gone. `rm -f` exits 0 even on a suppressed permission error, so the
         * shell re-checks with `[ -f ]` and exits non-zero when the marker survived; the
         * app-side exists() double-check covers a transport failure after a successful
         * unlink. Bounded retry ([MARKER_CLEAR_ATTEMPTS] × [MARKER_CLEAR_RETRY_MS]) so a
         * recovery that lands while adbd is still reconnecting is not lost.
         */
        private fun clearParkedMarkerVerified(
            context: Context,
            attempt: Int,
            onResult: (Boolean) -> Unit
        ) {
            val marker = com.overdrive.app.ui.model.ParkedShutdown.markerPath()
            fun retryOrFail(reason: String) {
                if (!java.io.File(marker).exists()) {
                    onResult(true)
                    return
                }
                if (attempt < MARKER_CLEAR_ATTEMPTS) {
                    android.util.Log.w(TAG, "Parked marker erase attempt $attempt failed ($reason) — retrying")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { clearParkedMarkerVerified(context, attempt + 1, onResult) },
                        MARKER_CLEAR_RETRY_MS
                    )
                } else {
                    onResult(false)
                }
            }
            try {
                val launcher = AdbDaemonLauncher(context)
                launcher.executeShellCommand(
                    "rm -f $marker 2>/dev/null; " +
                        "if [ -f $marker ]; then echo STILL_PRESENT; exit 1; fi; echo CLEARED",
                    object : AdbDaemonLauncher.LaunchCallback {
                        override fun onLog(message: String) {}
                        override fun onLaunched() { onResult(true) }
                        override fun onError(error: String) { retryOrFail(error) }
                    }
                )
            } catch (e: Exception) {
                retryOrFail(e.message ?: e.javaClass.simpleName)
            }
        }

        /**
         * Make sure the parked ACC judge is alive. acc_sentry_daemon is the ONLY process
         * with a hardware-backed view of ACC while parked in onOnly: it stays resident
         * through the park (the reaper deliberately spares it), relights the panel, and
         * erases the parked marker on a real ACC-on. If it died and its watchdog with it,
         * nothing else can end the park except an unambiguous BYD broadcast or a reboot —
         * so the app relaunches it, and only it, when a parked-state hint arrives. Honours
         * the user's manual-stop sentinel.
         */
        fun ensureAccSentryJudgeRunning(context: Context) {
            try {
                if (java.io.File(DaemonType.ACC_SENTRY_DAEMON.sentinelPath).exists()) {
                    android.util.Log.i(TAG, "acc_sentry_daemon manually stopped — not relaunching the parked ACC judge")
                    return
                }
                val launcher = AdbDaemonLauncher(context.applicationContext)
                launcher.isDaemonRunning(DaemonType.ACC_SENTRY_DAEMON.processName) { running ->
                    if (running) return@isDaemonRunning
                    android.util.Log.i(TAG, "acc_sentry_daemon (parked ACC judge) is not running — relaunching it alone")
                    launcher.launchAccSentryDaemon(
                        onSuccess = { android.util.Log.i(TAG, "Parked ACC judge relaunched") },
                        onError = { error -> android.util.Log.w(TAG, "Parked ACC judge relaunch failed: $error") }
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ensureAccSentryJudgeRunning failed: ${e.message}")
            }
        }
    }

    fun initializeOnAppLaunch() {
        log.info(TAG, "=== Initializing daemon startup on app launch ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")

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

        // This instance is now the live MainActivity-scoped manager (cleared again in
        // stopHealthCheckThread). startOnBoot consults it on a park-end so it never
        // creates a boot-scoped manager beside a live one.
        activityManager = this

        // Enable AccessibilityService keep-alive immediately (doesn't need delay)
        enableAccessibilityKeepAlive()

        // "Vehicle ON only": an app launch is NOT evidence that the vehicle is on. The
        // head unit is lit in accessory mode too, and a resident process re-creates
        // MainActivity on any config change. The parked-shutdown marker is therefore
        // left alone here; the delayed starts below all run through ifNotUserStopped,
        // whose parked gate keeps them down until the ACC judge or a recovery trigger
        // erases the marker. An explicit Start in the Daemons UI remains the user's
        // override (DaemonsViewModel.clearStartBlockers).

        // Keep manual-stop sentinels. Automatic startup classifies and clears
        // only machine-written markers immediately before starting a daemon.
        clearStaleSentinels()

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

        // Start Camera Daemon FIRST. Probe the actual --nice-name (`byd_cam_daemon`)
        // not the legacy "camera_daemon" string — `ps -A` on stock Android shows
        // the nice-name, and "camera_daemon" is not a substring of "byd_cam_daemon".
        // The previous literal always reported false → one redundant launch+
        // cleanup ADB round-trip on every boot (the inner `launchDaemon` does
        // its own correct probe at DaemonLauncher.kt:328 and short-circuits, so
        // this was cosmetic, but kept boot ~1-2 s slower than necessary).
        ifNotUserStopped(DaemonType.CAMERA_DAEMON) {
            adbLauncher.isDaemonRunning(DaemonType.CAMERA_DAEMON.processName) { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting Camera Daemon...")
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                    adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("CameraDaemon"))
                } else {
                    log.info(TAG, "Boot: Camera Daemon already running")
                }
            }
        }
        
        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            ifNotUserStopped(DaemonType.SENTRY_DAEMON) {
                adbLauncher.isSentryDaemonRunning { running ->
                    if (!running) {
                        log.info(TAG, "Boot: Starting Sentry Daemon...")
                        adbLauncher.launchSentryDaemon(createLogCallback("SentryDaemon"))
                    } else {
                        log.info(TAG, "Boot: Sentry Daemon already running")
                    }
                }
            }
        }, 5000)
        
        // Start ACC Sentry Daemon last
        handler.postDelayed({
            ifNotUserStopped(DaemonType.ACC_SENTRY_DAEMON) {
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
            }
        }, 10000)
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
    private fun ifNotUserStopped(type: DaemonType, onAllowed: () -> Unit) {
        // ABSOLUTE "Vehicle ON only" parked gate, for BOTH managers, in the SAME
        // ordered shell command as the per-daemon sentinel decision. While the
        // parked-shutdown marker exists the stack was intentionally terminated for
        // the parked window and no automatic start may run — a MainActivity
        // start, a +45 s boot timer or a health-check revival is not evidence that
        // the vehicle is on. The gate only READS the marker; it is erased by the
        // ACC judge (acc_sentry_daemon) on a real ACC-on, by recoverFromPark after
        // a verified erase, or by an explicit user Start. A shell-side check
        // (rather than an app-side exists()) keeps the decision atomic with the
        // sentinel classification that follows it.
        //
        // acc_sentry_daemon is the ONE exception: it is the parked ACC judge — the
        // process that ends the park — so it may (and must) start while the marker
        // exists. Its own start-up handles a parked car (no wakelock, sentry re-entry).
        val parkedGate = if (type == DaemonType.ACC_SENTRY_DAEMON) {
            ""
        } else {
            "P='${com.overdrive.app.ui.model.ParkedShutdown.markerPath()}'; " +
                "if [ -f \"\$P\" ]; then echo PARKED_BLOCKED; exit 0; fi; "
        }
        val probe =
            parkedGate +
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
                        out.contains("PARKED_BLOCKED") -> {
                            noteParkObserved()
                            log.info(TAG, "Auto-start: Vehicle-ON-only parked marker present — " +
                                "not starting ${type.displayName} (stay asleep)")
                        }
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
                    log.warn(TAG, "Auto-start sentinel probe failed for " +
                        "${type.displayName} ($error) — leaving it stopped")
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
        // Arm the ADB reconnect hook together with the periodic loop (and
        // disarm together in stopHealthCheckThread): a reconnect-triggered
        // check must obey exactly the same lifecycle as the 30s tick.
        AdbShellExecutor.addConnectionReestablishedListener(adbReconnectListener)
        scheduleNextHealthCheck()
    }

    /**
     * ADB-reconnect hook. When the process-wide shared ADB connection is
     * RE-established (generation > 1), adbd died in between — and on this
     * platform init SIGKILLs adbd's entire cgroup when it reaps the service,
     * which includes every daemon this manager supervises (all are spawned
     * via adb shell and inherit adbd's cgroup; nohup/setsid don't change
     * cgroup membership). Waiting for the next 30s tick is wrong twice over:
     * the tick can itself be queued behind commands that died with the old
     * connection, and the field incident's nine-minute outage was exactly
     * this gap. Run ONE immediate health check instead — it reuses every
     * existing gate (sentinel probe, ParkedShutdown marker, userStoppedDaemons,
     * per-daemon launch guards, zrok's bespoke path), so a spurious fire is
     * harmless, and the UI is refreshed by the same vm.startDaemon path the
     * periodic tick uses.
     */
    private val adbReconnectListener =
        AdbShellExecutor.ConnectionReestablishedListener { generation ->
            onAdbConnectionReestablished(generation)
        }

    private fun onAdbConnectionReestablished(generation: Long) {
        if (!healthCheckRunning.get()) return
        val now = System.currentTimeMillis()
        if (now - lastReconnectHealthCheckAtMs < RECONNECT_CHECK_MIN_INTERVAL_MS) {
            log.info(TAG, "ADB reconnect (gen=$generation): immediate check ran recently — skipping")
            return
        }
        lastReconnectHealthCheckAtMs = now
        log.warn(TAG, "ADB connection re-established (gen=$generation) — adbd likely restarted " +
            "and its cgroup (all shell-spawned daemons) was killed with it. " +
            "Running immediate health check.")
        // Forensics for the NEXT incident: the adbd abort tombstone does not
        // identify the offending peer, so capture who is connected to :5555
        // right now (fresh connection ⇒ this is cheap and safe). Distinguishes
        // "only our local dadb" from "an external ADB client was attached".
        // 0x15B3 == 5555 for the /proc/net/tcp fallback on ps-less toyboxes.
        adbLauncher.executeShellCommand(
            "netstat -tn 2>/dev/null | grep ':5555' | head -20; " +
                "cat /proc/net/tcp 2>/dev/null | grep -i ':15B3' | head -20; echo .",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    log.info(TAG, "adbd peer snapshot after reconnect (gen=$generation): " +
                        message.trim())
                }
                override fun onLaunched() {}
                override fun onError(error: String) {
                    log.debug(TAG, "adbd peer snapshot failed: $error")
                }
            }
        )
        healthCheckHandler.post {
            if (healthCheckRunning.get()) runHealthCheck()
        }
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
        // Previously each candidate called isDaemonRunning() individually —
        // one adb shell session plus a full /proc walk per daemon, serialized
        // on a process-wide lock (historically doubled by a per-command
        // `echo ok` liveness probe, since removed from AdbShellExecutor
        // entirely after the vendor-adbd abort incident). `adbd` is a shared
        // SYSTEM service, so with 3+ daemons that load was being taken from
        // the whole head unit every 30s, forever. One snapshot keeps the tick
        // at a single session + a single /proc walk.
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
        // The shell watchdog exclusively owns zrok edge-health recovery. During
        // its retry/cooldown window the share child is intentionally absent, so
        // a live start_zrok.sh means "recovering", not "dead".
        if (type == DaemonType.ZROK_TUNNEL) {
            zrokLauncherForHealthCheck.isTunnelManaged { managed ->
                if (!managed) {
                    log.warn(TAG, "Health check: Zrok share and watchdog are DEAD — relaunching...")
                    relaunchDaemon(type)
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
        // Every relaunch path funnels through here, so gating once at this
        // chokepoint covers them all. The parked-shutdown marker is READ here,
        // never erased: a health-check tick is not evidence that the vehicle is
        // on, and a dead daemon while parked is the intended state — except for
        // acc_sentry_daemon, the parked ACC judge, which this health check is the
        // one periodic app-side mechanism able to revive mid-park.
        val parkedMarker = com.overdrive.app.ui.model.ParkedShutdown.markerPath()
        val stoppedProbe = if (type == DaemonType.ACC_SENTRY_DAEMON) {
            "test -f ${type.sentinelPath} && echo STOPPED || echo OK"
        } else {
            "test -f ${type.sentinelPath} -o -f $parkedMarker && echo STOPPED || echo OK"
        }
        adbLauncher.executeShellCommand(
            stoppedProbe,
            object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    if (message.trim().contains("STOPPED")) {
                        // STOPPED = user disable sentinel present, OR the "Vehicle ON only"
                        // parked-shutdown marker present. In onOnly the whole stack is
                        // terminated on park and MUST NOT be revived by the 30s health-check
                        // until the ACC-on edge clears the marker.
                        if (java.io.File(parkedMarker).exists()) noteParkObserved()
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

    fun clearStaleSentinels() {
        // Per-daemon sentinels are durable manual-stop intent. Machine-written
        // markers are classified and removed by ifNotUserStopped immediately
        // before an automatic start; never sweep user intent at process launch.
        //
        // The "Vehicle ON only" parked-shutdown marker is deliberately NOT swept
        // here either, not even by age. Elapsed time is not evidence that the
        // vehicle is on; the previous 24 h fail-safe was one of the paths that
        // restarted the whole stack on a still-parked car. The marker ends only
        // when the ACC judge (acc_sentry_daemon) sees a real ACC-on, when a
        // recovery trigger completes a verified erase, or when the user presses
        // Start explicitly.
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
        if (activityManager === this) activityManager = null
        AdbShellExecutor.removeConnectionReestablishedListener(adbReconnectListener)
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
