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
import com.overdrive.app.shell.PrivilegedShellSetup
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
        
        // SOTA: Setup storage directories FIRST (app becomes owner for cross-UID access)
        setupStorageDirectories()
        
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
        
        // Setup privileged shell (UID 1000) - required for daemon management
        // setupPrivilegedShell()
        
        // Start daemons and services
        // Device ID is already synced above via generateDeviceId() which writes to file async
        // The daemon will reload from file when getState() is called
        
        // Start Location Sidecar service (establishes ADB connection)
        startLocationSidecarService()
        
        // Initialize daemons after a short delay to allow ADB connection
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Sync device ID to file synchronously before daemon startup
            Thread {
                try {
                    val synced = com.overdrive.app.util.DeviceIdGenerator.syncDeviceIdToFileSync(this)
                    android.util.Log.i("MainActivity", "Device ID sync result: $synced")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Device ID sync error: ${e.message}")
                }
                
                // Start daemons on main thread
                runOnUiThread {
                    daemonStartupManager.initializeOnAppLaunch()
                    
                    // Check daemon statuses after startup
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        daemonStartupManager.checkAllDaemonStatuses()
                    }, 3000)
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
                Toast.makeText(this, "✅ Updated to $updatedVersion", Toast.LENGTH_LONG).show()
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
     * Start the status overlay service if overlay permission is granted.
     * If not granted, show the setup guide to help the user enable it.
     */
    private fun startStatusOverlay() {
        val hasPermission = com.overdrive.app.overlay.StatusOverlayService.hasOverlayPermission(this)
        android.util.Log.i("MainActivity", "Overlay permission: $hasPermission")
        logsViewModel.info("Overlay", "Overlay permission: $hasPermission")
        
        if (hasPermission) {
            // Permission granted — start the overlay service
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(this)
            logsViewModel.info("Overlay", "Status overlay service started")
        } else {
            // No permission — show setup guide after a short delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.overdrive.app.overlay.SetupGuideDialog.showIfNeeded(this)
            }, 2000)
        }
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
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@MainActivity, "✅ App is up to date (v$currentVersion)", Toast.LENGTH_LONG).show()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, "❌ Update check failed: $error", Toast.LENGTH_LONG).show()
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
            // Permission granted - create directories
            android.util.Log.i("MainActivity", "Permission OK - calling setupDirectories()")
            val success = StorageSetup.setupDirectories()
            if (success) {
                android.util.Log.i("MainActivity", "Storage directories ready (App is owner)")
            } else {
                android.util.Log.w("MainActivity", "Some storage directories could not be created")
            }
        } else {
            // Need to request permission
            android.util.Log.i("MainActivity", "Permission NOT granted - requesting...")
            StorageSetup.requestStoragePermission(this)
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
                Toast.makeText(this, "Storage permission required for recordings", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "Storage permission required for recordings", Toast.LENGTH_LONG).show()
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
        
        // Initialize with context
        PrivilegedShellSetup.init(this)
        
        PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
            override fun onSuccess() {
                runOnUiThread {
                    logsViewModel.info("Shell", "✓ Privileged shell ready (UID 1000)")
                    
                    // Now check all daemon statuses (auto-start is configurable in DaemonStartupManager)
                    daemonStartupManager.checkAllDaemonStatuses()
                }
            }
            
            override fun onFailure(reason: String) {
                runOnUiThread {
                    logsViewModel.warn("Shell", "⚠ Privileged shell setup failed: $reason")
                    logsViewModel.info("Shell", "Falling back to ADB shell for daemon management")
                    
                    // Still check daemon statuses - they might be running from previous session
                    daemonStartupManager.checkAllDaemonStatuses()
                }
            }
            
            override fun onProgress(message: String) {
                runOnUiThread {
                    logsViewModel.debug("Shell", "→ $message")
                }
            }
        })
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
            }
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
        
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
                val clip = ClipData.newPlainText("URL", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "URL copied!", Toast.LENGTH_SHORT).show()
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
        
        // Observe daemon states for tunnel status (cloudflared or zrok)
        daemonsViewModel.daemonStates.observe(this) { states ->
            val cloudflaredState = states[DaemonType.CLOUDFLARED_TUNNEL]
            val zrokState = states[DaemonType.ZROK_TUNNEL]
            // Show online if either tunnel is running
            val tunnelStatus = when {
                zrokState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                cloudflaredState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                zrokState?.status == DaemonStatus.STARTING || cloudflaredState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
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
        val tunnelUrl = zrokUrl?.takeIf { it.isNotEmpty() } ?: cloudflaredUrl
        
        // Both modes now use tunnel URL
        if (tunnelUrl.isNullOrEmpty()) {
            // Show context-aware message based on tunnel state
            val states = daemonsViewModel.daemonStates.value
            val cfState = states?.get(DaemonType.CLOUDFLARED_TUNNEL)
            val zrokState = states?.get(DaemonType.ZROK_TUNNEL)
            val message = when {
                zrokState?.status == DaemonStatus.STARTING -> "Starting Zrok tunnel..."
                cfState?.status == DaemonStatus.STARTING -> "Starting Cloudflared tunnel..."
                zrokState?.status == DaemonStatus.RUNNING || cfState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
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
            
            if (savedId >= 0 && savedMode >= 0) {
                menuItem.title = "Camera: ID $savedId, Mode $savedMode"
            } else {
                menuItem.title = "Camera: Not Configured"
            }
        } catch (e: Exception) {
            menuItem.title = "Reconfigure Camera"
        }
    }
    
    /**
     * Handle "Reconfigure Camera" menu item click.
     * Clears the saved camera probe config and restarts the camera daemon
     * so it performs a full probe of all camera ID × surfaceMode combinations.
     */
    private fun onReconfigureCameraClicked() {
        // Read current saved config for display
        var currentInfo = "Not configured"
        try {
            val config = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
            val cameraConfig = config.optJSONObject("camera")
            if (cameraConfig != null) {
                val savedId = cameraConfig.optInt("probedCameraId", -1)
                val savedMode = cameraConfig.optInt("probedSurfaceMode", -1)
                if (savedId >= 0 && savedMode >= 0) {
                    currentInfo = "Camera ID: $savedId, Surface Mode: $savedMode"
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Reconfigure Camera")
            .setMessage(
                "Current config: $currentInfo\n\n" +
                "This will clear the saved camera configuration and restart the camera daemon. " +
                "On restart, the daemon will probe all camera ID (0-5) × surface mode (0-5) " +
                "combinations to find the one that produces panoramic video.\n\n" +
                "Recording and streaming are paused during probe and resume automatically " +
                "once a working camera is found.\n\n" +
                "This is useful if:\n" +
                "• Video appears black or frozen\n" +
                "• You changed vehicle models\n" +
                "• Camera stopped working after a firmware update\n\n" +
                "The daemon will restart automatically."
            )
            .setPositiveButton("Reconfigure") { _, _ ->
                performCameraReconfigure()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Clear saved camera config and restart the camera daemon.
     */
    private fun performCameraReconfigure() {
        Toast.makeText(this, "Clearing camera config...", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "Restarting camera daemon...", Toast.LENGTH_SHORT).show()
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
                                "✅ Camera daemon restarting with full probe", Toast.LENGTH_LONG).show()
                            
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
                                "⚠ Config cleared but daemon restart failed. Please restart manually.", 
                                Toast.LENGTH_LONG).show()
                        }
                    }
                })
                
            } catch (e: Exception) {
                runOnUiThread {
                    logsViewModel.error("Camera", "Reconfigure failed: ${e.message}")
                    Toast.makeText(this, "❌ Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    // ==================== Traffic Monitor Management ====================
    
    /** Track current traffic monitor state to show correct button */
    private var trafficMonitorEnabled: Boolean? = null
    
    /**
     * Check if BYD Traffic Monitor app is currently enabled or disabled.
     * Updates the drawer menu item title accordingly.
     */
    private fun checkTrafficMonitorStatus() {
        val adb = AdbDaemonLauncher(this)
        adb.executeShellCommand(
            "pm list packages -d 2>/dev/null | grep com.byd.trafficmonitor",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    // If the package appears in disabled list, it's disabled
                    val isDisabled = message.contains("com.byd.trafficmonitor")
                    runOnUiThread {
                        trafficMonitorEnabled = !isDisabled
                        updateTrafficMonitorMenuItem(!isDisabled)
                    }
                }
                override fun onLaunched() {}
                override fun onError(error: String) {
                    runOnUiThread {
                        updateTrafficMonitorMenuItemText("Traffic Monitor: Unknown")
                    }
                }
            }
        )
    }
    
    private fun updateTrafficMonitorMenuItem(enabled: Boolean) {
        val menuItem = navigationView.menu.findItem(R.id.nav_traffic_monitor)
        if (enabled) {
            menuItem?.title = "✅ Traffic Monitor: Enabled"
        } else {
            menuItem?.title = "⛔ Traffic Monitor: Disabled"
        }
    }
    
    private fun updateTrafficMonitorMenuItemText(text: String) {
        val menuItem = navigationView.menu.findItem(R.id.nav_traffic_monitor)
        menuItem?.title = text
    }
    
    /**
     * Handle traffic monitor menu item click.
     * Shows confirmation dialog with appropriate enable/disable action.
     */
    private fun onTrafficMonitorClicked() {
        val currentlyEnabled = trafficMonitorEnabled
        if (currentlyEnabled == null) {
            Toast.makeText(this, "Checking traffic monitor status...", Toast.LENGTH_SHORT).show()
            checkTrafficMonitorStatus()
            return
        }
        
        if (currentlyEnabled) {
            // Currently enabled — offer to disable
            android.app.AlertDialog.Builder(this)
                .setTitle("Disable Traffic Monitor")
                .setMessage(
                    "The BYD Traffic Monitor app runs in the background consuming mobile data and battery.\n\n" +
                    "Disabling it is recommended.\n\n" +
                    "After disabling, please perform a hard reboot by pressing and holding the central console button for 5 seconds."
                )
                .setPositiveButton("Disable") { _, _ ->
                    setTrafficMonitorEnabled(false)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Currently disabled — offer to enable
            android.app.AlertDialog.Builder(this)
                .setTitle("Enable Traffic Monitor")
                .setMessage(
                    "This will re-enable the BYD Traffic Monitor app.\n\n" +
                    "Note: It will run in the background and may consume mobile data and battery.\n\n" +
                    "After enabling, please perform a hard reboot by pressing and holding the central console button for 5 seconds."
                )
                .setPositiveButton("Enable") { _, _ ->
                    setTrafficMonitorEnabled(true)
                }
                .setNegativeButton("Cancel", null)
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
        Toast.makeText(this, "$action traffic monitor...", Toast.LENGTH_SHORT).show()
        
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
                        .setTitle("✅ Traffic Monitor ${state.replaceFirstChar { it.uppercase() }}")
                        .setMessage(
                            "The change has been applied.\n\n" +
                            "Please perform a hard reboot now:\n" +
                            "Press and hold the central console button for 5 seconds."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Failed: $error", Toast.LENGTH_LONG).show()
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
