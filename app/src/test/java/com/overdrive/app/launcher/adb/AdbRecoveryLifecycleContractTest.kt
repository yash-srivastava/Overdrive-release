package com.overdrive.app.launcher.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdbRecoveryLifecycleContractTest {
    @Test fun keepaliveOwnsEveryTriggerAndRemovesCallbacks() {
        val source = File("src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt").readText()
        assertTrue(source.contains("registerAdbRecoveryLifecycle()"))
        assertTrue(source.contains("unregisterAdbRecoveryLifecycle()"))
        assertTrue(source.contains("NET_CAPABILITY_VALIDATED"))
        assertTrue(source.contains("Intent.ACTION_SCREEN_ON"))
        assertTrue(source.contains("Intent.ACTION_USER_PRESENT"))
        assertTrue(source.contains("ConnectionState.DISCONNECTED"))
        assertTrue(source.contains("removeConnectionStateListener"))
        assertTrue(source.contains(".get(applicationContext).cancelAutomatic()"))
        val parkedGate = source.indexOf("parked-shutdown marker present")
        val lifecycleRegistration = source.indexOf("registerAdbRecoveryLifecycle()", parkedGate)
        assertTrue(parkedGate >= 0 && lifecycleRegistration > parkedGate)
        val manager = File("src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt").readText()
        assertTrue(manager.contains("AdbRecoveryManager"))
        assertTrue(manager.contains(".triggerAutomatic()"))
        assertTrue(source.indexOf("registerAdbRecoveryLifecycle()") < source.indexOf("override fun onDestroy()"))
    }

    @Test fun automaticPortRestorationRequiresBothGenerationAndExplicitOptIn() {
        assertFalse(com.overdrive.app.launcher.AdbShellExecutor.automaticReenableAllowed(false, false))
        assertFalse(com.overdrive.app.launcher.AdbShellExecutor.automaticReenableAllowed(false, true))
        assertFalse(com.overdrive.app.launcher.AdbShellExecutor.automaticReenableAllowed(true, false))
        assertTrue(com.overdrive.app.launcher.AdbShellExecutor.automaticReenableAllowed(true, true))
        assertFalse(com.overdrive.app.launcher.AdbShellExecutor.shouldRequestAutomaticRecovery(false, true))
        assertFalse(com.overdrive.app.launcher.AdbShellExecutor.shouldRequestAutomaticRecovery(true, false))
        assertTrue(com.overdrive.app.launcher.AdbShellExecutor.shouldRequestAutomaticRecovery(true, true))
    }

    @Test fun adbUiUsesWarningAndIndependentCancellationLane() {
        val source = File("src/main/java/com/overdrive/app/ui/fragment/settings/SettingsAdbFragment.kt").readText()
        assertTrue(source.contains("onAutomaticRestoreChanged(enabled)"))
        assertTrue(source.contains("settings_dilink5_adb_enable_confirm_body"))
        assertTrue(source.contains("settings_adb_recovery_confirm_body"))
        assertTrue(source.contains("recoveryControlExecutor().execute"))
        assertTrue(source.contains("Thread(r, \"AdbRecoveryControl\")"))
        val manualStart = source.indexOf("private fun confirmAdbRecovery()")
        val manualEnd = source.indexOf("private fun recoveryResultText", manualStart)
        val manual = source.substring(manualStart, manualEnd)
        assertTrue(manual.contains("recoverManualAsync"))
        assertFalse(manual.contains("executor().execute"))
    }

    @Test fun classicExecutorKeepsAuthCallbackAndAddsMulticastStateObservers() {
        val source = File("src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt").readText()
        assertTrue(source.contains("private var authCallback: AdbAuthCallback?"))
        assertTrue(source.contains("fun setAuthCallback(callback: AdbAuthCallback?)"))
        assertTrue(source.contains("CopyOnWriteArrayList<ConnectionStateListener>"))
        assertTrue(source.contains("installFreshAuthenticatedConnection"))
        val installStart = source.indexOf("fun installFreshAuthenticatedConnection(")
        val installEnd = source.indexOf("fun shutdown()", installStart)
        val installMethod = source.substring(installStart, installEnd)
        assertTrue(installMethod.contains("publishIfCurrent: ((() -> Unit) -> Boolean)"))
        assertTrue(installMethod.indexOf("val published = publishIfCurrent") <
            installMethod.indexOf("installConnectionLocked(ready)"))
    }

    @Test fun classicExecutorDelegatesAutomaticMutationToCoordinator() {
        val source = File("src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt").readText()
        val start = source.indexOf("private fun requestDiLink5AdbReenable()")
        val end = source.indexOf("private fun watchDiLink5AdbPort()", start)
        val method = source.substring(start, end)
        assertTrue(method.contains("AdbRecoveryManager"))
        assertTrue(method.contains("triggerAutomatic()"))
        assertTrue(source.contains("automaticRecoveryRequestsAllowed"))
        val coordinator = File(
            "src/main/java/com/overdrive/app/launcher/adb/DiLink5AdbRecoveryCoordinator.kt",
        ).readText()
        assertTrue(coordinator.contains("automaticRecoveryRequestsAllowed = false"))
        assertFalse(method.contains("Settings.Global.put"))
        assertFalse(method.contains("adb_enabled"))
        assertFalse(method.contains("adb_allowed_connection_time"))
    }

    @Test fun localObserverBelongsToTheMainApplicationRatherThanASettingsViewOrWorker() {
        val application = File("src/main/java/com/overdrive/app/OverdriveApplication.kt").readText()
        val workerGuard = application.indexOf("DiLink5AdbWorkerProcess.isCurrentProcess()")
        val ownerStart = application.indexOf("AdbRecoveryManager.start(this)")
        val mainGuard = application.lastIndexOf(
            "if (packageName == android.app.Application.getProcessName())", ownerStart,
        )
        assertTrue(workerGuard >= 0 && mainGuard > workerGuard && ownerStart > mainGuard)
        val manager = File("src/main/java/com/overdrive/app/launcher/adb/AdbRecoveryManager.kt").readText()
        assertTrue(manager.contains("Application.getProcessName() != context.packageName"))
        assertTrue(manager.contains("Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false"))
        assertTrue(manager.contains("unregisterContentObserver(observed)"))
        assertTrue(manager.contains("automaticRecoveryRequestsAllowed = false"))
        assertTrue(manager.contains("committedDiLink5RecoveryAvailable()"))
        assertTrue(manager.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.R"))
        assertFalse(manager.contains("adb_allowed_connection_time"))
        assertFalse(manager.contains("recordWrite("))
    }

    @Test fun recoverRemainsAvailableForAWirelessOwnerAwaitingManualApproval() {
        val source = File("src/main/java/com/overdrive/app/ui/fragment/settings/SettingsAdbFragment.kt").readText()
        val controls = source.substring(
            source.indexOf("private fun setAdbOperationControlsEnabled"),
            source.indexOf("private fun renderAdbResult"),
        )
        assertTrue(controls.contains("val localBusy = localRecoveryManager().hasLocalManualRequest()"))
        assertTrue(controls.contains("R.id.btnAdbRecover -> manualAdbModeAvailable"))
        assertTrue(controls.contains("enabled && compatible && !localBusy"))
        assertFalse(controls.contains("!localRecoveryManager().hasManualRecoveryOwner()"))
    }
}
