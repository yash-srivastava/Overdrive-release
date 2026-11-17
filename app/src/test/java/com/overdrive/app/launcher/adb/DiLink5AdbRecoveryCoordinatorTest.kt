package com.overdrive.app.launcher.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DiLink5AdbRecoveryCoordinatorTest {
    @Test fun accessibilityPlanPreservesOtherServicesAndCanonicalizesTarget() {
        val full = "com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService"
        val short = "com.overdrive.app/.services.KeepAliveAccessibilityService"
        val plan = AccessibilityServiceList.plan(
            "other.one/Service:$short:other.two/Service:$full",
            full,
            short,
        )
        assertEquals("other.one/Service:other.two/Service", plan.withoutTarget)
        assertEquals("other.one/Service:other.two/Service:$full", plan.withTarget)
        assertTrue(AccessibilityServiceList.containsTarget(plan.withTarget, full, short))
        assertFalse(AccessibilityServiceList.containsTarget(plan.withoutTarget, full, short))
    }

    @Test fun missingVerifiedGrantFailsBeforeNetworkMutation() {
        val gateway = FakeGateway().apply { permission = false }
        val result = coordinator(gateway).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.MISSING_PERMISSION, result.outcome)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
    }

    @Test fun unverifiedIdentityFailsBeforeNetworkMutation() {
        val gateway = FakeGateway().apply { identityTrusted = false }
        val result = coordinator(gateway).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.TRUST_LOST, result.outcome)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
    }

    @Test fun eofAfterTcpipRequiresFreshAuthenticatedClassicProof() {
        val failed = FakeGateway().apply {
            tls = AdbTlsTransport.Result.RestartAmbiguous
            freshClassic = false
        }
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.TRANSPORT_FAILURE,
            coordinator(failed).recoverManual().outcome,
        )
        val restored = FakeGateway().apply {
            tls = AdbTlsTransport.Result.RestartAmbiguous
            freshClassic = true
        }
        val result = coordinator(restored).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result.outcome)
        assertTrue(result.authenticated)
        assertTrue(result.helperHealthy)
    }

    @Test fun absentPropertyPerformsOnlyOneMdnsSearch() {
        val gateway = FakeGateway().apply {
            propertyEndpointAvailable = false
            tls = AdbTlsTransport.Result.TlsFailure()
        }

        coordinator(gateway).recoverManual()

        assertEquals(2, gateway.discoveryCount)
        assertEquals(1, gateway.mdnsDiscoveryCount)
    }

    @Test fun failedPropertyCandidateFallsBackToMdnsCandidate() {
        val gateway = FakeGateway().apply {
            tlsResults.add(AdbTlsTransport.Result.TlsFailure())
            tlsResults.add(AdbTlsTransport.Result.RestartAmbiguous)
            freshClassic = true
        }
        val result = coordinator(gateway).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result.outcome)
        assertEquals(2, gateway.discoveryCount)
        assertEquals(2, gateway.tlsOpenCount)
    }

    @Test fun postOpenFailuresNeverTryAnotherEndpoint() {
        val results = listOf<AdbTlsTransport.Result>(
            AdbTlsTransport.Result.RestartAmbiguous,
            AdbTlsTransport.Result.Timeout(true),
            AdbTlsTransport.Result.TlsFailure(true),
            AdbTlsTransport.Result.TransportFailure("malformed", true),
            AdbTlsTransport.Result.TransportFailure("packet_limit", true),
        )
        results.forEach { failure ->
            val gateway = FakeGateway().apply {
                tls = failure
                freshClassic = false
            }
            coordinator(gateway).recoverManual()
            assertEquals("fallback after $failure", 1, gateway.discoveryCount)
            assertEquals("replayed OPEN after $failure", 1, gateway.tlsOpenCount)
        }
    }

    @Test fun recoveryStartsHelpersWithoutAccessibilityUntilCurrentNetworkConsent() {
        val withoutConsent = FakeGateway().apply { classic = true }
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE,
            coordinator(withoutConsent).recoverManual().outcome,
        )
        assertEquals(1, withoutConsent.helperStarts)
        assertEquals(0, withoutConsent.accessibilityEnables)

        val withConsent = FakeGateway().apply { classic = true }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(dialogAutoConfirm = true, dialogAutoConfirmNetwork = "wifi")
        }
        DiLink5AdbRecoveryCoordinator(withConsent, store, FakeClock()).recoverManual()
        assertEquals(1, withConsent.helperStarts)
        assertEquals(1, withConsent.accessibilityEnables)
    }

    @Test fun classicHelperBootstrapRetainsInheritedNetworkScope() {
        val gateway = FakeGateway().apply {
            classic = true
            network = "wifi-a"
            onStartHelpers = { network = "wifi-b" }
        }
        val result = coordinator(gateway).recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.outcome)
        assertEquals(1, gateway.helperStarts)
        assertEquals(0, gateway.accessibilityEnables)
    }

    @Test fun uncertainWirelessWriteRetainsDurableOwnershipAndVisiblePartialResult() {
        val gateway = FakeGateway().apply {
            setting = 0
            writeResult = DiLink5AdbRecoveryCoordinator.WriteResult.UNCERTAIN
            unreadableAfterWrite = true
        }
        val store = FakeStore()
        val result = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock()).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.TRANSPORT_FAILURE, result.outcome)
        assertTrue(result.rollbackPartial)
        assertTrue(result.settingOwned)
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
        assertEquals(0, store.snapshot.priorSettingValue)
    }

    @Test fun existingWirelessValueIsNotRewrittenAndTlsFailureIsNotAuthRejection() {
        val gateway = FakeGateway().apply {
            setting = 1
            tls = AdbTlsTransport.Result.TlsFailure()
        }
        val result = coordinator(gateway).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE, result.outcome)
        assertEquals(0, gateway.writeCount)
        assertFalse(result.outcome == DiLink5AdbRecoveryCoordinator.Outcome.AUTHORIZATION_REJECTED)
    }

    @Test fun restartedCoordinatorExposesOwnedJournalBeforeAnyClassicProbe() {
        val firstGateway = FakeGateway().apply {
            setting = 0
            unreadableAfterWrite = true
        }
        val firstStore = FakeStore()
        val first = DiLink5AdbRecoveryCoordinator(firstGateway, firstStore, FakeClock()).recoverManual()
        assertTrue(first.settingOwned)
        assertEquals(1, firstGateway.setting)
        val restoredStore = FakeStore().apply { snapshot = firstStore.snapshot.copy() }
        val gateway = FakeGateway().apply { classic = true }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, restoredStore, FakeClock())

        val result = restarted.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertTrue(restarted.hasManualRecoveryOwner())
        assertEquals(1L, gateway.classicChecked.count)
        assertEquals(0, gateway.helperStarts)
        assertEquals(0, gateway.writeCount)
        assertEquals(firstStore.snapshot.settingOwnerGeneration, restoredStore.snapshot.settingOwnerGeneration)
        assertTrue(restarted.cancelManual())
        assertEquals(0, gateway.setting)
        assertFalse(restarted.hasManualRecoveryOwner())
        assertFalse(restoredStore.snapshot.hasSettingOwnershipJournal)
    }

    @Test fun restartOnAnotherNetworkCannotReplaceOrCleanTheOriginalJournal() {
        val store = orphanedStore()
        val original = store.snapshot
        val gateway = FakeGateway().apply {
            network = "other"
            setting = 0
        }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        val result = restarted.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertFalse(restarted.cancelManual())
        assertEquals(original, store.snapshot)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
        assertTrue(restarted.hasManualRecoveryOwner())

        gateway.network = "wifi"
        gateway.setting = 1
        assertTrue(restarted.cancelManual())
        assertEquals(0, gateway.setting)
        assertEquals(1, gateway.writeCount)
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
    }

    @Test(timeout = 5_000) fun restartJournalAlsoFencesAutomaticDrainWithoutReschedulingForever() {
        val store = orphanedStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val gateway = FakeGateway().apply { network = "other"; setting = 0 }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        restarted.triggerAutomatic()
        val deadline = System.currentTimeMillis() + 2_000L
        while (!restarted.automaticDrainIdle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }

        assertTrue(restarted.automaticDrainIdle())
        assertEquals(setOf("other"), restarted.pendingAutomaticScopes())
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
        assertEquals(0, gateway.writeCount)
        assertEquals(1L, gateway.classicChecked.count)
        assertTrue(restarted.hasManualRecoveryOwner())
        restarted.setAutomaticRestore(false)
    }

    @Test fun restartCleanupAtTheRecordedPriorValueOnlyClearsTheJournal() {
        val store = orphanedStore()
        val gateway = FakeGateway().apply { setting = 0; classic = true }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        assertTrue(restarted.cancelManual())
        assertEquals(0, gateway.writeCount)
        assertTrue(store.ownershipCleared)
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE,
            restarted.recoverManual().outcome,
        )
        assertEquals(0, gateway.writeCount)
    }

    @Test fun failedRestartJournalClearStaysVisibleAndCanBeRetriedWithoutASecondWrite() {
        for (throwOnClear in listOf(false, true)) {
            val store = orphanedStore().apply {
                clearAllowed = false
                clearThrows = throwOnClear
            }
            val gateway = FakeGateway()
            val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

            assertFalse(restarted.cancelManual())
            assertEquals(0, gateway.setting)
            assertTrue(restarted.hasManualRecoveryOwner())
            val blocked = restarted.recoverManual()
            assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, blocked.outcome)
            assertTrue(blocked.settingOwned)
            assertTrue(blocked.rollbackPartial)
            assertEquals("wifi", store.snapshot.settingOwnerNetwork)

            store.clearAllowed = true
            store.clearThrows = false
            assertTrue(restarted.cancelManual())
            assertEquals(1, gateway.writeCount)
            assertFalse(restarted.hasManualRecoveryOwner())
        }
    }

    @Test fun restartCleanupRequiresReadbackAndRetainsTheJournalUntilItCanBeConfirmed() {
        val store = orphanedStore()
        val gateway = FakeGateway().apply { unreadableAfterWrite = true }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        assertFalse(restarted.cancelManual())
        assertFalse(store.ownershipCleared)
        assertTrue(restarted.hasManualRecoveryOwner())

        gateway.unreadableAfterWrite = false
        assertTrue(restarted.cancelManual())
        assertEquals(1, gateway.writeCount)
        assertTrue(store.ownershipCleared)
    }

    @Test fun restartCleanupDoesNotOverwriteAnExternallyChangedWirelessValue() {
        val store = orphanedStore()
        val gateway = FakeGateway().apply { setting = 7 }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        assertFalse(restarted.cancelManual())
        assertEquals(7, gateway.setting)
        assertEquals(0, gateway.writeCount)
        assertTrue(store.snapshot.hasSettingOwnershipJournal)
        assertTrue(restarted.hasManualRecoveryOwner())
    }

    @Test fun malformedRestartJournalIsVisibleAndNeverAuthorizesAWrite() {
        val store = orphanedStore().apply {
            snapshot = snapshot.copy(priorSettingValue = null, ownershipJournalInvalid = true)
        }
        val gateway = FakeGateway().apply { setting = 0; classic = true }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        val result = restarted.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertFalse(restarted.cancelManual())
        assertTrue(restarted.hasManualRecoveryOwner())
        assertEquals(0, gateway.writeCount)
        assertEquals(1L, gateway.classicChecked.count)
    }

    @Test fun unreadableRestartJournalFailsClosedAndRecoversAfterAValidRead() {
        val store = FakeStore().apply { loadThrows = true }
        val gateway = FakeGateway().apply { classic = true }
        val restarted = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        val result = restarted.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.rollbackPartial)
        assertFalse(restarted.cancelManual())
        assertTrue(restarted.hasManualRecoveryOwner())
        assertEquals(0, gateway.writeCount)
        assertEquals(1L, gateway.classicChecked.count)
        assertFalse(store.exhaustionCleared)

        store.loadThrows = false
        assertTrue(restarted.recoverySettingsSnapshot() != null)
        assertFalse(restarted.hasManualRecoveryOwner())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE,
            restarted.recoverManual().outcome,
        )
        assertEquals(0, gateway.writeCount)
    }

    @Test fun queuedCancellationCannotHideAnUnresolvedRestartOwner() {
        val store = orphanedStore().apply { clearAllowed = false }
        val gateway = FakeGateway()
        val dispatch = HoldingExecutor()
        val restarted = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = dispatch,
        )
        val completion = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        assertTrue(restarted.recoverManualAsync { completion.set(it) })

        assertFalse(restarted.cancelManual())
        assertEquals(0, gateway.setting)
        dispatch.runPending()

        val cancelled = completion.get()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, cancelled.outcome)
        assertTrue(cancelled.settingOwned)
        assertTrue(cancelled.rollbackPartial)
        assertTrue(restarted.hasManualRecoveryOwner())
        assertEquals(0, gateway.discoveryCount)
        assertEquals(1, gateway.writeCount)

        store.clearAllowed = true
        assertTrue(restarted.cancelManual())
        assertEquals(1, gateway.writeCount)
    }

    @Test fun successfulTransportCannotHideFailedOwnershipJournalClear() {
        for (throwOnClear in listOf(false, true)) {
            val store = FakeStore().apply { clearAllowed = false; clearThrows = throwOnClear }
            val gateway = FakeGateway().apply { setting = 0 }
            val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

            val result = coordinator.recoverManual()

            assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result.outcome)
            assertTrue(result.transportRestored)
            assertTrue(result.authenticated)
            assertTrue(result.settingOwned)
            assertTrue(result.rollbackPartial)
            assertTrue(coordinator.hasManualRecoveryOwner())
            assertTrue(store.snapshot.hasSettingOwnershipJournal)

            store.clearAllowed = true
            store.clearThrows = false
            assertTrue(coordinator.cancelManual())
            assertEquals(0, gateway.setting)
            assertFalse(store.snapshot.hasSettingOwnershipJournal)
        }
    }

    @Test fun persistenceExceptionStillCompletesAndRetiresAnAsynchronousManualRequest() {
        val store = FakeStore().apply { clearExhaustionThrows = true }
        val gateway = FakeGateway()
        val dispatch = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = dispatch,
        )
        val completion = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()

        assertTrue(coordinator.recoverManualAsync { completion.set(it) })
        dispatch.runPending()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.TRANSPORT_FAILURE, completion.get().outcome)
        assertFalse(coordinator.hasManualRecoveryOwner())
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)

        store.clearExhaustionThrows = false
        assertTrue(coordinator.recoverManualAsync { completion.set(it) })
        dispatch.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, completion.get().outcome)
    }

    @Test fun uiOwnerObservationNeverReadsTheDurableStore() {
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(FakeGateway(), store, FakeClock())
        assertTrue(coordinator.hasManualRecoveryOwner())
        assertEquals(0, store.loadCalls)

        assertTrue(coordinator.recoverySettingsSnapshot() != null)
        assertFalse(coordinator.hasManualRecoveryOwner())
        assertEquals(1, store.loadCalls)

        store.snapshot = store.snapshot.copy(
            settingOwnerGeneration = 45L, settingOwnerNetwork = "wifi", priorSettingValue = 0,
        )
        coordinator.recoverySettingsSnapshot()
        assertTrue(coordinator.hasManualRecoveryOwner())
        val reads = store.loadCalls
        store.loadThrows = true
        assertTrue(coordinator.hasManualRecoveryOwner())
        assertEquals(reads, store.loadCalls)
        assertTrue(coordinator.recoverySettingsSnapshot() == null)
        assertTrue(coordinator.hasManualRecoveryOwner())
    }

    @Test(timeout = 5_000) fun uiOwnerObservationDoesNotWaitForTheBackgroundAdmissionLock() {
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            FakeGateway().apply { classic = true }, store, FakeClock(),
        )
        coordinator.recoverySettingsSnapshot()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.beforeLoad = { call ->
            // Background preflight is read 2; beginManual holds ownerLock for read 3.
            if (call == 3) {
                entered.countDown()
                assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val result = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val background = Thread { result.set(coordinator.recoverManual()) }
        background.start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val reads = store.loadCalls
            assertFalse(coordinator.hasManualRecoveryOwner())
            assertEquals(reads, store.loadCalls)
        } finally {
            release.countDown()
            background.join(2_000L)
        }
        assertFalse(background.isAlive)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE, result.get().outcome)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test fun terminalRetryPersistenceFailureCannotStrandASuccessfulManualOwner() {
        val store = FakeStore().apply { clearExhaustionThrowsOnCall = 2 }
        val gateway = FakeGateway().apply { setting = 0 }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result.outcome)
        assertTrue(result.authenticated)
        assertTrue(result.transportRestored)
        assertFalse(result.settingOwned)
        assertFalse(result.rollbackPartial)
        assertEquals(null, coordinator.snapshot())
        assertFalse(coordinator.hasManualRecoveryOwner())
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertEquals(2, store.clearExhaustionCalls)
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, coordinator.recoverManual().outcome,
        )
    }

    @Test(timeout = 5_000) fun uiRecoveryEnqueueDoesNotWaitForTheBackgroundAdmissionLock() {
        val store = FakeStore()
        val dispatch = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            FakeGateway().apply { classic = true }, store, FakeClock(), dispatchExecutor = dispatch,
        )
        coordinator.recoverySettingsSnapshot()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.beforeLoad = { call ->
            if (call == 3) {
                entered.countDown()
                assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val first = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val queued = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val coalesced = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val background = Thread { first.set(coordinator.recoverManual()) }
        background.start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val reads = store.loadCalls
            assertTrue(coordinator.recoverManualAsync { queued.set(it) })
            assertTrue(coordinator.recoverManualAsync { coalesced.set(it) })
            assertTrue(background.isAlive)
            assertEquals(reads, store.loadCalls)
            assertEquals(1, dispatch.submissions)
            assertTrue(coordinator.hasManualRecoveryOwner())
            assertEquals(null, queued.get())
        } finally {
            release.countDown()
            background.join(2_000L)
        }
        assertFalse(background.isAlive)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE, first.get().outcome)
        dispatch.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE, queued.get().outcome)
        assertEquals(queued.get(), coalesced.get())
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test(timeout = 5_000) fun cancellationMarkedDuringDurableAdmissionCannotStartTheQueuedRecovery() {
        val store = FakeStore()
        val dispatch = HoldingExecutor()
        val gateway = FakeGateway().apply { setting = 0; classic = true }
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = dispatch,
        )
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.beforeLoad = { call ->
            if (call == 2) {
                entered.countDown()
                assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val result = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val cancelled = AtomicReference<Boolean>()
        assertTrue(coordinator.recoverManualAsync { result.set(it) })
        val recovery = Thread { dispatch.runPending() }
        recovery.start()
        val cancellation = Thread { cancelled.set(coordinator.cancelManual()) }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            cancellation.start()
            // The cancellation has marked the ticket before waiting for the admission lock.
            awaitBlocked(cancellation)
        } finally {
            release.countDown()
            recovery.join(2_000L)
            cancellation.join(2_000L)
        }

        assertFalse(recovery.isAlive)
        assertFalse(cancellation.isAlive)
        assertEquals(true, cancelled.get())
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.get().outcome)
        assertEquals(1L, gateway.classicChecked.count)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
        assertEquals(0, gateway.helperStarts)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test(timeout = 5_000) fun cancellationDuringWaitingTransferCannotDispatchTheReplacement() {
        val store = FakeStore()
        val dispatch = HoldingExecutor()
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = dispatch,
        )
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, coordinator.recoverManual().outcome)
        assertTrue(store.snapshot.hasSettingOwnershipJournal)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.beforeTransfer = {
            entered.countDown()
            assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        val result = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val cancelled = AtomicReference<Boolean>()
        assertTrue(coordinator.recoverManualAsync { result.set(it) })
        val recovery = Thread { dispatch.runPending() }
        val cancellation = Thread { cancelled.set(coordinator.cancelManual()) }
        recovery.start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            cancellation.start()
            awaitBlocked(cancellation)
        } finally {
            release.countDown()
            recovery.join(2_000L)
            cancellation.join(2_000L)
        }

        assertFalse(recovery.isAlive)
        assertFalse(cancellation.isAlive)
        assertEquals(true, cancelled.get())
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.get().outcome)
        assertEquals(1, store.transferCalls)
        assertEquals(1, gateway.writeCount)
        assertEquals(0, gateway.tlsOpenCount)
        assertEquals(0, gateway.helperStarts)
        assertEquals(0, gateway.setting)
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test fun terminalRetryPersistenceFailurePreservesManualCleanupWarning() {
        val store = FakeStore().apply {
            clearExhaustionThrowsOnCall = 2
            clearAllowed = false
        }
        val gateway = FakeGateway().apply { setting = 0 }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result.outcome)
        assertTrue(result.authenticated)
        assertTrue(result.transportRestored)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertTrue(coordinator.hasManualRecoveryOwner())
        assertTrue(store.snapshot.hasSettingOwnershipJournal)
        store.clearAllowed = true
        assertTrue(coordinator.cancelManual())
        assertEquals(0, gateway.setting)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test(timeout = 5_000) fun terminalRetryPersistenceFailureAlwaysFinalizesAutomaticOwnership() {
        for (clearAllowed in listOf(true, false)) {
            val store = FakeStore().apply {
                snapshot = snapshot.copy(automaticRestore = true)
                clearExhaustionThrowsOnCall = 1
                this.clearAllowed = clearAllowed
            }
            val gateway = FakeGateway().apply { setting = 0 }
            val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
            coordinator.triggerAutomatic()
            val deadline = System.currentTimeMillis() + 2_000L
            while (!coordinator.automaticDrainIdle() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            assertTrue(coordinator.automaticDrainIdle())
            assertEquals(1, store.clearExhaustionCalls)
            assertTrue(gateway.classicPublished)
            assertEquals(!clearAllowed, store.snapshot.hasSettingOwnershipJournal)
            assertEquals(!clearAllowed, coordinator.hasManualRecoveryOwner())
            if (clearAllowed) {
                assertEquals(null, coordinator.snapshot())
            } else {
                store.clearAllowed = true
                assertTrue(coordinator.cancelManual())
                assertEquals(0, gateway.setting)
            }
            coordinator.setAutomaticRestore(false)
        }
    }

    @Test(timeout = 5_000) fun lateAutomaticJournalReadFailureRetainsAnExplicitCleanupOwner() {
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val gateway = FakeGateway().apply {
            setting = 0
            onStartHelpers = { store.loadThrows = true }
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        val deadline = System.currentTimeMillis() + 2_000L
        while (!coordinator.automaticDrainIdle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }

        assertTrue(coordinator.automaticDrainIdle())
        assertTrue(gateway.classicPublished)
        assertTrue(store.snapshot.hasSettingOwnershipJournal)
        assertTrue(coordinator.hasManualRecoveryOwner())
        assertFalse(coordinator.cancelManual())
        assertEquals(1, gateway.writeCount)

        store.loadThrows = false
        assertTrue(coordinator.cancelManual())
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertFalse(coordinator.hasManualRecoveryOwner())
        assertEquals(0, gateway.setting)
        coordinator.setAutomaticRestore(false)
    }

    @Test(timeout = 5_000) fun cancelledAutomaticJournalRemainsAvailableForExplicitCleanup() {
        val store = FakeStore().apply {
            snapshot = snapshot.copy(automaticRestore = true)
            clearAllowed = false
        }
        val gateway = FakeGateway().apply { setting = 0 }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        val deadline = System.currentTimeMillis() + 2_000L
        while ((!coordinator.automaticDrainIdle() || coordinator.snapshot() == null) &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(10L)

        assertTrue(coordinator.setAutomaticRestore(false))
        assertTrue(coordinator.hasManualRecoveryOwner())
        assertTrue(store.snapshot.hasSettingOwnershipJournal)
        assertEquals(0, gateway.setting)
        assertFalse(coordinator.cancelManual())

        store.clearAllowed = true
        assertTrue(coordinator.cancelManual())
        assertFalse(coordinator.hasManualRecoveryOwner())
        assertEquals(2, gateway.writeCount)
    }

    @Test fun manualRecoveryClearsExhaustionAndBypassesPersistedCooldown() {
        val gateway = FakeGateway().apply {
            setting = 0
            tls = AdbTlsTransport.Result.TlsFailure()
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(
                networkWriteTimes = mapOf("wifi" to 9_999L),
                exhaustedNetwork = "wifi",
            )
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock(10_000L))
        coordinator.recoverManual()
        assertTrue(store.exhaustionCleared)
        assertEquals(null, store.snapshot.exhaustedNetwork)
        assertEquals(2, gateway.writeCount)
    }

    @Test fun enablingAutomaticAndSuccessfulRecoveryClearExhaustion() {
        val enabledStore = FakeStore().apply {
            snapshot = snapshot.copy(exhaustedNetwork = "wifi", retryVerdict = "old")
        }
        val enabled = DiLink5AdbRecoveryCoordinator(FakeGateway(), enabledStore, FakeClock())
        assertTrue(enabled.setAutomaticRestore(true))
        assertTrue(enabledStore.exhaustionCleared)
        assertTrue(enabledStore.snapshot.exhaustedNetwork == null)

        val restoredStore = FakeStore().apply {
            snapshot = snapshot.copy(
                automaticRestore = true,
                exhaustedNetwork = "wifi",
                retryVerdict = "old",
            )
        }
        val restored = DiLink5AdbRecoveryCoordinator(
            FakeGateway().apply { classic = true },
            restoredStore,
            FakeClock(),
        )
        restored.triggerAutomatic()
        val deadline = System.currentTimeMillis() + 2_000L
        while ((!restored.automaticDrainIdle() || restored.snapshot() != null) &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(10)
        assertTrue(restoredStore.exhaustionCleared)
        assertTrue(restoredStore.snapshot.exhaustedNetwork == null)
    }

    @Test fun automaticCooldownSuppressesWriteWithoutCreatingRetryWave() {
        val gateway = FakeGateway().apply {
            setting = 0
            freshClassic = false
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(
                automaticRestore = true,
                networkWriteTimes = mapOf("wifi" to 9_999L),
            )
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock(10_000L))
        coordinator.triggerAutomatic()
        assertTrue(gateway.classicChecked.await(2, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
        assertTrue(store.snapshot.retryVerdict == null)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun genuinelyRevertedAutomaticWriteRunsBoundedWaveAndPersistsExhaustion() {
        val gateway = FakeGateway().apply {
            setting = 0
            freshClassic = false
            settledWriteReversionsRemaining = -1
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(automaticRestore = true)
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock(1_000_000L))
        coordinator.triggerAutomatic()
        assertTrue(store.retryRecorded.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("wifi", store.snapshot.exhaustedNetwork)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG.name, store.snapshot.retryVerdict)
        assertTrue(gateway.writeCount > 1)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun retryWaveExitsWhenARetryReturnsTlsFailure() {
        val gateway = FakeGateway().apply {
            setting = 0
            tls = AdbTlsTransport.Result.TlsFailure()
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(automaticRestore = true)
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock(1_000_000L))
        coordinator.triggerAutomatic()
        assertTrue(store.retryRecorded.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val finished = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() != null && System.currentTimeMillis() < finished) Thread.sleep(10)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE.name, store.snapshot.retryVerdict)
        assertEquals(null, store.snapshot.exhaustedNetwork)
        // Initial enable + one retry + owner rollback. TLS failure ends the wave.
        assertEquals(3, gateway.writeCount)
        assertEquals(0, gateway.setting)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun needsDialogRetainsManualOwnerButDialogRequiresSeparateOptIn() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        val result = coordinator.recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, result.outcome)
        val session = coordinator.snapshot()!!
        assertFalse(coordinator.isEligible(session.generation, "wifi"))
        store.setDialogAutoConfirm(true, "wifi")
        assertTrue(coordinator.isEligible(session.generation, "wifi"))
        gateway.network = "other"
        assertFalse(coordinator.isEligible(session.generation, "wifi"))
        assertFalse(coordinator.isDialogAutoConfirmEnabledForCurrentNetwork())
        coordinator.cancelManual()
        assertEquals(session.generation, coordinator.snapshot()?.generation)
        gateway.network = "wifi"
        coordinator.cancelManual()
        assertTrue(coordinator.snapshot() == null)
    }

    @Test fun failedDialogOwnershipTransferRetainsUnresolvedOwnerFence() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        val generation = coordinator.snapshot()!!.generation
        store.transferAllowed = false
        gateway.network = "other"

        val retry = coordinator.recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, retry.outcome)
        assertTrue(retry.settingOwned)
        assertTrue(retry.rollbackPartial)
        assertEquals(generation, coordinator.snapshot()?.generation)
        assertEquals(generation, store.snapshot.settingOwnerGeneration)
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
    }

    @Test fun staleNetworkOwnerIsNeverTransferredToTheNewNetwork() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        gateway.network = "wifi-b"

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertEquals(0, store.transferCalls)
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
    }

    @Test fun freshnessIsRecheckedInsideWaitingOwnershipTransfer() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        var interleavingEnabled = false
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway,
            store,
            FakeClock(),
            beforeWaitingTransfer = {
                if (interleavingEnabled) gateway.network = "wifi-b"
            },
        )
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        interleavingEnabled = true

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertEquals(0, store.transferCalls)
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
    }

    @Test fun modeFreshnessIsRecheckedAtWaitingOwnershipTransfer() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        var interleavingEnabled = false
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway,
            store,
            FakeClock(),
            beforeWaitingTransfer = {
                if (interleavingEnabled) gateway.mode = "next-generation"
            },
        )
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        interleavingEnabled = true

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertFalse(result.settingOwned)
        assertFalse(result.rollbackPartial)
        assertEquals(0, store.transferCalls)
        assertEquals(0, gateway.setting)
        assertEquals(null, coordinator.snapshot())
    }

    @Test fun networkChangeAfterOwnershipTransferPreservesOriginalScopeForCancellation() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
            freshClassic = false
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        val waitingGeneration = coordinator.snapshot()!!.generation
        gateway.setting = 1
        coordinator.addSessionInvalidationListener { retired ->
            if (retired == waitingGeneration) gateway.network = "wifi-b"
        }

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertEquals(1, store.transferCalls)
        assertEquals("wifi", coordinator.snapshot()?.networkId)
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
        assertEquals(0, gateway.tlsOpenCount)
        assertEquals(1, gateway.writeCount)

        gateway.network = "wifi"
        assertTrue(coordinator.cancelManual())
        assertEquals(0, gateway.setting)
        assertEquals(null, coordinator.snapshot())
        assertEquals(null, store.snapshot.settingOwnerGeneration)
    }

    @Test fun modeChangeAfterOwnershipTransferCannotRebindTheContinuation() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        val waitingGeneration = coordinator.snapshot()!!.generation
        gateway.setting = 1
        coordinator.addSessionInvalidationListener { retired ->
            if (retired == waitingGeneration) gateway.mode = "replacement-generation"
        }

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.outcome)
        assertFalse(result.settingOwned)
        assertFalse(result.rollbackPartial)
        assertEquals(1, store.transferCalls)
        assertEquals(0, gateway.tlsOpenCount)
        assertEquals(0, gateway.setting)
        assertEquals(null, coordinator.snapshot())
        assertEquals(null, store.snapshot.settingOwnerGeneration)
    }

    @Test fun dialogContinuationPreservesNetworkScopeAfterTransferNotification() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
            freshClassic = false
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        val waitingGeneration = coordinator.snapshot()!!.generation
        gateway.setting = 1
        assertTrue(coordinator.setDialogAutoConfirm(true))
        val finalized = java.util.concurrent.CountDownLatch(1)
        coordinator.addSessionInvalidationListener { retired ->
            if (retired == waitingGeneration) {
                gateway.network = "wifi-b"
            } else {
                finalized.countDown()
            }
        }

        coordinator.onDialogActionAttempted(waitingGeneration)

        assertTrue(finalized.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("wifi", coordinator.snapshot()?.networkId)
        assertEquals("wifi", store.snapshot.settingOwnerNetwork)
        assertEquals(0, gateway.tlsOpenCount)
        assertEquals(1, gateway.writeCount)
        gateway.network = "wifi"
        assertTrue(coordinator.cancelManual())
        assertEquals(0, gateway.setting)
        assertEquals(null, coordinator.snapshot())
    }

    @Test fun manualRetryAfterConfirmationReplacesWaitingSessionInsteadOfReturningBusy() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            coordinator.recoverManual().outcome,
        )
        gateway.setting = 1
        gateway.freshClassic = true

        val retried = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, retried.outcome)
        assertTrue(retried.authenticated)
        assertEquals(1, store.transferCalls)
    }

    @Test fun helperStartupRequestIsNotReportedAsObservedHealth() {
        val gateway = FakeGateway().apply {
            classic = true
            helperObservedHealthy = false
        }
        val result = coordinator(gateway).recoverManual()

        assertTrue(result.helperStartRequested)
        assertFalse(result.helperHealthy)
    }

    @Test fun dialogConsentCannotBeEnabledForUnidentifiedNetwork() {
        val gateway = FakeGateway().apply { network = null }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertFalse(coordinator.setDialogAutoConfirm(true))
        assertFalse(store.snapshot.dialogAutoConfirm)
        assertTrue(store.snapshot.dialogAutoConfirmNetwork == null)
    }

    @Test fun dialogConsentRejectsEphemeralNetworkIdentity() {
        val gateway = FakeGateway().apply { network = "ephemeral:boot-network" }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        assertFalse(coordinator.setDialogAutoConfirm(true))
        assertFalse(store.snapshot.dialogAutoConfirm)
    }

    @Test fun dialogRerunTransfersConfirmedSettingOwnershipToReplacementSession() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(dialogAutoConfirm = true, dialogAutoConfirmNetwork = "wifi")
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        val first = coordinator.recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, first.outcome)
        val firstGeneration = coordinator.snapshot()!!.generation
        gateway.setting = 1
        gateway.freshClassic = true
        coordinator.onDialogActionAttempted(firstGeneration)
        val deadline = System.currentTimeMillis() + 2_000
        while (coordinator.snapshot() != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(coordinator.snapshot() == null)
        assertEquals(1, gateway.setting)
        assertTrue(store.ownershipCleared)
    }

    @Test fun disablingAutomaticDuringConfirmedWriteCancelsAndRollsBackItsOwner() {
        val gateway = FakeGateway().apply {
            setting = 0
            blockedWriteNumber = 1
        }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        assertTrue(gateway.writeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val disabling = Thread { coordinator.setAutomaticRestore(false) }
        disabling.start()
        awaitBlocked(disabling)
        gateway.releaseWrite.countDown()
        disabling.join(2_000)
        assertEquals(0, gateway.setting)
        assertTrue(store.ownershipCleared)
        assertFalse(store.snapshot.automaticRestore)
    }

    @Test fun cancellationRollsBackOnlyTheOwnedConfirmedSetting() {
        val gateway = FakeGateway().apply {
            setting = 0
            blockedWriteNumber = 1
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        val recovery = Thread { coordinator.recoverManual() }
        recovery.start()
        assertTrue(gateway.writeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val cancellation = Thread { coordinator.cancelManual() }
        cancellation.start()
        awaitBlocked(cancellation)
        gateway.releaseWrite.countDown()
        recovery.join(2_000)
        cancellation.join(2_000)
        assertEquals(0, gateway.setting)
        assertTrue(store.ownershipCleared)
        assertTrue(coordinator.snapshot() == null)
    }

    @Test fun retryStateKeepsTheOwnersFirstPriorValue() {
        val owned = FakeStore().snapshot.copy(
            settingOwnerGeneration = 17L,
            settingOwnerNetwork = "wifi",
            priorSettingValue = 2,
        )
        assertEquals(
            2,
            DiLink5AdbRecoveryStateStore.priorValueForWrite(owned, "wifi", 17L, 0),
        )
        assertEquals(
            0,
            DiLink5AdbRecoveryStateStore.priorValueForWrite(owned, "other", 17L, 0),
        )
    }

    @Test fun retryPreservesNonzeroPriorValueForCancellationRollback() {
        val gateway = FakeGateway().apply {
            setting = 2
            freshClassic = false
            settledWriteReversionsRemaining = 1
            settledWriteReversionValue = 2
            blockedWriteNumber = 2
        }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock(1_000_000L))
        coordinator.triggerAutomatic()
        assertTrue(gateway.writeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val cancellation = Thread { coordinator.setAutomaticRestore(false) }
        cancellation.start()
        awaitBlocked(cancellation)
        gateway.releaseWrite.countDown()
        cancellation.join(2_000)
        assertEquals(2, gateway.setting)
        assertTrue(store.ownershipCleared)
        assertTrue(coordinator.snapshot() == null)
    }

    @Test fun cancellationDuringClassicProofRollsBackAndSuppressesLateSuccess() {
        val gateway = FakeGateway().apply {
            setting = 0
            blockClassicProof = true
        }
        val store = FakeStore()
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        val result = java.util.concurrent.atomic.AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val recovery = Thread { result.set(coordinator.recoverManual()) }
        recovery.start()
        assertTrue(gateway.classicProofEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val generation = coordinator.snapshot()!!.generation
        val invalidated = java.util.concurrent.atomic.AtomicLong(-1L)
        coordinator.addSessionInvalidationListener { invalidated.set(it) }
        assertTrue(coordinator.cancelManual())
        gateway.releaseClassicProof.countDown()
        recovery.join(2_000)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.get().outcome)
        assertFalse(gateway.classicPublished)
        assertEquals(0, gateway.setting)
        assertTrue(store.ownershipCleared)
        assertEquals(generation, invalidated.get())
        assertTrue(coordinator.snapshot() == null)
    }

    @Test fun modeChangeAfterConfirmedWriteFinalizesAndRollsBackOwner() {
        val gateway = FakeGateway().apply { setting = 0 }
        val clock = FakeClock(onSleep = { gateway.mode = "changed" })
        val store = FakeStore()
        val result = DiLink5AdbRecoveryCoordinator(gateway, store, clock).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.outcome)
        assertEquals(0, gateway.setting)
        assertTrue(store.ownershipCleared)
    }

    @Test fun networkChangeAfterConfirmedWriteFinalizesAndRollsBackOwner() {
        val gateway = FakeGateway().apply { setting = 0 }
        val clock = FakeClock(onSleep = { gateway.network = "other" })
        val store = FakeStore()
        val result = DiLink5AdbRecoveryCoordinator(gateway, store, clock).recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.outcome)
        assertEquals(1, gateway.setting)
        assertTrue(result.rollbackPartial)
        assertFalse(store.ownershipCleared)
    }

    @Test fun networkScopeChangeDuringDiscoveryCannotOpenTlsOnReplacementNetwork() {
        val gateway = FakeGateway().apply {
            setting = 1
            blockDiscovery = true
        }
        val coordinator = coordinator(gateway)
        val result = AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val recovery = Thread { result.set(coordinator.recoverManual()) }
        recovery.start()
        assertTrue(gateway.discoveryEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        gateway.network = "wifi-b"
        gateway.releaseDiscovery.countDown()
        recovery.join(2_000L)

        assertFalse(recovery.isAlive)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.get().outcome)
        assertEquals(0, gateway.tlsOpenCount)
        assertEquals(null, coordinator.snapshot())
    }

    @Test fun automaticTriggersCoalesceAndReplayAfterTheRunningAttempt() {
        val gateway = FakeGateway().apply { blockDiscovery = true }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        coordinator.triggerAutomatic()
        assertTrue(gateway.discoveryEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        coordinator.triggerAutomatic()
        coordinator.triggerAutomatic()
        gateway.releaseDiscovery.countDown()

        val deadline = System.currentTimeMillis() + 2_000L
        while (gateway.discoveryCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, gateway.discoveryCount)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun failedAutomaticRollbackDuringManualTakeoverReportsOwnedPartialBusy() {
        val gateway = FakeGateway().apply {
            network = "wifi-a"
            setting = 0
            settledWriteReversionsRemaining = -1
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(automaticRestore = true)
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        assertTrue(store.retryRecorded.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val waitingDeadline = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() == null && System.currentTimeMillis() < waitingDeadline) {
            Thread.sleep(10L)
        }
        val automatic = coordinator.snapshot()!!
        gateway.network = "wifi-b"

        val result = coordinator.recoverManual()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result.outcome)
        assertTrue(result.settingOwned)
        assertTrue(result.rollbackPartial)
        assertEquals(automatic.generation, coordinator.snapshot()?.generation)
        assertEquals("wifi-a", store.snapshot.settingOwnerNetwork)
    }

    @Test fun networkChangeRetainsOldOwnershipAndQueuesTheNewNetwork() {
        val gateway = FakeGateway().apply {
            network = "wifi-a"
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(
                automaticRestore = true,
                exhaustedNetwork = "wifi-a",
            )
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        val retainedDeadline = System.currentTimeMillis() + 2_000L
        while (store.snapshot.settingOwnerNetwork != "wifi-a" &&
            System.currentTimeMillis() < retainedDeadline
        ) Thread.sleep(10)
        val ownerGeneration = coordinator.snapshot()!!.generation

        gateway.network = "wifi-b"
        coordinator.triggerAutomatic()
        val queuedDeadline = System.currentTimeMillis() + 2_000L
        while ((!coordinator.automaticDrainIdle() ||
                "wifi-b" !in coordinator.pendingAutomaticScopes()) &&
            System.currentTimeMillis() < queuedDeadline
        ) Thread.sleep(10)

        assertEquals(ownerGeneration, coordinator.snapshot()?.generation)
        assertEquals("wifi-a", store.snapshot.settingOwnerNetwork)
        assertTrue("wifi-b" in coordinator.pendingAutomaticScopes())

        // Returning to A lets the exact old owner reconcile. The earlier B request remains queued
        // rather than being overwritten by this cleanup event.
        gateway.network = "wifi-a"
        gateway.classic = true
        coordinator.triggerAutomatic()
        val cleanupDeadline = System.currentTimeMillis() + 2_000L
        while ((coordinator.snapshot() != null || !coordinator.automaticDrainIdle()) &&
            System.currentTimeMillis() < cleanupDeadline
        ) {
            Thread.sleep(10)
        }
        assertTrue(coordinator.snapshot() == null)
        assertTrue(store.snapshot.settingOwnerGeneration == null)
        assertTrue("wifi-b" in coordinator.pendingAutomaticScopes())

        gateway.network = "wifi-b"
        coordinator.triggerAutomatic()
        val finishDeadline = System.currentTimeMillis() + 2_000L
        while ((coordinator.snapshot() != null || coordinator.pendingAutomaticScopes().isNotEmpty() ||
                !coordinator.automaticDrainIdle()) &&
            System.currentTimeMillis() < finishDeadline
        ) Thread.sleep(10)
        assertTrue(coordinator.pendingAutomaticScopes().isEmpty())
        coordinator.setAutomaticRestore(false)
    }

    @Test fun routineTriggerTransfersDialogOwnershipAfterUserApproval() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = 1
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(
                automaticRestore = true,
                exhaustedNetwork = "wifi",
            )
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        val retainedDeadline = System.currentTimeMillis() + 2_000L
        while ((coordinator.snapshot()?.networkId == null ||
                store.snapshot.settingOwnerGeneration == null) &&
            System.currentTimeMillis() < retainedDeadline
        ) Thread.sleep(10)
        val retained = coordinator.snapshot()!!
        assertEquals(0, gateway.setting)

        // The system dialog is approved outside the app. The next ordinary trigger must continue
        // under transferred ownership instead of rolling the user's approval back to zero.
        gateway.setting = 1
        coordinator.triggerAutomatic()
        val finished = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() != null && System.currentTimeMillis() < finished) Thread.sleep(10)

        assertTrue(coordinator.snapshot() == null)
        assertEquals(1, gateway.setting)
        assertEquals(1, gateway.writeCount)
        assertTrue(store.ownershipCleared)
        assertTrue(store.snapshot.settingOwnerGeneration == null)
        assertTrue(retained.generation > 0)
    }

    @Test fun aNewAutomaticTriggerReplacesARetainedDialogSession() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = -1
        }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        coordinator.triggerAutomatic()
        val firstDeadline = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() == null && System.currentTimeMillis() < firstDeadline) Thread.sleep(10)
        val firstGeneration = coordinator.snapshot()!!.generation
        coordinator.triggerAutomatic()
        val secondDeadline = System.currentTimeMillis() + 2_000L
        while ((coordinator.snapshot()?.generation ?: firstGeneration) == firstGeneration &&
            System.currentTimeMillis() < secondDeadline
        ) Thread.sleep(10)

        assertTrue((coordinator.snapshot()?.generation ?: firstGeneration) != firstGeneration)
        assertTrue(gateway.writeCount >= 2)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun manualTakeoverWaitsForTheCancelledAutomaticTransportToQuiesce() {
        val gateway = FakeGateway().apply { blockTls = true }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        assertTrue(gateway.tlsEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val result = java.util.concurrent.atomic.AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()
        val manual = Thread { result.set(coordinator.recoverManual()) }
        manual.start()
        Thread.sleep(50)
        assertTrue(manual.isAlive)

        gateway.releaseTls.countDown()
        manual.join(2_000)

        assertFalse(manual.isAlive)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result.get().outcome)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun lifecycleCancellationStopsAnAutomaticSessionInTransport() {
        val gateway = FakeGateway().apply { blockTls = true }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        assertTrue(gateway.tlsEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))

        coordinator.cancelAutomatic()
        gateway.releaseTls.countDown()

        val deadline = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(coordinator.snapshot() == null)
    }

    @Test fun retryWaveKeepsFifteenSecondSpacingUntilTheFiveMinuteBudget() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = -1
        }
        val clock = FakeClock(1_000_000L)
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, clock)

        coordinator.triggerAutomatic()
        assertTrue(store.retryRecorded.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertTrue(clock.now >= 1_000_000L + 280_000L)
        assertTrue(gateway.writeCount > 10)
        assertEquals("wifi", store.snapshot.exhaustedNetwork)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun parkedAdmissionDropsAutomaticTriggersBeforeAnyProbe() {
        val gateway = FakeGateway().apply { automaticAllowed = false }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())

        coordinator.triggerAutomatic()
        Thread.sleep(50)

        assertTrue(coordinator.snapshot() == null)
        assertEquals(0, gateway.discoveryCount)
        assertEquals(1L, gateway.classicChecked.count)
    }

    @Test fun parkedGateRevocationDuringAutomaticWriteCancelsAndRollsBack() {
        val gateway = FakeGateway().apply { setting = 0 }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val clock = FakeClock(onSleep = { gateway.automaticAllowed = false })
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, clock)

        coordinator.triggerAutomatic()
        assertTrue(gateway.classicChecked.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() != null && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertTrue(coordinator.snapshot() == null)
        assertEquals(0, gateway.setting)
        assertTrue(store.ownershipCleared)
    }

    @Test fun queuedAutomaticCancellationCannotCancelANewerGeneration() {
        val gateway = FakeGateway().apply {
            blockTls = true
            freshClassic = false
        }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(
                automaticRestore = true,
                exhaustedNetwork = "wifi",
            )
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        coordinator.triggerAutomatic()
        assertTrue(gateway.tlsEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        gateway.setting = 0
        gateway.settledWriteReversionsRemaining = 1
        val oldGeneration = coordinator.snapshot()!!.generation

        coordinator.cancelAutomatic()
        coordinator.triggerAutomatic()
        gateway.releaseTls.countDown()

        val deadline = System.currentTimeMillis() + 2_000L
        while ((coordinator.snapshot()?.generation ?: oldGeneration) == oldGeneration &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(10)
        val replacement = coordinator.snapshot()
        assertTrue(replacement != null && replacement.generation != oldGeneration)
        Thread.sleep(50)
        assertEquals(replacement?.generation, coordinator.snapshot()?.generation)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun quiescenceTimeoutKeepsTheCancelledGenerationActiveAndBlocksReplacement() {
        val gateway = FakeGateway().apply { blockTls = true }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway,
            store,
            FakeClock(),
            quiesceTimeoutMs = 10L,
        )
        coordinator.triggerAutomatic()
        assertTrue(gateway.tlsEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val cancelledGeneration = coordinator.snapshot()!!.generation

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, coordinator.recoverManual().outcome)
        assertEquals(cancelledGeneration, coordinator.snapshot()?.generation)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, coordinator.recoverManual().outcome)

        gateway.releaseTls.countDown()
        val deadline = System.currentTimeMillis() + 2_000L
        while (coordinator.snapshot() != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(coordinator.snapshot() == null)
    }

    @Test fun queuedManualCancellationPreventsRecoveryDispatch() {
        val gateway = FakeGateway()
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway,
            FakeStore(),
            FakeClock(),
            dispatchExecutor = held,
        )
        val completed = java.util.concurrent.atomic.AtomicReference<DiLink5AdbRecoveryCoordinator.Result>()

        assertTrue(coordinator.recoverManualAsync { completed.set(it) })
        assertTrue(coordinator.hasManualRecoveryOwner())
        assertTrue(coordinator.cancelManual())
        held.runPending()

        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, completed.get().outcome)
        assertEquals(1L, gateway.classicChecked.count)
        assertFalse(coordinator.hasManualRecoveryOwner())
        assertFalse(coordinator.cancelManual())
    }

    @Test fun repeatedManualRequestsCoalesceCallbacksIntoOneSession() {
        val gateway = FakeGateway().apply {
            setting = 1
            tls = AdbTlsTransport.Result.TlsFailure()
        }
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway,
            FakeStore(),
            FakeClock(),
            dispatchExecutor = held,
        )
        val results = mutableListOf<DiLink5AdbRecoveryCoordinator.Outcome>()

        assertTrue(coordinator.recoverManualAsync { results += it.outcome })
        assertTrue(coordinator.recoverManualAsync { results += it.outcome })
        assertEquals(1, held.submissions)
        held.runPending()

        assertEquals(listOf(
            DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE,
            DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE,
        ), results)
        assertEquals(2, gateway.tlsOpenCount)
    }

    @Test fun revokedCallerBeforeDispatchCannotStartWirelessRecovery() {
        val gateway = FakeGateway().apply { setting = 0 }
        val store = FakeStore()
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        var current = true
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        current = false
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
        assertEquals(0, store.clearExhaustionCalls)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test fun revokedCallerDuringDurableAdmissionCannotPublishAnOwner() {
        val gateway = FakeGateway().apply { setting = 0 }
        val store = FakeStore()
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        var current = true
        store.beforeLoad = { call -> if (call == 2) current = false }
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(0, gateway.writeCount)
        assertEquals(1L, gateway.classicChecked.count)
        assertEquals(null, coordinator.snapshot())
    }

    @Test fun revokedCallerDuringJournalWriteCannotWriteTheSetting() {
        val gateway = FakeGateway().apply { setting = 0 }
        val store = FakeStore()
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        var current = true
        store.afterRecordWrite = { current = false }
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.discoveryCount)
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertFalse(result!!.rollbackPartial)
        assertEquals(null, coordinator.snapshot())
    }

    @Test fun revokedCallerAfterWriteRollsBackOnlyItsOwnedWirelessSetting() {
        val gateway = FakeGateway().apply { setting = 0 }
        val store = FakeStore()
        val held = HoldingExecutor()
        var current = true
        val clock = FakeClock(onSleep = { current = false })
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, clock, dispatchExecutor = held,
        )
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(2, gateway.writeCount)
        assertEquals(0, gateway.setting)
        assertEquals(0, gateway.discoveryCount)
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertFalse(result!!.rollbackPartial)
    }

    @Test fun scopedDialogContinuationRetainsAuthorityAndAcceptsANewManualRequest() {
        val gateway = FakeGateway().apply { setting = 0; settledWriteReversionsRemaining = 1 }
        val store = FakeStore()
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        var current = true
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, result?.outcome)
        assertTrue(coordinator.hasManualRecoveryOwner())
        gateway.setting = 1
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, result?.outcome)
        assertTrue(result!!.authenticated)
        assertEquals(1, store.transferCalls)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test fun revokedReplacementCallerDuringDialogTransferCannotStartTransport() {
        val gateway = FakeGateway().apply { setting = 0; settledWriteReversionsRemaining = 1 }
        val store = FakeStore()
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, coordinator.recoverManual().outcome)
        gateway.setting = 1
        var current = true
        store.beforeTransfer = { current = false }
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(0, gateway.tlsOpenCount)
        assertEquals(0, gateway.setting)
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertFalse(coordinator.hasManualRecoveryOwner())
    }

    @Test fun coalescingCannotShareARequestAcrossDifferentCallerFences() {
        val gateway = FakeGateway().apply { classic = true }
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, FakeStore(), FakeClock(), dispatchExecutor = held,
        )
        val guard: () -> Boolean = { true }
        val otherGuard: () -> Boolean = { true }
        val results = mutableListOf<DiLink5AdbRecoveryCoordinator.Result>()
        assertTrue(coordinator.recoverManualAsync(guard) { results.add(it) })
        assertFalse(coordinator.recoverManualAsync(otherGuard) { fail("Different authority coalesced") })
        assertTrue(coordinator.recoverManualAsync(guard) { results.add(it) })
        assertEquals(1, held.submissions)
        held.runPending()
        assertEquals(2, results.size)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE, results[0].outcome)
    }

    @Test fun callerCheckFailureFailsClosedBeforeRecovery() {
        val gateway = FakeGateway().apply { setting = 0 }
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, FakeStore(), FakeClock(), dispatchExecutor = held,
        )
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ throw IllegalStateException("scope unavailable") }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(0, gateway.writeCount)
        assertEquals(0, gateway.tlsOpenCount)
    }

    @Test fun callerRevokedDuringPermissionReadCannotSeedAGrant() {
        val gateway = FakeGateway().apply { classic = true; permission = false }
        val held = HoldingExecutor()
        var current = true
        gateway.onPermissionRead = { current = false }
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, FakeStore(), FakeClock(), dispatchExecutor = held,
        )
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertEquals(0, gateway.permissionSeeds)
        assertEquals(0, gateway.helperStarts)
    }

    @Test fun revokedCallerPreventsDialogAutoConfirmation() {
        val gateway = FakeGateway().apply { setting = 0; settledWriteReversionsRemaining = 1 }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(dialogAutoConfirm = true, dialogAutoConfirmNetwork = "wifi")
        }
        val held = HoldingExecutor()
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        var current = true
        assertTrue(coordinator.recoverManualAsync({ current }) {})
        held.runPending()
        val generation = coordinator.snapshot()!!.generation
        assertTrue(coordinator.isEligible(generation, "wifi"))
        current = false
        assertFalse(coordinator.isEligible(generation, "wifi"))
        assertTrue(coordinator.cancelManual())
    }

    @Test fun dialogEligibilityRechecksCallerModeNetworkAndOwnerAfterTheJournalRead() {
        for (change in listOf("caller", "mode", "network", "cancel")) {
            val gateway = FakeGateway().apply { setting = 0; settledWriteReversionsRemaining = 1 }
            val store = FakeStore().apply {
                snapshot = snapshot.copy(dialogAutoConfirm = true, dialogAutoConfirmNetwork = "wifi")
            }
            val held = HoldingExecutor()
            val coordinator = DiLink5AdbRecoveryCoordinator(
                gateway, store, FakeClock(), dispatchExecutor = held,
            )
            var current = true
            assertTrue(coordinator.recoverManualAsync({ current }) {})
            held.runPending()
            val generation = coordinator.snapshot()!!.generation
            assertTrue(coordinator.isEligible(generation, "wifi"))
            store.beforeLoad = {
                store.beforeLoad = null
                when (change) {
                    "caller" -> current = false
                    "mode" -> gateway.mode = "replacement-generation"
                    "network" -> gateway.network = "replacement-wifi"
                    "cancel" -> assertTrue(coordinator.cancelManual())
                }
            }
            assertFalse("Stale $change authority survived the read", coordinator.isEligible(generation, "wifi"))
            assertEquals(0, gateway.tlsOpenCount)
        }
    }

    @Test fun revokedCallerDuringClassicProofCannotPublishAuthenticatedSuccess() {
        val gateway = FakeGateway()
        val held = HoldingExecutor()
        var current = true
        gateway.beforeClassicPublish = { current = false }
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, FakeStore(), FakeClock(), dispatchExecutor = held,
        )
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertFalse(result!!.authenticated)
        assertFalse(gateway.classicPublished)
        assertEquals(0, gateway.helperStarts)
    }

    @Test fun invalidationDuringFinalRetryReadsCannotPublishSuccessFlags() {
        for (changedAuthority in listOf("caller", "network")) {
            for (invalidatingRead in listOf(1, 2)) {
                val gateway = FakeGateway()
                val store = FakeStore()
                val held = HoldingExecutor()
                var current = true
                var finalReads = 0
                gateway.onHelpersHealthy = {
                    store.beforeLoad = {
                        finalReads++
                        if (finalReads == invalidatingRead) {
                            if (changedAuthority == "caller") current = false
                            else gateway.network = "replacement-wifi"
                        }
                    }
                }
                val coordinator = DiLink5AdbRecoveryCoordinator(
                    gateway, store, FakeClock(), dispatchExecutor = held,
                )
                var result: DiLink5AdbRecoveryCoordinator.Result? = null
                assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
                held.runPending()
                assertTrue(finalReads >= invalidatingRead)
                assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
                assertFalse(result!!.authenticated)
                assertFalse(result!!.transportRestored)
                assertFalse(result!!.helperHealthy)
                assertEquals(null, coordinator.snapshot())
            }
        }
    }

    @Test fun dialogOwnerCannotBeRetainedAfterRevocationDuringItsFinalRetryRead() {
        val gateway = FakeGateway().apply { setting = 0; settledWriteReversionsRemaining = 1 }
        val store = FakeStore()
        val held = HoldingExecutor()
        var current = true
        var finalReads = 0
        val clock = FakeClock(onSleep = {
            store.beforeLoad = {
                finalReads++
                if (finalReads == 2) current = false
            }
        })
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, clock, dispatchExecutor = held,
        )
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertTrue(finalReads >= 2)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertFalse(result!!.authenticated)
        assertFalse(store.snapshot.hasSettingOwnershipJournal)
        assertEquals(null, coordinator.snapshot())
    }

    @Test(timeout = 10_000) fun automaticPolicyReadAfterJournalingCannotAuthorizeAStaleWrite() {
        for (invalidation in listOf("cancel", "mode")) {
            val gateway = FakeGateway().apply { setting = 0 }
            val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
            val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
            store.afterRecordWrite = {
                store.beforeLoad = {
                    store.beforeLoad = null
                    if (invalidation == "cancel") coordinator.cancelAutomatic()
                    else gateway.mode = "replacement-generation"
                }
            }
            coordinator.triggerAutomatic()
            awaitAutomaticIdle(coordinator)
            assertEquals(0, gateway.writeCount)
            assertEquals(0, gateway.discoveryCount)
            assertFalse(store.snapshot.hasSettingOwnershipJournal)
        }
    }

    @Test(timeout = 10_000) fun automaticHelperPolicyReadCannotStartHelpersAfterCancellation() {
        val gateway = FakeGateway().apply { classic = true }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        gateway.onPermissionRead = {
            store.beforeLoad = {
                store.beforeLoad = null
                coordinator.cancelAutomatic()
            }
        }
        coordinator.triggerAutomatic()
        awaitAutomaticIdle(coordinator)
        assertEquals(0, gateway.helperStarts)
    }

    @Test fun accessibilityConsentReadCannotKeepARevokedOwnerCurrent() {
        val gateway = FakeGateway().apply { classic = true }
        val store = FakeStore().apply {
            snapshot = snapshot.copy(dialogAutoConfirm = true, dialogAutoConfirmNetwork = "wifi")
        }
        val held = HoldingExecutor()
        var current = true
        gateway.beforeAccessibilityCheck = {
            store.beforeLoad = {
                store.beforeLoad = null
                current = false
            }
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(
            gateway, store, FakeClock(), dispatchExecutor = held,
        )
        var result: DiLink5AdbRecoveryCoordinator.Result? = null
        assertTrue(coordinator.recoverManualAsync({ current }) { result = it })
        held.runPending()
        assertEquals(0, gateway.accessibilityEnables)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result?.outcome)
        assertFalse(result!!.authenticated)
    }

    @Test fun legacyClassicRecoveryWithoutWifiStillRechecksTheModeBeforeReturning() {
        val gateway = FakeGateway().apply { classic = true; network = null }
        val store = FakeStore()
        gateway.onHelpersHealthy = {
            store.beforeLoad = {
                store.beforeLoad = null
                gateway.mode = "replacement-generation"
            }
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, FakeClock())
        val result = coordinator.recoverManual()
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED, result.outcome)
        assertFalse(result.authenticated)
        assertFalse(result.transportRestored)
        assertFalse(result.helperHealthy)
    }

    @Test fun wallClockJumpsDoNotChangeMonotonicRetryBudgetOrCreateNegativeSleeps() {
        val gateway = FakeGateway().apply {
            setting = 0
            settledWriteReversionsRemaining = -1
        }
        val store = FakeStore().apply { snapshot = snapshot.copy(automaticRestore = true) }
        val clock = FakeClock(1_000_000L)
        clock.onSleep = {
            clock.wall = if (clock.wall > 0L) -5_000_000L else 9_000_000L
        }
        val coordinator = DiLink5AdbRecoveryCoordinator(gateway, store, clock)

        coordinator.triggerAutomatic()
        assertTrue(store.retryRecorded.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertTrue(clock.elapsed >= 1_000_000L + 280_000L)
        assertTrue(clock.sleeps.all { it >= 0L })
        assertEquals("wifi", store.snapshot.exhaustedNetwork)
        coordinator.setAutomaticRestore(false)
    }

    @Test fun unsuccessfulRetryOnAnotherNetworkPreservesPriorExhaustion() {
        val store = FakeStore().apply {
            snapshot = snapshot.copy(exhaustedNetwork = "wifi-a", retryVerdict = "old")
        }
        store.recordRetry("wifi-b", DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE.name, false)
        assertEquals("wifi-a", store.snapshot.exhaustedNetwork)
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE.name, store.snapshot.retryVerdict)
    }

    @Test fun accessibilityReadbackRequiresTheGlobalEnableBitAndComponent() {
        val full = "com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService"
        val short = "com.overdrive.app/.services.KeepAliveAccessibilityService"
        assertFalse(AccessibilityServiceList.isEnabled(full, false, full, short))
        assertFalse(AccessibilityServiceList.isEnabled("other/Service", true, full, short))
        assertTrue(AccessibilityServiceList.isEnabled(full, true, full, short))
    }

    @Test fun unsupportedApiFailsBeforeDiscovery() {
        val gateway = FakeGateway().apply { api = 29 }
        assertEquals(
            DiLink5AdbRecoveryCoordinator.Outcome.UNSUPPORTED_API,
            coordinator(gateway).recoverManual().outcome,
        )
        assertEquals(0, gateway.discoveryCount)
    }

    private fun coordinator(gateway: FakeGateway) =
        DiLink5AdbRecoveryCoordinator(gateway, FakeStore(), FakeClock())

    private fun orphanedStore() = FakeStore().apply {
        snapshot = snapshot.copy(
            settingOwnerGeneration = 51L,
            settingOwnerNetwork = "wifi",
            priorSettingValue = 0,
        )
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.currentTimeMillis() + 2_000
        while (thread.state !in setOf(
                Thread.State.BLOCKED,
                Thread.State.WAITING,
                Thread.State.TIMED_WAITING,
            ) && System.currentTimeMillis() < deadline
        ) {
            Thread.yield()
        }
        assertTrue(thread.isAlive)
        assertTrue(thread.state in setOf(
            Thread.State.BLOCKED,
            Thread.State.WAITING,
            Thread.State.TIMED_WAITING,
        ))
    }

    private fun awaitAutomaticIdle(coordinator: DiLink5AdbRecoveryCoordinator) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (!coordinator.automaticDrainIdle() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Automatic drain did not finish", coordinator.automaticDrainIdle())
    }

    private class HoldingExecutor : java.util.concurrent.Executor {
        private var pending: Runnable? = null
        var submissions = 0
        override fun execute(command: Runnable) {
            check(pending == null)
            submissions++
            pending = command
        }
        fun runPending() {
            val command = pending ?: error("no pending command")
            pending = null
            command.run()
        }
    }

    private class FakeGateway : DiLink5AdbRecoveryCoordinator.Gateway {
        var api = 30
        var automaticAllowed = true
        var mode = "generation"
        var modeReadCount = 0
        var onModeGenerationRead: ((Int) -> Unit)? = null
        var network: String? = "wifi"
        var permission = true
        var permissionSeeds = 0
        var onPermissionRead: (() -> Unit)? = null
        var identityTrusted = true
        var classic = false
        var settledWriteReversionsRemaining = 0
        var settledWriteReversionValue = 0
        private var lastRevertedWrite = 0
        var setting = 1
        var writeResult = DiLink5AdbRecoveryCoordinator.WriteResult.CONFIRMED
        var unreadableAfterWrite = false
        var writeCount = 0
        var discoveryCount = 0
        var mdnsDiscoveryCount = 0
        var propertyEndpointAvailable = true
        var tls: AdbTlsTransport.Result = AdbTlsTransport.Result.RestartObserved
        val tlsResults = java.util.ArrayDeque<AdbTlsTransport.Result>()
        var tlsOpenCount = 0
        var freshClassic = true
        var blockedWriteNumber = 0
        var revertAfterProofFailure = false
        var revertAfterProofFailureTo: Int? = null
        var revertAfterTls = false
        var blockClassicProof = false
        var blockDiscovery = false
        var blockTls = false
        var classicPublished = false
        var helperStarts = 0
        var onStartHelpers: (() -> Unit)? = null
        var beforeClassicPublish: (() -> Unit)? = null
        var helperObservedHealthy = true
        var onHelpersHealthy: (() -> Unit)? = null
        var beforeAccessibilityCheck: (() -> Unit)? = null
        var accessibilityEnables = 0
        val classicChecked = java.util.concurrent.CountDownLatch(1)
        val classicProofEntered = java.util.concurrent.CountDownLatch(1)
        val releaseClassicProof = java.util.concurrent.CountDownLatch(1)
        val writeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val discoveryEntered = java.util.concurrent.CountDownLatch(1)
        val releaseDiscovery = java.util.concurrent.CountDownLatch(1)
        val tlsEntered = java.util.concurrent.CountDownLatch(1)
        val releaseTls = java.util.concurrent.CountDownLatch(1)
        override fun apiLevel() = api
        override fun automaticRecoveryAllowed() = automaticAllowed
        override fun modeGeneration(): String? {
            modeReadCount++
            onModeGenerationRead?.invoke(modeReadCount)
            return mode
        }
        override fun classicUsable(): Boolean {
            classicChecked.countDown()
            return classic
        }
        override fun identityPreviouslyAuthenticated() = identityTrusted
        override fun hasWriteSecureSettings(): Boolean { onPermissionRead?.invoke(); return permission }
        override fun seedWriteSecureSettings(): Boolean { permissionSeeds++; return permission }
        override fun networkIdentity() = network
        override fun readWirelessSetting(): Int? {
            if (unreadableAfterWrite && writeCount > 0) return null
            if (writeCount > lastRevertedWrite && settledWriteReversionsRemaining != 0) {
                lastRevertedWrite = writeCount
                if (settledWriteReversionsRemaining > 0) settledWriteReversionsRemaining--
                setting = settledWriteReversionValue
            }
            return setting
        }
        override fun writeWirelessSetting(value: Int): DiLink5AdbRecoveryCoordinator.WriteResult {
            writeCount++
            if (writeCount == blockedWriteNumber) {
                writeEntered.countDown()
                releaseWrite.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            setting = value
            return writeResult
        }
        override fun discover(cancelled: AtomicBoolean, includeProperty: Boolean): DiLink5AdbRecoveryDiscovery.Result {
            discoveryCount++
            if (!includeProperty) mdnsDiscoveryCount++
            discoveryEntered.countDown()
            if (blockDiscovery) releaseDiscovery.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (cancelled.get()) return DiLink5AdbRecoveryDiscovery.Result.Cancelled
            if (includeProperty && !propertyEndpointAvailable) {
                return DiLink5AdbRecoveryDiscovery.Result.NoListener
            }
            return DiLink5AdbRecoveryDiscovery.Result.Found(
                DiLink5AdbRecoveryDiscovery.Endpoint(
                    InetAddress.getByName("127.0.0.1"), 1234, requireNotNull(network),
                ),
            )
        }
        override fun openTls(
            endpoint: DiLink5AdbRecoveryDiscovery.Endpoint,
            cancelled: AtomicBoolean,
        ): AdbTlsTransport.Result {
            tlsOpenCount++
            if (blockTls) {
                tlsEntered.countDown()
                releaseTls.await(2, java.util.concurrent.TimeUnit.SECONDS)
                if (cancelled.get()) return AdbTlsTransport.Result.Cancelled(false)
            }
            if (revertAfterTls) setting = 0
            return if (tlsResults.isEmpty()) tls else tlsResults.removeFirst()
        }
        override fun proveFreshClassic(
            timeoutMs: Int,
            publishIfCurrent: ((() -> Unit) -> Boolean),
        ): Boolean {
            if (blockClassicProof) {
                classicProofEntered.countDown()
                releaseClassicProof.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            if (!freshClassic) {
                if (revertAfterProofFailure) setting = 0
                revertAfterProofFailureTo?.let { setting = it }
                return false
            }
            beforeClassicPublish?.invoke()
            return publishIfCurrent { classicPublished = true }
        }
        override fun startHelpers(): Boolean {
            helperStarts++
            onStartHelpers?.invoke()
            return true
        }
        override fun helpersHealthy(): Boolean { onHelpersHealthy?.invoke(); return helperObservedHealthy }
        override fun enableAccessibilityHelper(isCurrent: () -> Boolean): Boolean {
            beforeAccessibilityCheck?.invoke()
            if (!isCurrent()) return false
            accessibilityEnables++
            return isCurrent()
        }
    }

    private class FakeClock(
        initialTime: Long = 0,
        var onSleep: (() -> Unit)? = null,
    ) : DiLink5AdbRecoveryCoordinator.Clock {
        var wall = initialTime
        var elapsed = initialTime
        val now: Long get() = elapsed
        val sleeps = mutableListOf<Long>()
        override fun wallTimeMs() = wall
        override fun elapsedTimeMs() = elapsed
        override fun sleep(ms: Long, cancelled: AtomicBoolean): Boolean {
            require(ms >= 0L)
            sleeps += ms
            elapsed += ms
            onSleep?.invoke()
            return !cancelled.get()
        }
    }

    private class FakeStore : AdbRecoveryStateStore {
        var snapshot = DiLink5AdbRecoveryStateStore.Snapshot(
            automaticRestore = false,
            dialogAutoConfirm = false,
            dialogAutoConfirmNetwork = null,
            networkWriteTimes = emptyMap(),
            exhaustedNetwork = null,
            retryVerdict = null,
            settingOwnerGeneration = null,
            settingOwnerNetwork = null,
            priorSettingValue = null,
        )
        var exhaustionCleared = false
        var ownershipCleared = false
        var transferAllowed = true
        var transferCalls = 0
        var clearAllowed = true
        var clearThrows = false
        var loadThrows = false
        var clearExhaustionThrows = false
        var loadCalls = 0
        var beforeLoad: ((Int) -> Unit)? = null
        var clearExhaustionCalls = 0
        var clearExhaustionThrowsOnCall = 0
        var beforeTransfer: (() -> Unit)? = null
        var afterRecordWrite: (() -> Unit)? = null
        val retryRecorded = java.util.concurrent.CountDownLatch(1)
        override fun load(): DiLink5AdbRecoveryStateStore.Snapshot {
            loadCalls++
            beforeLoad?.invoke(loadCalls)
            if (loadThrows) throw IllegalStateException("journal read unavailable")
            return snapshot
        }
        override fun setAutomaticRestore(enabled: Boolean): Boolean {
            snapshot = snapshot.copy(automaticRestore = enabled); return true
        }
        override fun setDialogAutoConfirm(enabled: Boolean, networkId: String?): Boolean {
            if (enabled && networkId == null) return false
            snapshot = snapshot.copy(
                dialogAutoConfirm = enabled,
                dialogAutoConfirmNetwork = if (enabled) networkId else null,
            )
            return true
        }
        override fun recordWrite(networkId: String, atMs: Long, generation: Long, priorValue: Int): Boolean {
            if (snapshot.ownershipJournalInvalid ||
                (snapshot.hasSettingOwnershipJournal &&
                    (snapshot.settingOwnerGeneration != generation ||
                        snapshot.settingOwnerNetwork != networkId))
            ) return false
            val preservedPriorValue = if (snapshot.settingOwnerGeneration == generation &&
                snapshot.settingOwnerNetwork == networkId
            ) snapshot.priorSettingValue ?: priorValue else priorValue
            snapshot = snapshot.copy(
                networkWriteTimes = snapshot.networkWriteTimes + (networkId to atMs),
                settingOwnerGeneration = generation,
                settingOwnerNetwork = networkId,
                priorSettingValue = preservedPriorValue,
            )
            afterRecordWrite?.invoke()
            return true
        }
        override fun recordRetry(networkId: String, verdict: String, exhausted: Boolean): Boolean {
            snapshot = snapshot.copy(
                retryVerdict = verdict,
                exhaustedNetwork = if (exhausted) networkId else snapshot.exhaustedNetwork,
            )
            retryRecorded.countDown()
            return true
        }
        override fun clearExhaustion(): Boolean {
            clearExhaustionCalls++
            if (clearExhaustionThrows || clearExhaustionCalls == clearExhaustionThrowsOnCall) {
                throw IllegalStateException("retry persistence unavailable")
            }
            exhaustionCleared = true
            snapshot = snapshot.copy(exhaustedNetwork = null, retryVerdict = null)
            return true
        }
        override fun transferOwnership(fromGeneration: Long, toGeneration: Long): Boolean {
            transferCalls++
            if (!transferAllowed || snapshot.settingOwnerGeneration != fromGeneration) return false
            beforeTransfer?.invoke()
            snapshot = snapshot.copy(settingOwnerGeneration = toGeneration)
            return true
        }
        override fun clearOwnership(generation: Long): Boolean {
            if (clearThrows) throw IllegalStateException("journal persistence unavailable")
            if (!clearAllowed || snapshot.ownershipJournalInvalid) return false
            if (snapshot.settingOwnerGeneration != generation) return false
            ownershipCleared = true
            snapshot = snapshot.copy(
                settingOwnerGeneration = null,
                settingOwnerNetwork = null,
                priorSettingValue = null,
            )
            return true
        }
    }
}
