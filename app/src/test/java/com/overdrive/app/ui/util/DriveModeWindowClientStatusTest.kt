package com.overdrive.app.ui.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveModeWindowClientStatusTest {

    private fun status(enabled: Boolean, last: String, available: Boolean = true) =
        JSONObject().put("success", true).put("enabled", enabled)
            .put("lastResult", last).put("freeformAvailable", available)

    @Test
    fun mapsTheDaemonStatusToALine() {
        assertEquals(DriveModeWindowClient.Status.ERROR, DriveModeWindowClient.statusOf(null))
        assertEquals(DriveModeWindowClient.Status.OFF,
            DriveModeWindowClient.statusOf(status(false, "floating")))
        assertEquals(DriveModeWindowClient.Status.FLOATING,
            DriveModeWindowClient.statusOf(status(true, "floating")))
        assertEquals(DriveModeWindowClient.Status.WAITING,
            DriveModeWindowClient.statusOf(status(true, "enabled")))
        assertEquals(DriveModeWindowClient.Status.NEEDS_RESTART,
            DriveModeWindowClient.statusOf(status(true, "not_honoured")))
        assertEquals(DriveModeWindowClient.Status.NEEDS_RESTART,
            DriveModeWindowClient.statusOf(status(true, "enabled", available = false)))
    }

    @Test
    fun aFailedTurnOnOrOffIsShownAsFailedNotOff() {
        val failedOn = JSONObject().put("success", false).put("platform", true).put("failed", true)
            .put("enabled", false).put("lastResult", "setting_unreadable")
        assertEquals(DriveModeWindowClient.Status.FAILED, DriveModeWindowClient.statusOf(failedOn))
        val failedOff = JSONObject(failedOn.toString()).put("enabled", true)
            .put("lastResult", "window_restore_failed")
        assertEquals(DriveModeWindowClient.Status.FAILED, DriveModeWindowClient.statusOf(failedOff))
    }
}
