package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import com.overdrive.app.util.ScratchPaths

/**
 * Launches sing-box proxy via ADB shell.
 * 
 * Installs sing-box from jniLibs (libsingbox.so) to /data/local/tmp/sing-box
 * and manages the proxy process.
 */
class SingboxLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "SingboxLauncher"
        
        // Paths
        private val SINGBOX_TMP_PATH = ScratchPaths.path("sing-box")
        private val SINGBOX_CONFIG_PATH = ScratchPaths.path("singbox_config.json")
        private val SINGBOX_LOG = ScratchPaths.path("singbox.log")
        
        // Default proxy port
        private const val PROXY_PORT = 8119
    }
    
    interface SingboxCallback {
        fun onLog(message: String)
        fun onStarted()
        fun onError(error: String)
    }
    
    /**
     * Launch sing-box proxy via ADB shell.
     */
    fun launchSingbox(callback: SingboxCallback) {
        logManager.info(TAG, "Launching sing-box...")
        callback.onLog("Setting up sing-box...")
        
        // Check if binary is installed
        adbShellExecutor.execute(
            command = "test -x $SINGBOX_TMP_PATH && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        killExistingAndLaunch(callback)
                    } else {
                        installSingbox(callback)
                    }
                }
                
                override fun onError(error: String) {
                    installSingbox(callback)
                }
            }
        )
    }
    
    private fun installSingbox(callback: SingboxCallback) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libsingbox.so"
        
        callback.onLog("Installing sing-box...")
        
        // Check if source exists
        adbShellExecutor.execute(
            command = "test -f $srcPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() != "yes") {
                        logManager.error(TAG, "libsingbox.so not found")
                        callback.onError("libsingbox.so not found. Add it to jniLibs/arm64-v8a/")
                        return
                    }
                    
                    // Copy and make executable
                    adbShellExecutor.execute(
                        command = "cp $srcPath $SINGBOX_TMP_PATH && chmod +x $SINGBOX_TMP_PATH",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(copyOutput: String) {
                                callback.onLog("sing-box installed")
                                killExistingAndLaunch(callback)
                            }
                            
                            override fun onError(error: String) {
                                logManager.error(TAG, "Failed to install sing-box: $error")
                                callback.onError("Failed to install sing-box: $error")
                            }
                        }
                    )
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to check sing-box source: $error")
                    callback.onError("Failed to check sing-box source: $error")
                }
            }
        )
    }
    
    private fun killExistingAndLaunch(callback: SingboxCallback) {
        callback.onLog("Stopping existing sing-box...")

        // ps+awk+kill instead of pkill -f. The latter self-matches because
        // execute() wraps body in `sh -c "<cmd>"` and the wrapper's argv
        // contains the literal "sing-box" — toybox pkill SIGKILLs the
        // calling shell before any subsequent commands run.
        adbShellExecutor.execute(
            command = "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F sing-box | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "true",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    Thread.sleep(500)
                    cleanupAndLaunch(callback)
                }

                override fun onError(error: String) {
                    cleanupAndLaunch(callback)
                }
            }
        )
    }
    
    private fun cleanupAndLaunch(callback: SingboxCallback) {
        // Remove old config and logs
        adbShellExecutor.execute(
            command = "rm -f $SINGBOX_CONFIG_PATH $SINGBOX_LOG 2>/dev/null || true",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    createConfigAndLaunch(callback)
                }
                
                override fun onError(error: String) {
                    createConfigAndLaunch(callback)
                }
            }
        )
    }
    
    private fun createConfigAndLaunch(callback: SingboxCallback) {
        callback.onLog("Creating sing-box config...")
        
        // VLESS Reality config for HTTP proxy on port 8119
        // Updated for sing-box 1.12.0+ (removed deprecated geoip rules and legacy DNS format)
        val config = """
{
  "log": { "level": "warn", "timestamp": true, "output": "$SINGBOX_LOG" },
  "dns": {
    "servers": [
      { "tag": "google", "address": "tcp://8.8.8.8", "detour": "proxy" }
    ],
    "final": "google",
    "strategy": "ipv4_only"
  },
  "inbounds": [
    {
      "type": "mixed",
      "tag": "mixed-in",
      "listen": "127.0.0.1",
      "listen_port": $PROXY_PORT,
      "sniff": true
    }
  ],
  "outbounds": [
    {
      "type": "vless",
      "tag": "proxy",
      "server": "80.225.224.92",
      "server_port": 443,
      "uuid": "ce8591be-9fa8-4361-90f3-427e9b5e8b85",
      "flow": "xtls-rprx-vision",
      "tls": {
        "enabled": true,
        "server_name": "google.com",
        "utls": { "enabled": true, "fingerprint": "chrome" },
        "reality": {
          "enabled": true,
          "public_key": "fxNUGiLzVwAk89RgogDrMq2u4pzyWAe_wx8D2frOPAQ",
          "short_id": "3ca47a3f8fb71e13"
        }
      }
    },
    { "type": "direct", "tag": "direct" }
  ],
  "route": {
    "rules": [
      { "protocol": "dns", "outbound": "proxy" }
    ]
  }
}
""".trimIndent()
        
        // Write config via shell
        val escapedConfig = config.replace("\"", "\\\"").replace("\n", "\\n")
        adbShellExecutor.execute(
            command = "echo '$config' > $SINGBOX_CONFIG_PATH",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    launchSingboxInternal(callback)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to write config: $error")
                    callback.onError("Failed to write config: $error")
                }
            }
        )
    }
    
    private fun launchSingboxInternal(callback: SingboxCallback) {
        callback.onLog("Starting sing-box...")
        
        val cmd = "nohup $SINGBOX_TMP_PATH run -c $SINGBOX_CONFIG_PATH > $SINGBOX_LOG 2>&1 &"
        
        logManager.debug(TAG, "Executing: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "sing-box launch command sent")
                    callback.onLog("Waiting for sing-box to start...")
                    waitForStartup(callback, 1)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch sing-box: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    private fun waitForStartup(callback: SingboxCallback, attempt: Int) {
        if (attempt > 10) {
            adbShellExecutor.execute(
                command = "cat $SINGBOX_LOG 2>/dev/null | tail -20",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.error(TAG, "sing-box startup timed out. Log: $output")
                        callback.onError("Startup timed out. Log:\n$output")
                    }
                    
                    override fun onError(error: String) {
                        callback.onError("Startup timed out")
                    }
                }
            )
            return
        }
        
        Thread.sleep(500)
        
        // Check if process is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box && echo RUNNING || echo NOT_RUNNING",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.contains("RUNNING") && !output.contains("NOT_RUNNING")) {
                        logManager.info(TAG, "sing-box started successfully")
                        callback.onLog("sing-box running on port $PROXY_PORT")
                        callback.onStarted()
                    } else {
                        callback.onLog("Waiting... ($attempt/10)")
                        waitForStartup(callback, attempt + 1)
                    }
                }
                
                override fun onError(error: String) {
                    callback.onLog("Waiting... ($attempt/10)")
                    waitForStartup(callback, attempt + 1)
                }
            }
        )
    }
    
    /**
     * Stop sing-box proxy.
     */
    fun stopSingbox(callback: SingboxCallback) {
        logManager.info(TAG, "Stopping sing-box...")
        callback.onLog("Stopping sing-box...")

        // ps+awk+kill — see killExistingAndLaunch above for self-match
        // explanation.
        adbShellExecutor.execute(
            command = "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F sing-box | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "true",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "sing-box stopped")
                    callback.onLog("sing-box stopped")
                    callback.onStarted() // Signal completion
                }
                
                override fun onError(error: String) {
                    callback.onLog("sing-box stopped")
                    callback.onStarted()
                }
            }
        )
    }
    
    /**
     * Check if sing-box is running.
     */
    fun isRunning(callback: (Boolean) -> Unit) {
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
}
