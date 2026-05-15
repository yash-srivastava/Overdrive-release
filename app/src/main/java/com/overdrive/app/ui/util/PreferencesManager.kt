package com.overdrive.app.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.overdrive.app.ui.model.AccessMode
import com.overdrive.app.ui.model.DaemonType

/**
 * Manages SharedPreferences for app settings persistence.
 * Uses device-encrypted storage to support Direct Boot (before user unlock).
 */
object PreferencesManager {
    
    private const val TAG = "PreferencesManager"
    private const val PREFS_NAME = "overdrive_prefs"
    private const val KEY_ACCESS_MODE = "access_mode"
    private const val KEY_ENABLED_DAEMONS = "enabled_daemons"
    private const val KEY_SELECTED_CAMERAS = "selected_cameras"
    private const val KEY_LAST_TUNNEL_URL = "last_tunnel_url"
    private const val KEY_LAST_ZROK_URL = "last_zrok_url"
    private const val KEY_ZROK_UNIQUE_NAME = "zrok_unique_name"
    private const val KEY_ZROK_ENABLE_TOKEN = "zrok_enable_token"
    private const val KEY_LOGS_EXPANDED = "logs_expanded"
    private const val KEY_CLOUDFLARE_TOKEN = "cloudflare_token"
    private const val KEY_CLOUDFLARE_CUSTOM_URL = "cloudflare_custom_url"
    private const val KEY_CLOUDFLARE_PAID = "cloudflare_paid"
    
    private var prefs: SharedPreferences? = null
    
