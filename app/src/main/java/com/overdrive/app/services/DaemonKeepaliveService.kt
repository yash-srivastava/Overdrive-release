package com.overdrive.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.overdrive.app.R
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
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        
        createNotificationChannel()
        startForegroundWithNotification()
        acquireWakeLock()
        registerScreenOffReceiver()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")
        
        // Start daemons
        try {
            DaemonStartupManager.startOnBoot(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemons: ${e.message}")
        }
        
        // Bring the status pill back if the process was restarted without the
        // Activity running (e.g. system killed the process, then Android
        // respawned this keepalive service via START_STICKY).
        try {
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kick status overlay: ${e.message}")
        }
        
        // START_STICKY ensures service restarts if killed
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        
        unregisterScreenOffReceiver()
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
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Overdrive Active")
                .setContentText("Monitoring vehicle systems")
                .setSmallIcon(R.drawable.ic_sentry)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Overdrive Active")
                .setContentText("Monitoring vehicle systems")
                .setSmallIcon(R.drawable.ic_sentry)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
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
}
