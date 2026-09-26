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

        // How long a no-marker ACC_MODE_CHANGED waits before acting. Covers the OFF
        // edge on DiLink 5, where the ACC judge needs two power-mode confirmations
        // (≥3 s apart, 5 s poll) before the reaper plants the marker (~7-12 s).
        private const val ACC_MODE_CHANGED_SETTLE_MS = 20_000L
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.d(TAG, "Received broadcast: $action")

        // Unambiguous ignition edge → publish the cross-process ignition hint FIRST,
        // before the debounce and the parked-marker recovery path below (both can
        // return early). acc_sentry_daemon cannot receive broadcasts; on DiLink 5 its
        // power-mode probe reads "DisPlay on" for a running car, which alone is a
        // weak signal it refuses to exit sentry on — so the panel it darkened at
        // park stays dark until the driver shifts out of P. This hint is the
        // independent second source that lets it exit sentry (and relight) while
        // still in P. Deliberately NOT on ACC_MODE_CHANGED, which fires on both
        // edges and carries no direction.
        if (action == "com.byd.action.ACC_ON" || action == "com.byd.action.IGN_ON") {
            com.overdrive.app.power.IgnitionBroadcastHint.publishAsync(
                context.applicationContext)
        }

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
        // is bumped, and the launch path itself sets lastStartTime once the verified
        // marker erase has completed.
        // Guarded to the marker-present case so onAndOff (no marker) is completely unaffected.
        if (isRecoveryTrigger(action)) {
            try {
                if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.markerPath()).exists()) {
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

        // ACC_MODE_CHANGED while parked. This vendor broadcast fires on BOTH the on and
        // the off edge and carries no readable direction, so it can never be treated as
        // "the car is on" (doing so restarted the whole stack — and armed the hotspot and
        // blind-spot pipeline — the moment the driver switched the car OFF). While the
        // parked marker exists it is only a hint that the ACC state may have changed:
        // the decision belongs to the ACC judge, acc_sentry_daemon, which has the
        // hardware view and erases the marker itself on a real ACC-on. All the app does
        // is make sure the judge is alive. Runs before the debounce (cheap, idempotent)
        // so a passive broadcast in the same burst cannot swallow it.
        if (action == "com.byd.accmode.ACC_MODE_CHANGED") {
            try {
                if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()) {
                    Log.i(TAG, "ACC_MODE_CHANGED with parked marker — leaving the decision to acc_sentry_daemon")
                    DaemonStartupManager.noteParkObserved()
                    DaemonStartupManager.ensureAccSentryJudgeRunning(context.applicationContext)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "ACC_MODE_CHANGED parked check failed (${e.message}) — falling through")
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
            
            // BYD ACC ON events - start daemons. Only the two direction-unambiguous
            // actions live here (see isRecoveryTrigger).
            "com.byd.action.ACC_ON",
            "com.byd.action.IGN_ON" -> {
                startDaemons(context, action)
                // Arm the blind-spot overlay ON ACC-ON, app-independently. Without
                // this the overlay only started via MainActivity.syncBlindSpotOverlay()
                // on app resume — so a fresh ACC-on with the app backgrounded left
                // the pipeline un-armed and the panel wouldn't pop on the indicator.
                // Gated on the same blindspot.enabled flag the activity uses, so a
                // disabled feature stays off.
                armBlindSpotIfEnabled(context)
            }

            // ACC_MODE_CHANGED with NO parked marker (the marker-present case returned
            // above): a passive broadcast. In onAndOff this keeps today's behaviour — an
            // idempotent stack start (startOnBoot no-ops when already started) plus the
            // daemon-arbitrated blind-spot sync. It is NOT a recovery trigger, so it can
            // neither erase a parked marker nor arm the hotspot.
            //
            // Deferred, because on the OFF edge this broadcast usually lands BEFORE the
            // park reaper has planted the marker (the ACC judge needs a few seconds to
            // confirm OFF, then the reaper plants it); acting immediately restarted the
            // keepalive service that the ACC_OFF standdown had just stopped. Let the
            // park settle, then decide on the marker as it stands. The ON-edge case in
            // onAndOff only gains a short delay on an idempotent start.
            "com.byd.accmode.ACC_MODE_CHANGED" -> {
                val appCtx = context.applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()) {
                            Log.i(TAG, "ACC_MODE_CHANGED settled into a park — not starting; acc_sentry_daemon decides")
                            DaemonStartupManager.noteParkObserved()
                            DaemonStartupManager.ensureAccSentryJudgeRunning(appCtx)
                        } else {
                            startDaemons(appCtx, action)
                            armBlindSpotIfEnabled(appCtx)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Deferred ACC_MODE_CHANGED handling failed: ${e.message}")
                    }
                }, ACC_MODE_CHANGED_SETTLE_MS)
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
    // marker (verified) and recover the full stack: the direction-unambiguous BYD ACC/IGN
    // ON edges + head-unit boot. com.byd.accmode.ACC_MODE_CHANGED is deliberately NOT here:
    // it fires on both edges (see OnboardingGate / PinLockActivity), so it is handled as a
    // passive hint in onReceive and the ACC judge decides. A boot is treated as recovery
    // on purpose: a wrong recovery is cheap (acc_sentry_daemon's ACC-off detection re-parks
    // the stack within seconds), whereas staying parked wrongly costs the whole drive.
    private fun isRecoveryTrigger(trigger: String): Boolean = when (trigger) {
        "com.byd.action.ACC_ON",
        "com.byd.action.IGN_ON",
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
        val appCtx = context.applicationContext
        try {
            val markerPresent = java.io.File(
                com.overdrive.app.ui.model.ParkedShutdown.markerPath()).exists()
            if (markerPresent) {
                if (isRecoveryTrigger(trigger)) {
                    Log.i(TAG, "Recovery trigger '$trigger' with parked marker present — recovering (verified marker erase, then relaunch)")
                    // recoverFromPark erases the marker, VERIFIES it is gone, resets the
                    // bootStarted guard (the app process survives the park via the
                    // accessibility keep-alive, so the process-lifetime guard is still
                    // true), and only then calls back so the stack is launched into a
                    // marker-free state. If the erase fails, nothing is launched: the
                    // stack stays parked and the next ACC-on / boot edge retries.
                    DaemonStartupManager.recoverFromPark(appCtx) { launchStack(appCtx, trigger) }
                } else {
                    Log.i(TAG, "Parked-shutdown marker present + passive trigger '$trigger' — suppressing rebuild (stay asleep)")
                    DaemonStartupManager.noteParkObserved()
                    // Return WITHOUT bumping lastStartTime: a suppressed passive trigger
                    // must not consume the debounce slot, or a real recovery trigger
                    // arriving within 5s could be debounced away (the pre-debounce recovery
                    // path in onReceive covers the primary case; this keeps the slot free
                    // as defense-in-depth).
                }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parked-marker gate check failed (${e.message}) — proceeding with normal start")
        }

        launchStack(appCtx, trigger)
    }

    /**
     * The actual launch. Reached only with the parked marker absent — either it never
     * existed (onAndOff, or the ACC judge already erased it on a real ACC-on) or a
     * recovery trigger just completed a verified erase. startOnBoot carries its own
     * absolute marker gate and rebuilds after an observed park, so a passive trigger
     * arriving after the judge erased the marker also brings the stack back.
     */
    private fun launchStack(appCtx: Context, trigger: String) {
        // Only reached on an actual launch path — bump the debounce timer here (not at the
        // top) so suppressed passive returns never shadow a subsequent recovery.
        lastStartTime = System.currentTimeMillis()

        try {
            // Start DaemonKeepaliveService (foreground + sticky + wakelock)
            DaemonKeepaliveService.start(appCtx)

            // Also start daemons directly via DaemonStartupManager
            DaemonStartupManager.startOnBoot(appCtx)

            // (Re-)seed out-of-process revival watchdog. Self-heals the alarm
            // chain if it was ever broken (force-stop, reboot, app data clear).
            ProcessRevivalReceiver.schedule(appCtx)

            // Hotspot "start at power-up": one-shot per process, and only from a
            // recovery trigger — a passive broadcast must never bring the AP up
            // (it would drop the station link the user is currently on).
            if (isRecoveryTrigger(trigger)) {
                com.overdrive.app.network.HotspotManager.armBootAutoStart(appCtx)
            }

            Log.d(TAG, "Daemon startup initiated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemons: ${e.message}")
            e.printStackTrace()
        }
    }
}
