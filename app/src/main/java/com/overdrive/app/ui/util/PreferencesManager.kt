package com.overdrive.app.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.overdrive.app.ui.model.DaemonType

/**
 * Manages SharedPreferences for app settings persistence.
 * Uses device-encrypted storage to support Direct Boot (before user unlock).
 */
object PreferencesManager {
    
    private const val TAG = "PreferencesManager"
    private const val PREFS_NAME = "overdrive_prefs"
    private const val KEY_ENABLED_DAEMONS = "enabled_daemons"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_SELECTED_CAMERAS = "selected_cameras"
    private const val KEY_LAST_TUNNEL_URL = "last_tunnel_url"
    private const val KEY_LAST_ZROK_URL = "last_zrok_url"
    private const val KEY_ZROK_UNIQUE_NAME = "zrok_unique_name"
    private const val KEY_ZROK_ENABLE_TOKEN = "zrok_enable_token"
    private const val KEY_LOGS_EXPANDED = "logs_expanded"
    private const val KEY_DI5_ADB_RECOVERY_SETUP = "dilink5_adb_recovery_setup"
    private const val KEY_NAVIGATION_VISIBLE_V1 = "navigation_visible_v1"
    /** Rail keys the user has already been offered, so a NEW one can default to
     *  visible without un-hiding something they deliberately switched off. */
    private const val KEY_NAVIGATION_SEEN_V1 = "navigation_seen_v1"
    /** The rail's customisable keys when offers started being recorded (v40.2). */
    private val NAVIGATION_KEYS_BEFORE_OFFERS = setOf(
        "live", "recordings", "vehicle", "projection", "trips", "charging", "automations",
        "key_mapping", "integrations", "road_sense", "map", "network", "diagnostics",
    )

    private var prefs: SharedPreferences? = null
    // Held so theme-mode changes can poke the floating overlay service —
    // a Service doesn't see AppCompatDelegate.setDefaultNightMode() the way
    // an Activity does, so we ask it to rebuild itself explicitly.
    private var appContextRef: Context? = null

    /**
     * Initialize with application context. Call once in Application.onCreate().
     * Uses device-encrypted storage to support Direct Boot scenarios.
     * Migrates existing prefs from credential-encrypted storage on first run.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        
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
        Log.d(TAG, "Initialized with device-encrypted storage")
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
    
    /**
     * Theme mode (Auto/Light/Dark) — persisted, read at app start.
     * Stored as the AppCompatDelegate.MODE_NIGHT_* int so the value can be
     * passed straight to setDefaultNightMode().
     * Default: MODE_NIGHT_FOLLOW_SYSTEM (auto, follows the head-unit theme).
     */
    fun getThemeMode(): Int {
        return requirePrefs().getInt(
            KEY_THEME_MODE,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }

    fun setThemeMode(mode: Int) {
        requirePrefs().edit().putInt(KEY_THEME_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        // Nudge the floating overlay so the recording/trip pill repaints
        // against the newly-chosen palette. AppCompat only refires
        // onConfigurationChanged for foreground Activities; a plain Service
        // wouldn't notice the flip otherwise.
        appContextRef?.let { ctx ->
            try {
                com.overdrive.app.overlay.StatusOverlayService.refreshTheme(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "overlay theme refresh failed: ${e.message}")
            }
        }
        com.overdrive.app.overlay.GlassWidgetsOverlayService.refreshThemeIfRunning()
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
    
    /** Opt-in permission setup for the existing DiLink 5 ADB recovery path. */
    fun isDiLink5AdbRecoverySetupEnabled(): Boolean =
        requirePrefs().getBoolean(KEY_DI5_ADB_RECOVERY_SETUP, false)

    fun setDiLink5AdbRecoverySetupEnabled(enabled: Boolean): Boolean =
        requirePrefs().edit().putBoolean(KEY_DI5_ADB_RECOVERY_SETUP, enabled).commit()

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
     * Missing preference means the complete current catalog, preserving the
     * navigation users had before customization existed. Once customized, new
     * optional destinations remain available in Settings but are not pinned
     * automatically.
     */
    fun getVisibleNavigationKeys(knownKeys: Set<String>): Set<String> {
        val p = requirePrefs()
        val stored = if (p.contains(KEY_NAVIGATION_VISIBLE_V1)) {
            p.getStringSet(KEY_NAVIGATION_VISIBLE_V1, emptySet())?.toSet()
        } else {
            null
        }
        // A key the user has never been offered is NEW: show it rather than leaving
        // a shipped feature invisible on every existing install. Record the offer so
        // this only ever happens once per key. A rail customised before offers were
        // recorded had been offered every key of that release.
        val recorded = p.getStringSet(KEY_NAVIGATION_SEEN_V1, null)?.toSet()
        val seen = recorded ?: if (stored != null) NAVIGATION_KEYS_BEFORE_OFFERS else emptySet()
        val resolved = NavigationVisibilityPolicy.resolveWithNewDefaults(stored, knownKeys, seen)
        if (recorded == null || !seen.containsAll(knownKeys)) {
            val edit = p.edit().putStringSet(KEY_NAVIGATION_SEEN_V1, seen + knownKeys)
            // a new key stays pinned until the user unpins it, not just until the next refresh
            if (stored != null) edit.putStringSet(KEY_NAVIGATION_VISIBLE_V1, resolved)
            edit.apply()
        }
        return resolved
    }

    fun setNavigationItemVisible(key: String, visible: Boolean, knownKeys: Set<String>) {
        val updated = NavigationVisibilityPolicy.setVisible(
            getVisibleNavigationKeys(knownKeys),
            key,
            visible,
            knownKeys,
        )
        requirePrefs().edit()
            .putStringSet(KEY_NAVIGATION_VISIBLE_V1, updated)
            .apply()
    }

    fun resetNavigationVisibility() {
        requirePrefs().edit()
            .remove(KEY_NAVIGATION_VISIBLE_V1)
            .remove(KEY_NAVIGATION_SEEN_V1)
            .apply()
    }

    /** Current access URL — always the last tunnel URL we saw. */
    fun getCurrentUrl(): String? = getLastTunnelUrl()
}
