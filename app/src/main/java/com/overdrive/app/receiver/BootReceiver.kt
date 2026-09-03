package com.overdrive.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import com.overdrive.app.services.DaemonKeepaliveService
import com.overdrive.app.ui.daemon.DaemonStartupManager
import com.overdrive.app.ui.util.PreferencesManager

/**
 * Handles boot and system events to start daemons.
 * 
 * Listens for:
 * - Boot completed events
 * - Screen/user events (including SCREEN_OFF via ScreenOffReceiver delegation)
 * - BYD ACC ON/OFF events
 * - WiFi/Network state changes
 * 
 * Starts DaemonKeepaliveService (foreground + sticky + wakelock) and
 * daemons via DaemonStartupManager.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "BootReceiver"
        
        @Volatile
        private var lastStartTime = 0L
        private const val MIN_RESTART_INTERVAL = 5000L // 5 seconds debounce
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.d(TAG, "Received broadcast: $action")

        // NOTE: plug edges are deliberately NOT forwarded to ChargingDetector here.
        // ChargingDetector is a plain per-process `static final INSTANCE`
        // (ChargingDetector.java:110), so calling it from THIS (app) process
        // mutated a different object than the one every consumer reads — all of
        // which (VehicleDataMonitor, BydDataCollector, MQTT, automations) run
        // daemon-side. The calls were therefore dead: the plug edge never reached
        // the detector that matters, and they class-loaded ChargingDetector +
        // BydVehicleData + DaemonLogger into the app process for nothing.
        //
        // The daemon already registers its OWN ACTION_POWER_CONNECTED/DISCONNECTED
        // receiver feeding its own detector (BydDataCollector.java:763-778), so the
        // real edge path is intact and unaffected by this removal.

        // "Vehicle ON only" RECOVERY must bypass the 5s debounce. While parked in onOnly
        // the whole stack is terminated; the only automatic recovery is a recovery trigger
        // (ACC/IGN-on, boot) reaching recoverFromPark. If a passive broadcast (WiFi state,
        // power-connected) arrives in the same power-up burst and wins the debounce slot,
        // a subsequent ACC_ON within 5s would be debounced away and the stack would stay
        // dead for the whole drive. So: if a recovery trigger arrives with the parked
        // marker present, recover NOW, before the debounce. This runs BEFORE lastStartTime
        // is bumped, and startDaemons itself will set lastStartTime on the launch path.
        // Guarded to the marker-present case so onAndOff (no marker) is completely unaffected.
        if (isRecoveryTrigger(action)) {
            try {
                if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()) {
                    Log.i(TAG, "Recovery trigger '$action' with parked marker — recovering pre-debounce")
                    if (!PreferencesManager.isInitialized()) {
                        try { PreferencesManager.init(context.applicationContext) } catch (e: Exception) {}
                    }
                    startDaemons(context, action)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pre-debounce recovery check failed (${e.message}) — falling through")
            }
        }

        // Debounce rapid restarts
        val now = System.currentTimeMillis()
        if (now - lastStartTime < MIN_RESTART_INTERVAL) {
            Log.d(TAG, "Debouncing restart (too soon)")
            return
        }
        
        // Initialize PreferencesManager if needed (for boot scenarios)
        // Uses device-encrypted storage so it works before user unlock
        try {
            if (!PreferencesManager.isInitialized()) {
                PreferencesManager.init(context.applicationContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PreferencesManager init error: ${e.message}")
            // Continue anyway - core daemons can start without preferences
        }
        
        when (action) {
            // Boot events - start daemons and launch activity minimized.
            // Launching the activity keeps the app process alive (Android is less
            // likely to kill a process with a recent activity) and runs essential
            // initialization (storage, device ID, BYD whitelist). We immediately
            // move it to the back so the user sees their home screen, not OverDrive.
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                startDaemons(context, action)
                try {
                    val launchIntent = Intent(context, com.overdrive.app.ui.MainActivity::class.java)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    launchIntent.putExtra("minimize_on_start", true)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "App launched minimized on boot")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch app: ${e.message}")
                }
            }
            
            // App update — DO NOT start daemons here. The old process's daemon
            // kill sequence may still be in flight, and the new MainActivity is
            // the sole orchestrator post-update: it runs UpdateLifecycle.hardResetDaemons
            // before DaemonStartupManager. Starting daemons here would race the
            // hard reset and resurrect old/zombie watchdogs (see /data/local/tmp/
            // overdrive_update_in_progress sentinel).
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                lastStartTime = System.currentTimeMillis()
                // Skip auto-relaunch in debug builds: Android Studio drives its own
                // launch after install, and racing it makes "Run" abort with the
                // app already in the foreground.
                if (com.overdrive.app.BuildConfig.DEBUG) {
                    Log.d(TAG, "MY_PACKAGE_REPLACED — debug build, skipping auto-relaunch (let IDE drive launch)")
                    return
                }
                try {
                    val launchIntent = Intent(context, com.overdrive.app.ui.MainActivity::class.java)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    launchIntent.putExtra(
                        com.overdrive.app.updater.UpdateLifecycle.EXTRA_POST_UPDATE,
                        true,
                    )
                    // System-driven relaunch, not a user tap. Suppress the PIN
                    // gate + minimize so the keypad doesn't flash over the BYD
                    // home screen on a locked install. The post-update daemon
                    // hard-reset still runs (runDaemonStartup executes before the
                    // minimize check in MainActivity.onCreate); minimize_on_start
                    // only governs the PIN gate + moveTaskToBack, and is one-shot
                    // so the user's next real foreground entry gates normally.
                    launchIntent.putExtra("minimize_on_start", true)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "MY_PACKAGE_REPLACED — relaunching MainActivity (post_update=true, minimized)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to relaunch app: ${e.message}")
                }
            }
            
            // Screen/user events - start if not running
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,  // Delegated from ScreenOffReceiver
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.USER_UNLOCKED" -> {
                startDaemons(context, action)
            }

            // Plug-in is also a wake-worthy event so the daemon comes
            // up to record the overnight charge session. (The detector
            // notification already fired above, before debounce.)
            Intent.ACTION_POWER_CONNECTED -> {
                startDaemons(context, action)
            }
            // POWER_DISCONNECTED: detector was notified before debounce.
            // No daemon restart needed.
            Intent.ACTION_POWER_DISCONNECTED -> {
                // intentionally no-op here
            }
            
            // BYD ACC ON events - start daemons
            "com.byd.action.ACC_ON",
            "com.byd.action.IGN_ON",
            "com.byd.accmode.ACC_MODE_CHANGED" -> {
                startDaemons(context, action)
                // Arm the blind-spot overlay ON ACC-ON, app-independently. Without
                // this the overlay only started via MainActivity.syncBlindSpotOverlay()
                // on app resume — so a fresh ACC-on with the app backgrounded left
                // the pipeline un-armed and the panel wouldn't pop on the indicator.
                // Gated on the same blindspot.enabled flag the activity uses, so a
                // disabled feature stays off.
                armBlindSpotIfEnabled(context)
            }
            
            // BYD ACC OFF - AccSentryDaemon handles sentry mode via bodywork listener
            "com.byd.action.ACC_OFF" -> {
                Log.d(TAG, "ACC OFF received - AccSentryDaemon handles sentry mode")
            }
            
            // WiFi/Network events - restart daemons if WiFi is enabled
            "android.net.wifi.STATE_CHANGE",
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager?.isWifiEnabled == true) {
                    startDaemons(context, action)
                }
            }
        }
    }
    
    /** On ACC-ON, arm the NATIVE blind-spot lane IF the feature is enabled, so the
     *  daemon brings up its SurfaceControl layer without needing MainActivity to be
     *  foregrounded. BlindSpotControl.sync POSTs the daemon control surface; the
     *  daemon owns show/hide (turn-trigger) + positioning. No app-process overlay. */
    private fun armBlindSpotIfEnabled(context: Context) {
        try {
            com.overdrive.app.roadsense.overlay.BlindSpotControl.sync(context)
        } catch (t: Throwable) {
            Log.w(TAG, "armBlindSpotIfEnabled failed: ${t.message}")
        }
    }

    // Triggers that mean "the car is being used again" — these CLEAR the parked-shutdown
    // marker and recover the full stack. HAL-backed ACC/IGN edges + head-unit boot.
    private fun isRecoveryTrigger(trigger: String): Boolean = when (trigger) {
        "com.byd.action.ACC_ON",
        "com.byd.action.IGN_ON",
        "com.byd.accmode.ACC_MODE_CHANGED",
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON" -> true
        else -> false
    }

    private fun startDaemons(context: Context, trigger: String) {
        Log.d(TAG, "Starting daemons (trigger: $trigger)")

        // "Vehicle ON only" parked-shutdown gate. While the parked-shutdown marker is
        // present the whole stack was intentionally terminated for the parked window.
        //  - A RECOVERY trigger (ACC/IGN-on, boot) means the car is being used again:
        //    clear the marker and fall through to relaunch.
        //  - Any PASSIVE trigger (screen on/off, USER_PRESENT, power-connected, wifi/
        //    connectivity) fires while parked and must NOT resurrect the stack — return
        //    early so parked compute stays zero. (These are the exact triggers that would
        //    otherwise defeat the terminate.)
        try {
            val markerPresent = java.io.File(
                com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()
            if (markerPresent) {
                if (isRecoveryTrigger(trigger)) {
                    Log.i(TAG, "Recovery trigger '$trigger' with parked marker present — recovering (clear marker + reset boot guard)")
                    // recoverFromPark clears the marker AND resets bootStarted so the
                    // startOnBoot below actually redeploys the watchdogs (the app process
                    // survives the park via the accessibility keep-alive, so bootStarted
                    // would otherwise still be true and startOnBoot would no-op).
                    DaemonStartupManager.recoverFromPark(context.applicationContext)
                    // fall through to relaunch
                } else {
                    Log.i(TAG, "Parked-shutdown marker present + passive trigger '$trigger' — suppressing rebuild (stay asleep)")
                    // Return WITHOUT bumping lastStartTime: a suppressed passive trigger
                    // must not consume the debounce slot, or a real recovery trigger
                    // arriving within 5s could be debounced away (the pre-debounce recovery
                    // path in onReceive covers the primary case; this keeps the slot free
                    // as defense-in-depth).
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parked-marker gate check failed (${e.message}) — proceeding with normal start")
        }

        // Only reached on an actual launch path — bump the debounce timer here (not at the
        // top) so suppressed passive returns above never shadow a subsequent recovery.
        lastStartTime = System.currentTimeMillis()

        try {
            // Enforce persistent global ADB settings on boot/wakeup
            com.overdrive.app.launcher.AdbShellExecutor.enforceGlobalAdbSettings(context.applicationContext)

            // Start DaemonKeepaliveService (foreground + sticky + wakelock)
            DaemonKeepaliveService.start(context.applicationContext)

            // Also start daemons directly via DaemonStartupManager
            DaemonStartupManager.startOnBoot(context.applicationContext)

            // (Re-)seed out-of-process revival watchdog. Self-heals the alarm
            // chain if it was ever broken (force-stop, reboot, app data clear).
            ProcessRevivalReceiver.schedule(context.applicationContext)

            // Hotspot "start at power-up": one-shot per process, and only from a
            // recovery trigger — a passive broadcast must never bring the AP up
            // (it would drop the station link the user is currently on).
            if (isRecoveryTrigger(trigger)) {
                com.overdrive.app.network.HotspotManager.armBootAutoStart(
                    context.applicationContext)
            }

            Log.d(TAG, "Daemon startup initiated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemons: ${e.message}")
            e.printStackTrace()
        }
    }
}
