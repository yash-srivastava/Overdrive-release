package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionOwnership
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterAppDensityTest {
    @Test
    fun completedDensityVerdictSurvivesRestartAndBootWithoutPersistingInFlightStrikes() {
        val unsafe = mapOf("com.example.navigator" to DirectDensityVerdict(
            DirectDensityLossReason.MISSING_TASK, 1234L,
        ))
        val saved = serializeDirectDensityVerdicts(unsafe)
        val restarted = JSONObject(saved.toString())
        assertEquals(unsafe, readDirectDensityVerdicts(restarted))
        assertEquals(unsafe, readDirectDensityVerdicts(
            JSONObject(saved.toString()).put("bootId", "old-boot")))
        assertEquals(unsafe, readDirectDensityVerdicts(
            JSONObject(saved.toString()).put("bootId", "new-boot")))
        assertFalse(saved.has("bootId"))
        assertFalse(saved.has("lossEpisodes"))
        assertFalse(saved.has("losses"))
        assertEquals(DIRECT_DENSITY_PROBE_GENERATION, saved.getInt("probeGeneration"))
    }

    @Test
    fun priorEpisodeOnlyAndDifferentModelVerdictsCannotBePromotedToProvedDeaths() {
        val prior = JSONObject().put("bootId", "same-boot")
            .put("losses", JSONObject().put("com.example.navigator", 2))
            .put("lossEpisodes", JSONObject().put("com.example.navigator",
                JSONArray().put("17:0:42").put("17:1:51")))
        val before = prior.toString()
        assertTrue(readDirectDensityVerdicts(prior).isEmpty())
        assertEquals(before, prior.toString()) // decoding never changes the existing lease/config data
        val current = serializeDirectDensityVerdicts(mapOf(
            "com.example.navigator" to DirectDensityVerdict(DirectDensityLossReason.RECREATED_TASK, 99L),
        ))
        assertTrue(readDirectDensityVerdicts(
            JSONObject(current.toString()).put("probeGeneration", 1)).isEmpty())
        assertTrue(readDirectDensityVerdicts(
            JSONObject(current.toString()).put("probeGeneration", 3)).isEmpty())
        assertTrue(readDirectDensityVerdicts(
            JSONObject(current.toString()).put("schemaVersion", 0)).isEmpty())
        assertTrue(readDirectDensityVerdicts(
            JSONObject(current.toString()).put("evidenceModel", "unclassified-flee")).isEmpty())
        assertTrue(currentDirectDensityJournal(serializeDirectDensityVerdicts(emptyMap())))
    }

    @Test
    fun malformedVerdictRowsAreRejectedWithoutDroppingValidObservedReasons() {
        val saved = serializeDirectDensityVerdicts(mapOf(
            "com.example.valid" to DirectDensityVerdict(DirectDensityLossReason.TASK_MODE_RESET, 5L),
        ))
        saved.getJSONObject("unsafePackages")
            .put("com.example.noReason", JSONObject().put("learnedAtMillis", 1L))
            .put("com.example.fled", JSONObject().put("reason", "FLED").put("learnedAtMillis", 1L))
            .put("com.example.badTime", JSONObject()
                .put("reason", "MISSING_TASK").put("learnedAtMillis", -1L))
            .put("bad package", JSONObject().put("reason", "MISSING_TASK").put("learnedAtMillis", 1L))
        assertEquals(mapOf("com.example.valid" to DirectDensityVerdict(
            DirectDensityLossReason.TASK_MODE_RESET, 5L,
        )), readDirectDensityVerdicts(saved))
    }

    @Test
    fun twoLossesMustBelongToTheSameWatchAttemptAndHostedOrLegacyOwnersCannotTeach() {
        val episodes = linkedSetOf<String>()
        assertTrue(recordDirectDensityLossForAttempt(episodes, "di5:17", "17:0:42"))
        assertFalse(recordDirectDensityLossForAttempt(episodes, "di5:17", "17:0:42"))
        assertTrue(recordDirectDensityLossForAttempt(episodes, "di5:18", "18:0:51"))
        assertEquals(setOf("18:0:51"), episodes) // a new attempt does not inherit a first strike
        assertTrue(recordDirectDensityLossForAttempt(episodes, "di5:18", "18:1:52"))
        assertEquals(2, episodes.size)
        assertFalse(recordDirectDensityLossForAttempt(episodes, "di5:18", "17:2:53"))
        assertFalse(recordDirectDensityLossForAttempt(episodes, "legacy:18", "18:2:53"))
        assertFalse(recordDirectDensityLossForAttempt(episodes, "di5:18:resize:19", "18:2:53"))
        assertFalse(directDensityLearningEligible("legacy:18", false))
        assertFalse(directDensityLearningEligible("di5:18", true))
        assertTrue(directDensityLearningEligible("di5:18", false))
        // A direct unsafe verdict changes no legacy/hosted preferred-DPI behavior.
        assertEquals(360, densityForProjectionPolicy(360, 320, 100, directOwner = false))
        assertEquals(320, densityForProjectionPolicy(360, 320, 100, directOwner = true))
    }

    @Test
    fun lossProvenanceComesFromTheSameObservedTaskAndMode() {
        assertEquals(DirectDensityLossReason.MISSING_TASK,
            directDensityLossReason(42, -1, -1, 8, null))
        assertEquals(DirectDensityLossReason.RECREATED_TASK,
            directDensityLossReason(42, 51, 0, 8, 5))
        assertEquals(DirectDensityLossReason.TASK_MODE_RESET,
            directDensityLossReason(42, 42, 0, 8, 1))
        assertNull(directDensityLossReason(42, 42, 0, 8, 5))
        assertNull(directDensityLossReason(0, 51, 0, 8, 5))
    }

    @Test
    fun hostedReplacementLeaseIsPreparedPromotedAndRestoredAsAFamily() {
        val source = java.io.File(
            "src/main/java/com/overdrive/app/launcher/ClusterAppDensity.kt",
        ).readText()
        assertTrue(source.contains("fun prepareHostedReplacement("))
        assertTrue(source.contains("fun promoteHostedReplacement("))
        assertTrue(source.contains("fun restoreForSessionFamily("))
        assertTrue(source.contains("it.startsWith(\"\$owner:resize:\")"))
    }

    @Test
    fun hostedReplacementRetainsHostedPreferredDensityPolicy() {
        val preferred = 360
        assertEquals(
            preferred,
            densityForProjectionPolicy(preferred, 320, 80, directOwner = false),
        )
        assertEquals(
            256,
            densityForProjectionPolicy(preferred, 320, 80, directOwner = true),
        )
        assertEquals(
            320,
            densityForProjectionPolicy(preferred, 320, 100, directOwner = true),
        )
    }

    @Test
    fun hostedPreparationDistinguishesOwnedUnchangedAndFailure() {
        assertEquals(
            HostedDensityPreparation.READY_WITH_LEASE,
            hostedDensityPreparationResult(true, true, "applied"),
        )
        assertEquals(
            HostedDensityPreparation.READY_UNCHANGED,
            hostedDensityPreparationResult(false, false, "already_applied"),
        )
        assertEquals(
            HostedDensityPreparation.READY_UNCHANGED,
            hostedDensityPreparationResult(false, false, "unchanged"),
        )
        assertEquals(
            HostedDensityPreparation.FAILED,
            hostedDensityPreparationResult(false, false, "failed"),
        )
        assertEquals(
            HostedDensityPreparation.FAILED,
            hostedDensityPreparationResult(true, false, "applied"),
        )
    }

    @Test
    fun hostedLifecycleExecutesPreferredDensityMovePromotionAndStopRestoration() {
        data class Lease(val owner: String, val applied: Int, val previous: Int)
        val canonical = "di5:11"
        val transition = "$canonical:resize:12"
        val commands = mutableListOf<String>()
        val leases = linkedMapOf<String, Lease>()
        val active = linkedSetOf<String>()
        var moved = false

        val prepared = runHostedDensityPreparation(
            apply = {
                commands += "density 360"
                leases[transition] = Lease(transition, 360, 300)
                active += transition
            },
            inspect = {
                hostedDensityPreparationResult(
                    leases.containsKey(transition),
                    transition in active,
                    "applied",
                )
            },
        )
        assertEquals(HostedDensityPreparation.READY_WITH_LEASE, prepared)
        assertEquals(360, leases.getValue(transition).applied)
        moved = true
        assertTrue(promoteHostedLeaseTransition(
            leases,
            active,
            canonical,
            transition,
            rename = { lease, owner -> lease.copy(owner = owner) },
            persist = { true },
        ))
        assertTrue(moved)
        val promoted = leases.remove(canonical)!!
        active.remove(canonical)
        commands += "density ${promoted.previous}"
        assertEquals(listOf("density 360", "density 300"), commands)
        assertTrue(leases.isEmpty())
        assertTrue(active.isEmpty())
    }

    @Test
    fun leaseFreeHostedReplacementAppliesPromotesAndRestoresPreferredDensity() {
        data class Lease(val owner: String, val applied: Int, val previous: Int)
        val preferred = 360
        val native = 320
        val directScale = 80
        val canonical = "di5:21"
        val transition = "$canonical:resize:22"
        val leases = linkedMapOf<String, Lease>()
        val active = linkedSetOf<String>()
        val commands = mutableListOf<Int>()
        var replacementEffective = native
        var moved = false

        // The old hosted display was already at the preference, so this session owned no lease.
        val oldEffective = preferred
        assertEquals(preferred, oldEffective)
        assertFalse(leases.containsKey(canonical))
        assertEquals(256, densityForProjectionPolicy(preferred, native, directScale, true))

        val prepared = runHostedReplacementPreparation(
            canonicalStateValid = canonical !in active,
            ownersDiffer = canonical != transition,
            apply = {
                val desired = densityForProjectionPolicy(preferred, native, directScale, false)
                commands += desired
                leases[transition] = Lease(transition, desired, replacementEffective)
                active += transition
                replacementEffective = desired
            },
            inspect = {
                hostedDensityPreparationResult(
                    leases.containsKey(transition),
                    transition in active,
                    "applied",
                )
            },
        )
        assertEquals(HostedDensityPreparation.READY_WITH_LEASE, prepared)
        assertEquals(preferred, replacementEffective)
        moved = true
        assertTrue(promoteHostedLeaseTransition(
            leases,
            active,
            canonical,
            transition,
            rename = { lease, owner -> lease.copy(owner = owner) },
            persist = { true },
        ))
        assertTrue(moved)
        val promoted = leases.remove(canonical)!!
        active.remove(canonical)
        commands += promoted.previous
        replacementEffective = promoted.previous
        assertEquals(listOf(preferred, native), commands)
        assertEquals(native, replacementEffective)
        assertTrue(leases.isEmpty())
        assertTrue(active.isEmpty())
    }

    @Test
    fun hostedReplacementTransitionPromotesAndRollsBackDifferingDensity() {
        data class Lease(val owner: String, val dpi: Int)
        val canonical = "di5:7"
        val transition = "$canonical:resize:8"
        val leases = linkedMapOf(transition to Lease(transition, 360))
        val active = linkedSetOf(transition)

        assertTrue(promoteHostedLeaseTransition(
            leases,
            active,
            canonical,
            transition,
            rename = { lease, owner -> lease.copy(owner = owner) },
            persist = { true },
        ))
        assertEquals(360, leases.getValue(canonical).dpi)
        assertEquals(setOf(canonical), active)

        leases.clear()
        leases[transition] = Lease(transition, 360)
        active.clear()
        active += transition
        assertFalse(promoteHostedLeaseTransition(
            leases,
            active,
            canonical,
            transition,
            rename = { lease, owner -> lease.copy(owner = owner) },
            persist = { false },
        ))
        assertEquals(360, leases.getValue(transition).dpi)
        assertEquals(setOf(transition), active)
    }

    @Test
    fun directScaleUsesNativeDensityAndHundredPercentResets() {
        assertEquals(0, directDensityForScale(320, 100))
        assertEquals(160, directDensityForScale(320, 50))
        assertEquals(480, directDensityForScale(320, 150))
        assertEquals(0, directDensityForScale(0, 120))
    }

    @Test
    fun livingTaskMovementIsNotCountedAsDensityLoss() {
        assertFalse(isDirectDensityLoss(42, 42, 0, 8, 5))
        assertFalse(isDirectDensityLoss(0, 51, 0, 8, 5))
        assertTrue(isDirectDensityLoss(42, 0, -1, 8, null))
        assertTrue(isDirectDensityLoss(42, 51, 0, 8, 5))
        assertFalse(isDirectDensityLoss(42, 42, 8, 8, 5))
    }

    @Test
    fun sameIdFullscreenResetAndUnknownModeStillCountAsDensityLoss() {
        assertTrue(isDirectDensityLoss(42, 42, 0, 8, 1))
        assertTrue(isDirectDensityLoss(42, 42, 0, 8, null))
        assertTrue(isDirectDensityLoss(0, 51, 0, 8, 1))
        assertTrue(isDirectDensityLoss(42, 51, 0, 8, 1))
        // Being on the target is not a placement loss; mode checks apply to tasks that left it.
        assertFalse(isDirectDensityLoss(42, 42, 8, 8, 1))
    }

    @Test
    fun onlyDistinctPostRelaunchTaskDeathsCountTowardSuppression() {
        val episodes = linkedSetOf<String>()
        assertTrue(recordDistinctDensityLoss(episodes, "17:0:0"))
        assertFalse(recordDistinctDensityLoss(episodes, "17:0:0"))
        assertTrue(recordDistinctDensityLoss(episodes, "17:1:52"))
        assertEquals(setOf("17:0:0", "17:1:52"), episodes)
        assertFalse(recordDistinctDensityLoss(episodes, ""))
        assertFalse(densityLossEpisode(17, 0, 0).isBlank())
    }

    @Test
    fun restorationRequiresCompleteEvidenceAcrossEveryMatchingTask() {
        fun evidence(vararg locations: ClusterFreeformWindow.TaskLocation) =
            ClusterFreeformWindow.allTasksReclaimedFromDisplay(locations.toList(), 8)

        assertEquals(true, evidence())
        assertEquals(true, evidence(ClusterFreeformWindow.TaskLocation(true, 42, 0)))
        assertEquals(
            false,
            evidence(
                ClusterFreeformWindow.TaskLocation(true, 42, 0),
                ClusterFreeformWindow.TaskLocation(true, 43, 8),
            ),
        )
        assertEquals(
            false,
            evidence(
                ClusterFreeformWindow.TaskLocation(true, 42, -1),
                ClusterFreeformWindow.TaskLocation(true, 43, 8),
            ),
        )
        assertEquals(
            null,
            evidence(
                ClusterFreeformWindow.TaskLocation(true, 42, 0),
                ClusterFreeformWindow.TaskLocation(true, 43, -1),
            ),
        )
        assertEquals(null, evidence(ClusterFreeformWindow.TaskLocation.unknown()))
    }

    @Test
    fun runningTaskInventoryUsesAllDisplaysAndRejectsSaturation() {
        val args = ClusterFreeformWindow.runningTaskQueryArgs(
            arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
        )
        assertEquals(65, args[0])
        assertEquals(false, args[1])
        assertEquals(false, args[2])
        assertEquals(-1, args[3])
        assertFalse(ClusterFreeformWindow.taskInventoryComplete(0))
        assertTrue(ClusterFreeformWindow.taskInventoryComplete(64))
        assertFalse(ClusterFreeformWindow.taskInventoryComplete(65))
    }

    @Test
    fun legacyStopRehomesBeforeDensityRestoreAndPropagatesFailure() {
        val source = File("src/main/java/com/overdrive/app/launcher/ClusterCast.java").readText()
        val start = source.indexOf("private static boolean stop(int rehomeMode")
        val end = source.indexOf("private static boolean stillOurs(", start)
        val stop = source.substring(start, end)
        val rehome = stop.indexOf("AppLauncher.reparentToDisplay0(pkg)")
        val restore = stop.indexOf("ClusterAppDensity.restoreForSession(")
        assertTrue(rehome >= 0 && restore > rehome)
        assertTrue(stop.contains("return rehomed && densityRestored && projectionReleased;"))
        assertTrue(stop.contains("rehomeMode != NO_REHOME && densityRestored"))
    }

    @Test
    fun persistedLeasesAcceptEveryWindowManagerDensityRange() {
        assertTrue(isValidPersistedDensity(1))
        assertTrue(isValidPersistedDensity(640))
        assertTrue(isValidPersistedDensity(10_000))
        assertFalse(isValidPersistedDensity(0))
        assertFalse(isValidPersistedDensity(10_001))
    }

    @Test
    fun suppressionEvidenceRequiresAConfirmedNonNativeLease() {
        assertTrue(isConfirmedNonNativeDensity(384, 320, 384))
        assertFalse(isConfirmedNonNativeDensity(320, 320, 320))
        assertFalse(isConfirmedNonNativeDensity(384, 320, 320))
    }

    @Test
    fun transitionJournalOwnsBothSidesOfANativeReset() {
        assertTrue(densityJournalOwns(384, 320, 384))
        assertTrue(densityJournalOwns(384, 320, 320))
        assertFalse(densityJournalOwns(384, 320, 400))
        assertFalse(densityJournalOwns(384, null, 320))
    }

    @Test
    fun bootDensityRecoveryWaitsForTaskReclamation() {
        assertFalse(densityRecoveryMayWrite(writesAllowed = false, taskRecoveryInFlight = false))
        assertFalse(densityRecoveryMayWrite(writesAllowed = true, taskRecoveryInFlight = true))
        assertTrue(densityRecoveryMayWrite(writesAllowed = true, taskRecoveryInFlight = false))
    }

    private fun predecessorLease(): JSONObject = JSONObject()
        .put("owner", "legacy:7")
        .put("displayId", 8)
        .put("name", "cluster")
        .put("uniqueId", "original-cluster")
        .put("bootId", "boot-a")
        .put("previousOverride", 0)
        .put("applied", 360)

    @Test
    fun exactPredecessorSchemaMigratesWithoutInventingAPackage() {
        val old = predecessorLease()
        assertTrue(isPredecessorDensityLease(old))
        assertEquals("", old.optString("package"))
        assertFalse(isPredecessorDensityLease(predecessorLease().put("package", "")))
        assertFalse(isPredecessorDensityLease(predecessorLease().put("owner", "di5:7")))
        assertFalse(isPredecessorDensityLease(predecessorLease().put("unexpected", true)))
        assertFalse(isPredecessorDensityLease(predecessorLease().put("owner", "legacy:0")))
        val migrated = predecessorLease().put("package", "")
            .put("transitionApplied", 0).put("predecessorWithoutPackage", true)
        assertTrue(isPredecessorDensityLease(migrated))
        assertFalse(isPredecessorDensityLease(migrated.put("package", "com.example.app")))
    }

    @Test
    fun predecessorRecoveryRequiresCompleteDisplayWideReclamation() {
        assertTrue(predecessorDensityReclamationConfirmed(true, "", true))
        assertFalse(predecessorDensityReclamationConfirmed(true, "", null))
        assertFalse(predecessorDensityReclamationConfirmed(true, "", false))
        assertFalse(predecessorDensityReclamationConfirmed(false, "", true))
        assertFalse(predecessorDensityReclamationConfirmed(true, "com.example.app", true))
        val saturatedOffTarget = ClusterFreeformWindow.allTasksReclaimedFromDisplay(
            listOf(ClusterFreeformWindow.TaskLocation(true, 11, 0)), 8, false,
        )
        assertFalse(predecessorDensityReclamationConfirmed(true, "", saturatedOffTarget))
    }

    private fun hostedOwner(): DiLink5ProjectionOwnership = DiLink5ProjectionOwnership(
        generation = 77,
        bootId = "boot-a",
        transport = "hosted",
        packageName = "com.example.app",
        physicalDisplayId = 4,
        physicalDisplayName = "XDJAScreenProjection_1",
        physicalUniqueId = "panel",
        contentDisplayId = 8,
        virtualDisplayName = "OverDriveCluster-77",
        taskId = 11,
        overlayOwned = true,
        virtualDisplayOwned = true,
        taskMayBeMoved = true,
        densityOwned = true,
        freeformOwned = false,
        compositorOwned = true,
        uncertain = true,
        densityOwner = "di5:66",
    )

    @Test
    fun destroyedOwnedHostedDisplayCanRetireItsLeaseOnlyAfterTaskProof() {
        val ownership = hostedOwner()
        fun mayClear(
            retired: Boolean = true,
            reclaimed: Boolean = true,
            active: Boolean = false,
            record: DiLink5ProjectionOwnership = ownership,
            name: String = ownership.virtualDisplayName,
            boot: String = ownership.bootId,
        ) = retiredHostedLeaseMayClear(
            "di5:66", "com.example.app", 8, name, boot, record, retired, reclaimed, active,
        )
        assertTrue(mayClear())
        assertFalse(mayClear(retired = false))
        assertFalse(mayClear(reclaimed = false))
        assertFalse(mayClear(active = true))
        assertFalse(mayClear(name = "XDJAScreenProjection_1"))
        assertFalse(mayClear(boot = "different-boot"))
        assertFalse(mayClear(record = ownership.copy(transport = "direct")))
        assertFalse(mayClear(record = ownership.copy(contentDisplayId = 4)))
        assertFalse(mayClear(record = ownership.copy(generation = 78)))
        assertFalse(mayClear(record = ownership.copy(packageName = "com.other.app")))
        assertFalse(mayClear(record = ownership.copy(densityOwner = "di5:67")))
        assertFalse(mayClear(record = ownership.copy(densityOwner = "")))
        assertTrue(hostedDensityOwnerMatches(
            "di5:66:resize:77", ownership.copy(densityOwner = ""),
        ))
        assertFalse(hostedDensityOwnerMatches(
            "di5:66:resize:78", ownership.copy(densityOwner = ""),
        ))
    }

    @Test
    fun failedJournalClearRetainsLeaseAndActiveMembershipForRetry() {
        val leases = linkedMapOf("di5:66" to 360, "legacy:9" to 320)
        val active = linkedSetOf("di5:66")
        val removing = mapOf("di5:66" to 360)
        assertFalse(removeDensityLeasesDurably(leases, active, removing) { false })
        assertEquals(360, leases["di5:66"])
        assertEquals(setOf("di5:66"), active)
        assertFalse(removeDensityLeasesDurably(leases, active, removing) {
            throw IllegalStateException("storage unavailable")
        })
        assertEquals(360, leases["di5:66"])
        assertEquals(setOf("di5:66"), active)
        assertTrue(removeDensityLeasesDurably(leases, active, removing) { true })
        assertEquals(mapOf("legacy:9" to 320), leases)
        assertTrue(active.isEmpty())
    }

    @Test
    fun promotionCrashAcceptsOnlyTheRecordedTransitionAndItsCanonicalOwner() {
        val record = hostedOwner().copy(densityOwner = "di5:66:resize:77")
        fun mayClear(owner: String) = retiredHostedLeaseMayClear(
            owner, "com.example.app", 8, "OverDriveCluster-77", "boot-a",
            record, retirementConfirmed = true, tasksReclaimed = true, active = false,
        )
        assertTrue(mayClear("di5:66:resize:77")) // before the durable lease promotion
        assertTrue(mayClear("di5:66")) // after promotion, before the VD marker update
        assertFalse(mayClear("di5:67"))
        assertFalse(mayClear("di5:66:resize:78"))
        assertFalse(mayClear("di5:77"))
        assertFalse(hostedDensityOwnerMatches(
            "di5:66", record.copy(densityOwner = "di5:66:resize:78"),
        ))
        assertFalse(hostedDensityOwnerMatches(
            "di5:66", record.copy(densityOwner = "invalid"),
        ))
    }
}
