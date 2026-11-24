package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.DiLink5HostedProjectionBridge.AppDisplayInfo
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig
import com.overdrive.app.launcher.dilink5.cluster.HostedProjectionGeometry
import com.overdrive.app.launcher.dilink5.cluster.Sealion7HdmiProjection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sealion7HdmiProjectionTest {
    @Test
    fun onlyTheCapturedFirmwareAndAnExplicitSealion7SelectionQualify() {
        val product = "DX_BYD_AUTO"
        val firmware = "eng.scm.20260603.212028"
        assertTrue(Sealion7HdmiProjection.matchesFirmware(product, 30, firmware))
        assertFalse(Sealion7HdmiProjection.matchesFirmware(product, 33, firmware))
        assertFalse(Sealion7HdmiProjection.matchesFirmware("other_$product", 30, firmware))
        assertFalse(Sealion7HdmiProjection.matchesFirmware(product, 30, "$firmware.1"))
        assertFalse(Sealion7HdmiProjection.matchesFirmware(product, 30, null))

        assertTrue(Sealion7HdmiProjection.isUserSelected("sealion7", "user"))
        for (source in listOf(null, "unset", "auto", "legacy", true)) {
            assertFalse(Sealion7HdmiProjection.isUserSelected("sealion7", source))
        }
        assertFalse(Sealion7HdmiProjection.isUserSelected("seal", "user"))
        assertFalse(Sealion7HdmiProjection.isUserSelected(null, "user"))
    }

    @Test
    fun existingConfigurationDefaultsOffAndOnlyBooleanTrueOptsIn() {
        val key = Sealion7HdmiProjection.CONFIG_KEY
        val projection = JSONObject()
        assertFalse(Sealion7HdmiProjection.isOptedIn(null))
        assertFalse(Sealion7HdmiProjection.isOptedIn(projection))
        DiLink5ProjectionConfig.applyDefaults(projection)
        assertFalse(projection.getBoolean(key))

        assertFalse(Sealion7HdmiProjection.isOptedIn(projection.put(key, "true")))
        assertFalse(Sealion7HdmiProjection.isOptedIn(projection.put(key, 1)))
        projection.put(key, true)
        DiLink5ProjectionConfig.applyDefaults(projection)
        assertTrue(Sealion7HdmiProjection.isOptedIn(projection))
    }

    @Test
    fun fullWindowAndContentBufferMatchThePanelWithoutAnApprovedMapRectangle() {
        val display = AppDisplayInfo(2, 1920, 720, 160)
        val expected = HostedProjectionGeometry(
            1920, 720, 160, HostedProjectionGeometry.Bounds(0, 0, 1920, 720), 1920, 720,
        )
        assertEquals(expected, Sealion7HdmiProjection.geometry(display))
        assertNull(Sealion7HdmiProjection.geometry(display.copy(displayId = 0)))
        assertNull(Sealion7HdmiProjection.geometry(display.copy(width = 1919)))
        assertNull(Sealion7HdmiProjection.geometry(display.copy(height = 1080)))
        assertNull(Sealion7HdmiProjection.geometry(display.copy(densityDpi = 0)))

        assertTrue(Sealion7HdmiProjection.matchesGeometry(0, 0, 1920, 720, 1920, 720))
        assertFalse(Sealion7HdmiProjection.matchesGeometry(1, 0, 1920, 720, 1920, 720))
        assertFalse(Sealion7HdmiProjection.matchesGeometry(0, 1, 1920, 720, 1920, 720))
        assertFalse(Sealion7HdmiProjection.matchesGeometry(0, 0, 1919, 720, 1920, 720))
        assertFalse(Sealion7HdmiProjection.matchesGeometry(0, 0, 1920, 720, 960, 360))
    }
}
