package com.overdrive.app.roadsense

import com.overdrive.app.byd.BydVehicleData
import com.overdrive.app.roadsense.sidecar.RoadSenseImuSidecarService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadSenseGearStabilityTest {

    @Test
    fun resolveRawGearFallsBackToCollectorWhenRunningMonitorReportsUnavailable() {
        assertEquals(
            4,
            RoadSenseController.resolveRawGear(
                monitorRunning = true,
                monitorGear = BydVehicleData.UNAVAILABLE,
                collectorGear = 4,
            )
        )
        assertEquals(
            1,
            RoadSenseController.resolveRawGear(
                monitorRunning = true,
                monitorGear = 1,
                collectorGear = 4,
            )
        )
    }

    @Test
    fun transientUnavailableBlipHoldsForwardGearBrieflyButNeverOverridesRealNonForwardGear() {
        // Stale UNAVAILABLE/0 blip within 3s holds D(4).
        assertTrue(
            RoadSenseController.shouldHoldForwardGearOnBlip(
                rawGear = BydVehicleData.UNAVAILABLE,
                lastForwardGear = 4,
                nowMs = 12_500L,
                lastForwardGearAtMs = 10_000L,
            )
        )
        assertTrue(
            RoadSenseController.shouldHoldForwardGearOnBlip(
                rawGear = 0,
                lastForwardGear = 4,
                nowMs = 13_000L,
                lastForwardGearAtMs = 10_000L,
            )
        )
        // Past 3s window, no longer held.
        assertFalse(
            RoadSenseController.shouldHoldForwardGearOnBlip(
                rawGear = BydVehicleData.UNAVAILABLE,
                lastForwardGear = 4,
                nowMs = 13_001L,
                lastForwardGearAtMs = 10_000L,
            )
        )
        // Explicit P(1), R(2), N(3) must NEVER be held.
        assertFalse(
            RoadSenseController.shouldHoldForwardGearOnBlip(
                rawGear = 1,
                lastForwardGear = 4,
                nowMs = 10_100L,
                lastForwardGearAtMs = 10_000L,
            )
        )
    }

    @Test
    fun imuBuildUnsupportedLatchMatchesOnlyIdenticalNonEmptyFingerprint() {
        assertTrue(
            RoadSenseImuSidecarService.isBuildMarkedUnsupported(
                "BYD/DiLink5/Sealion7:13/TP1A/2026:user/release-keys",
                "BYD/DiLink5/Sealion7:13/TP1A/2026:user/release-keys",
            )
        )
        assertFalse(
            RoadSenseImuSidecarService.isBuildMarkedUnsupported(
                "BYD/DiLink5/Sealion7:13/TP1A/2026:user/release-keys",
                "BYD/DiLink5/Sealion7:13/TP1A/2027:user/release-keys",
            )
        )
        assertFalse(RoadSenseImuSidecarService.isBuildMarkedUnsupported("", ""))
    }
}
