package com.overdrive.app.roadsense.sidecar

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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.R
import com.overdrive.app.roadsense.detect.ImuAccelSample
import com.overdrive.app.roadsense.detect.ImuFrameCodec
import com.overdrive.app.roadsense.detect.ImuGyroSample
import com.overdrive.app.roadsense.detect.ImuSource
import com.overdrive.app.services.DaemonKeepaliveService
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The ONLY app-process component of RoadSense (D-023).
 *
 * It acquires the real `-iner` accelerometer + gyroscope (the one thing that
 * cannot be read from the daemon's existing singletons — no component streams
 * 100 Hz inertial data), batches samples, and ships them to the CameraDaemon's
 * IPC server (port 19877) as `IMU_BATCH` frames. ALL detection/storage/sync logic
 * runs daemon-side; this service is a dumb pump.
 *
 * Mirrors `LocationSidecarService`: a foreground service with a background thread
 * for all sensor + network work. The difference is RATE — 100 Hz × 2 sensors — so
 * we **batch** (~[ImuFrameCodec.TARGET_BATCH_MS] per frame) and keep one socket
 * while driving instead of connecting per sample.
 *
 * Resource scaling (D-021) is driven by the daemon via start/stop of THIS service
 * and the [EXTRA_RATE] hint: DRIVING → FAST (~100 Hz), RELAXED → SLOW (service
 * resident, sensors unregistered), ACC OFF → the daemon stops the service entirely.
 * Keeping the service/handler resident preserves the existing fast D-gear resume
 * without streaming or serializing unused IMU data in P/N/R.
 */
