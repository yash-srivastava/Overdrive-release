package com.overdrive.app.daemon.sentry

import com.overdrive.app.logging.DaemonLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Controls ACC (Accessory) mode monitoring for sentry mode.
 * 
 * Monitors sys.accanim.status property to detect ACC ON/OFF transitions.
 * When ACC goes OFF, triggers sentry mode entry.
 * 
 * Extracted from SentryDaemon for better separation of concerns.
 */
class AccMonitorController(
    private val onAccOff: () -> Unit,
    private val onAccOn: () -> Unit
) {
    
    companion object {
        private val logger = DaemonLogger.getInstance("AccMonitorController")
        
        // Power levels from BYDAutoBodyworkDevice
        const val POWER_LEVEL_OFF = 0
        const val POWER_LEVEL_ACC = 1
        const val POWER_LEVEL_ON = 2
        const val POWER_LEVEL_OK = 3
        // Adaptive polling intervals
        const val POLL_INTERVAL_SENTRY_MS = 3_000L   // 3s when car is OFF / parked in sentry
        const val POLL_INTERVAL_ACTIVE_MS = 1_500L   // 1.5s when car is ON / driving
    }
    
    @Volatile
    private var running = true
    
    @Volatile
    private var lastAccAnimStatus = "0"
    
    private var pollingThread: Thread? = null
    
    /**
     * Start polling mode for ACC status monitoring.
     */
    fun startPolling() {
        logger.info("Starting polling mode (adaptive throttle: ${POLL_INTERVAL_SENTRY_MS}ms sentry / ${POLL_INTERVAL_ACTIVE_MS}ms active)...")
        
        // Log initial state
        logAllPowerSources()
        
        pollingThread = Thread({
            var pollCount = 0
            
            // Get initial state - treat empty/"0" as ACC ON
            lastAccAnimStatus = execShell("getprop sys.accanim.status").trim()
            if (lastAccAnimStatus.isEmpty()) {
                lastAccAnimStatus = "0" // Empty means ACC ON
            }
            logger.info("Initial sys.accanim.status: '$lastAccAnimStatus' (0 or empty = ACC ON)")
            
            // If we start with ACC already OFF (status != 0), enter sentry mode
            if (lastAccAnimStatus != "0") {
                logger.info("Started with ACC OFF (status=$lastAccAnimStatus) - entering sentry mode")
                onAccOff()
            } else {
                logger.info("Started with ACC ON - waiting for ACC OFF event...")
            }
            
            var isCurrentlyAccOff = (lastAccAnimStatus != "0")
            var lastCarServiceCheckMs = 0L

            while (running) {
                try {
                    val sleepInterval = if (isCurrentlyAccOff) POLL_INTERVAL_SENTRY_MS else POLL_INTERVAL_ACTIVE_MS
                    Thread.sleep(sleepInterval)
                    pollCount++
                    
                    // Diagnostic logging: reduced frequency to once every ~10 minutes
                    if (pollCount % 200 == 0) {
                        logAllPowerSources()
                    }
                    
                    // 1. Check Display Power & Interactive State (prefer direct Binder call, zero forks)
                    val isScreenOff = checkScreenOff()

                    // 2. Fast property check
                    var accAnimStatus = execShell("getprop sys.accanim.status").trim()

                    // 3. Cadenced CarService check: run when screen is ON, or every 9s in sentry, or when active
                    val nowMs = System.currentTimeMillis()
                    val shouldCheckCarService = !isScreenOff || !isCurrentlyAccOff || (nowMs - lastCarServiceCheckMs >= 9_000L)
                    
                    var isStandby = false
                    var carPowerMode = ""
                    if (shouldCheckCarService) {
                        carPowerMode = execShell("dumpsys car_service 2>/dev/null | grep -i 'Power Mute State' -A 2 | grep 'current' | head -1").trim()
                        isStandby = carPowerMode.contains("Standby") || carPowerMode.contains("Sleep") || carPowerMode.contains("Str") || carPowerMode.contains("4=") || carPowerMode.contains("8=") || carPowerMode.contains("5=")
                        lastCarServiceCheckMs = nowMs
                    } else if (isCurrentlyAccOff && isScreenOff) {
                        // While screen is off and car was off, maintain standby without waking car_service
                        isStandby = true
                    }

                    if (accAnimStatus.isEmpty()) {
                        accAnimStatus = if (isScreenOff || isStandby) "1" else "0"
                    }

                    // Combined ACC OFF logic: if screen is OFF, car in Standby, or accanim.status is 1
                    val isAccOffNow = (accAnimStatus != "0") || isScreenOff || isStandby
                    val wasAccOff = (lastAccAnimStatus != "0")

                    if (isAccOffNow != wasAccOff) {
                        logger.info(">>> ACC STATE CHANGED: isAccOffNow=$isAccOffNow (wasAccOff=$wasAccOff, isStandby=$isStandby, screenOff=$isScreenOff, accAnim=$accAnimStatus, powerMode=$carPowerMode)")
                        if (isAccOffNow) {
                            logger.info("!!! ACC OFF DETECTED (Standby/ScreenOff) -> ENTER SENTRY !!!")
                            onAccOff()
                        } else {
                            logger.info("!!! ACC ON DETECTED -> EXIT SENTRY !!!")
                            onAccOn()
                        }
                        lastAccAnimStatus = if (isAccOffNow) "1" else "0"
                        isCurrentlyAccOff = isAccOffNow
                        logAllPowerSources() // Log full snapshot on actual state change
                    }
                    
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Polling error: ${e.message}")
                    try { Thread.sleep(1000) } catch (ignored: Exception) {}
                }
            }
        }, "PowerLevelPoller")
        
        pollingThread?.start()
    }
    
    /**
     * Check if display is OFF or non-interactive.
     * Uses direct PowerManager Binder call when context is available (0 forks),
     * falling back to a single dumpsys power query.
     */
    private fun checkScreenOff(): Boolean {
        try {
            val ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext()
            if (ctx != null) {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm != null) {
                    return !pm.isInteractive
                }
            }
        } catch (t: Throwable) {
            // Fall back to dumpsys
        }

        val screenState = execShell("dumpsys power 2>/dev/null | grep -E -i 'Display Power: state=|mIsInteractive' | head -2").trim()
        val isInteractive = !screenState.contains("mIsInteractive=false") && !screenState.contains("mIsInteractive: false")
        val isDisplayOn = screenState.contains("state=ON")
        return !isDisplayOn || !isInteractive
    }
    
    /**
     * Stop polling.
     */
    fun stopPolling() {
        running = false
        pollingThread?.interrupt()
        pollingThread = null
    }

    
    /**
     * Log power state from all available sources for debugging.
     */
    private fun logAllPowerSources() {
        logger.info("=== Power State Snapshot ===")
        
        // 1. Driving state from byd_car_service
        val drivingState = getDrivingState()
        logger.info("Driving State: $drivingState")
        
        // 2. ACC animation status
        val accAnimStatus = execShell("getprop sys.accanim.status")
        logger.info("sys.accanim.status: $accAnimStatus")
        
        // 3. ACC animation service
        val accAnimSvc = execShell("getprop init.svc.accanim")
        logger.info("init.svc.accanim: $accAnimSvc")
        
        // 4. accmodemanager dirty flag
        val accDump = execShell("dumpsys accmodemanager 2>/dev/null | head -5")
        logger.info("accmodemanager: ${accDump.replace("\n", " | ")}")
        
        // 5. Screen state
        val screenState = execShell("dumpsys power 2>/dev/null | grep -i 'Display Power' | head -1")
        logger.info("Display Power: $screenState")
        
        logger.info("=== End Snapshot ===")
    }
    
    /**
     * Get driving state from byd_car_service.
     */
    private fun getDrivingState(): Int {
        return try {
            val output = execShell("dumpsys byd_car_service 2>/dev/null | grep 'Current Driving State'")
            if (output.contains(":")) {
                val value = output.split(":")[1].trim()
                value.toInt()
            } else -1
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Convert power level to human-readable string.
     */
    fun powerLevelToString(level: Int): String {
        return when (level) {
            0 -> "OFF(0)"
            1 -> "ACC(1)"
            2 -> "ON(2)"
            3 -> "OK(3)"
            4 -> "FAKE_OK(4)"
            255 -> "INVALID(255)"
            else -> "UNKNOWN($level)"
        }
    }
    
    /**
     * Execute shell command.
     */
    private fun execShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            process.waitFor()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
