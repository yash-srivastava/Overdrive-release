package com.overdrive.app.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.overdrive.app.launcher.AdbShellExecutor
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Generates and persists device IDs for daemon identification.
 * 
 * Works in both app context (uses SharedPreferences) and daemon context (uses file).
 * Uses ADB shell to write to /data/local/tmp/ (app doesn't have direct write permission).
 */
object DeviceIdGenerator {
    
    private const val TAG = "DeviceIdGenerator"
    private const val ID_FILE_NAME = ".overdrive_device_id"
    private const val ID_PREFIX = "byd-"
    private const val PREFS_NAME = "device_id_prefs"
    private const val PREFS_KEY = "device_id"
    
    @Volatile
    private var cachedId: String? = null
    
    // ADB shell executor for writing to /data/local/tmp/
    private var adbShellExecutor: AdbShellExecutor? = null
    
    /**
     * Initialize with ADB shell executor for file sync.
     * Call this early in app startup (e.g., in Application.onCreate or MainActivity).
     */
    fun init(executor: AdbShellExecutor) {
        adbShellExecutor = executor
    }
    
    /**
     * Generate or retrieve a persistent device ID.
     * Uses in-memory cache for performance.
     */
    fun generateDeviceId(): String {
        // Return cached ID if available
        cachedId?.let { return it }
        
        // Try to load from file (daemon context)
        loadFromFile()?.let { 
            cachedId = it
            return it 
        }
        
        // Try serial number (works without context)
        try {
            @Suppress("DEPRECATION")
            val serial = Build.SERIAL
            if (!serial.isNullOrEmpty() && serial != "unknown") {
                val id = "$ID_PREFIX${Integer.toHexString(serial.hashCode()).take(8)}"
                saveToFileViaAdb(id)
                cachedId = id
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get serial: ${e.message}")
        }
        
        // Try Build.FINGERPRINT hash
        try {
            val fingerprint = Build.FINGERPRINT
            if (!fingerprint.isNullOrEmpty()) {
                val id = "$ID_PREFIX${Integer.toHexString(fingerprint.hashCode()).take(8)}"
                saveToFileViaAdb(id)
                cachedId = id
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get fingerprint: ${e.message}")
        }
        
        // Generate random ID
        val id = "$ID_PREFIX${java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(8)}"
        saveToFileViaAdb(id)
        cachedId = id
        return id
    }
    
    /**
     * Generate device ID with context (can use SharedPreferences and Android ID).
     * 
     * Priority: SharedPrefs -> File -> Generate new
     * Always syncs SharedPrefs to file for daemon compatibility.
     */
    fun generateDeviceId(context: Context): String {
        // Return cached ID if available
        cachedId?.let { return it }
        
        // Try SharedPreferences first (authoritative source when app has context)
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREFS_KEY, null)?.let { prefsId ->
                cachedId = prefsId
                // ALWAYS sync to file for daemon compatibility
                saveToFileViaAdb(prefsId)
                return prefsId
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read from SharedPreferences: ${e.message}")
        }
        
        // Try to load from file (for daemon compatibility)
        loadFromFile()?.let { fileId ->
            cachedId = fileId
            saveToPrefs(context, fileId)
            return fileId
        }
        
        // Try Android ID
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrEmpty()) {
                val id = "$ID_PREFIX${Integer.toHexString(androidId.hashCode()).take(8)}"
                saveToPrefs(context, id)
                saveToFileViaAdb(id)
                cachedId = id
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get Android ID: ${e.message}")
        }
        
        // Try serial number
        try {
            @Suppress("DEPRECATION")
            val serial = Build.SERIAL
            if (!serial.isNullOrEmpty() && serial != "unknown") {
                val id = "$ID_PREFIX${Integer.toHexString(serial.hashCode()).take(8)}"
                saveToPrefs(context, id)
                saveToFileViaAdb(id)
                cachedId = id
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get serial: ${e.message}")
        }
        
        // Generate random ID
        val id = "$ID_PREFIX${java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(8)}"
        saveToPrefs(context, id)
        saveToFileViaAdb(id)
        cachedId = id
        return id
    }
    
    private fun idFilePath(): String = ScratchPaths.path(ID_FILE_NAME)

    private fun loadFromFile(): String? {
        return try {
            val file = ScratchPaths.firstExistingFile(ID_FILE_NAME)
            if (file.isFile) {
                BufferedReader(FileReader(file)).use { reader ->
                    reader.readLine()?.takeIf { it.isNotEmpty() && it.startsWith(ID_PREFIX) }
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Save device ID to file via ADB shell (has write permission to /data/local/tmp/).
     */
    private fun saveToFileViaAdb(id: String) {
        val executor = adbShellExecutor
        if (executor == null) {
            Log.w(TAG, "ADB shell executor not initialized, cannot save device ID to file")
            return
        }
        
        executor.execute(
            command = "echo '$id' > ${idFilePath()}",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    Log.d(TAG, "Device ID saved to file via ADB: $id")
                }
                override fun onError(error: String) {
                    Log.w(TAG, "Failed to save device ID via ADB: $error")
                }
            }
        )
    }
    
    /**
     * Save device ID to file SYNCHRONOUSLY via ADB shell.
     * Use this when you need to ensure the file is written before proceeding.
     * 
     * @return true if write succeeded, false otherwise
     */
    fun syncDeviceIdToFileSync(context: Context): Boolean {
        val executor = adbShellExecutor
        if (executor == null) {
            Log.w(TAG, "ADB shell executor not initialized")
            return false
        }
        
        val id = generateDeviceId(context)
        return try {
            val result = executor.executeSync("echo '$id' > ${idFilePath()}")
            if (result.exitCode == 0) {
                Log.i(TAG, "Device ID synced to file (sync): $id")
                true
            } else {
                Log.w(TAG, "Failed to sync device ID: ${result.output}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing device ID: ${e.message}")
            false
        }
    }
    
    private fun saveToPrefs(context: Context, id: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREFS_KEY, id).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not save to SharedPreferences: ${e.message}")
        }
    }
}
