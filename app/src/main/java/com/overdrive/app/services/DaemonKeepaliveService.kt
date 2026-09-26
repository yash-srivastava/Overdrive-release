package com.overdrive.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.overdrive.app.R
import com.overdrive.app.receiver.ProcessRevivalReceiver
import com.overdrive.app.receiver.ScreenOffReceiver
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.daemon.DaemonStartupManager

/**
 * Foreground service that keeps the app alive and monitors SCREEN_OFF events.
 * 
 * Features:
 * - START_STICKY to restart if killed
 * - PARTIAL_WAKE_LOCK to prevent CPU sleep
 * - Registers ScreenOffReceiver for daemon survival
 * - Starts daemons on service start
 */
class DaemonKeepaliveService : Service() {
    
    companion object {
        private const val TAG = "DaemonKeepalive"
        private const val NOTIFICATION_ID = 19876
        private const val CHANNEL_ID = "daemon_keepalive_channel"

        // Shared notification grouping. The three foreground services that
        // Overdrive runs (this one + LocationSidecarService + StatusOverlay
        // Service) each post their own ongoing notification — Android needs
        // the FGS notification to remain visible per service. Tagging them
        // all with the same group key, plus a 4th `setGroupSummary(true)`
        // notification posted by this service, collapses the four entries
        // into a single expandable shade row. The user sees one "Overdrive
        // Active" tile with the per-service lines available on tap-to-expand.
        //
        // The summary notification is *not* a foreground-service notification
        // — it's a plain ongoing notification posted via NotificationManager
        // .notify(). FGS notifs can't be the group summary on every Android
        // version; a separate summary side-steps that and survives any of
        // the three children temporarily disappearing (e.g. permission flap
        // killing LocationSidecarService).
        const val NOTIFICATION_GROUP_KEY = "com.overdrive.app.STATUS"
        private const val SUMMARY_NOTIFICATION_ID = 19875
        private const val SUMMARY_CHANNEL_ID = "overdrive_status_summary"

        fun start(context: Context) {
            val intent = Intent(context, DaemonKeepaliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonKeepaliveService::class.java))
        }
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOffReceiver: ScreenOffReceiver? = null
    private var powerStateReceiver: BroadcastReceiver? = null
    private var startupOnOnlySnapshot: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        createNotificationChannel()
        createSummaryChannel()
        startForegroundWithNotification()
        postOrUpdateSummary()
        // A service started with startForegroundService must promote itself
        // before any shared-storage read. Keep the bounded, lock-free mode read
        // after startForeground, and reuse it for the first onStartCommand so a
        // cold AVM request never parses the same config twice on the main thread.
        val onOnlySnapshot =
            com.overdrive.app.config.UnifiedConfigManager
                .isVehicleOnOnlyModeSnapshot()
        startupOnOnlySnapshot = onOnlySnapshot
        acquireWakeLock()
        registerScreenOffReceiver()
        // GATE (G3): in "Vehicle ON only" mode the app-process keep-alive wakelock
        // ("Overdrive:DaemonKeepalive") must NOT pin the CPU 24/7 while parked, or the
        // head unit can never sleep even after the daemon-side gates (G1/G4) let it.
        // There is no reliable ACC-OFF broadcast in the app process (only ACC_ON/IGN_ON
        // are directionally trustworthy — see OnboardingGate), so we use SCREEN_OFF as
        // the parked-proxy release signal and SCREEN_ON/ACC_ON/IGN_ON to re-acquire.
        // Releasing on SCREEN_OFF is safe: a PARTIAL_WAKE_LOCK only has any effect when
        // the system would otherwise sleep, and while ACC is ON the vehicle powers the
        // AP awake regardless — so a transient screen-off during a drive costs nothing.
        // The release is MODE-GATED at fire time, so onAndOff keeps holding the lock
        // exactly as before (byte-identical behaviour).
        registerPowerStateReceiver()
        // ...but SCREEN_OFF is an EDGE broadcast: if this service is (re)created while
        // the vehicle is ALREADY parked with the screen already off — e.g. a START_STICKY
        // respawn or a ProcessRevival-driven relaunch while parked — no SCREEN_OFF will
        // follow, so the unconditional acquire above would pin the wakelock for the whole
        // parked window and defeat onOnly. Reconcile against the CURRENT screen state
        // once at startup: in onOnly, if the screen is already off, release now.
        reconcileWakeLockToScreenState(onOnlySnapshot)

