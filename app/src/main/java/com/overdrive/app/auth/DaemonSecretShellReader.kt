package com.overdrive.app.auth

import android.content.Context
import com.overdrive.app.launcher.AdbShellExecutor

/** Reads a shell-owned file over the app's open ADB link; never opens one (that can prompt for authorization). */
class DaemonSecretShellReader(context: Context) : AuthManager.DaemonSecretReader {
    private val appContext = context.applicationContext
    private val shell by lazy { AdbShellExecutor(appContext, automaticRecoveryRequestsAllowed = false) }

    override fun read(path: String): String? {
        if (!AdbShellExecutor.isConnected()) return null
        return try {
            val result = shell.executeSync("cat '$path'", 3_000L)
            if (result.exitCode == 0) result.output else null
        } catch (_: Exception) {
            null
        }
    }
}
