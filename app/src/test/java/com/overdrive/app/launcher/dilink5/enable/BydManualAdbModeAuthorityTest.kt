package com.overdrive.app.launcher.dilink5.enable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydManualAdbModeAuthorityTest {
    @Test
    fun manualAdmissionDoesNotRequireDiLink5OrACameraCommit() {
        listOf("default", "dilink4", "dilink5", "camera2").forEach { mode ->
            val authority = BydManualAdbModeAuthority { BydManualAdbModeAuthority.Scope(mode) }
            assertNotNull(mode, authority.admit())
            assertTrue(authority.isCurrent(authority.admit()!!))
        }
    }

    @Test
    fun malformedOrUnreadablePresentMarkerCannotBecomeFreshAdmission() {
        val unavailable = BydManualAdbModeAuthority { null }
        assertNull(unavailable.admit())
        assertFalse(unavailable.isCurrent("manual:byd:v1:old"))
        assertNull(BydManualAdbModeAuthority {
            BydManualAdbModeAuthority.Scope("unknown")
        }.admit())
        assertNull(BydManualAdbModeAuthority {
            BydManualAdbModeAuthority.Scope("default", activeMode = "default")
        }.admit())
    }

    @Test
    fun everyPendingGenerationAndModeTransitionRevokesAnAdmittedRequest() {
        var scope = BydManualAdbModeAuthority.Scope("default")
        val authority = BydManualAdbModeAuthority { scope }
        val fresh = authority.admit()!!
        scope = scope.copy(pendingMode = "dilink4", pendingGeneration = "pending-4")
        assertFalse(authority.isCurrent(fresh))
        val pending4 = authority.admit()!!
        scope = scope.copy(pendingGeneration = "pending-4-restaged")
        assertFalse(authority.isCurrent(pending4))
        val restaged = authority.admit()!!
        scope = scope.copy(
            configuredMode = "dilink4",
            activeMode = "dilink4",
            activeGeneration = "committed-4",
            pendingMode = null,
            pendingGeneration = null,
        )
        assertFalse(authority.isCurrent(restaged))
        assertFalse(scope.permitsTlsRecovery)
    }

    @Test
    fun pendingDiLink5BootstrapUsesTheExactPendingUuidAndCommitIsANewAuthority() {
        var scope = BydManualAdbModeAuthority.Scope(
            "dilink5", "default", "old-default", "dilink5", "pending-5",
        )
        val authority = BydManualAdbModeAuthority { scope }
        val pending = authority.admit()!!
        assertFalse(scope.permitsTlsRecovery)
        scope = scope.copy(pendingMode = "default", pendingGeneration = "away")
        assertFalse(authority.isCurrent(pending))
        scope = scope.copy(pendingMode = "dilink5", pendingGeneration = "back")
        assertFalse(authority.isCurrent(pending))
        val replacement = authority.admit()!!
        scope = scope.copy(activeMode = "dilink5", activeGeneration = "committed-5",
            pendingMode = null, pendingGeneration = null)
        assertFalse(authority.isCurrent(replacement))
        assertTrue(scope.permitsTlsRecovery)
        assertEquals(scope.authority(), authority.admit())
    }

    @Test
    fun tlsControlsFollowTheCommittedDiLink5UntilAStagedModeIsActuallyCommitted() {
        listOf("default", "dilink4", "camera2").forEach { mode ->
            assertFalse(BydManualAdbModeAuthority.Scope(mode, mode, "active").permitsTlsRecovery)
        }
        assertFalse(BydManualAdbModeAuthority.Scope("dilink5").permitsTlsRecovery)
        val committed = BydManualAdbModeAuthority.Scope("dilink5", "dilink5", "active")
        assertTrue(committed.permitsTlsRecovery)
        val stagedAway = committed.copy(
            configuredMode = "default", pendingMode = "default", pendingGeneration = "pending",
        )
        assertTrue(stagedAway.permitsTlsRecovery)
        assertFalse(stagedAway.copy(activeMode = "default", activeGeneration = "new-active").permitsTlsRecovery)
    }
}
