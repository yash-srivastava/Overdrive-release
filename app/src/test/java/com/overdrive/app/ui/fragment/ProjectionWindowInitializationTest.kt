package com.overdrive.app.ui.fragment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionWindowInitializationTest {
    @Test
    fun openingHeadlessHostedProjectionReflectsItsOverlayWithoutApplyingFullOrLegacyBox() {
        val physical = intArrayOf(960, 100, 1920, 620)
        var reflected: IntArray? = null
        var rememberedOrFullApplied = false
        val handled = reflectActiveProjectionWindow(true, physical) {
            reflected = it
        }
        if (!handled) rememberedOrFullApplied = true
        assertTrue(handled)
        assertFalse(rememberedOrFullApplied)
        assertArrayEquals(physical, reflected)
        reflected!![0] = 0
        assertTrue(physical[0] == 960) // reflecting UI state never mutates the owned snapshot
    }

    @Test
    fun missingHostedBoundsWaitForStatusInsteadOfCommittingAFallback() {
        var geometryWrites = 0
        for (actual in listOf<IntArray?>(null, intArrayOf(-1, 0, 1920, 720))) {
            val handled = reflectActiveProjectionWindow(true, actual) { geometryWrites++ }
            if (!handled) geometryWrites++ // old local-window/FULL fallback
            assertTrue(handled)
        }
        assertTrue(geometryWrites == 0)
    }

    @Test
    fun legacyProjectionKeepsItsWindowFlowAndHidesOnlyDiLink5Controls() {
        assertFalse(reflectActiveProjectionWindow(false, null) {
            error("no hosted window on the legacy path")
        })
        assertFalse(projectionUsesDiLink5Controls("legacy"))
        assertFalse(projectionUsesDiLink5Controls("default"))
        assertFalse(projectionUsesDiLink5Controls(""))
        assertTrue(projectionUsesDiLink5Controls("dilink5"))
    }
}
