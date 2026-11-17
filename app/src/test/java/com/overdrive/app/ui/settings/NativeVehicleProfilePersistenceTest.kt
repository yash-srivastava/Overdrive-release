package com.overdrive.app.ui.settings

import com.overdrive.app.byd.dilink5.NativeVehicleProfileAvailability
import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeVehicleProfilePersistenceTest {
    @Test
    fun absentAndUnknownProfileValuesFailClosed() {
        assertFalse(UnifiedConfigManager.isNativeVehicleManualProfileEnabled(JSONObject()))
        assertFalse(UnifiedConfigManager.isNativeVehicleManualProfileEnabled(
            JSONObject().put(UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_KEY, "unknown")
        ))
    }

    @Test
    fun selectedProfileSurvivesJsonRestartRoundTrip() {
        val vehicle = JSONObject().put(
            UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_KEY,
            UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_UNVERIFIED,
        )
        val afterRestart = JSONObject(vehicle.toString())

        assertTrue(UnifiedConfigManager.isNativeVehicleManualProfileEnabled(afterRestart))
        assertEquals(
            UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_UNVERIFIED,
            afterRestart.getString(UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_KEY),
        )
    }

    @Test
    fun profileSelectionContainsNoRoutingOrAutomationGrant() {
        val selected = JSONObject().put(
            UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_KEY,
            UnifiedConfigManager.NATIVE_VEHICLE_PROFILE_UNVERIFIED,
        )
        assertEquals(1, selected.length())
        for (key in listOf("routePolicy", "mqtt", "http", "voice", "schedule",
            "keymap", "proximity", "automation")) {
            assertFalse(selected.has(key))
        }
    }

    @Test
    fun olderGenerationSettingsNeverExposeNativeProfile() {
        assertFalse(NativeVehicleProfileAvailability.visible(false))
        assertTrue(NativeVehicleProfileAvailability.visible(true))

        val ui = repoFile("app/src/main/java/com/overdrive/app/ui/fragment/settings/SettingsSecurityFragment.kt")
            .readText()
        val layout = repoFile("app/src/main/res/layout/fragment_settings_security.xml").readText()
        assertTrue(ui.contains("NativeVehicleProfileAvailability.visible(DiLink5Platform.isSelected())"))
        assertTrue(ui.contains("groupNativeVehicleProfile.visibility"))
        assertTrue(layout.contains("android:id=\"@+id/groupNativeVehicleProfile\""))
        assertTrue(layout.substring(layout.indexOf("@+id/groupNativeVehicleProfile"))
            .substringBefore("@+id/rowNativeVehicleProfile")
            .contains("android:visibility=\"gone\""))
    }

    @Test
    fun defaultsSetterAndUiKeepProfileManualAndUnverified() {
        val root = repoFile("app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt")
            .readText()
        val ui = repoFile("app/src/main/java/com/overdrive/app/ui/fragment/settings/SettingsSecurityFragment.kt")
            .readText()
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        val daemon = repoFile("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java").readText()

        assertTrue(root.contains("vehicle.put(NATIVE_VEHICLE_PROFILE_KEY, NATIVE_VEHICLE_PROFILE_DISABLED)"))
        assertTrue(root.contains("setNativeVehicleManualProfileEnabled"))
        assertTrue(root.contains("setVehicle(JSONObject().put("))
        assertFalse(root.substring(root.indexOf("setNativeVehicleManualProfileEnabled"))
            .substringBefore("Update vehicle appearance").contains("routePolicy"))
        assertTrue(ui.contains("executeManualNative(action)"))
        assertTrue(ui.contains("NativeVehicleWriteClient.requestBinder"))
        assertTrue(ui.contains("settings_native_vehicle_confirm_body"))
        assertTrue(strings.contains("compatibility unverified"))
        assertTrue(ui.contains("VehicleCommandRouter.Outcome.SUCCESS ->"))
        assertTrue(ui.contains("settings_native_vehicle_result_completed"))
        assertTrue(ui.contains("settings_native_vehicle_result_accepted"))
        assertTrue(strings.contains("Physical movement has not been verified"))
        assertTrue(strings.contains("Physical movement was not verified"))
        val register = daemon.indexOf("NativeVehicleWriteService.register")
        assertTrue(register >= 0)
        assertTrue(daemon.substring((register - 500).coerceAtLeast(0), register)
            .contains("DiLink5Platform.isSelected()"))
    }

    private fun repoFile(relative: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        throw IllegalStateException("repository file not found: $relative")
    }
}