class RoadSenseImuSidecarService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var ioThread: HandlerThread? = null
    private var ioHandler: android.os.Handler? = null
    // Applied operating mode. FAST means listeners are registered; SLOW is the
    // compatibility command name for the resident-but-paused RELAXED state.
    // Read/written on ioHandler's thread but cleared from onDestroy on main.
    @Volatile private var registeredRate: ImuRate? = null

    // Batch accumulators — only touched on ioHandler's thread (sensor callbacks
    // are delivered there), so no locking needed.
    private val accelBatch = ArrayList<ImuAccelSample>(16)
    private val gyroBatch = ArrayList<ImuGyroSample>(16)
    private var batchStartMs = 0L
    @Volatile private var currentRate = ImuRate.FAST

    // Persistent IPC socket to the daemon (SurveillanceIpcServer 19877). At ~10
    // batches/sec (TARGET_BATCH_MS=100) a connect-per-batch was ~10 TCP
    // handshakes/sec, every second, while driving. We now hold ONE socket and
    // stream batches over it; the daemon server loops per-connection, so this is
    // zero connects in steady state. Only touched on ioHandler's single thread
    // (flush/sendLine + the posted close in onDestroy), so no locking needed.
    private var ipcSocket: Socket? = null
    private var ipcOut: OutputStream? = null

    enum class ImuRate { FAST, SLOW }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        val t = HandlerThread("roadsense-imu", Process.THREAD_PRIORITY_DEFAULT)
        t.start()
        ioThread = t
        ioHandler = android.os.Handler(t.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rateName = intent?.getStringExtra(EXTRA_RATE)
        currentRate = if (rateName == ImuRate.SLOW.name) ImuRate.SLOW else ImuRate.FAST
        ioHandler?.post { applyRate(currentRate) }
        // Not sticky: the daemon owns our lifecycle (D-021). If the OS kills us,
        // the daemon's controller re-starts us on the next DRIVING evaluation.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        registeredRate = null
        // Close the persistent IPC socket on the thread that owns it, before the
        // looper quits. quitSafely() lets already-posted work (this close) run.
        ioHandler?.post { closeIpcSocket() }
        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Sensor acquisition ────────────────────────────────────────────────────

    private fun applyRate(rate: ImuRate) {
        // Idempotence (audit): a redundant start at the SAME rate (e.g. the daemon
        // re-issuing the same regime) must not churn listeners or the IPC connection.
        if (rate == registeredRate) {
            Log.d(TAG, "applyRate: already at rate=$rate, no-op")
            return
        }
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: run {
            Log.e(TAG, "no SensorManager")
            return
        }
        sensorManager = sm

        // Every mode transition starts from a clean listener/batch state. In
        // particular, don't let a partial FAST batch escape after gear leaves D.
        sm.unregisterListener(this)
        registeredRate = null
        accelBatch.clear()
        gyroBatch.clear()
        batchStartMs = 0L

        if (accel == null) {
            val resolved = ImuSource.resolve(sm)
            if (!resolved.usableForDetection) {
                // R-EXT-6 graceful fallback: no real -iner accel on this trim.
                Log.w(TAG, "no usable -iner accelerometer on this trim; stopping IMU sidecar")
                stopSelf()
                return
            }
            accel = resolved.accelerometer
            gyro = resolved.gyroscope
        }

        if (rate == ImuRate.SLOW) {
            // RELAXED does no detection, so even 5 Hz callbacks only create JSON,
            // socket and GC work. Keep the FGS + HandlerThread resident for the
            // existing fast resume path, but release sensors and the daemon worker
            // blocked on this otherwise-idle persistent connection.
            closeIpcSocket()
            registeredRate = ImuRate.SLOW
            Log.i(TAG, "IMU sidecar paused for RELAXED; sensors unregistered")
            return
        }

        val activeAccel = accel ?: run {
            Log.w(TAG, "resolved accelerometer disappeared; stopping IMU sidecar")
            stopSelf()
            return
        }

        val delay = SensorManager.SENSOR_DELAY_FASTEST
        // Hardware FIFO batching (FAST only): ask the sensor hub to buffer up to
        // ~TARGET_BATCH_MS of samples and wake the AP once per window instead of
        // per sample. At 100 Hz × 2 sensors this cuts AP wakeups from ~200/s to
        // ~10/s with ZERO detection impact — every SensorEvent still carries its
        // own hardware timestamp (see wallClockFromElapsed), and the daemon
        // reassembles by that timestamp, so batched delivery is indistinguishable
        // downstream. End-to-end latency is unchanged: the software flush window
        // (TARGET_BATCH_MS) already delayed shipping by the same amount. If the
        // sensor has no hardware FIFO, Android silently falls back to per-sample
        // delivery — strictly no worse than before. The batch is also hard-capped
        // by MAX_SAMPLES_PER_FRAME in onSensorChanged.
        val maxReportLatencyUs = (ImuFrameCodec.TARGET_BATCH_MS * 1000L).toInt()
        sm.registerListener(this, activeAccel, delay, maxReportLatencyUs, ioHandler)
        gyro?.let { sm.registerListener(this, it, delay, maxReportLatencyUs, ioHandler) }
        registeredRate = ImuRate.FAST
        Log.i(TAG, "IMU sidecar registered: accel=${accel?.name} gyro=${gyro?.name} rate=FAST")
    }

    override fun onSensorChanged(event: SensorEvent) {
        // unregisterListener can race with an already-queued callback. Once RELAXED
        // has been applied, ignore that tail event instead of recreating a batch.
        if (registeredRate != ImuRate.FAST) return
        val tMs = wallClockFromElapsed(event.timestamp)
        if (batchStartMs == 0L) batchStartMs = tMs
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                accelBatch.add(ImuAccelSample(tMs, event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_GYROSCOPE ->
                gyroBatch.add(ImuGyroSample(tMs, event.values[0], event.values[1], event.values[2]))
        }
        // Flush when the batch spans the target window OR a defensive cap is hit
        // (so a stall-then-burst can't build an unbounded list).
        val span = tMs - batchStartMs
        val overCap = accelBatch.size >= ImuFrameCodec.MAX_SAMPLES_PER_FRAME ||
            gyroBatch.size >= ImuFrameCodec.MAX_SAMPLES_PER_FRAME
        if (span >= ImuFrameCodec.TARGET_BATCH_MS || overCap) flush()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* ignore */ }

    /** Encode the current batch and ship it; runs on ioHandler's thread. */
    private fun flush() {
        if (accelBatch.isEmpty() && gyroBatch.isEmpty()) return
        val line = ImuFrameCodec.encode(accelBatch, gyroBatch)
        accelBatch.clear()
        gyroBatch.clear()
        batchStartMs = 0L
        sendLine(line)
    }

    /**
     * Ship one batch over the PERSISTENT socket, reconnecting once on failure.
     * Fire-and-forget: we don't read the daemon's ack (newline-delimited write
     * is all the server needs). Runs on ioHandler's thread only.
     */
    private fun sendLine(line: String) {
        // SECURITY (run-2 fix): sign the IPC line; the daemon rejects unsigned writes.
        val payload = (com.overdrive.app.remote.RemoteDevViewBridgeAuth.sign(org.json.JSONObject(line)) + "\n").toByteArray()
        // First attempt on the existing connection; if the write fails (daemon
        // restarted / socket reset), reconnect once and retry. A failure after
        // reconnect just drops the batch — the next batch reconnects again.
        if (writeWithReconnect(payload, allowReconnect = true)) return
    }

    /** @return true if the payload was written. */
    private fun writeWithReconnect(payload: ByteArray, allowReconnect: Boolean): Boolean {
        try {
            if (ipcSocket == null) {
                if (!allowReconnect) return false
                if (!openIpcSocket()) return false
            }
            ipcOut!!.write(payload)
            ipcOut!!.flush()
            return true
        } catch (_: java.net.ConnectException) {
            // Daemon not up yet / restarting — expected transient, drop the batch.
            closeIpcSocket()
            return false
        } catch (_: Throwable) {
            // Socket went bad (daemon restart, reset). Drop this connection and,
            // if we haven't already retried, reconnect once and retry the write.
            closeIpcSocket()
            return if (allowReconnect) writeWithReconnect(payload, allowReconnect = false) else false
        }
    }

    private fun openIpcSocket(): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", DAEMON_IPC_PORT), 1000)
            s.soTimeout = 1000
            s.tcpNoDelay = true
            ipcSocket = s
            ipcOut = s.getOutputStream()
            true
        } catch (_: Throwable) {
            closeIpcSocket()
            false
        }
    }

    private fun closeIpcSocket() {
        try { ipcSocket?.close() } catch (_: Throwable) {}
        ipcSocket = null
        ipcOut = null
    }

    private fun wallClockFromElapsed(elapsedNs: Long): Long {
        val nowWall = System.currentTimeMillis()
        val nowEr = SystemClock.elapsedRealtimeNanos()
        return nowWall - (nowEr - elapsedNs) / 1_000_000L
    }

    // ── Foreground notification ────────────────────────────────────────────────

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
            val ch = NotificationChannel(CHANNEL_ID, "RoadSense IMU", NotificationManager.IMPORTANCE_LOW)
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
        return b.setContentTitle("RoadSense")
            .setContentText("Road sensing active")
            .setSmallIcon(R.drawable.ic_diagnostics)
            .setOngoing(true)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    companion object {
        private const val TAG = "RoadSense/ImuSidecar"
        private const val CHANNEL_ID = "roadsense_imu"
        private const val NOTIFICATION_ID = 9987
        private const val DAEMON_IPC_PORT = 19877

        /** Intent extra carrying the desired [ImuRate] name (D-021 rate scaling). */
        const val EXTRA_RATE = "rate"

        /** Fully-qualified component for the daemon's `am` launch (see [start]). */
        private const val COMPONENT =
            "com.overdrive.app/com.overdrive.app.roadsense.sidecar.RoadSenseImuSidecarService"

        /**
         * Start the sidecar at [rate]. Called from the CameraDaemon (app_process,
         * shell uid) whose synthetic Context CANNOT launch an app-process Service via
         * startForegroundService() — that is a silent cross-process no-op, the bug
         * that left RoadSense inert (no IMU → calibration stuck at 0 → "Calibrating"
         * forever, empty hazard store). So we use the SAME proven mechanism the daemon
         * already uses for LocationSidecarService (see GpsMonitor.START_CMD): a shell
         * `am start-foreground-service -n <component>` exec. The service is
         * `exported="true"` (like LocationSidecarService) so `am` from the shell-uid
         * daemon can reach it; it then runs in the real app process. The [rate] rides
         * as a string extra.
         */
        fun start(rate: ImuRate) {
            exec("am start-foreground-service -n $COMPONENT --es $EXTRA_RATE ${rate.name}")
        }

        fun stop() {
            exec("am stopservice -n $COMPONENT")
        }

        /**
         * Run an `am` command via Runtime.exec — fire-and-forget, exactly like
         * GpsMonitor's daemon→sidecar launch (no waitFor). This runs on the daemon's
         * ~2 Hz regime/warning tick thread, so we must NOT block it: `am` returns in
         * ~100 ms normally, but a hung `am` must never stall the tick (which also
         * drives approach warnings). The OS reaps the short-lived child; a failed
         * launch is harmless and self-corrects on the next regime transition.
         */
        private fun exec(cmd: String) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "exec failed [$cmd]: ${t.message}")
            }
        }
    }
}
