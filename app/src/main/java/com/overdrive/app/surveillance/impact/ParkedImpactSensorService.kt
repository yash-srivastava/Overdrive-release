package com.overdrive.app.surveillance.impact

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.R
import com.overdrive.app.roadsense.detect.ImuSource
import com.overdrive.app.services.DaemonKeepaliveService
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject

/**
 * App-process accelerometer watch for parked impacts.
 *
 * The real inertial sensor is only readable from the app process, so — like the RoadSense
 * sidecar — this is a dumb pump whose lifecycle the daemon owns ([start]/[stop]). Unlike the
 * sidecar it streams nothing: detection runs here and only a confirmed hit crosses to the daemon,
 * as one HTTP call. Refuses to run on the stub accelerometer, whose canned waveform would fire.
 */
class ParkedImpactSensorService : Service(), SensorEventListener {

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private var sensorManager: SensorManager? = null
    private var registered = false
    private var detector: ImpactDetector? = null
    private var appliedSensitivity: String? = null

    private var samplesSinceHeartbeat = 0L
    private var lastHeartbeatMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        val t = HandlerThread("parked-impact", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        ioThread = t
        ioHandler = Handler(t.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sensitivity = intent?.getStringExtra(EXTRA_SENSITIVITY)
        ioHandler?.post { applySensitivity(sensitivity) }
        // The daemon's reconcile restarts us if the OS kills the process.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        registered = false
        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null
        Log.i(TAG, "stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applySensitivity(sensitivity: String?) {
        val threshold = ImpactDetector.thresholdFor(sensitivity)
        if (registered && sensitivity == appliedSensitivity) return
        appliedSensitivity = sensitivity
        detector = ImpactDetector(threshold)
        if (registered) {
            Log.i(TAG, "sensitivity=$sensitivity threshold=${threshold}m/s² (listener kept)")
            return
        }
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            Log.e(TAG, "no SensorManager — impact detection unavailable")
            stopSelf()
            return
        }
        sensorManager = sm
        val resolved = ImuSource.resolve(sm)
        val accel = resolved.accelerometer
        if (accel == null || !resolved.usableForDetection) {
            Log.w(TAG, "no real inertial accelerometer — impact detection unavailable on this trim")
            stopSelf()
            return
        }
        registered = sm.registerListener(this, accel, SAMPLE_PERIOD_US, ioHandler)
        lastHeartbeatMs = SystemClock.elapsedRealtime()
        samplesSinceHeartbeat = 0L
        Log.i(TAG, "watching ${accel.name} at ${1_000_000 / SAMPLE_PERIOD_US} Hz, " +
                "sensitivity=$sensitivity threshold=${threshold}m/s² registered=$registered")
        if (!registered) stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
        val now = SystemClock.elapsedRealtime()
        samplesSinceHeartbeat++
        if (now - lastHeartbeatMs >= HEARTBEAT_MS) {
            // Proves the sensor still delivers with the car off — the one thing not yet
            // confirmed on-car. A heartbeat with 0 samples means the HAL went quiet.
            Log.i(TAG, "heartbeat: $samplesSinceHeartbeat samples in ${(now - lastHeartbeatMs) / 1000}s")
            samplesSinceHeartbeat = 0L
            lastHeartbeatMs = now
        }
        val d = detector ?: return
        val peak = d.onSample(event.values[0], event.values[1], event.values[2], now)
        if (!peak.isNaN()) {
            Log.i(TAG, "impact: peak residual=${"%.2f".format(peak)} m/s²")
            reportImpact(peak)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun reportImpact(peak: Float) {
        // Already on the background sensor thread; a short bounded call is acceptable here and
        // the detector's cooldown caps how often it can happen.
        try {
            val conn = DaemonHttpClient.open(IMPACT_PATH, "POST", 1500, 2000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("peak", peak.toDouble()).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) Log.w(TAG, "daemon rejected impact report: HTTP $code")
            conn.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "impact report failed: ${t.message}")
        }
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_channel_parked_impact), NotificationManager.IMPORTANCE_LOW)
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
        return b.setContentTitle(com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_parked_impact_title))
            .setContentText(com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_parked_impact_text))
            .setSmallIcon(R.drawable.ic_diagnostics)
            .setOngoing(true)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    companion object {
        private const val TAG = "ParkedImpact"
        private const val CHANNEL_ID = "parked_impact"
        private const val NOTIFICATION_ID = 9988
        private const val SAMPLE_PERIOD_US = 20_000
        private const val HEARTBEAT_MS = 5 * 60 * 1000L
        private const val IMPACT_PATH = "/api/surveillance/impact"

        const val EXTRA_SENSITIVITY = "sensitivity"

        private const val COMPONENT =
            "com.overdrive.app/com.overdrive.app.surveillance.impact.ParkedImpactSensorService"

        /** Called from the daemon (shell uid); same `am` mechanism as the RoadSense sidecar. */
        @JvmStatic
        fun start(sensitivity: String) {
            val safe = when (sensitivity) { "low", "high" -> sensitivity; else -> "medium" }
            exec("am start-foreground-service -n $COMPONENT --es $EXTRA_SENSITIVITY $safe")
        }

        @JvmStatic
        fun stop() {
            exec("am stopservice -n $COMPONENT")
        }

        private fun exec(cmd: String) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)))
            } catch (t: Throwable) {
                Log.w(TAG, "exec failed [$cmd]: ${t.message}")
            }
        }
    }
}
