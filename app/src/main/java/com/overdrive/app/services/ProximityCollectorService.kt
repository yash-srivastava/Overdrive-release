package com.overdrive.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.overdrive.app.R
import com.overdrive.app.remoteaccess.BluetoothAccessMonitor
import com.overdrive.app.remoteaccess.KeyFobAccessMonitor
import com.overdrive.app.remoteaccess.SharedBluetoothKeepAliveController
import com.overdrive.app.util.AppLocaleContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps the app process, and with it the proximity collector, alive while parked.
 *
 * The head unit can kill this process at switch-off. The phone collector, its parked Bluetooth
 * keep-alive and the app's revival alarms all die with it, so walk-away lock had no readings until
 * something else restarted the app. The daemon's
 * [com.overdrive.app.remoteaccess.ProximityCollectorWatchdog] owns this service's lifecycle: it
 * starts it at switch-off, restarts it when readings stop and stops it on power-on. Each start
 * re-applies the saved settings; every resume call is idempotent and leaves a running collector
 * untouched. It shows no UI beyond its low-importance notification.
 */
class ProximityCollectorService : Service() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "proximity-collector-resume").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must be answered with startForeground, even on a running service.
        startForegroundCompat()
        // Re-apply off the main thread. On a cold start OverdriveApplication.onCreate has already
        // run the same idempotent calls, so this is then a no-op; on a live process it restarts a
        // collector that stopped.
        try {
            worker.execute { resumeCollectors() }
        } catch (t: Throwable) {
            Log.w(TAG, "resume not scheduled: ${t.message}")
        }
        // The daemon watchdog restarts us; a sticky respawn could outlive its stop on power-on.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
        Log.i(TAG, "stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resumeCollectors() {
        val app = applicationContext
        try { SharedBluetoothKeepAliveController.resume(app) } catch (t: Throwable) {
            Log.w(TAG, "Bluetooth keep-alive resume failed: ${t.javaClass.simpleName}")
        }
        try { KeyFobAccessMonitor.resumeIfEnabled(app) } catch (t: Throwable) {
            Log.w(TAG, "key fob resume failed: ${t.javaClass.simpleName}")
        }
        try { BluetoothAccessMonitor.resumeIfEnabled(app) } catch (t: Throwable) {
            Log.w(TAG, "phone collector resume failed: ${t.javaClass.simpleName}")
        }
    }

    private fun startForegroundCompat() {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed: ${t.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                AppLocaleContext.of(this).getString(R.string.notif_channel_proximity_collector),
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val text = AppLocaleContext.of(this)
        return b.setContentTitle(text.getString(R.string.notif_proximity_collector_title))
            .setContentText(text.getString(R.string.notif_proximity_collector_text))
            .setSmallIcon(R.drawable.ic_diagnostics)
            .setOngoing(true)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    private companion object {
        const val TAG = "ProximityCollector"
        const val CHANNEL_ID = "proximity_collector"
        const val NOTIFICATION_ID = 9989
    }
}
