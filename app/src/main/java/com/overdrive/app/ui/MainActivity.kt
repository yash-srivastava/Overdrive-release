package com.overdrive.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupWithNavController
import com.overdrive.app.logging.LogLevel
import com.overdrive.app.logging.LogManager
// import com.overdrive.app.shell.PrivilegedShellSetup
import com.overdrive.app.storage.StorageSetup
import com.overdrive.app.ui.daemon.DaemonStartupManager
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.NavigationRailSwipePolicy
import com.overdrive.app.ui.model.TunnelDisplayPolicy
import com.overdrive.app.ui.navigation.NavigationRailCatalog
import com.overdrive.app.ui.privacy.ScreenshotPrivacyController
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.ui.widget.SwipeExpandableRailScrollView
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.LogsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.google.android.material.appbar.MaterialToolbar
import android.widget.ImageView
import android.widget.LinearLayout
import com.overdrive.app.R
import com.overdrive.app.util.BydDataCacheWhitelist
import com.overdrive.app.byd.TrafficMonitorPolicy

/**
 * Main activity hosting the M3 navigation-rail shell.
 *
 * Top-level destinations are wired via the rail in setupNavigation(). The
 * old drawer-action handlers (check-update, reset, battery-health,
 * camera-probe, traffic-monitor) are kept private but exposed via
 * `invoke*Action` thin wrappers that the new SettingsFragment / Diagnostics
 * fragment call.
 */
