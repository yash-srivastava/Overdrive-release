package com.overdrive.app.ui.daemon

import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.util.ScratchPaths

/**
 * Controller for the Sentry Daemon (SentryDaemon.java).
 * 
 * Kill methods:
 * 1. Control socket (port 19876) - clean shutdown
 * 2. PID file (/data/local/tmp/sentry_daemon.pid)
 * 3. pkill -f SentryDaemon - matches Java class name
 */
class SentryDaemonController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.SENTRY_DAEMON
    
    private fun getKillCommand(): String {
        // ps+awk+kill, NOT pkill -f. executeShellCommand wraps in
        // `sh -c "<body>"`; pkill -f matches that wrapper's argv on
        // any literal pattern in the body, SIGKILLing the calling
        // shell before subsequent commands run.
        //
        // ALSO fixed: previously this command also pkilled
        // 'AccSentryDaemon' — a collateral kill of a sibling daemon
        // when stopping SentryDaemon. AccSentryDaemon has its own
        // controller and its own watchdog; stopping SentryDaemon
        // should not nuke AccSentryDaemon (and its watchdog would
        // respawn it anyway, leaving the user's stop intent
        // partially honored). Match only on `--nice-name=sentry_daemon`
        // (which is what app_process emits in argv) plus the FQCN of
        // the SentryDaemon class — neither contains "AccSentry" as a
        // substring.
        return "echo 'STOP' | nc -w 1 127.0.0.1 19879 2>/dev/null; " +  // Port 19879 for SentryDaemon
               "if [ -f " + ScratchPaths.getDir() + "/sentry_daemon.pid ]; then " +
               "kill -9 \$(cat " + ScratchPaths.getDir() + "/sentry_daemon.pid) 2>/dev/null; " +
               "rm -f " + ScratchPaths.getDir() + "/sentry_daemon.pid; fi; " +
               "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F sentry_daemon | grep -v grep " +
               "| grep -v acc_sentry | awk '{print \$1}' | while read pid; do " +
               "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
               "echo done"
    }

    private fun getCheckCommand(): String {
        // Match sentry_daemon nice-name only, not AccSentryDaemon (which is
        // a separate daemon with its own controller).
        return "pgrep -f 'sentry_daemon' 2>/dev/null | head -1"
    }
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting sentry daemon...")
        
        adbLauncher.launchSentryDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STARTING, message)
            }
            
            override fun onLaunched() {
                callback.onStatusChanged(DaemonStatus.RUNNING, "Sentry daemon running")
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping sentry daemon...")
        
        adbLauncher.executeShellCommand(
            getKillCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Sentry daemon stopped")
                }
                override fun onError(error: String) {
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Sentry daemon stopped")
                }
            }
        )
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.executeShellCommand(
            getCheckCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    callback(message.trim().isNotEmpty())
                }
                override fun onLaunched() {}
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    override fun cleanup() {
        adbLauncher.executeShellCommand(
            getKillCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
    }
}
