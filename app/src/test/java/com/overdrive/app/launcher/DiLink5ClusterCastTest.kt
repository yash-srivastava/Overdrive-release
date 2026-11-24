package com.overdrive.app.launcher

import android.view.Display
import com.overdrive.app.surveillance.ClusterProjectionController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DiLink5ClusterCastTest {
    @Test
    fun factoryMiniMapUsesOnlyTheRunningSharedMiniMapAndItsLogicalWindowSize() {
        val base = """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_1", displayId 4, real 1920 x 720, state ON}"""
        val override = base.replace("mBase", "mOverride").replace("x 720", "x 660")
        val full = base.replace("_1", "_0").replace("displayId 4", "displayId 3")
        val background = base.replace("shared_fission_bg_XDJAScreenProjection_1", "fission_bg_XDJAScreenProjection")
        assertEquals(DiLink5ProjectionDisplayRef(4, "shared_fission_bg_XDJAScreenProjection_1", 1920, 660),
            resolveFactoryMiniMapDisplay(listOf(full, background, base, override)))
        assertNull(resolveFactoryMiniMapDisplay(listOf(full, background, base, override.replace("state ON", "state OFF"))))
        assertNull(resolveFactoryMiniMapDisplay(listOf(base, override.replace("1920 x 660", "1 x 1"))))
    }

    @Test
    fun aConfirmedReturnMakesTheSameTasksNextFullscreenResetADistinctDensityLoss() {
        val watch = DirectDensityLossEpisodes(17)
        val strikes = linkedSetOf<String>()
        val returned = ClusterFreeformWindow.TaskLocation(true, 42, 8, 1)
        assertTrue(watch.confirmTaskReturn(returned, 8))
        val reason = directDensityLossReason(42, 42, 0, 8, 1)
        assertEquals(DirectDensityLossReason.TASK_MODE_RESET, reason)
        val first = watch.episodeForLoss(42)
        assertTrue(recordDirectDensityLossForAttempt(strikes, "di5:17", first))

        // An accepted correction/relaunch or an unreadable foreground-compatible poll
        // does not prove a return; every still-off-target poll remains the same loss.
        assertFalse(watch.confirmTaskReturn(ClusterFreeformWindow.TaskLocation(false, 42, 8), 8))
        assertFalse(watch.confirmTaskReturn(ClusterFreeformWindow.TaskLocation(true, -1, 8), 8))
        assertFalse(watch.confirmTaskReturn(ClusterFreeformWindow.TaskLocation(true, 42, -1), 8))
        assertFalse(watch.confirmTaskReturn(ClusterFreeformWindow.TaskLocation(true, 42, 0), 8))
        assertEquals(first, watch.episodeForLoss(0)) // relaunch cleared the captured placed ID
        assertFalse(recordDirectDensityLossForAttempt(strikes, "di5:17", watch.episodeForLoss(42)))
        assertEquals(1, strikes.size)

        assertTrue(watch.confirmTaskReturn(returned, 8))
        assertTrue(watch.confirmTaskReturn(returned, 8)) // repeated healthy polls do not add episodes
        val second = watch.episodeForLoss(42)
        assertEquals("17:1:42", second)
        assertTrue(recordDirectDensityLossForAttempt(strikes, "di5:17", second))
        assertEquals(2, strikes.size)
        val unsafe = mapOf("com.example.navigator" to DirectDensityVerdict(reason!!, 1234L))
        assertEquals(unsafe, readDirectDensityVerdicts(
            JSONObject(serializeDirectDensityVerdicts(unsafe).toString()),
        ))
    }

    @Test
    fun alternativeTargetPreservesDeferredEnableAndAppVisibleOrderingWithoutDuplicateCommands() {
        val events = mutableListOf<String>()
        assertTrue(prepareDiLink5AlternativeCompositor(true, false) {
            events += "enable16"
            true
        })
        assertTrue(events.isEmpty()) // the shell-only replacement must place and verify first
        events += "verified-shell-placement"
        events += "enable16"
        assertEquals(listOf("verified-shell-placement", "enable16"), events)

        events.clear()
        assertFalse(prepareDiLink5AlternativeCompositor(true, true) {
            events += "marked-enable16"
            true
        })
        events += "app-visible-placement"
        assertEquals(listOf("marked-enable16", "app-visible-placement"), events)

        events.clear()
        assertFalse(prepareDiLink5AlternativeCompositor(false, true) {
            events += "duplicate-enable16"
            true
        })
        assertTrue(events.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectedAlternativeEnableCannotProceedAsAnUnmarkedSuccessfulPlacement() {
        prepareDiLink5AlternativeCompositor(true, true) { false }
    }

    @Test
    fun resizeRefusesAnotherPackageOrAnotherSessionOfTheSamePackage() {
        assertTrue(diLink5ResizeOwnershipMatches("com.example.app", 7, "com.example.app", 7))
        assertFalse(diLink5ResizeOwnershipMatches("com.old.app", 7, "com.example.app", 7))
        assertFalse(diLink5ResizeOwnershipMatches("com.example.app", 7, "com.example.app", 8))
        assertFalse(diLink5ResizeOwnershipMatches("com.example.app", -1, "com.example.app", 8))
        assertTrue(diLink5ResizeOwnershipMatches(null, 0, "com.example.app", 8))
    }

    @Test
    fun unpublishedAllocationFailureRetainsGenerationUntilTheNextConfirmedCleanup() {
        val pending = linkedSetOf<Long>()
        val ownership = linkedSetOf<Long>()
        val allocation = startHostedAllocation<String>(
            register = { pending += 7; ownership += 7; true },
            start = { null },
        )
        assertNull(allocation.value) // no Session was ever published
        var releaseSucceeds = false
        fun cleanup(): Boolean = completeHostedGenerationCleanup(
            densityRestored = true,
            densityLeasePending = false,
        ) {
            finishHostedResourceCleanup(
                pending.toList(),
                release = { generation ->
                    if (!releaseSucceeds) false else {
                        ownership.remove(generation)
                        true
                    }
                },
                clearRegistration = pending::remove,
            )
        }
        assertFalse(cleanup())
        assertEquals(setOf(7L), pending)
        assertEquals(setOf(7L), ownership)
        releaseSucceeds = true
        assertTrue(cleanup())
        assertTrue(pending.isEmpty())
        assertTrue(ownership.isEmpty())
    }

    @Test
    fun failedDensityRestoreAndAccDeferralRetainAllHostedResourcesForRecovery() {
        val events = mutableListOf<String>()
        val resources = linkedSetOf(7L, 8L)
        val release = {
            finishHostedResourceCleanup(
                resources.toList(),
                release = { events += "release-$it"; true },
                clearRegistration = resources::remove,
            )
        }
        assertFalse(completeHostedGenerationCleanup(false, true, release))
        assertFalse(completeHostedGenerationCleanup(true, true, release))
        assertTrue(events.isEmpty())
        assertEquals(setOf(7L, 8L), resources)
        assertTrue(completeHostedGenerationCleanup(true, false, release))
        assertEquals(listOf("release-7", "release-8"), events)
        assertTrue(resources.isEmpty())
    }

    @Test
    fun trinketFirmwareRequiresTheOemSharedDisplay() {
        assertTrue(
            requiresSharedDiLink5ProjectionDisplay(
                "trinket",
                "trinket",
                "BYD-AUTO/trinket/trinket:13/build"
            )
        )
        assertTrue(
            requiresSharedDiLink5ProjectionDisplay(
                "D50F_LC", null, null
            )
        )
        assertFalse(
            requiresSharedDiLink5ProjectionDisplay(
                "sa8155p", "byd_ivi", "BYD-AUTO/byd_ivi/release"
            )
        )
    }

    @Test
    fun containerEnableRequiresAPositiveResolvedAndAppVisibleTarget() {
        assertFalse(canDispatchDiLink5ContainerEnable(-1))
        assertFalse(canDispatchDiLink5ContainerEnable(Display.DEFAULT_DISPLAY))
        assertTrue(canDispatchDiLink5ContainerEnable(4))

        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        val methodStart = source.indexOf("private fun enableAndAwaitProjectionDisplay(")
        val methodEnd = source.indexOf("private fun readProjectionConfig()", methodStart)
        val method = source.substring(methodStart, methodEnd)
        val visibilityCheck = method.indexOf("isAppDisplayVisible(")
        val enableDispatch = method.indexOf("sendDiLink5ContainerInfoResult(16)")
        assertTrue(visibilityCheck >= 0)
        assertTrue(enableDispatch > visibilityCheck)
    }

    @Test
    fun autoUsesHostedForEveryDefiniteDirectFailureOnly() {
        assertTrue(shouldFallbackToHostedAfterDirectFailure("direct", true, false, true))
        assertFalse(shouldFallbackToHostedAfterDirectFailure("direct", true, false, false))
        assertFalse(shouldFallbackToHostedAfterDirectFailure("direct", false, false, true))
        assertFalse(shouldFallbackToHostedAfterDirectFailure("hosted", true, false, true))
        assertFalse(shouldFallbackToHostedAfterDirectFailure("direct", true, true, true))
    }

    @Test
    fun persistedHostedGenerationsReserveFutureAdmission() {
        assertEquals(1L, nextPositiveGeneration(0L))
        assertEquals(43L, nextPositiveGeneration(42L))
        assertEquals(1L, nextPositiveGeneration(Long.MAX_VALUE))
        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        assertTrue(source.contains("prepareHostedOwnershipForAdmission()"))
        assertTrue(source.contains("previous hosted projection ownership is unresolved"))
        assertTrue(source.contains("DiLink5ProjectionOwnership.loadAll()"))
        assertTrue(source.contains("nextHostedResourceGeneration()"))
    }

    @Test
    fun markerTargetAndDensityOrderingPreserveRecoveryEvidence() {
        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        val markerStart = source.indexOf("private fun markCompositorEnableInFlight(")
        val markerEnd = source.indexOf("private fun markDisplayReclaimed(", markerStart)
        val markers = source.substring(markerStart, markerEnd)
        assertEquals(3, Regex("appMoved = appMovedGeneration == expectedGeneration").findAll(markers).count())

        val enableStart = source.indexOf("private fun enableAndAwaitProjectionDisplay(")
        val enableEnd = source.indexOf("private fun readProjectionConfig()", enableStart)
        val enable = source.substring(enableStart, enableEnd)
        assertTrue(enable.indexOf("val preferredName") < enable.indexOf("isAppDisplayVisible("))
        assertTrue(enable.contains("return configureProjectionTarget(context, preEnableTarget"))

        val hostedStart = source.indexOf("private fun configureHostedProjectionTarget(")
        val hostedEnd = source.indexOf("private fun projectionOwnership(", hostedStart)
        val hosted = source.substring(hostedStart, hostedEnd)
        assertTrue(hosted.indexOf("nativeDensityForDisplay") <
            hosted.indexOf("HostedProjectionGeometry.calculate"))
        assertTrue(hosted.indexOf("uncertainHostedResources.getOrPut") <
            hosted.indexOf("DiLink5HostedProjectionBridge.start("))
    }

    @Test
    fun longLivedLifecycleAndFinalMutationFencesCoverAppAndMapSessions() {
        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        assertTrue(source.contains("private fun startLifecycleGuardian("))
        assertTrue(source.contains("LIFECYCLE_GUARD_POLL_MS"))
        assertTrue(source.contains("projected task exited after startup"))
        assertTrue(source.contains("projection display disappeared after startup"))
        assertTrue(source.contains("private val taskMutationLock"))
        assertTrue(source.contains("withLiveTaskMutation(live"))
        val surface = source.substring(
            source.indexOf("fun onHostedSurfaceLost("),
            source.indexOf("fun currentTargetDisplayWidth", source.indexOf("fun onHostedSurfaceLost(")),
        )
        assertTrue(surface.contains("expectedKind = lost.second"))
        assertTrue(surface.contains("lost.second == SessionKind.APP"))
        assertTrue(surface.contains("lost.second == SessionKind.MAP"))
        assertTrue(surface.contains("onDiLink5ProjectionFailed()"))
    }

    @Test
    fun mapSurfaceLossClearsProjectorEvenWhenCleanupOwnershipIsRetained() {
        var mapFailures = 0
        assertFalse(
            completeHostedSurfaceLoss(
                isMap = true,
                stop = { false },
                onMapFailure = { mapFailures++ },
            ),
        )
        assertEquals(1, mapFailures)

        assertFalse(
            completeHostedSurfaceLoss(
                isMap = false,
                stop = { false },
                onMapFailure = { mapFailures++ },
            ),
        )
        assertEquals(1, mapFailures)
    }

    @Test
    fun movedPendingResizeSurfaceLossReclaimsTheCanonicalSession() {
        assertTrue(pendingHostedSurfaceLossNeedsCleanup(
            preparing = true, active = false, pendingReplacement = false,
            taskMayBeMoved = false, packageMatches = false,
        ))
        assertTrue(pendingHostedSurfaceLossNeedsCleanup(
            preparing = false, active = true, pendingReplacement = true,
            taskMayBeMoved = true, packageMatches = true,
        ))
        // An unmoved replacement can fail while the previous projection remains usable.
        assertFalse(pendingHostedSurfaceLossNeedsCleanup(
            preparing = false, active = true, pendingReplacement = true,
            taskMayBeMoved = false, packageMatches = true,
        ))
        assertFalse(pendingHostedSurfaceLossNeedsCleanup(
            preparing = false, active = true, pendingReplacement = true,
            taskMayBeMoved = true, packageMatches = false,
        ))
        assertFalse(pendingHostedSurfaceLossNeedsCleanup(
            preparing = false, active = false, pendingReplacement = true,
            taskMayBeMoved = true, packageMatches = true,
        ))
        // A predecessor awaiting release is cleanup-owned, but is no longer a pending replacement.
        assertFalse(pendingHostedSurfaceLossNeedsCleanup(
            preparing = false, active = true, pendingReplacement = false,
            taskMayBeMoved = true, packageMatches = true,
        ))
    }

    @Test
    fun retiredSurfaceLossAndAQueuedOldLossCannotStopThePublishedReplacement() {
        var publishedResource = 17L
        val cleanupResources = linkedSetOf(17L, 18L)
        val pendingReplacements = linkedSetOf(18L)
        fun needsCleanup(resource: Long): Boolean = hostedSurfaceLossNeedsCleanup(
            resourceGeneration = resource,
            publishedResourceGeneration = publishedResource,
            registeredForCleanup = resource in cleanupResources,
            preparing = false,
            active = true,
            pendingReplacement = resource in pendingReplacements,
            taskMayBeMoved = true,
            packageMatches = true,
        )

        assertTrue(needsCleanup(17)) // loss selected while the original Session is published
        assertTrue(needsCleanup(18)) // a moved, unpublished replacement must still be reclaimed

        // Publication and stop admission hold the same task/state locks in production. Old 17
        // remains cleanup-owned if its retirement fails; that role must not authorize stopping 18.
        pendingReplacements.remove(18)
        publishedResource = 18
        assertFalse(needsCleanup(17))
        assertTrue(cleanupResources.contains(17))
        var stoppedResource = 0L
        assertFalse(claimHostedSurfaceLoss(
            current = { needsCleanup(17) },
            claim = { stoppedResource = publishedResource },
        ))
        assertEquals(0L, stoppedResource)
        assertEquals(18L, publishedResource)

        // Losing the actually published replacement still owns the final cleanup claim.
        assertTrue(claimHostedSurfaceLoss(
            current = { needsCleanup(18) },
            claim = { stoppedResource = publishedResource },
        ))
        assertEquals(18L, stoppedResource)
    }

    @Test
    fun hostedAllocationRegistersCleanupBeforeStartAndRetainsNullOutcome() {
        val registered = AtomicBoolean(false)
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val result = AtomicReference<HostedAllocationStart<String>>()
        val allocation = Thread {
            result.set(startHostedAllocation(
                register = {
                    registered.set(true)
                    true
                },
                start = {
                    startEntered.countDown()
                    releaseStart.await(2, TimeUnit.SECONDS)
                    null
                },
            ))
        }

        allocation.start()
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        assertTrue(registered.get())
        releaseStart.countDown()
        allocation.join(2_000L)
        assertTrue(result.get().registered)
        assertTrue(result.get().publicationAllowed)
        assertNull(result.get().value)

        val stillCurrent = AtomicBoolean(true)
        val lateEntered = CountDownLatch(1)
        val releaseLate = CountDownLatch(1)
        val late = AtomicReference<HostedAllocationStart<String>>()
        val lateWorker = Thread {
            late.set(startHostedAllocation(
                register = { true },
                start = {
                    lateEntered.countDown()
                    releaseLate.await(2, TimeUnit.SECONDS)
                    "display"
                },
                current = { stillCurrent.get() },
            ))
        }
        lateWorker.start()
        assertTrue(lateEntered.await(2, TimeUnit.SECONDS))
        stillCurrent.set(false)
        releaseLate.countDown()
        lateWorker.join(2_000L)
        assertFalse(lateWorker.isAlive)
        assertTrue(late.get().registered)
        assertFalse(late.get().publicationAllowed)
        assertEquals("display", late.get().value)

        var started = false
        val rejected = startHostedAllocation(
            register = { false },
            start = { started = true; "unexpected" },
        )
        assertFalse(rejected.registered)
        assertFalse(started)
    }

    @Test
    fun guardianAndResizeMutationsShareOneSerializedAuthority() {
        val authority = TaskMutationAuthority()
        val guardianEntered = CountDownLatch(1)
        val releaseGuardian = CountDownLatch(1)
        val resizeEntered = AtomicBoolean(false)
        val guardian = Thread {
            authority.mutate {
                guardianEntered.countDown()
                releaseGuardian.await(2, TimeUnit.SECONDS)
            }
        }
        val resize = Thread {
            authority.mutate { resizeEntered.set(true) }
        }

        guardian.start()
        assertTrue(guardianEntered.await(2, TimeUnit.SECONDS))
        resize.start()
        Thread.sleep(50L)
        assertFalse(resizeEntered.get())
        releaseGuardian.countDown()
        guardian.join(2_000L)
        resize.join(2_000L)
        assertTrue(resizeEntered.get())
    }

    @Test
    fun lateHostedAllocationRepublishesBeforeReleaseAndRetainsFailure() {
        val events = mutableListOf<String>()
        var registered = true
        assertFalse(reconcileLateHostedAllocation(
            republish = { events += "persist"; true },
            release = { events += "release"; false },
            updateRegistration = { released -> registered = !released },
        ))
        assertEquals(listOf("persist", "release"), events)
        assertTrue(registered)

        events.clear()
        assertTrue(reconcileLateHostedAllocation(
            republish = { events += "persist"; true },
            release = { events += "release"; true },
            updateRegistration = { released -> registered = !released },
        ))
        assertEquals(listOf("persist", "release"), events)
        assertFalse(registered)
        assertTrue(canReleaseUnpublishedHostedResource(false, false))
        assertFalse(canReleaseUnpublishedHostedResource(true, false))
        assertFalse(canReleaseUnpublishedHostedResource(false, true))
    }

    @Test
    fun hostedMoveDispatchCannotRunBeforeRegistrationOrAfterStopWins() {
        var current = false
        var registered = false
        var dispatched = 0
        assertFalse(runHostedMoveDispatch(
            current = { current },
            register = { registered = true },
            dispatch = Runnable { dispatched++ },
        ))
        assertFalse(registered)
        assertEquals(0, dispatched)

        current = true
        assertTrue(runHostedMoveDispatch(
            current = { current },
            register = { registered = true },
            dispatch = Runnable {
                assertTrue(registered)
                dispatched++
            },
        ))
        assertEquals(1, dispatched)
    }

    @Test
    fun replacementCleanupRegistrationSurvivesUntilDensityAndResourcesAreClean() {
        var registered = true
        var releases = 0
        assertFalse(completeHostedReplacementCleanup(
            restoreDensity = { false },
            releaseResources = { releases++; true },
            clearRegistration = { registered = false },
        ))
        assertTrue(registered)
        assertEquals(0, releases)

        assertFalse(completeHostedReplacementCleanup(
            restoreDensity = { true },
            releaseResources = { releases++; false },
            clearRegistration = { registered = false },
        ))
        assertTrue(registered)
        assertEquals(1, releases)

        assertTrue(completeHostedReplacementCleanup(
            restoreDensity = { true },
            releaseResources = { releases++; true },
            clearRegistration = { registered = false },
        ))
        assertFalse(registered)
        assertEquals(2, releases)
    }

    @Test
    fun hostedResizeRetainsEveryPossiblyMovedOrStopRacedReplacement() {
        assertEquals(
            HostedResizeMoveDisposition.RELEASE_UNMOVED,
            hostedResizeMoveDisposition(
                ClusterFreeformWindow.MoveTaskResult.NOT_DISPATCHED,
                sessionCurrent = true,
            ),
        )
        assertEquals(
            HostedResizeMoveDisposition.RETAIN_UNCERTAIN,
            hostedResizeMoveDisposition(
                ClusterFreeformWindow.MoveTaskResult.DISPATCHED_UNCONFIRMED,
                sessionCurrent = true,
            ),
        )
        assertEquals(
            HostedResizeMoveDisposition.RETAIN_UNCERTAIN,
            hostedResizeMoveDisposition(
                ClusterFreeformWindow.MoveTaskResult.NOT_DISPATCHED,
                sessionCurrent = false,
            ),
        )
        assertEquals(
            HostedResizeMoveDisposition.CONTINUE_VERIFICATION,
            hostedResizeMoveDisposition(
                ClusterFreeformWindow.MoveTaskResult.CONFIRMED,
                sessionCurrent = true,
            ),
        )

        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        val start = source.indexOf("private fun resizeHosted(")
        val end = source.indexOf("fun recoverAtBoot()", start)
        val resize = source.substring(start, end)
        assertTrue(resize.indexOf("registeredForCleanup") <
            resize.indexOf("moveTaskToDisplayResult("))
        assertTrue(resize.indexOf("nativeDensityForDisplay(live.physicalDisplayId)") <
            resize.indexOf("HostedProjectionGeometry.calculate"))
        assertTrue(resize.indexOf("startHostedAllocation(") <
            resize.indexOf("DiLink5HostedProjectionBridge.start("))
        assertTrue(resize.indexOf("synchronized(taskMutationLock.lock)") <
            resize.indexOf("findTaskLocation("))
        assertTrue(resize.indexOf("synchronized(taskMutationLock.lock)") <
            resize.indexOf("startTaskGuardian("))
        assertTrue(resize.indexOf("prepareHostedReplacement(") <
            resize.indexOf("moveTaskToDisplayResult("))
        assertTrue(resize.indexOf("restoreForSession(densityOwner)") <
            resize.indexOf("promoteHostedReplacement("))
        assertTrue(resize.contains("runHostedMoveDispatch("))
        assertTrue(resize.contains("synchronized(stateLock)"))
    }

    @Test
    fun densityAdoptionUsesCompleteOnTargetEvidence() {
        assertEquals(
            DensityAdoptionDecision.PRESERVE_ADOPTED,
            densityAdoptionDecision(true),
        )
        assertEquals(
            DensityAdoptionDecision.APPLY_FOR_NEW_TASK,
            densityAdoptionDecision(false),
        )
        assertEquals(
            DensityAdoptionDecision.DEFER_UNCERTAIN,
            densityAdoptionDecision(null),
        )

        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        val start = source.indexOf("private fun launchAndVerify(")
        val end = source.indexOf("private fun awaitStableTargetForeground(", start)
        val method = source.substring(start, end)
        assertTrue(method.contains("ClusterFreeformWindow.packageTaskOnDisplay"))
        assertTrue(method.indexOf("densityAdoptionDecision(") <
            method.indexOf("restoreForDisplay(displayId)"))
    }

    @Test
    fun directAttemptsReassertFreeformAndHostedPreparationFailsClosed() {
        val cast = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
        val freeform = File("src/main/java/com/overdrive/app/launcher/ClusterFreeformWindow.java").readText()
        val density = File("src/main/java/com/overdrive/app/launcher/ClusterAppDensity.kt").readText()

        assertTrue(cast.contains("ClusterFreeformWindow.beginDirectPlacementAttempt()"))
        assertTrue(cast.contains("if (!ClusterFreeformWindow.prepareHostedFreeformState())"))
        assertTrue(cast.contains("if (ClusterFreeformWindow.prepareHostedFreeformState())"))
        assertTrue(freeform.contains("freeformRecoveryChecked = false;"))
        assertTrue(freeform.contains("freeformSettingChecked = false;"))
        val directPreparation = freeform.substring(
            freeform.indexOf("static void beginDirectPlacementAttempt()"),
            freeform.indexOf("private static TaskCache cacheFor"),
        )
        assertTrue(directPreparation.contains("ensureFreeformSupportEnabled();"))
        assertTrue(density.contains("ClusterFreeformWindow.freeformSupportStatus()"))
    }

    @Test
    fun projectionDisplayPrefersThePhysicallyRoutedFamily() {
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_1",
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "XDJAScreenProjection_1",
                    "shared_fission_bg_XDJAScreenProjection_0",
                    "shared_fission_bg_XDJAScreenProjection_1"
                ),
                preferFull = false,
                requireShared = true
            )
        )
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_0",
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "shared_fission_bg_XDJAScreenProjection_0",
                    "shared_fission_bg_XDJAScreenProjection_1"
                ),
                preferFull = true,
                requireShared = true
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "fission_bg_XDJAScreenProjection",
                    "XDJAScreenProjection_0"
                ),
                preferFull = true,
                requireShared = true
            )
        )
        assertEquals(
            "XDJAScreenProjection_1",
            selectDiLink5ProjectionDisplayName(
                listOf("XDJAScreenProjection_1"),
                preferFull = false,
                requireShared = false
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf("fission_bg_XDJAScreenProjection", "unrelated"),
                preferFull = false,
                requireShared = false
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "FISSION_BG_xdjascreenprojection"
                ),
                preferFull = false,
                requireShared = false
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf("Remote_Dashboard"),
                preferFull = true
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf("presentation", "unrelated"),
                preferFull = false
            )
        )
    }

    @Test
    fun aaosHdmiTopologyFailsClosedBeforeContainerCommands() {
        assertTrue(
            isClosedAaosProjectionTopology(
                "DX_BYD_AUTO",
                automotiveFeature = true,
                displayNames = listOf("內置畫面", "HDMI 螢幕")
            )
        )
        assertTrue(
            isClosedAaosProjectionTopology(
                "other_product",
                automotiveFeature = true,
                displayNames = listOf("Built-in Screen", "HDMI Screen")
            )
        )
        assertFalse(
            isClosedAaosProjectionTopology(
                "trinket",
                automotiveFeature = true,
                displayNames = listOf(
                    "Built-in Screen",
                    "shared_fission_bg_XDJAScreenProjection_0"
                )
            )
        )
        assertFalse(
            isClosedAaosProjectionTopology(
                "phone_like",
                automotiveFeature = false,
                displayNames = listOf("Built-in Screen", "HDMI Screen")
            )
        )
        assertTrue(
            isClosedAaosProjectionTopology(
                "DX_BYD_AUTO",
                automotiveFeature = true,
                displayNames = listOf(
                    "Built-in Screen", "HDMI Screen", "Remote_Dashboard"
                )
            )
        )
    }

    @Test
    fun runtimeTopologySeparatesTheTwoFieldLogFamilies() {
        assertEquals(
            DiLink5ProjectionTopology.TRINKET_DEBUG_ONLY,
            classifyDiLink5ProjectionTopology(
                product = "trinket",
                device = "trinket",
                fingerprint = "BYD-AUTO/trinket/trinket:13/build",
                automotiveFeature = false,
                fissionSingleOs = "0",
                displayNames = listOf(
                    "Built-in Screen",
                    "fission_bg_XDJAScreenProjection"
                )
            )
        )
        assertEquals(
            DiLink5ProjectionTopology.AAOS_HDMI_CLOSED,
            classifyDiLink5ProjectionTopology(
                product = "unknown_byd_product",
                device = null,
                fingerprint = null,
                automotiveFeature = true,
                fissionSingleOs = null,
                displayNames = listOf("內置畫面", "HDMI 螢幕")
            )
        )
        assertEquals(
            DiLink5ProjectionTopology.SHARED_FISSION,
            classifyDiLink5ProjectionTopology(
                product = "trinket",
                device = "trinket",
                fingerprint = null,
                automotiveFeature = false,
                fissionSingleOs = "0",
                displayNames = listOf(
                    "fission_bg_XDJAScreenProjection",
                    "shared_fission_bg_XDJAScreenProjection_0"
                )
            )
        )
        assertEquals(
            DiLink5ProjectionTopology.SINGLE_OS_CLOSED,
            classifyDiLink5ProjectionTopology(
                product = "byd",
                device = "byd",
                fingerprint = null,
                automotiveFeature = false,
                fissionSingleOs = "1",
                displayNames = listOf("Built-in Screen")
            )
        )
    }

    @Test
    fun pendingMarkerAloneDoesNotClaimCompositorOwnership() {
        assertFalse(
            mayOwnDiLink5ProjectionCompositor(
                pending = true, powered = false
            )
        )
        assertTrue(
            mayOwnDiLink5ProjectionCompositor(
                pending = false, powered = true
            )
        )
        assertFalse(
            mayOwnDiLink5ProjectionCompositor(
                pending = false, powered = false
            )
        )
        assertTrue(
            mayOwnDiLink5ProjectionCompositor(
                pending = false,
                powered = false,
                enableIndeterminate = true
            )
        )
        assertTrue(
            mayOwnDiLink5ProjectionCompositor(
                pending = false,
                powered = false,
                teardownIndeterminate = true
            )
        )
    }

    @Test
    fun uncertainTaskCleanupRetriesWithoutClaimingTheFactoryCompositor() {
        val source = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()
            .substringAfter("private fun recoverOrphanedStateSynchronously()")
            .substringBefore("private fun readRecoveryMarker()")
        val ownership = source.substringAfter("val needsPowerDown =").substringBefore("val taskMayBeMoved")
        assertTrue(ownership.contains("mayOwnDiLink5ProjectionCompositor("))
        assertTrue(ownership.contains("marker.powered || poweredGeneration > 0L"))
        assertTrue(ownership.contains("marker.enableIndeterminate || enableIndeterminateGeneration > 0L"))
        assertTrue(ownership.contains("marker.teardownIndeterminate || teardownIndeterminateGeneration > 0L"))
        assertFalse(ownership.contains("cleanupUncertain"))
        assertTrue(source.contains("!needsPowerDown && !taskMayBeMoved && !cleanupUncertain"))
        assertTrue(source.contains("val poweredDown = !needsPowerDown || powerDownCompositor("))
    }

    @Test
    fun indeterminateEnableOwnershipExpiresOnlyAcrossAProvenBootChange() {
        val bootA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val bootB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        assertTrue(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = true,
                originBootId = bootA,
                currentBootId = bootA
            )
        )
        assertTrue(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = true,
                originBootId = "",
                currentBootId = bootA
            )
        )
        assertFalse(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = true,
                originBootId = bootA,
                currentBootId = bootB
            )
        )
        assertFalse(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = false,
                originBootId = bootA,
                currentBootId = bootA
            )
        )
        assertTrue(
            diLink5IndeterminateCommandMayStillComplete(
                indeterminate = true,
                originBootId = bootA,
                currentBootId = bootA
            )
        )
        assertFalse(
            diLink5IndeterminateCommandMayStillComplete(
                indeterminate = true,
                originBootId = bootA,
                currentBootId = bootB
            )
        )
        assertTrue(
            diLink5IndeterminateCommandMayStillComplete(
                indeterminate = true,
                originBootId = "corrupt-boot-id",
                currentBootId = bootB
            )
        )
        assertEquals(bootA, canonicalDiLink5BootIdOrNull(bootA))
        assertNull(canonicalDiLink5BootIdOrNull("corrupt-boot-id"))
        assertNull(canonicalDiLink5BootIdOrNull(bootA.uppercase()))
    }

    @Test
    fun recoveryMarkerFieldsRejectCoercionAndMalformedBootIds() {
        val boot = "11111111-1111-1111-1111-111111111111"
        val valid = JSONObject()
            .put("flag", true)
            .put("name", "pkg")
            .put("boot", boot)
        assertEquals(
            true,
            DiLink5ClusterCast.strictMarkerBoolean(
                valid, "flag", false
            )
        )
        assertEquals(
            "pkg",
            DiLink5ClusterCast.strictMarkerString(
                valid, "name", ""
            )
        )
        assertEquals(
            boot,
            DiLink5ClusterCast.strictMarkerBootId(valid, "boot")
        )

        val malformed = JSONObject()
            .put("flag", "true")
            .put("name", 7)
            .put("boot", "not-a-kernel-boot-id")
        assertNull(
            DiLink5ClusterCast.strictMarkerBoolean(
                malformed, "flag", false
            )
        )
        assertNull(
            DiLink5ClusterCast.strictMarkerString(
                malformed, "name", ""
            )
        )
        assertNull(
            DiLink5ClusterCast.strictMarkerBootId(
                malformed, "boot"
            )
        )
    }

    @Test
    fun recoveryRetriesAreBoundedAndSkipSameBootIndeterminateCommands() {
        assertEquals(
            1_000L,
            DiLink5ClusterCast.recoveryRetryDelayMs(0, false)
        )
        assertEquals(
            3_000L,
            DiLink5ClusterCast.recoveryRetryDelayMs(1, false)
        )
        assertEquals(
            8_000L,
            DiLink5ClusterCast.recoveryRetryDelayMs(2, false)
        )
        assertEquals(
            -1L,
            DiLink5ClusterCast.recoveryRetryDelayMs(3, false)
        )
        assertEquals(
            -1L,
            DiLink5ClusterCast.recoveryRetryDelayMs(0, true)
        )
    }

    @Test
    fun legacyRecoveryPropagatesTheExactSameBootCause() {
        val recovered = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED
            )
        assertTrue(recovered.recovered)
        assertFalse(recovered.sameBootIndeterminate)

        val sameBoot = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE
            )
        assertFalse(sameBoot.recovered)
        assertTrue(sameBoot.sameBootIndeterminate)

        val taskFailure = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = false,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED
            )
        assertFalse(taskFailure.recovered)
        assertFalse(taskFailure.sameBootIndeterminate)
    }

    @Test
    fun reclaimedSharedDisplaysAreSkippedAndTheLearnedPreferenceWins() {
        val shared = listOf(
            "fission_bg_XDJAScreenProjection",
            "shared_fission_bg_XDJAScreenProjection_0",
            "shared_fission_bg_XDJAScreenProjection_1"
        )
        // Default ranking is unchanged: _0 first.
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_0",
            selectDiLink5ProjectionDisplayName(shared, true, true)
        )
        // An OEM cluster app reclaimed _0 → the sibling shared surface is the
        // alternative; never the non-physical debug display.
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_1",
            selectDiLink5ProjectionDisplayName(
                shared, true, true,
                excludedNames = setOf("shared_fission_bg_xdjascreenprojection_0")
            )
        )
        // Both shared surfaces reclaimed → no alternative (strict), so the
        // caller can report the takeover instead of retrying the same surface.
        assertNull(
            selectDiLink5ProjectionDisplayName(
                shared, true, true,
                excludedNames = setOf(
                    "shared_fission_bg_xdjascreenprojection_0",
                    "shared_fission_bg_xdjascreenprojection_1"
                )
            )
        )
        // A learned preference is honoured when present and not excluded.
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_1",
            selectDiLink5ProjectionDisplayName(
                shared, true, true,
                preferredName = "shared_fission_bg_XDJAScreenProjection_1"
            )
        )
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_0",
            selectDiLink5ProjectionDisplayName(
                shared, true, true,
                preferredName = "shared_fission_bg_XDJAScreenProjection_1",
                excludedNames = setOf("shared_fission_bg_xdjascreenprojection_1")
            )
        )
        // A preference naming an absent display falls back to the ranking.
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_0",
            selectDiLink5ProjectionDisplayName(
                shared, true, true,
                preferredName = "shared_fission_bg_XDJAScreenProjection_9"
            )
        )
        assertTrue(
            isSharedDiLink5ProjectionDisplayName(
                "shared_fission_bg_XDJAScreenProjection_1"
            )
        )
        assertFalse(
            isSharedDiLink5ProjectionDisplayName("fission_bg_XDJAScreenProjection")
        )
    }

    @Test
    fun terminalLegacyVerdictsEndTheRecoveryLadder() {
        // Fully recovered → terminal, fence released.
        val recovered = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED
            )
        assertTrue(recovered.terminal)
        assertFalse(recovered.unsupported)

        // Unsupported firmware verdict → terminal even when the stranded-task
        // sweep did not confirm (it is idempotent and re-runs at next boot);
        // retrying could never change what the firmware exposes.
        val unsupported = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = false,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .UNSUPPORTED
            )
        assertFalse(unsupported.recovered)
        assertTrue(unsupported.terminal)
        assertTrue(unsupported.unsupported)
        assertFalse(unsupported.sameBootIndeterminate)

        // A plain task failure with recovered gauges stays retryable.
        val taskFailure = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = false,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED
            )
        assertFalse(taskFailure.terminal)

        // Retryable and same-boot indeterminate gauge outcomes never settle
        // the ladder (the latter is the safety lockout).
        val retryable = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RETRYABLE_FAILURE
            )
        assertFalse(retryable.terminal)
        val sameBoot = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE
            )
        assertFalse(sameBoot.terminal)
        assertTrue(sameBoot.sameBootIndeterminate)
    }

    @Test
    fun teardownCommandIsMarkedBeforeDispatchAndClearedAfterDefinitiveReply() {
        val events = mutableListOf<String>()
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = {
                events += "mark"
                true
            },
            shouldSend = { true },
            send = {
                events += "send"
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            },
            clearInFlight = {
                events += "clear"
                true
            }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.ACCEPTED,
            result
        )
        assertEquals(listOf("mark", "send", "clear"), events)
    }

    @Test
    fun indeterminateTeardownNeverClearsDurableOwnership() {
        var cleared = false
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = { true },
            shouldSend = { true },
            send = {
                ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
            },
            clearInFlight = {
                cleared = true
                true
            }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.INDETERMINATE,
            result
        )
        assertFalse(cleared)
    }

    @Test
    fun teardownIsNotDispatchedWithoutADurableInFlightMarker() {
        var sent = false
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = { false },
            shouldSend = { true },
            send = {
                sent = true
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            },
            clearInFlight = { true }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.INDETERMINATE,
            result
        )
        assertFalse(sent)
    }

    @Test
    fun failedTeardownMarkerClearMakesAReplyIndeterminate() {
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = { true },
            shouldSend = { true },
            send = {
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            },
            clearInFlight = { false }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.INDETERMINATE,
            result
        )
    }

    @Test
    fun everyDefinitiveTeardownReplyClearsItsInFlightMarker() {
        listOf(
            ClusterProjectionController.DiLink5CommandResult.REJECTED,
            ClusterProjectionController.DiLink5CommandResult.UNAVAILABLE,
            ClusterProjectionController.DiLink5CommandResult.TRANSPORT_FAILURE
        ).forEach { reply ->
            var clears = 0
            val result = executeDiLink5MarkedTeardownCommand(
                markInFlight = { true },
                shouldSend = { true },
                send = { reply },
                clearInFlight = {
                    clears++
                    true
                }
            )
            assertEquals(reply, result)
            assertEquals(1, clears)
        }
    }

    @Test
    fun onlyAcceptedOrIndeterminateEnableCarriesCleanupOwnership() {
        assertTrue(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            )
        )
        assertTrue(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
            )
        )
        assertFalse(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.REJECTED
            )
        )
        assertFalse(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.UNAVAILABLE
            )
        )
    }

    @Test
    fun authoritativeInventoryParserIncludesHdmiAndProjectionSurfaces() {
        assertEquals(
            listOf(
                "Built-in Screen",
                "HDMI Screen",
                "shared_fission_bg_XDJAScreenProjection_0"
            ),
            parseDiLink5DisplayNames(
                listOf(
                    """DisplayDeviceInfo{"Built-in Screen": state ON}""",
                    """DisplayInfo{"HDMI Screen", displayId 1", state ON}""",
                    """DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0, displayId 3", state ON}"""
                )
            )
        )
    }

    @Test
    fun visibleProjectionDisplayMustBeLiveAndPowered() {
        assertTrue(
            isUsableDiLink5ProjectionDisplay(true, Display.STATE_ON)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(false, Display.STATE_ON)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(true, Display.STATE_OFF)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(true, Display.STATE_UNKNOWN)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(
                true, Display.STATE_DOZE_SUSPEND
            )
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(
                true, Display.STATE_ON_SUSPEND
            )
        )
    }

    @Test
    fun dumpsysParserKeepsDisplayIdentityAndRejectsDeadEntries() {
        val refs = parseDiLink5ProjectionDisplays(
            listOf(
                """mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", real 1920 x 720, layerStack 2, state ON}""",
                """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0", displayId 3", real 1920 x 720, layerStack 3, state ON}""",
                """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_1", displayId 4", real 1920 x 720, layerStack 4, state OFF}""",
                """mBaseDisplayInfo=DisplayInfo{"XDJAScreenProjection_0", displayId 5", real 1920 x 720, layerStack 5}""",
                """mBaseDisplayInfo=DisplayInfo{"XDJAScreenProjection_1", displayId 6", real 1920 x 720, layerStack 6, state ON_SUSPEND}""",
                """DisplayDeviceInfo{"shared_fission_bg_XDJAScreenProjection_0": state ON}"""
            )
        )

        assertEquals(
            listOf(
                DiLink5ProjectionDisplayRef(
                    2, "fission_bg_XDJAScreenProjection", 1_920, 720
                ),
                DiLink5ProjectionDisplayRef(
                    3, "shared_fission_bg_XDJAScreenProjection_0", 1_920, 720
                )
            ),
            refs
        )
    }

    @Test
    fun dumpsysParserAcceptsBothBydHeaderFormatsAndNeverUsesTheSquareEnvelope() {
        val refs = parseDiLink5ProjectionDisplays(
            listOf(
                // Exact field ordering from log_L3GHGKTL.
                """mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, app 1920 x 720, layerStack 2, state ON}""",
                """mOverrideDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", real 1920 x 720, largest app 1920 x 1920, smallest app 720 x 720, app 1920 x 720, layerStack 2, state ON}""",
                // Alternate Android format with displayId inside the quoted header.
                """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0, displayId 3", real 1 x 1, app 1 x 1, layerStack 3, state ON}"""
            )
        )

        assertEquals(2, refs.size)
        assertEquals(
            DiLink5ProjectionDisplayRef(
                2, "fission_bg_XDJAScreenProjection", 1_920, 720
            ),
            refs[0]
        )
        assertEquals(
            DiLink5ProjectionDisplayRef(
                3, "shared_fission_bg_XDJAScreenProjection_0", 0, 0
            ),
            refs[1]
        )
    }

    @Test
    fun seaLion07MiniMapUsesTheSelectedSharedDisplaysActualHeightAndRightSide() {
        // BYDMate #263 lists _0 as 1920x720 and _1 as 1920x660 on the same head unit.
        assertEquals(
            DiLink5ProjectionBounds(1306, 0, 1920, 660),
            factoryMiniMapDefaultBounds(1920, 660)
        )
        assertEquals(
            DiLink5ProjectionBounds(870, 0, 1280, 480),
            factoryMiniMapDefaultBounds(1280, 480)
        )
    }

    @Test
    fun sharedShadowUsesThePlainComposedPanelGeometryWhenNeeded() {
        val plain = DiLink5ProjectionDisplayRef(
            2, "fission_bg_XDJAScreenProjection", 1_920, 720
        )
        val shared = DiLink5ProjectionDisplayRef(
            3, "shared_fission_bg_XDJAScreenProjection_0", 1, 1
        )

        assertEquals(
            1_920 to 720,
            resolveDiLink5ProjectionPanelSize(listOf(plain, shared), shared)
        )
        assertEquals(
            1_280 to 480,
            resolveDiLink5ProjectionPanelSize(
                listOf(plain),
                DiLink5ProjectionDisplayRef(
                    4, "XDJAScreenProjection_1", 1_280, 480
                )
            )
        )
    }

    @Test
    fun projectionBoundsStayVisibleAndMeetTheMinimumSize() {
        val bounds = clampDiLink5ProjectionBounds(
            1_250, 450, 1_280, 480, 1_280, 480
        )

        assertEquals(1_080, bounds!!.left)
        assertEquals(280, bounds.top)
        assertEquals(1_280, bounds.right)
        assertEquals(480, bounds.bottom)
        assertNull(clampDiLink5ProjectionBounds(10, 10, 10, 20, 1_280, 480))
    }

    @Test
    fun forceRestartIsReservedForAPositivelyKnownOffDisplayTask() {
        assertTrue(
            shouldForceRestartDiLink5Task(
                locationKnown = true,
                taskId = 42,
                currentDisplayId = 0,
                targetDisplayId = 3
            )
        )
        assertFalse(
            shouldForceRestartDiLink5Task(
                locationKnown = true,
                taskId = 42,
                currentDisplayId = 3,
                targetDisplayId = 3
            )
        )
        assertFalse(
            shouldForceRestartDiLink5Task(
                locationKnown = false,
                taskId = 42,
                currentDisplayId = 0,
                targetDisplayId = 3
            )
        )
        assertFalse(
            shouldForceRestartDiLink5Task(
                locationKnown = true,
                taskId = -1,
                currentDisplayId = -1,
                targetDisplayId = 3
            )
        )
    }
}
