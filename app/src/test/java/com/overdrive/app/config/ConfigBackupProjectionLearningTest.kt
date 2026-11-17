package com.overdrive.app.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupProjectionLearningTest {
    @Test
    fun exportFilterDropsDeviceLearningAndKeepsSavedProjectionChoices() {
        val live = JSONObject().put("projection", JSONObject()
            .put("clusterDensitySuppression", JSONObject().put("unsafePackages", "local-learning"))
            .put("di5ProjectionTransport", "hosted")
            .put("di5HostedXOffsetPercent", 0)
            .put("clusterAppDpi", JSONObject().put("com.example.navigator", 360)))
        val exportSnapshot = JSONObject(live.toString())
        stripEphemeral(exportSnapshot)
        val exported = exportSnapshot.getJSONObject("projection")
        assertFalse(exported.has("clusterDensitySuppression"))
        assertEquals("hosted", exported.getString("di5ProjectionTransport"))
        assertEquals(0, exported.getInt("di5HostedXOffsetPercent"))
        assertEquals(360, exported.getJSONObject("clusterAppDpi").getInt("com.example.navigator"))
        assertTrue(live.getJSONObject("projection").has("clusterDensitySuppression"))
    }

    @Test
    fun importingForeignLearningLeavesTheLiveCarsVerdictAndMergesOnlyPreferences() {
        val localVerdict = JSONObject().put("probeGeneration", 2)
            .put("unsafePackages", JSONObject().put("com.local.navigator", true))
        val live = JSONObject().put("projection", JSONObject()
            .put("clusterDensitySuppression", localVerdict)
            .put("di5ProjectionTransport", "hosted")
            .put("di5HostedXOffsetPercent", 0))
        val incoming = JSONObject().put("projection", JSONObject()
            .put("clusterDensitySuppression", JSONObject()
                .put("unsafePackages", JSONObject().put("com.foreign.navigator", true)))
            .put("di5ProjectionTransport", "direct")
            .put("di5HostedXOffsetPercent", 100))

        // Use the same filter and merge invoked by production applyBundleSettings.
        stripEphemeral(incoming)
        ConfigBackupService::class.java.getDeclaredMethod(
            "deepMergeInto", JSONObject::class.java, JSONObject::class.java,
            String::class.java, Int::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }.invoke(ConfigBackupService, live, incoming, "", 0)

        val projection = live.getJSONObject("projection")
        assertEquals(localVerdict.toString(), projection.getJSONObject("clusterDensitySuppression").toString())
        assertEquals("direct", projection.getString("di5ProjectionTransport"))
        assertEquals(100, projection.getInt("di5HostedXOffsetPercent"))
        assertFalse(incoming.getJSONObject("projection").has("clusterDensitySuppression"))
    }

    private fun stripEphemeral(snapshot: JSONObject) {
        ConfigBackupService::class.java.getDeclaredMethod("stripEphemeral", JSONObject::class.java)
            .apply { isAccessible = true }.invoke(ConfigBackupService, snapshot)
    }
}
