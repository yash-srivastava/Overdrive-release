package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager

/**
 * Launches Android services and configures permissions via ADB shell.
 * 
 * This class handles:
 * - Starting foreground services via shell (bypasses BYD's broadcast blocking)
 * - Granting location permissions via appops
 * - Configuring background operation settings
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class ServiceLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "ServiceLauncher"
        private const val PACKAGE_NAME = "com.overdrive.app"
    }
    
    interface LaunchCallback {
        fun onLog(message: String)
        fun onLaunched()
        fun onError(error: String)
    }
    
    /**
     * Start daemons via ADB shell.
     * This bypasses BYD's broadcast blocking (ssc_skip).
     */
    fun startDaemonsViaShell(callback: LaunchCallback) {
        logManager.info(TAG, "Starting daemons via shell...")
        callback.onLog("Starting daemons via shell...")
        
        // Start the main activity which will trigger DaemonStartupManager
        startViaActivity(callback)
    }
    
    private fun startViaActivity(callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "am start -n $PACKAGE_NAME/.ui.MainActivity 2>&1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Activity start result: ${output.trim()}")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Activity start failed: $error")
                    callback.onError("Service start failed: $error")
                }
            }
        )
    }
    
    /**
     * Grant location permissions via pm grant and appops for background location access.
     * This allows LocationSidecarService to run in background.
     */
    fun grantLocationPermissions(callback: LaunchCallback) {
        logManager.info(TAG, "Configuring Location permissions...")
        callback.onLog("Configuring Location permissions...")
        
        val commands = listOf(
            // Grant runtime permissions via pm grant (requires shell/root)
            "pm grant $PACKAGE_NAME android.permission.ACCESS_FINE_LOCATION",
            "pm grant $PACKAGE_NAME android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $PACKAGE_NAME android.permission.ACCESS_BACKGROUND_LOCATION",
            // Grant location permissions via appops
            "appops set $PACKAGE_NAME ACCESS_FINE_LOCATION allow",
            "appops set $PACKAGE_NAME ACCESS_COARSE_LOCATION allow",
            "appops set $PACKAGE_NAME ACCESS_BACKGROUND_LOCATION allow",
            // Additional permissions for background operation
            "appops set $PACKAGE_NAME RUN_IN_BACKGROUND allow",
            "appops set $PACKAGE_NAME RUN_ANY_IN_BACKGROUND allow",
            "appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow",
            "dumpsys deviceidle whitelist +$PACKAGE_NAME"
        )
        
        executeCommandSequence(commands, 0, callback) {
            logManager.info(TAG, "Location permissions configured")
            callback.onLog("Location permissions configured")
            callback.onLaunched()
        }
    }
    
    /**
     * Enable the KeepAliveAccessibilityService via ADB settings.
     * This gives the app process the highest possible priority — Android's OOM killer
     * and BYD's firmware process killer will not kill an active AccessibilityService.
     */
    fun enableAccessibilityKeepAlive(callback: LaunchCallback) {
        logManager.info(TAG, "Enabling AccessibilityService keep-alive...")
        callback.onLog("Enabling AccessibilityService keep-alive...")

        val serviceName = "$PACKAGE_NAME/$PACKAGE_NAME.services.KeepAliveAccessibilityService"

        val commands = listOf(
            // Get current enabled services and append ours (don't overwrite existing ones)
            "current=\$(settings get secure enabled_accessibility_services 2>/dev/null); " +
                "if echo \"\$current\" | grep -q '$serviceName'; then " +
                "echo 'Already enabled'; " +
                "else " +
                "if [ -z \"\$current\" ] || [ \"\$current\" = 'null' ]; then " +
                "settings put secure enabled_accessibility_services '$serviceName'; " +
                "else " +
                "settings put secure enabled_accessibility_services \"\$current:$serviceName\"; " +
                "fi; fi",
            "settings put secure accessibility_enabled 1"
        )

        executeCommandSequence(commands, 0, callback) {
            logManager.info(TAG, "AccessibilityService keep-alive enabled")
            callback.onLog("AccessibilityService keep-alive enabled")
            callback.onLaunched()
        }
    }

    /**
     * Apply power settings to keep WiFi and system active.
     */
    fun applyPowerSettings(callback: LaunchCallback) {
        logManager.info(TAG, "Applying power settings...")
        callback.onLog("Applying power settings...")
        
        val commands = listOf(
            // Force Wi-Fi to NEVER sleep
            "settings put global wifi_sleep_policy 2",
            // Force "Stay Awake" on AC/USB/Wireless (7)
            "settings put global stay_on_while_plugged_in 7",
            // Disable Doze Mode (Deep Sleep)
            "dumpsys deviceidle disable"
        )
        
        executeCommandSequence(commands, 0, callback) {
            logManager.info(TAG, "Power settings applied")
            callback.onLog("Power settings applied")
            callback.onLaunched()
        }
    }
    
    /**
     * Send wake-up broadcasts to keep system active.
     */
    fun sendWakeUpBroadcasts(callback: LaunchCallback? = null) {
        logManager.debug(TAG, "Sending wake-up broadcasts...")
        
        val commands = listOf(
            "am broadcast -a com.byd.action.KEEP_ALIVE 2>/dev/null",
            "input keyevent 224 2>/dev/null" // KEYCODE_WAKEUP
        )
        
        executeCommandSequence(commands, 0, callback ?: object : LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {}
        }) {
            callback?.onLaunched()
        }
    }
    
    /**
     * Turn screen off to reduce power consumption.
     */
    fun turnScreenOff(callback: LaunchCallback? = null) {
        logManager.info(TAG, "Turning screen off...")
        callback?.onLog("Turning screen off to save power...")
        
        val commands = listOf(
            "input keyevent 26", // KEYCODE_POWER
            "settings put system screen_off_timeout 1000",
            "svc power stayon false 2>/dev/null"
        )
        
        executeCommandSequence(commands, 0, callback ?: object : LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {}
        }) {
            logManager.info(TAG, "Screen off commands sent")
            callback?.onLaunched()
        }
    }
    
    // ==================== ACC WHITELIST ====================
    
    /**
     * Inject app into BYD ACC mode whitelist via ADB shell.
     * This allows WiFi to stay active when car is powered off.
     */
    fun injectAccWhitelist(packageName: String, callback: LaunchCallback) {
        logManager.info(TAG, "Injecting ACC whitelist for $packageName...")
        callback.onLog("Injecting ACC whitelist for $packageName...")
        
        val commands = listOf(
            // Method 1: setprop (persistent property)
            "setprop persist.sys.acc.whitelist '$packageName' 2>&1",
            // Method 2: service call with different transaction codes
            "service call accmodemanager 1 s16 '$packageName' 2>/dev/null",
            "service call accmodemanager 2 s16 '$packageName' 2>/dev/null",
            "service call accmodemanager 3 s16 '$packageName' 2>/dev/null",
            // Method 3: appops
            "appops set $packageName RUN_IN_BACKGROUND allow 2>/dev/null",
            "appops set $packageName RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
            "appops set $packageName WAKE_LOCK allow 2>/dev/null",
            // Method 4: Disable battery optimization
            "dumpsys deviceidle whitelist +$packageName 2>/dev/null",
            // Method 5: BYD Start Control whitelist — APPEND (merge), never
            // overwrite. The old `settings put ... '$packageName'` clobbered the
            // whole list to just OverDrive, wiping any co-installed app (e.g. the
            // standalone head-unit keep-alive) that had added itself. Read
            // current, add ourselves only if absent, preserve the rest.
            "CUR=\$(settings get global ssc_whitelist 2>/dev/null); [ \"\$CUR\" = null ] && CUR=; case \",\$CUR,\" in *,$packageName,*) ;; *) settings put global ssc_whitelist \"\${CUR:+\$CUR,}$packageName\" 2>/dev/null;; esac",
            "CUR=\$(settings get secure ssc_whitelist 2>/dev/null); [ \"\$CUR\" = null ] && CUR=; case \",\$CUR,\" in *,$packageName,*) ;; *) settings put secure ssc_whitelist \"\${CUR:+\$CUR,}$packageName\" 2>/dev/null;; esac",
            // Method 6: BYD app startup manager
            "content call --uri content://com.byd.appstartup/whitelist --method add --arg '$packageName' 2>/dev/null",
            "cmd appops set $packageName AUTO_START allow 2>/dev/null",
            "cmd appops set $packageName BOOT_COMPLETED allow 2>/dev/null"
        )
        
        executeCommandSequence(commands, 0, callback) {
            logManager.info(TAG, "ACC whitelist injection complete")
            callback.onLog("ACC whitelist injection complete")
            callback.onLaunched()
        }
    }
    
    /**
     * Enable WiFi and prevent it from sleeping via shell commands.
     */
    fun ensureWifiEnabled(callback: LaunchCallback) {
        // Honor an explicit user "WiFi off" automation / key-mapping. When the radio
        // action set the suppression flag we must NOT run `svc wifi enable` NOR write the
        // OEM firmware keep-alive hints (byd_wifi_always_on / byd_wifi_keep_alive), which
        // would make the firmware re-enable WiFi outside our control. Default false → the
        // full sequence runs as before. Fail-open on read error.
        if (com.overdrive.app.config.UnifiedConfigManager.isWifiKeepAliveSuppressed()) {
            logManager.info(TAG, "WiFi keep-alive skipped — user radio rule keeps WiFi off")
            callback.onLog("WiFi keep-alive skipped — user turned WiFi off")
            callback.onLaunched()
            return
        }
        logManager.info(TAG, "Ensuring WiFi stays enabled...")
        callback.onLog("Ensuring WiFi stays enabled...")

        val commands = listOf(
            "svc wifi enable",
            "settings put global wifi_sleep_policy 2",
            "settings put global wifi_scan_throttle_enabled 0",
            "settings put global wifi_on 1",
            "settings put global byd_wifi_always_on 1 2>/dev/null",
            "settings put system byd_wifi_keep_alive 1 2>/dev/null",
            "dumpsys deviceidle whitelist +com.android.wifi"
        )

        executeCommandSequence(commands, 0, callback) {
            logManager.info(TAG, "WiFi keep-alive settings applied")
            callback.onLog("WiFi keep-alive settings applied")
            callback.onLaunched()
        }
    }
    
    // ==================== LOCATION SIDECAR MANAGEMENT ====================
    
    /**
     * Start the LocationSidecarService via ADB shell.
     * This runs as shell user (UID 2000) with elevated privileges.
     */
    fun startLocationSidecarService(callback: LaunchCallback) {
        logManager.info(TAG, "Starting LocationSidecarService via shell...")
        callback.onLog("Starting LocationSidecarService via shell...")
        
        val cmd = "am start-foreground-service -n $PACKAGE_NAME/.services.LocationSidecarService 2>&1"
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "LocationSidecarService start result: ${output.trim()}")
                    callback.onLog("Service start result: ${output.trim()}")
                    
                    if (output.contains("Error") || output.contains("Exception")) {
                        callback.onError("Service start failed: ${output.trim()}")
                    } else {
                        callback.onLaunched()
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "LocationSidecarService start failed: $error")
                    callback.onError("Service start failed: $error")
                }
            }
        )
    }
    
    /**
     * Stop the LocationSidecarService via ADB shell.
     */
    fun stopLocationSidecarService(callback: LaunchCallback) {
        logManager.info(TAG, "Stopping LocationSidecarService via shell...")
        callback.onLog("Stopping LocationSidecarService via shell...")
        
        val cmd = "am stopservice -n $PACKAGE_NAME/.services.LocationSidecarService 2>&1"
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "LocationSidecarService stop result: ${output.trim()}")
                    callback.onLog("Service stop result: ${output.trim()}")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "LocationSidecarService stop failed: $error")
                    callback.onError("Service stop failed: $error")
                }
            }
        )
    }
    
    /**
     * Check if LocationSidecarService is running.
     */
    fun isLocationSidecarRunning(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "dumpsys activity services $PACKAGE_NAME/.services.LocationSidecarService | grep -i 'ServiceRecord'",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().isNotEmpty() && !output.contains("app=null"))
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun executeCommandSequence(
        commands: List<String>,
        index: Int,
        callback: LaunchCallback,
        onComplete: () -> Unit
    ) {
        if (index >= commands.size) {
            onComplete()
            return
        }
        
        adbShellExecutor.execute(
            command = commands[index],
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    executeCommandSequence(commands, index + 1, callback, onComplete)
                }
                
                override fun onError(error: String) {
                    // Continue even on error - some commands may fail on certain devices
                    logManager.warn(TAG, "Command failed (continuing): ${commands[index]} - $error")
                    executeCommandSequence(commands, index + 1, callback, onComplete)
                }
            }
        )
    }
}
