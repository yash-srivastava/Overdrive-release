package com.overdrive.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.overdrive.app.R
import com.overdrive.app.logging.LogManager
import com.overdrive.app.network.HotspotManager

/**
 * Bridge that lets the UID-2000 daemon reach [HotspotManager], which lives in
 * the app process because only a real app process can drive the tethering
 * binder. The daemon starts this with `am start-foreground-service` and an
 * `action` extra; DUMP permission restricts starts to shell/system callers.
 */
class HotspotBridgeService : Service() {

    private val log = LogManager.getInstance()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startForegroundCompat()) {
            stopSelf()
            return
        }
        HotspotManager.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var action: String? = null
        try {
            action = intent?.getStringExtra("action")
            HotspotManager.init(applicationContext)
            when (action) {
                "enable" -> {
                    HotspotManager.ensureWriteSettingsAppop()
                    HotspotManager.enable { ok, msg -> log.info(TAG, "enable -> $ok $msg") }
                }
                "disable" -> HotspotManager.disable { ok, msg -> log.info(TAG, "disable -> $ok $msg") }
                // The daemon already persisted the values; re-apply the side
                // effects from config so AP config / proxy state converge.
                "settings" -> HotspotManager.reapplyPersistedSettings()
                "reset-usage" -> HotspotManager.resetUsage(null)
                null -> { /* bare start (service restart) — nothing to apply */ }
                else -> log.warn(TAG, "unknown action: $action")
            }
        } catch (t: Throwable) {
            log.warn(TAG, "bridge action failed ($action): ${t.message}")
        }
        // The manager is a process-wide singleton with its own worker, so this
        // service has nothing left to hold once the request is queued.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(): Boolean {
        val n = buildNotification()
        // Tier exactly as VehicleActuatorService: SPECIAL_USE is API-34, so pass
        // DATA_SYNC on Q..33 (this API-29 head unit) and fall back to bare.
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (t: Throwable) {
            try {
                startForeground(NOTIFICATION_ID, n)
                true
            } catch (t2: Throwable) {
                log.error(TAG, "foreground promotion failed: ${t2.message}")
                false
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_network_hotspot), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle(com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_network_hotspot))
            .setContentText("OverDrive")
            .setSmallIcon(R.drawable.ic_play_circle)
            .setOngoing(false)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    companion object {
        private const val TAG = "HotspotBridge"
        private const val CHANNEL_ID = "overdrive_hotspot_bridge"
        private const val NOTIFICATION_ID = 9981
    }
}
