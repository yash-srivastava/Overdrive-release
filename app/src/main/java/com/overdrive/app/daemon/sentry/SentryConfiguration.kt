package com.overdrive.app.daemon.sentry

import com.overdrive.app.util.ScratchPaths
/**
 * Sentry daemon configuration constants.
 * 
 * Extracted from SentryDaemon for better separation of concerns.
 */
object SentryConfiguration {
    
    // Power levels from BYDAutoBodyworkDevice
    const val POWER_LEVEL_OFF = 0
    const val POWER_LEVEL_ACC = 1
    const val POWER_LEVEL_ON = 2
    const val POWER_LEVEL_OK = 3
    
    // Log file paths
    const val LOG_FILE_SYSTEM = "/data/system/sentry_daemon.log"
    val LOG_FILE_SHELL = ScratchPaths.path("sentry_daemon.log")
    
    // Package name
    const val PACKAGE_NAME = "com.overdrive.app"
    
    // BYD permissions
    val BYD_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_BODYWORK_GET",
        "android.permission.BYDAUTO_BODYWORK_SET",
        "android.permission.ACCESS_APPOPSDATA"
    )
    
    // System apps that might have ACCESS_APPOPSDATA
    val SYSTEM_APPS = listOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.byd.launcher",
        "com.byd.settings",
        "com.byd.systemui"
    )
    
    /**
     * Get log file path based on UID.
     */
    fun getLogFilePath(uid: Int): String {
        return if (uid == 1000) LOG_FILE_SYSTEM else LOG_FILE_SHELL
    }
    
    /**
     * Convert power level to string.
     */
    fun powerLevelToString(level: Int): String {
        return when (level) {
            POWER_LEVEL_OFF -> "OFF"
            POWER_LEVEL_ACC -> "ACC"
            POWER_LEVEL_ON -> "ON"
            POWER_LEVEL_OK -> "OK"
            else -> "UNKNOWN($level)"
        }
    }
}
