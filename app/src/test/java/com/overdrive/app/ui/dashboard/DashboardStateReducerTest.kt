package com.overdrive.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStateReducerTest {

    @Test
    fun independentSectionsSurviveVehicleRefreshFailure() {
        val storage = DashboardUiState.StorageSummary(
            usedBytes = 25,
            availableBytes = 75,
            totalBytes = 100,
        )
        var state = DashboardStateReducer.recordings(
            DashboardUiState(),
            todayClipCount = 0,
            storage = storage,
        )
        state = DashboardStateReducer.activity(
            state,
            activityRows("Latest surveillance event", "Last charge"),
        )
        state = DashboardStateReducer.status(
            state,
            DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.SERVICE_UNAVAILABLE
            ),
        )

        assertTrue(state.vehicle is DashboardUiState.VehicleState.Unavailable)
        val recordings = state.recordings as DashboardUiState.RecordingState.Ready
        assertEquals(0, recordings.todayClipCount)
        assertEquals(25, recordings.storage?.usagePercent)
        assertEquals(
            listOf("Latest surveillance event", "Last charge"),
            activityTexts(state),
        )
    }

    @Test
    fun vehicleLoadingResultDoesNotResetOtherDashboardSections() {
        val recordings = DashboardStateReducer.recordings(
            DashboardUiState(),
            todayClipCount = 4,
            storage = null,
        )

        val state = DashboardStateReducer.status(
            recordings,
            DashboardStatusResult.Loading,
        )

        assertTrue(state.vehicle is DashboardUiState.VehicleState.Loading)
        assertEquals(recordings.recordings, state.recordings)
    }

    @Test
    fun activityRowsAreStableDeduplicatedAndBounded() {
        val state = DashboardStateReducer.activity(
            DashboardUiState(),
            activityRows(" Alert ", "Charge", "Alert", "Parking", "Extra"),
        )

        assertEquals(
            listOf("Alert", "Charge", "Parking"),
            activityTexts(state),
        )
    }

    @Test
    fun activityRowsKeepTheIconOfTheirSourceInsight() {
        val state = DashboardStateReducer.activity(
            DashboardUiState(),
            listOf(
                DashboardUiState.ActivityRow(" Alert ", icon = 7),
                DashboardUiState.ActivityRow("Charge"),
            ),
        )

        val rows = (state.activity as DashboardUiState.ActivityState.Ready).rows
        assertEquals(listOf(7, 0), rows.map { it.icon })
        assertEquals("Alert", rows.first().text.toString())
    }

    @Test
    fun remoteExpansionDoesNotResetContent() {
        val snapshot = DashboardVehicleSnapshot(
            socPercent = 65.0,
            range = DashboardDistance(240, DashboardDistance.Unit.KILOMETRES),
            charging = null,
            activeRecordingCameras = 1,
        )
        val ready = DashboardStateReducer.status(
            DashboardUiState(),
            DashboardStatusResult.Available(snapshot),
        )
        val expanded = DashboardStateReducer.remoteExpanded(ready, true)

        assertTrue(expanded.remoteExpanded)
        assertEquals(
            snapshot,
            (expanded.vehicle as DashboardUiState.VehicleState.Ready).snapshot,
        )
        assertFalse(
            DashboardStateReducer.remoteExpanded(expanded, false).remoteExpanded
        )
    }

    private fun activityRows(vararg texts: String) =
        texts.map { DashboardUiState.ActivityRow(it) }

    private fun activityTexts(state: DashboardUiState) =
        (state.activity as DashboardUiState.ActivityState.Ready)
            .rows
            .map { it.text.toString() }
}
