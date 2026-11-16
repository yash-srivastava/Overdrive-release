package com.overdrive.app.ui.daemon

import android.content.Context
import com.overdrive.app.R
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.util.AppLocaleContext

/**
 * Controller for the Sing-box Proxy.
 */
class SingboxController(
    private val context: Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.SINGBOX_PROXY
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Checking sing-box proxy...")
        adbLauncher.isSingboxRunning { running ->
            if (running) {
                callback.onStatusChanged(
                    DaemonStatus.RUNNING,
                    AppLocaleContext.of(context).getString(R.string.singbox_status_running)
                )
                return@isSingboxRunning
            }

            callback.onStatusChanged(DaemonStatus.STARTING, "Starting sing-box proxy...")
            adbLauncher.launchProxyDaemon(object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    callback.onStatusChanged(DaemonStatus.STARTING, message)
                }

                override fun onLaunched() {
                    callback.onStatusChanged(
                        DaemonStatus.RUNNING,
                        AppLocaleContext.of(context).getString(R.string.singbox_status_running)
                    )
                }

                override fun onError(error: String) {
                    callback.onError(error)
                }
            })
        }
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping sing-box proxy...")

        // Kill BOTH sing-box AND its GlobalProxyDaemon parent (`sentry_proxy`).
        // start() invokes adbLauncher.launchProxyDaemon → spawns
        // GlobalProxyDaemon under --nice-name=sentry_proxy, which in turn
        // launches sing-box. The previous code only killed sing-box; the
        // sentry_proxy parent stayed alive holding a wakelock + writing
        // a log line every 60 s indefinitely. Use stopProxyDaemon which
        // already routes through executeScript (tmpfile, no self-match)
        // and clears the global http_proxy settings as part of its
        // body — so we don't also need restoreProxySettings here.
        adbLauncher.stopSentryProxy(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {
                callback.onStatusChanged(DaemonStatus.STOPPED, "Sing-box proxy stopped")
            }
            override fun onError(error: String) {
                callback.onStatusChanged(DaemonStatus.STOPPED, "Sing-box proxy stopped")
            }
        })
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        // The listening socket is the readiness contract; process-list output
        // can include the shell wrapper used to run the probe itself.
        adbLauncher.isSingboxRunning(callback)
    }
    
    override fun cleanup() {
        // Same pattern as stop() — kill both sing-box and its sentry_proxy
        // parent so we don't leak a zombie wrapper.
        adbLauncher.stopSentryProxy(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {}
        })
    }
    
    // restoreProxySettings() removed — stopSentryProxy already clears
    // global_http_proxy_host/port/exclusion_list in its tmpfile script,
    // which is correct for the GlobalProxyDaemon-managed flow.
}