    /**
     * Initialize with application context. Call once in Application.onCreate().
     * Uses device-encrypted storage to support Direct Boot scenarios.
     * Migrates existing prefs from credential-encrypted storage on first run.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        
        // Use device-encrypted storage for Direct Boot support
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceContext = appContext.createDeviceProtectedStorageContext()
            
            // Migrate from credential-encrypted to device-encrypted storage (one-time)
            // This preserves existing preferences when upgrading
            if (isUserUnlocked(appContext)) {
                try {
                    deviceContext.moveSharedPreferencesFrom(appContext, PREFS_NAME)
                    Log.d(TAG, "Migrated prefs from credential to device storage")
                } catch (e: Exception) {
                    // Already migrated or nothing to migrate
                    Log.d(TAG, "Prefs migration skipped: ${e.message}")
                }
            }
            deviceContext
        } else {
            appContext
        }
        
        prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "Initialized with device-encrypted storage. Current access mode: ${getAccessMode()}")
    }
    
    /**
     * Check if the user is unlocked (credential-encrypted storage available).
     */
    fun isUserUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked ?: true
        } else {
            true
        }
    }
    
    /**
     * Check if PreferencesManager has been initialized.
     */
    fun isInitialized(): Boolean = prefs != null
    
    private fun requirePrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("PreferencesManager not initialized. Call init() first.")
    }

    // Token Cloudflare
    @JvmStatic
    fun getCloudflareToken(): String {
        return com.overdrive.app.ui.util.PreferencesManager.requirePrefs()
            .getString(com.overdrive.app.ui.util.PreferencesManager.KEY_CLOUDFLARE_TOKEN, "") ?: ""
    }

    fun setCloudflareToken(token: String) {
        com.overdrive.app.ui.util.PreferencesManager.requirePrefs()
            .edit().putString(com.overdrive.app.ui.util.PreferencesManager.KEY_CLOUDFLARE_TOKEN, token).apply()
    }


    // Cloudflare Paid Version
    @JvmStatic
    fun isCloudflarePaid(): Boolean {
        return com.overdrive.app.ui.util.PreferencesManager.requirePrefs()
            .getBoolean(com.overdrive.app.ui.util.PreferencesManager.KEY_CLOUDFLARE_PAID, false)
    }

    fun setCloudflarePaid(paid: Boolean) {
        com.overdrive.app.ui.util.PreferencesManager.requirePrefs()
            .edit().putBoolean(com.overdrive.app.ui.util.PreferencesManager.KEY_CLOUDFLARE_PAID, paid).apply()
    }

    fun isCloudflareConfigured(): Boolean {
        return !isCloudflarePaid() || getCloudflareToken().isNotEmpty()
    }
    
    // Access Mode
    fun getAccessMode(): AccessMode {
        val value = requirePrefs().getString(KEY_ACCESS_MODE, AccessMode.PRIVATE.name)
        val mode = try {
            AccessMode.valueOf(value ?: AccessMode.PRIVATE.name)
        } catch (e: Exception) {
            AccessMode.PRIVATE
        }
        Log.d(TAG, "getAccessMode() -> $mode (raw: $value)")
        return mode
    }
    
    fun setAccessMode(mode: AccessMode) {
        Log.d(TAG, "setAccessMode($mode)")
        // Use commit() instead of apply() to ensure synchronous write
        val success = requirePrefs().edit().putString(KEY_ACCESS_MODE, mode.name).commit()
        Log.d(TAG, "setAccessMode commit result: $success")
    }
    
    // Enabled Daemons
    fun getEnabledDaemons(): Set<DaemonType> {
        val names = requirePrefs().getStringSet(KEY_ENABLED_DAEMONS, emptySet()) ?: emptySet()
        return names.mapNotNull { name ->
            try { DaemonType.valueOf(name) } catch (e: Exception) { null }
        }.toSet()
    }
    
    fun setDaemonEnabled(type: DaemonType, enabled: Boolean) {
        val current = getEnabledDaemons().map { it.name }.toMutableSet()
        if (enabled) {
            current.add(type.name)
        } else {
            current.remove(type.name)
        }
        requirePrefs().edit().putStringSet(KEY_ENABLED_DAEMONS, current).commit()
    }
    
    fun isDaemonEnabled(type: DaemonType): Boolean {
        return type in getEnabledDaemons()
    }
    
    // Selected Cameras
    fun getSelectedCameras(): Set<Int> {
        val strings = requirePrefs().getStringSet(KEY_SELECTED_CAMERAS, setOf("1")) ?: setOf("1")
        return strings.mapNotNull { it.toIntOrNull() }.toSet()
    }
    
    fun setSelectedCameras(cameras: Set<Int>) {
        val strings = cameras.map { it.toString() }.toSet()
        requirePrefs().edit().putStringSet(KEY_SELECTED_CAMERAS, strings).apply()
    }
    
    fun toggleCamera(cameraId: Int): Set<Int> {
        val current = getSelectedCameras().toMutableSet()
        if (cameraId in current) {
            current.remove(cameraId)
        } else {
            current.add(cameraId)
        }
        setSelectedCameras(current)
        return current
    }
    
    // Tunnel URL
    fun getLastTunnelUrl(): String? {
        return requirePrefs().getString(KEY_LAST_TUNNEL_URL, null)
    }
    
    fun setLastTunnelUrl(url: String?) {
        requirePrefs().edit().putString(KEY_LAST_TUNNEL_URL, url).apply()
    }
    
    // Zrok URL
    fun getLastZrokUrl(): String? {
        return requirePrefs().getString(KEY_LAST_ZROK_URL, null)
    }
    
    fun setLastZrokUrl(url: String?) {
        requirePrefs().edit().putString(KEY_LAST_ZROK_URL, url).apply()
    }
    
    // Zrok Unique Name - CRITICAL for preventing "Not Found" errors
    // The unique name MUST be persisted alongside the token to prevent split-brain
    fun getZrokUniqueName(): String? {
        return requirePrefs().getString(KEY_ZROK_UNIQUE_NAME, null)
    }
    
    fun setZrokUniqueName(name: String) {
        requirePrefs().edit().putString(KEY_ZROK_UNIQUE_NAME, name).commit()
    }
    
    // Zrok Enable Token - stored in preferences for app-side access
    // Also synced to /data/local/tmp/.zrok/enable_token for cross-UID access
    @Deprecated("Use unified storage via ZrokController instead")
    fun getZrokEnableToken(): String? {
        return requirePrefs().getString(KEY_ZROK_ENABLE_TOKEN, null)
    }
    
    @Deprecated("Use unified storage via ZrokController instead")
    fun setZrokEnableToken(token: String?) {
        if (token.isNullOrBlank()) {
            requirePrefs().edit().remove(KEY_ZROK_ENABLE_TOKEN).commit()
        } else {
            requirePrefs().edit().putString(KEY_ZROK_ENABLE_TOKEN, token.trim()).commit()
        }
    }
    
    @Deprecated("Use unified storage via ZrokController instead")
    fun hasZrokEnableToken(): Boolean {
        return !getZrokEnableToken().isNullOrBlank()
    }
    
    @Deprecated("Use unified storage via ZrokController instead")
    fun clearZrokEnableToken() {
        requirePrefs().edit().remove(KEY_ZROK_ENABLE_TOKEN).commit()
    }
    
    // Logs Panel State
    fun isLogsExpanded(): Boolean {
        return requirePrefs().getBoolean(KEY_LOGS_EXPANDED, false)
    }
    
    fun setLogsExpanded(expanded: Boolean) {
        requirePrefs().edit().putBoolean(KEY_LOGS_EXPANDED, expanded).apply()
    }

    /**
     * Get the current access URL based on mode.
     */
    fun getCurrentUrl(): String? {
        return when (getAccessMode()) {
            AccessMode.PRIVATE -> getLastTunnelUrl()  // Private = cloudflared tunnel
            AccessMode.PUBLIC -> getLastTunnelUrl()   // Public = also uses tunnel now
        }
    }
}
