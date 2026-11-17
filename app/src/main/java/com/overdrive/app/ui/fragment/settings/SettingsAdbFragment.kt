package com.overdrive.app.ui.fragment.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.adb.AdbRecoveryManager
import com.overdrive.app.launcher.adb.DiLink5AdbRecoveryCoordinator
import com.overdrive.app.launcher.dilink5.enable.BydManualAdbCapabilities
import com.overdrive.app.launcher.dilink5.enable.BydManualAdbModeAuthority
import com.overdrive.app.launcher.dilink5.enable.DiLink5AdbEnableCoordinator
import com.overdrive.app.launcher.dilink5.enable.DiLink5DeveloperToolsExport
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Settings → ADB. UI ownership is separate from the process-lifetime operation owner. */
class SettingsAdbFragment : Fragment() {
    private lateinit var groupDiLink5AdbEnablement: View
    private lateinit var groupAdbRecovery: View
    private lateinit var tvDiLink5AdbStatus: TextView
    private lateinit var tvDiLink5AdbDetails: TextView
    private lateinit var swAdbAutomaticRestore: MaterialSwitch
    private lateinit var swDiLink5AdbDialogAutoConfirm: MaterialSwitch
    private lateinit var btnAdbCancelRecovery: View
    private var updatingRecoverySwitches = false
    private var adbOperationObservation: AutoCloseable? = null
    private var adbDetailObservation: AutoCloseable? = null
    private var adbOperationControls: List<View> = emptyList()
    private var manualAdbCapabilities = BydManualAdbCapabilities.NONE
    private var manualAdbModeAvailable = false
    private var manualAdbModeAuthority: String? = null
    private var tlsRecoveryAvailable = false
    private var manualAdbUiGeneration = 0L

    private lateinit var groupAdbManualTools: View
    private lateinit var groupAdbManualAdvanced: View
    private lateinit var groupDiLink5AdbTlsAdvanced: View
    private lateinit var groupAdbAdvancedTools: View
    private lateinit var btnAdbAdvancedTools: MaterialButton
    private lateinit var tvAdbOperationSummary: TextView
    private lateinit var tvAdbRecoveryAvailability: View
    private var advancedExpanded = false
    private var adbIoExecutor: ExecutorService? = null
    private var recoveryControlExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings_adb, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        groupDiLink5AdbEnablement = view.findViewById(R.id.groupDiLink5AdbEnablement)
        groupAdbRecovery = view.findViewById(R.id.groupAdbRecovery)
        tvDiLink5AdbStatus = view.findViewById(R.id.tvDiLink5AdbStatus)
        tvDiLink5AdbDetails = view.findViewById(R.id.tvDiLink5AdbDetails)
        swAdbAutomaticRestore = view.findViewById(R.id.swAdbAutomaticRestore)
        swDiLink5AdbDialogAutoConfirm = view.findViewById(R.id.swDiLink5AdbDialogAutoConfirm)

