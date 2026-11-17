package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5ProjectionOwnershipTest {
    private fun owned() = DiLink5ProjectionOwnership(
        4, "boot-a", "hosted", "pkg", 3, "panel", "stable-panel", 8,
        "OverDriveCluster-4", 21, true, true, true, true, true, true, true,
        "di5:2:resize:4",
    )

    @Test
    fun numericDisplayReuseDoesNotSatisfyStableIdentity() {
        val state = owned()
        assertTrue(state.stablePhysicalIdentityMatches(3, "panel", "stable-panel"))
        assertFalse(state.stablePhysicalIdentityMatches(3, "panel", "replacement"))
        assertFalse(state.stablePhysicalIdentityMatches(4, "panel", "stable-panel"))
    }

    @Test
    fun uncertainOwnershipClearsOnlyAfterProofOrBootChange() {
        val state = owned()
        assertFalse(state.mayClear(false, "boot-a"))
        assertTrue(state.mayClear(true, "boot-a"))
        assertTrue(state.mayClear(false, "boot-b"))
        val restored = DiLink5ProjectionOwnership.fromJson(state.toJson())
        assertNotNull(restored)
        assertEquals("di5:2:resize:4", restored?.densityOwner)
    }
}
