package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Launches Zrok tunnel processes via ADB shell for remote access.
 * 
 * MODES:
 * 1. RESERVED MODE (Recommended): Uses a pre-reserved token for permanent URL
 *    - URL never changes: https://<unique-name>.share.zrok.io
 *    - Requires one-time setup: `zrok reserve public http://localhost:8080 --unique-name <name>`
 *    - Then use: `zrok share reserved <token>`
 * 
 * 2. PUBLIC MODE (Fallback): Creates random URL each time
 *    - URL changes on every restart
 *    - Uses: `zrok share public http://localhost:8080`
 * 
 * IMPORTANT: Zrok has a 5-device limit on the free tier!
 * - `zrok enable <token>` = Register device (LIMITED to 5 times total!)
 * - `zrok share` = Start tunnel (UNLIMITED restarts)
 * - `zrok reserve` = Reserve a permanent URL (counts as 1 share slot)
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class ZrokLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "ZrokLauncher"

        // Zrok paths
        private const val ZROK_HOME = "/data/local/tmp"
        private const val ZROK_TMP_PATH = "$ZROK_HOME/zrok"
        private const val ZROK2_TMP_PATH = "$ZROK_HOME/zrok2"
        private const val ZROK_LOG = "$ZROK_HOME/zrok.log"

        // Identity file - THIS IS THE KEY FILE that proves device is enabled
        private const val ZROK_IDENTITY_FILE = "$ZROK_HOME/.zrok/environment.json"
        private const val ZROK2_IDENTITY_FILE = "$ZROK_HOME/.zrok2/environment.json"
        
        // Reserved token file - stores the reserved share token
        private const val ZROK_RESERVED_TOKEN_FILE = "$ZROK_HOME/.zrok/reserved_token"
        
        // Enable token file - stores the enable token for cross-UID access
        private const val ZROK_ENABLE_TOKEN_FILE = "$ZROK_HOME/.zrok/enable_token"
        // Endpoint file - stores the zrok endpoint for a self hosted instance
        private const val ZROK_ENDPOINT_FILE = "$ZROK_HOME/.zrok/endpoint"
        
        // Unique name file - stores the generated unique name
        private const val ZROK_UNIQUE_NAME_FILE = "$ZROK_HOME/.zrok/unique_name"
        
        // Default enable token - can be overridden via setEnableToken()
        // This is loaded from unified storage at runtime
        var zrokToken: String = ""
        
        // Flag to track if token has been loaded from storage
        private var tokenLoaded = false

        // Default endpoint - empty means use zrok.io -  can be overridden via setZrokEndpoint()
        // This is loaded from unified storage at runtime
        var zrokEndpoint: String = ""

        // Flag to track if endpoint has been loaded from storage
        private var endpointLoaded = false
        
        // Reserved share token (obtained from `zrok reserve` command)
        // Set this after running reserve command once
        var reservedShareToken: String? = null
        
        // Unique name for reserved URL (e.g., "overdrive1a2b3c" -> https://overdrive1a2b3c.share.zrok.io)
        // Generated automatically - must be lowercase alphanumeric only, 4-32 chars
        var uniqueName: String = "overdrive"
        
        // Prefix for unique name generation (no hyphens allowed!)
        private const val UNIQUE_NAME_PREFIX = "overdrive"
        
        // Proxy settings for sing-box (socks5 for zrok)
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = "8119"
        
        /**
         * Generate a unique name for this device.
         * Format: overdrive<6-char-random>
         * Must be lowercase alphanumeric only, 4-32 chars (zrok requirement).
         * Returns a NEW random value each time called.
         */
        fun generateUniqueName(context: Context): String {
            // Generate random 6-char alphanumeric string (lowercase only, no hyphens)
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val random = java.util.Random()
            val suffix = (1..6)
                .map { chars[random.nextInt(chars.length)] }
                .joinToString("")
            
            return "$UNIQUE_NAME_PREFIX$suffix"
        }

        private val isSelfHosted: Boolean
            get() = zrokEndpoint.isNotEmpty()

        private val zrokCommand: String
            get() {
                return buildString {
                    append("HOME=$ZROK_HOME ")

                    if (isSelfHosted) {
                        append("ZROK2_API_ENDPOINT=$zrokEndpoint $ZROK2_TMP_PATH")
                    } else {
                        append(ZROK_TMP_PATH)
                    }
                }
            }

        private val zrokPath: String
            get() = if (isSelfHosted) ZROK2_TMP_PATH else ZROK_TMP_PATH

        private val zrokIdentityFile: String
            get() = if (isSelfHosted) ZROK2_IDENTITY_FILE else ZROK_IDENTITY_FILE
    }
    
    interface ZrokCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }
    
    /**
     * Launch Zrok tunnel via ADB shell.
     * 
     * Priority:
     * 1. If reservedShareToken is set, use reserved mode (permanent URL)
     * 2. Otherwise, use public mode (random URL)
     * 
     * NOTE: Zrok and Cloudflared are mutually exclusive - this will kill cloudflared first.
     */
    fun launchZrok(callback: ZrokCallback) {
        logManager.info(TAG, "Launching Zrok tunnel...")
        callback.onLog("Loading token...")
        
        // First ensure token is loaded from unified storage
        ensureSettingsLoaded { hasToken ->
            if (!hasToken) {
                logManager.error(TAG, "No enable token configured!")
                callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
                return@ensureSettingsLoaded
            }
            
            callback.onLog("Checking for existing tunnel...")
            
            // Kill cloudflared first (mutually exclusive)
            killCloudflaredIfRunning {
                // Then check if zrok tunnel is already running
                isTunnelRunning { isRunning ->
                    if (isRunning) {
                        // Tunnel already running, try to get existing URL
                        logManager.info(TAG, "Zrok already running, checking for URL...")
                        callback.onLog("Tunnel already running, getting URL...")
                        getTunnelUrl { existingUrl ->
                            if (existingUrl != null) {
                                logManager.info(TAG, "Reusing existing tunnel: $existingUrl")
                                callback.onLog("Reusing existing tunnel")
                                callback.onTunnelUrl(existingUrl)
                            } else {
                                // Running but no URL - wait for it
                                logManager.info(TAG, "Tunnel running but no URL yet, waiting...")
                                callback.onLog("Waiting for tunnel URL...")
                                waitForTunnelUrl(callback, 1)
                            }
                        }
                    } else {
                        // Not running, check if binary is installed
                        callback.onLog("Setting up zrok...")
                        checkAndInstallZrok(callback) {
                            checkEnableAndLaunch(callback)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Launch Zrok in RESERVED mode with a specific token.
     * This gives you a permanent URL that never changes.
     * 
     * @param shareToken The reserved share token (from `zrok reserve` command)
     * @param permanentUrl The known permanent URL (e.g., https://byd-sentry-01.share.zrok.io)
     */
    fun launchZrokReserved(shareToken: String, callback: ZrokCallback) {
        reservedShareToken = shareToken
        logManager.info(TAG, "Launching Zrok in RESERVED mode...")
        callback.onLog("Starting reserved tunnel...")
        
        // Kill cloudflared first (mutually exclusive)
        killCloudflaredIfRunning {
            isTunnelRunning { isRunning ->
                if (isRunning) {
                    getTunnelUrl { url ->
                        if (url != null) {
                            logManager.info(TAG, "Zrok already running")
                            callback.onLog("Tunnel already running")
                            callback.onTunnelUrl(url)
                        } else {
                            logManager.error(TAG, "Failed to get url for running zrok share")
                            callback.onError("Failed to get url for running zrok share")
                        }
                    }
                } else {
                    callback.onLog("Setting up zrok...")
                    checkAndInstallZrok(callback) {
                        checkEnableAndLaunchReserved(shareToken, callback)
                    }
                }
            }
        }
    }
    
    /**
     * Reserve a permanent URL (ONE-TIME setup).
     * Run this once to get a reserved share token.
     * 
     * @param customName Optional custom name. If null, uses auto-generated unique name.
     * @return The reserved share token via callback
     */
    fun reservePermanentUrl(customName: String? = null, callback: ZrokCallback) {
        // Use custom name or load/generate unique name
        if (customName != null) {
            uniqueName = customName
            saveUniqueName(customName)
            doReservePermanentUrl(callback)
        } else {
            loadSavedUniqueName { savedName ->
                if (savedName != null) {
                    uniqueName = savedName
                } else {
                    uniqueName = generateUniqueName(context)
                    saveUniqueName(uniqueName)
                }
                doReservePermanentUrl(callback)
            }
        }
    }
    
    private fun doReservePermanentUrl(callback: ZrokCallback) {
        logManager.info(TAG, "Reserving permanent URL with name: $uniqueName")
        callback.onLog("Reserving permanent URL with name: $uniqueName")
        
        // First ensure device is enabled
        checkAndInstallZrok(object : ZrokCallback {
            override fun onLog(message: String) {
                callback.onLog(message)
            }
            
            override fun onTunnelUrl(url: String) {
                // After enable, run reserve command
                runReserveCommand(uniqueName, callback)
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        }) {
            checkEnableAndLaunch(callback)
        }
    }

    private fun isSingboxActive(callback: (Boolean) -> Unit) {
        // Check if sing-box proxy is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().isNotEmpty())
                }

                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    private fun runReserveCommand(uniqueName: String, callback: ZrokCallback) {
        isSingboxActive { active ->
            executeReserveCommand(uniqueName, active, callback)
        }
    }
    
    private fun executeReserveCommand(uniqueName: String, useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            if (useProxy) {
                val proxyUrl = "socks5h://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            append(zrokCommand)

            if (isSelfHosted) {
                append(" create name -n public $uniqueName")
            } else {
                // Note: `zrok reserve` doesn't support --headless
                append(" reserve public http://localhost:8080 --unique-name $uniqueName")
            }
            append(" 2>&1")
        }
        
        logManager.debug(TAG, "Executing reserve: $cmd")
        callback.onLog("Running reserve command...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserve output: $output")
                    
                    // Parse the reserved token from output
                    // Expected: [INFO] your reserved share token is 'abc-xyz-123'
                    val tokenPattern = Regex("token is '([^']+)'")
                    val match = tokenPattern.find(output)
                    
                    if (isSelfHosted || match != null) {
                        val token = if (isSelfHosted) uniqueName else match!!.groupValues[1]
                        logManager.info(TAG, "✅ Reserved token: $token")
                        callback.onLog("✅ Reserved! Token: $token")
                        callback.onLog("Reserved permanent URL with name: $uniqueName")
                        
                        // Save token to file for persistence
                        saveReservedToken(token)
                        reservedShareToken = token
                    } else if (output.contains("already reserved") || output.contains("exists")) {
                        callback.onLog("⚠️ Name already reserved. Use existing token.")
                        callback.onError("Name '$uniqueName' already reserved. Check saved token or use different name.")
                    } else {
                        callback.onError("Failed to reserve: $output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Reserve failed: $error")
                    callback.onError("Reserve failed: $error")
                }
            }
        )
    }
    
    private fun saveReservedToken(token: String) {
        adbShellExecutor.execute(
            command = "echo '$token' > $ZROK_RESERVED_TOKEN_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserved token saved to file")
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to save token: $error")
                }
            }
        )
    }
    
    /**
     * Load saved reserved token from file.
     */
    fun loadReservedToken(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_RESERVED_TOKEN_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val token = output.trim()
                    if (token.isNotEmpty() && !token.contains("No such file")) {
                        reservedShareToken = token
                        callback(token)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }

    private fun checkEnableAndLaunchReserved(shareToken: String, callback: ZrokCallback) {
        callback.onLog("Checking zrok identity...")

        isDeviceEnabled { enabled ->
            if (enabled) {
                logManager.info(TAG, "✅ Device already enabled")
                callback.onLog("✅ Device enabled")
                launchZrokShareReserved(shareToken, callback)
            } else {
                logManager.warn(TAG, "⚠️ Device not enabled. Enabling now...")
                callback.onLog("⚠️ Registering device...")
                enableZrokThenReserved(shareToken, callback)
            }
        }
    }
    
    private fun enableZrokThenReserved(shareToken: String, callback: ZrokCallback) {
        isSingboxActive { active ->
            enableZrokWithConfigThenReserved(shareToken, active, callback)
        }
    }
    
    private fun enableZrokWithConfigThenReserved(shareToken: String, useProxy: Boolean, callback: ZrokCallback) {
        // Check for token first
        if (zrokToken.isEmpty()) {
            logManager.error(TAG, "No enable token configured!")
            callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
            return
        }
        
        val cmd = buildString {
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl HTTP_PROXY=$proxyUrl HTTPS_PROXY=$proxyUrl NO_PROXY=localhost,127.0.0.1 ")
            }
            append("$zrokCommand enable $zrokToken --headless 2>&1")
        }
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Enable output: $output")
                    Thread.sleep(500)
                    launchZrokShareReserved(shareToken, callback)
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to enable zrok: $error")
                }
            }
        )
    }
    
    /**
     * Launch zrok share in RESERVED mode.
     * Uses: `zrok share reserved <token> --headless`
     * This is the recommended mode for permanent URLs.
     */
    private fun launchZrokShareReserved(shareToken: String, callback: ZrokCallback) {
        callback.onLog("Starting reserved share...")

        isSingboxActive { active ->
            startZrokShareReservedProcess(shareToken, active, callback)
        }
    }
    
    private fun startZrokShareReservedProcess(shareToken: String, useProxy: Boolean, callback: ZrokCallback) {
        // Clear old log
        cleanUpRunningShares {
            adbShellExecutor.execute(
                command = "rm -f $ZROK_LOG",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        launchReservedProcess(shareToken, useProxy, callback)
                    }
                    override fun onError(error: String) {
                        launchReservedProcess(shareToken, useProxy, callback)
                    }
                }
            )
        }
    }

    private fun deleteShare(name: String, onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "$zrokCommand delete share $name",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Deleted old share: $name")
                    onComplete()
                }

                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to delete share $error")
                    onComplete()
                }
            }
        )
    }

    /**
     * When killing a zrok2 instance, the share isn't released
     * Check if there is a running share for the $uniqueName and release
     */
    private fun cleanUpRunningShares(onComplete: () -> Unit) {
        if (isSelfHosted) {
            adbShellExecutor.execute(
                command = "$zrokCommand list names --json",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        try {
                            val data = JSONArray(output)

                            for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                if (item.getString("name") == uniqueName && item.has("shareToken")) {
                                    deleteShare(item.getString("shareToken"), onComplete)
                                    return
                                }
                            }

                            // No shares to delete
                            onComplete()
                        } catch (error: JSONException) {
                            logManager.error(TAG, "Failed to parse Zrok list names output: $error")
                            onComplete()
                        }
                    }

                    override fun onError(error: String) {
                        logManager.warn(TAG, "Failed to check if share is running $error")
                        onComplete()
                    }
                }
            )
        } else {
            onComplete()
        }
    }

    private fun launchReservedProcess(shareToken: String, useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            append("nohup sh -c '")
            
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            append(zrokCommand)

            if (isSelfHosted) {
                append(" share public http://localhost:8080 -n public:$shareToken --headless")
            } else {
                // RESERVED mode: uses token instead of public
                append(" share reserved $shareToken --headless")
            }
            append("' > $ZROK_LOG 2>&1 &")
        }
        
        logManager.debug(TAG, "Executing reserved share: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "✅ Waiting for reserved tunnel to start...")
                    waitForTunnelUrl(callback, 1)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch reserved share: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Kill cloudflared if running (mutual exclusion).
     */
    private fun killCloudflaredIfRunning(onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "pgrep -f cloudflared",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "Killing cloudflared (mutually exclusive with zrok)")
                        adbShellExecutor.execute(
                            command = "killall -9 cloudflared 2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) {
                                    Thread.sleep(300)
                                    onComplete()
                                }
                                override fun onError(e: String) { onComplete() }
                            }
                        )
                    } else {
                        onComplete()
                    }
                }
                override fun onError(error: String) { onComplete() }
            }
        )
    }
    
    private fun checkAndInstallZrok(callback: ZrokCallback, onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "test -x $zrokPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        onComplete()
                    } else {
                        installZrok(callback, onComplete)
                    }
                }
                
                override fun onError(error: String) {
                    installZrok(callback, onComplete)
                }
            }
        )
    }
    
    private fun installZrok(callback: ZrokCallback, onComplete: () -> Unit) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = if (isSelfHosted) "$nativeLibDir/libzrok2.so" else "$nativeLibDir/libzrok.so"

        callback.onLog("Installing zrok...")
        
        // Check if source exists
        adbShellExecutor.execute(
            command = "test -f $srcPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() != "yes") {
                        logManager.error(TAG, "$srcPath not found")
                        callback.onError("$srcPath not found. Add it to jniLibs/arm64-v8a/")
                        return
                    }

                    // Copy and make executable
                    adbShellExecutor.execute(
                        command = "cp $srcPath $zrokPath && chmod +x $zrokPath",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(output: String) {
                                callback.onLog("zrok installed")
                                onComplete()
                            }
                            
                            override fun onError(error: String) {
                                logManager.error(TAG, "Failed to install zrok: $error")
                                callback.onError("Failed to install zrok: $error")
                            }
                        }
                    )
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to check zrok source: $error")
                    callback.onError("Failed to check zrok source: $error")
                }
            }
        )
    }

    /**
     * CRITICAL: Check if device is already enabled before running `zrok enable`.
     * Also checks for reserved token to use reserved mode if available.
     * 
     * The free tier only allows 5 device registrations TOTAL.
     * We check for environment.json to avoid wasting registrations.
     */
    private fun checkEnableAndLaunch(callback: ZrokCallback) {
        callback.onLog("Checking zrok identity...")
        
        // First load saved unique name (if any)
        loadSavedUniqueName { savedName ->
            if (savedName != null) {
                uniqueName = savedName
                logManager.info(TAG, "Loaded saved unique name: $uniqueName")
            } else {
                // Generate new unique name for this device
                uniqueName = generateUniqueName(context)
                logManager.info(TAG, "Generated unique name: $uniqueName")
                saveUniqueName(uniqueName)
            }
            
            // Then check if we have a reserved token
            loadReservedToken { savedToken ->
                if (savedToken != null) {
                    logManager.info(TAG, "Found saved reserved token, using reserved mode")
                    callback.onLog("✅ Using reserved mode (permanent URL)")
                    checkEnableAndLaunchReserved(savedToken, callback)
                    return@loadReservedToken
                }
                
                // No reserved token, check if reservedShareToken is set programmatically
                if (reservedShareToken != null) {
                    logManager.info(TAG, "Using programmatic reserved token")
                    callback.onLog("✅ Using reserved mode (permanent URL)")
                    checkEnableAndLaunchReserved(reservedShareToken!!, callback)
                    return@loadReservedToken
                }
                
                // No reserved token, use public mode (random URL)
                logManager.info(TAG, "No reserved token, using public mode")
                checkEnableAndLaunchPublic(callback)
            }
        }
    }
    
    private fun loadSavedUniqueName(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_UNIQUE_NAME_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val name = output.trim()
                    // Must be lowercase alphanumeric, 4-32 chars, starting with "overdrive"
                    if (name.isNotEmpty() && !name.contains("No such file") && 
                        name.startsWith("overdrive") && name.matches(Regex("^[a-z0-9]{4,32}$"))) {
                        callback(name)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    private fun saveUniqueName(name: String) {
        adbShellExecutor.execute(
            command = "mkdir -p $ZROK_HOME/.zrok && echo '$name' > $ZROK_UNIQUE_NAME_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Unique name saved: $name")
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to save unique name: $error")
                }
            }
        )
    }
    
    private fun checkEnableAndLaunchPublic(callback: ZrokCallback) {
        // Check for the SPECIFIC identity file, not just the directory
        isDeviceEnabled { enabled ->
            if (enabled) {
                // Device already enabled - now check if we need to reserve
                logManager.info(TAG, "✅ Device already enabled (environment.json exists)")
                callback.onLog("✅ Device already enabled")
                checkReserveAndLaunch(callback)
            } else {
                // Need to enable - THIS COUNTS AGAINST THE 5-DEVICE LIMIT!
                logManager.warn(TAG, "⚠️ Device not enabled. Will register now (uses 1 of 5 slots)")
                callback.onLog("⚠️ Registering device (1 of 5 allowed)...")
                enableZrokEnvironment(callback)
            }
        }
    }
    
    /**
     * Check if we have a reserved token. If not, reserve one automatically.
     * This is similar to the enable check - reserve once, use forever.
     */
    private fun checkReserveAndLaunch(callback: ZrokCallback) {
        callback.onLog("Checking for reserved URL...")

        loadReservedToken { token ->
            if (token != null) {
                // Have reserved token - use reserved mode
                logManager.info(TAG, "✅ Found reserved token, using permanent URL")
                callback.onLog("✅ Using permanent URL")
                reservedShareToken = token
                launchZrokShareReserved(token, callback)
            } else {
                // No reserved token - need to reserve first (ONE TIME)
                logManager.info(TAG, "⚠️ No reserved token. Reserving permanent URL...")
                callback.onLog("⚠️ Reserving permanent URL (one-time setup)...")
                autoReserveAndLaunch(callback)
            }
        }
    }
    
    /**
     * Automatically reserve a permanent URL and then launch.
     * This runs `zrok reserve` once, saves the token, then uses `zrok share reserved`.
     */
    private fun autoReserveAndLaunch(callback: ZrokCallback) {
        callback.onLog("Reserving permanent URL with name: $uniqueName")

        isSingboxActive { active ->
            executeAutoReserve(active, callback, 0)
        }
    }
    
    private fun executeAutoReserve(useProxy: Boolean, callback: ZrokCallback, retryCount: Int = 0) {
        if (retryCount > 3) {
            logManager.error(TAG, "Reserve failed after 3 retries")
            callback.onError("Failed to reserve URL after multiple attempts")
            return
        }
        
        val cmd = buildString {
            // Use timeout to prevent hanging (30 seconds max)
            append("timeout 30 sh -c '")
            
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            append(zrokCommand)

            if (isSelfHosted) {
                append(" create name -n public $uniqueName")
            } else {
                // Note: `zrok reserve` doesn't support --headless, only `zrok share` does
                append(" reserve public http://localhost:8080 --unique-name $uniqueName")
            }
            append("' 2>&1")
        }
        
        logManager.debug(TAG, "Executing auto-reserve (attempt ${retryCount + 1}): $cmd")
        callback.onLog("Reserving URL (attempt ${retryCount + 1})...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserve output: $output")
                    
                    // Check for timeout
                    if (output.isEmpty() || output.contains("timeout")) {
                        logManager.warn(TAG, "Reserve command timed out, retrying...")
                        callback.onLog("⚠️ Timeout, retrying...")
                        executeAutoReserve(useProxy, callback, retryCount + 1)
                        return
                    }
                    
                    // Parse the reserved token from output
                    // Expected: [INFO] your reserved share token is 'abc-xyz-123'
                    val tokenPattern = Regex("token is '([^']+)'")
                    val match = tokenPattern.find(output)
                    
                    if (isSelfHosted || match != null) {
                        val token = if (isSelfHosted) uniqueName else match!!.groupValues[1]
                        logManager.info(TAG, "✅ Reserved! Token: $token")
                        callback.onLog("✅ Reserved! Permanent URL ready")
                        
                        // Save token for future use
                        saveReservedToken(token)
                        reservedShareToken = token
                        
                        // Now launch with reserved token
                        launchZrokShareReserved(token, callback)
                    } else if (output.contains("already reserved") || output.contains("exists") || output.contains("duplicate")) {
                        // Name already taken - generate new name and retry
                        logManager.warn(TAG, "Name '$uniqueName' already taken, generating new name...")
                        callback.onLog("⚠️ Name taken, trying new name...")
                        uniqueName = generateUniqueName(context)
                        saveUniqueName(uniqueName)
                        executeAutoReserve(useProxy, callback, 0) // Reset retry count for new name
                    } else if (output.contains("error") || output.contains("failed") || output.contains("ERROR")) {
                        logManager.error(TAG, "Reserve failed: $output")
                        callback.onError("Failed to reserve URL: $output")
                    } else {
                        // Unexpected output - try to extract token anyway or fail
                        logManager.warn(TAG, "Unexpected reserve output: $output")
                        callback.onError("Reserve failed: $output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Reserve command failed: $error")
                    // Check if it's a timeout or network issue - retry
                    if (error.contains("timeout") || error.contains("connection")) {
                        callback.onLog("⚠️ Connection issue, retrying...")
                        executeAutoReserve(useProxy, callback, retryCount + 1)
                    } else {
                        callback.onError("Reserve failed: $error")
                    }
                }
            }
        )
    }
    
    /**
     * Enable zrok environment with token.
     * Uses same proxy detection as cloudflared.
     */
    private fun enableZrokEnvironment(callback: ZrokCallback) {
        callback.onLog("Enabling zrok environment...")

        isSingboxActive { active ->
            if (active) {
                logManager.info(TAG, "Sing-box detected, using socks5 proxy")
            } else {
                logManager.info(TAG, "Sing-box not running, enabling zrok without proxy")
            }
            enableZrokWithConfig(callback, active)
        }
    }
    
    private fun enableZrokWithConfig(callback: ZrokCallback, useProxy: Boolean) {
        // First ensure we have a token
        if (zrokToken.isEmpty()) {
            logManager.error(TAG, "No enable token configured!")
            callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
            return
        }
        
        val cmd = buildString {
            if (useProxy) {
                // Zrok uses socks5 proxy (different from cloudflared's http proxy)
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
                callback.onLog("Using sing-box socks5 proxy...")
            } else {
                callback.onLog("Direct connection (no proxy)...")
            }

            append("$zrokCommand enable $zrokToken --headless 2>&1")
        }
        
        logManager.debug(TAG, "Executing enable: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok enable output: $output")
                    
                    // Check for errors
                    val lowerOutput = output.lowercase()
                    when {
                        lowerOutput.contains("already enabled") || 
                        lowerOutput.contains("environment already") -> {
                            callback.onLog("✅ Zrok already enabled")
                            // After enable, check for reserve
                            checkReserveAndLaunch(callback)
                        }
                        lowerOutput.contains("error") || lowerOutput.contains("failed") -> {
                            if (lowerOutput.contains("limit") || lowerOutput.contains("maximum")) {
                                callback.onError("❌ Device limit reached! You've used all 5 registrations.")
                            } else {
                                callback.onError("Failed to enable zrok: $output")
                            }
                        }
                        else -> {
                            callback.onLog("✅ Zrok environment enabled")
                            // Small delay before checking reserve
                            Thread.sleep(500)
                            // After enable, check for reserve
                            checkReserveAndLaunch(callback)
                        }
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to enable zrok: $error")
                    callback.onError("Failed to enable zrok: $error")
                }
            }
        )
    }
    
    /**
     * Launch zrok share public.
     * This is SAFE to run unlimited times - it doesn't count against device limit.
     */
    private fun launchZrokShare(callback: ZrokCallback) {
        callback.onLog("Starting zrok share (unlimited restarts OK)...")

        isSingboxActive { active ->
            if (!active) {
                logManager.info(TAG, "Sing-box not running, launching zrok without proxy")
            }
            launchZrokShareWithConfig(callback, active)
        }
    }

    private fun launchZrokShareWithConfig(callback: ZrokCallback, useProxy: Boolean) {
        // Clear old log first
        adbShellExecutor.execute(
            command = "rm -f $ZROK_LOG",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    startZrokShareProcess(callback, useProxy)
                }
                override fun onError(error: String) {
                    startZrokShareProcess(callback, useProxy)
                }
            }
        )
    }
    
    private fun startZrokShareProcess(callback: ZrokCallback, useProxy: Boolean) {
        val cmd = buildString {
            append("nohup sh -c '")
            
            if (useProxy) {
                // Zrok uses socks5 proxy
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            
            append("$zrokCommand share public http://localhost:8080 --headless")
            append("' > $ZROK_LOG 2>&1 &")
        }
        
        logManager.debug(TAG, "Executing share: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok share command sent")
                    callback.onLog("Waiting for tunnel URL...")
                    waitForTunnelUrl(callback, 1)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch zrok share: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }


    private fun waitForTunnelUrl(callback: ZrokCallback, attempt: Int) {
        val maxAttempts = 15
        if (attempt > maxAttempts) {
            // Timeout - get final log
            adbShellExecutor.execute(
                command = "cat $ZROK_LOG 2>/dev/null",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        // Check for proxy errors
                        if (output.contains("proxyconnect") ||
                            (output.contains("proxy") && output.contains("refused"))) {
                            logManager.error(TAG, "Proxy error - is sing-box running?")
                            callback.onError("Proxy Error: Is sing-box running on port $PROXY_PORT?\n${output.takeLast(200)}")
                            return
                        }

                        // Check for connection errors
                        if (output.contains("connection refused") || output.contains("dial tcp")) {
                            logManager.error(TAG, "Connection error: $output")
                            callback.onError("Zrok connection error: ${output.takeLast(300)}")
                            return
                        }

                        // Check for token/identity errors
                        val lowerContent = output.lowercase()
                        if (lowerContent.contains("invalid") && lowerContent.contains("token")) {
                            logManager.error(TAG, "Invalid token: $output")
                            callback.onError("Invalid zrok token. Please check your token.")
                            return
                        }

                        // Check for identity not found (need to re-enable)
                        if (lowerContent.contains("identity") && lowerContent.contains("not found")) {
                            logManager.error(TAG, "Identity not found - need to re-enable")
                            callback.onError("Identity not found. Device may need re-registration.")
                            return
                        }

                        logManager.error(TAG, "Zrok timed out. Log: ${output.takeLast(500)}")
                        callback.onError("Failed to get URL. Log tail:\n${output.takeLast(500)}")
                    }
                    
                    override fun onError(error: String) {
                        callback.onError("Timed out waiting for tunnel URL")
                    }
                }
            )
            return
        }

        Thread.sleep(1000)

        getTunnelUrl { url ->
            if (url != null) {
                logManager.info(TAG, "Tunnel established: $url")
                callback.onLog("Tunnel established: $url")
                callback.onTunnelUrl(url)
            } else {
                callback.onLog("Waiting... ($attempt/$maxAttempts)")
                waitForTunnelUrl(callback, attempt + 1)
            }
        }
    }
    
    /**
     * Stop the zrok tunnel.
     * Safe to call - doesn't affect device registration.
     */
    fun stopTunnel(callback: ZrokCallback) {
        logManager.info(TAG, "Stopping zrok tunnel...")
        callback.onLog("Stopping tunnel...")
        
        // Kill zrok process and clear the log file so stale URLs don't cause false positives
        adbShellExecutor.execute(
            command = "pkill -9 -f 'zrok' 2>/dev/null; rm -f $ZROK_LOG; echo stopped",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok tunnel stopped")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }
                
                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.info(TAG, "Zrok tunnel stopped (with warning: $error)")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }
            }
        )
    }
    
    /**
     * Check if zrok tunnel is running.
     */
    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        // Check for actual zrok share process specifically
        // Look for process with 'zrok share' in command line to avoid false positives
        adbShellExecutor.execute(
            command = "ps -A -o ARGS 2>/dev/null | grep -E 'zrok2? share' | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val isRunning = output.trim().isNotEmpty() && (output.contains("zrok share") || output.contains("zrok2 share"))
                    logManager.debug(TAG, "isTunnelRunning check: $isRunning (output: '${output.trim().take(50)}')")
                    callback(isRunning)
                }

                override fun onError(error: String) {
                    // grep returns exit code 1 when no match - that's expected when not running
                    logManager.debug(TAG, "isTunnelRunning: not running (grep found nothing)")
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Get current tunnel URL from zrok commands and parse json
     */
    fun getTunnelUrl(callback: (String?) -> Unit) {
        val cmd = buildString {
            append(zrokCommand)
            if (isSelfHosted) {
                append(" list shares --json")
            } else {
                append(" overview")
            }
            append(" 2>/dev/null")
        }

        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    try {
                        val data = JSONObject(output)
                        val url = if (isSelfHosted) {
                            data.getJSONArray("shares")
                                .getJSONObject(0)
                                .getJSONArray("frontendEndpoints")
                                .getString(0)
                        } else {
                            data.getJSONArray("environments")
                                .getJSONObject(0)
                                .getJSONArray("shares")
                                .getJSONObject(0)
                                .getString("frontendEndpoint")
                        }
                        logManager.info(TAG, "Found tunnel URL: $url")
                        saveTunnelUrl(url)
                        callback(url)
                    } catch (error: JSONException) {
                        logManager.error(TAG, "Failed to parse Zrok output: $error")
                        callback(null)
                    }
                }

                override fun onError(error: String) {
                    logManager.warn(TAG, "Zrok command failed - tunnel may need restart")
                    callback(null)
                }
            }
        )
    }

    /**
     * Save the url so it can be used by telegram
     */
    private fun saveTunnelUrl(url: String) {
        adbShellExecutor.execute(
            command = "echo '$url' > /data/local/tmp/tunnel_url.txt",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tunnel url saved: $url")
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to save tunnel url: $error")
                }
            }
        )
    }

    /**
     * Check if device is already enabled (has identity file).
     */
    fun isDeviceEnabled(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -f $zrokIdentityFile && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }
                
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Disable zrok environment (cleanup).
     * WARNING: This will require re-enabling which uses one of your 5 device slots!
     */
    fun disableEnvironment(callback: ZrokCallback? = null) {
        logManager.warn(TAG, "⚠️ Disabling zrok environment - will need to re-register!")
        callback?.onLog("⚠️ Disabling environment (will need re-registration)...")
        
        adbShellExecutor.execute(
            command = "$zrokCommand disable 2>&1; rm -rf $ZROK_HOME/.zrok $ZROK_HOME/.zrok2 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok environment disabled")
                    callback?.onLog("Environment disabled")
                    callback?.onTunnelUrl("")
                }
                
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to disable zrok: $error")
                    callback?.onError("Failed to disable: $error")
                }
            }
        )
    }
    
    // ==================== Enable Token Management ====================
    
    /**
     * Save enable token to unified storage (cross-UID accessible).
     * Stores in $ZROK_HOME/.zrok/enable_token for daemon access.
     */
    fun saveEnableToken(token: String, callback: ((Boolean) -> Unit)? = null) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            callback?.invoke(false)
            return
        }
        
        // Update in-memory token
        zrokToken = trimmedToken
        tokenLoaded = true
        
        adbShellExecutor.execute(
            command = "mkdir -p $ZROK_HOME/.zrok && echo '$trimmedToken' > $ZROK_ENABLE_TOKEN_FILE && chmod 666 $ZROK_ENABLE_TOKEN_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Enable token saved to unified storage")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to save enable token: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    /**
     * Save endpoint to unified storage (cross-UID accessible).
     * Stores in $ZROK_HOME/.zrok/endpoint for daemon access.
     */
    fun saveZrokEndpoint(endpoint: String?, callback: ((Boolean) -> Unit)? = null) {
        val trimmedEndpoint = endpoint?.trim() ?: ""

        // Update in-memory endpoint
        zrokEndpoint = trimmedEndpoint
        endpointLoaded = true

        adbShellExecutor.execute(
            command = "mkdir -p $ZROK_HOME/.zrok && echo '$trimmedEndpoint' > $ZROK_ENDPOINT_FILE && chmod 666 $ZROK_ENDPOINT_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Endpoint saved to unified storage")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to save endpoint: $error")
                    callback?.invoke(false)
                }
            }
        )
    }
    
    /**
     * Load enable token from unified storage.
     * Returns the token via callback, or null if not found.
     */
    fun loadEnableToken(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_ENABLE_TOKEN_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val token = output.trim()
                    if (token.isNotEmpty() && !token.contains("No such file")) {
                        zrokToken = token
                        tokenLoaded = true
                        logManager.info(TAG, "Enable token loaded from unified storage")
                        callback(token)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }

    /**
     * Get zrok apiEndpoint.
     */
    fun loadZrokEndpoint(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_ENDPOINT_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val endpoint = output.trim()
                    if (endpoint.isNotEmpty() && !endpoint.contains("No such file")) {
                        zrokEndpoint = endpoint
                        endpointLoaded = true
                        logManager.info(TAG, "Enable token loaded from unified storage")
                        callback(endpoint)
                    } else {
                        zrokEndpoint = ""
                        endpointLoaded = true
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    zrokEndpoint = ""
                    endpointLoaded = false
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Delete enable token from unified storage.
     */
    fun deleteZrokSettings(callback: ((Boolean) -> Unit)? = null) {
        zrokToken = ""
        tokenLoaded = false
        zrokEndpoint = ""
        endpointLoaded = false
        
        adbShellExecutor.execute(
            command = "rm -f $ZROK_ENABLE_TOKEN_FILE $ZROK_ENDPOINT_FILE 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Enable token deleted from unified storage")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to delete enable token: $error")
                    callback?.invoke(false)
                }
            }
        )
    }
    
    /**
     * Check if enable token exists in unified storage.
     */
    fun hasEnableToken(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -f $ZROK_ENABLE_TOKEN_FILE && test -s $ZROK_ENABLE_TOKEN_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Ensure settings are loaded before operations.
     * Call this before launchZrok() to ensure token and endpoint is available.
     */
    fun ensureSettingsLoaded(callback: (Boolean) -> Unit) {
        if (tokenLoaded && zrokToken.isNotEmpty() && endpointLoaded) {
            callback(true)
            return
        }

        loadZrokEndpoint {
            loadEnableToken { token ->
                callback(token != null)
            }
        }
    }
}
