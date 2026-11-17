package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig
import com.overdrive.app.launcher.dilink5.cluster.HostedProjectionGeometry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostedProjectionGeometryTest {
    @Test
    fun freshOffsetsCenterButPresentSavedEdgesSurviveDefaultMigration() {
        val fresh = JSONObject()
        DiLink5ProjectionConfig.applyDefaults(fresh)
        assertEquals(50, fresh.getInt(DiLink5ProjectionConfig.KEY_X))
        assertEquals(50, fresh.getInt(DiLink5ProjectionConfig.KEY_Y))
        val centered = HostedProjectionGeometry.calculate(1920, 720, 320,
            DiLink5ProjectionConfig.from(JSONObject()
                .put(DiLink5ProjectionConfig.KEY_WIDTH, 50)
                .put(DiLink5ProjectionConfig.KEY_HEIGHT, 80)))!!
        assertEquals(480, centered.overlay.left)
        assertEquals(72, centered.overlay.top)

        val saved = JSONObject()
            .put(DiLink5ProjectionConfig.KEY_X, 0)
            .put(DiLink5ProjectionConfig.KEY_Y, 100)
            .put(DiLink5ProjectionConfig.LEGACY_PREFER_FULL, true)
        DiLink5ProjectionConfig.applyDefaults(saved)
        val migrated = DiLink5ProjectionConfig.from(saved)
        assertEquals(0, migrated.xOffsetPercent)
        assertEquals(100, migrated.yOffsetPercent)
        assertEquals(DiLink5ProjectionConfig.Target.FULL, migrated.target)
        assertEquals(0, saved.getInt(DiLink5ProjectionConfig.KEY_X))
        assertEquals(100, saved.getInt(DiLink5ProjectionConfig.KEY_Y))
    }

    @Test
    fun syntheticPanelUsesRemainingSpaceOffsetsAndInverseScale() {
        val config = DiLink5ProjectionConfig(
            DiLink5ProjectionConfig.Transport.HOSTED,
            DiLink5ProjectionConfig.Target.STANDARD,
            50, 80, 50, 0, 50, 100
        )
        val result = HostedProjectionGeometry.calculate(1920, 720, 240, config)!!
        assertEquals(480, result.overlay.left)
        assertEquals(0, result.overlay.top)
        assertEquals(960, result.overlay.width())
        assertEquals(576, result.overlay.height())
        assertEquals(1920, result.bufferWidth)
        assertEquals(1152, result.bufferHeight)
        assertEquals(240, result.densityDpi)
    }

    @Test
    fun malformedValuesClampWithoutOverflow() {
        val config = DiLink5ProjectionConfig.from(JSONObject()
            .put(DiLink5ProjectionConfig.KEY_WIDTH, -1)
            .put(DiLink5ProjectionConfig.KEY_HEIGHT, 1000)
            .put(DiLink5ProjectionConfig.KEY_X, 1000)
            .put(DiLink5ProjectionConfig.KEY_Y, -1)
            .put(DiLink5ProjectionConfig.KEY_CONTENT_SCALE, 1000))
        val result = HostedProjectionGeometry.calculate(1000, 500, 320, config)!!
        assertEquals(800, result.overlay.left)
        assertEquals(0, result.overlay.top)
        assertEquals(200, result.overlay.width())
        assertEquals(500, result.overlay.height())
        assertNull(HostedProjectionGeometry.calculate(9000, 500, 320, config))
    }

    @Test
    fun freshTargetIsStandardAndLegacyExplicitChoiceIsPreserved() {
        assertEquals(
            DiLink5ProjectionConfig.Target.STANDARD,
            DiLink5ProjectionConfig.from(JSONObject()).target
        )
        assertEquals(
            DiLink5ProjectionConfig.Target.FULL,
            DiLink5ProjectionConfig.from(JSONObject()
                .put(DiLink5ProjectionConfig.LEGACY_PREFER_FULL, true)).target
        )
        assertEquals(
            DiLink5ProjectionConfig.Target.STANDARD,
            DiLink5ProjectionConfig.from(JSONObject()
                .put(DiLink5ProjectionConfig.KEY_TARGET, "standard")
                .put(DiLink5ProjectionConfig.LEGACY_PREFER_FULL, true)).target
        )
    }
}
