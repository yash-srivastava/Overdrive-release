package com.overdrive.app.ui.daemon

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.ZrokLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager
import android.content.Context

/**
 * Controller for the Zrok Tunnel.
 * 
 * Supports two modes:
 * 1. RESERVED MODE (Recommended): Permanent URL that never changes
 *    - Set reservedShareToken and uniqueName before starting
 *    - URL: https://<uniqueName>.share.zrok.io
 * 
 * 2. PUBLIC MODE (Fallback): Random URL each time
 *    - Used when no reserved token is set
 * 
 * IMPORTANT: The unique name is persisted alongside the token to prevent
 * "Not Found" errors caused by name/token mismatch (split-brain).
 */
class ZrokController(
    private val context: Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.ZROK_TUNNEL
    
    private val _tunnelUrl = MutableLiveData<String?>()
    val tunnelUrl: LiveData<String?> = _tunnelUrl
    
    // Lazy init zrok launcher
    private val zrokLauncher by lazy {
        ZrokLauncher(
            context,
            com.overdrive.app.launcher.AdbShellExecutor(context),
            com.overdrive.app.logging.LogManager.getInstance()
        )
    }
    
    /**
     * FIX 1: Ensure we restore the unique name from prefs when setting the token.
     * If we don't do this, ZrokLauncher might generate a new random name on restart,
     * breaking the URL match.
     */
    fun setReservedToken(token: String, customUniqueName: String? = null) {
        ZrokLauncher.reservedShareToken = token
        
        // Priority:
        // 1. Custom name passed in
        // 2. Saved name from Prefs
        // 3. Keep existing/generated name
        if (customUniqueName != null) {
            ZrokLauncher.uniqueName = customUniqueName
            PreferencesManager.setZrokUniqueName(customUniqueName) // SAVE IT
        } else {
            val savedName = PreferencesManager.getZrokUniqueName()
            if (!savedName.isNullOrEmpty()) {
                ZrokLauncher.uniqueName = savedName // RESTORE IT
            }
        }
    }
    
    /**
     * Get the unique name for this device.
     */
    fun getUniqueName(): String {
        return ZrokLauncher.uniqueName
    }
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Checking token...")
        
        // First ensure token is loaded
        ensureTokenLoaded { hasToken ->
            if (!hasToken) {
                callback.onError("❌ No Zrok token configured. Tap to configure.")
                return@ensureTokenLoaded
            }
            
            callback.onStatusChanged(DaemonStatus.STARTING, "Initializing...")
            
            // FIX 2: Aggressive cleanup BEFORE start
            // This prevents "share already reserved" errors if a zombie process exists
            adbLauncher.executeShellCommand("pkill -9 -f 'zrok'; sleep 1", object : AdbDaemonLauncher.LaunchCallback {
                override fun onLaunched() {
                    startInternal(callback)
                }
                override fun onLog(m: String) {}
                override fun onError(e: String) {
                    // Even if kill fails (no process), proceed
                    startInternal(callback)
                }
            })
        }
    }
    
    private fun startInternal(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting zrok tunnel...")
        
        // Restore name/token state
        val reservedToken = ZrokLauncher.reservedShareToken
        
        // Safety check: ensure name matches saved preference if token exists
        if (reservedToken != null) {
            val savedName = PreferencesManager.getZrokUniqueName()
            if (!savedName.isNullOrEmpty() && savedName != ZrokLauncher.uniqueName) {
                ZrokLauncher.uniqueName = savedName
            }
        }
        
        if (reservedToken != null) {
            callback.onStatusChanged(DaemonStatus.STARTING, "Target: ${ZrokLauncher.uniqueName}")
            
            zrokLauncher.launchZrokReserved(reservedToken, object : ZrokLauncher.ZrokCallback {
                override fun onLog(message: String) {
                    // Filter out noise, look for errors
                    if (message.contains("error", true) || message.contains("panic", true)) {
                        callback.onStatusChanged(DaemonStatus.STARTING, "Error: $message")
                    } else {
                        callback.onStatusChanged(DaemonStatus.STARTING, message)
                    }
                }
                
                override fun onTunnelUrl(url: String) {
                    _tunnelUrl.postValue(url)
                    PreferencesManager.setLastZrokUrl(url)
                    callback.onStatusChanged(DaemonStatus.RUNNING, url)
                }
                
                override fun onError(error: String) {
                    callback.onError(error)
                }
            })
        } else {
            // Public mode fallback
            zrokLauncher.launchZrok(object : ZrokLauncher.ZrokCallback {
                override fun onLog(message: String) = callback.onStatusChanged(DaemonStatus.STARTING, message)
                
                override fun onTunnelUrl(url: String) {
                    _tunnelUrl.postValue(url)
                    callback.onStatusChanged(DaemonStatus.RUNNING, url)
                }
                
                override fun onError(error: String) = callback.onError(error)
            })
        }
    }
    
    /**
     * Reserve a permanent URL (ONE-TIME setup).
     * After this, the token is saved and will be used automatically.
     * 
     * @param customName Optional custom name. If null, uses auto-generated unique name (overdrive<random>)
     */
    fun reservePermanentUrl(customName: String? = null, callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Reserving permanent URL...")
        
        zrokLauncher.reservePermanentUrl(customName, object : ZrokLauncher.ZrokCallback {
            override fun onLog(message: String) = callback.onStatusChanged(DaemonStatus.STARTING, message)
            
            override fun onTunnelUrl(url: String) {
                _tunnelUrl.postValue(url)
                PreferencesManager.setLastZrokUrl(url)
                
                // FIX 3: Save the unique name immediately after reservation
                // This ensures the next restart uses the same name
                PreferencesManager.setZrokUniqueName(ZrokLauncher.uniqueName)
                
                callback.onStatusChanged(DaemonStatus.RUNNING, "Reserved: $url")
            }
            
            override fun onError(error: String) = callback.onError(error)
        })
    }
    
    /**
     * Load any saved reserved token from device.
     */
    fun loadSavedReservedToken(callback: (String?) -> Unit) {
        zrokLauncher.loadReservedToken(callback)
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping zrok tunnel...")
        
        zrokLauncher.stopTunnel(object : ZrokLauncher.ZrokCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STOPPING, message)
            }
            
            override fun onTunnelUrl(url: String) {
                _tunnelUrl.postValue(null)
                callback.onStatusChanged(DaemonStatus.STOPPED, "Tunnel stopped")
            }
            
            override fun onError(error: String) {
                _tunnelUrl.postValue(null)
                callback.onError(error)
            }
        })
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        zrokLauncher.isTunnelRunning(callback)
    }
    
    /**
     * Refresh the tunnel URL from log file (useful when daemon is already running).
     * Also tries to get the last saved URL from preferences if log doesn't have it.
     */
    fun refreshTunnelUrl(callback: ((String?) -> Unit)? = null) {
        zrokLauncher.getTunnelUrl { url ->
            if (url != null) {
                _tunnelUrl.postValue(url)
                PreferencesManager.setLastZrokUrl(url)
                callback?.invoke(url)
            } else {
                // Try to get last saved URL from preferences
                val lastUrl = PreferencesManager.getLastZrokUrl()
                if (!lastUrl.isNullOrEmpty()) {
                    _tunnelUrl.postValue(lastUrl)
                    callback?.invoke(lastUrl)
                } else {
                    callback?.invoke(null)
                }
            }
        }
    }
    
    override fun cleanup() {
        // Use pkill -f for more reliable process killing (matches full command line)
        adbLauncher.executeShellCommand(
            "pkill -9 -f 'zrok'; killall -9 zrok 2>/dev/null; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        _tunnelUrl.postValue(null)
    }
    
    /**
     * Get the current tunnel URL if available.
     */
    fun getTunnelUrl(): String? = _tunnelUrl.value
    
    // ==================== Enable Token Management ====================
    // Single source of truth: /data/local/tmp/.zrok/enable_token
    // Accessed via ADB shell for cross-UID compatibility (app UID + UID 2000)
    
    /**
     * Check if enable token is configured.
     */
    fun hasEnableToken(callback: (Boolean) -> Unit) {
        zrokLauncher.hasEnableToken(callback)
    }
    
    /**
     * Get the current enable token from unified storage.
     */
    fun getEnableToken(callback: (String?) -> Unit) {
        zrokLauncher.loadEnableToken(callback)
    }

    /**
     * Get the current enable token from unified storage.
     */
    fun getZrokEndpoint(callback: (String?) -> Unit) {
        zrokLauncher.loadZrokEndpoint(callback)
    }
    
    /**
     * Save enable token to unified storage (/data/local/tmp/.zrok/enable_token).
     * Single source of truth - no sync needed.
     */
    fun saveEnableToken(token: String, callback: ((Boolean) -> Unit)? = null) {
        zrokLauncher.saveEnableToken(token, callback)
    }

    /**
     * Save zrok endpoint to unified storage (/data/local/tmp/.zrok/endpoint).
     * Single source of truth - no sync needed.
     */
    fun saveZrokEndpoint(endpoint: String?, callback: ((Boolean) -> Unit)? = null) {
        zrokLauncher.saveZrokEndpoint(endpoint, callback)
    }
    
    /**
     * Delete enable token from unified storage.
     */
    fun deleteZrokSettings(callback: ((Boolean) -> Unit)? = null) {
        zrokLauncher.deleteZrokSettings(callback)
    }
    
    /**
     * Ensure token is loaded before starting tunnel.
     */
    fun ensureTokenLoaded(callback: (Boolean) -> Unit) {
        zrokLauncher.ensureSettingsLoaded(callback)
    }
    
    /**
     * Disable zrok environment (full cleanup including token).
     */
    fun disableEnvironment(callback: DaemonCallback? = null) {
        zrokLauncher.disableEnvironment(object : ZrokLauncher.ZrokCallback {
            override fun onLog(message: String) {
                callback?.onStatusChanged(DaemonStatus.STOPPING, message)
            }
            
            override fun onTunnelUrl(url: String) {
                _tunnelUrl.postValue(null)
                callback?.onStatusChanged(DaemonStatus.STOPPED, "Environment disabled")
            }
            
            override fun onError(error: String) {
                callback?.onError(error)
            }
        })
    }
}