        groupAdbManualTools = view.findViewById(R.id.groupAdbManualTools)
        groupAdbManualAdvanced = view.findViewById(R.id.groupAdbManualAdvanced)
        groupDiLink5AdbTlsAdvanced = view.findViewById(R.id.groupDiLink5AdbTlsAdvanced)
        groupAdbAdvancedTools = view.findViewById(R.id.groupAdbAdvancedTools)
        btnAdbAdvancedTools = view.findViewById(R.id.btnAdbAdvancedTools)
        tvAdbOperationSummary = view.findViewById(R.id.tvAdbOperationSummary)
        tvAdbRecoveryAvailability = view.findViewById(R.id.tvAdbRecoveryAvailability)
        advancedExpanded = savedInstanceState?.getBoolean(KEY_ADVANCED, false) ?: false
        btnAdbAdvancedTools.setOnClickListener {
            advancedExpanded = !advancedExpanded
            renderAdvancedTools()
        }
        renderAdvancedTools()
        swAdbAutomaticRestore.setOnCheckedChangeListener { _, enabled ->
            if (!updatingRecoverySwitches) onAutomaticRestoreChanged(enabled)
        }
        swDiLink5AdbDialogAutoConfirm.setOnCheckedChangeListener { _, enabled ->
            if (!updatingRecoverySwitches && tlsRecoveryAvailable) {
                val client = recoveryCoordinator()
                executor().execute {
                    val saved = committedDiLink5RecoveryAvailable() &&
                        client.setDialogAutoConfirm(enabled)
                    val enabledHere = saved &&
                        client.isDialogAutoConfirmEnabledForCurrentNetwork()
                    mainHandler.post {
                        if (!isAdded || this@SettingsAdbFragment.view !== view) return@post
                        updatingRecoverySwitches = true
                        swDiLink5AdbDialogAutoConfirm.isChecked = enabledHere
                        updatingRecoverySwitches = false
                    }
                }
            }
        }
        val btnAdbRecover = view.findViewById<View>(R.id.btnAdbRecover)
        btnAdbRecover.setOnClickListener { confirmAdbRecovery() }
        btnAdbCancelRecovery = view.findViewById(R.id.btnAdbCancelRecovery)
        btnAdbCancelRecovery.setOnClickListener {
            val client = localRecoveryManager()
            recoveryControlExecutor().execute {
                val prior = LAST_ADB_RECOVERY_RESULT ?: client.lastResult()
                val rolledBack = client.cancelManual()
                val cleanupPending = !rolledBack && client.hasManualRecoveryOwner()
                val wireless = (prior?.wirelessResult ?: DiLink5AdbRecoveryCoordinator.Result(
                    DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED,
                )).copy(
                    outcome = DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED,
                    authenticated = false,
                    transportRestored = false,
                    helperHealthy = false,
                    settingOwned = cleanupPending,
                    rollbackPartial = cleanupPending,
                )
                val cancelled = (prior ?: AdbRecoveryManager.Result(
                    AdbRecoveryManager.Outcome.CANCELLED,
                )).copy(
                    outcome = AdbRecoveryManager.Outcome.CANCELLED,
                    authenticated = false,
                    wirelessResult = wireless,
                )
                LAST_ADB_RECOVERY_RESULT = cancelled
                mainHandler.post {
                    if (!isAdded || this@SettingsAdbFragment.view !== view) return@post
                    showAdbMessage(localRecoveryResultText(cancelled))
                    setAdbOperationControlsEnabled(!ADB_OPERATION_SCHEDULER.isRunning())
                }
            }
        }
        val btnAdbEnable = view.findViewById<View>(R.id.btnDiLink5AdbEnable)
        btnAdbEnable.setOnClickListener { confirmManualAdbEnablement() }
        val btnAdbPage = view.findViewById<View>(R.id.btnDiLink5AdbPage)
        btnAdbPage.setOnClickListener { confirmDeveloperPageRequest() }
        val btnAdbDiagnostics = view.findViewById<View>(R.id.btnDiLink5AdbDiagnostics)
        btnAdbDiagnostics.setOnClickListener {
            runAdbOperation { client, _ -> client.diagnostics() }
        }
        val btnAdbRestore = view.findViewById<View>(R.id.btnDiLink5AdbRestore)
        btnAdbRestore.setOnClickListener { confirmAdbRestoration() }
        val btnAdbExport = view.findViewById<View>(R.id.btnDiLink5AdbExport)
        btnAdbExport.setOnClickListener { runDeveloperToolsExport() }
        adbOperationControls = listOf(
            btnAdbRecover,
            btnAdbEnable,
            btnAdbPage,
            btnAdbDiagnostics,
            btnAdbRestore,
            btnAdbExport,
        )
        adbOperationObservation = ADB_OPERATION_SCHEDULER.observeRunning {
            mainHandler.post {
                if (!isAdded || this.view == null) return@post
                val running = ADB_OPERATION_SCHEDULER.isRunning()
                setAdbOperationControlsEnabled(!running)
                if (!running) renderManualAdbEnablement()
            }
        }
        val detailObserver: () -> Unit = {
            mainHandler.post {
                if (!isAdded || this.view == null) return@post
                renderAdbResult(LAST_ADB_ENABLE_RESULT, coordinator().lateDetails())
            }
        }
        ADB_DETAIL_OBSERVERS += detailObserver
        adbDetailObservation = AutoCloseable { ADB_DETAIL_OBSERVERS -= detailObserver }
        view.findViewById<View>(R.id.btnSecurityBydAdb).setOnClickListener { openBydAdb() }
        renderManualAdbEnablement()
    }

    override fun onResume() {
        super.onResume()
        renderManualAdbEnablement()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ADVANCED, advancedExpanded)
    }

    override fun onDestroyView() {
        adbOperationObservation?.close()
        adbOperationObservation = null
        adbDetailObservation?.close()
        adbDetailObservation = null
        adbOperationControls = emptyList()
        manualAdbUiGeneration++
        manualAdbModeAuthority = null
        manualAdbModeAvailable = false
        manualAdbCapabilities = BydManualAdbCapabilities.NONE
        tlsRecoveryAvailable = false
        super.onDestroyView()
        adbIoExecutor?.shutdownNow()
        adbIoExecutor = null
        recoveryControlExecutor?.shutdownNow()
        recoveryControlExecutor = null
    }

    private fun renderAdvancedTools() {
        groupAdbAdvancedTools.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        btnAdbAdvancedTools.setText(
            if (advancedExpanded) R.string.settings_adb_hide_advanced
            else R.string.settings_adb_advanced_tools,
        )
        btnAdbAdvancedTools.isSelected = advancedExpanded
    }

    private fun showAdbMessage(message: Int) {
        tvAdbOperationSummary.setText(message)
        tvDiLink5AdbDetails.setText(message)
    }

    private fun showAdbMessage(message: String) {
        tvAdbOperationSummary.text = message
        tvDiLink5AdbDetails.text = message
    }

    private fun renderManualAdbEnablement(afterRefresh: () -> Unit = {}) {
        val refresh = ++manualAdbUiGeneration
        val app = requireContext().applicationContext
        executor().execute {
            val scope = BydManualAdbModeAuthority.currentScope()
            val modeAvailable = scope?.authority() != null
            val capabilities = BydManualAdbCapabilities.forContext(app)
            val tlsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                BydManualAdbModeAuthority.committedDiLink5RecoveryAvailable()
            val recovery = DiLink5AdbRecoveryCoordinator.get(app).recoverySettingsSnapshot()
            val recoveryOwned = AdbRecoveryManager.get(app).hasManualRecoveryOwner()
            val settingsPermission = app.checkSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val dialogEnabledHere = tlsAvailable && DiLink5AdbRecoveryCoordinator.get(app)
                .isDialogAutoConfirmEnabledForCurrentNetwork()
            mainHandler.post {
                if (!isAdded || view == null || refresh != manualAdbUiGeneration) return@post
                manualAdbCapabilities = capabilities
                manualAdbModeAvailable = modeAvailable
                manualAdbModeAuthority = scope?.authority()
                tlsRecoveryAvailable = tlsAvailable
                groupDiLink5AdbEnablement.visibility =
                    if (modeAvailable || tlsAvailable || recoveryOwned) View.VISIBLE else View.GONE
                groupAdbRecovery.visibility =
                    if (modeAvailable || recoveryOwned) View.VISIBLE else View.GONE
                groupDiLink5AdbTlsAdvanced.visibility = if (tlsAvailable) View.VISIBLE else View.GONE
                val manualAvailable = modeAvailable && capabilities.available
                groupAdbManualTools.visibility = if (manualAvailable) View.VISIBLE else View.GONE
                groupAdbManualAdvanced.visibility = if (manualAvailable) View.VISIBLE else View.GONE
                tvAdbRecoveryAvailability.visibility = if (settingsPermission) View.GONE else View.VISIBLE
                updatingRecoverySwitches = true
                swAdbAutomaticRestore.isChecked = recovery?.automaticRestore == true
                swDiLink5AdbDialogAutoConfirm.isChecked = dialogEnabledHere
                updatingRecoverySwitches = false
                swAdbAutomaticRestore.isEnabled = recovery != null &&
                    (modeAvailable || recovery.automaticRestore)
                swDiLink5AdbDialogAutoConfirm.isEnabled = tlsAvailable
                tvDiLink5AdbStatus.setText(
                    when {
                        AdbShellExecutor.isConnected() -> R.string.settings_dilink5_adb_status_authorized
                        AdbShellExecutor.isAuthPending() -> R.string.settings_dilink5_adb_status_pending
                        AdbShellExecutor.wasDiLink5PortClosed() -> R.string.settings_dilink5_adb_status_port_closed
                        else -> R.string.settings_dilink5_adb_status_disconnected
                    },
                )
                setAdbOperationControlsEnabled(!ADB_OPERATION_SCHEDULER.isRunning())
                if (groupDiLink5AdbEnablement.visibility != View.VISIBLE) {
                    showAdbMessage(R.string.settings_adb_tools_unavailable)
                    return@post
                }
                val exportResult = LAST_ADB_EXPORT_RESULT
                val recovered = LAST_ADB_RECOVERY_RESULT
                if (recovered != null) {
                    showAdbMessage(localRecoveryResultText(recovered))
                } else if (exportResult == null) {
                    renderAdbResult(LAST_ADB_ENABLE_RESULT, coordinator().lateDetails())
                } else {
                    renderDeveloperToolsExport(exportResult)
                }
                afterRefresh()
            }
        }
    }

    private fun committedDiLink5RecoveryAvailable(): Boolean =
        BydManualAdbModeAuthority.committedDiLink5RecoveryAvailable()

    private fun recoveryCoordinator(): DiLink5AdbRecoveryCoordinator =
        DiLink5AdbRecoveryCoordinator.get(requireContext().applicationContext)

    private fun localRecoveryManager(): AdbRecoveryManager =
        AdbRecoveryManager.get(requireContext().applicationContext)

    private fun onAutomaticRestoreChanged(enabled: Boolean) {
        val client = localRecoveryManager()
        if (!enabled) {
            executor().execute {
                val saved = client.setAutomaticRestore(false)
                mainHandler.post {
                    if (!isAdded || view == null) return@post
                    renderManualAdbEnablement()
                    if (!saved) showAdbMessage(R.string.settings_adb_recovery_save_failed)
                }
            }
            return
        }
        val authority = manualAdbModeAuthority ?: return
        updatingRecoverySwitches = true
        swAdbAutomaticRestore.isChecked = false
        updatingRecoverySwitches = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_dilink5_adb_enable_confirm_title)
            .setMessage(R.string.settings_adb_recovery_confirm_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_adb_automatic_restore) { _, _ ->
                executor().execute {
                    val saved = BydManualAdbModeAuthority.isCurrent(authority) &&
                        client.setAutomaticRestore(true)
                    mainHandler.post {
                        if (!isAdded || view == null) return@post
                        updatingRecoverySwitches = true
                        swAdbAutomaticRestore.isChecked = saved
                        updatingRecoverySwitches = false
                        if (!saved) showAdbMessage(R.string.settings_adb_recovery_save_failed)
                    }
                }
            }
            .show()
    }

    private fun confirmAdbRecovery() {
        val authority = manualAdbModeAuthority ?: return
        val client = localRecoveryManager()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_dilink5_adb_recover_confirm_title)
            .setMessage(R.string.settings_adb_recovery_confirm_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_adb_recover) { _, _ ->
                showAdbMessage(R.string.settings_dilink5_adb_running)
                val accepted = client.recoverManualAsync(authority) { result ->
                    LAST_ADB_RECOVERY_RESULT = result
                    mainHandler.post {
                        if (!isAdded || view == null) return@post
                        renderManualAdbEnablement {
                            showAdbMessage(localRecoveryResultText(result))
                        }
                    }
                }
                if (!accepted) {
                    showAdbMessage(R.string.settings_dilink5_adb_operation_failed)
                }
                setAdbOperationControlsEnabled(!ADB_OPERATION_SCHEDULER.isRunning())
            }
            .show()
    }

    private fun localRecoveryResultText(result: AdbRecoveryManager.Result): String {
        val message = if (result.outcome == AdbRecoveryManager.Outcome.CANCELLED) {
            if (result.wirelessResult?.rollbackPartial == true) {
                R.string.settings_dilink5_adb_recovery_rollback_partial
            } else R.string.settings_dilink5_adb_recovery_cancelled
        } else result.wirelessResult?.let { recoveryResultText(it) } ?: when (result.outcome) {
            AdbRecoveryManager.Outcome.AUTHENTICATED -> R.string.settings_dilink5_adb_status_authorized
            AdbRecoveryManager.Outcome.SETTING_ENABLED -> R.string.settings_adb_recovery_setting_enabled
            AdbRecoveryManager.Outcome.MISSING_PERMISSION -> R.string.settings_adb_recovery_permission_missing
            AdbRecoveryManager.Outcome.SETTING_FAILED -> R.string.settings_adb_recovery_setting_failed
            AdbRecoveryManager.Outcome.MODE_UNAVAILABLE -> R.string.settings_adb_tools_unavailable
            AdbRecoveryManager.Outcome.CANCELLED -> R.string.settings_dilink5_adb_recovery_cancelled
        }
        val verdict = getString(message)
        return if (result.settingEnabled && !result.authenticated &&
            (result.wirelessResult != null || result.outcome == AdbRecoveryManager.Outcome.CANCELLED)
        ) {
            getString(R.string.settings_adb_recovery_setting_enabled) + "\n\n" + verdict
        } else verdict
    }

    private fun recoveryResultText(result: DiLink5AdbRecoveryCoordinator.Result): Int {
        if (result.rollbackPartial) return R.string.settings_dilink5_adb_recovery_rollback_partial
        return when (result.outcome) {
            DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE ->
                if (result.helperHealthy) R.string.settings_dilink5_adb_recovery_not_needed
                else R.string.settings_dilink5_adb_recovery_helper_unhealthy
            DiLink5AdbRecoveryCoordinator.Outcome.RESTORED ->
                if (result.helperHealthy) R.string.settings_dilink5_adb_recovery_restored
                else R.string.settings_dilink5_adb_recovery_helper_unhealthy
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG ->
                R.string.settings_dilink5_adb_recovery_needs_dialog
            DiLink5AdbRecoveryCoordinator.Outcome.MISSING_PERMISSION ->
                R.string.settings_dilink5_adb_recovery_missing_permission
            DiLink5AdbRecoveryCoordinator.Outcome.NO_VALIDATED_WIFI ->
                R.string.settings_dilink5_adb_recovery_no_wifi
            DiLink5AdbRecoveryCoordinator.Outcome.NO_LISTENER ->
                R.string.settings_dilink5_adb_recovery_no_listener
            DiLink5AdbRecoveryCoordinator.Outcome.AUTHORIZATION_REJECTED,
            DiLink5AdbRecoveryCoordinator.Outcome.TRUST_LOST ->
                R.string.settings_dilink5_adb_recovery_auth_rejected
            DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE ->
                R.string.settings_dilink5_adb_recovery_tls_failed
            DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED ->
                R.string.settings_dilink5_adb_recovery_cancelled
            DiLink5AdbRecoveryCoordinator.Outcome.UNSUPPORTED_API ->
                R.string.settings_dilink5_adb_recovery_unsupported
            DiLink5AdbRecoveryCoordinator.Outcome.BUSY ->
                R.string.settings_dilink5_adb_result_busy
            DiLink5AdbRecoveryCoordinator.Outcome.MODE_UNAVAILABLE,
            DiLink5AdbRecoveryCoordinator.Outcome.TRANSPORT_FAILURE ->
                R.string.settings_dilink5_adb_recovery_transport_failed
        }
    }

    private fun coordinator(): DiLink5AdbEnableCoordinator = synchronized(ADB_OWNER_LOCK) {
        SHARED_ADB_ENABLE_COORDINATOR ?: DiLink5AdbEnableCoordinator.forContext(
            requireContext().applicationContext,
        ) {
            ADB_DETAIL_OBSERVERS.forEach { observer -> observer() }
        }.also { SHARED_ADB_ENABLE_COORDINATOR = it }
    }

    private fun confirmManualAdbEnablement() {
        val authority = manualAdbModeAuthority ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_dilink5_adb_enable_confirm_title)
            .setMessage(R.string.settings_dilink5_adb_enable_confirm_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_adb_enable) { _, _ ->
                runAdbOperation(authority) { client, admitted -> client.enable(admitted) }
            }
            .show()
    }

    private fun confirmDeveloperPageRequest() {
        val authority = manualAdbModeAuthority ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_dilink5_adb_enable_confirm_title)
            .setMessage(R.string.settings_dilink5_adb_enable_confirm_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_adb_developer_page) { _, _ ->
                runAdbOperation(authority) { client, admitted -> client.requestDeveloperPage(admitted) }
            }
            .show()
    }

    private fun confirmAdbRestoration() {
        val authority = manualAdbModeAuthority ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_dilink5_adb_restore_confirm_title)
            .setMessage(R.string.settings_dilink5_adb_restore_confirm_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_adb_restore) { _, _ ->
                runAdbOperation(authority) { client, admitted -> client.restore(admitted) }
            }
            .show()
    }

    private fun runAdbOperation(
        expectedModeAuthority: String? = manualAdbModeAuthority,
        operation: (DiLink5AdbEnableCoordinator, String) -> DiLink5AdbEnableCoordinator.Result,
    ) {
        if (groupDiLink5AdbEnablement.visibility != View.VISIBLE) return
        val admittedAuthority = expectedModeAuthority ?: return
        val client = coordinator()
        val accepted = ADB_OPERATION_SCHEDULER.submit(
            operation = { operation(client, admittedAuthority) },
            onComplete = { result ->
                LAST_ADB_ENABLE_RESULT = result
                if (!isAdded || view == null) return@submit
                renderManualAdbEnablement()
                if (result == null) {
                    showAdbMessage(R.string.settings_dilink5_adb_operation_failed)
                } else {
                    renderAdbResult(result, client.lateDetails())
                }
            },
        )
        if (!accepted) {
            val busy = DiLink5AdbEnableCoordinator.Result(
                DiLink5AdbEnableCoordinator.Stage.BUSY,
                AdbShellExecutor.isConnected(),
                emptyList(),
            )
            LAST_ADB_ENABLE_RESULT = busy
            renderAdbResult(busy, client.lateDetails())
            return
        }
        LAST_ADB_EXPORT_RESULT = null
        LAST_ADB_RECOVERY_RESULT = null
        setAdbOperationControlsEnabled(false)
        showAdbMessage(R.string.settings_dilink5_adb_running)
    }

    private fun setAdbOperationControlsEnabled(enabled: Boolean) {
        val localBusy = localRecoveryManager().hasLocalManualRequest()
        adbOperationControls.forEach {
            val compatible = when (it.id) {
                R.id.btnAdbRecover -> manualAdbModeAvailable
                R.id.btnDiLink5AdbEnable -> manualAdbModeAvailable && manualAdbCapabilities.canEnable
                R.id.btnDiLink5AdbPage -> manualAdbModeAvailable && manualAdbCapabilities.developerPage
                R.id.btnDiLink5AdbExport -> manualAdbModeAvailable && manualAdbCapabilities.developerToolsExport
                else -> manualAdbModeAvailable && manualAdbCapabilities.available
            }
            it.isEnabled = enabled && compatible && !localBusy
        }
        // Manual OEM work must not remove cancellation access to an independent TLS owner.
        btnAdbCancelRecovery.isEnabled = localRecoveryManager().hasManualRecoveryOwner()
        btnAdbCancelRecovery.visibility =
            if (btnAdbCancelRecovery.isEnabled) View.VISIBLE else View.GONE
    }

    private fun renderAdbResult(
        result: DiLink5AdbEnableCoordinator.Result?,
        lateDetails: List<DiLink5AdbEnableCoordinator.Detail>,
    ) {
        tvAdbOperationSummary.text = result?.let {
            adbStageText(DiLink5AdbResultText.displayStage(it, lateDetails))
        } ?: getString(R.string.settings_dilink5_adb_no_attempt)
        tvDiLink5AdbDetails.text = buildString {
            result?.let {
                append(adbStageText(DiLink5AdbResultText.displayStage(it, lateDetails)))
                it.prerequisiteStage?.let { prerequisite ->
                    append("\n")
                    append(getString(
                        R.string.settings_dilink5_adb_prerequisite,
                        adbStageText(prerequisite),
                    ))
                }
                it.mutationWorker?.let { worker ->
                    append("\n")
                    append(getString(
                        R.string.settings_dilink5_adb_mutation_target,
                        worker,
                        it.mutationPath.orEmpty(),
                    ))
                }
            }
            (result?.details.orEmpty() + lateDetails).forEach { detail ->
                if (isNotEmpty()) append("\n")
                append(adbDetailText(detail))
            }
            result?.let {
                if (isNotEmpty()) append("\n")
                append(if (it.authenticatedAdb) {
                    getString(R.string.settings_dilink5_adb_authenticated_yes)
                } else {
                    getString(R.string.settings_dilink5_adb_authenticated_no)
                })
            }
        }
    }

    private fun adbDetailText(detail: DiLink5AdbEnableCoordinator.Detail): String {
        val text = DiLink5AdbResultText.detail(detail)
        val arguments = text.arguments.map { argument ->
            when (argument) {
                is DiLink5AdbText.Argument.Resource -> getString(argument.resourceId)
                is DiLink5AdbText.Argument.Value -> argument.value
            }
        }.toTypedArray()
        return getString(text.resourceId, *arguments)
    }

    private fun adbStageText(stage: DiLink5AdbEnableCoordinator.Stage): String = getString(when (stage) {
        DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED -> R.string.settings_dilink5_adb_result_flag_confirmed
        DiLink5AdbEnableCoordinator.Stage.FLAG_ACCEPTED -> R.string.settings_dilink5_adb_result_flag_accepted
        DiLink5AdbEnableCoordinator.Stage.FLAG_ALREADY_ENABLED -> R.string.settings_dilink5_adb_result_flag_already_enabled
        DiLink5AdbEnableCoordinator.Stage.FLAG_UNCERTAIN_TRANSITION -> R.string.settings_dilink5_adb_result_flag_uncertain
        DiLink5AdbEnableCoordinator.Stage.FLAG_REPLY_LOST -> R.string.settings_dilink5_adb_detail_worker_write_unconfirmed
        DiLink5AdbEnableCoordinator.Stage.FLAG_EXISTS_ONLY -> R.string.settings_dilink5_adb_result_flag_exists
        DiLink5AdbEnableCoordinator.Stage.FLAG_CONTENT_ZERO -> R.string.settings_dilink5_adb_result_flag_zero
        DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_CONFIRMED -> R.string.settings_dilink5_adb_result_log_confirmed
        DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED -> R.string.settings_dilink5_adb_result_log_unconfirmed
        DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED -> R.string.settings_dilink5_adb_result_strategy_confirmed
        DiLink5AdbEnableCoordinator.Stage.STRATEGY_NOT_CONFIRMED -> R.string.settings_dilink5_adb_result_strategy_unconfirmed
        DiLink5AdbEnableCoordinator.Stage.DIAGNOSTICS -> R.string.settings_dilink5_adb_result_diagnostics
        DiLink5AdbEnableCoordinator.Stage.CANCELLED -> R.string.settings_dilink5_adb_result_cancelled
        DiLink5AdbEnableCoordinator.Stage.BUSY -> R.string.settings_dilink5_adb_result_busy
        DiLink5AdbEnableCoordinator.Stage.UNAVAILABLE -> R.string.settings_dilink5_adb_result_unavailable
        DiLink5AdbEnableCoordinator.Stage.RESTORED -> R.string.settings_dilink5_adb_result_restored
        DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION -> R.string.settings_dilink5_adb_result_partial_restoration
        DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_ACCEPTED -> R.string.settings_dilink5_adb_result_page_accepted
        DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_UNAVAILABLE -> R.string.settings_dilink5_adb_result_page_unavailable
    })

    private fun runDeveloperToolsExport() {
        if (groupDiLink5AdbEnablement.visibility != View.VISIBLE) return
        LAST_ADB_RECOVERY_RESULT = null
        val appContext = requireContext().applicationContext
        val accepted = ADB_OPERATION_SCHEDULER.submit(
            operation = { DiLink5DeveloperToolsExport.export(appContext) },
            onComplete = { result ->
                LAST_ADB_EXPORT_RESULT = result
                if (!isAdded || view == null) return@submit
                if (result == null) {
                    showAdbMessage(R.string.settings_dilink5_adb_export_failed)
                } else {
                    renderDeveloperToolsExport(result)
                }
            },
        )
        if (!accepted) {
            val busy = DiLink5AdbEnableCoordinator.Result(
                DiLink5AdbEnableCoordinator.Stage.BUSY,
                AdbShellExecutor.isConnected(),
                emptyList(),
            )
            LAST_ADB_ENABLE_RESULT = busy
            renderAdbResult(busy, coordinator().lateDetails())
            return
        }
        LAST_ADB_ENABLE_RESULT = null
        LAST_ADB_EXPORT_RESULT = null
        LAST_ADB_RECOVERY_RESULT = null
        setAdbOperationControlsEnabled(false)
        showAdbMessage(R.string.settings_dilink5_adb_running)
    }

    private fun renderDeveloperToolsExport(result: DiLink5DeveloperToolsExport.Result) {
        showAdbMessage(when (result.outcome) {
            DiLink5DeveloperToolsExport.Outcome.EXPORTED_BASE_APK ->
                R.string.settings_dilink5_adb_export_complete
            DiLink5DeveloperToolsExport.Outcome.UNSUPPORTED ->
                R.string.settings_dilink5_adb_export_unsupported
            DiLink5DeveloperToolsExport.Outcome.PACKAGE_UNAVAILABLE ->
                R.string.settings_dilink5_adb_export_package_unavailable
            DiLink5DeveloperToolsExport.Outcome.FAILED ->
                R.string.settings_dilink5_adb_export_failed
            DiLink5DeveloperToolsExport.Outcome.FAILED_CLEANUP ->
                R.string.settings_dilink5_adb_export_cleanup_failed
        })
    }

    /** Open one of the head unit's built-in BYD configuration screens. */
    private fun launchBydScreen(packageName: String, activityName: String, verify: Boolean): Boolean {
        val intent = Intent().setComponent(ComponentName(packageName, activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (verify) intent.putExtra("Development.verify.requestCode", true)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.d("SettingsAdb", "BYD screen unavailable: $activityName", e)
            false
        }
    }

    private fun openBydAdb() {
        val packageName = "com.byd.byddevelopmenttools"
        if (launchBydScreen(packageName, "$packageName.MainActivity", true) ||
            launchBydScreen(packageName, "$packageName.VerificationActivity", true)) return
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(requireContext(), R.string.settings_security_byd_adb_hint,
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.settings_security_byd_adb_unavailable,
                Toast.LENGTH_LONG).show()
        }
    }

    private fun executor(): ExecutorService =
        adbIoExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SettingsAdbIO").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }.also { adbIoExecutor = it }

    private fun recoveryControlExecutor(): ExecutorService =
        recoveryControlExecutor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "AdbRecoveryControl").apply { isDaemon = true }
        }.also { recoveryControlExecutor = it }

    private companion object {
        private const val KEY_ADVANCED = "adb_advanced_expanded"
        private val ADB_OWNER_LOCK = Any()
        private val ADB_DETAIL_OBSERVERS = CopyOnWriteArraySet<() -> Unit>()
        @Volatile private var SHARED_ADB_ENABLE_COORDINATOR: DiLink5AdbEnableCoordinator? = null
        @Volatile private var LAST_ADB_ENABLE_RESULT: DiLink5AdbEnableCoordinator.Result? = null
        @Volatile private var LAST_ADB_EXPORT_RESULT: DiLink5DeveloperToolsExport.Result? = null
        @Volatile private var LAST_ADB_RECOVERY_RESULT: AdbRecoveryManager.Result? = null
        private val ADB_OPERATION_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DiLink5ManualAdb").apply { isDaemon = true }
        }
        private val ADB_OPERATION_SCHEDULER = DiLink5AdbOperationScheduler(
            ADB_OPERATION_EXECUTOR,
            java.util.concurrent.Executor { command ->
                Handler(Looper.getMainLooper()).post(command)
            },
        )

    }
}
