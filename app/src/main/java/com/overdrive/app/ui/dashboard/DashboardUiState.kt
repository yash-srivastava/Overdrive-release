package com.overdrive.app.ui.dashboard

import androidx.annotation.DrawableRes

data class DashboardUiState(
    val vehicle: VehicleState = VehicleState.Loading,
    val recordings: RecordingState = RecordingState.Loading,
    val activity: ActivityState = ActivityState.Loading,
    val remoteExpanded: Boolean = false,
) {
    sealed interface VehicleState {
        data object Loading : VehicleState
        data class Ready(val snapshot: DashboardVehicleSnapshot) : VehicleState
        data class Unavailable(val reason: DashboardStatusResult.Reason) : VehicleState
    }

    sealed interface RecordingState {
        data object Loading : RecordingState
        data class Ready(
            val todayClipCount: Int?,
            val storage: StorageSummary?,
        ) : RecordingState
        data object Unavailable : RecordingState
    }

    data class StorageSummary(
        val usedBytes: Long,
        val availableBytes: Long,
        val totalBytes: Long,
    ) {
        val usagePercent: Int
            get() = if (totalBytes > 0L) {
                ((usedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt()
            } else {
                0
            }
    }

    sealed interface ActivityState {
        data object Loading : ActivityState
        data class Ready(val rows: List<ActivityRow>) : ActivityState
        data object Unavailable : ActivityState
    }

    /**
     * A rendered activity line. `text` stays a CharSequence so the insight
     * builders' emphasis spans survive into the card. `icon` is 0 when the
     * source had none.
     */
    data class ActivityRow(
        val text: CharSequence,
        @DrawableRes val icon: Int = 0,
    )
}

object DashboardStateReducer {
    fun status(
        state: DashboardUiState,
        result: DashboardStatusResult,
    ): DashboardUiState {
        val vehicle = when (result) {
            DashboardStatusResult.Loading ->
                DashboardUiState.VehicleState.Loading
            is DashboardStatusResult.Available ->
                DashboardUiState.VehicleState.Ready(result.snapshot)
            is DashboardStatusResult.Unavailable ->
                DashboardUiState.VehicleState.Unavailable(result.reason)
        }
        return state.copy(vehicle = vehicle)
    }

    fun statusLoading(state: DashboardUiState): DashboardUiState =
        state.copy(vehicle = DashboardUiState.VehicleState.Loading)

    fun recordings(
        state: DashboardUiState,
        todayClipCount: Int?,
        storage: DashboardUiState.StorageSummary?,
    ): DashboardUiState {
        val next = if (todayClipCount == null && storage == null) {
            DashboardUiState.RecordingState.Unavailable
        } else {
            DashboardUiState.RecordingState.Ready(todayClipCount, storage)
        }
        return state.copy(recordings = next)
    }

    fun activity(
        state: DashboardUiState,
        rows: List<DashboardUiState.ActivityRow>?,
    ): DashboardUiState {
        val next = if (rows == null) {
            DashboardUiState.ActivityState.Unavailable
        } else {
            DashboardUiState.ActivityState.Ready(
                rows.asSequence()
                    .map { it.copy(text = it.text.trim()) }
                    .filter { it.text.isNotEmpty() }
                    // Spanned text is not comparable by value, so dedupe on the
                    // plain string.
                    .distinctBy { it.text.toString() }
                    .take(3)
                    .toList()
            )
        }
        return state.copy(activity = next)
    }

    fun remoteExpanded(
        state: DashboardUiState,
        expanded: Boolean,
    ): DashboardUiState = state.copy(remoteExpanded = expanded)
}
