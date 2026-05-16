package com.overdrive.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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
import com.overdrive.app.ui.model.AccessMode
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.LogsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.BuildConfig
import com.overdrive.app.R
import com.overdrive.app.util.BydDataCacheWhitelist

/**
 * Main activity with drawer navigation and modern UI.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    
    private val mainViewModel: MainViewModel by viewModels()
    private val daemonsViewModel: DaemonsViewModel by viewModels()
    private val logsViewModel: LogsViewModel by viewModels()
    private var appUpdater: com.overdrive.app.updater.AppUpdater? = null
    
    // Daemon startup manager
    private lateinit var daemonStartupManager: DaemonStartupManager
    
    // UI elements
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigationView: NavigationView
    private lateinit var switchAccessMode: SwitchMaterial
    private lateinit var tvAccessMode: TextView
    private lateinit var tvCurrentUrl: TextView
    private lateinit var urlBar: View
    private lateinit var statusIndicator: View
    private lateinit var urlStatusDot: View
    private lateinit var btnCopyUrl: ImageButton
    
    // Flag to prevent recursive switch updates
    private var isUpdatingSwitch = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_new)

        // Storage setup is posted off the onCreate critical path so a failure
        // (e.g. ROM lacking the All-Files-Access Settings activity on BYD SL7)
        // cannot abort activity launch. See setupStorageDirectories().
        window.decorView.post {
            try {
                setupStorageDirectories()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Deferred storage setup failed: ${e.message}", e)
            }
        }

        // Initialize DeviceIdGenerator with ADB executor for file sync
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
        
        initViews()
        setupNavigation(savedInstanceState)
        setupAccessModeToggle()
        setupCopyButton()
        setupLogListener()
        observeViewModels()
        
        // Initialize daemon startup manager
        daemonStartupManager = DaemonStartupManager(this, daemonsViewModel)
        daemonsViewModel.setStartupManager(daemonStartupManager)
        
        // Setup ADB auth callback to re-initialize when auth is granted
        setupAdbAuthCallback()
        
        // Log app start
        logsViewModel.info("App", "OverDrive started")

        // Seed out-of-process revival watchdog so the process gets resurrected
        // if it ever gets force-stopped or OOM-killed without an external event.
        try {
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
        startLocationSidecarService()
        
        // Initialize daemons after a short delay to allow ADB connection.
        // If this is a post-update launch, run UpdateLifecycle.hardResetDaemons
        // FIRST so any zombie daemons / watchdogs from the previous install are
        // dead before the new daemon launcher starts. See UpdateLifecycle for
        // the sentinel handshake details.
        val isPostUpdate = com.overdrive.app.updater.UpdateLifecycle
            .isPostUpdateLaunch(this, intent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Sync device ID to file synchronously before daemon startup
            Thread {
                try {
                    val synced = com.overdrive.app.util.DeviceIdGenerator.syncDeviceIdToFileSync(this)
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
                    logsViewModel.info("Update", "Post-update launch — hard-resetting daemons before startup")
                    com.overdrive.app.updater.UpdateLifecycle.hardResetDaemons(this) {
                        // Consume the just-updated marker only after the cleanup
                        // completes. A crash mid-reset will leave the sentinel
                        // in place so the next launch retries.
                        val updatedVersion = com.overdrive.app.updater.AppUpdater
                            .consumeJustUpdatedVersion(this)
                        if (updatedVersion != null) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.toast_updated_to, updatedVersion), Toast.LENGTH_LONG).show()
                                logsViewModel.info("Update", "App updated to $updatedVersion")
                            }
                            // Plant the Telegram post-update hint file so when
                            // the cloudflared tunnel comes back with a NEW URL,
                            // the user sees "🔄 Updated to vX — new tunnel URL"
                            // instead of the generic "URL changed" message.
                            // The daemon deletes the hint after one read, so
                            // subsequent (non-update) tunnel restarts go back
                            // to the normal copy.
                            try {
                                val adb = com.overdrive.app.launcher.AdbDaemonLauncher(this)
                                val hintFile = com.overdrive.app.updater
                                    .UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE
                                adb.executeShellCommand(
                                    "echo '$updatedVersion' > $hintFile",
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
                        startDaemons.run()
                    }
                } else {
                    startDaemons.run()
                }
            }.start()
        }, 1000)
        
        // Handle Location start intent (from SentryDaemon restart)
        handleLocationStartIntent(intent)
        
        // Check traffic monitor status early so drawer shows correct state
        checkTrafficMonitorStatus()
        
        // Check for app updates (delayed to not block startup)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Clean up any leftover update APK from previous install
            val adb = com.overdrive.app.launcher.AdbDaemonLauncher(this)
            adb.executeShellCommand("rm -f /data/local/tmp/overdrive_update.apk", object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            })

            // Show post-update message if app was just updated
            val updatedVersion = com.overdrive.app.updater.AppUpdater.consumeJustUpdatedVersion(this)
            if (updatedVersion != null) {
                Toast.makeText(this, getString(R.string.toast_updated_to, updatedVersion), Toast.LENGTH_LONG).show()
                logsViewModel.info("Update", "App updated to $updatedVersion")
            }

            checkForAppUpdate()
        }, 10000) // 10 seconds after launch
        
        // Schedule periodic update checks (every 6 hours)
        schedulePeriodicUpdateCheck()
        
        // Status overlay: start immediately if permission granted, show guide if not
        startStatusOverlay()
        
        // If launched from boot receiver with minimize flag, move to back immediately.
        // This keeps the process alive (important for daemon stability) without
        // showing the app UI over the BYD home screen.
        if (intent?.getBooleanExtra("minimize_on_start", false) == true) {
            android.util.Log.i("MainActivity", "Boot launch — minimizing to background")
            moveTaskToBack(true)
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

        // showIfNeeded is no-op when the seen install-time matches the current
        // PackageInfo.lastUpdateTime, so it's safe to call on every launch.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            com.overdrive.app.overlay.SetupGuideDialog.showIfNeeded(this)
        }, 2000)
    }
    
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleLocationStartIntent(it) }
    }
    
    override fun onResume() {
        super.onResume()
        // Try to start overlay if permission was just granted (user returned from settings)
        com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(this)
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
                    logsViewModel.info("ADB", "✓ ADB authorization granted!")
                    logsViewModel.info("ADB", "Re-initializing daemons...")
                    
                    // Re-run daemon initialization now that ADB is authorized
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        daemonStartupManager.initializeOnAppLaunch()
                        
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
                    logsViewModel.error("ADB", "⚠ ADB connection failed: $error")
                }
            }
        })
    }
    
    /**
     * Check GitHub for app updates and show dialog if available.
     */
    private fun checkForAppUpdate() {
        logsViewModel.info("Update", "Checking for updates (channel: ${BuildConfig.UPDATE_CHANNEL})...")
        val updater = com.overdrive.app.updater.AppUpdater(this)
        appUpdater = updater
        updater.checkForUpdate(object : com.overdrive.app.updater.AppUpdater.UpdateCallback {
            override fun onUpdateAvailable(currentVersion: String, newVersion: String, releaseNotes: String) {
                com.overdrive.app.updater.UpdateDialog.showUpdateAvailable(
                    this@MainActivity, currentVersion, newVersion, releaseNotes,
                    { performAppUpdate(updater) },
                    null
                )
            }

            override fun onNoUpdate(currentVersion: String) {
                logsViewModel.debug("Update", "App is up to date (v$currentVersion)")
            }

            override fun onError(error: String) {
                logsViewModel.debug("Update", "Update check failed: $error")
            }
        })
    }

    /**
     * Manual update check — shows toast if already up to date.
     */
    fun checkForAppUpdateManual() {
        Toast.makeText(this, getString(R.string.toast_checking_for_updates), Toast.LENGTH_SHORT).show()
        val updater = com.overdrive.app.updater.AppUpdater(this)
        appUpdater = updater
        updater.checkForUpdate(object : com.overdrive.app.updater.AppUpdater.UpdateCallback {
            override fun onUpdateAvailable(currentVersion: String, newVersion: String, releaseNotes: String) {
                com.overdrive.app.updater.UpdateDialog.showUpdateAvailable(
                    this@MainActivity, currentVersion, newVersion, releaseNotes,
                    { performAppUpdate(updater) },
                    null
                )
            }

            override fun onNoUpdate(currentVersion: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_app_up_to_date, currentVersion), Toast.LENGTH_LONG).show()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_update_check_failed, error), Toast.LENGTH_LONG).show()
            }
        })
    }

    /**
     * Schedule periodic update checks (every 6 hours).
     */
    private fun schedulePeriodicUpdateCheck() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val sixHoursMs = 6 * 60 * 60 * 1000L
        val checkRunnable = object : Runnable {
            override fun run() {
                checkForAppUpdate()
                handler.postDelayed(this, sixHoursMs)
            }
        }
        handler.postDelayed(checkRunnable, sixHoursMs)
    }

    private fun performAppUpdate(updater: com.overdrive.app.updater.AppUpdater) {
        val progress = com.overdrive.app.updater.UpdateDialog.showProgress(this) {
            updater.cancel()
        }

        updater.downloadAndInstall(object : com.overdrive.app.updater.AppUpdater.InstallCallback {
            override fun onProgress(message: String) {
                runOnUiThread {
                    when {
                        message.contains("Downloading") -> progress.setStep("\u2B07\uFE0F Downloading update...", 15)
                        message.contains("Verifying") -> progress.setStep("\uD83D\uDD0D Verifying download...", 40)
                        message.contains("Stopping") -> progress.setStep("\u23F9\uFE0F Stopping daemons...", 60)
                        message.contains("Installing") -> progress.setStep("\uD83D\uDCE6 Installing update...", 85)
                        message.contains("installed") -> progress.setStep("\u2705 Update installed!", 100)
                        else -> progress.setStatus(message)
                    }
                }
            }

            override fun onDownloadProgress(percent: Int) {
                // Download is via ADB shell — no granular progress
                // Step-based progress handles this
            }

            override fun onSuccess() {
                runOnUiThread {
                    progress.setStep("\u2705 Restarting app...", 100)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        progress.dismiss()
                    }, 2000)
                }
            }

            override fun onError(error: String) {
                runOnUiThread { progress.showError(error) }
            }
        })
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
                    logsViewModel.info("Storage", "✓ Storage directories created (App is owner)")
                } else {
                    logsViewModel.warn("Storage", "Some directories could not be created")
                }
            } else {
                android.util.Log.e("MainActivity", "Storage permission denied by user")
                logsViewModel.error("Storage", "⚠ Storage permission denied - recordings may not work")
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
                    logsViewModel.info("Storage", "✓ Storage directories created (App is owner)")
                } else {
                    logsViewModel.warn("Storage", "Some directories could not be created")
                }
            } else {
                android.util.Log.e("MainActivity", "Storage permission denied by user")
                logsViewModel.error("Storage", "⚠ Storage permission denied - recordings may not work")
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
        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar = findViewById(R.id.toolbar)
        navigationView = findViewById(R.id.navigationView)
        switchAccessMode = findViewById(R.id.switchAccessMode)
        tvAccessMode = findViewById(R.id.tvAccessMode)
        tvCurrentUrl = findViewById(R.id.tvCurrentUrl)
        urlBar = findViewById(R.id.urlBar)
        statusIndicator = findViewById(R.id.statusIndicator)
        urlStatusDot = findViewById(R.id.urlStatusDot)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        
        // Populate nav header with version and device ID
        val headerView = navigationView.getHeaderView(0)
        if (headerView != null) {
            val tvVersion = headerView.findViewById<TextView>(R.id.tvVersion)
            val tvDeviceId = headerView.findViewById<TextView>(R.id.tvDeviceId)
            
            // Set version (use channel version from updater if available)
            val versionName = com.overdrive.app.updater.AppUpdater.getDisplayVersion(this)
            tvVersion?.text = versionName
            
            // Set device ID
            val deviceId = com.overdrive.app.util.DeviceIdGenerator.generateDeviceId(this)
            tvDeviceId?.text = deviceId
        }
    }
    
    private fun setupNavigation(savedInstanceState: Bundle?) {
        // Setup toolbar
        setSupportActionBar(toolbar)
        
        // Get NavController from NavHostFragment
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        
        // Define top-level destinations (no back button)
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.dashboardFragment, R.id.daemonsFragment, 
                  R.id.recordingFragment, R.id.adbConsoleFragment),
            drawerLayout
        )
        
        // Setup toolbar with navigation
        toolbar.setupWithNavController(navController, appBarConfiguration)
        
        // Setup navigation view with nav controller
        navigationView.setupWithNavController(navController)
        
        // Handle non-navigation menu items (like "Check for Updates")
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_check_update -> {
                    drawerLayout.closeDrawers()
                    checkForAppUpdateManual()
                    true
                }
                R.id.nav_traffic_monitor -> {
                    drawerLayout.closeDrawers()
                    onTrafficMonitorClicked()
                    true
                }
                R.id.nav_reconfigure_camera -> {
                    drawerLayout.closeDrawers()
                    onReconfigureCameraClicked()
                    true
                }
                R.id.nav_battery_health -> {
                    drawerLayout.closeDrawers()
                    showBatteryHealthDialog()
                    true
                }
                R.id.nav_reset_data -> {
                    drawerLayout.closeDrawers()
                    showResetDataDialog()
                    true
                }
                else -> {
                    // Let NavController handle navigation items
                    val handled = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(menuItem, navController)
                    if (handled) drawerLayout.closeDrawers()
                    handled
                }
            }
        }
        
        // Check traffic monitor status when drawer opens
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerOpened(drawerView: View) {
                checkTrafficMonitorStatus()
                updateCameraProbeMenuItem()
                refreshLanguageFooter()
            }
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })

        // Pinned language footer at the bottom of the drawer.
        findViewById<View>(R.id.drawerLanguageFooter)?.setOnClickListener {
            drawerLayout.closeDrawers()
            com.overdrive.app.ui.dialog.LanguagePickerDialog.show(this) {
                // Recreate so AppCompat reapplies the new locale to the entire
                // activity tree (toolbar title, all visible Fragments, drawer).
                recreate()
            }
        }
        refreshLanguageFooter()
        
        // Add LogsPanelFragment if not already added
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.logsPanelContainer, com.overdrive.app.ui.fragment.LogsPanelFragment())
                .commit()
        }
    }
    
    private fun setupAccessModeToggle() {
        switchAccessMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingSwitch) {
                val mode = if (isChecked) AccessMode.PUBLIC else AccessMode.PRIVATE
                mainViewModel.setAccessMode(mode)
                logsViewModel.info("App", "Access mode changed to ${mode.name}")
                
                // Handle cloudflared based on mode using startup manager
                daemonStartupManager.onAccessModeChanged(mode)
                updateUrlDisplay()
            }
        }
    }
    
    private fun setupCopyButton() {
        btnCopyUrl.setOnClickListener {
            val url = tvCurrentUrl.text.toString()
            if (url.isNotEmpty() && !url.startsWith("No tunnel") && !url.startsWith("Waiting") && !url.startsWith("Starting") && url != "Connecting...") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.clip_label_url), url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.toast_url_copied_short), Toast.LENGTH_SHORT).show()
            }
        }
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
        // Observe access mode changes
        mainViewModel.accessMode.observe(this) { mode ->
            // Update switch without triggering listener
            isUpdatingSwitch = true
            switchAccessMode.isChecked = mode == AccessMode.PUBLIC
            isUpdatingSwitch = false
            
            tvAccessMode.text = mode.name
            updateUrlDisplay()
        }
        
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
            val cloudflaredState = states[DaemonType.CLOUDFLARED_TUNNEL]
            val zrokState = states[DaemonType.ZROK_TUNNEL]
            val tailscaleState = states[DaemonType.TAILSCALE_TUNNEL]
            // Show online if either tunnel is running
            val tunnelStatus = when {
                zrokState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                cloudflaredState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                tailscaleState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                zrokState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
                cloudflaredState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
                tailscaleState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
                else -> DaemonStatus.STOPPED
            }
            updateStatusIndicator(tunnelStatus)
        }
    }
    
    private fun updateUrlDisplay() {
        val accessMode = mainViewModel.accessMode.value ?: AccessMode.PRIVATE
        // Check both tunnel URLs - prefer zrok if available
        val zrokUrl = daemonsViewModel.zrokController.tunnelUrl.value
        val cloudflaredUrl = daemonsViewModel.cloudflaredController.tunnelUrl.value
        val tailscaleUrl = daemonsViewModel.tailscaleController.tunnelUrl.value
        val tunnelUrl = zrokUrl?.takeIf { it.isNotEmpty() } ?: cloudflaredUrl?.takeIf { it.isNotEmpty() } ?: tailscaleUrl
        
        // Both modes now use tunnel URL
        if (tunnelUrl.isNullOrEmpty()) {
            // Show context-aware message based on tunnel state
            val states = daemonsViewModel.daemonStates.value
            val cfState = states?.get(DaemonType.CLOUDFLARED_TUNNEL)
            val zrokState = states?.get(DaemonType.ZROK_TUNNEL)
            val tailscaleState = states?.get(DaemonType.TAILSCALE_TUNNEL)
            val message = when {
                zrokState?.status == DaemonStatus.STARTING -> "Starting Zrok tunnel..."
                cfState?.status == DaemonStatus.STARTING -> "Starting Cloudflared tunnel..."
                tailscaleState?.status == DaemonStatus.STARTING -> "Starting Tailscale tunnel..."
                zrokState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
                cfState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
                tailscaleState?.status == DaemonStatus.RUNNING -> "Waiting for tailscale URL..."
                else -> "No tunnel running"
            }
            tvCurrentUrl.text = message
            urlStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
            mainViewModel.setCurrentUrl(null)
        } else {
            tvCurrentUrl.text = tunnelUrl
            urlStatusDot.setBackgroundResource(R.drawable.status_dot_online)
            mainViewModel.setCurrentUrl(tunnelUrl)
        }
    }
    
    private fun updateStatusIndicator(status: DaemonStatus?) {
        val drawableRes = when (status) {
            DaemonStatus.RUNNING -> R.drawable.status_dot_online
            DaemonStatus.STARTING, DaemonStatus.STOPPING -> R.drawable.status_dot_starting
            else -> R.drawable.status_dot_offline
        }
        statusIndicator.setBackgroundResource(drawableRes)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
    
    // ==================== Camera Reconfiguration ====================
    
    /**
     * Update the "Reconfigure Camera" menu item to show current probe status.
     * Called when the drawer opens.
     */
    private fun updateCameraProbeMenuItem() {
        val menuItem = navigationView.menu.findItem(R.id.nav_reconfigure_camera) ?: return
        
        try {
            val config = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
            val cameraConfig = config.optJSONObject("camera")
            val savedId = cameraConfig?.optInt("probedCameraId", -1) ?: -1
            val savedMode = cameraConfig?.optInt("probedSurfaceMode", -1) ?: -1
            val isManual = cameraConfig?.optBoolean("manualOverride", false) ?: false
            
            if (savedId >= 0 && savedMode >= 0) {
                val mode = if (isManual) "Manual" else "Auto"
                menuItem.title = "Camera: ID $savedId ($mode)"
            } else {
                menuItem.title = "Camera: Auto (detecting...)"
            }
        } catch (e: Exception) {
            menuItem.title = "Camera Selection"
        }
    }
    
    /**
     * Handle "Reconfigure Camera" menu item click.
     * Shows a styled dialog to manually select camera ID or use auto-detection.
     */
    private fun onReconfigureCameraClicked() {
        var currentId = -1
        var isManual = false
        try {
            val config = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
            val cameraConfig = config.optJSONObject("camera")
            if (cameraConfig != null) {
                currentId = cameraConfig.optInt("probedCameraId", -1)
                isManual = cameraConfig.optBoolean("manualOverride", false)
            }
        } catch (e: Exception) { /* ignore */ }

        val dialogView = layoutInflater.inflate(R.layout.dialog_camera_selection, null)
        
        // Set current status
        val statusText = dialogView.findViewById<TextView>(R.id.tvCurrentCamera)
        if (isManual && currentId >= 0) {
            statusText.text = getString(R.string.camera_current_manual, currentId)
        } else {
            statusText.text = getString(R.string.camera_current_auto_label)
        }
        
        // Set radio button selection
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgCameraOptions)
        val radioIds = arrayOf(
            R.id.rbCameraAuto, R.id.rbCamera0, R.id.rbCamera1,
            R.id.rbCamera2, R.id.rbCamera3, R.id.rbCamera4, R.id.rbCamera5
        )
        val currentSelection = if (isManual && currentId >= 0) currentId + 1 else 0
        radioGroup.check(radioIds[currentSelection])
        
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Overdrive_Dialog)
            .setView(dialogView)
            .setNegativeButton(getString(R.string.dialog_close), null)
            .create()
        
        // Save immediately on radio button selection — no Apply button needed
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedIndex = radioIds.indexOf(checkedId)
            if (selectedIndex < 0) return@setOnCheckedChangeListener
            
            // Don't re-save if user tapped the already-selected option
            if (selectedIndex == currentSelection) return@setOnCheckedChangeListener
            
            if (selectedIndex == 0) {
                // Auto mode
                Thread {
                    try {
                        val conn = com.overdrive.app.util.DaemonHttpClient.open(
                            "/api/surveillance/config", "POST", 3000, 3000)
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        val body = """{"clearManualCameraId":true}"""
                        conn.outputStream.use { it.write(body.toByteArray()) }
                        val responseCode = conn.responseCode
                        conn.disconnect()

                        runOnUiThread {
                            if (responseCode == 200) {
                                Toast.makeText(this, getString(R.string.toast_camera_set_to_auto), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, getString(R.string.toast_failed_to_save_short), Toast.LENGTH_SHORT).show()
                            }
                            updateCameraProbeMenuItem()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, getString(R.string.toast_failed_with_message, e.message ?: ""), Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            } else {
                // Manual camera ID
                val selectedCamId = selectedIndex - 1
                Thread {
                    try {
                        val conn = com.overdrive.app.util.DaemonHttpClient.open(
                            "/api/surveillance/config", "POST", 3000, 3000)
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        val body = """{"manualCameraId":$selectedCamId}"""
                        conn.outputStream.use { it.write(body.toByteArray()) }
                        val responseCode = conn.responseCode
                        conn.disconnect()

                        runOnUiThread {
                            if (responseCode == 200) {
                                Toast.makeText(this, getString(R.string.toast_camera_id_set, selectedCamId), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, getString(R.string.toast_failed_to_save_short), Toast.LENGTH_SHORT).show()
                            }
                            updateCameraProbeMenuItem()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, getString(R.string.toast_failed_with_message, e.message ?: ""), Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
        }
        
        dialog.show()
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
                    Toast.makeText(this, getString(R.string.toast_restarting_camera_daemon), Toast.LENGTH_SHORT).show()
                }
                
                // Kill the camera daemon — DaemonLauncher's watchdog will auto-restart it
                val adb = com.overdrive.app.launcher.AdbDaemonLauncher(this)
                adb.killDaemon(object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) {
                        logsViewModel.debug("Camera", message)
                    }
                    
                    override fun onLaunched() {
                        runOnUiThread {
                            logsViewModel.info("Camera", "Camera daemon stopped — will auto-restart with full probe")
                            Toast.makeText(this@MainActivity,
                                getString(R.string.toast_camera_daemon_restarting), Toast.LENGTH_LONG).show()
                            
                            // Re-launch the daemon after a brief delay
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                daemonStartupManager.initializeOnAppLaunch()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    daemonStartupManager.checkAllDaemonStatuses()
                                }, 5000)
                            }, 3000)
                        }
                    }
                    
                    override fun onError(error: String) {
                        runOnUiThread {
                            logsViewModel.error("Camera", "Failed to stop daemon: $error")
                            Toast.makeText(this@MainActivity,
                                getString(R.string.toast_camera_restart_failed),
                                Toast.LENGTH_LONG).show()
                        }
                    }
                })
                
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
     * Reads directly from the persisted properties file (no HTTP/auth needed).
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
            var preferredSource = "auto"
            var hasEstimate = false
            
            try {
                val sohFile = java.io.File("/data/local/tmp/abrp_soh_estimate.properties")
                if (sohFile.exists()) {
                    val props = java.util.Properties()
                    java.io.FileInputStream(sohFile).use { props.load(it) }
                    
                    val soh = props.getProperty("soh_percent")?.toDoubleOrNull()
                    if (soh != null && soh > 0 && soh <= 100) {
                        sohPercent = String.format("%.1f%%", soh)
                        hasEstimate = true
                    }
                    
                    method = props.getProperty("estimation_method") ?: "--"
                    samples = props.getProperty("sample_count") ?: "0"
                    preferredSource = props.getProperty("preferred_source") ?: "auto"
                    
                    val nominal = props.getProperty("nominal_capacity_kwh")?.toDoubleOrNull()
                    if (nominal != null && nominal > 0) {
                        nominalKwh = String.format("%.1f kWh", nominal)
                    }
                    
                    val ts = props.getProperty("last_updated")?.toLongOrNull()
                    if (ts != null && ts > 0) {
                        lastUpdated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(ts))
                    }
                    
                    source = preferredSource
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "SOH file read failed: ${e.message}")
            }
            
            val finalSoh = sohPercent
            val finalSource = source
            val finalMethod = method
            val finalNominal = nominalKwh
            val finalSamples = samples
            val finalLastUpdated = lastUpdated
            val finalHasEstimate = hasEstimate
            
            runOnUiThread {
                val dialogView = layoutInflater.inflate(R.layout.dialog_battery_health, null)
                
                // Populate fields
                dialogView.findViewById<TextView>(R.id.tvSohPercent).text = finalSoh
                dialogView.findViewById<TextView>(R.id.tvSohSource).text = finalSource
                dialogView.findViewById<TextView>(R.id.tvSohMethod).text = finalMethod
                dialogView.findViewById<TextView>(R.id.tvSohCapacity).text = finalNominal
                dialogView.findViewById<TextView>(R.id.tvSohSamples).text = finalSamples
                dialogView.findViewById<TextView>(R.id.tvSohLastUpdated).text = finalLastUpdated
                
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
                
                val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Overdrive_Dialog)
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.dialog_close), null)
                    .create()
                
                // Wire up reset button
                dialogView.findViewById<TextView>(R.id.btnResetSoh).setOnClickListener {
                    dialog.dismiss()
                    confirmSohReset()
                }
                
                dialog.show()
            }
        }
    }
    
    /**
     * Confirmation dialog before resetting SOH estimation.
     */
    private fun confirmSohReset() {
        android.app.AlertDialog.Builder(this)
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

        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Overdrive_Dialog)
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
        android.app.AlertDialog.Builder(this)
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
                                lines.append("✓ ").append(label).append(detail).append("\n")
                            } else {
                                val err = r?.optString("error", "failed") ?: "failed"
                                lines.append("✗ ").append(label).append(": ").append(err).append("\n")
                            }
                        }
                        android.app.AlertDialog.Builder(this)
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
        
        val adb = AdbDaemonLauncher(this)
        // Use 'grep ... || echo NOT_DISABLED' to ensure exit code 0 regardless of grep result
        adb.executeShellCommand(
            "pm list packages -d 2>/dev/null | grep com.byd.trafficmonitor || echo NOT_DISABLED",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    val isDisabled = message.contains("com.byd.trafficmonitor") && !message.contains("NOT_DISABLED")
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
    
    private fun refreshLanguageFooter() {
        val tv = findViewById<TextView>(R.id.tvDrawerLanguageCurrent) ?: return
        tv.text = com.overdrive.app.ui.dialog.LanguagePickerDialog.currentLabel(this)
    }

    private fun updateTrafficMonitorMenuItem(enabled: Boolean) {
        val menuItem = navigationView.menu.findItem(R.id.nav_traffic_monitor)
        if (enabled) {
            menuItem?.title = getString(R.string.traffic_monitor_enabled_title)
        } else {
            menuItem?.title = getString(R.string.traffic_monitor_disabled_title)
        }
    }
    
    private fun updateTrafficMonitorMenuItemText(text: String) {
        val menuItem = navigationView.menu.findItem(R.id.nav_traffic_monitor)
        menuItem?.title = text
    }
    
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
            
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_traffic_cannot_check_title))
                .setMessage(getString(R.string.dialog_traffic_cannot_check_message))
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
            return
        }

        if (currentlyEnabled) {
            // Currently enabled — offer to disable with full explanation
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_traffic_disable_title))
                .setMessage(getString(R.string.dialog_traffic_disable_message))
                .setPositiveButton(getString(R.string.dialog_disable)) { _, _ ->
                    setTrafficMonitorEnabled(false)
                }
                .setNegativeButton(getString(R.string.dialog_keep_enabled), null)
                .show()
        } else {
            // Currently disabled — offer to re-enable
            android.app.AlertDialog.Builder(this)
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
     * Enable or disable the BYD Traffic Monitor package via ADB shell.
     */
    private fun setTrafficMonitorEnabled(enable: Boolean) {
        val cmd = if (enable) {
            "pm enable com.byd.trafficmonitor 2>&1"
        } else {
            "pm disable-user --user 0 com.byd.trafficmonitor 2>&1"
        }
        
        val action = if (enable) "Enabling" else "Disabling"
        Toast.makeText(this, getString(R.string.toast_traffic_monitor_changing, action), Toast.LENGTH_SHORT).show()
        
        val adb = AdbDaemonLauncher(this)
        adb.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                android.util.Log.i("TrafficMonitor", "$action result: $message")
            }
            
            override fun onLaunched() {
                runOnUiThread {
                    trafficMonitorEnabled = enable
                    updateTrafficMonitorMenuItem(enable)
                    
                    val state = if (enable) "enabled" else "disabled"
                    logsViewModel.info("TrafficMonitor", "BYD Traffic Monitor $state")
                    
                    // Show reboot reminder
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.dialog_traffic_status_title, state.replaceFirstChar { it.uppercase() }))
                        .setMessage(getString(R.string.dialog_traffic_reboot_message))
                        .setPositiveButton(getString(R.string.dialog_ok), null)
                        .show()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_failed_with_message_x, error), Toast.LENGTH_LONG).show()
                    logsViewModel.error("TrafficMonitor", "Failed to ${if (enable) "enable" else "disable"}: $error")
                }
            }
        })
    }
    
    override fun onDestroy() {
        // Remove log listener
        LogManager.setLogListener(null)
        // Remove ADB auth callback
        com.overdrive.app.launcher.AdbShellExecutor.setAuthCallback(null)
        // Note: We intentionally do NOT call cleanupAll() here
        // Daemons should persist after app closure
        super.onDestroy()
    }
}
