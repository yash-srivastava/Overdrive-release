package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.DiLink5HostedProjectionBridge
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig
import com.overdrive.app.launcher.dilink5.cluster.HostedProjectionBoundary
import com.overdrive.app.launcher.dilink5.cluster.HostedProjectionGeometry
import com.overdrive.app.launcher.dilink5.cluster.RetriableHostedResources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DiLink5HostedProjectionBridgeTest {
    @Test
    fun onlyOptedInShellStartsThroughTheDaemonAndFallsBackOnceOnNull() {
        fun check(optedIn: Boolean, uid: Int, shellResult: String?, expectedCalls: List<String>) {
            val calls = mutableListOf<String>()
            val result = HostedProjectionBoundary.startService(optedIn, uid,
                asShell = { calls.add("shell"); shellResult },
                fromContext = { calls.add("context"); "context" },
            )
            assertEquals(expectedCalls, calls)
            assertEquals(expectedCalls.last(), result)
        }
        check(true, 2000, "shell", listOf("shell"))
        check(true, 2000, null, listOf("shell", "context"))
        check(false, 2000, "shell", listOf("context"))
        check(true, 10001, "shell", listOf("context"))
        check(true, 0, "shell", listOf("context"))
    }

    @Test
    fun confirmedProbeReturnsTheSelectedCoordinatesAndRejectsIncompleteOrOffPanelFrames() {
        fun decode(vararg coordinates: Int) = HostedProjectionBoundary.mapBoundsFromProbe(coordinates, 1920, 720)
        assertEquals(HostedProjectionGeometry.Bounds(640, 220, 1280, 500), decode(640, 220, 1280, 500))
        assertEquals(HostedProjectionGeometry.Bounds(1440, 232, 1840, 488), decode(1440, 232, 1840, 488))
        assertNull(decode())
        assertNull(decode(640, 220, 1280))
        assertNull(decode(-1, 220, 639, 500))
        assertNull(decode(1500, 220, 2140, 500))
        assertNull(decode(640, 220, 1280, 220))
    }

    @Test
    fun transportParserDefaultsFreshConfigToHostedAndKeepsExplicitChoices() {
        assertEquals(
            DiLink5ProjectionConfig.Transport.HOSTED,
            DiLink5ProjectionConfig.from(null).transport,
        )
        assertEquals(
            DiLink5ProjectionConfig.Transport.AUTO,
            DiLink5ProjectionConfig.from(JSONObject().put(
                DiLink5ProjectionConfig.KEY_TRANSPORT, "unexpected")).transport
        )
        assertEquals(
            DiLink5ProjectionConfig.Transport.HOSTED,
            DiLink5ProjectionConfig.from(JSONObject().put(
                DiLink5ProjectionConfig.KEY_TRANSPORT, "hosted")).transport
        )
        assertEquals(
            DiLink5ProjectionConfig.Transport.DIRECT,
            DiLink5ProjectionConfig.from(JSONObject().put(
                DiLink5ProjectionConfig.KEY_TRANSPORT, "direct")).transport
        )
    }

    @Test
    fun shellOnlyDisplayForcesDirectWithoutChangingThePersistedPreference() {
        for (preference in DiLink5ProjectionConfig.Transport.entries) {
            val stored = JSONObject().put(DiLink5ProjectionConfig.KEY_TRANSPORT, preference.wire)
            val config = DiLink5ProjectionConfig.from(stored)
            assertEquals(DiLink5ProjectionConfig.Transport.DIRECT, config.initialTransport(false))
            assertEquals(preference.wire, stored.getString(DiLink5ProjectionConfig.KEY_TRANSPORT))
            assertEquals(preference, config.transport)
            assertEquals(
                if (preference == DiLink5ProjectionConfig.Transport.HOSTED)
                    DiLink5ProjectionConfig.Transport.HOSTED else DiLink5ProjectionConfig.Transport.DIRECT,
                config.initialTransport(true),
            )
        }
        // The fresh hosted default must also work when only shell UID can resolve the panel.
        assertEquals(DiLink5ProjectionConfig.Transport.DIRECT,
            DiLink5ProjectionConfig.from(null).initialTransport(false))
    }

    @Test
    fun bothAppOpsAreGrantedAndReadBackBeforeTheHostedAttemptCanProceed() {
        val allowed = mutableSetOf<HostedProjectionBoundary.AppOp>()
        val events = mutableListOf<String>()
        assertTrue(HostedProjectionBoundary.ensureOwnProjectionAppOps(
            identityCurrent = { true },
            readAllowed = { operation ->
                events += "read:$operation"
                HostedProjectionBoundary.appOpReadbackAllowed(
                    operation, "$operation: ${if (operation in allowed) "allow" else "deny"};",
                )
            },
            grant = { operation ->
                events += "grant:$operation"
                allowed += operation
                true
            },
        ))
        assertEquals(listOf(
            "read:SYSTEM_ALERT_WINDOW", "grant:SYSTEM_ALERT_WINDOW", "read:SYSTEM_ALERT_WINDOW",
            "read:PROJECT_MEDIA", "grant:PROJECT_MEDIA", "read:PROJECT_MEDIA",
        ), events)
        assertEquals(HostedProjectionBoundary.AppOp.entries.toSet(), allowed)
    }

    @Test
    fun grantExitSuccessAloneCannotClaimPermissionAndPriorSuccessCannotHideRevocation() {
        assertFalse(HostedProjectionBoundary.ensureOwnProjectionAppOps(
            identityCurrent = { true },
            readAllowed = { false },
            grant = { true }, // command delivery without the required allow readback
        ))
        val allowed = HostedProjectionBoundary.AppOp.entries.toMutableSet()
        fun ensure(grantSucceeds: Boolean) = HostedProjectionBoundary.ensureOwnProjectionAppOps(
            identityCurrent = { true },
            readAllowed = { it in allowed },
            grant = { grantSucceeds.also { ok -> if (ok) allowed += it } },
        )
        assertTrue(ensure(false))
        allowed.remove(HostedProjectionBoundary.AppOp.SYSTEM_ALERT_WINDOW)
        assertFalse(ensure(false))
        assertTrue(ensure(true))
        allowed.remove(HostedProjectionBoundary.AppOp.PROJECT_MEDIA)
        assertFalse(ensure(false))
    }

    @Test
    fun failedInstalledIdentityCannotMutateOrAcceptAReplacementUid() {
        var installedUid = 10001
        var grants = 0
        assertFalse(HostedProjectionBoundary.ensureOwnProjectionAppOps(
            identityCurrent = {
                HostedProjectionBoundary.appIdentityAccepted(
                    2000, "com.overdrive.app", 10001, installedUid,
                )
            },
            readAllowed = { false },
            grant = {
                grants++
                installedUid = 10002
                true
            },
        ))
        assertEquals(1, grants)
        assertFalse(HostedProjectionBoundary.appIdentityAccepted(
            10001, "com.overdrive.app", 10001, 10001))
        assertFalse(HostedProjectionBoundary.appIdentityAccepted(
            2000, "com.foreign.app", 10001, 10001))
        assertFalse(HostedProjectionBoundary.appIdentityAccepted(
            2000, "com.overdrive.app", 110001, 110001))
        assertFalse(HostedProjectionBoundary.appIdentityAccepted(
            2000, "com.overdrive.app", 10001, -1))
    }

    @Test
    fun appOpReadbackRejectsMissingDifferentDeniedAndUidOverriddenOperations() {
        val overlay = HostedProjectionBoundary.AppOp.SYSTEM_ALERT_WINDOW
        assertTrue(HostedProjectionBoundary.appOpReadbackAllowed(
            overlay, "SYSTEM_ALERT_WINDOW: allow; time=+1s0ms ago"))
        assertTrue(HostedProjectionBoundary.appOpReadbackAllowed(
            overlay, "Uid mode: SYSTEM_ALERT_WINDOW: allow; time=+1s ago (running)\n" +
                "SYSTEM_ALERT_WINDOW: allow; rejectTime=+5s ago; duration=+1s"))
        assertTrue(HostedProjectionBoundary.appOpReadbackAllowed(
            overlay, "Uid mode: SYSTEM_ALERT_WINDOW: allow"))
        assertTrue(HostedProjectionBoundary.appOpReadbackAllowed(
            overlay, "Uid mode: SYSTEM_ALERT_WINDOW: allow\nSYSTEM_ALERT_WINDOW: ignore"))
        for (output in listOf(
            "", "No operations.", "PROJECT_MEDIA: allow;", "SYSTEM_ALERT_WINDOW: deny;",
            "SYSTEM_ALERT_WINDOW: default;", "SYSTEM_ALERT_WINDOW: allowlisted;",
            "Uid mode: ignore\nSYSTEM_ALERT_WINDOW: allow;",
            "Uid mode: allow\nSYSTEM_ALERT_WINDOW: allow;",
            "Uid mode: SYSTEM_ALERT_WINDOW: ignore\nSYSTEM_ALERT_WINDOW: allow;",
            "Uid mode: unrecognized-format\nSYSTEM_ALERT_WINDOW: allow;",
            "Uid mode: SYSTEM_ALERT_WINDOW: allow\nUid mode: SYSTEM_ALERT_WINDOW: ignore",
            "SYSTEM_ALERT_WINDOW: allow;\nSYSTEM_ALERT_WINDOW: ignore;",
        )) assertFalse(HostedProjectionBoundary.appOpReadbackAllowed(overlay, output))
    }

    @Test fun boundaryRejectsWrongCallerVersionTruncationAndBounds() {
        fun admitted(
            caller: Int = 10001,
            expected: Int = 10001,
            version: Int = DiLink5HostedProjectionBridge.PROTOCOL_VERSION,
            generation: Long = 7,
            width: Int = 1920,
            owner: Boolean = true,
            surfaceMarker: Int = 1,
            bytes: Int = 24,
        ) = HostedProjectionBoundary.admit(
            caller, expected, version, generation, 2, width, 720, 320,
            owner, surfaceMarker, bytes,
        )
        assertTrue(admitted())
        assertFalse(admitted(caller = 10002))
        assertFalse(admitted(expected = -1))
        assertFalse(admitted(version = 2))
        assertFalse(admitted(generation = 0))
        assertFalse(admitted(width = 9000))
        assertFalse(admitted(owner = false))
        assertFalse(admitted(surfaceMarker = 0))
        assertFalse(admitted(bytes = 0))
    }

    @Test fun virtualDisplayUsesPreferredThenFallbackFlags() {
        val attempts = mutableListOf<Int>()
        val preferred = HostedProjectionBoundary.allocateDisplay(sealion7Hdmi = false) { flags ->
            attempts += flags
            "preferred display"
        }
        assertEquals(listOf(323), attempts)
        assertEquals(323, preferred?.flags)

        attempts.clear()
        val allocation = HostedProjectionBoundary.allocateDisplay<String>(sealion7Hdmi = false) { flags ->
            attempts += flags
            if (flags == DiLink5HostedProjectionBridge.FLAGS_FALLBACK) "display" else null
        }
        assertEquals(listOf(323, 322), attempts)
        assertEquals("display", allocation?.value)
        assertEquals(322, allocation?.flags)
        assertNull(HostedProjectionBoundary.allocateDisplay<String>(sealion7Hdmi = false) { null })
    }

    @Test fun sealion7AllocatesOnlyThePublicOwnContentDisplay() {
        val attempts = mutableListOf<Int>()
        val success = HostedProjectionBoundary.allocateDisplay(sealion7Hdmi = true) { flags ->
            attempts += flags
            "display"
        }
        assertEquals(listOf(331), attempts)
        assertEquals("display", success?.value)
        assertEquals(331, success?.flags)

        attempts.clear()
        assertNull(HostedProjectionBoundary.allocateDisplay<String>(sealion7Hdmi = true) { flags ->
            attempts += flags
            null
        })
        assertEquals(listOf(331), attempts)
    }

    @Test fun failedReleaseRemainsOwnedAndCanBeRetried() {
        val resources = mutableMapOf(7L to "overlay")
        var attempts = 0

        assertFalse(RetriableHostedResources.release(resources, 7L) {
            attempts++
            false
        })
        assertEquals("overlay", resources[7L])
        assertTrue(RetriableHostedResources.release(resources, 7L) {
            attempts++
            true
        })
        assertFalse(resources.containsKey(7L))
        assertEquals(2, attempts)
    }

    @Test fun surfaceWaitAndLifecycleReleaseAreBounded() {
        assertFalse(HostedProjectionBoundary.awaitSurface(CountDownLatch(1), 1))
        assertTrue(HostedProjectionBoundary.awaitSurface(CountDownLatch(0), 1))
        assertFalse(HostedProjectionBoundary.shouldRelease(true, true, true))
        assertTrue(HostedProjectionBoundary.shouldRelease(false, true, true))
        assertTrue(HostedProjectionBoundary.shouldRelease(true, false, true))
        assertTrue(HostedProjectionBoundary.shouldRelease(true, true, false))

        assertTrue(HostedProjectionBoundary.canPublish(true, false))
        assertFalse(HostedProjectionBoundary.canPublish(false, false))
        assertFalse(HostedProjectionBoundary.canPublish(true, true))
        assertTrue(HostedProjectionBoundary.releaseConfirmed(true, true))
        assertFalse(HostedProjectionBoundary.releaseConfirmed(false, true))
        assertFalse(HostedProjectionBoundary.releaseConfirmed(true, false))
        assertTrue(HostedProjectionBoundary.appDisplayVisible(true, true, 7, 7))
        assertFalse(HostedProjectionBoundary.appDisplayVisible(true, false, 7, 7))
        assertTrue(HostedProjectionBoundary.appDisplayInfoValid(7, 7, 1920, 720, 320))
        assertFalse(HostedProjectionBoundary.appDisplayInfoValid(7, 8, 1920, 720, 320))
        assertFalse(HostedProjectionBoundary.completeCreateFrame(4))
        assertTrue(HostedProjectionBoundary.completeCreateFrame(0))
        assertEquals(
            "/system/bin/appops set --user 0 'com.overdrive.app' PROJECT_MEDIA allow",
            HostedProjectionBoundary.projectionAppOpCommand(
                "com.overdrive.app", 10001, HostedProjectionBoundary.AppOp.PROJECT_MEDIA, true,
            ),
        )
        assertEquals(
            "/system/bin/appops get --user 0 'com.overdrive.app' SYSTEM_ALERT_WINDOW",
            HostedProjectionBoundary.projectionAppOpCommand(
                "com.overdrive.app", 10001, HostedProjectionBoundary.AppOp.SYSTEM_ALERT_WINDOW, false,
            ),
        )

        assertTrue(HostedProjectionBoundary.shellIdentityAccepted(
            2000, "com.android.shell", 2000, 2000, "com.android.shell"))
        assertFalse(HostedProjectionBoundary.shellIdentityAccepted(
            2000, "com.overdrive.app", 2000, 2000, "com.overdrive.app"))
        assertFalse(HostedProjectionBoundary.shellIdentityAccepted(
            10001, "com.android.shell", 2000, 2000, "com.android.shell"))
    }

    @Test fun frameworkAttributionIsSpecificToTheSealion7ShellDisplayContext() {
        fun accepted(
            opPackage: String?,
            sealion7Hdmi: Boolean = true,
            processUid: Int = 2000,
            packageName: String = "com.android.shell",
            contextUid: Int = 2000,
            installedUid: Int = 2000,
        ) = HostedProjectionBoundary.shellIdentityAccepted(
            processUid, packageName, contextUid, installedUid, opPackage, sealion7Hdmi,
        )

        assertTrue(accepted("android"))
        assertFalse(accepted("android", sealion7Hdmi = false))
        assertTrue(accepted("com.android.shell"))
        assertTrue(accepted("com.android.shell", sealion7Hdmi = false))
        assertFalse(accepted(null))
        assertFalse(accepted("com.overdrive.app"))
        assertFalse(accepted("android", processUid = 1000))
        assertFalse(accepted("android", packageName = "android"))
        assertFalse(accepted("android", contextUid = 1000))
        assertFalse(accepted("android", installedUid = 1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun permissionCommandRefusesAUserSuppliedPackage() {
        HostedProjectionBoundary.projectionAppOpCommand(
            "com.other.app", 10001, HostedProjectionBoundary.AppOp.SYSTEM_ALERT_WINDOW, true,
        )
    }

    @Test fun unexpectedSurfaceLossReclaimsTasksBeforeDestroyingTheDensityDisplay() {
        val events = mutableListOf<String>()
        var leasePending = true
        assertTrue(HostedProjectionBoundary.finishAppSurfaceRelease(
            expected = false,
            overlayReleased = true,
            releaseDisplay = { events += "release-display"; true },
            onUnexpectedLoss = { events += "request-cast-cleanup" },
        ))
        assertEquals(listOf("request-cast-cleanup"), events)
        assertFalse(completeHostedGenerationCleanup(false, leasePending) {
            events += "release-display"
            true
        })
        events += "reclaim-task"
        events += "restore-density"
        leasePending = false
        assertTrue(completeHostedGenerationCleanup(true, leasePending) {
            events += "release-display"
            true
        })
        assertEquals(
            listOf("request-cast-cleanup", "reclaim-task", "restore-density", "release-display"),
            events,
        )
    }

    @Test fun failedOverlayRemovalAndUnconfirmedDisplayReleaseKeepOwnership() {
        var displayReleaseCalls = 0
        assertFalse(HostedProjectionBoundary.finishAppSurfaceRelease(
            expected = true,
            overlayReleased = false,
            releaseDisplay = { displayReleaseCalls++; true },
            onUnexpectedLoss = { error("expected cleanup") },
        ))
        assertEquals(0, displayReleaseCalls)
        assertFalse(HostedProjectionBoundary.finishAppSurfaceRelease(
            expected = true,
            overlayReleased = true,
            releaseDisplay = { displayReleaseCalls++; false },
            onUnexpectedLoss = { error("expected cleanup") },
        ))
        assertEquals(1, displayReleaseCalls)
    }

    @Test fun surfaceLossAfterVerificationCannotPublishOrRetireTheWorkingHost() {
        val authority = Any()
        var surfaceAlive = true
        var publishedGeneration = 7L
        var workingHostReleased = false
        val finalVerificationDone = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val result = AtomicReference<Long>()
        val resize = Thread {
            finalVerificationDone.countDown()
            check(allowPublication.await(2, TimeUnit.SECONDS))
            result.set(HostedProjectionBoundary.withLiveSurfaceAuthority(
                authority,
                surfaceAlive = { surfaceAlive },
            ) {
                publishedGeneration = 8L
                8L
            })
            if (result.get() != null) workingHostReleased = true
        }
        resize.start()
        assertTrue(finalVerificationDone.await(1, TimeUnit.SECONDS))
        synchronized(authority) { surfaceAlive = false }
        allowPublication.countDown()
        resize.join(2_000)
        assertFalse(resize.isAlive)
        assertNull(result.get())
        assertEquals(7L, publishedGeneration)
        assertFalse(workingHostReleased)
    }

    @Test fun surfaceLossThatFollowsPublicationObservesTheReplacementGeneration() {
        val authority = Any()
        var surfaceAlive = true
        var publishedGeneration = 7L
        val publicationEntered = CountDownLatch(1)
        val finishPublication = CountDownLatch(1)
        val lossAttempted = CountDownLatch(1)
        val lossRecorded = CountDownLatch(1)
        val observedLostGeneration = AtomicReference<Long>()
        val result = AtomicReference<Long>()
        val resize = Thread {
            result.set(HostedProjectionBoundary.withLiveSurfaceAuthority(
                authority,
                surfaceAlive = { surfaceAlive },
            ) {
                publicationEntered.countDown()
                check(finishPublication.await(2, TimeUnit.SECONDS))
                publishedGeneration = 8L
                8L
            })
        }
        val surfaceLoss = Thread {
            lossAttempted.countDown()
            synchronized(authority) { surfaceAlive = false }
            // Like the Binder callback, inspect Cast only after releasing Surface authority.
            observedLostGeneration.set(publishedGeneration)
            lossRecorded.countDown()
        }
        resize.start()
        assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))
        surfaceLoss.start()
        assertTrue(lossAttempted.await(1, TimeUnit.SECONDS))
        assertFalse(lossRecorded.await(50, TimeUnit.MILLISECONDS))
        finishPublication.countDown()
        resize.join(2_000)
        surfaceLoss.join(2_000)
        assertFalse(resize.isAlive)
        assertFalse(surfaceLoss.isAlive)
        assertEquals(8L, result.get())
        assertEquals(8L, observedLostGeneration.get())
        assertFalse(surfaceAlive)
    }

    @Test fun callerTimeoutCannotConfirmReleaseBeforeTheLateAllocatorFinishes() {
        val allocatorEntered = CountDownLatch(1)
        val allowAllocatorFinish = CountDownLatch(1)
        val allocationInFlight = AtomicBoolean(false)
        val retained = mutableMapOf<Long, String>()
        val allocator = Thread {
            allocationInFlight.set(true)
            allocatorEntered.countDown()
            check(allowAllocatorFinish.await(2, TimeUnit.SECONDS))
            // Allocation completed after its caller had already cancelled; release failed.
            synchronized(retained) { retained[7] = "late-display" }
            allocationInFlight.set(false)
        }
        allocator.start()
        assertTrue(allocatorEntered.await(1, TimeUnit.SECONDS))
        assertFalse(HostedProjectionBoundary.mayConfirmDisplayRelease(allocationInFlight.get()))
        allowAllocatorFinish.countDown()
        allocator.join(2_000)
        assertFalse(allocator.isAlive)
        assertTrue(HostedProjectionBoundary.mayConfirmDisplayRelease(allocationInFlight.get()))
        assertFalse(RetriableHostedResources.release(retained, 7L) { false })
        assertEquals("late-display", retained[7])
        assertTrue(RetriableHostedResources.release(retained, 7L) { true })
        assertTrue(retained.isEmpty())
    }

    @Test fun onlyACompleteShellInventoryCanProveOwnedDisplayRetirement() {
        val default = HostedProjectionBoundary.DisplayIdentity(0, "built-in", true)
        val owned = HostedProjectionBoundary.DisplayIdentity(8, "OverDriveCluster-77", true)
        fun retired(displays: List<HostedProjectionBoundary.DisplayIdentity>?) =
            HostedProjectionBoundary.ownedContentDisplayRetired(
                8, "OverDriveCluster-77", displays,
            )
        assertNull(retired(null))
        assertNull(retired(emptyList()))
        assertNull(retired(listOf(owned)))
        assertFalse(retired(listOf(default, owned))!!)
        assertTrue(retired(listOf(default))!!)
        assertNull(retired(listOf(default, owned.copy(valid = false))))
        assertTrue(retired(listOf(default, owned.copy(name = "another-display")))!!)
        // A same-name VD elsewhere is still alive even if the old numeric ID was recycled.
        assertFalse(retired(listOf(default,
            owned.copy(name = "another-display"), owned.copy(id = 9)))!!)
    }
}
