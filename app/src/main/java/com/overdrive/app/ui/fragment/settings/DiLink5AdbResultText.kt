package com.overdrive.app.ui.fragment.settings

import androidx.annotation.StringRes
import com.overdrive.app.R
import com.overdrive.app.launcher.dilink5.enable.DiLink5AdbEnableCoordinator
import com.overdrive.app.launcher.dilink5.enable.DiLink5AdbEnableStateStore
import com.overdrive.app.launcher.dilink5.enable.DiLink5LogToolProtocol
import com.overdrive.app.launcher.dilink5.enable.DiLink5StrategyServiceClient

internal data class DiLink5AdbText(
    @StringRes val resourceId: Int,
    val arguments: List<Argument> = emptyList(),
) {
    sealed interface Argument {
        data class Value(val value: Any) : Argument
        data class Resource(@StringRes val resourceId: Int) : Argument
    }
}

internal object DiLink5AdbResultText {
    fun displayStage(
        result: DiLink5AdbEnableCoordinator.Result,
        currentDetails: List<DiLink5AdbEnableCoordinator.Detail>,
    ): DiLink5AdbEnableCoordinator.Stage =
        if (result.stage == DiLink5AdbEnableCoordinator.Stage.RESTORED &&
            currentDetails.any {
                it is DiLink5AdbEnableCoordinator.Detail.FlagOwnership ||
                    it == DiLink5AdbEnableCoordinator.Detail.OwnershipNotDurable
            }
        ) DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION else result.stage

    fun detail(detail: DiLink5AdbEnableCoordinator.Detail): DiLink5AdbText = when (detail) {
        is DiLink5AdbEnableCoordinator.Detail.CandidateAvailability -> packageAvailability(
            detail.packageName,
            detail.installed,
        )
        is DiLink5AdbEnableCoordinator.Detail.PackageAvailability -> packageAvailability(
            detail.packageName,
            detail.installed,
        )
        is DiLink5AdbEnableCoordinator.Detail.WorkerTimeout -> text(
            R.string.settings_dilink5_adb_detail_worker_timeout,
            detail.packageName,
        )
        is DiLink5AdbEnableCoordinator.Detail.WorkerAttempt -> DiLink5AdbText(
            R.string.settings_dilink5_adb_detail_worker_result,
            listOf(
                value(detail.packageName),
                resource(workerOutcome(detail.outcome)),
            ),
        )
        is DiLink5AdbEnableCoordinator.Detail.FlagOwnership -> text(
            when (detail.source) {
                DiLink5AdbEnableStateStore.OwnershipSource.ORDINARY ->
                    R.string.settings_dilink5_adb_mutation_target
                DiLink5AdbEnableStateStore.OwnershipSource.DISCONNECT_RETAINED ->
                    if (detail.certainty == DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED) {
                        R.string.settings_dilink5_adb_detail_ownership_retained
                    } else {
                        R.string.settings_dilink5_adb_mutation_target
                    }
                DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT ->
                    if (detail.certainty == DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED) {
                        R.string.settings_dilink5_adb_detail_late_confirmed
                    } else {
                        R.string.settings_dilink5_adb_detail_late_uncertain
                    }
            },
            detail.workerProcess,
            detail.path,
        )
        DiLink5AdbEnableCoordinator.Detail.LogToolUnavailable ->
            text(R.string.settings_dilink5_adb_detail_log_unavailable)
        is DiLink5AdbEnableCoordinator.Detail.PropertyRequest -> text(
            when {
                detail.outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED ->
                    R.string.settings_dilink5_adb_detail_property_confirmed
                detail.outcome == DiLink5LogToolProtocol.Outcome.REJECTED ->
                    R.string.settings_dilink5_adb_detail_property_rejected
                detail.outcome == DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY ->
                    R.string.settings_dilink5_adb_detail_property_unknown
                detail.requestSent -> R.string.settings_dilink5_adb_detail_property_sent_unconfirmed
                else -> R.string.settings_dilink5_adb_detail_property_unavailable
            },
        )
        is DiLink5AdbEnableCoordinator.Detail.DeveloperActivityRequest -> text(
            when (detail.outcome) {
                DiLink5LogToolProtocol.Outcome.CONFIRMED ->
                    R.string.settings_dilink5_adb_detail_activity_confirmed
                DiLink5LogToolProtocol.Outcome.REJECTED ->
                    R.string.settings_dilink5_adb_detail_activity_rejected
                DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY ->
                    R.string.settings_dilink5_adb_detail_activity_unknown
                DiLink5LogToolProtocol.Outcome.UNAVAILABLE,
                DiLink5LogToolProtocol.Outcome.CANCELLED ->
                    R.string.settings_dilink5_adb_detail_activity_unavailable
            },
        )
        DiLink5AdbEnableCoordinator.Detail.StrategyFallback ->
            text(R.string.settings_dilink5_adb_detail_strategy_fallback)
        is DiLink5AdbEnableCoordinator.Detail.StrategyChange -> text(
            when (detail.outcome) {
                DiLink5StrategyServiceClient.Outcome.CONFIRMED ->
                    R.string.settings_dilink5_adb_result_strategy_confirmed
                DiLink5StrategyServiceClient.Outcome.CONNECTED_NOT_CONFIRMED ->
                    R.string.settings_dilink5_adb_detail_strategy_connected_unconfirmed
                DiLink5StrategyServiceClient.Outcome.NOT_DISPATCHED,
                DiLink5StrategyServiceClient.Outcome.UNAVAILABLE,
                DiLink5StrategyServiceClient.Outcome.CANCELLED ->
                    R.string.settings_dilink5_adb_detail_strategy_unavailable
            },
        )
        is DiLink5AdbEnableCoordinator.Detail.FlagRestoration -> text(
            when (detail.outcome) {
                DiLink5AdbEnableCoordinator.RestorationOutcome.CONFIRMED ->
                    R.string.settings_dilink5_adb_detail_flag_restored
                DiLink5AdbEnableCoordinator.RestorationOutcome.NOT_CONFIRMED ->
                    R.string.settings_dilink5_adb_detail_flag_restore_unconfirmed
                DiLink5AdbEnableCoordinator.RestorationOutcome.UNSUPPORTED ->
                    R.string.settings_dilink5_adb_detail_flag_restore_unsupported
            },
            detail.workerProcess,
            detail.path,
        )
        is DiLink5AdbEnableCoordinator.Detail.StrategyRestoration -> text(
            when (detail.outcome) {
                DiLink5AdbEnableCoordinator.RestorationOutcome.CONFIRMED ->
                    R.string.settings_dilink5_adb_detail_strategy_restored
                DiLink5AdbEnableCoordinator.RestorationOutcome.NOT_CONFIRMED,
                DiLink5AdbEnableCoordinator.RestorationOutcome.UNSUPPORTED ->
                    R.string.settings_dilink5_adb_detail_strategy_restore_unconfirmed
            },
        )
        DiLink5AdbEnableCoordinator.Detail.PropertyNotRestorable ->
            text(R.string.settings_dilink5_adb_detail_property_not_restorable)
        DiLink5AdbEnableCoordinator.Detail.NoOwnedRestorableChange ->
            text(R.string.settings_dilink5_adb_detail_no_owned_change)
        DiLink5AdbEnableCoordinator.Detail.OwnershipNotDurable ->
            text(R.string.settings_byd_adb_ownership_not_durable)
        is DiLink5AdbEnableCoordinator.Detail.PageRequest -> text(
            when (detail.result) {
                DiLink5AdbEnableCoordinator.PageResult.TARGETED_INTENT_ACCEPTED ->
                    R.string.settings_dilink5_adb_detail_page_targeted
                DiLink5AdbEnableCoordinator.PageResult.GENERIC_INTENT_ACCEPTED ->
                    R.string.settings_dilink5_adb_detail_page_generic
                DiLink5AdbEnableCoordinator.PageResult.UNAVAILABLE ->
                    R.string.settings_dilink5_adb_result_page_unavailable
            },
        )
        is DiLink5AdbEnableCoordinator.Detail.AdbStatusSnapshot -> text(
            when (detail.status) {
                DiLink5AdbEnableCoordinator.AdbStatus.CONNECTED_AUTHORIZED ->
                    R.string.settings_dilink5_adb_status_authorized
                DiLink5AdbEnableCoordinator.AdbStatus.AUTHORIZATION_PENDING ->
                    R.string.settings_dilink5_adb_status_pending
                DiLink5AdbEnableCoordinator.AdbStatus.DISCONNECTED ->
                    R.string.settings_dilink5_adb_status_disconnected
                DiLink5AdbEnableCoordinator.AdbStatus.PORT_CLOSED ->
                    R.string.settings_dilink5_adb_status_port_closed
            },
        )
        is DiLink5AdbEnableCoordinator.Detail.DeviceSnapshot -> text(
            R.string.settings_dilink5_adb_detail_device,
            detail.model,
            detail.build,
            detail.sdk,
        )
    }

