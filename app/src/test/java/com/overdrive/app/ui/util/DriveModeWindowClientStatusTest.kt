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
}
