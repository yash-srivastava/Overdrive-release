package com.overdrive.app.ui.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.overdrive.app.ui.view.AaosMapAreaCheck.MapArea
import com.overdrive.app.ui.view.AaosMapAreaCheck.ParkDecision
import org.junit.Test

class AaosMapAreaCheckTest {
    @Test fun bothLayoutsMustAuthorizeTheSameSelectedRectangleOnTheSameFirmware() {
        val scope = "v2.test-build.local:3"
        val center = MapArea.CENTER
        val right = MapArea.RIGHT
        val stored = mutableMapOf<String, Any>()
        assertEquals(center, AaosMapAreaCheck.selectedArea(stored, scope))
        // Center approval written by the single-area version remains valid.
        stored["$scope.640x280.center.full"] = true
        stored["$scope.640x280.center.small"] = true
        assertEquals(center, AaosMapAreaCheck.approvedArea(stored, scope))
        stored["$scope.selected"] = right.id
        assertNull(AaosMapAreaCheck.approvedArea(stored, scope))
        stored["${right.proofKey(scope)}.small"] = true
        assertNull(AaosMapAreaCheck.approvedArea(stored, scope))
        stored["${right.proofKey(scope)}.full"] = true
        assertEquals(right, AaosMapAreaCheck.approvedArea(stored, scope))
        assertNull(AaosMapAreaCheck.approvedArea(stored, "$scope.new-firmware"))
        stored["${right.proofKey(scope)}.small"] = false
        assertNull(AaosMapAreaCheck.approvedArea(stored, scope))
        stored["$scope.selected"] = center.id
        assertEquals(center, AaosMapAreaCheck.approvedArea(stored, scope))
    }

    @Test fun approvedRegionsHaveTheCoordinatesUsedByTheParkedOutlineAndHost() {
        fun coordinates(area: MapArea) = listOf(area.left, area.top, area.right, area.bottom)
        assertEquals(listOf(640, 220, 1280, 500), coordinates(MapArea.CENTER))
        assertEquals(listOf(1440, 232, 1840, 488), coordinates(MapArea.RIGHT))
    }

    @Test fun onlyTheCapturedExternalPortAndResolutionQualify() {
        assertTrue(AaosMapAreaCheck.matchesMapPanel(2, "local:3", 1920, 720))
        for ((type, uniqueId) in listOf(2 to "local:30", 5 to "local:3", 2 to null, null to "local:3")) {
            assertFalse(AaosMapAreaCheck.matchesMapPanel(type, uniqueId, 1920, 720))
        }
        assertFalse(AaosMapAreaCheck.matchesMapPanel(2, "local:3", 1920, 1080))
    }

    @Test fun stalledPollCannotRenewParkAndFutureOrUnknownTimesFailClosed() {
        assertTrue(AaosMapAreaCheck.parkSampleIsFresh(9_001, 10_000))
        assertFalse(AaosMapAreaCheck.parkSampleIsFresh(9_000, 10_000))
        assertFalse(AaosMapAreaCheck.parkSampleIsFresh(0, 500))
        assertFalse(AaosMapAreaCheck.parkSampleIsFresh(10_001, 10_000))
    }

    @Test fun manualChoiceRequiresInitialUnknownAndCannotOverrideAChangedOrLostParkSignal() {
        val now = 5_000L
        fun decision(gear: Long?, previous: Long = 0L, owner: Boolean = false, visible: Boolean = false) =
            AaosMapAreaCheck.parkDecision(gear, now, previous, owner, visible)
        assertEquals(ParkDecision.ASK_OWNER, decision(null))
        assertEquals(ParkDecision.STOP, decision(null, visible = true))
        assertEquals(ParkDecision.MANUAL, decision(null, owner = true, visible = true))
        assertEquals(ParkDecision.AUTOMATIC, decision(now, owner = true, visible = true))
        assertEquals(ParkDecision.STOP, decision(0L, owner = true))
        assertEquals(ParkDecision.STOP, decision(null, previous = now - 50, owner = true))
        assertEquals(ParkDecision.STOP, decision(now - 1_000, owner = true))
        assertEquals(ParkDecision.STOP, decision(now + 1))
    }

    @Test fun eitherSourceCanReportLeavingPark() {
        assertNull(AaosMapAreaCheck.combineGear(null, null))
        assertEquals(5000L, AaosMapAreaCheck.combineGear(null, 5000L))
        assertEquals(5001L, AaosMapAreaCheck.combineGear(5000L, 5001L))
        assertEquals(0L, AaosMapAreaCheck.combineGear(0L, 5000L))
        assertEquals(0L, AaosMapAreaCheck.combineGear(5000L, 0L))
    }
}
