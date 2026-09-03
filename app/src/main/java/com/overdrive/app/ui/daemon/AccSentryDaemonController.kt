package com.overdrive.app.ui.daemon

import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.util.ScratchPaths

/**
 * Controller for AccSentryDaemon - handles ACC monitoring and screen control.
 * 
 * This daemon MUST run as UID 2000 (shell) for screen control to work.
 * It's separate from SentryDaemon (UID 1000) which handles system whitelisting.
 */
class AccSentryDaemonController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.ACC_SENTRY_DAEMON
    
    companion object {
        private const val PROCESS_NAME = "acc_sentry_daemon"
    }
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Launching AccSentryDaemon (UID 2000)...")
        
        adbLauncher.launchAccSentryDaemon(
            onSuccess = {
                callback.onStatusChanged(DaemonStatus.RUNNING, "Running as UID 2000")
            },
            onError = { error ->
                callback.onError(error)
            }
        )
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping...")

        // Plant the disable sentinel BEFORE the kill so the watchdog (if it
        // survives the pkill via an orphan race) sees it on its next loop
        // iteration and exits cleanly. Single broad pkill on 'acc_sentry'
        // takes out start_acc_sentry.sh (watchdog), acc_sentry_daemon
        // (daemon), and any orphans together. Mirrors CameraDaemonController
        // and ZrokController.
        // Use executeShellScript (tmpfile) so toybox `pkill -f 'acc_sentry'`
        // can't self-match the calling shell's argv. Order: sentinel +
        // chmod first, watchdog-script rm, pkill, then settle + lock-rm.
        // Lock rm AFTER pkill prevents the lockfile-resurrection race.
        adbLauncher.executeShellScript(
            "echo \"disabled by ui at \$(date)\" > " + ScratchPaths.getDir() + "/acc_sentry_daemon.disabled\n" +
            "chmod 666 " + ScratchPaths.getDir() + "/acc_sentry_daemon.disabled 2>/dev/null\n" +
            "rm -f " + ScratchPaths.getDir() + "/start_acc_sentry.sh 2>/dev/null\n" +
            com.overdrive.app.launcher.DaemonLauncher.psAwkKillLine("acc_sentry") +
            "sleep 1\n" +
            "rm -f " + ScratchPaths.getDir() + "/acc_sentry_daemon.lock 2>/dev/null\n" +
            "echo done\n",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Stopped")
                }
                override fun onError(error: String) {
                    // pkill returns error if no process - that's fine
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Stopped")
                }
            }
        )
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.executeShellCommand(
            "ps -A | grep $PROCESS_NAME | grep -v grep",
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
        // Use executeShellScript (tmpfile) for self-match defense.
        adbLauncher.executeShellScript(
            "rm -f " + ScratchPaths.getDir() + "/start_acc_sentry.sh 2>/dev/null\n" +
            com.overdrive.app.launcher.DaemonLauncher.psAwkKillLine("acc_sentry") +
            "sleep 1\n" +
            "rm -f " + ScratchPaths.getDir() + "/acc_sentry_daemon.lock 2>/dev/null\n" +
            "echo done\n",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
    }
}