    private fun packageAvailability(packageName: String, installed: Boolean) = text(
        if (installed) R.string.settings_dilink5_adb_detail_package_installed
        else R.string.settings_dilink5_adb_detail_package_missing,
        packageName,
    )

    @StringRes
    private fun workerOutcome(outcome: DiLink5AdbEnableCoordinator.WorkerOutcome): Int = when (outcome) {
        DiLink5AdbEnableCoordinator.WorkerOutcome.WRITE_AND_VALUE_CONFIRMED ->
            R.string.settings_dilink5_adb_result_flag_confirmed
        DiLink5AdbEnableCoordinator.WorkerOutcome.WRITE_ACCEPTED ->
            R.string.settings_dilink5_adb_result_flag_accepted
        DiLink5AdbEnableCoordinator.WorkerOutcome.UNCERTAIN_TRANSITION ->
            R.string.settings_dilink5_adb_result_flag_uncertain
        DiLink5AdbEnableCoordinator.WorkerOutcome.WRITE_REPLY_LOST ->
            R.string.settings_dilink5_adb_detail_worker_write_unconfirmed
        DiLink5AdbEnableCoordinator.WorkerOutcome.ALREADY_ENABLED ->
            R.string.settings_dilink5_adb_result_flag_already_enabled
        DiLink5AdbEnableCoordinator.WorkerOutcome.CONTENT_ZERO ->
            R.string.settings_dilink5_adb_result_flag_zero
        DiLink5AdbEnableCoordinator.WorkerOutcome.EXISTS_VALUE_UNKNOWN ->
            R.string.settings_dilink5_adb_result_flag_exists
        DiLink5AdbEnableCoordinator.WorkerOutcome.TRANSPORT_FAILED ->
            R.string.settings_dilink5_adb_detail_worker_transport_failed
        DiLink5AdbEnableCoordinator.WorkerOutcome.WRITE_NOT_CONFIRMED ->
            R.string.settings_dilink5_adb_detail_worker_write_unconfirmed
    }

    private fun text(@StringRes resourceId: Int, vararg arguments: Any) = DiLink5AdbText(
        resourceId,
        arguments.map(::value),
    )

    private fun value(value: Any) = DiLink5AdbText.Argument.Value(value)
    private fun resource(@StringRes resourceId: Int) = DiLink5AdbText.Argument.Resource(resourceId)
}