        // Seed out-of-process revival watchdog. If this service was started
        // by the watchdog itself, this just re-arms the next alarm.
        try {
            ProcessRevivalReceiver.schedule(applicationContext, onOnlySnapshot)
        } catch (e: Exception) {
            Log.w(TAG, "ProcessRevivalReceiver.schedule failed: ${e.message}")
        }

    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")
        // This method is delivered on the app main thread. The daemon may be
        // holding the unified-config lock while it asks us to probe AVM, so a
        // blocking refresh here deadlocks the acknowledgement path and freezes
        // UI actions (including Projection). The daemon already committed this
        // marker before issuing the request; use that lock-free source here.
        com.overdrive.app.camera.dilink5.DiLink5Platform
            .refreshActiveModeFromCommittedMarker()
        val startDiLink5Avm = intent?.action ==
            com.overdrive.app.camera.dilink5.TsAvmCoordinator.ACTION_START_AVM
        val stopDiLink5Avm = intent?.action ==
            com.overdrive.app.camera.dilink5.TsAvmCoordinator.ACTION_STOP_AVM
        val probeDiLink5Avm = intent?.action ==
            com.overdrive.app.camera.dilink5.TsAvmCoordinator.ACTION_PROBE_AVM
        val startupSnapshot = startupOnOnlySnapshot
        val onOnlySnapshot = if (startupSnapshot != null) {
            startupOnOnlySnapshot = null
            startupSnapshot
        } else {
            com.overdrive.app.config.UnifiedConfigManager
                .isVehicleOnOnlyModeSnapshot()
        }
        val diLink5AvmCompletion =
            com.overdrive.app.camera.dilink5.TsAvmCoordinator.RequestCompletion {
                success ->
                com.overdrive.app.camera.dilink5.TsAvmCoordinator
                    .sendAppProcessResult(
                        intent,
                        success,
                        if (success) "" else "AVM transition was not confirmed"
                    )
            }
        // "Vehicle ON only" parked-shutdown gate. START_STICKY respawns this service after
        // the process is killed — but while parked (marker present) that respawn must NOT
        // rebuild the daemon stack or it would defeat the terminate. Stop self so the app
        // process can die again and the head unit stays asleep. The ACC-on recovery path
        // (BootReceiver) erases the marker (verified) and relaunches everything.
        //
        // The marker is AUTHORITATIVE on its own — this gate no longer also requires the
        // onOnly config read. That read fails open (false on any error), which let a
        // START_STICKY respawn rebuild the stack on a parked car whenever the config was
        // momentarily unreadable. A stray marker in onAndOff cannot happen: it is only
        // ever written by the onOnly park paths, and switching the mode to onAndOff
        // clears it daemon-side (SurveillanceApiHandler → CameraDaemon).
        try {
            if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.markerPath()).exists() &&
                !DaemonStartupManager.recoveryInProgress) {
                // Marker present AND we're not in the middle of an ACC-on recovery → this
                // is a START_STICKY respawn while genuinely parked; don't rebuild. The
                // recoveryInProgress guard avoids self-stopping on the recovery edge, where
                // the verified erase may not have completed yet even though the car is on
                // and we SHOULD stay up.
                DaemonStartupManager.noteParkObserved()
                Log.i(TAG, "parked-shutdown marker present (onOnly=$onOnlySnapshot, not recovering) — not rebuilding; stopping keepalive service")
                if (startDiLink5Avm) {
                    com.overdrive.app.camera.dilink5.TsAvmCoordinator
                        .sendAppProcessResult(
                            intent,
                            false,
                            "AVM start is unavailable while parked"
                        )
                }
                if (probeDiLink5Avm) {
                    com.overdrive.app.camera.dilink5.TsAvmCoordinator
                        .sendAppProcessResult(
                            intent,
                            false,
                            "AVM probe is unavailable while parked"
                        )
                }
                if (stopDiLink5Avm) {
                    releaseWakeLock()
                    com.overdrive.app.camera.dilink5.TsAvmCoordinator
                        .getInstance(applicationContext)
                        .stopAvm(
                            diLink5AvmCompletion,
                            Runnable { stopSelfResult(startId) }
                        )
                    Log.i(TAG, "DiLink 5 AVM stop dispatched in parked app process")
                } else {
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.w(TAG, "parked-marker onStartCommand gate failed (${e.message}) — proceeding")
        }

        if (stopDiLink5Avm) {
            com.overdrive.app.camera.dilink5.TsAvmCoordinator
                .getInstance(applicationContext)
                .stopAvm(diLink5AvmCompletion)
            Log.i(TAG, "DiLink 5 AVM stop dispatched in app process")
            return START_STICKY
        }

        if (probeDiLink5Avm) {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                com.overdrive.app.camera.dilink5.TsAvmCoordinator
                    .probeAvmService(
                        applicationContext,
                        diLink5AvmCompletion
                    )
                Log.i(TAG, "DiLink 5 AVM Binder preflight dispatched in app process")
            } else {
                com.overdrive.app.camera.dilink5.TsAvmCoordinator
                    .sendAppProcessResult(
                        intent,
                        false,
                        "DiLink 5 camera mode is unavailable"
                    )
                Log.w(TAG, "Ignoring DiLink 5 AVM probe while platform is disabled")
            }
            return START_STICKY
        }

        if (startDiLink5Avm) {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                com.overdrive.app.camera.dilink5.TsAvmCoordinator
                    .getInstance(applicationContext)
                    .startAvm(diLink5AvmCompletion)
                Log.i(TAG, "DiLink 5 AVM start dispatched in app process")
            } else {
                com.overdrive.app.camera.dilink5.TsAvmCoordinator
                    .sendAppProcessResult(
                        intent,
                        false,
                        "DiLink 5 camera mode is unavailable"
                    )
                Log.w(TAG, "Ignoring DiLink 5 AVM request while platform is disabled")
            }
            return START_STICKY
        }

        syncVehicleTelemetry()

        // Skip daemon startup when a post-update launch is in progress.
        // MainActivity is the sole orchestrator after an install: it runs
        // UpdateLifecycle.hardResetDaemons (kills zombie daemons + watchdogs +
        // wipes lock files) and THEN calls DaemonStartupManager.initializeOnAppLaunch.
        // If we also fired startOnBoot here, two things would race:
        //   1. The bootStarted flag would block initializeOnAppLaunch's view of
        //      the singleton (different instance — but both schedule 45s tasks),
        //      and we'd end up doing work in the still-zombie environment.
        //   2. Daemons launched here would be killed seconds later by the
        //      hardReset sweep, then restarted again — pointless thrash and
        //      a real risk of overlapping camera handles on the AVMCamera HAL.
        val postUpdate = com.overdrive.app.updater.UpdateLifecycle
            .isPostUpdateLaunch(applicationContext, null)
        if (postUpdate) {
            Log.i(TAG, "Post-update launch — deferring daemon startup to MainActivity")
        } else {
            try {
                DaemonStartupManager.startOnBoot(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start daemons: ${e.message}")
            }
        }
        
        // Bring the status pill back if the process was restarted without the
        // Activity running (e.g. system killed the process, then Android
        // respawned this keepalive service via START_STICKY).
        try {
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kick status overlay: ${e.message}")
        }


        // Same for the RoadSense overlay: it must be visible whenever the feature is
        // enabled, NOT only after the user opens MainActivity (its sole other launch
        // path, onResume). The app process is killed/respawned across ACC cycles, so
        // without this an ACC-on with RoadSense already enabled would leave the overlay
        // absent until the user manually opened the app. Gated on the enabled flag
        // (forceReload — the daemon/web UI may have just toggled it cross-UID) so a
        // disabled feature stays silent. The overlay itself only renders daemon-
        // published state, so showing it early just yields the idle/scanning pill.
        try {
            // The shared lifecycle policy also honours the user's overlayVisible
            // opt-out, and actively stops a stale service when the master is off.
            com.overdrive.app.roadsense.overlay.RoadSenseOverlayService
                .syncWithConfig(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kick RoadSense overlay: ${e.message}")
        }
        
        // START_STICKY ensures service restarts if killed
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")

        // Drop the group-summary so the user doesn't see a stale "Overdrive"
        // row after the keepalive service stops. The per-service FGS notifs
        // are auto-removed by the framework when their service stops; only
        // the summary (posted via NotificationManager.notify) needs an
        // explicit cancel.
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(SUMMARY_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel summary failed: ${e.message}")
        }

        unregisterScreenOffReceiver()
        unregisterPowerStateReceiver()
        com.overdrive.app.byd.BydDataCollector.stopDiLink5Producer()
        releaseWakeLock()

        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daemon Keepalive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps daemons running in background"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.i(TAG, "Foreground service started")
    }
    
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(R.string.daemon_keepalive_notif_title)
        val text  = getString(R.string.daemon_keepalive_notif_text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_sentry)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_sentry)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .build()
        }
    }

    /**
     * Dedicated channel for the group-summary notification. Kept separate
     * from the daemon-keepalive channel so the user can mute the summary
     * row without losing the per-service FGS notifications underneath
     * (Android requires the FGS channel to stay user-visible). Importance
     * mirrors the children — LOW means silent, no badge, but sticky.
     */
    private fun createSummaryChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUMMARY_CHANNEL_ID,
                "Overdrive Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Combined status row for Overdrive's background services"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Post (or refresh) the group-summary notification. This is what the
     * user actually sees collapsed in the shade. The three FGS children
     * (this service, LocationSidecarService, StatusOverlayService) all
     * carry the same `setGroup(NOTIFICATION_GROUP_KEY)` so Android pairs
     * them under this summary on API 20+ (every supported version of this
     * app — minSdk is 28).
     *
     * The summary is intentionally generic. We don't enumerate the three
     * children inline because the keepalive service can outlive the other
     * two:
     *   - On first launch, this summary is posted from Application.onCreate
     *     before MainActivity has had a chance to start LocationSidecar /
     *     StatusOverlay — naming them here would lie for a few hundred ms.
     *   - After an OOM kill with no Activity, START_STICKY only respawns
     *     this service; LocationSidecar (kicked from MainActivity) stays
     *     dead until the next app launch. A static "Location: GPS tracking"
     *     line would lie for the entire respawn window.
     * Tap-to-expand still shows whichever per-service FGS notifications
     * are actually live, so the user sees the truth on demand.
     *
     * Idempotent — calling it again replaces the existing summary.
     */
    private fun postOrUpdateSummary() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, SUMMARY_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.overdrive_status_summary_title))
            .setContentText(getString(R.string.overdrive_status_summary_text))
            .setSmallIcon(R.drawable.ic_sentry)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "postOrUpdateSummary failed: ${e.message}")
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Overdrive:DaemonKeepalive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
    
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        
        screenOffReceiver = ScreenOffReceiver.register(this)
        Log.i(TAG, "ScreenOffReceiver registered in service")
    }
    
    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            ScreenOffReceiver.unregister(this, it)
            screenOffReceiver = null
        }
    }

    /**
     * GATE (G3): drive the keep-alive wakelock by ACC/parked state so "Vehicle ON only"
     * mode lets the head unit sleep. Registered unconditionally; the RELEASE action is
     * mode-gated at fire time so a runtime mode flip is honoured without a service
     * restart, and onAndOff mode keeps the lock held exactly as before.
     *
     * PRIMARY signal is the vendor ACC edge (the same HAL-backed broadcast the daemon,
     * PinLockActivity and OnboardingGate key off):
     *   com.byd.action.ACC_OFF          → release iff onOnly (authoritative parked edge)
     *   com.byd.action.ACC_ON / IGN_ON  → always re-acquire (idempotent)
     * SCREEN_OFF is kept as a SECONDARY release trigger (belt-and-suspenders for a park
     * where the ACC edge is somehow missed) and SCREEN_ON as a secondary re-acquire.
     * Releasing on either edge in onOnly is safe: a PARTIAL_WAKE_LOCK only matters when
     * the system would otherwise sleep, and while ACC is ON the vehicle powers the AP
     * awake regardless, so a spurious release during a drive costs nothing and the next
     * ACC_ON/SCREEN_ON re-acquires. re-acquire is idempotent (no-op in onAndOff / when
     * already held), so the CPU is guaranteed awake the instant the car starts.
     */
    private fun registerPowerStateReceiver() {
        if (powerStateReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.byd.action.ACC_OFF" -> {
                        com.overdrive.app.byd.BydDataCollector
                            .updateDiLink5ProducerAccState(false)
                        // Authoritative parked edge → FULL app-side standdown in onOnly:
                        // the daemon stack is being terminated (reaper + parkTerminate), so
                        // the app must also stop keeping itself/anything alive.
                        if (com.overdrive.app.config.UnifiedConfigManager
                                .isVehicleOnOnlyModeSnapshot()) {
                            Log.i(TAG, "onOnly + ACC_OFF — full app-side standdown (release wakelock, stop health-check, cancel revival, stop service)")
                            parkStanddown(applicationContext)
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // Secondary/belt-and-suspenders: SCREEN_OFF can fire mid-drive, so
                        // only release the wakelock here (safe — moot while ACC powers the
                        // AP). Do NOT do the heavy standdown on a mere screen-off.
                        if (com.overdrive.app.config.UnifiedConfigManager
                                .isVehicleOnOnlyModeSnapshot()) {
                            Log.i(TAG, "onOnly + SCREEN_OFF — releasing keep-alive wakelock (secondary)")
                            releaseWakeLock()
                        }
                    }
                    Intent.ACTION_SCREEN_ON,
                    "com.byd.action.ACC_ON",
                    "com.byd.action.IGN_ON" -> {
                        com.overdrive.app.byd.BydDataCollector
                            .updateDiLink5ProducerAccState(true)
                        // Re-acquire for the awake/ON session. Idempotent (acquireWakeLock
                        // early-returns if already held), so this is a no-op in onAndOff.
                        acquireWakeLock()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.byd.action.ACC_OFF")
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction("com.byd.action.ACC_ON")
            addAction("com.byd.action.IGN_ON")
        }
        try {
            // Cross-UID vendor broadcasts (BYD system UID) + platform SCREEN_* — the
            // un-flagged registration is correct here (targetSdk 25 exempt), matching
            // OnboardingGate / PinLockActivity / ScreenOffReceiver.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
            powerStateReceiver = r
            Log.i(TAG, "PowerStateReceiver registered (G3 wakelock gating)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register PowerStateReceiver: ${t.message}")
        }
    }

    private fun unregisterPowerStateReceiver() {
        powerStateReceiver?.let {
            try { unregisterReceiver(it) } catch (t: Throwable) {
                Log.w(TAG, "Error unregistering PowerStateReceiver: ${t.message}")
            }
            powerStateReceiver = null
        }
    }

    private fun syncVehicleTelemetry() {
        Thread({
            try {
                // The full config-backed refresh is allowed to wait, but never
                // on Android's service/UI main thread.
                com.overdrive.app.camera.dilink5.DiLink5Platform
                    .refreshActiveMode()
                if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                    com.overdrive.app.byd.BydDataCollector
                        .syncDiLink5Producer(applicationContext)
                } else {
                    com.overdrive.app.byd.BydDataCollector
                        .stopDiLink5Producer()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Vehicle telemetry sync failed: ${t.message}")
            }
        }, "VehicleTelemetrySync").start()
    }

    /**
     * "Vehicle ON only" full app-side standdown on the authoritative ACC_OFF edge. The
     * daemon stack is being terminated (AccSentryDaemon reaper + CameraDaemon.parkTerminate),
     * so the app process must stop keeping anything alive so it too can die and the head
     * unit can sleep. Order:
     *  1. Stop the 30s health-check (no more relaunch probes).
     *  2. Cancel the RTC revival alarms now (don't wait for the next fire).
     *  3. Release the keep-alive wakelock.
     *  4. Stop this foreground service (and mark START_NOT_STICKY-equivalent by stopSelf).
     * The onStartCommand marker-gate handles any subsequent START_STICKY respawn. Recovery
     * on ACC-on (BootReceiver) clears the marker and restarts the whole stack.
     */
    private fun parkStanddown(appCtx: Context) {
        // Record the park in-process so startOnBoot can recognise the park-END later
        // (marker gone after having been present) and rebuild the stack even though
        // its process-lifetime guard is still set from the pre-park session.
        DaemonStartupManager.noteParkObserved()
        try { DaemonStartupManager.stopHealthChecks() } catch (t: Throwable) {
            Log.w(TAG, "parkStanddown: stopHealthChecks failed: ${t.message}")
        }
        try { ProcessRevivalReceiver.cancel(appCtx) } catch (t: Throwable) {
            Log.w(TAG, "parkStanddown: revival cancel failed: ${t.message}")
        }
        // Also stand down the sibling foreground services so no app-side loop keeps
        // running against the now-dead daemon (LocationSidecar's 1Hz GPS→IPC, StatusOverlay's
        // 10s /status poll). Their own onStartCommand parked-marker gate keeps them down
        // across any START_STICKY respawn; this stops them now. Recovery restarts them.
        try { com.overdrive.app.overlay.StatusOverlayService.stop(appCtx) } catch (t: Throwable) {
            Log.w(TAG, "parkStanddown: StatusOverlayService.stop failed: ${t.message}")
        }
        try {
            appCtx.stopService(Intent(appCtx,
                com.overdrive.app.services.LocationSidecarService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "parkStanddown: LocationSidecarService stop failed: ${t.message}")
        }
        releaseWakeLock()
        try { stopSelf() } catch (t: Throwable) {
            Log.w(TAG, "parkStanddown: stopSelf failed: ${t.message}")
        }
    }

    /**
     * One-shot startup reconciliation for the G3 wakelock. SCREEN_OFF is an edge-only
     * broadcast, so a service (re)start while the vehicle is already parked with the
     * screen already off would never receive the release edge — leaving the
     * "Overdrive:DaemonKeepalive" wakelock pinned for the whole parked window and
     * re-establishing the CPU keep-awake onOnly is meant to remove. Here we read the
     * CURRENT interactive state directly and, in onOnly with the screen already off,
     * release immediately. In onAndOff (or when the screen is on) this is a no-op, so
     * the default behaviour is unchanged. PowerManager.isInteractive() is a synchronous
     * app-process read — it mirrors the SCREEN_ON/OFF edges the receiver keys off.
     */
    private fun reconcileWakeLockToScreenState(onOnlySnapshot: Boolean) {
        try {
            if (!onOnlySnapshot) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                Log.i(TAG, "onOnly + screen already off at startup — releasing keep-alive wakelock so AP can sleep")
                releaseWakeLock()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reconcileWakeLockToScreenState failed: ${t.message}")
        }
    }
}
