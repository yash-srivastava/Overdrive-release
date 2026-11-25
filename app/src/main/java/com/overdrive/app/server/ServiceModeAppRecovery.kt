package com.overdrive.app.server

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.launcher.AdbShellExecutor
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-UID recovery fallback when the shell-UID camera daemon is down.
 *
 * The app cannot toggle other packages itself; it requests the *same* PM
 * restore operations through its already-supported authenticated ADB bridge.
 * This does not modify the daemon-owned serviceMode configuration, which the
 * daemon will clear when it next reconciles. A failed ADB connection is safe:
 * package states are retained for the next daemon/boot attempt.
 */
object ServiceModeAppRecovery {
    private const val TAG = "ServiceModeRecovery"
    private val inFlight = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { job ->
        Thread(job, "ServiceModeAppRecovery").apply { isDaemon = true }
    }
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    /** Safe to invoke on every boot or activity start. Does no disk/ADB work on the caller. */
    @JvmStatic
    fun tryRestore(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                val state = UnifiedConfigManager.forceReload().optJSONObject("serviceMode")
                if (state == null || !state.optBoolean("enabled", false) || !isDue(appContext, state)) {
                    inFlight.set(false)
                    return@execute
                }
                val original = state.optJSONArray("originalStates")
                val commands = ArrayList<String>()
                if (original != null) for (index in 0 until original.length()) {
                    val entry = original.optJSONObject(index) ?: continue
                    val name = entry.optString("package", "")
                    val previous = entry.optInt("state", -1)
                    if (!PACKAGE_NAME.matches(name) || name == "com.overdrive.app"
                        || (previous != 0 && previous != 1)) continue
                    val operation = if (previous == 0) "default-state" else "enable"
                    commands.add("pm $operation ${ServiceModeManager.userFlag(ServiceModeManager.targetUser(state))} '$name'")
                }
                if (commands.isEmpty()) {
                    inFlight.set(false)
                    return@execute
                }
                // Execute every restore even when an earlier package has been removed.
                val shell = "code=0; " + commands.joinToString("; ") { "$it || code=1" } + "; exit \$code"
                val adb = AdbShellExecutor(appContext)
                adb.execute(shell, object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        Log.i(TAG, "App-side package restoration requested; daemon will finalize state")
                        adb.shutdown()
                        inFlight.set(false)
                    }
                    override fun onError(error: String) {
                        Log.w(TAG, "App-side package restoration deferred: $error")
                        adb.shutdown()
                        inFlight.set(false)
                    }
                })
            } catch (error: Throwable) {
                Log.w(TAG, "App-side restoration check failed", error)
                inFlight.set(false)
            }
        }
    }

    private fun isDue(context: Context, state: JSONObject): Boolean {
        if (state.optBoolean("ending", false)) return true
        val currentBoot = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        } catch (_: Throwable) { -1L }
        val savedBoot = state.optLong("bootCount", -1L)
        val started = state.optLong("startedAtElapsedMs", -1L)
        val deadline = state.optLong("endsAtElapsedMs", -1L)
        if (currentBoot >= 0 && currentBoot == savedBoot && started >= 0 && deadline >= started) {
            return SystemClock.elapsedRealtime() >= deadline
        }
        return System.currentTimeMillis() >= state.optLong("endsAtEpochMs", 0L)
    }
}
