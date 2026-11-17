package com.overdrive.app.navmap.cluster

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayoutMapSessionClientTest {
    private fun status(generation: Long = 71, phase: String = "active",
                       active: Boolean = true, pkg: String = "com.overdrive.app",
                       display: Int = 14): JSONObject =
        JSONObject().put("generation", generation).put("phase", phase).put("active", active)
            .put("slots", JSONArray().put(JSONObject().put("package", pkg).put("displayId", display)))

    @Test fun stagedMapWaitsForCommitButCancellationFinishesIt() {
        assertNull(LayoutMapSessionClient.matchesSession(status(phase = "starting", active = false), 71, 14, 14))
        assertEquals(false, LayoutMapSessionClient.matchesSession(status(phase = "stopping", active = false), 71, 14, 14))
        assertEquals(false, LayoutMapSessionClient.matchesSession(status(generation = 72, phase = "starting"), 71, 14, 14))
    }

    @Test fun mapCannotContinueOnDefaultOrReusedDisplay() {
        assertEquals(false, LayoutMapSessionClient.matchesSession(status(), 71, 14, 0))
        assertEquals(false, LayoutMapSessionClient.matchesSession(status(display = 15), 71, 14, 14))
        assertEquals(false, LayoutMapSessionClient.matchesSession(status(pkg = "com.example.music"), 71, 14, 14))
        assertEquals(false, LayoutMapSessionClient.matchesSession(status(), 0, 14, 14))
    }

    @Test fun onlyCommittedOwnSlotKeepsActivityAlive() {
        assertEquals(true, LayoutMapSessionClient.matchesSession(status(), 71, 14, 14))
        assertEquals(false, LayoutMapSessionClient.matchesSession(
            status().put("slots", JSONArray().put(JSONObject.NULL)), 71, 14, 14))
    }
}