open class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val mainViewModel: MainViewModel by viewModels()
    private val daemonsViewModel: DaemonsViewModel by viewModels()
    private val logsViewModel: LogsViewModel by viewModels()
    private var appUpdater: com.overdrive.app.updater.AppUpdater? = null

    // Daemon startup manager
    private lateinit var daemonStartupManager: DaemonStartupManager
    private var onboardingHost: com.overdrive.app.onboarding.OnboardingHost? = null
    // Bounded poll while waiting for an onboarding navigation to commit (~2s total).
    private val NAV_POLL_INTERVAL_MS = 100L
    private val NAV_POLL_MAX_ATTEMPTS = 20
    private val RAIL_COMPACT_WIDTH_DP = 80
    private val RAIL_EXPANDED_WIDTH_DP = 216
    private val RAIL_ANIMATION_MS = 350L
    // Floor for a re-toggle that interrupts a slide part-way: the duration
    // scales with the distance left, but never gets so short it reads as a snap.
    private val RAIL_ANIMATION_MIN_MS = 140L
    // Chevron's distance from the rail's trailing edge when expanded:
    // 8dp margin + half of the 48dp button.
    private val RAIL_CHEVRON_EDGE_INSET_DP = 32
    private val KEY_RAIL_EXPANDED = "navigation_rail_expanded"
    // M3 emphasized-decelerate — same curve ZoomableVideoView uses, so the rail
    // shares the app's motion vocabulary instead of a stock Decelerate.
    private val RAIL_EASING = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)

    // Handler + runnable owned by the activity so they can be cancelled in
    // onDestroy() — prevents the periodic update check from leaking the
    // activity instance after recreate.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateCheckRunnable: Runnable? = null

    // True only for the headless boot launch where BootReceiver delivers
    // `minimize_on_start=true` so the app process stays alive for daemon
    // stability without surfacing UI. Consumed once in onCreate; not
    // re-read from intent later because Android's singleTop handling does
    // NOT update getIntent() on subsequent onNewIntent calls unless we
    // explicitly setIntent — without this latch the stale boot intent
    // would permanently bypass the PIN gate after a launcher tap.
    private var headlessBootSilenceGate: Boolean = false
    private var remoteDevSession: Boolean = false

    // UI elements
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigationRail: LinearLayout
    private lateinit var navigationRailScroll: SwipeExpandableRailScrollView
    private var navigationRailExpanded = false
    private var navigationRailAnimator: ValueAnimator? = null
    // Which form the rows currently hold. Rows sit in expanded form for the
    // whole slide and only return to compact once a collapse settles, so a
    // starting slide can skip a redundant 17-row relayout on its first frame.
    // Starts false to match item_rail_destination.xml's compact form.
    private var navigationRailRowsExpandedForm = false
    private var railItems: List<RailItem> = emptyList()
    private var railActionItems: List<RailActionItem> = emptyList()
    private lateinit var tvCurrentUrl: TextView
    private lateinit var urlBar: View
    private lateinit var statusPill: View
    private lateinit var statusIndicator: View
    private lateinit var urlStatusDot: View
    private lateinit var btnCopyUrl: ImageButton
    private var screenshotPrivacyController: ScreenshotPrivacyController? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteDevSession = this is RemoteMainActivity &&
            intent?.getBooleanExtra(EXTRA_REMOTE_DEV_SESSION, false) == true

        // The main app must run on the HEAD UNIT (display 0), never the driver
        // cluster. When the OEM cluster projection opens a secondary display, AMS
        // auto-launches the LAUNCHER activity (this one) onto it. If we land on a
        // non-default display, relaunch on display 0 and finish — BEFORE any
        // setContentView / startup work, so nothing partially initialises on the
        // wrong display. (The earlier crash from this was the ADB launcher executor
        // being torn down mid-sequence; that path is now guarded in AdbShellExecutor,
        // and bailing here this early means the startup work hasn't begun yet.)
        try {
            val did = display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            if (did != android.view.Display.DEFAULT_DISPLAY && !remoteDevSession) {
                android.util.Log.w("MainActivity", "launched on display $did — relaunching on display 0")
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    launchDisplayId = android.view.Display.DEFAULT_DISPLAY
                }
                startActivity(
                    android.content.Intent(this, MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    opts.toBundle()
                )
                finish()
                return
            }
            if (did == android.view.Display.DEFAULT_DISPLAY && remoteDevSession) {
                android.util.Log.w("MainActivity", "refusing RemoteMainActivity on display 0")
                finish()
                return
            }
        } catch (error: Throwable) {
            if (remoteDevSession) {
                android.util.Log.w(
                    "MainActivity",
                    "refusing RemoteMainActivity when display identity is unavailable",
                    error,
                )
                finish()
                return
            }
            // Physical MainActivity keeps the existing best-effort behavior.
        }

        com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(this)

        setContentView(R.layout.activity_main_new)

        // Storage setup is posted off the onCreate critical path so a failure
        // (e.g. ROM lacking the All-Files-Access Settings activity on BYD SL7)
        // cannot abort activity launch. See setupStorageDirectories().
        if (!remoteDevSession) window.decorView.post {
            try {
                setupStorageDirectories()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Deferred storage setup failed: ${e.message}", e)
            }
        }

        // Initialize DeviceIdGenerator with ADB executor for file sync
        if (!remoteDevSession) {
            val adbExecutor = com.overdrive.app.launcher.AdbShellExecutor(this)
            com.overdrive.app.util.DeviceIdGenerator.init(adbExecutor)

            // Generate device ID early - this syncs to file for daemon compatibility
            // Must happen BEFORE any daemon starts
            val deviceId = com.overdrive.app.util.DeviceIdGenerator.generateDeviceId(this)
            android.util.Log.i("MainActivity", "Device ID initialized: $deviceId")

            // Apply BYD whitelist (ACC + data cache) to prevent background killing
            // CRITICAL: Run on background thread to avoid blocking UI on boot
            // ActivityThread.systemMain() can block for 1+ minute waiting for system services
            Thread {
                try {
                    BydDataCacheWhitelist.applyAll(this)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "BYD whitelist error: ${e.message}")
                }
            }.start()
        }
        
        // Register the screen-off receiver that locks the PIN session as
        // soon as the panel sleeps. Idempotent — safe to call again on
        // config-change recreate. The receiver is held by the application
        // process so it survives Activity destruction.
        com.overdrive.app.auth.PinSession.ensureScreenOffReceiverRegistered(applicationContext)

        // Latch the headless-boot silence flag exactly once from the
        // initial launch intent and STRIP the extra so the OS task-restore
        // path (process kill → fresh MainActivity instance recreated from
        // the same intent record) cannot re-arm the silence gate against
        // the user's intent. Without the strip, a task restore after OOM
        // would silently bypass the PIN gate again on the recreated
        // instance — see audit round 2/3.
        val rawIntent = intent
        headlessBootSilenceGate =
            rawIntent?.getBooleanExtra("minimize_on_start", false) == true
        if (headlessBootSilenceGate) {
            rawIntent?.removeExtra("minimize_on_start")
            if (rawIntent != null) setIntent(rawIntent)
        }

        // Gate before any visible state appears. If the PIN is enabled and
        // the session isn't fresh, push PinLockActivity on top right away
        // so MainActivity never paints first. Daemons / surveillance /
        // DeterrentActivity are unaffected — this only governs MainActivity.
        maybeShowPinLock()

        initViews()
        screenshotPrivacyController = ScreenshotPrivacyController(this).also { it.start() }
        setupNavigation(savedInstanceState)
        handleNavigateExtra(intent)
        setupCopyButton()
        if (!remoteDevSession) setupLogListener()
        observeViewModels()
        
        // Initialize daemon startup manager
        daemonStartupManager = DaemonStartupManager(this, daemonsViewModel)
        daemonsViewModel.setStartupManager(daemonStartupManager)
        
        // Setup ADB auth callback to re-initialize when auth is granted
        if (!remoteDevSession) setupAdbAuthCallback()
        
        // Log app start
        logsViewModel.info("App", "OverDrive started")

        // Seed out-of-process revival watchdog so the process gets resurrected
        // if it ever gets force-stopped or OOM-killed without an external event.
        if (!remoteDevSession) try {
            com.overdrive.app.receiver.ProcessRevivalReceiver.schedule(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "ProcessRevivalReceiver.schedule failed: ${e.message}")
        }
        
        // Setup privileged shell (UID 1000) - required for daemon management
        // setupPrivilegedShell()
        
        // Start daemons and services
        // Device ID is already synced above via generateDeviceId() which writes to file async
        // The daemon will reload from file when getState() is called
        
        // Start Location Sidecar service (establishes ADB connection)
        if (!remoteDevSession) startLocationSidecarService()
        
        // Initialize daemons after a short delay to allow ADB connection.
        // If this is a post-update launch, run UpdateLifecycle.hardResetDaemons
        // FIRST so any zombie daemons / watchdogs from the previous install are
        // dead before the new daemon launcher starts. See UpdateLifecycle for
        // the sentinel handshake details.
        if (!remoteDevSession) runDaemonStartup(intent, fromOnCreate = true)
        
        // Handle Location start intent (from SentryDaemon restart)
        if (!remoteDevSession) handleLocationStartIntent(intent)
        
        // Check traffic monitor status early so drawer shows correct state
        checkTrafficMonitorStatus()
        
        // Check for app updates (delayed to not block startup)
        if (!remoteDevSession) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Clean up any leftover update APK from previous install. Use the
            // shared daemonStartupManager.adbLauncher — allocating a fresh
            // AdbDaemonLauncher here would leak its non-daemon executor + a
            // tunnel-poll scheduler thread on every postDelayed firing.
            daemonStartupManager.adbLauncher.executeShellCommand("rm -f /data/local/tmp/overdrive_update.apk", object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            })

            // Post-update toasts are consumed by EXACTLY ONE path. On a
            // post-update launch the hardResetDaemons callback drives
            // showPostUpdateToasts(); consuming the same one-shot markers here
            // too could double-fire the toast (the markers clear via async
            // apply()). So only consume here on a NORMAL launch (where the
            // post-update path won't run). The markers survive to the next
            // launch if neither path ran.
            if (!com.overdrive.app.updater.UpdateLifecycle.isPostUpdateLaunch(this, intent)) {
                // Surface failed-install errors first (consumeJustUpdatedVersion
                // returns null when a failure marker is present, so the success
                // toast never fires on a failed install).
                val installError = com.overdrive.app.updater.AppUpdater.consumeFailedUpdateError(this)
                if (installError != null) {
                    Toast.makeText(this, getString(R.string.toast_update_install_failed, installError), Toast.LENGTH_LONG).show()
                    logsViewModel.warn("Update", "Install failed: $installError")
                }

                // Show post-update message if app was just updated.
                // consumeJustUpdatedVersion is the success MARKER and ALSO
                // carries the GitHub label that was installed (PREF_UPDATED_VERSION
                // = remoteVersion). Prefer that label so this toast matches the
                // About row (getDisplayVersion) and /status (getDisplayVersionFromFile),
                // both VERSION_FILE-first. getInstalledVersion() is the BuildConfig
                // identity (braveheart-v26.0 today — versionName is pinned), which
                // would make THIS toast the lone surface showing a stale 26.0 on a
                // braveheart in-place re-upload. Empty marker (remoteVersion was
                // "unknown") → fall through getDisplayVersion (still VERSION_FILE-
                // first, only drops to BuildConfig on a fresh sideload).
                val justUpdated = com.overdrive.app.updater.AppUpdater.consumeJustUpdatedVersion(this)
                if (justUpdated != null) {
                    val shown = if (justUpdated.isNotEmpty()) justUpdated
                                else com.overdrive.app.updater.AppUpdater.getDisplayVersion(this)
                    Toast.makeText(this, getString(R.string.toast_updated_to, shown), Toast.LENGTH_LONG).show()
                    logsViewModel.info("Update", "App updated to $shown")
                }
            }

            checkForAppUpdate()
        }, 10000) // 10 seconds after launch
        
        // Schedule periodic update checks (every 6 hours)
        if (!remoteDevSession) schedulePeriodicUpdateCheck()
        
        // Status overlay: start immediately if permission granted, show guide if not
        if (!remoteDevSession) startStatusOverlay()
        
        // If launched from boot receiver with minimize flag, move to back immediately.
        // This keeps the process alive (important for daemon stability) without
        // showing the app UI over the BYD home screen.
        if (!remoteDevSession && headlessBootSilenceGate) {
            android.util.Log.i("MainActivity", "Boot launch — minimizing to background")
            moveTaskToBack(true)
            // NB: do NOT clear headlessBootSilenceGate here. This block runs
            // inside onCreate; the launch's OWN first onResume fires immediately
            // after and calls maybeShowPinLock(). If the latch were already
            // cleared, that first onResume would gate and surface PinLockActivity
            // over the BYD home screen (with FLAG_TURN_SCREEN_ON waking the
            // panel) even though we just moved the task to back — an unrequested
            // lock-screen flash on every boot / post-update. The latch is instead
            // cleared in onPause (which fires once this minimized launch leaves
            // the foreground), so the gate stays suppressed through the launch's
            // own onResume and re-arms for the user's next genuine foreground
            // entry. onNewIntent clears it explicitly for notification/user
            // re-entries.
        }
    }
    
    /**
     * Start the status overlay service if overlay permission is granted, and
     * show the setup guide whenever the install/update marker has advanced.
     * The guide must reappear on every install/replace because BYD wipes the
     * autostart whitelist on each install.
     */
    private fun startStatusOverlay() {
        val hasPermission = com.overdrive.app.overlay.StatusOverlayService.hasOverlayPermission(this)
        android.util.Log.i("MainActivity", "Overlay permission: $hasPermission")
        logsViewModel.info("Overlay", "Overlay permission: $hasPermission")

        if (hasPermission) {
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(this)
            logsViewModel.info("Overlay", "Status overlay service started")
        }

        // RoadSense floating overlay (D-024): start only when the feature is enabled.
        // It's a separate window from the status overlay and renders the pill/card.
        syncRoadSenseOverlay()

        // Blind Spot overlay: native hardware-decoded stream view 7/8, shown on
        // turn indicator. Started only when the blindspot feature is enabled.
        syncBlindSpotOverlay()

        // showIfNeeded is no-op when the seen install-time matches the current
        // PackageInfo.lastUpdateTime, so it's safe to call on every launch.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val guideShown = com.overdrive.app.overlay.SetupGuideDialog.showIfNeeded(this)
            // Sequence the onboarding guide AFTER the setup-guide perms dialog. If the
            // setup guide was shown this launch, wait for the user to clear it before
            // starting onboarding (SetupGuideDialog has no completion callback, so we
            // post on a further delay); otherwise start once the dialog window is free.
            // The host self-gates on OnboardingState.shouldAutoRunNovice() + the parked
            // ACC broadcast, and is sequenced after the PIN gate because startStatusOverlay
            // runs in onCreate after maybeShowPinLock.
            maybeStartOnboarding(if (guideShown) 1500L else 0L)
        }, 2000)
    }

    private fun maybeStartOnboarding(delayMs: Long) {
        val runner = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            val host = onboardingHost ?: com.overdrive.app.onboarding.OnboardingHost(
                this, daemonStartupManager.adbLauncher,
            ).also { onboardingHost = it }
            host.startIfNeeded()
        }
        if (delayMs <= 0L) runOnUiThread(runner)
        else android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(runner, delayMs)
    }
    
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        // Re-pin getIntent() to the new intent so any code that consults
        // it (now or later) sees the current launcher/notification intent
        // rather than the original cold-launch intent. Null-guarded so we
        // don't strand getIntent() at null if the platform delivers a
        // null intent on some upstream OEM build.
        if (intent != null) setIntent(intent)
        // Clear the boot-silence latch as soon as a fresh user intent
        // arrives. Any onNewIntent path is by definition the user
        // bringing the app forward — they should be gated.
        headlessBootSilenceGate = false
        // PIN gate first — notification taps reach MainActivity via
        // onNewIntent because of singleTop. Gating here keeps that path
        // covered without relying on onResume firing first.
        maybeShowPinLock()
        intent?.let {
            if (!remoteDevSession) handleLocationStartIntent(it)
            handleNavigateExtra(it)
            // Critical: when MainActivity is already running and the install
            // script's `am start --ez post_update true` re-delivers the
            // intent (singleTop launchMode → onNewIntent, not onCreate), the
            // post-update hard-reset would otherwise be skipped, leaving
            // zombie daemons from the old install alive. Re-run the same
            // flow onCreate uses; runDaemonStartup is idempotent (guarded
            // by daemonStartupRequested + by the sentinel/intent-extra
            // checks inside isPostUpdateLaunch — once the sentinels are
            // consumed, subsequent calls become no-ops).
            if (!remoteDevSession) runDaemonStartup(it, fromOnCreate = false)
        }
    }

    /**
     * Deep-link entry for external surfaces (the OverDrive launcher's glance
     * widgets, notifications). A plain string extra keeps the contract stable
     * across both APKs without sharing code: `--es navigate_to trips`.
     * The extra is STRIPPED once consumed so an OS task-restore of the same
     * intent record cannot replay the navigation over the user's later state.
     * Unknown values are ignored (forward/backward compatible).
     */
    private fun handleNavigateExtra(intent: android.content.Intent) {
        val target = intent.getStringExtra(EXTRA_NAVIGATE_TO) ?: return
        intent.removeExtra(EXTRA_NAVIGATE_TO)
        setIntent(intent)
        val destinationId = when (target) {
            "trips" -> R.id.tripsFragment
            "charging" -> R.id.chargingFragment
            "roadsense" -> R.id.roadSenseFragment
            "recordings" -> R.id.recordingsFragment
            "live" -> R.id.liveViewFragment
            "vehicle" -> R.id.vehicleControlFragment
            "dashboard" -> R.id.dashboardFragment
            "assistant" -> R.id.genAiFragment
            else -> return
        }
        // navigateToRailDestination self-defers via pendingRailDestination when
        // FragmentManager state is saved, so this is safe from onCreate/onNewIntent.
        navigateToRailDestination(destinationId)
    }

    /**
     * Single source of truth for "MainActivity wants daemons running."
     * Idempotent across onCreate / onNewIntent / ADB-auth-granted callbacks:
     *   - daemonStartupCoordinator guards against duplicate concurrent runs
     *   - the underlying UpdateLifecycle sentinels are one-shot so a second
     *     call after the post-update reset becomes a normal startup
     *
     * The 1-second postDelayed gives ADB / device-id sync time to settle.
     */
    private fun runDaemonStartup(intent: android.content.Intent?, fromOnCreate: Boolean) {
        val isPostUpdate = com.overdrive.app.updater.UpdateLifecycle
            .isPostUpdateLaunch(this, intent)
        // Skip the postDelayed boilerplate when called from onNewIntent for
        // a non-post-update intent — daemons are already up from onCreate
        // and re-running initializeOnAppLaunch() in that case just churns.
        if (!fromOnCreate && !isPostUpdate) {
            android.util.Log.d("MainActivity",
                "runDaemonStartup: onNewIntent without post-update marker — no-op")
            return
        }

        synchronized (daemonStartupCoordinator) {
            if (daemonStartupCoordinator.inFlight) {
                android.util.Log.i("MainActivity",
                    "runDaemonStartup: another startup pass is in flight — skipping")
                return
            }
            daemonStartupCoordinator.inFlight = true
        }

        // Defensive sentinel cleanup. Gated by the inFlight guard above so
        // duplicate onNewIntent calls don't fire redundant ADB rms while
        // another startup pass is already running. Routes through
        // daemonStartupManager's shared AdbDaemonLauncher — see
        // DaemonStartupManager.clearStaleSentinels for the contract.
        daemonStartupManager.clearStaleSentinels()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread {
                try {
                    try {
                        val synced = com.overdrive.app.util.DeviceIdGenerator
                            .syncDeviceIdToFileSync(this)
                        android.util.Log.i("MainActivity", "Device ID sync result: $synced")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Device ID sync error: ${e.message}")
                    }

                    val startDaemons = Runnable {
                        runOnUiThread {
                            daemonStartupManager.initializeOnAppLaunch()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                daemonStartupManager.checkAllDaemonStatuses()
                            }, 3000)
                        }
                    }

                    if (isPostUpdate) {
                        logsViewModel.info("Update",
                            "Post-update launch — hard-resetting daemons before startup")
                        // Watchdog: if hardResetDaemons silently drops its
                        // callback (synchronous throw inside the launcher,
                        // unexpected shell exec failure mode), the inFlight
                        // flag would otherwise stay true forever and block
                        // every subsequent runDaemonStartup call from
                        // onNewIntent. 30s is well past hardResetDaemons'
                        // observed worst-case (~5-8s for the shell sweep
                        // plus the 1s settle) so this only fires on a
                        // real silent-drop bug, not on slow happy paths.
                        //
                        // The watchdog Runnable is captured into
                        // daemonStartupCoordinator.pendingWatchdog so
                        // onDestroy can cancel it — otherwise the lambda
                        // captures `this@MainActivity` and leaks the
                        // activity for up to 30s after a backout.
                        val callbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        val watchdog = Runnable {
                            if (isFinishing || isDestroyed) {
                                if (callbackFired.compareAndSet(false, true)) {
                                    synchronized (daemonStartupCoordinator) {
                                        daemonStartupCoordinator.inFlight = false
                                        daemonStartupCoordinator.pendingWatchdog = null
                                    }
                                }
                                return@Runnable
                            }
                            if (callbackFired.compareAndSet(false, true)) {
                                android.util.Log.w("MainActivity",
                                    "runDaemonStartup: hardResetDaemons callback never fired " +
                                    "within 30s — proceeding with daemon startup anyway")
                                try { showPostUpdateToasts() } catch (_: Exception) {}
                                startDaemons.run()
                                synchronized (daemonStartupCoordinator) {
                                    daemonStartupCoordinator.inFlight = false
                                    daemonStartupCoordinator.pendingWatchdog = null
                                }
                            }
                        }
                        synchronized (daemonStartupCoordinator) {
                            daemonStartupCoordinator.pendingWatchdog = watchdog
                            daemonStartupCoordinator.watchdogHandler = mainHandler
                        }
                        mainHandler.postDelayed(watchdog, 30_000)
                        com.overdrive.app.updater.UpdateLifecycle.hardResetDaemons(this) {
                            if (callbackFired.compareAndSet(false, true)) {
                                synchronized (daemonStartupCoordinator) {
                                    daemonStartupCoordinator.pendingWatchdog?.let {
                                        daemonStartupCoordinator.watchdogHandler
                                            ?.removeCallbacks(it)
                                    }
                                    daemonStartupCoordinator.pendingWatchdog = null
                                }
                                if (isFinishing || isDestroyed) {
                                    synchronized (daemonStartupCoordinator) {
                                        daemonStartupCoordinator.inFlight = false
                                    }
                                    return@hardResetDaemons
                                }
                                try {
                                    showPostUpdateToasts()
                                } finally {
                                    startDaemons.run()
                                    synchronized (daemonStartupCoordinator) {
                                        daemonStartupCoordinator.inFlight = false
                                    }
                                }
                            }
                        }
                    } else {
                        startDaemons.run()
                        synchronized (daemonStartupCoordinator) {
                            daemonStartupCoordinator.inFlight = false
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity",
                        "runDaemonStartup error: ${e.message}", e)
                    synchronized (daemonStartupCoordinator) {
                        daemonStartupCoordinator.inFlight = false
                    }
                }
            }.start()
        }, 1000)
    }

    /**
     * Show "updated to vX" / "install failed" toasts after a post-update
     * hard-reset. Plants the Telegram hint file when an update succeeded
     * so the next tunnel-URL message includes the new version.
     *
     * Both consume* methods clear their backing markers, so calling this
     * twice in one process lifetime fires toasts only once.
     */
    private fun showPostUpdateToasts() {
        val installError = com.overdrive.app.updater.AppUpdater
            .consumeFailedUpdateError(this)
        if (installError != null) {
            runOnUiThread {
                Toast.makeText(this,
                    getString(R.string.toast_update_install_failed, installError),
                    Toast.LENGTH_LONG).show()
                logsViewModel.warn("Update", "Install failed: $installError")
            }
        }
        val justUpdated = com.overdrive.app.updater.AppUpdater
            .consumeJustUpdatedVersion(this)
        if (justUpdated != null) {
            // Marker non-null = update succeeded; DISPLAY the installed GitHub
            // label the marker carries (PREF_UPDATED_VERSION = remoteVersion) so
            // this toast — and the Telegram post-update hint planted from `shown`
            // below — match the About row (getDisplayVersion) and /status
            // (getDisplayVersionFromFile), both VERSION_FILE-first. The old
            // getInstalledVersion() returns the pinned BuildConfig identity
            // (braveheart-v26.0), which is stale on a braveheart in-place
            // re-upload. Empty marker → getDisplayVersion (VERSION_FILE-first).
            val shown = if (justUpdated.isNotEmpty()) justUpdated
                        else com.overdrive.app.updater.AppUpdater.getDisplayVersion(this)
            runOnUiThread {
                Toast.makeText(this,
                    getString(R.string.toast_updated_to, shown),
                    Toast.LENGTH_LONG).show()
                logsViewModel.info("Update", "App updated to $shown")
            }
            try {
                // Reuse the shared adbLauncher; see comment at the rm site
                // for why allocating a fresh one here leaks resources.
                val hintFile = com.overdrive.app.updater
                    .UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE
                daemonStartupManager.adbLauncher.executeShellCommand(
                    "echo '$shown' > $hintFile",
                    object : com.overdrive.app.launcher
                        .AdbDaemonLauncher.LaunchCallback {
                        override fun onLog(message: String) {}
                        override fun onLaunched() {}
                        override fun onError(error: String) {}
                    }
                )
            } catch (e: Exception) {
                android.util.Log.w("MainActivity",
                    "Failed to plant Telegram post-update hint: ${e.message}")
            }
        }
    }

    /** Coordinator object shared by onCreate / onNewIntent so concurrent
     *  startup attempts collapse into one. inFlight is reset in every
     *  terminal branch (success, hard-reset done, error path).
     *  pendingWatchdog/watchdogHandler are tracked so onDestroy can
     *  cancel an in-flight 30s watchdog and avoid leaking the Activity. */
    private val daemonStartupCoordinator = DaemonStartupCoordinator()
    private class DaemonStartupCoordinator {
        @Volatile var inFlight: Boolean = false
        var pendingWatchdog: Runnable? = null
        var watchdogHandler: android.os.Handler? = null
    }

    
    override fun onResume() {
        super.onResume()
        // PIN gate: if Settings → Security has the app lock enabled and the
        // session isn't currently unlocked (or the auto-lock timeout has
        // elapsed since last pause), bring up the lock screen on top of
        // this Activity. We don't finish() — MainActivity remains behind
        // PinLockActivity so unlock returns straight to the user's nav
        // destination. Daemons / surveillance / DeterrentActivity are
        // never affected by this gate.
        maybeShowPinLock()

        // Try to start overlay if permission was just granted (user returned from settings)
        if (!remoteDevSession) {
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(this)
        }

        // Re-sync the RoadSense overlay on resume: the user may have just toggled
        // RoadSense on/off in the web UI and returned to the app, or granted the
        // overlay permission. Cheap — a no-op if the state already matches.
        if (!remoteDevSession) syncRoadSenseOverlay()

        // Daemon-ready flush of any pending onboarding operating-mode choice. The
        // auth-granted callback fires BEFORE the user reaches the MODE step (and only
        // once on a stable ADB connection), so it can't be the sole trigger — onResume
        // runs on every return to the app, reliably after the daemon has bound :8080 on
        // any non-first launch. Idempotent + no-op when nothing is pending, and it
        // reconciles against the live config so it never clobbers a later Settings
        // change (see flushPendingOperatingMode).
        if (!remoteDevSession) {
            com.overdrive.app.onboarding.OnboardingHost
                .flushPendingOperatingMode(applicationContext)
        }

        // Re-drive a rail tap that was deferred because it raced Activity state saving. Now
        // resumed, FragmentManager can commit. navigateToRailDestination re-checks isStateSaved
        // and clears the pending value, so this can't loop or fire a stale destination.
        pendingRailDestination?.let { dest ->
            pendingRailDestination = null
            navigateToRailDestination(dest)
        }
    }

    /**
     * Start the RoadSense floating overlay when the feature is enabled (and overlay
     * permission is granted), or stop it when disabled. RoadSense persists its
     * `enabled` flag in the UnifiedConfigManager `roadSense` section (cross-UID with
     * the daemon); we forceReload so a just-written daemon/web change is seen. The
     * overlay itself only RENDERS daemon-published state, so starting it while the
     * daemon isn't producing hazards simply shows the idle "scanning" pill.
     */
    private fun syncRoadSenseOverlay() {
        try {
            // One policy owns both the RoadSense master gate and the user's
            // display-only overlay preference.
            com.overdrive.app.roadsense.overlay.RoadSenseOverlayService
                .syncWithConfig(this)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "syncRoadSenseOverlay failed: ${t.message}")
        }
    }

    /**
     * Arm/disarm the NATIVE blind-spot lane to match the `blindspot.enabled` flag.
     * The visual is daemon-side (SurfaceControl layer); the app just POSTs the
     * daemon control surface — no app-process overlay/decoder. The daemon owns
     * show/hide (turn-trigger) and positioning.
     */
    private fun syncBlindSpotOverlay() {
        com.overdrive.app.roadsense.overlay.BlindSpotControl.sync(this)
    }

    override fun onPause() {
        super.onPause()
        // Consume the one-shot headless-boot silence latch here rather than at
        // the end of onCreate. A minimized boot/post-update launch goes
        // onCreate (latch set, maybeShowPinLock suppressed) → onResume (still
        // suppressed) → onPause (this, as moveTaskToBack backgrounds us). By the
        // time onPause fires the launch's own first onResume has already passed
        // without flashing the PIN, and the NEXT foreground entry — a genuine
        // user open — finds the latch cleared and gates normally. Clearing in
        // onCreate would let that first onResume surface the keypad over the BYD
        // home screen (see the minimize block in onCreate).
        if (headlessBootSilenceGate) {
            headlessBootSilenceGate = false
            android.util.Log.i("MainActivity", "Boot-silence latch consumed on first onPause")
        }
        if (!remoteDevSession) com.overdrive.app.auth.PinSession.notePaused()
    }

    /**
     * Single source of truth for PIN gating. Called from onCreate, onResume,
     * and onNewIntent; bails fast when the lock is disabled.
     *
     * Two extra side-effects beyond launching PinLockActivity:
     *  1. While the lock is active, FLAG_SECURE is applied to MainActivity's
     *     window. The BYD launcher's recents thumbnail / OS task switcher
     *     would otherwise capture whatever fragment was on screen — which
     *     leaks live-camera, vehicle-control, and dashboard data while
     *     the app is supposedly "locked." FLAG_SECURE causes the
     *     framework to render a black snapshot.
     *  2. The flag is cleared once the session is unlocked (called from
     *     onResume after PinLockActivity returns RESULT_OK).
     *
     * The {@code minimize_on_start} intent extra is set by BootReceiver
     * for headless boot launches that immediately moveTaskToBack to keep
     * the process alive without showing UI. In that path the user isn't
     * present, so we skip the gate — otherwise the lock screen would
     * flash up on top of the BYD home screen at every boot.
     */
    private fun maybeShowPinLock() {
        // Remote Dev View never launches the PIN Activity or mutates the
        // process-wide PinSession. The bridge admits a remote session only
        // while the physical UI is already unlocked.
        if (remoteDevSession) return
        try {
            val pinEnabled = com.overdrive.app.auth.PinManager.isEnabled()
            // Apply / clear FLAG_SECURE based on current lock state, regardless
            // of whether we're about to gate (covers the unlock-now path too).
            if (pinEnabled && !com.overdrive.app.auth.PinSession.isUnlocked()) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            if (!pinEnabled) return

            // Headless boot: BootReceiver may launch us with minimize_on_start
            // so the app process exists for daemon stability, but the user
            // isn't there. Skip the lock-screen flash for that one launch —
            // the gate will fire on the user's next foreground entry. The
            // flag is one-shot per Activity instance (latched in onCreate
            // from the original launch intent) and explicitly cleared on
            // any subsequent user-driven foreground entry below.
            if (headlessBootSilenceGate) return

            val autoLock = com.overdrive.app.auth.PinManager.getAutoLockMs()
            if (com.overdrive.app.auth.PinSession.shouldGate(autoLock)) {
                val pinIntent = android.content.Intent(this, com.overdrive.app.ui.PinLockActivity::class.java)
                pinIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(pinIntent)
            }
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "PIN gate check failed: ${t.message}")
        }
    }
    
    /**
     * Setup ADB auth callback to re-initialize daemons when auth is granted.
     * This handles the case where user grants ADB auth after the initial connection attempt failed.
     */
    private fun setupAdbAuthCallback() {
        com.overdrive.app.launcher.AdbShellExecutor.setAuthCallback(object : com.overdrive.app.launcher.AdbShellExecutor.AdbAuthCallback {
            override fun onAuthPending() {
                runOnUiThread {
                    logsViewModel.info("ADB", "⏳ Waiting for ADB authorization...")
                    logsViewModel.info("ADB", "Please accept the USB debugging prompt")
                }
            }
            
            override fun onAuthGranted() {
                runOnUiThread {
                    logsViewModel.info("ADB", "ADB authorization granted")
                    logsViewModel.info("ADB", "Re-initializing daemons...")

                    // Advance the onboarding Step-0 (daemon auth) if it's waiting. This
                    // is the SINGLE process-wide auth callback slot, so we notify the
                    // host here rather than registering a second callback.
                    onboardingHost?.onDaemonAuthGranted()
                        ?: run { com.overdrive.app.onboarding.OnboardingState.get(this@MainActivity).daemonAuthorized = true }
                    
                    // Re-run daemon initialization now that ADB is authorized
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        daemonStartupManager.initializeOnAppLaunch()

                        // Flush any pending operating-mode choice the first-run dialog
                        // couldn't persist before the daemon bound :8080. Decoupled from
                        // the onboarding step lifecycle (survives onboardingComplete), so
                        // an "On only" pick is never silently lost. No-op when nothing is
                        // pending; has its own retry+backoff to tolerate daemon bring-up.
                        com.overdrive.app.onboarding.OnboardingHost
                            .flushPendingOperatingMode(applicationContext)

                        // Check daemon statuses after startup
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            daemonStartupManager.checkAllDaemonStatuses()
                        }, 3000)
                    }, 500)
                    
                    // Re-check traffic monitor now that ADB is available
                    checkTrafficMonitorStatus()
                }
            }
            
            override fun onAuthFailed(error: String) {
                runOnUiThread {
                    logsViewModel.error("ADB", "ADB connection failed: $error")
                }
            }
        })
    }
    
    /**
     * Check GitHub for app updates and show dialog if available.
     */
    private fun checkForAppUpdate() {
        logsViewModel.info("Update", "Checking for updates (channel: ${com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel()})...")
        val updater = com.overdrive.app.updater.AppUpdater(this)
        appUpdater = updater
        updater.checkForUpdate(object : com.overdrive.app.updater.AppUpdater.UpdateCallback {
            override fun onUpdateAvailable(currentVersion: String, newVersion: String, releaseNotes: String) {
                // Don't close updater here — performAppUpdate will use it.
                com.overdrive.app.updater.UpdateDialog.showUpdateAvailable(
                    this@MainActivity, currentVersion, newVersion, releaseNotes,
                    { performAppUpdate(updater) },
                    { updater.close() }  // Dismiss path: release executor + scheduler.
                )
            }

            override fun onNoUpdate(currentVersion: String) {
                logsViewModel.debug("Update", "App is up to date (v$currentVersion)")
                // No further use — release per-instance executor.
                updater.close()
            }

            override fun onError(error: String) {
                logsViewModel.debug("Update", "Update check failed: $error")
                updater.close()
            }
        })
    }

    /**
     * Manual update check — shows toast if already up to date.
     *
     * Forks on the resolved channel: braveheart keeps the timestamp-based
     * "is an update available?" flow; alpha opens the pick-any version
     * catalog (there is no single "the update" on the archive channel).
     */
    fun checkForAppUpdateManual() {
        Toast.makeText(this, getString(R.string.toast_checking_for_updates), Toast.LENGTH_SHORT).show()
        val updater = com.overdrive.app.updater.AppUpdater(this)
        appUpdater = updater

        val channel = com.overdrive.app.config.UnifiedConfigManager.let {
            it.forceReload(); it.getUpdateChannel()
        }
        if (channel == com.overdrive.app.updater.AppUpdater.CHANNEL_ALPHA) {
            checkAlphaVersions(updater)
            return
        }

        updater.checkForUpdate(object : com.overdrive.app.updater.AppUpdater.UpdateCallback {
            override fun onUpdateAvailable(currentVersion: String, newVersion: String, releaseNotes: String) {
                com.overdrive.app.updater.UpdateDialog.showUpdateAvailable(
                    this@MainActivity, currentVersion, newVersion, releaseNotes,
                    { performAppUpdate(updater) },
                    { updater.close() }  // Dismiss path: release executor + scheduler.
                )
            }

            override fun onNoUpdate(currentVersion: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_app_up_to_date, currentVersion), Toast.LENGTH_LONG).show()
                updater.close()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_update_check_failed, error), Toast.LENGTH_LONG).show()
                updater.close()
            }
        })
    }

    /**
     * Alpha channel: list the archive, show the single-choice picker, then
     * resolve the chosen tag SERVER-SIDE (prepareInstall, off the UI thread —
     * it does network I/O) before handing off to the shared install flow.
     */
    private fun checkAlphaVersions(updater: com.overdrive.app.updater.AppUpdater) {
        updater.listVersions(object : com.overdrive.app.updater.AppUpdater.VersionListCallback {
            override fun onResult(
                versions: MutableList<com.overdrive.app.updater.AppUpdater.VersionEntry>,
                currentVersion: String
            ) {
                com.overdrive.app.updater.UpdateDialog.showVersionPicker(
                    this@MainActivity, versions,
                    object : com.overdrive.app.updater.UpdateDialog.VersionPickListener {
                        override fun onPick(entry: com.overdrive.app.updater.AppUpdater.VersionEntry) {
                            // Pass the chosen tag straight to performAppUpdate —
                            // the daemon's INSTALL_UPDATE resolves it server-side
                            // (prepareInstall) before downloading, so no app-side
                            // prepareInstall is needed (and the app UID's resolve
                            // would just be redundant work the daemon repeats).
                            performAppUpdate(updater, entry.tag)
                        }
                        override fun onDismiss() { updater.close() }
                    })
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.toast_update_check_failed, error), Toast.LENGTH_LONG).show()
                updater.close()
            }
        })
    }

    /**
     * Schedule periodic update checks (every 6 hours).
     */
    private fun schedulePeriodicUpdateCheck() {
        // AUTO-UPDATE DISABLED — the periodic 6h check called checkForAppUpdate()
        // which can downloadAndInstall a newer release and silently overwrite a
        // locally-patched build. Turned off so the device stays on the build you
        // flashed. Manual updates still work (Settings → About → Check for
        // updates / invokeCheckForUpdates()). To restore auto-update, revert
        // this method to the periodic postDelayed scheduling.
        updateCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        updateCheckRunnable = null
    }

    /**
     * Run the install in the DAEMON (UID 2000) via IPC, then poll progress —
     * the same INSTALL_UPDATE → /overdrive_update_progress.json path the webapp
     * and Telegram already use. Why not download in-process: the app UID can't
     * write /data/local/tmp/, so the old in-process path fell back to a shell
     * `wget` that emits no parseable progress — the bar pinned at 15% for the
     * whole download ("stuck at 15%"). The daemon's OkHttp download streams real
     * percent into the shared progress file. This unifies app/web/Telegram on
     * one download+install engine.
     *
     * @param versionTag alpha-pick tag (e.g. "alpha-v26.1"), or null for the
     *                   braveheart available-check install.
     */
    private fun performAppUpdate(updater: com.overdrive.app.updater.AppUpdater,
                                 versionTag: String? = null) {
        // The whole flow runs in the daemon now, so this app-side AppUpdater is
        // only here for its lifecycle (close releases the lazily-allocated ADB
        // executor + tunnel scheduler). Release it once the IPC handoff is done.
        val progress = com.overdrive.app.updater.UpdateDialog.showProgress(this) {
            // "Hide" — the daemon download/install can't be cancelled from here
            // (it runs in another process, mirroring the webapp which also has
            // no mid-install cancel once scheduled). Just stop polling + dismiss;
            // the install continues and the app restarts when it lands.
            updatePollRunnable?.let { mainHandler.removeCallbacks(it) }
            updatePollRunnable = null
        }
        progress.setStep(R.string.update_step_queued, R.drawable.ic_update, 0)

        // Kick off the install via IPC OFF the main thread (DaemonIpcClient.send
        // is synchronous: connect + up to 20s read for the pre-install check).
        Thread {
            val req = org.json.JSONObject().apply {
                put("command", "INSTALL_UPDATE")
                if (!versionTag.isNullOrEmpty()) put("version", versionTag)
            }
            // 25s > daemon's 20s pre-install checkForUpdate wait, so the IPC read
            // doesn't time out before INSTALL_UPDATE replies {status:"scheduled"}.
            val resp = com.overdrive.app.server.DaemonIpcClient.send(req, 25_000)
            runOnUiThread {
                // Activity tore down during the ~25s IPC window (rotation,
                // back-out, recreate) — runOnUiThread does NOT auto-cancel, so
                // bail before touching the progress dialog whose window token is
                // gone (showError on a detached window throws/leaks). Mirrors
                // startUpdateProgressPolling and onReconfigureCameraClicked.
                // Close the updater so its lazy ADB executor/scheduler doesn't
                // leak; the daemon-side install gate self-recovers regardless.
                if (isFinishing || isDestroyed) {
                    updater.close()
                    return@runOnUiThread
                }
                if (resp == null) {
                    // Daemon down / IPC refused — can't delegate. Surface clearly.
                    progress.showError(getString(R.string.update_error_daemon_down))
                    updater.close()
                    return@runOnUiThread
                }
                val ok = resp.optBoolean("success", false)
                    || "scheduled" == resp.optString("status")
                if (!ok) {
                    val err = resp.optString("error", getString(R.string.errors_network))
                    progress.showError(getString(R.string.update_error_start_failed, err))
                    updater.close()
                    return@runOnUiThread
                }
                // Scheduled. The app-side updater has no more work — the daemon
                // owns download+install. Release its per-instance resources now.
                updater.close()
                startUpdateProgressPolling(progress)
            }
        }.start()
    }

    /** Handle for the active update progress poll loop (so Hide can stop it). */
    private var updatePollRunnable: Runnable? = null

    /**
     * Poll GET_UPDATE_PROGRESS every 1.5s and drive the progress dialog.
     * Mirrors the webapp's startProgressPolling: render {phase, percent},
     * treat `error` as a hard failure, and treat a daemon disconnect AFTER a
     * terminal phase (stopping_daemons / installing) as success — the install
     * is underway and pm install is tearing the daemon (and soon us) down.
     */
    private fun startUpdateProgressPolling(progress: com.overdrive.app.updater.UpdateDialog.ProgressHandle) {
        updatePollRunnable?.let { mainHandler.removeCallbacks(it) }
        var consecutiveFailures = 0
        var sawTerminalPhase = false
        var barLatchedAt100 = false

        val poll = object : Runnable {
            override fun run() {
                Thread {
                    val resp = com.overdrive.app.server.DaemonIpcClient.send(
                        org.json.JSONObject().put("command", "GET_UPDATE_PROGRESS"), 5_000)
                    runOnUiThread {
                        // Activity gone (rotation/finish) — stop, don't touch the
                        // dialog (would leak / throw on a dead window).
                        if (isFinishing || isDestroyed) {
                            updatePollRunnable = null
                            return@runOnUiThread
                        }
                        // If Hide stopped us between dispatch and reply, drop it.
                        if (updatePollRunnable == null) return@runOnUiThread

                        if (resp == null || !resp.optBoolean("success", false)) {
                            consecutiveFailures++
                            // Daemon dying mid-install → after a terminal phase
                            // (stopping_daemons/installing) a disconnect IS the
                            // success signal: pm install is tearing the daemon
                            // (and soon this app) down. 2 failures (~3s) is enough.
                            if (consecutiveFailures >= 2 && sawTerminalPhase) {
                                updatePollRunnable = null
                                progress.setStep(R.string.update_step_installing, R.drawable.ic_download_log, 100)
                                mainHandler.postDelayed({ progress.dismiss() }, 2000)
                                return@runOnUiThread
                            }
                            // Non-terminal disconnect (still queued/downloading/
                            // verifying): give up at 4 failures (~6s), matching
                            // the web poller's threshold (update-flow.js
                            // startProgressPolling) so both surfaces declare the
                            // failure at the same point. Unlike the web client
                            // (a separate device that can outlive the head unit's
                            // reinstall and watch for a version bump), this poller
                            // runs ON the head unit — a SUCCESSFUL update kills
                            // this very process, so a non-terminal disconnect that
                            // leaves us alive can only mean the install did NOT
                            // complete. Surface the head-unit-lost copy, aligned
                            // with the web's cannot_reach_headunit message.
                            if (consecutiveFailures >= 4) {
                                updatePollRunnable = null
                                progress.showError(getString(R.string.update_error_lost_headunit))
                                return@runOnUiThread
                            }
                            mainHandler.postDelayed(this, 1500)
                            return@runOnUiThread
                        }
                        consecutiveFailures = 0

                        val phase = resp.optString("phase", "")
                        val percent = resp.optInt("percent", -1)
                        if (phase == "stopping_daemons" || phase == "installing") sawTerminalPhase = true

                        when (phase) {
                            "error" -> {
                                updatePollRunnable = null
                                progress.showError(resp.optString("error",
                                    resp.optString("message", getString(R.string.errors_network))))
                                return@runOnUiThread
                            }
                            "downloading" -> {
                                if (percent < 0) {
                                    // No Content-Length (CDN/proxy) → indeterminate
                                    // rather than a frozen 15%.
                                    progress.setIndeterminate(getString(R.string.update_step_downloading))
                                } else {
                                    // Map 0..100 download into the bar's [15,75).
                                    val mapped = 15 + (percent.coerceIn(0, 100) * 60 / 100)
                                    progress.setStep(R.string.update_step_downloading, R.drawable.ic_arrow_down, mapped)
                                    if (percent >= 100) barLatchedAt100 = true
                                }
                            }
                            "verifying" -> progress.setStep(R.string.update_step_verifying, R.drawable.ic_check_circle, 75)
                            "stopping_daemons" -> progress.setStep(R.string.update_step_stopping, R.drawable.ic_update, 85)
                            "installing" -> progress.setStep(R.string.update_step_installing, R.drawable.ic_download_log, if (percent == 100) 100 else 95)
                            "queued" -> progress.setStep(R.string.update_step_queued, R.drawable.ic_update, 0)
                            "idle" -> { /* no active install yet — keep polling */ }
                        }
                        mainHandler.postDelayed(this, 1500)
                    }
                }.start()
            }
        }
        updatePollRunnable = poll
        mainHandler.post(poll)
    }

    /**
     * SOTA: Setup storage directories from the App so it becomes the owner.
     * This ensures both app and daemon can read/write to the directories.
     * On Android 11+, requires MANAGE_EXTERNAL_STORAGE permission.
     * On Android 10 and below, requires WRITE_EXTERNAL_STORAGE runtime permission.
     */
    private fun setupStorageDirectories() {
        android.util.Log.i("MainActivity", "========== CHECKING STORAGE PERMISSION ==========")
        val hasPermission = StorageSetup.checkStoragePermission(this)
        android.util.Log.i("MainActivity", "checkStoragePermission() = $hasPermission")

        if (hasPermission) {
            android.util.Log.i("MainActivity", "Permission OK - calling setupDirectories()")
            val success = StorageSetup.setupDirectories()
            if (success) {
                android.util.Log.i("MainActivity", "Storage directories ready (App is owner)")
            } else {
                android.util.Log.w("MainActivity", "Some storage directories could not be created")
            }
            return
        }

        // On Android 10 and below, the only path is the standard runtime
        // permission dialog for WRITE_EXTERNAL_STORAGE. No MES, no app-ops.
        // Preserve the original behaviour exactly.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            android.util.Log.i("MainActivity", "Pre-R: requesting runtime WRITE_EXTERNAL_STORAGE")
            fallbackToSettingsRequest()
            return
        }

        // Android 11+: best-effort directory creation in legacy mode regardless
        // of MES state, so recordings still work on this launch even if MES
        // never lands. With requestLegacyExternalStorage="true" + targetSdk 25,
        // WRITE_EXTERNAL_STORAGE is enough for our own paths under
        // /storage/emulated/0/Overdrive.
        val legacySuccess = StorageSetup.setupDirectories()
        android.util.Log.i("MainActivity", "Legacy-mode setupDirectories success=$legacySuccess")

        // Try the silent app-ops path first (only viable route on BYD SL7 which
        // lacks the All-Files-Access Settings activity). Settings intent is only
        // opened if the app-ops grant fails to land.
        android.util.Log.i("MainActivity", "MES missing - attempting silent app-ops grant via ADB")
        try {
            val adb = com.overdrive.app.launcher.AdbShellExecutor(this)
            StorageSetup.tryGrantViaAppOps(this, adb) { granted ->
                runOnUiThread { onAppOpsGrantResult(granted) }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "app-ops pre-grant threw, falling back to Settings: ${e.message}")
            fallbackToSettingsRequest()
        }
    }

    private fun onAppOpsGrantResult(granted: Boolean) {
        if (granted) {
            android.util.Log.i("MainActivity", "MES granted via app-ops; refreshing directories")
            val success = StorageSetup.setupDirectories()
            android.util.Log.i("MainActivity", "Post-grant setupDirectories success=$success")
            return
        }
        android.util.Log.w("MainActivity", "app-ops grant did not take; falling back to Settings UI")
        fallbackToSettingsRequest()
    }

    private fun fallbackToSettingsRequest() {
        when (StorageSetup.requestStoragePermission(this)) {
            StorageSetup.RequestOutcome.REQUESTED_RUNTIME,
            StorageSetup.RequestOutcome.OPENED_SETTINGS -> {
                // Result delivered to onRequestPermissionsResult / onActivityResult.
            }
            StorageSetup.RequestOutcome.UNAVAILABLE -> {
                android.util.Log.w(
                    "MainActivity",
                    "All-Files-Access UI unavailable; staying in legacy storage mode"
                )
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == StorageSetup.REQUEST_CODE_STORAGE_PERMISSION) {
            // Android 11+ Settings result
            if (StorageSetup.checkStoragePermission(this)) {
                android.util.Log.i("MainActivity", "Storage permission granted! Creating directories...")
                val success = StorageSetup.setupDirectories()
                if (success) {
                    logsViewModel.info("Storage", "Storage directories created (App is owner)")
                } else {
                    logsViewModel.warn("Storage", "Some directories could not be created")
                }
            } else {
                android.util.Log.e("MainActivity", "Storage permission denied by user")
                logsViewModel.error("Storage", "Storage permission denied - recordings may not work")
                Toast.makeText(this, getString(R.string.toast_storage_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == StorageSetup.REQUEST_CODE_RUNTIME_PERMISSION) {
            // Android 10 and below runtime permission result
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            android.util.Log.i("MainActivity", "Runtime permission result: granted=$granted")
            
            if (granted) {
                android.util.Log.i("MainActivity", "Storage permission granted! Creating directories...")
                val success = StorageSetup.setupDirectories()
                if (success) {
                    logsViewModel.info("Storage", "Storage directories created (App is owner)")
                } else {
                    logsViewModel.warn("Storage", "Some directories could not be created")
                }
            } else {
                android.util.Log.e("MainActivity", "Storage permission denied by user")
                logsViewModel.error("Storage", "Storage permission denied - recordings may not work")
                Toast.makeText(this, getString(R.string.toast_storage_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Auto-start Location Sidecar service for GPS tracking.
     * Uses daemonsViewModel's adbLauncher to avoid multiple ADB auth popups.
     * This runs silently in the background and is monitored by SentryDaemon.
     */
    private fun startLocationSidecarService() {
        logsViewModel.info("Location", "Auto-starting Location Sidecar service via ADB...")
        
        daemonsViewModel.startLocationSidecarService(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logsViewModel.debug("Location", message)
            }
            
            override fun onLaunched() {
                logsViewModel.info("Location", "Location Sidecar service started successfully")
            }
            
            override fun onError(error: String) {
                logsViewModel.error("Location", "Failed to start Location Sidecar: $error")
            }
        })
    }
    
    /**
     * Handle Location start intent from SentryDaemon or boot receiver.
     * This is called when the daemon detects Location service died and launches the app to restart it.
     */
    private fun handleLocationStartIntent(intent: android.content.Intent) {
        val action = intent.action
        val startLocation = intent.getBooleanExtra("start_location", false)
        
        if (action == "com.overdrive.app.START_LOCATION_ACTIVITY" || startLocation) {
            logsViewModel.info("Location", "Received Location start intent from SentryDaemon")
            
            // Start LocationSidecarService directly
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                logsViewModel.info("Location", "Auto-starting Location service...")
                try {
                    val serviceIntent = android.content.Intent(this, com.overdrive.app.services.LocationSidecarService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    logsViewModel.info("Location", "Location service start requested")
                } catch (e: Exception) {
                    logsViewModel.error("Location", "Failed to start Location service: ${e.message}")
                }
            }, 1000)
        }
    }
    
    /**
     * Setup the privileged shell (UID 1000) for daemon management.
     * This must be done before starting any daemons that need elevated privileges.
     */
    private fun setupPrivilegedShell() {
        logsViewModel.info("Shell", "Setting up privileged shell...")
        
        // PrivilegedShellSetup disabled — all daemons now run via ADB shell (UID 2000)
        // PrivilegedShellSetup.init(this)
        // 
        // PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
        //     override fun onSuccess() {
        //         runOnUiThread {
        //             logsViewModel.info("Shell", "✓ Privileged shell ready (UID 1000)")
        //             daemonStartupManager.checkAllDaemonStatuses()
        //         }
        //     }
        //     
        //     override fun onFailure(reason: String) {
        //         runOnUiThread {
        //             logsViewModel.warn("Shell", "⚠ Privileged shell setup failed: $reason")
        //             logsViewModel.info("Shell", "Falling back to ADB shell for daemon management")
        //             daemonStartupManager.checkAllDaemonStatuses()
        //         }
        //     }
        //     
        //     override fun onProgress(message: String) {
        //         runOnUiThread {
        //             logsViewModel.debug("Shell", "→ $message")
        //         }
        //     }
        // })
    }
    
    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        navigationRail = findViewById(R.id.navigationRail)
        navigationRailScroll = findViewById(R.id.navigationRailScroll)
        tvCurrentUrl = findViewById(R.id.tvCurrentUrl)
        urlBar = findViewById(R.id.urlBar)
        statusPill = findViewById(R.id.statusPill)
        statusIndicator = findViewById(R.id.statusIndicator)
        urlStatusDot = findViewById(R.id.urlStatusDot)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        
        // Brand version + device id used to live in the drawer header; in the
        // rail-based shell they're surfaced on the Dashboard card instead.
    }

    /** Apply a persisted screenshot-privacy change without recreating the activity. */
    fun applyScreenshotPrivacyMode(enabled: Boolean) {
        screenshotPrivacyController?.setEnabled(enabled)
    }
    
    private fun setupNavigation(savedInstanceState: Bundle?) {
        setSupportActionBar(toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        // Top-level destinations on the rail — no back arrow on these.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.dashboardFragment,
                R.id.genAiFragment,
                R.id.liveViewFragment,
                R.id.recordingsFragment,
                R.id.vehicleControlFragment,
                R.id.projectionFragment,
                R.id.tripsFragment,
                R.id.chargingFragment,
                R.id.automationsFragment,
                R.id.keyMappingFragment,
                R.id.integrationsFragment,
                R.id.roadSenseFragment,
                R.id.networkFragment,
                R.id.diagnosticsFragment,
                R.id.settingsFragment,
                R.id.settingsAboutFragment
            )
        )

        toolbar.setupWithNavController(navController, appBarConfiguration)

        setupCustomRail(savedInstanceState)
    }

    /**
     * Bind the custom navigation rail (LinearLayout of @layout/item_rail_destination
     * includes). Material's NavigationRailView caps menu items at 7 in
     * collapsed mode, so we use a plain vertical list of icon+label rows
     * instead. Each row's destination, icon, and label are wired here.
     *
     * Selection sync is driven from the NavController so deep links and
     * code-driven nav also light up the right rail item.
     */
    private fun setupCustomRail(savedInstanceState: Bundle?) {
        // Order matches the previous rail_menu.xml so user's mental model
        // stays the same.
        val items = listOf(
            RailItem("dashboard", R.id.railDestDashboard, R.id.dashboardFragment,
                R.drawable.ic_dashboard, R.string.rail_dashboard),
            RailItem(NavigationRailCatalog.ASSISTANT, R.id.railDestAssistant,
                R.id.genAiFragment, R.drawable.ic_smart_toy, R.string.rail_assistant),
            RailItem(NavigationRailCatalog.LIVE, R.id.railDestLive, R.id.liveViewFragment,
                R.drawable.ic_live, R.string.rail_live),
            RailItem(NavigationRailCatalog.RECORDINGS, R.id.railDestRecordings,
                R.id.recordingsFragment, R.drawable.ic_recording, R.string.rail_recordings,
                ownedDestinationIds = setOf(
                    R.id.videoPlayerFragment,
                    R.id.surveillanceSettingsWebFragment,
                    R.id.recordingSettingsWebFragment,
                )),
            RailItem(NavigationRailCatalog.VEHICLE, R.id.railDestVehicle,
                R.id.vehicleControlFragment,
                R.drawable.ic_vehicle_control, R.string.rail_vehicle),
            RailItem(NavigationRailCatalog.SEAT_POSITIONS, R.id.railDestSeatPositions,
                R.id.seatPositionsFragment,
                R.drawable.ic_seat_positions, R.string.rail_seat_positions),
            RailItem(NavigationRailCatalog.PROJECTION, R.id.railDestProjection,
                R.id.projectionFragment,
                R.drawable.ic_projection, R.string.rail_projection),
            RailItem(NavigationRailCatalog.TRIPS, R.id.railDestTrips, R.id.tripsFragment,
                R.drawable.ic_trips, R.string.rail_trips),
            RailItem(NavigationRailCatalog.CHARGING, R.id.railDestCharging,
                R.id.chargingFragment,
                R.drawable.ic_charging, R.string.rail_charging),
            RailItem(NavigationRailCatalog.AUTOMATIONS, R.id.railDestAutomations,
                R.id.automationsFragment,
                R.drawable.ic_automations, R.string.rail_automations),
            RailItem(NavigationRailCatalog.KEY_MAPPING, R.id.railDestKeyMapping,
                R.id.keyMappingFragment,
                R.drawable.ic_key_mapping, R.string.rail_key_mapping),
            RailItem(NavigationRailCatalog.INTEGRATIONS, R.id.railDestIntegrations,
                R.id.integrationsFragment, R.drawable.ic_integrations,
                R.string.rail_integrations,
                ownedDestinationIds = setOf(
                    R.id.telegramSettingsFragment,
                    R.id.abrpSettingsFragment,
                    R.id.mqttFragment,
                    R.id.bydCloudFragment,
                )),
            RailItem(NavigationRailCatalog.ROAD_SENSE, R.id.railDestRoadSense,
                R.id.roadSenseFragment,
                R.drawable.ic_roadsense, R.string.rail_roadsense),
            // Hazard Map is a standalone Activity, not a nav-graph fragment,
            // so it launches via startActivity (destinationId = 0).
            RailItem(NavigationRailCatalog.MAP, R.id.railDestMap, 0,
                R.drawable.ic_roadsense_map, R.string.rail_hazard_map,
                launchActivity = com.overdrive.app.navmap.RoadSenseMapActivity::class.java),
            RailItem(NavigationRailCatalog.NETWORK, R.id.railDestNetwork,
                R.id.networkFragment,
                R.drawable.ic_hotspot, R.string.rail_network),
            RailItem(NavigationRailCatalog.DIAGNOSTICS, R.id.railDestDiagnostics,
                R.id.diagnosticsFragment,
                R.drawable.ic_diagnostics, R.string.rail_diagnostics,
                ownedDestinationIds = setOf(R.id.adbConsoleFragment)),
            RailItem("settings", R.id.railDestSettings, R.id.settingsFragment,
                R.drawable.ic_settings, R.string.rail_settings,
                ownedDestinationIds = setOf(
                    R.id.daemonsFragment,
                    R.id.settingsSecurityFragment,
                )),
            RailItem("about", R.id.railDestAbout, R.id.settingsAboutFragment,
                R.drawable.ic_update, R.string.settings_section_about)
        )
        railItems = items

        val languageClick = View.OnClickListener {
            com.overdrive.app.ui.dialog.LanguagePickerDialog.show(this) {
                recreate()
            }
        }
        val helpClick = View.OnClickListener { startOnboardingReplay() }
        val actions = listOf(
            RailActionItem(
                R.id.railLanguageButton,
                R.drawable.ic_language,
                R.string.settings_language_label,
                languageClick
            ),
            RailActionItem(
                R.id.railHelpButton,
                R.drawable.ic_help,
                R.string.onboarding_help_cd,
                helpClick
            )
        )
        railActionItems = actions

        // Bind icon + label and click handler per row.
        items.forEach { item ->
            val row = navigationRail.findViewById<View>(item.rowId) ?: return@forEach
            row.findViewById<ImageView>(R.id.railItemIcon)?.setImageResource(item.iconRes)
            row.findViewById<TextView>(R.id.railItemLabel)?.setText(item.labelRes)
            val label = getString(item.labelRes)
            row.contentDescription = label
            TooltipCompat.setTooltipText(row, label)
            row.setOnClickListener {
                val activity = item.launchActivity
                if (activity != null) {
                    startActivity(Intent(this, activity))
                } else {
                    navigateToRailDestination(item.destinationId)
                }
            }
        }

        actions.forEach { item ->
            val row = navigationRail.findViewById<View>(item.rowId) ?: return@forEach
            row.findViewById<ImageView>(R.id.railItemIcon)?.setImageResource(item.iconRes)
            row.findViewById<TextView>(R.id.railItemLabel)?.setText(item.labelRes)
            val label = getString(item.labelRes)
            row.contentDescription = label
            TooltipCompat.setTooltipText(row, label)
            row.setOnClickListener(item.onClick)
        }

        // Portrait keeps these actions in the toolbar. Landscape renders
        // them as full rail rows after the destination list.
        findViewById<View>(R.id.toolbarLanguageButton)?.setOnClickListener(languageClick)
        findViewById<View>(R.id.toolbarHelpButton)?.setOnClickListener(helpClick)

        val expandButton = navigationRail.findViewById<ImageButton>(R.id.railExpandButton)
        expandButton?.setOnClickListener {
            setNavigationRailExpanded(!navigationRailExpanded, animate = true)
        }
        navigationRailScroll.onRailSwipe = { action ->
            when (action) {
                NavigationRailSwipePolicy.Action.EXPAND ->
                    setNavigationRailExpanded(true, animate = true)
                NavigationRailSwipePolicy.Action.COLLAPSE ->
                    setNavigationRailExpanded(false, animate = true)
                NavigationRailSwipePolicy.Action.NONE -> Unit
            }
        }
        setNavigationRailExpanded(
            savedInstanceState?.getBoolean(KEY_RAIL_EXPANDED, true) ?: true,
            animate = false
        )
        refreshNavigationRailVisibility()

        // Selection sync — light up the row whose destinationId matches
        // the current nav destination (or any of its ancestors).
        // Only fragment-backed rows participate in sync. Activity-launch
        // rows (launchActivity != null, destinationId == 0) never become the
        // NavController's current destination, so they are excluded here to
        // avoid a spurious highlight and to keep destinationId == 0 from
        // colliding with the matcher.
        val syncItems = items.filter { it.launchActivity == null && it.destinationId != 0 }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            var node: androidx.navigation.NavDestination? = destination
            while (node != null) {
                val nodeId = node.id
                val match = syncItems.firstOrNull {
                    it.destinationId == nodeId || nodeId in it.ownedDestinationIds
                }
                if (match != null) {
                    applyNavigationRailVisibility(match.key)
                    syncItems.forEach { item ->
                        navigationRail.findViewById<View>(item.rowId)?.isSelected =
                            (item.key == match.key)
                    }
                    return@addOnDestinationChangedListener
                }
                node = node.parent
            }
            syncItems.forEach { item ->
                navigationRail.findViewById<View>(item.rowId)?.isSelected = false
            }
            applyNavigationRailVisibility(activeKey = null)
        }

    }

    private data class RailItem(
        val key: String,
        val rowId: Int,
        val destinationId: Int,
        val iconRes: Int,
        val labelRes: Int,
        val ownedDestinationIds: Set<Int> = emptySet(),
        // When non-null, the row launches this Activity via startActivity()
        // instead of navigating to a nav-graph fragment. Such rows have no
        // NavController destination (destinationId == 0) and are skipped by
        // the selection-sync matcher.
        val launchActivity: Class<*>? = null
    )

    /**
     * Re-applies persisted pin/unpin choices. A hidden destination opened by a
     * deep link is shown temporarily while active so selection and navigation
     * context remain visible without silently changing the user's preference.
     */
    fun refreshNavigationRailVisibility() {
        if (!::navigationRail.isInitialized || !::navController.isInitialized) return
        val destinationId = navController.currentDestination?.id
        val activeKey = destinationId?.let { id ->
            railItems.firstOrNull {
                it.destinationId == id || id in it.ownedDestinationIds
            }?.key
        }
        applyNavigationRailVisibility(activeKey)
    }

    private fun applyNavigationRailVisibility(activeKey: String?) {
        val customizableKeys = NavigationRailCatalog.customizableKeys
        val visibleKeys = PreferencesManager.getVisibleNavigationKeys(customizableKeys)
        railItems.forEach { item ->
            val fixed = item.key !in customizableKeys
            val visible = fixed || item.key in visibleKeys || item.key == activeKey
            navigationRail.findViewById<View>(item.rowId)?.visibility =
                if (visible) View.VISIBLE else View.GONE
        }
    }

    private data class RailActionItem(
        val rowId: Int,
        val iconRes: Int,
        val labelRes: Int,
        val onClick: View.OnClickListener
    )

    /**
     * Expanded is the default so destinations are named without requiring a gesture.
     * A left swipe (or chevron button) still collapses to the compact 80dp icon rail;
     * right reverses it.
     *
     * Rows are switched to their expanded (horizontal) form up-front and stay
     * there for the whole slide. Both widths put the icon centre on the same
     * 40dp line, so holding one form keeps the icons visually static instead of
     * letting them drift to the centre of the widening rail and snap back at
     * the end. Labels then only need to cross-fade.
     */
    private fun setNavigationRailExpanded(expanded: Boolean, animate: Boolean) {
        val stateChanged = navigationRailExpanded != expanded
        navigationRailExpanded = expanded
        // Capture the in-flight width before cancelling so an interrupted slide
        // resumes from where it is rather than jumping to a stale edge.
        val startWidth = navigationRailScroll.width
            .takeIf { it > 0 } ?: navigationRailScroll.layoutParams.width
        // Clear the field BEFORE cancelling: cancel() delivers onAnimationEnd
        // synchronously, and the identity check there is what stops the outgoing
        // slide from settling rows to a state this call is about to replace.
        val outgoing = navigationRailAnimator
        navigationRailAnimator = null
        outgoing?.cancel()

        val targetWidth = dp(
            if (expanded) RAIL_EXPANDED_WIDTH_DP else RAIL_COMPACT_WIDTH_DP
        )
        if (!animate || !stateChanged || !navigationRailScroll.isLaidOut) {
            updateRailExpandButton(expanded, animate = false, durationMs = 0L)
            navigationRailScroll.layoutParams =
                navigationRailScroll.layoutParams.apply { width = targetWidth }
            // force: this is the assert-the-truth path (first bind, rotation,
            // density change), so it must re-apply even if the memo agrees —
            // dp() padding is density-derived and can go stale.
            applyRailItemLayout(expanded, force = true)
            if (expanded) setRailLabelsVisible(true, durationMs = 0L)
            return
        }

        // Interrupting a slide part-way shortens the remaining travel, so scale
        // the duration to it — otherwise a re-toggle crawls the last few dp.
        val travel = Math.abs(targetWidth - startWidth)
        val fullTravel = dp(RAIL_EXPANDED_WIDTH_DP - RAIL_COMPACT_WIDTH_DP)
        val slideMs = (
            if (fullTravel > 0) RAIL_ANIMATION_MS * travel / fullTravel
            else RAIL_ANIMATION_MS
            ).coerceAtLeast(RAIL_ANIMATION_MIN_MS)

        updateRailExpandButton(expanded, animate = true, durationMs = slideMs)
        // Expanded row geometry for the entire slide — see the kdoc above.
        applyRailItemLayout(true)
        setRailLabelsVisible(expanded, durationMs = slideMs)

        navigationRailAnimator = ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = slideMs
            interpolator = RAIL_EASING
            addUpdateListener { animator ->
                navigationRailScroll.layoutParams =
                    navigationRailScroll.layoutParams.apply {
                        width = animator.animatedValue as Int
                    }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Identity separates "this slide finished" from "this slide
                    // was cancelled by a re-toggle" — the caller clears the field
                    // before cancelling, so a superseded slide bails here.
                    if (navigationRailAnimator !== this@apply) return
                    navigationRailAnimator = null
                    // Expanding needs no settle: the rows already hold expanded
                    // geometry, so a completed expand costs zero relayout.
                    if (!expanded) applyRailItemLayout(false)
                }
            })
            start()
        }
    }

    /**
     * Cross-fade the destination labels. Rows stay in their expanded form for
     * the whole slide, so the labels are laid out either way — fading them
     * beats flipping GONE/VISIBLE at the boundary, which is what made them pop.
     * The collapse settle in [applyRailRowLayout] takes them back to GONE.
     *
     * A duration of 0 applies the end state immediately.
     */
    private fun setRailLabelsVisible(visible: Boolean, durationMs: Long) {
        forEachRailRow { row, _ ->
            val label = row.findViewById<TextView>(R.id.railItemLabel) ?: return@forEachRailRow
            label.animate().cancel()
            if (durationMs <= 0L) {
                label.alpha = if (visible) 1f else 0f
                label.visibility = View.VISIBLE
                return@forEachRailRow
            }
            // Coming from GONE the alpha is whatever it was left at (the XML
            // default is opaque), so seed it to 0 or the fade-in would be a pop.
            // An interrupted slide is already VISIBLE mid-fade — resume from
            // its current alpha instead.
            if (visible && label.visibility != View.VISIBLE) label.alpha = 0f
            label.visibility = View.VISIBLE
            label.animate()
                .alpha(if (visible) 1f else 0f)
                // Fade in over the back half, once the rail has the room for
                // text; fade out over the front half so it never squeezes into
                // a narrowing rail.
                .setStartDelay(if (visible) durationMs / 2 else 0L)
                .setDuration(durationMs / 2)
                .setInterpolator(RAIL_EASING)
                .start()
        }
    }

    /** Drops any queued label / chevron view animations. */
    private fun cancelRailViewAnimations() {
        forEachRailRow { row, _ ->
            row.findViewById<TextView>(R.id.railItemLabel)?.animate()?.cancel()
        }
        navigationRail.findViewById<ImageButton>(R.id.railExpandButton)?.animate()?.cancel()
    }

    /** Walks every rail row — destinations then secondary actions. */
    private inline fun forEachRailRow(action: (LinearLayout, Int) -> Unit) {
        railItems.forEach { item ->
            navigationRail.findViewById<LinearLayout>(item.rowId)?.let {
                action(it, item.labelRes)
            }
        }
        railActionItems.forEach { item ->
            navigationRail.findViewById<LinearLayout>(item.rowId)?.let {
                action(it, item.labelRes)
            }
        }
    }

    /**
     * Switch every row between the compact (vertical) and expanded (horizontal)
     * form. Each call relayouts ~17 rows, so it no-ops when the rows already
     * hold the requested form — during a slide that keeps the work off the
     * first and last frames, where a dropped frame is most visible.
     *
     * [force] skips the memo for paths that must assert absolute state: the
     * initial bind, and rotation, where the inflated views are new (landscape
     * even carries two extra rows) so the memo describes a dead hierarchy.
     */
    private fun applyRailItemLayout(expanded: Boolean, force: Boolean = false) {
        if (!force && navigationRailRowsExpandedForm == expanded) return
        navigationRailRowsExpandedForm = expanded
        forEachRailRow { row, labelRes -> applyRailRowLayout(row, labelRes, expanded) }
    }

    private fun applyRailRowLayout(row: LinearLayout, labelRes: Int, expanded: Boolean) {
        val label = row.findViewById<TextView>(R.id.railItemLabel) ?: return
        val horizontalPadding = if (expanded) dp(12) else 0
        val verticalPadding = dp(6)

        row.orientation =
            if (expanded) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        row.gravity =
            if (expanded) Gravity.CENTER_VERTICAL else Gravity.CENTER
        row.minimumHeight = dp(56)
        row.setPadding(
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )

        val labelParams = label.layoutParams as LinearLayout.LayoutParams
        labelParams.width =
            if (expanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        labelParams.weight = if (expanded) 1f else 0f
        labelParams.marginStart = if (expanded) dp(12) else 0
        labelParams.topMargin = if (expanded) 0 else dp(4)
        label.layoutParams = labelParams
        label.gravity =
            if (expanded) Gravity.START or Gravity.CENTER_VERTICAL
            else Gravity.CENTER_HORIZONTAL
        // Visibility is owned by setRailLabelsVisible during a slide; only the
        // settled compact state drops the label out of layout entirely.
        if (!expanded) {
            label.animate().cancel()
            label.alpha = 0f
            label.visibility = View.GONE
        }

        // Tooltip only earns its place while the label is hidden. contentDescription
        // is set once at bind time in setupCustomRail and never changes.
        TooltipCompat.setTooltipText(row, if (expanded) null else getString(labelRes))
    }

    /**
     * Chevron points right when collapsed, left when expanded. Rotating one
     * drawable rather than swapping two keeps the affordance continuous with
     * the slide instead of blinking to a different asset mid-motion.
     */
    private fun updateRailExpandButton(
        expanded: Boolean,
        animate: Boolean,
        durationMs: Long
    ) {
        val button = navigationRail.findViewById<ImageButton>(R.id.railExpandButton)
            ?: return
        val descriptionRes = if (expanded) R.string.rail_collapse else R.string.rail_expand
        val description = getString(descriptionRes)
        button.contentDescription = description
        TooltipCompat.setTooltipText(button, description)

        button.animate().cancel()
        // The vector auto-mirrors in RTL, so expansion alone controls rotation.
        // Translation still mirrors because the rail grows from the other edge.
        //
        // Read the direction off the Configuration, not the View: this first runs
        // from onCreate, and a View resolves its layoutDirection during layout —
        // until then it reports LTR, which left the collapsed chevron pointing
        // into the rail in RTL locales until the first toggle.
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val targetRotation = if (expanded) 180f else 0f
        // Translate rather than re-gravity: the button is centred by the rail's
        // own gravity, so a gravity flip would jump. Offset runs from where the
        // expanded rail centres it (half of 216dp) out to the trailing inset.
        val trailingOffset =
            dp(RAIL_EXPANDED_WIDTH_DP / 2 - RAIL_CHEVRON_EDGE_INSET_DP).toFloat()
        val targetTranslation = when {
            !expanded -> 0f
            rtl -> -trailingOffset
            else -> trailingOffset
        }
        if (!animate) {
            button.rotation = targetRotation
            button.translationX = targetTranslation
            return
        }
        button.animate()
            .rotation(targetRotation)
            .translationX(targetTranslation)
            .setDuration(durationMs)
            .setInterpolator(RAIL_EASING)
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Navigate to a rail destination, popping any sub-pages so the tab
     * resets to its root. Uses M3 expressive fade-through (the incoming
     * destination scales up slightly while the outgoing fades) so the
     * switch reads as motion, not just a cross-fade.
     */
    private fun navigateToRailDestination(destinationId: Int) {
        // Reselecting an already-visible top-level screen is a no-op. Besides avoiding a
        // pointless transition, this prevents Surface-backed screens such as Projection from
        // being torn down and recreated while a resize transaction is still settling.
        if (navController.currentDestination?.id == destinationId) return
        // A late click can race Activity state saving (for example while a configuration
        // transition is settling). Navigation cannot commit after that point; FragmentManager
        // would throw. Stash the target and re-drive it from onResume (once the Activity is
        // resumed and state is no longer saved) so the user's tap isn't silently dropped.
        if (supportFragmentManager.isStateSaved) {
            pendingRailDestination = destinationId
            return
        }
        pendingRailDestination = null
        val options = androidx.navigation.NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(false)
            .setPopUpTo(destinationId, /* inclusive = */ false, /* saveState = */ false)
            .setEnterAnim(R.anim.m3_fade_through_enter)
            .setExitAnim(R.anim.m3_fade_through_exit)
            .setPopEnterAnim(R.anim.m3_fade_through_enter)
            .setPopExitAnim(R.anim.m3_fade_through_exit)
            .build()
        try {
            navController.navigate(destinationId, /* args = */ null, options)
        } catch (_: IllegalArgumentException) {
            // Destination not in graph — defensive only.
        } catch (_: IllegalStateException) {
            // FragmentManager state may have been saved between the guard and navigate().
            pendingRailDestination = destinationId
        }
    }
    
    private fun setupCopyButton() {
        val copyUrl = View.OnClickListener {
            val url = mainViewModel.currentUrl.value ?: return@OnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.clip_label_url), url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_url_copied_short), Toast.LENGTH_SHORT).show()
        }
        btnCopyUrl.setOnClickListener(copyUrl)
        findViewById<View>(R.id.statusPill)?.setOnClickListener(copyUrl)
    }
    
    private fun setupLogListener() {
        // Wire LogManager to LogsViewModel
        LogManager.setLogListener(object : LogManager.LogListener {
            override fun onLog(tag: String, message: String, level: LogLevel) {
                // Convert LogManager.LogLevel to UI LogLevel
                val uiLevel = when (level) {
                    LogLevel.DEBUG -> com.overdrive.app.ui.model.LogLevel.DEBUG
                    LogLevel.INFO -> com.overdrive.app.ui.model.LogLevel.INFO
                    LogLevel.WARN -> com.overdrive.app.ui.model.LogLevel.WARN
                    LogLevel.ERROR -> com.overdrive.app.ui.model.LogLevel.ERROR
                }
                logsViewModel.addLog(tag, message, uiLevel)
            }
        })
    }
    
    private fun observeViewModels() {
        // Observe tunnel URL from cloudflared controller
        daemonsViewModel.cloudflaredController.tunnelUrl.observe(this) { url ->
            mainViewModel.setTunnelUrl(url)
            updateUrlDisplay()
        }
        
        // Observe tunnel URL from zrok controller
        daemonsViewModel.zrokController.tunnelUrl.observe(this) { url ->
            // Zrok URL takes precedence if available
            if (!url.isNullOrEmpty()) {
                mainViewModel.setTunnelUrl(url)
            }
            updateUrlDisplay()
        }

        // Observe tunnel URL from tailscale controller
        daemonsViewModel.tailscaleController.tunnelUrl.observe(this) { url ->
            // Tailscale is lowest priority — only adopt its URL when no higher-priority tunnel has one
            val zrokUrl = daemonsViewModel.zrokController.tunnelUrl.value
            val cloudflaredUrl = daemonsViewModel.cloudflaredController.tunnelUrl.value
            if (zrokUrl.isNullOrEmpty() && cloudflaredUrl.isNullOrEmpty() && !url.isNullOrEmpty()) {
                mainViewModel.setTunnelUrl(url)
            }
            updateUrlDisplay()
        }
        
        // Observe daemon states for tunnel status (cloudflared, zrok or tailscale)
        daemonsViewModel.daemonStates.observe(this) { states ->
            // A daemon transition must update the label as well as the dot.
            // Previously STOPPED initial state changed only the dot, leaving
            // the XML placeholder "Connecting..." visible indefinitely.
            updateUrlDisplay()
        }
        updateUrlDisplay()
    }
    
    private fun updateUrlDisplay() {
        val zrokUrl = daemonsViewModel.zrokController.tunnelUrl.value
        val cloudflaredUrl = daemonsViewModel.cloudflaredController.tunnelUrl.value
        val tailscaleUrl = daemonsViewModel.tailscaleController.tunnelUrl.value
        val states = daemonsViewModel.daemonStates.value
        val display = TunnelDisplayPolicy.resolve(
            zrokUrl,
            cloudflaredUrl,
            tailscaleUrl,
            states?.get(DaemonType.ZROK_TUNNEL)?.status,
            states?.get(DaemonType.CLOUDFLARED_TUNNEL)?.status,
            states?.get(DaemonType.TAILSCALE_TUNNEL)?.status,
        )

        statusPill.visibility = if (display.isVisible) View.VISIBLE else View.GONE
        btnCopyUrl.visibility = if (display.isOnline) View.VISIBLE else View.GONE
        tvCurrentUrl.text = when (display.kind) {
            TunnelDisplayPolicy.Kind.STARTING_ZROK ->
                getString(R.string.dashboard_starting_zrok)
            TunnelDisplayPolicy.Kind.STARTING_CLOUDFLARED ->
                getString(R.string.dashboard_starting_cloudflared)
            TunnelDisplayPolicy.Kind.STARTING_TAILSCALE ->
                getString(R.string.dashboard_starting_tailscale)
            TunnelDisplayPolicy.Kind.WAITING_FOR_URL ->
                getString(R.string.dashboard_waiting_url)
            TunnelDisplayPolicy.Kind.STOPPING ->
                getString(R.string.dashboard_tunnel_stopping)
            TunnelDisplayPolicy.Kind.FAILED ->
                getString(R.string.dashboard_tunnel_failed)
            TunnelDisplayPolicy.Kind.ONLINE -> display.url
            TunnelDisplayPolicy.Kind.HIDDEN -> getString(R.string.dashboard_no_tunnel)
        }
        val drawableRes = when (display.kind) {
            TunnelDisplayPolicy.Kind.ONLINE -> R.drawable.status_dot_online
            TunnelDisplayPolicy.Kind.STARTING_ZROK,
            TunnelDisplayPolicy.Kind.STARTING_CLOUDFLARED,
            TunnelDisplayPolicy.Kind.STARTING_TAILSCALE,
            TunnelDisplayPolicy.Kind.WAITING_FOR_URL,
            TunnelDisplayPolicy.Kind.STOPPING -> R.drawable.status_dot_starting
            TunnelDisplayPolicy.Kind.FAILED,
            TunnelDisplayPolicy.Kind.HIDDEN -> R.drawable.status_dot_offline
        }
        statusIndicator.setBackgroundResource(drawableRes)
        urlStatusDot.setBackgroundResource(drawableRes)
        mainViewModel.setCurrentUrl(display.url)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    
    @Deprecated("Default back behavior is handled by NavController + the activity")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    
    // ==================== Camera Reconfiguration ====================
    
    /**
     * No-op since the rail shell doesn't have a drawer-side menu item to
     * retitle — Camera probe status is shown inside the dialog itself.
     */
    private fun updateCameraProbeMenuItem() { /* intentionally empty */ }


    // ==================== Camera Mapping data model ====================

    private data class CameraRoleOption(val key: String, val label: String)

    private data class CameraPreviewCandidate(
        val id: String,
        val kind: String,
        val label: String,
        val cameraId: Int?,
        val slice: String?,
        val view: String?,
        val width: Int,
        val height: Int
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("kind", kind)
            cameraId?.let { put("cameraId", it) }
            slice?.let { put("slice", it) }
            view?.let { put("view", it) }
        }
    }

    private data class CameraMappingState(
        val summary: String,
        val roles: List<CameraRoleOption>,
        val candidates: List<CameraPreviewCandidate>,
        val currentMappings: Map<String, String>,
        // Currently-saved manual camera ID, or null when auto / unset.
        // Drives the manual-camera-ID radio group's initial selection.
        val manualCameraId: Int?,
        val isManualOverride: Boolean,
        // Persisted ingestion mode. "default" = legacy ImageReader + 4-strip
        // → 2x2 rearrangement. "dilink4" = oem SurfaceTexture passthrough.
        // Absent in older config → "default".
        val cameraMode: String,
        // DiLink 4-only path that leaves APA output untouched and consumes
        // preview port 0 exactly as supplied by the HAL.
        val dilink4PassiveApaMode: Boolean,
        // GL fragment-shader red-pixel suppression for the HAL "calibration
        // failed" overlay. Cosmetic mitigation only.
        val dilink4RedMask: Boolean,
        // Resolved pano camera id (from /api/surveillance/config). Drives the
        // "Auto — currently using id N (pano is id M)" sub-label on the OEM
        // Dashcam card; -1 when probing or unconfigured.
        val panoCameraId: Int,
        // OEM Dashcam camera id (camera.oemDashcamCameraId). -1 when unset
        // (Auto). Used as initial radio selection when manualOverride is true.
        val oemDashcamCameraId: Int,
        // Whether the user has manually overridden the OEM dashcam id
        // (camera.oemDashcamManualOverride). false = Auto = infer pano^1.
        val oemDashcamManualOverride: Boolean,
        // Opt-in for the destructive dual-camera concurrency probe
        // (camera.concurrentAvmProbeEnabled). Default false = never auto-probe.
        val concurrentAvmProbeEnabled: Boolean
    )

    /**
     * Diagnostics → Camera mapping entry point. Pulls resolved camera state
     * from the daemon and shows the mapping dialog. Falls back to a toast on
     * fetch failure rather than opening an empty dialog.
     */
    private fun onReconfigureCameraClicked() {
        Thread {
            val state = fetchCameraMappingState()
            runOnUiThread {
                // Bail if the activity tore down during the 4-7 s fetch
                // window — without this guard the captured `this@MainActivity`
                // reference would hold the destroyed activity in memory and
                // showCameraMappingDialog could crash with
                // "Activity has been destroyed" on layoutInflater.
                if (isFinishing || isDestroyed) {
                    // Don't strand a pending onboarding coach attached to a dead fetch.
                    pendingCameraOnboardingCoach = null
                    return@runOnUiThread
                }
                if (state == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Fetch failed → the dialog won't open → the coach's attachToDialog
                    // never fires. Clear it so it can't attach to a later manual open;
                    // the coach's own failsafe timeout will defer the onboarding chapter.
                    pendingCameraOnboardingCoach = null
                    return@runOnUiThread
                }
                showCameraMappingDialog(state)
            }
        }.start()
    }

    /**
     * Single GET against /api/surveillance/config — server merges the
     * resolved camera summary into the response so the dialog can render
     * roles, candidates, and current mappings without a second round-trip.
     */
    private fun fetchCameraMappingState(): CameraMappingState? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/api/surveillance/config", "GET", 3000, 4000)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val config = org.json.JSONObject(body).optJSONObject("config") ?: return null

            val panoCameraId = config.optInt("panoCameraId", config.optInt("cameraId", -1))
            val panoWidth = config.optInt("panoWidth", -1)
            val panoHeight = config.optInt("panoHeight", -1)
            val summary = if (panoCameraId >= 0 && panoWidth > 0 && panoHeight > 0) {
                getString(
                    R.string.camera_mapping_summary_format,
                    panoCameraId, panoWidth, panoHeight
                )
            } else {
                getString(R.string.camera_mapping_summary_probing)
            }

            val roles = mutableListOf<CameraRoleOption>()
            val roleArray = config.optJSONArray("cameraRoleOptions") ?: org.json.JSONArray()
            for (i in 0 until roleArray.length()) {
                val item = roleArray.optJSONObject(i) ?: continue
                roles += CameraRoleOption(
                    item.optString("key", ""),
                    item.optString("label", item.optString("key", "Role"))
                )
            }

            val candidates = mutableListOf<CameraPreviewCandidate>()
            val candidateArray = config.optJSONArray("cameraPreviewCandidates") ?: org.json.JSONArray()
            for (i in 0 until candidateArray.length()) {
                val item = candidateArray.optJSONObject(i) ?: continue
                candidates += CameraPreviewCandidate(
                    id = item.optString("id", "candidate-$i"),
                    kind = item.optString("kind", "panoramicSlice"),
                    label = item.optString("label", item.optString("id", "Candidate")),
                    cameraId = if (item.has("cameraId")) item.optInt("cameraId") else null,
                    slice = if (item.has("slice")) item.optString("slice") else null,
                    view = if (item.has("view")) item.optString("view") else null,
                    width = item.optInt("previewWidth", 1280),
                    height = item.optInt("previewHeight", 720)
                )
            }

            val mappings = mutableMapOf<String, String>()
            val mappingsJson = config.optJSONObject("cameraRoleMappings")
            roles.forEach { role ->
                val source = mappingsJson?.optJSONObject(role.key)
                val sourceId = if (source != null && source.has("id")) source.optString("id") else null
                if (!sourceId.isNullOrEmpty()) {
                    mappings[role.key] = sourceId
                }
            }

            // Manual override state — daemon merges these into the same
            // /api/surveillance/config response (see SurveillanceApiHandler:546).
            // cameraId == -1 + manualOverride==false is the "auto" state.
            val rawManualId = config.optInt("cameraId", -1)
            val manualOverride = config.optBoolean("cameraManualOverride", false)
            val manualCameraId = if (manualOverride && rawManualId in 0..5) rawManualId else null

            val cameraMode = config.optString("cameraMode", "default")
                .lowercase(java.util.Locale.US)
                .let { if (it == "dilink4") "dilink4" else "default" }

            // OEM Dashcam state lives in the same camera.* UCM section but is
            // not (yet) merged into /api/surveillance/config. Read it directly
            // from UnifiedConfigManager — the daemon writes these from shell
            // UID, so app-side reads need a forceReload to dodge the stale
            // per-UID cache (see feedback_unified_config_force_reload.md).
            com.overdrive.app.config.UnifiedConfigManager.forceReload()
            val cameraSection = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera") ?: org.json.JSONObject()
            val oemDashcamCameraId = cameraSection.optInt("oemDashcamCameraId", -1)
            val oemDashcamManualOverride = cameraSection.optBoolean(
                "oemDashcamManualOverride", false)
            val concurrentAvmProbeEnabled = cameraSection.optBoolean(
                "concurrentAvmProbeEnabled", false)

            CameraMappingState(
                summary = summary,
                roles = roles,
                candidates = candidates,
                currentMappings = mappings,
                manualCameraId = manualCameraId,
                isManualOverride = manualOverride,
                cameraMode = cameraMode,
                dilink4PassiveApaMode = config.optBoolean(
                    "dilink4PassiveApaMode", false),
                dilink4RedMask = config.optBoolean("dilink4RedMask", false),
                panoCameraId = panoCameraId,
                oemDashcamCameraId = oemDashcamCameraId,
                oemDashcamManualOverride = oemDashcamManualOverride,
                concurrentAvmProbeEnabled = concurrentAvmProbeEnabled
            )
        } catch (e: Exception) {
            logsViewModel.error("Camera", "Failed to load camera mapping state: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun showCameraMappingDialog(state: CameraMappingState) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_camera_mapping, null)
        val summaryView = dialogView.findViewById<TextView>(R.id.tvCameraProfileSummary)
        val roleSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerCameraRole)
        val currentMappingView = dialogView.findViewById<TextView>(R.id.tvCurrentRoleMapping)
        val candidateLabelView = dialogView.findViewById<TextView>(R.id.tvCandidateLabel)
        val previewImageView = dialogView.findViewById<ImageView>(R.id.ivCameraCandidatePreview)
        val previewPlaceholderView = dialogView.findViewById<TextView>(R.id.tvCameraPreviewPlaceholder)
        val prevButton = dialogView.findViewById<View>(R.id.btnPrevCandidate)
        val nextButton = dialogView.findViewById<View>(R.id.btnNextCandidate)
        val saveMappingButton = dialogView.findViewById<View>(R.id.btnSaveCameraRoleMapping)
        val clearMappingButton = dialogView.findViewById<View>(R.id.btnClearCameraRoleMapping)
        val manualCameraGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgManualCameraId)
        val currentManualCameraView = dialogView.findViewById<TextView>(R.id.tvCurrentManualCamera)
        val saveManualCameraButton = dialogView.findViewById<View>(R.id.btnSaveManualCameraId)
        val cameraModeGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgCameraMode)
        val currentCameraModeView = dialogView.findViewById<TextView>(R.id.tvCurrentCameraMode)
        val saveCameraModeButton = dialogView.findViewById<View>(R.id.btnSaveCameraMode)
        val saveDilink4TweaksButton = dialogView.findViewById<View>(R.id.btnSaveCameraDilink4Tweaks)
        val dilink4PassiveApaSwitch = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swCameraDilink4PassiveApaMode)
        val dilink4RedMaskSwitch = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swCameraDilink4RedMask)
        val oemDashcamGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgOemDashcamId)
        val currentOemDashcamView = dialogView.findViewById<TextView>(R.id.tvCurrentOemDashcam)
        val saveOemDashcamButton = dialogView.findViewById<View>(R.id.btnSaveOemDashcamId)
        val concurrentProbeSwitch = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swConcurrentAvmProbe)
        summaryView.text = state.summary

        val roleAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            state.roles.map { it.label }
        )
        roleSpinner.adapter = roleAdapter

        var currentRoleIndex = 0
        var currentCandidateIndex = 0
        val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var dialogClosed = false
        var activePreviewCandidateId: String? = null
        // Single in-flight preview at a time. The daemon gates direct opens
        // and panoramic-slice JPEG cache reads behind a single-flight
        // cold-start (returns 503 Retry-After=2s while warming up). Keeping
        // one outstanding request avoids stacking when the user holds Next.
        var fetchInFlight = false
        // The previously-displayed bitmap. ImageView keeps a reference via
        // its Drawable but won't recycle the old one when setImageBitmap
        // replaces it; without manual recycle a 2s polling cycle leaks
        // ~30+ ARGB_8888 1280×960 bitmaps per minute (4 MB each = 120 MB/min)
        // and OOMs the dialog on the BYD heap.
        var previousPreviewBitmap: android.graphics.Bitmap? = null

        fun mappedCandidateIndexForRole(roleKey: String): Int {
            val mappedId = state.currentMappings[roleKey] ?: return 0
            return state.candidates.indexOfFirst { it.id == mappedId }
                .let { if (it >= 0) it else 0 }
        }

        // Per-role explainer: this one spinner mixes two role families —
        // direct-camera roles (windshield / cabin: WHICH camera feeds a
        // layout view) and the four pano slice roles (WHERE each direction
        // lives inside the mosaic). The explainer keeps them distinct, and
        // for windshield explicitly decouples it from the Forward-dashcam
        // recorder ID picker. Null-safe: absent view = no behaviour change.
        val roleExplainerView = dialogView.findViewById<TextView>(R.id.tvRoleExplainer)
        fun updateRoleExplainer() {
            val key = state.roles.getOrNull(currentRoleIndex)?.key ?: return
            roleExplainerView?.setText(
                when {
                    key.equals("windshield", ignoreCase = true) ->
                        R.string.camera_role_explainer_windshield
                    key.equals("cabin", ignoreCase = true) ->
                        R.string.camera_role_explainer_cabin
                    else -> R.string.camera_role_explainer_slice
                }
            )
        }

        fun updateCurrentMappingText() {
            val role = state.roles.getOrNull(currentRoleIndex)
            val mappedId = role?.let { state.currentMappings[it.key] }
            val mappedLabel = state.candidates.firstOrNull { it.id == mappedId }?.label
            currentMappingView.text = if (mappedLabel.isNullOrEmpty()) {
                getString(R.string.camera_mapping_current_none)
            } else {
                getString(R.string.camera_mapping_current_format, mappedLabel)
            }
            updateRoleExplainer()
        }

        fun refreshPreview(scheduleNext: Boolean) {
            if (dialogClosed || state.candidates.isEmpty()) return
            if (fetchInFlight) {
                // A previous fetch hasn't completed. Arm a single retry on the
                // poll cadence so we don't permanently lose the loop when the
                // user navigates Prev/Next mid-fetch (the in-flight result
                // discards itself when activePreviewCandidateId differs and
                // can't re-arm for the new candidate).
                if (scheduleNext) {
                    previewHandler.removeCallbacksAndMessages(null)
                    previewHandler.postDelayed({ refreshPreview(true) }, 2000)
                }
                return
            }
            val candidate = state.candidates[currentCandidateIndex]
            activePreviewCandidateId = candidate.id
            candidateLabelView.text = candidate.label
            val previewPath = buildCameraPreviewPath(candidate)
            if (previewPath == null) {
                // Malformed candidate — show placeholder, skip the fetch,
                // re-arm next poll.
                previewImageView.setImageDrawable(null)
                previewPlaceholderView.visibility = View.VISIBLE
                if (scheduleNext) {
                    previewHandler.removeCallbacksAndMessages(null)
                    previewHandler.postDelayed({ refreshPreview(true) }, 2000)
                }
                return
            }
            fetchInFlight = true
            Thread {
                // Network + JPEG decode both happen on this worker thread —
                // never block the UI thread on Bitmap.decodeByteArray (5-15 ms
                // per tick at 2 s cadence is enough to cause visible jank on
                // the BYD head unit).
                var bitmap: android.graphics.Bitmap? = null
                try {
                    // 4 s read budget — daemon does AVMCamera open + ≤800 ms
                    // frame-wait + teardown for direct kind, ~50 ms volatile
                    // read + JPEG decode/crop for slice/virtual kinds. Both
                    // are well inside this window.
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        previewPath,
                        "GET",
                        2500,
                        4000
                    )
                    val bytes = if (conn.responseCode == 200) {
                        conn.inputStream.use { it.readBytes() }
                    } else null
                    conn.disconnect()
                    if (bytes != null && bytes.isNotEmpty()) {
                        bitmap = android.graphics.BitmapFactory
                            .decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) {
                    bitmap = null
                }

                val finalBitmap = bitmap
                runOnUiThread {
                    fetchInFlight = false
                    if (dialogClosed || activePreviewCandidateId != candidate.id) {
                        // Result for an old candidate or the dialog closed.
                        // The bitmap is no longer needed; recycle.
                        finalBitmap?.recycle()
                        // The new candidate may have re-armed via
                        // selectCandidate → refreshPreview but bailed at
                        // fetchInFlight; now that the in-flight is done, kick
                        // a fresh fetch immediately so the user doesn't see
                        // a 2s blank period after Prev/Next.
                        if (!dialogClosed && scheduleNext) {
                            previewHandler.removeCallbacksAndMessages(null)
                            refreshPreview(true)
                        }
                        return@runOnUiThread
                    }
                    if (finalBitmap != null) {
                        // Recycle the previous frame before swapping in the
                        // new one — ImageView holds via Drawable and won't
                        // recycle the replaced bitmap on its own.
                        val oldBitmap = previousPreviewBitmap
                        previewImageView.setImageBitmap(finalBitmap)
                        previewPlaceholderView.visibility = View.GONE
                        previousPreviewBitmap = finalBitmap
                        oldBitmap?.recycle()
                    } else {
                        previewImageView.setImageDrawable(null)
                        previewPlaceholderView.visibility = View.VISIBLE
                        previousPreviewBitmap?.recycle()
                        previousPreviewBitmap = null
                    }
                    if (scheduleNext) {
                        // Cancel any pending poll callback first so we don't
                        // double-arm when the in-flight result coincides
                        // with selectCandidate() arming its own poll.
                        previewHandler.removeCallbacksAndMessages(null)
                        previewHandler.postDelayed({ refreshPreview(true) }, 2000)
                    }
                }
            }.start()
        }

        fun selectCandidate(index: Int) {
            if (state.candidates.isEmpty()) {
                candidateLabelView.text = getString(R.string.camera_preview_unavailable)
                previewImageView.setImageDrawable(null)
                previewPlaceholderView.visibility = View.VISIBLE
                activePreviewCandidateId = null
                return
            }
            currentCandidateIndex = when {
                index < 0 -> 0
                index >= state.candidates.size -> state.candidates.lastIndex
                else -> index
            }
            val newCandidate = state.candidates[currentCandidateIndex]
            candidateLabelView.text = newCandidate.label
            // Eagerly update the active id so an in-flight fetch result for
            // a previous candidate can no longer slip through and render
            // its image under the new candidate's label. The fetch path
            // sees activePreviewCandidateId != candidate.id and discards.
            activePreviewCandidateId = newCandidate.id
            // Cancel any pending poll for the previous candidate, then
            // re-arm with the new one.
            previewHandler.removeCallbacksAndMessages(null)
            refreshPreview(true)
        }

        // Spinner.setOnItemSelectedListener fires synchronously on the next
        // layout pass for position 0 even when the user hasn't interacted —
        // an Android quirk. Suppress the first auto-fire so we don't kick a
        // duplicate fetch that races dialog.show() and the explicit
        // selectCandidate() call below.
        var spinnerInitialFireSuppressed = false
        roleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!spinnerInitialFireSuppressed) {
                    spinnerInitialFireSuppressed = true
                    return
                }
                currentRoleIndex = position
                val newIdx = mappedCandidateIndexForRole(state.roles[position].key)
                updateCurrentMappingText()
                selectCandidate(newIdx)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        prevButton.setOnClickListener {
            if (state.candidates.isEmpty()) return@setOnClickListener
            val idx = if (currentCandidateIndex <= 0) state.candidates.lastIndex
                      else currentCandidateIndex - 1
            selectCandidate(idx)
        }

        nextButton.setOnClickListener {
            if (state.candidates.isEmpty()) return@setOnClickListener
            val idx = if (currentCandidateIndex >= state.candidates.lastIndex) 0
                      else currentCandidateIndex + 1
            selectCandidate(idx)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Overdrive_M3_Dialog
        )
            .setView(dialogView)
            .setNegativeButton(getString(R.string.dialog_close), null)
            .create()

        // NOTE: the dismiss listener is registered ONCE at the end of this method (after
        // dialog.show()), so it can also unregister the ACC-dismiss receiver and notify
        // the onboarding coach. It preserves this same teardown (recycle bitmap, stop the
        // preview loop). Do not add a second setOnDismissListener here — it would replace
        // that combined one and drop the ACC-receiver cleanup.

        // Lock both Save and Clear after the first click on either. The
        // success path dismisses the dialog and SIGKILLs the daemon for a
        // 5-second relaunch; without this guard the user can spam-click,
        // each click POSTing to a dying daemon and toasting "failed to
        // save". The buttons are re-enabled only on a save *failure* (the
        // dialog stays open so the user can retry).
        fun setActionsEnabled(enabled: Boolean) {
            saveMappingButton.isEnabled = enabled
            clearMappingButton.isEnabled = enabled
        }

        saveMappingButton.setOnClickListener {
            val role = state.roles.getOrNull(currentRoleIndex) ?: return@setOnClickListener
            val candidate = state.candidates.getOrNull(currentCandidateIndex) ?: return@setOnClickListener
            val payload = org.json.JSONObject().apply {
                put("cameraRoleMapping", org.json.JSONObject().apply {
                    put("role", role.key)
                    put("source", candidate.toJson())
                })
            }.toString()
            setActionsEnabled(false)
            postSurveillanceConfig(payload) { success, message ->
                if (success) {
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartCameraDaemonForCameraSettings()
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                    setActionsEnabled(true)
                }
            }
        }

        clearMappingButton.setOnClickListener {
            val role = state.roles.getOrNull(currentRoleIndex) ?: return@setOnClickListener
            val payload = org.json.JSONObject().apply {
                put("cameraRoleMapping", org.json.JSONObject().apply {
                    put("role", role.key)
                    put("clear", true)
                })
            }.toString()
            setActionsEnabled(false)
            postSurveillanceConfig(payload) { success, message ->
                if (success) {
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartCameraDaemonForCameraSettings()
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                    setActionsEnabled(true)
                }
            }
        }

        // Manual camera ID picker. Independent from the role-mapping flow:
        // saves a single integer (or clears it) and skips prepare-restart so
        // it works even when the live preview pipeline is wedged. The
        // manualCameraId field is read on next pipeline init (typically the
        // next ACC OFF/ON cycle) — operators can also restart the daemon
        // manually from the Daemons screen if they want it to take effect now.
        val initialManualRadioId = when (state.manualCameraId) {
            0 -> R.id.rbManualCamera0
            1 -> R.id.rbManualCamera1
            2 -> R.id.rbManualCamera2
            3 -> R.id.rbManualCamera3
            4 -> R.id.rbManualCamera4
            5 -> R.id.rbManualCamera5
            else -> R.id.rbManualCameraAuto
        }
        manualCameraGroup.check(initialManualRadioId)
        currentManualCameraView.text = state.manualCameraId?.let {
            getString(R.string.camera_mapping_current_format, "Camera $it")
        } ?: getString(R.string.camera_current_auto)

        saveManualCameraButton.setOnClickListener {
            val selectedId = when (manualCameraGroup.checkedRadioButtonId) {
                R.id.rbManualCamera0 -> 0
                R.id.rbManualCamera1 -> 1
                R.id.rbManualCamera2 -> 2
                R.id.rbManualCamera3 -> 3
                R.id.rbManualCamera4 -> 4
                R.id.rbManualCamera5 -> 5
                else -> -1
            }
            val payload = org.json.JSONObject().apply {
                if (selectedId >= 0) {
                    put("manualCameraId", selectedId)
                } else {
                    put("clearManualCameraId", true)
                }
            }.toString()
            saveManualCameraButton.isEnabled = false
            postSurveillanceConfig(payload) { success, message ->
                saveManualCameraButton.isEnabled = true
                if (success) {
                    currentManualCameraView.text = if (selectedId >= 0) {
                        getString(R.string.camera_mapping_current_format, "Camera $selectedId")
                    } else {
                        getString(R.string.camera_current_auto)
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Camera ingestion mode (Default vs DiLink 4). Pre-select from
        // saved config; if no value present the data class default is
        // "default" so the radio group defaults match the daemon's
        // resolveCameraModeFromConfig fallback.
        val initialModeRadioId = if (state.cameraMode == "dilink4") {
            R.id.rbCameraModeDilink4
        } else {
            R.id.rbCameraModeDefault
        }
        cameraModeGroup.check(initialModeRadioId)
        currentCameraModeView.text = if (state.cameraMode == "dilink4") {
            getString(R.string.camera_mode_current_dilink4)
        } else {
            getString(R.string.camera_mode_current_default)
        }

        saveCameraModeButton.setOnClickListener {
            val selectedMode = when (cameraModeGroup.checkedRadioButtonId) {
                R.id.rbCameraModeDilink4 -> "dilink4"
                else -> "default"
            }
            // No-op when the user re-applies the already-saved mode — saves a
            // daemon restart and a "settings unchanged" toast.
            if (selectedMode == state.cameraMode) {
                Toast.makeText(
                    this,
                    getString(R.string.camera_mode_save),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val payload = org.json.JSONObject().apply {
                put("cameraMode", selectedMode)
            }.toString()
            saveCameraModeButton.isEnabled = false
            postSurveillanceConfig(payload) { success, message ->
                if (success) {
                    currentCameraModeView.text = if (selectedMode == "dilink4") {
                        getString(R.string.camera_mode_current_dilink4)
                    } else {
                        getString(R.string.camera_mode_current_default)
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Mode changes are read at PanoramicCameraGpu construction
                    // — only a daemon restart picks up the new path. Reuse the
                    // existing prepare-restart flow used by role-mapping saves.
                    restartCameraDaemonForCameraSettings()
                    dialog.dismiss()
                } else {
                    saveCameraModeButton.isEnabled = true
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // DiLink 4 compatibility options. Both are ignored outside the
        // DiLink 4 ingestion path.
        dilink4PassiveApaSwitch.isChecked = state.dilink4PassiveApaMode
        dilink4RedMaskSwitch.isChecked = state.dilink4RedMask
        saveDilink4TweaksButton.setOnClickListener {
            val payload = org.json.JSONObject().apply {
                put("dilink4PassiveApaMode", dilink4PassiveApaSwitch.isChecked)
                put("dilink4RedMask", dilink4RedMaskSwitch.isChecked)
            }.toString()
            saveDilink4TweaksButton.isEnabled = false
            postSurveillanceConfig(payload) { success, message ->
                if (success) {
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartCameraDaemonForCameraSettings()
                    dialog.dismiss()
                } else {
                    saveDilink4TweaksButton.isEnabled = true
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // OEM Dashcam camera ID picker. Mirrors the manual-camera-ID card
        // above but writes to camera.oemDashcamCameraId / oemDashcamManual
        // Override directly via UnifiedConfigManager.updateSection (same
        // direct-write shape MainActivity.performCameraReconfigure uses) —
        // the OEM dashcam fields aren't merged into /api/surveillance/config
        // yet, so going through the daemon HTTP path would silently drop
        // them. Auto means: let resolveOemDashcamId() infer pano^1 at
        // pipeline init.
        //
        // After save we invalidate ConcurrentAvmProbe so the next daemon
        // boot re-probes — the previous probe result was keyed against the
        // old (pano,dashcam) pair and could be stale once the user picks a
        // different dashcam id.
        fun radioIdForOemDashcamId(id: Int): Int = when (id) {
            0 -> R.id.rbOemDashcam0
            1 -> R.id.rbOemDashcam1
            2 -> R.id.rbOemDashcam2
            3 -> R.id.rbOemDashcam3
            4 -> R.id.rbOemDashcam4
            5 -> R.id.rbOemDashcam5
            else -> R.id.rbOemDashcamAuto
        }
        val initialOemDashcamRadioId = if (state.oemDashcamManualOverride) {
            radioIdForOemDashcamId(state.oemDashcamCameraId)
        } else {
            R.id.rbOemDashcamAuto
        }
        oemDashcamGroup.check(initialOemDashcamRadioId)
        concurrentProbeSwitch?.isChecked = state.concurrentAvmProbeEnabled

        fun refreshOemDashcamSubLabel(
            isManual: Boolean,
            dashcamId: Int,
            panoId: Int
        ) {
            currentOemDashcamView.text = when {
                isManual && dashcamId in 0..5 ->
                    getString(R.string.camera_mapping_current_format, "Camera $dashcamId")
                isManual ->
                    getString(R.string.camera_current_auto)
                else -> {
                    val resolved = com.overdrive.app.config.UnifiedConfigManager
                        .resolveOemDashcamId()
                    if (resolved >= 0) {
                        getString(
                            R.string.camera_oem_dashcam_auto_resolved,
                            resolved, panoId
                        )
                    } else {
                        getString(
                            R.string.camera_oem_dashcam_auto_unavailable,
                            panoId
                        )
                    }
                }
            }
        }
        refreshOemDashcamSubLabel(
            state.oemDashcamManualOverride,
            state.oemDashcamCameraId,
            state.panoCameraId
        )

        saveOemDashcamButton.setOnClickListener {
            val selectedId = when (oemDashcamGroup.checkedRadioButtonId) {
                R.id.rbOemDashcam0 -> 0
                R.id.rbOemDashcam1 -> 1
                R.id.rbOemDashcam2 -> 2
                R.id.rbOemDashcam3 -> 3
                R.id.rbOemDashcam4 -> 4
                R.id.rbOemDashcam5 -> 5
                else -> -1
            }
            val manualOverride = selectedId >= 0
            val probeOptIn = concurrentProbeSwitch?.isChecked ?: false
            val patch = org.json.JSONObject().apply {
                put("oemDashcamManualOverride", manualOverride)
                put("oemDashcamCameraId", if (manualOverride) selectedId else -1)
                // Opt-in for the destructive dual-camera probe. Persist the
                // user's explicit choice; the daemon only runs the probe on
                // next boot when this is true (and even then defers if a
                // pipeline is live, per ConcurrentAvmProbe liveness guard).
                put("concurrentAvmProbeEnabled", probeOptIn)
            }
            saveOemDashcamButton.isEnabled = false
            // Background thread for the UCM write — updateSection rewrites
            // the whole JSON file and is not safe on the UI looper (per
            // feedback_no_unified_writes_on_ui_thread.md). Mirrors the
            // performCameraReconfigure pattern.
            Thread {
                val ok = try {
                    com.overdrive.app.config.UnifiedConfigManager
                        .updateSection("camera", patch)
                } catch (e: Exception) {
                    logsViewModel.error(
                        "Camera",
                        "Failed to save OEM dashcam id: ${e.message}"
                    )
                    false
                }
                // Invalidate the concurrent-AVM probe so the next daemon
                // boot re-probes against the new (pano, dashcam) pair.
                if (ok) {
                    try {
                        com.overdrive.app.camera.ConcurrentAvmProbe.invalidate()
                    } catch (e: Exception) {
                        logsViewModel.warn(
                            "Camera",
                            "ConcurrentAvmProbe.invalidate failed: ${e.message}"
                        )
                    }
                    // If OEM dashcam is currently running, the new id is in
                    // UCM but the live pipeline captured the OLD id at
                    // start(). Without an explicit restart the user's new
                    // pick is silent until the next toggle off+on. Hit the
                    // /api/oem-dashcam/config endpoint with restart:true so
                    // the daemon does the stop+start cycle on its worker.
                    try {
                        // openConnection() WITHOUT Proxy.NO_PROXY routes this
                        // loopback POST through the system HTTP proxy when
                        // sing-box is active (it sets 127.0.0.1:8119 as the
                        // global proxy), so the request to our own daemon fails.
                        // Force a direct connection — every other daemon call
                        // goes through DaemonHttpClient which already does this.
                        val daemon = java.net.URL("http://127.0.0.1:8080/api/oem-dashcam/config")
                            .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                        daemon.requestMethod = "POST"
                        daemon.doOutput = true
                        daemon.connectTimeout = 2000
                        daemon.readTimeout = 2000
                        daemon.setRequestProperty("Content-Type", "application/json")
                        daemon.outputStream.use { it.write("{\"restart\":true}".toByteArray()) }
                        daemon.responseCode  // force send
                        daemon.disconnect()
                    } catch (e: Exception) {
                        // Daemon may not be running, or the endpoint is
                        // unreachable on this build — non-fatal. The user
                        // can toggle off+on as a fallback.
                        logsViewModel.warn("Camera",
                            "OEM dashcam restart request failed: ${e.message}")
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    saveOemDashcamButton.isEnabled = true
                    if (ok) {
                        refreshOemDashcamSubLabel(
                            manualOverride,
                            if (manualOverride) selectedId else -1,
                            state.panoCameraId
                        )
                        Toast.makeText(
                            this,
                            getString(R.string.camera_mapping_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_failed_to_save_short),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
        }

        // ---- Camera pairing card (additive visual mirror) ----
        // Reflects the two radio groups above into side-by-side role slots
        // with one-shot snapshot thumbnails and a one-tap Swap. Every view
        // is bound null-safely so the dialog degrades to the classic
        // pickers if the card is absent. Swap introduces NO new write path:
        // it re-checks the radio groups and drives the same Save buttons a
        // manual tap (and CameraWizardCoach.coachSaveIds) already uses, so
        // save semantics, failure handling, and restart behaviour are
        // byte-identical to the shipping flow.
        val pairPanoThumb = dialogView.findViewById<ImageView>(R.id.ivPairPanoThumb)
        val pairDashcamThumb = dialogView.findViewById<ImageView>(R.id.ivPairDashcamThumb)
        val pairPanoValue = dialogView.findViewById<TextView>(R.id.tvPairPanoValue)
        val pairDashcamValue = dialogView.findViewById<TextView>(R.id.tvPairDashcamValue)
        val swapButton = dialogView.findViewById<View>(R.id.btnSwapCameraIds)

        // Slot 0 = pano, slot 1 = dashcam. Same manual-recycle discipline as
        // the role-mapping preview above — ImageView won't recycle replaced
        // bitmaps on its own.
        val pairThumbBitmaps = arrayOfNulls<android.graphics.Bitmap>(2)
        val pairThumbInFlight = booleanArrayOf(false, false)
        val pairThumbShownId = intArrayOf(-1, -1)
        // Bounded per-slot retry. Both slots fetch at dialog open and the
        // daemon gates direct opens behind a single-flight cold start
        // (503 Retry-After=2s) — the losing slot (typically the dashcam)
        // would otherwise stay on the placeholder forever. Attempts reset
        // whenever the slot's target camera id changes. Dedicated handler:
        // the role-preview loop calls removeCallbacksAndMessages(null) on
        // previewHandler, which would nuke our retries if shared.
        val pairThumbAttempts = intArrayOf(0, 0)
        val pairThumbTargetId = intArrayOf(-1, -1)
        val pairRetryHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun radioIdForManualCameraId(id: Int): Int = when (id) {
            0 -> R.id.rbManualCamera0
            1 -> R.id.rbManualCamera1
            2 -> R.id.rbManualCamera2
            3 -> R.id.rbManualCamera3
            4 -> R.id.rbManualCamera4
            5 -> R.id.rbManualCamera5
            else -> R.id.rbManualCameraAuto
        }

        fun selectedPanoId(): Int = when (manualCameraGroup.checkedRadioButtonId) {
            R.id.rbManualCamera0 -> 0
            R.id.rbManualCamera1 -> 1
            R.id.rbManualCamera2 -> 2
            R.id.rbManualCamera3 -> 3
            R.id.rbManualCamera4 -> 4
            R.id.rbManualCamera5 -> 5
            else -> -1
        }

        fun selectedOemId(): Int = when (oemDashcamGroup.checkedRadioButtonId) {
            R.id.rbOemDashcam0 -> 0
            R.id.rbOemDashcam1 -> 1
            R.id.rbOemDashcam2 -> 2
            R.id.rbOemDashcam3 -> 3
            R.id.rbOemDashcam4 -> 4
            R.id.rbOemDashcam5 -> 5
            else -> -1
        }

        // Displayed id = the checked manual id, or what Auto resolves to.
        // Auto-dashcam mirrors the daemon's pano XOR 1 inference so the
        // card previews what a save would actually produce.
        fun displayedPanoId(): Int {
            val sel = selectedPanoId()
            return if (sel >= 0) sel else state.panoCameraId
        }

        fun displayedDashcamId(): Int {
            val sel = selectedOemId()
            if (sel >= 0) return sel
            val pano = displayedPanoId()
            return if (pano in 0..1) pano xor 1 else -1
        }

        fun loadPairThumb(slot: Int, target: ImageView?, cameraId: Int) {
            if (target == null || dialogClosed) return
            if (cameraId < 0) return  // unresolvable — keep placeholder icon
            if (pairThumbTargetId[slot] != cameraId) {
                // New target for this slot — fresh retry budget.
                pairThumbTargetId[slot] = cameraId
                pairThumbAttempts[slot] = 0
            }
            if (pairThumbShownId[slot] == cameraId || pairThumbInFlight[slot]) return
            if (pairThumbAttempts[slot] >= 3) return  // budget exhausted — placeholder stays
            // Reuse the daemon-advertised direct candidate (and its preview
            // dimensions) — same endpoint contract as the role-mapping
            // preview. No candidate → placeholder stays; selection and Swap
            // remain fully usable, preserving the wedged-pipeline rescue
            // path the manual pickers were designed for.
            val candidate = state.candidates.firstOrNull {
                it.kind.equals("direct", ignoreCase = true) && it.cameraId == cameraId
            } ?: return
            val path = buildCameraPreviewPath(candidate) ?: return
            pairThumbAttempts[slot]++
            pairThumbInFlight[slot] = true
            Thread {
                var bitmap: android.graphics.Bitmap? = null
                try {
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        path, "GET", 2500, 4000)
                    val bytes = if (conn.responseCode == 200) {
                        conn.inputStream.use { it.readBytes() }
                    } else null
                    conn.disconnect()
                    if (bytes != null && bytes.isNotEmpty()) {
                        bitmap = android.graphics.BitmapFactory
                            .decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) {
                    bitmap = null
                }
                val finalBitmap = bitmap
                runOnUiThread {
                    pairThumbInFlight[slot] = false
                    if (dialogClosed) {
                        finalBitmap?.recycle()
                        return@runOnUiThread
                    }
                    if (finalBitmap != null) {
                        val old = pairThumbBitmaps[slot]
                        target.setImageBitmap(finalBitmap)
                        pairThumbBitmaps[slot] = finalBitmap
                        pairThumbShownId[slot] = cameraId
                        old?.recycle()
                    } else if (pairThumbTargetId[slot] == cameraId) {
                        // Cold-start 503 / transient open failure — e.g. the
                        // other slot's fetch won the daemon's single-flight
                        // window. Bounded retry on the daemon's Retry-After
                        // cadence; loadPairThumb re-checks the budget.
                        pairRetryHandler.postDelayed({
                            if (!dialogClosed) loadPairThumb(slot, target, cameraId)
                        }, 2500)
                    }
                }
            }.start()
        }

        fun renderPairing() {
            if (pairPanoValue == null && pairDashcamValue == null) return
            val pano = displayedPanoId()
            val dash = displayedDashcamId()
            pairPanoValue?.text = when {
                selectedPanoId() >= 0 ->
                    getString(R.string.camera_pair_value_manual, pano)
                pano >= 0 -> getString(R.string.camera_pair_value_auto, pano)
                else -> getString(R.string.camera_option_auto)
            }
            pairDashcamValue?.text = when {
                selectedOemId() >= 0 ->
                    getString(R.string.camera_pair_value_manual, dash)
                dash >= 0 -> getString(R.string.camera_pair_value_auto, dash)
                else -> getString(R.string.camera_option_auto)
            }
            swapButton?.isEnabled = pano in 0..5 && dash in 0..5 && pano != dash
            loadPairThumb(0, pairPanoThumb, pano)
            loadPairThumb(1, pairDashcamThumb, dash)
        }

        // Live mirror: any radio change (manual tap, coach, or Swap) updates
        // the pairing card. Neither group had a checked-change listener
        // before, so this adds behaviour without replacing any.
        manualCameraGroup.setOnCheckedChangeListener { _, _ -> renderPairing() }
        oemDashcamGroup.setOnCheckedChangeListener { _, _ -> renderPairing() }

        // Advanced expander around the two manual ID picker cards. Collapsed
        // by default (the pairing card covers the common cases), but auto-
        // expanded when a manual override is already saved so configured
        // devices see their state without hunting. CameraWizardCoach expands
        // it via btnToggleAdvancedIds.performClick() when the user opts into
        // manual picking. Null-safe: without these views the pickers are
        // simply always visible (legacy layout behaviour).
        val advancedIdSection = dialogView.findViewById<View>(R.id.advancedIdSection)
        val advancedIdsToggle = dialogView.findViewById<View>(R.id.btnToggleAdvancedIds)
        val advancedIdsChevron = dialogView.findViewById<ImageView>(R.id.ivAdvancedIdsChevron)
        fun setAdvancedIdsExpanded(expanded: Boolean) {
            advancedIdSection?.visibility = if (expanded) View.VISIBLE else View.GONE
            advancedIdsChevron?.rotation = if (expanded) 180f else 0f
        }
        setAdvancedIdsExpanded(state.isManualOverride || state.oemDashcamManualOverride)
        advancedIdsToggle?.setOnClickListener {
            setAdvancedIdsExpanded(advancedIdSection?.visibility != View.VISIBLE)
        }

        swapButton?.setOnClickListener {
            val pano = displayedPanoId()
            val dash = displayedDashcamId()
            if (pano !in 0..5 || dash !in 0..5 || pano == dash) return@setOnClickListener
            // Cross-assign, then persist through the existing Save buttons —
            // identical payloads, toasts, and failure recovery as manual
            // taps. Converting Auto → Manual here is intentional: the user
            // is explicitly overriding what Auto resolved to.
            manualCameraGroup.check(radioIdForManualCameraId(dash))
            oemDashcamGroup.check(radioIdForOemDashcamId(pano))
            saveManualCameraButton.performClick()
            saveOemDashcamButton.performClick()
        }

        renderPairing()

        updateCurrentMappingText()
        selectCandidate(mappedCandidateIndexForRole(
            state.roles.getOrNull(currentRoleIndex)?.key ?: ""))
        dialog.show()

        // SAFETY: the camera dialog shows a live 280dp preview that must NOT remain on
        // screen while driving. The dialog has no ACC guard of its own, so register an
        // ACC-ON/IGN-ON receiver (same vendor broadcast PinLockActivity uses) that
        // dismisses it on the ignition edge — protecting BOTH the onboarding-driven open
        // and a normal manual reconfigure. dialog.dismiss() drives the dismiss listener
        // below (which also notifies the onboarding coach), so this is the single point
        // that closes the dialog when the car starts moving.
        val accDismissReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.byd.action.ACC_ON", "com.byd.action.IGN_ON" -> {
                        android.util.Log.i("MainActivity", "ACC ON — dismissing camera mapping dialog")
                        try { dialog.dismiss() } catch (_: Throwable) {}
                    }
                }
            }
        }
        try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(accDismissReceiver, android.content.IntentFilter().apply {
                addAction("com.byd.action.ACC_ON")
                addAction("com.byd.action.IGN_ON")
            })
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "camera-dialog ACC receiver register failed: ${t.message}")
        }

        // If the onboarding camera wizard requested this dialog, hand it the dialog
        // window + inflated view so it can attach coachmarks ABOVE the dialog and
        // spotlight the real controls. Cleared on dismiss so a normal (non-onboarding)
        // open never triggers the coach.
        val onboardingCoach = pendingCameraOnboardingCoach
        pendingCameraOnboardingCoach = null
        val onboardingDecor = if (onboardingCoach != null) {
            (dialog.window?.decorView as? android.view.ViewGroup)?.also { decor ->
                onboardingCoach.attachToDialog(dialogView, decor)
            }
        } else null

        // Single dismiss listener: preserves the original teardown (recycle bitmap,
        // stop the preview loop), unregisters the ACC receiver, and notifies the coach.
        dialog.setOnDismissListener {
            dialogClosed = true
            previewHandler.removeCallbacksAndMessages(null)
            previewImageView.setImageDrawable(null)
            previousPreviewBitmap?.recycle()
            previousPreviewBitmap = null
            // Pairing-card thumbnails follow the same manual-recycle
            // discipline; in-flight fetches see dialogClosed and recycle
            // their own result. Pending retries are dropped with the
            // dedicated handler.
            pairRetryHandler.removeCallbacksAndMessages(null)
            pairPanoThumb?.setImageDrawable(null)
            pairDashcamThumb?.setImageDrawable(null)
            for (i in pairThumbBitmaps.indices) {
                pairThumbBitmaps[i]?.recycle()
                pairThumbBitmaps[i] = null
            }
            try { unregisterReceiver(accDismissReceiver) } catch (_: Throwable) {}
            if (onboardingCoach != null && onboardingDecor != null) onboardingCoach.onDialogDismissed()
        }
    }

    private fun postSurveillanceConfig(body: String, onComplete: (Boolean, String?) -> Unit) {
        Thread {
            var success = false
            var message: String? = null
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/surveillance/config", "POST", 3000, 4000)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val httpOk = conn.responseCode == 200
                if (httpOk) {
                    // Daemon's HttpResponse.sendJsonError returns HTTP 200 with
                    // a {success:false, error: "..."} body, so we can't trust
                    // the status code alone. Parse the body and check the
                    // explicit success field.
                    val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                    try {
                        val json = org.json.JSONObject(bodyText)
                        success = json.optBoolean("success", true)
                        if (!success) {
                            message = json.optString("error",
                                getString(R.string.toast_failed_to_save_short))
                        }
                    } catch (_: Exception) {
                        // Non-JSON body — assume success (legacy endpoints).
                        success = true
                    }
                } else {
                    message = conn.errorStream?.bufferedReader()?.use { it.readText() }
                }
            } catch (e: Exception) {
                success = false
                message = e.message
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            runOnUiThread { onComplete(success, message) }
        }.start()
    }

    /**
     * Save → restart pattern: camera mapping changes propagate through
     * GpuSurveillancePipeline.init() (foveated cropper, mosaic recorder,
     * downscaler, stream scaler all read the resolved layout at init time).
     * A bare config write doesn't reapply offsets to the running pipeline,
     * so we kill the daemon and let the watchdog relaunch.
     *
     * <p>Two-phase to avoid corrupting in-flight recordings:
     * <ol>
     *   <li>POST /api/surveillance/prepare-restart — daemon stops the pipeline
     *       gracefully (writes MP4 moov atom, flushes circular buffer,
     *       closes encoder + EGL surfaces). Bounded ~1-2 s.</li>
     *   <li>SIGKILL the daemon and relaunch. The 5 s relaunch delay gives the
     *       BYD camera HAL time to release the camera before the new daemon
     *       opens it (without that, the HAL emits event 1002).</li>
     * </ol>
     */
    private fun restartCameraDaemonForCameraSettings() {
        // Guard every continuation against an Activity that's destroyed mid-
        // restart. The chain is: Thread → runOnUiThread → AdbDaemonLauncher
        // callback → runOnUiThread → 5s postDelayed → 5s postDelayed —
        // total ~10s. If the user backgrounds or recreates the activity
        // during that window, the captured `this` would otherwise be a
        // dead reference to a torn-down Activity.
        fun activityAlive(): Boolean = !isFinishing && !isDestroyed

        Toast.makeText(
            this,
            getString(R.string.toast_camera_settings_saved_restarting),
            Toast.LENGTH_LONG
        ).show()
        logsViewModel.info("Camera",
            "Camera settings changed — restarting daemon to apply them immediately")

        Thread {
            // Phase 1: graceful flush. The daemon only returns 2xx after any
            // active trip is durably checkpointed. Killing after any other
            // result can discard the in-memory telemetry tail.
            // Retry a transient refusal. Most 503s here are few-second
            // conditions (a just-ended trip still draining to disk, trip
            // analytics still starting on its own thread), and a single attempt
            // meant saving camera settings shortly after parking failed with the
            // settings written but never applied.
            var prepared = false
            for (attempt in 1..6) {
                var retryable = false
                prepared = try {
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        "/api/surveillance/prepare-restart", "POST", 3000, 10000)
                    conn.doOutput = true
                    conn.outputStream.use { it.write(byteArrayOf()) }
                    val code = conn.responseCode
                    val detail = try {
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    conn.disconnect()
                    if (code in 200..299) {
                        logsViewModel.debug("Camera",
                            "prepare-restart returned HTTP $code")
                        true
                    } else {
                        // The daemon's own verdict decides whether waiting can
                        // clear this; a failed teardown answers 503 too but is
                        // not fixable by retrying. Unreadable body ⇒ retry.
                        retryable = code == 503 && try {
                            if (detail.isBlank()) true
                            else org.json.JSONObject(detail)
                                .optBoolean("retryable", true)
                        } catch (_: Exception) {
                            true
                        }
                        logsViewModel.warn("Camera",
                            "prepare-restart rejected kill with HTTP $code" +
                                if (detail.isNotBlank()) ": $detail" else "")
                        false
                    }
                } catch (e: Exception) {
                    // A transport failure can mean the daemon prepared but the
                    // response was lost, so it is not retried blindly.
                    logsViewModel.warn("Camera",
                        "prepare-restart failed; daemon kill aborted: ${e.message}")
                    false
                }
                if (prepared || !retryable || attempt == 6) break
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (!prepared) {
                // The response may have been lost after the server set its
                // shutdown latch. Since no kill will follow, explicitly put
                // the surviving daemon back into its normal request state.
                try {
                    val abort = com.overdrive.app.util.DaemonHttpClient.open(
                        "/api/surveillance/abort-restart", "POST", 2000, 2000)
                    abort.doOutput = true
                    abort.outputStream.use { it.write(byteArrayOf()) }
                    abort.responseCode
                    abort.disconnect()
                } catch (_: Exception) {
                    // The failure toast below already directs a manual restart.
                }
            }

            // Phase 2: kill + relaunch on the UI thread.
            runOnUiThread {
                if (!activityAlive()) return@runOnUiThread
                if (!prepared) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_camera_settings_restart_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                // Reuse shared adbLauncher (avoids per-call leak).
                daemonStartupManager.adbLauncher.killDaemon(object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) {
                        logsViewModel.debug("Camera", message)
                    }

                    override fun onLaunched() {
                        runOnUiThread {
                            if (!activityAlive()) return@runOnUiThread
                            logsViewModel.info("Camera",
                                "Camera daemon stopped — relaunching with updated camera settings")
                            // 5 s delay: BYD camera HAL needs ~3-5 s to release
                            // the camera after process death; relaunching too
                            // early triggers event 1002 on the new daemon's open.
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!activityAlive()) return@postDelayed
                                daemonStartupManager.initializeOnAppLaunch()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    if (!activityAlive()) return@postDelayed
                                    daemonStartupManager.checkAllDaemonStatuses()
                                }, 5000)
                            }, 5000)
                        }
                    }

                    override fun onError(error: String) {
                        // Kill failed. The daemon is still alive AND its
                        // shutdownInProgress latch is set (prepare-restart
                        // ran successfully). Without unsticking it, every
                        // future preview returns "Preview unavailable".
                        // Fire-and-forget POST to clear the latch.
                        Thread {
                            try {
                                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                                    "/api/surveillance/abort-restart", "POST", 2000, 2000)
                                conn.doOutput = true
                                conn.outputStream.use { it.write(byteArrayOf()) }
                                conn.responseCode  // force send
                                conn.disconnect()
                            } catch (_: Exception) {
                                // Best-effort. Worst case the user must
                                // manually restart the daemon.
                            }
                        }.start()
                        runOnUiThread {
                            if (!activityAlive()) return@runOnUiThread
                            logsViewModel.error("Camera",
                                "Failed to restart daemon after camera settings save: $error")
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.toast_camera_settings_restart_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
            }
        }.start()
    }

    private fun buildCameraPreviewPath(candidate: CameraPreviewCandidate): String? {
        // Build the preview URL with strict shape matching. A malformed
        // candidate (e.g. kind=direct without cameraId, kind=panoramicSlice
        // without slice) used to silently fall through to "panoramic
        // front", which would render the front camera under whatever role
        // the user was actually mapping — misleading. Now we return null
        // and the caller treats it as "Preview unavailable".
        return when {
            candidate.kind.equals("direct", ignoreCase = true) -> {
                if (candidate.cameraId == null) null
                else "/api/surveillance/camera-preview?kind=direct" +
                    "&cameraId=${candidate.cameraId}" +
                    "&width=${candidate.width}&height=${candidate.height}"
            }
            candidate.kind.equals("panoramicSlice", ignoreCase = true) -> {
                if (candidate.slice.isNullOrEmpty()) null
                else "/api/surveillance/camera-preview?kind=panoramicSlice" +
                    "&slice=${candidate.slice}" +
                    "&width=${candidate.width}&height=${candidate.height}"
            }
            candidate.kind.equals("panoramic", ignoreCase = true)
                    || candidate.kind.equals("panoramicVirtual", ignoreCase = true) -> {
                if (candidate.view.isNullOrEmpty()) null
                else "/api/surveillance/camera-preview?kind=panoramic" +
                    "&view=${candidate.view}"
            }
            else -> null
        }
    }

    /**
     * Clear saved camera config and restart the camera daemon.
     */
    private fun performCameraReconfigure() {
        Toast.makeText(this, getString(R.string.toast_clearing_camera_config), Toast.LENGTH_SHORT).show()
        logsViewModel.info("Camera", "Clearing saved camera probe config for re-probe")
        
        Thread {
            try {
                // Clear the camera section from unified config
                val emptyCameraConfig = org.json.JSONObject()
                emptyCameraConfig.put("probedCameraId", -1)
                emptyCameraConfig.put("probedSurfaceMode", -1)
                com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", emptyCameraConfig)
                
                runOnUiThread {
                    logsViewModel.info("Camera", "Camera config cleared — restarting daemon")
                    // Reuse the guarded settings flow: it checkpoints the open
                    // trip and aborts on any prepare-restart failure before the
                    // launcher is allowed to SIGKILL the daemon.
                    restartCameraDaemonForCameraSettings()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    logsViewModel.error("Camera", "Reconfigure failed: ${e.message}")
                    Toast.makeText(this, getString(R.string.toast_failed_with_message_x, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    // ==================== Battery Health (SOH) Dialog ====================

    /**
     * Shows a styled dialog with SOH status details and a reset button.
     * The headline value always comes from the daemon's canonical OEM-first resolver.
     * The properties file contains only estimator internals and must not be displayed.
     */
    private fun showBatteryHealthDialog() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        executor.execute {
            var sohPercent = "--"
            var source = "--"
            var method = "--"
            var nominalKwh = "--"
            var samples = "--"
            var lastUpdated = "--"
            var hasEstimate = false

            // Vehicle section state. Populated from /api/performance/soh status JSON.
            // Non-SOH estimator metadata can still fall back to the properties file.
            var modelId: String? = null
            var nominalKwhValue = 0.0
            var nominalSourceVal = "unset"
            var estimatedKwhValue = 0.0
            var calibrationSoh = 0.0
            var calibrationTs = 0L
            // Frame anchor state (PHEV-only, hidden on BEV).
            var framePeakKwh = -1.0
            var frameSamples = 0
            var frameRequiredSamples = 3
            var frameMismatch = false

            try {
                val sohFile = java.io.File("/data/local/tmp/abrp_soh_estimate.properties")
                if (sohFile.exists()) {
                    val props = java.util.Properties()
                    java.io.FileInputStream(sohFile).use { props.load(it) }

                    // Shape B: live formula + calibration anchor (separate, not blended).
                    val cal = props.getProperty("calibration_soh")?.toDoubleOrNull()
                    samples = if (cal != null && cal > 0) String.format("calib %.1f%%", cal) else "—"

                    val nominal = props.getProperty("nominal_capacity_kwh")?.toDoubleOrNull()
                    if (nominal != null && nominal > 0) {
                        nominalKwh = String.format("%.1f kWh", nominal)
                        nominalKwhValue = nominal
                    }

                    val ts = props.getProperty("last_updated")?.toLongOrNull()
                    if (ts != null && ts > 0) {
                        lastUpdated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(ts))
                    }

                    nominalSourceVal = props.getProperty("nominal_source") ?: "unset"
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "SOH file read failed: ${e.message}")
            }

            // Fetch the canonical SOH plus model/capacity diagnostics. A daemon outage
            // leaves the headline unavailable rather than exposing the moving raw estimate.
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val canonicalSoh = json.optDouble("displaySoh", -1.0)
                    val canonicalSource = json.optString("displaySource", "unavailable")
                    if (canonicalSoh > 0.0 && canonicalSoh <= 100.0 &&
                        canonicalSource != "unavailable") {
                        sohPercent = String.format("%.1f%%", canonicalSoh)
                        hasEstimate = true
                        source = canonicalSource
                        method = if (canonicalSource == "oem") "vehicle-reported" else "estimated"
                        lastUpdated = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date())
                    }
                    if (!json.isNull("modelId")) {
                        modelId = json.optString("modelId", "").ifEmpty { null }
                    }
                    nominalKwhValue = json.optDouble("nominalCapacityKwh", nominalKwhValue)
                    if (nominalKwhValue > 0.0) {
                        nominalKwh = String.format("%.1f kWh", nominalKwhValue)
                    }
                    nominalSourceVal = json.optString("nominalSource", nominalSourceVal)
                    val est = json.optDouble("estimatedCapacityKwh", -1.0)
                    if (est > 0) estimatedKwhValue = est
                    val calObj = json.optJSONObject("calibration")
                    if (calObj != null) {
                        calibrationSoh = calObj.optDouble("soh", -1.0)
                        calibrationTs = calObj.optLong("timestampMs", 0L)
                    }
                    val frameObj = json.optJSONObject("frameAnchor")
                    if (frameObj != null) {
                        framePeakKwh = frameObj.optDouble("peakKwh", -1.0)
                        frameSamples = frameObj.optInt("samples", 0)
                        frameRequiredSamples = frameObj.optInt("requiredSamples", 3)
                        frameMismatch = frameObj.optBoolean("mismatch", false)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) { /* keep non-SOH metadata fallbacks only */ }

            val finalSoh = sohPercent
            val finalSource = source
            val finalMethod = method
            val finalNominal = nominalKwh
            val finalSamples = samples
            val finalLastUpdated = lastUpdated
            val finalHasEstimate = hasEstimate
            val finalModelId = modelId
            val finalNominalKwh = nominalKwhValue
            val finalNominalSource = nominalSourceVal
            val finalEstimatedKwh = estimatedKwhValue
            val finalCalSoh = calibrationSoh
            val finalCalTs = calibrationTs
            val finalFramePeakKwh = framePeakKwh
            val finalFrameSamples = frameSamples
            val finalFrameRequiredSamples = frameRequiredSamples
            val finalFrameMismatch = frameMismatch

            runOnUiThread {
                val dialogView = layoutInflater.inflate(R.layout.dialog_battery_health, null)

                // Populate fields
                dialogView.findViewById<TextView>(R.id.tvSohPercent).text = finalSoh
                dialogView.findViewById<TextView>(R.id.tvSohSource).text = finalSource
                dialogView.findViewById<TextView>(R.id.tvSohMethod).text = finalMethod
                dialogView.findViewById<TextView>(R.id.tvSohCapacity).text = finalNominal
                dialogView.findViewById<TextView>(R.id.tvSohSamples).text = finalSamples
                dialogView.findViewById<TextView>(R.id.tvSohLastUpdated).text = finalLastUpdated

                // Vehicle section
                dialogView.findViewById<TextView>(R.id.tvSohModel).text =
                    if (finalModelId != null) modelDisplayName(finalModelId)
                    else getString(R.string.soh_dialog_model_not_selected)

                val packCapView = dialogView.findViewById<TextView>(R.id.tvSohPackCapacity)
                val packBadgeView = dialogView.findViewById<TextView>(R.id.tvSohPackCapacityBadge)
                if (finalNominalKwh > 0) {
                    packCapView.text = String.format("%.1f kWh", finalNominalKwh)
                    val badgeText = when (finalNominalSource) {
                        "user" -> getString(R.string.soh_dialog_source_user)
                        "auto" -> getString(R.string.soh_dialog_source_auto)
                        else -> null
                    }
                    if (badgeText != null) {
                        packBadgeView.text = "(" + badgeText + ")"
                        packBadgeView.visibility = View.VISIBLE
                    } else {
                        packBadgeView.visibility = View.GONE
                    }
                } else {
                    packCapView.text = getString(R.string.soh_dialog_capacity_not_detected)
                    packBadgeView.visibility = View.GONE
                }

                val rowEst = dialogView.findViewById<View>(R.id.rowSohEstimatedCapacity)
                if (finalEstimatedKwh > 0) {
                    dialogView.findViewById<TextView>(R.id.tvSohEstimatedCapacity).text =
                        String.format("%.1f kWh", finalEstimatedKwh)
                    rowEst.visibility = View.VISIBLE
                } else {
                    rowEst.visibility = View.GONE
                }

                val rowCal = dialogView.findViewById<View>(R.id.rowSohCalibrationAnchor)
                if (finalCalSoh > 0 && finalCalTs > 0) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(finalCalTs))
                    dialogView.findViewById<TextView>(R.id.tvSohCalibrationAnchor).text =
                        getString(R.string.soh_dialog_calibration_format, finalCalSoh, date)
                    rowCal.visibility = View.VISIBLE
                } else {
                    rowCal.visibility = View.GONE
                }

                // Frame anchor row: shown on PHEV once any peak observation
                // exists. Pending vs stable wording driven by samples<required.
                val rowFrame = dialogView.findViewById<View>(R.id.rowSohFrameAnchor)
                if (finalFramePeakKwh > 0) {
                    val pending = finalFrameSamples < finalFrameRequiredSamples
                    dialogView.findViewById<TextView>(R.id.tvSohFrameAnchor).text =
                        if (pending) getString(R.string.soh_dialog_frame_anchor_pending_format,
                            finalFramePeakKwh, finalFrameSamples, finalFrameRequiredSamples)
                        else getString(R.string.soh_dialog_frame_anchor_format,
                            finalFramePeakKwh, finalFrameSamples, finalFrameRequiredSamples)
                    rowFrame.visibility = View.VISIBLE
                } else {
                    rowFrame.visibility = View.GONE
                }

                // Frame mismatch warning card — only when the anchor has
                // stabilized AND the ratio is < 0.85. Daemon sets the boolean.
                val cardMismatch = dialogView.findViewById<View>(R.id.cardSohFrameMismatch)
                if (finalFrameMismatch && finalFramePeakKwh > 0 && finalNominalKwh > 0) {
                    dialogView.findViewById<TextView>(R.id.tvSohFrameMismatch).text =
                        getString(R.string.soh_dialog_frame_mismatch_format,
                            finalFramePeakKwh, finalNominalKwh)
                    cardMismatch.visibility = View.VISIBLE
                } else {
                    cardMismatch.visibility = View.GONE
                }

                // Status text
                val statusView = dialogView.findViewById<TextView>(R.id.tvSohStatus)
                if (finalHasEstimate) {
                    statusView.text = getString(R.string.soh_estimation_active)
                    statusView.setTextColor(resources.getColor(R.color.brand_primary, null))
                } else {
                    statusView.text = getString(R.string.soh_no_estimate_yet)
                    statusView.setTextColor(resources.getColor(R.color.text_muted, null))
                }

                // SOH percent color based on health
                val sohView = dialogView.findViewById<TextView>(R.id.tvSohPercent)
                if (finalHasEstimate) {
                    val sohVal = finalSoh.replace("%", "").toDoubleOrNull() ?: 0.0
                    val colorRes = when {
                        sohVal >= 85 -> R.color.brand_primary   // Good
                        sohVal >= 70 -> R.color.status_starting // Moderate
                        else -> R.color.status_error            // Degraded
                    }
                    sohView.setTextColor(resources.getColor(colorRes, null))
                }

                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.dialog_close), null)
                    .create()

                // Wire up reset button
                dialogView.findViewById<TextView>(R.id.btnResetSoh).setOnClickListener {
                    dialog.dismiss()
                    confirmSohReset()
                }

                // One-tap "Use observed value" — POSTs the observed peak as
                // the new user nominal so the headline SOH updates without
                // the user navigating back to the input UI. Disable the
                // button while in flight to avoid double-submits, then close
                // the dialog so the next open shows the recomputed state.
                if (finalFrameMismatch && finalFramePeakKwh > 0) {
                    val btn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(
                        R.id.btnSohUseObservedValue)
                    btn.setOnClickListener {
                        btn.isEnabled = false
                        btn.text = getString(R.string.soh_dialog_use_observed_value_pending)
                        applyObservedNominal(finalFramePeakKwh) { dialog.dismiss() }
                    }
                }

                dialog.show()
            }
        }
    }

    /**
     * POSTs the peak-anchor kWh value as the new user nominal so the SOH
     * headline reflects the BMS's full-charge frame. The daemon does the
     * floor / range validation; we just surface success/failure to a toast
     * and dismiss the dialog so a re-open paints the corrected numbers.
     */
    private fun applyObservedNominal(observedKwh: Double, onComplete: () -> Unit) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            var ok = false
            var errMsg: String? = null
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/nominal", "POST", 3000, 5000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // Round to 1 decimal so the persisted value matches the UI's
                // own precision; the API accepts arbitrary doubles but the
                // user-visible suggestion is one decimal.
                val payload = "{\"nominalKwh\":${"%.1f".format(observedKwh)}}"
                conn.outputStream.use { it.write(payload.toByteArray()) }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    ok = json.optBoolean("success", true)
                    if (!ok) errMsg = json.optString("error", "")
                } else {
                    errMsg = "HTTP ${conn.responseCode}"
                }
                conn.disconnect()
            } catch (e: Throwable) {
                errMsg = e.message ?: e.javaClass.simpleName
            }

            runOnUiThread {
                if (ok) {
                    Toast.makeText(this,
                        getString(R.string.toast_soh_observed_value_saved, observedKwh),
                        Toast.LENGTH_LONG).show()
                    logsViewModel.info("SOH",
                        "Pack capacity updated to ${"%.1f".format(observedKwh)} kWh from observed peak")
                } else {
                    Toast.makeText(this,
                        getString(R.string.toast_soh_observed_value_failed, errMsg ?: ""),
                        Toast.LENGTH_LONG).show()
                }
                onComplete()
            }
        }
    }

    private fun modelDisplayName(modelId: String?): String {
        return when (modelId?.lowercase()) {
            null -> "—"
            "seal" -> "BYD Seal"
            "atto3", "atto-3" -> "BYD Atto 3"
            "atto2", "atto-2" -> "BYD Atto 2"
            "atto1", "atto-1" -> "BYD Atto 1"
            "han" -> "BYD Han"
            "tang" -> "BYD Tang"
            "song" -> "BYD Song"
            "qin" -> "BYD Qin"
            "dolphin" -> "BYD Dolphin"
            "seagull" -> getString(R.string.vehicle_model_seagull)
            "sealion6" -> "BYD Sealion 6"
            "sealion7" -> "BYD Sealion 7"
            "shark" -> "BYD Shark"
            "sealu", "seal-u" -> "BYD Seal U"
            else -> modelId.replaceFirstChar { it.uppercase() }
        }
    }
    
    /**
     * Confirmation dialog before resetting SOH estimation.
     */
    private fun confirmSohReset() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_reset_soh_title))
            .setMessage(getString(R.string.dialog_reset_soh_message))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ ->
                performSohReset()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    /**
     * Perform the actual SOH reset by deleting the properties file.
     * The daemon's SohEstimator will detect the missing file and re-seed.
     */
    private fun performSohReset() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                // Use daemon API (daemon owns the file, has write permissions)
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/reset", "POST", 3000, 3000)
                conn.doOutput = true
                conn.outputStream.use { it.write("{}".toByteArray()) }
                val responseCode = conn.responseCode
                conn.disconnect()
                
                if (responseCode == 200) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.toast_soh_reset_success), Toast.LENGTH_LONG).show()
                        logsViewModel.info("SOH", "SOH estimation reset by user")
                    }
                } else {
                    // Fallback: try direct file delete (works if app has permissions)
                    val sohFile = java.io.File("/data/local/tmp/abrp_soh_estimate.properties")
                    val deleted = if (sohFile.exists()) sohFile.delete() else true
                    runOnUiThread {
                        if (deleted) {
                            Toast.makeText(this, getString(R.string.toast_soh_reset_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, getString(R.string.toast_soh_reset_failed_no_daemon), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_soh_reset_failed_with_message, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==================== Reset Data Dialog ====================

    /** Map drawer menu category id → API category name (must match server). */
    private val resetCategoryMapping = listOf(
        R.id.cbResetTrips to "trips",
        R.id.cbResetSocHistory to "socHistory",
        R.id.cbResetSoh to "soh",
        R.id.cbResetAbrpToken to "abrpToken",
        R.id.cbResetBydCloud to "bydCloud",
        R.id.cbResetMediaRecordings to "mediaRecordings",
        R.id.cbResetMediaSurveillance to "mediaSurveillance",
        R.id.cbResetMediaProximity to "mediaProximity",
        R.id.cbResetMediaTrips to "mediaTrips"
    )

    private fun showResetDataDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reset_data, null)

        val checkboxes: List<Pair<com.google.android.material.checkbox.MaterialCheckBox, String>> =
            resetCategoryMapping.map { (id, cat) ->
                dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id) to cat
            }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.dialog_reset_selected), null)  // Wired below to allow keep-open on validate
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()

        // Quick toggles
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetSelectAll)
            .setOnClickListener {
                checkboxes.forEach { it.first.isChecked = true }
            }
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetClearAll)
            .setOnClickListener {
                checkboxes.forEach { it.first.isChecked = false }
            }

        dialog.setOnShowListener {
            val ok = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            ok.setTextColor(resources.getColor(R.color.status_error, null))
            ok.setOnClickListener {
                val selected = checkboxes.filter { it.first.isChecked }.map { it.second }
                if (selected.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_select_at_least_one_category), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                confirmAndPerformReset(selected)
            }
        }

        dialog.show()
    }

    private fun confirmAndPerformReset(categories: List<String>) {
        val labels = mapOf(
            "trips" to getString(R.string.reset_label_trips),
            "socHistory" to getString(R.string.reset_label_soc_history),
            "soh" to getString(R.string.reset_label_soh),
            "abrpToken" to getString(R.string.reset_label_abrp_token),
            "bydCloud" to getString(R.string.reset_label_byd_cloud),
            "mediaRecordings" to getString(R.string.reset_label_recordings),
            "mediaSurveillance" to getString(R.string.reset_label_sentry_events),
            "mediaProximity" to getString(R.string.reset_label_proximity),
            "mediaTrips" to getString(R.string.reset_label_trip_files)
        )
        val list = categories.joinToString("\n") { "• " + (labels[it] ?: it) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_reset_following_title))
            .setMessage(getString(R.string.dialog_reset_following_message, list))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ -> performReset(categories, labels) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun performReset(
        categories: List<String>,
        labels: Map<String, String>
    ) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val payload = org.json.JSONObject().apply {
                    put("categories", org.json.JSONArray(categories))
                }
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/reset", "POST", 5000, 15000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                conn.disconnect()

                val data = try { org.json.JSONObject(body) } catch (e: Exception) { null }
                runOnUiThread {
                    if (data != null && data.optBoolean("success", false)) {
                        val results = data.optJSONObject("results")
                        val lines = StringBuilder()
                        for (cat in categories) {
                            val r = results?.optJSONObject(cat)
                            val label = labels[cat] ?: cat
                            if (r != null && r.optBoolean("success", false)) {
                                val detail = when {
                                    r.has("rowsDeleted") -> " (${r.optLong("rowsDeleted")} rows)"
                                    r.has("filesDeleted") -> " (${r.optLong("filesDeleted")} files)"
                                    else -> ""
                                }
                                lines.append("• ").append(label).append(detail).append("\n")
                            } else {
                                val err = r?.optString("error", "failed") ?: "failed"
                                lines.append("• ").append(label).append(" — ").append(err).append("\n")
                            }
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                            .setIcon(R.drawable.ic_check_circle)
                            .setTitle(getString(R.string.dialog_reset_complete_title))
                            .setMessage(lines.toString().trim())
                            .setPositiveButton(getString(R.string.dialog_ok), null)
                            .show()
                        logsViewModel.info("Reset", "Categories: ${categories.joinToString(",")}")
                    } else {
                        val err = data?.optString("error") ?: "HTTP $code"
                        Toast.makeText(this, getString(R.string.toast_reset_failed_with_error, err), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_reset_failed_with_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== Traffic Monitor Management ====================

    /** Track current traffic monitor state to show correct button */
    private var trafficMonitorEnabled: Boolean? = null
    
    /**
     * Check if BYD Traffic Monitor app is currently enabled or disabled.
     * Updates the drawer menu item title accordingly.
     * 
     * Uses ADB shell — if ADB isn't connected yet, shows a "checking" state
     * and retries automatically when the drawer is opened.
     */
    private fun checkTrafficMonitorStatus() {
        // Show loading state while we check
        updateTrafficMonitorMenuItemText(getString(R.string.traffic_monitor_loading))
        
        // Reuse shared adbLauncher (avoids per-call leak). The pm verbs and the
        // probe parsing live in TrafficMonitorPolicy so this UI read and the
        // daemon's boot reconcile can never disagree about what "disabled" means.
        daemonStartupManager.adbLauncher.executeShellCommand(
            TrafficMonitorPolicy.stateProbeCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    val isDisabled = TrafficMonitorPolicy.probeSaysDisabled(message)
                    runOnUiThread {
                        trafficMonitorEnabled = !isDisabled
                        updateTrafficMonitorMenuItem(!isDisabled)
                    }
                }
                override fun onLaunched() {
                    // Command completed — if onLog wasn't called, default to enabled
                    if (trafficMonitorEnabled == null) {
                        runOnUiThread {
                            trafficMonitorEnabled = true
                            updateTrafficMonitorMenuItem(true)
                        }
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        // Actual ADB connection failure
                        trafficMonitorEnabled = null
                        updateTrafficMonitorMenuItemText(getString(R.string.traffic_monitor_tap_to_check))
                    }
                }
            }
        )
    }
    
    /**
     * Drawer-era helpers — kept as no-ops because checkTrafficMonitorStatus()
     * still calls them, and we want behavior parity (the status check still
     * runs; it just doesn't have a drawer menu item to retitle anymore).
     * Settings → Diagnostics shows the traffic monitor in dialog form.
     */
    private fun updateTrafficMonitorMenuItem(enabled: Boolean) { /* no-op in rail shell */ }
    private fun updateTrafficMonitorMenuItemText(text: String) { /* no-op in rail shell */ }
    
    /**
     * Handle traffic monitor menu item click.
     * Shows an informational dialog explaining what the traffic monitor is,
     * why disabling it is recommended, and lets the user take action.
     */
    private fun onTrafficMonitorClicked() {
        val currentlyEnabled = trafficMonitorEnabled
        
        if (currentlyEnabled == null) {
            // ADB not connected — retry the check and show explanation
            checkTrafficMonitorStatus()
            
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_warning)
                .setTitle(getString(R.string.dialog_traffic_cannot_check_title))
                .setMessage(getString(R.string.dialog_traffic_cannot_check_message))
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
            return
        }

        if (currentlyEnabled) {
            // Currently enabled — offer to disable with full explanation
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_traffic_monitor)
                .setTitle(getString(R.string.dialog_traffic_disable_title))
                .setMessage(getString(R.string.dialog_traffic_disable_message))
                .setPositiveButton(getString(R.string.dialog_disable)) { _, _ ->
                    setTrafficMonitorEnabled(false)
                }
                .setNegativeButton(getString(R.string.dialog_keep_enabled), null)
                .show()
        } else {
            // Currently disabled — offer to re-enable
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_traffic_monitor)
                .setTitle(getString(R.string.dialog_traffic_enable_title))
                .setMessage(getString(R.string.dialog_traffic_enable_message))
                .setPositiveButton(getString(R.string.dialog_enable)) { _, _ ->
                    setTrafficMonitorEnabled(true)
                }
                .setNegativeButton(getString(R.string.dialog_keep_disabled), null)
                .show()
        }
    }
    
    /**
     * Enable or disable the BYD Traffic Monitor package via ADB shell, then persist
     * the choice so it survives a firmware OTA.
     *
     * The pm state alone is not durable: an OTA re-scans com.byd.trafficmonitor and
     * resurrects it, which is why users had to re-disable it after every update.
     * The persisted flag is what CameraDaemon re-applies on boot. It is written only
     * after the shell payload's own probe confirms the transition actually landed —
     * a marker for a state we never reached would make the daemon fight the user.
     */
    private fun setTrafficMonitorEnabled(enable: Boolean) {

        try {
            packageManager.getPackageInfo(TrafficMonitorPolicy.PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            android.util.Log.w("TrafficMonitor", "${TrafficMonitorPolicy.PACKAGE} not installed on this firmware — ignoring toggle.")
            return
        }

        val wantDisabled = !enable
        val action = if (enable) "Enabling" else "Disabling"
        Toast.makeText(this, getString(R.string.toast_traffic_monitor_changing, action), Toast.LENGTH_SHORT).show()

        // Mutate + verify in one ADB roundtrip; the trailing probe is what we trust.
        var shellOutput = ""

        // Reuse shared adbLauncher (avoids per-call leak).
        daemonStartupManager.adbLauncher.executeShellCommand(
            TrafficMonitorPolicy.applyThenProbeCommand(wantDisabled),
            object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                shellOutput = message
                android.util.Log.i("TrafficMonitor", "$action result: $message")
            }

            override fun onLaunched() {
                val verified = TrafficMonitorPolicy.stateAfterApply(shellOutput)
                val expected = if (wantDisabled) TrafficMonitorPolicy.STATE_DISABLED
                               else TrafficMonitorPolicy.STATE_ENABLED
                if (verified != expected) {
                    // pm returned but the package did not move — report the real
                    // state instead of a green checkmark over an unchanged system.
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        // null == "unknown, tap to re-check" — don't render an
                        // unparseable probe as a confident "disabled".
                        trafficMonitorEnabled = when (verified) {
                            TrafficMonitorPolicy.STATE_ENABLED -> true
                            TrafficMonitorPolicy.STATE_DISABLED -> false
                            else -> null
                        }
                        trafficMonitorEnabled?.let { updateTrafficMonitorMenuItem(it) }
                        logsViewModel.error("TrafficMonitor",
                            "pm reported no change (wanted $expected, probe says $verified)")
                        Toast.makeText(this@MainActivity,
                            getString(R.string.toast_failed_with_message_x, verified),
                            Toast.LENGTH_LONG).show()
                    }
                    return
                }

                // Off the UI thread: the config write blocks on daemon IPC plus a
                // full JSON rewrite.
                Thread {
                    val persisted = TrafficMonitorPolicy.setDisableRequested(wantDisabled)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        trafficMonitorEnabled = enable
                        updateTrafficMonitorMenuItem(enable)

                        val state = if (enable) "enabled" else "disabled"
                        logsViewModel.info("TrafficMonitor", "BYD Traffic Monitor $state")
                        if (!persisted) {
                            // Live now, but nothing will re-apply it after an OTA.
                            logsViewModel.warn("TrafficMonitor",
                                "Could not persist the traffic-monitor preference — it will not survive a firmware update")
                            Toast.makeText(this@MainActivity,
                                getString(R.string.toast_traffic_monitor_persist_failed),
                                Toast.LENGTH_LONG).show()
                        }

                        // Show reboot reminder
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity, R.style.Theme_Overdrive_M3_Dialog)
                            .setIcon(R.drawable.ic_check_circle)
                            .setTitle(getString(R.string.dialog_traffic_status_title, state.replaceFirstChar { it.uppercase() }))
                            .setMessage(getString(R.string.dialog_traffic_reboot_message))
                            .setPositiveButton(getString(R.string.dialog_ok), null)
                            .show()
                    }
                }.start()
            }

            override fun onError(error: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this@MainActivity, getString(R.string.toast_failed_with_message_x, error), Toast.LENGTH_LONG).show()
                    logsViewModel.error("TrafficMonitor", "Failed to ${if (enable) "enable" else "disable"}: $error")
                }
            }
        })
    }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Orientation and screen-size changes recreate this Activity so Android
        // reinflates the correct layout/layout-land hierarchy. Re-assert the
        // rail only for configuration changes the Activity explicitly handles.
        if (::navigationRailScroll.isInitialized) {
            setNavigationRailExpanded(navigationRailExpanded, animate = false)
        }
        // Keep the onboarding overlay aligned for handled configuration changes.
        onboardingHost?.onConfigChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_RAIL_EXPANDED, navigationRailExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        screenshotPrivacyController?.stop()
        screenshotPrivacyController = null
        navigationRailAnimator?.cancel()
        navigationRailAnimator = null
        if (::navigationRailScroll.isInitialized) {
            navigationRailScroll.onRailSwipe = null
            // The label cross-fade posts a delayed animation; the chevron runs one
            // too. Cancel both so nothing stays queued against a destroyed view.
            cancelRailViewAnimations()
        }
        // Remove log listener
        if (!remoteDevSession) LogManager.setLogListener(null)
        // Tear down the onboarding overlay + its ACC receiver (mirrors the auth-callback
        // teardown below — any guard cleared only in a callback needs a destroy path).
        onboardingHost?.dismiss()
        onboardingHost = null
        // Cancel any in-flight nav-wait poll so it can't retain the Activity past destroy.
        navPollRunnable?.let { mainHandler.removeCallbacks(it) }
        navPollRunnable = null
        // Remove ADB auth callback
        if (!remoteDevSession) com.overdrive.app.launcher.AdbShellExecutor.setAuthCallback(null)
        // Cancel the periodic update check so the Runnable doesn't leak the
        // activity reference after recreate.
        updateCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        updateCheckRunnable = null
        // Stop the install-progress poll loop (Hide normally clears it, but a
        // destroy mid-install would otherwise leave the reposting Runnable
        // holding this activity until the next tick no-ops on isDestroyed).
        updatePollRunnable?.let { mainHandler.removeCallbacks(it) }
        updatePollRunnable = null
        // Cancel any in-flight post-update watchdog. Without this the
        // Handler.postDelayed lambda holds a reference to this@MainActivity
        // for up to 30 seconds, leaking the activity if the user backs out
        // mid-update. The watchdog itself also short-circuits on
        // isFinishing/isDestroyed but we cancel here for cleanliness.
        synchronized (daemonStartupCoordinator) {
            daemonStartupCoordinator.pendingWatchdog?.let {
                daemonStartupCoordinator.watchdogHandler?.removeCallbacks(it)
            }
            daemonStartupCoordinator.pendingWatchdog = null
            daemonStartupCoordinator.watchdogHandler = null
        }
        // Stop the Activity-scoped DaemonStartupManager's health-check loop and quit
        // its dedicated "DaemonHealthCheck" HandlerThread. Without this it leaks one
        // live OS thread per Activity recreate (theme switch, language change, any
        // config change outside the manifest's configChanges set), each pinning the
        // destroyed Activity via the Context it was constructed with.
        //
        // Deliberately NOT the full cleanup(): that also calls
        // adbLauncher.releasePerInstanceResources() and shuts down the zrok
        // launcher/executor, and this Activity's adbLauncher is shared with other
        // components (AdbConsoleFragment, AppUpdater, the update-APK rm at ~line 231).
        // Releasing those here would be a behaviour change beyond fixing the thread
        // leak I introduced, so this stops only what I added.
        if (::daemonStartupManager.isInitialized) {
            try { daemonStartupManager.stopHealthCheckThread() } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "stopHealthCheckThread failed: ${t.message}")
            }
        }
        // Note: We intentionally do NOT call cleanupAll() here
        // Daemons should persist after app closure
        super.onDestroy()
    }

    // ==========================================================
    //  Public shims invoked by SettingsFragment / DiagnosticsFragment
    //  Behaviour identical to the old drawer items — no logic change.
    // ==========================================================

    fun invokeCheckForUpdates() = checkForAppUpdateManual()
    fun invokeResetDataDialog() = showResetDataDialog()
    fun invokeBatteryHealthAction() = showBatteryHealthDialog()
    fun invokeReconfigureCameraAction() = onReconfigureCameraClicked()
    fun invokeTrafficMonitorAction() {
        // Match drawer-open behavior: refresh status before showing dialog.
        try {
            packageManager.getPackageInfo(TrafficMonitorPolicy.PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            android.util.Log.w("TrafficMonitor", "${TrafficMonitorPolicy.PACKAGE} not installed on this firmware — ignoring toggle.")
            return
        }
        checkTrafficMonitorStatus()
        onTrafficMonitorClicked()
    }

    // ===================== Onboarding bridge =====================
    // Thin public entry points the onboarding coaches call. They reuse the EXISTING
    // dialogs/handlers — onboarding never reimplements vehicle/camera logic.

    /** Set by CameraWizardCoach before it opens the camera dialog; consumed on show(). */
    private var pendingCameraOnboardingCoach: com.overdrive.app.onboarding.CameraWizardCoach? = null

    /** Open the real camera-mapping dialog for the onboarding wizard to coach over. */
    fun openCameraMappingForOnboarding(coach: com.overdrive.app.onboarding.CameraWizardCoach) {
        pendingCameraOnboardingCoach = coach
        onReconfigureCameraClicked()
    }

    /** Drop a pending coach (its open-failsafe fired) so a late fetch won't re-attach it. */
    fun clearPendingCameraOnboardingCoach() {
        pendingCameraOnboardingCoach = null
    }

    /**
     * Run the real ~10s daemon restart so a pano-id save actually goes live during the
     * camera wizard (btnSaveManualCameraId alone does not restart). Public wrapper around
     * the private restartCameraDaemonForCameraSettings.
     */
    fun restartCameraDaemonForOnboarding() = restartCameraDaemonForCameraSettings()

    /**
     * Open the real vehicle capacity/model dialog for the vehicle chapter.
     * Completion waits for the user's save/reset/cancel action so the camera
     * chapter cannot resolve against the old renderer fallback model.
     */
    fun openVehicleProfileForOnboarding(onFinished: () -> Unit): Boolean {
        val nav = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                as? androidx.navigation.fragment.NavHostFragment
        val dash = nav?.childFragmentManager?.primaryNavigationFragment
                as? com.overdrive.app.ui.fragment.DashboardFragment
                ?: return false
        return dash.showVehicleCapacityDialog(onFinished)
    }

    /** Live DashboardFragment root for the orientation tour anchors (null if not current). */
    fun currentDashboardRoot(): android.view.View? {
        val nav = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                as? androidx.navigation.fragment.NavHostFragment
        val dash = nav?.childFragmentManager?.primaryNavigationFragment
                as? com.overdrive.app.ui.fragment.DashboardFragment
        return dash?.view
    }

    /** Expert-tour entry: land the user on Diagnostics (advanced camera knobs live there). */
    fun openExpertTourEntry() {
        try { navController.navigate(R.id.diagnosticsFragment) } catch (_: Throwable) {}
    }

    /**
     * A nav-rail ROW view by id (e.g. R.id.railDestMap), for the Expert tour to spotlight
     * entries that aren't nav destinations — notably the Hazard Map row, which launches a
     * separate Activity (destinationId 0) so navigateForOnboarding can't reach it. Rail
     * rows are always laid out regardless of the current fragment, so they're reliable
     * anchors. Returns null in portrait where the rail row may be absent → caller centers.
     */
    fun railRowView(rowId: Int): android.view.View? =
        try { navigationRail.findViewById(rowId) } catch (_: Throwable) { null }

    /**
     * Navigate to any nav destination for the Expert tour (navController is private, so
     * coaches route through this). Returns false if the id isn't in the current graph.
     */
    fun navigateForOnboarding(destId: Int): Boolean {
        return try { navController.navigate(destId); true } catch (_: Throwable) { false }
    }

    /** True if the given nav destination is currently shown (tour waits on this). */
    fun isCurrentDestination(destId: Int): Boolean =
        try { navController.currentDestination?.id == destId } catch (_: Throwable) { false }

    /**
     * Root view of the currently-shown native fragment (any type), for the Expert tour
     * to resolve anchors. Null if the destination isn't laid out yet or is a WebView page
     * with no inner anchors — callers degrade to a centered card.
     */
    fun currentFragmentRoot(): android.view.View? = currentNavFragment()?.view

    /** The currently-shown nav destination fragment INSTANCE (not its view). */
    private fun currentNavFragment(): androidx.fragment.app.Fragment? {
        val nav = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                as? androidx.navigation.fragment.NavHostFragment
        return nav?.childFragmentManager?.primaryNavigationFragment
    }

    /**
     * Navigate to [destId] (if not already there) and invoke [onReady] with the TARGET
     * fragment's laid-out root once navigation has actually committed.
     *
     * Why fragment-IDENTITY, not just currentDestination: navController.navigate() updates
     * currentDestination.id SYNCHRONOUSLY, but FragmentNavigator commits the fragment
     * transaction ASYNCHRONOUSLY (commit(), not commitNow) — so for one frame after
     * navigate(), currentDestination.id already == destId while primaryNavigationFragment
     * still points at the PREVIOUS fragment (whose view is laid out, width>0). A
     * "committed && width>0" gate is therefore satisfied by the STALE fragment on the
     * first tick and spotlights the wrong screen. We capture the pre-navigate fragment
     * instance and wait until primaryNavigationFragment is a DIFFERENT, laid-out instance
     * (or, for a same-destination no-op nav, accept the current one immediately).
     */
    fun navigateForOnboardingThen(destId: Int, onReady: (android.view.View?) -> Unit) {
        // Cancel any in-flight poll from a prior call so its stale onReady can't fire
        // against this (or a later) chapter. Chapters run sequentially today, but this
        // makes re-entry safe regardless.
        navPollRunnable?.let { mainHandler.removeCallbacks(it) }
        navPollRunnable = null
        val alreadyThere = isCurrentDestination(destId)
        val previousFragment = currentNavFragment()
        if (!alreadyThere) {
            if (!navigateForOnboarding(destId)) { onReady(null); return }
        }
        fun attempt(remaining: Int) {
            if (isFinishing || isDestroyed) { onReady(null); return }
            val committed = isCurrentDestination(destId)
            val frag = currentNavFragment()
            val root = frag?.view
            // Ready when: destination committed AND (we didn't navigate / it's a NEW
            // fragment instance, not the stale previous one) AND the root is measured.
            val isFreshOrSameDest = alreadyThere || (frag != null && frag !== previousFragment)
            if (committed && isFreshOrSameDest && root != null && root.width > 0) {
                onReady(root); return
            }
            if (remaining <= 0) { onReady(if (committed && isFreshOrSameDest) root else null); return }
            val r = Runnable { attempt(remaining - 1) }
            navPollRunnable = r
            mainHandler.postDelayed(r, NAV_POLL_INTERVAL_MS)
        }
        attempt(NAV_POLL_MAX_ATTEMPTS)
    }

    private var navPollRunnable: Runnable? = null

    // A rail tap that arrived while FragmentManager state was saved (config transition
    // settling / backgrounding) is stashed here and re-driven from onResume, so the tap is
    // deferred rather than silently dropped. Cleared once consumed or when a later navigation
    // supersedes it.
    private var pendingRailDestination: Int? = null

    /**
     * Orientation-agnostic Settings anchor for the Expert tour (portrait card vs landscape
     * sub-rail row). The live SettingsFragment resolves it and, in landscape, selects the
     * section so its pane is shown. Null if Settings isn't current → caller centers.
     */
    fun settingsTourAnchor(
        target: com.overdrive.app.ui.fragment.SettingsFragment.TourTarget,
    ): android.view.View? {
        val nav = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                as? androidx.navigation.fragment.NavHostFragment
        val settings = nav?.childFragmentManager?.primaryNavigationFragment
                as? com.overdrive.app.ui.fragment.SettingsFragment
        return settings?.tourAnchorFor(target)
    }

    private fun ensureOnboardingHost(): com.overdrive.app.onboarding.OnboardingHost =
        onboardingHost ?: com.overdrive.app.onboarding.OnboardingHost(
            this, daemonStartupManager.adbLauncher,
        ).also { onboardingHost = it }

    /** Toolbar "?" — opens the Expert chapter menu (the on-demand help tour). */
    fun startOnboardingReplay() = ensureOnboardingHost().startExpertTour()

    /** Hidden Diagnostics long-press — wipe onboarding state and re-run the full novice track. */
    fun resetAndReplayOnboarding() {
        com.overdrive.app.onboarding.OnboardingState.get(this).reset()
        // Also clear the daemon-side operatingModeSetByUser marker so the replayed
        // session doesn't inherit the prior session's "user chose a mode" flag —
        // otherwise flushPendingOperatingMode would GET a stale true and wrongly drop a
        // legitimate new replay pick. reset() already wiped the app-private prefs
        // (modeChosen, pendingOperatingMode); this keeps the daemon flag on the same
        // reset boundary. Best-effort + retrying; the replay pick's own POST is ungated.
        com.overdrive.app.onboarding.OnboardingHost.clearOperatingModeUserFlag()
        ensureOnboardingHost().startReplay()
    }

    companion object {
        /** Deep-link extra consumed by [handleNavigateExtra] (launcher glance widgets). */
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val EXTRA_REMOTE_DEV_SESSION = "remote_dev_session"
    }
}
