package com.overdrive.app.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.ui.util.PreferencesManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in permission bootstrap for the EXISTING DiLink 5 recovery in AdbShellExecutor.
 * Does not write ADB settings, poll for recovery, or invoke the OEM escalator.
 * The existing DiLink 5 port watcher remains the sole recovery owner.
 */
object DiLink5AdbPermissionBootstrap {
    private const val TAG = "Di5AdbPermission"
    private const val POLL_MS = 60_000L
    private const val RETRY_MS = 300_000L
    private val running = AtomicBoolean(false)

    fun startIfEnabled(context: Context) {
        if (!DiLink5Platform.isSelected() || !PreferencesManager.isDiLink5AdbRecoverySetupEnabled()) return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                var lastAttempt = -RETRY_MS
                while (DiLink5Platform.isSelected() && PreferencesManager.isDiLink5AdbRecoverySetupEnabled()) {
                    try {
                        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                            PackageManager.PERMISSION_GRANTED) {
                            Log.i(TAG, "WRITE_SECURE_SETTINGS is available for existing DiLink 5 recovery")
                            break
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAttempt >= RETRY_MS && !AdbShellExecutor.isAdbPortClosedNow()) {
                            lastAttempt = now
                            val adb = AdbShellExecutor(app)
                            try {
                                val result = adb.executeSync(
                                    "pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS", 10_000L
                                )
                                if (result.exitCode != 0 || app.checkSelfPermission(
                                        Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                                    Log.w(TAG, "Authorized ADB permission grant failed; retry later")
                                }
                            } finally {
                                adb.shutdown()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DiLink 5 ADB permission setup failed", e)
                    }
                    Thread.sleep(POLL_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
            }
        }, "Di5AdbPermission").apply { isDaemon = true; start() }
    }
}
