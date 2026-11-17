package com.overdrive.app.launcher.adb

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class AdbRecoveryManagerTest {
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() {
            var executions = 0
            while (tasks.isNotEmpty()) {
                check(++executions <= 100) { "Unbounded recovery dispatch" }
                tasks.removeFirst().run()
            }
        }
    }

    private class FakeGateway : AdbRecoveryManager.Gateway {
        var authority: String? = "default:active-1:pending-none"
        var automatic = false
        var parkedAllowed = true
        var flag: Int? = 0
        var permission = true
        var classic = false
        var grantAccepted = true
        var tls = false
        var preferenceFailure = false
        var observerFailure = false
        var unregisterFailure = false
        var rejectWrite = false
        var firmwareResetsFlag = false
        var writes = 0
        var probes = 0
        var grants = 0
        var registrations = 0
        var unregistrations = 0
        var helperStarts = 0
        var observerCallback: (() -> Unit)? = null
        var readHook: () -> Unit = {}
        var automaticReadHook: () -> Unit = {}
        var probeHook: () -> Unit = {}
        var grantHook: () -> Unit = {}
        var writeHook: () -> Unit = {}
        override fun modeAuthority() = authority
        override fun automaticRestoreEnabled(): Boolean {
            if (preferenceFailure) throw IllegalStateException("durable config unavailable")
            val enabled = automatic
            automaticReadHook()
            return enabled
        }
        override fun automaticRecoveryAllowed() = parkedAllowed
        override fun readAdbEnabled(): Int? { readHook(); return flag }
        override fun hasWriteSecureSettings() = permission
        override fun writeAdbEnabled(): Boolean {
            writes++
            if (!firmwareResetsFlag) flag = 1
            writeHook()
            return !rejectWrite
        }
        override fun observeAdbEnabled(onChange: () -> Unit): AutoCloseable {
            registrations++
            if (observerFailure) throw IllegalStateException("observer unavailable")
            observerCallback = onChange
            return AutoCloseable {
                unregistrations++
                if (unregisterFailure) throw IllegalStateException("unregister failed")
                observerCallback = null
            }
        }
        override fun classicAuthenticated(): Boolean { probes++; probeHook(); return classic }
        override fun seedWriteSecureSettings(): Boolean {
            grants++
            grantHook()
            if (grantAccepted) permission = true
            return permission
        }
        override fun tlsRecoveryAvailable() = tls
        override fun startHelpers() { helperStarts++ }
    }

    private class FakeWireless(private val gateway: FakeGateway) : AdbRecoveryManager.WirelessRecovery {
        var automaticTriggers = 0
        var automaticCancels = 0
        var manualRequests = 0
        var manualAdmissions = 0
        var manualCancels = 0
        var manualOwned = false
        var persistenceWorks = true
        var acceptsManual = true
        var callback: ((DiLink5AdbRecoveryCoordinator.Result) -> Unit)? = null
        var immediateResult: DiLink5AdbRecoveryCoordinator.Result? = null
        var handoffHook: () -> Unit = {}
        var callerCheck: (() -> Boolean)? = null
        override fun setAutomaticRestore(enabled: Boolean): Boolean {
            if (persistenceWorks) gateway.automatic = enabled
            return persistenceWorks
        }
        override fun triggerAutomatic() { automaticTriggers++ }
        override fun cancelAutomatic() { automaticCancels++ }
        override fun recoverManualAsync(
            isCallerCurrent: () -> Boolean,
            callback: (DiLink5AdbRecoveryCoordinator.Result) -> Unit,
        ): Boolean {
            manualRequests++
            handoffHook()
            if (!acceptsManual) return false
            callerCheck = isCallerCurrent
            this.callback = callback
            manualOwned = true
            if (!isCallerCurrent()) {
                complete(DiLink5AdbRecoveryCoordinator.Result(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED))
                return true
            }
            manualAdmissions++
            immediateResult?.let { complete(it) }
            return true
        }
        override fun cancelManual(): Boolean {
            manualCancels++
            val owned = manualOwned
            callback?.let { complete(DiLink5AdbRecoveryCoordinator.Result(DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED)) }
            manualOwned = false
            return owned
        }
        override fun hasManualRecoveryOwner() = manualOwned
        fun complete(result: DiLink5AdbRecoveryCoordinator.Result) {
            val deliver = callback
            callback = null
            manualOwned = result.settingOwned ||
                result.outcome == DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG
            deliver?.invoke(result)
        }
    }

    private class Fixture {
        val gateway = FakeGateway()
        val wireless = FakeWireless(gateway)
        val queue = Queue()
        var now = 0L
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()
        val manager = AdbRecoveryManager(
            gateway, wireless, queue, nowMs = { now },
            defer = { action, delay -> delayed.add((now + delay) to action) },
        )
        fun start() { manager.trigger(AdbRecoveryManager.Trigger.STARTUP); queue.drain() }
        fun advance(ms: Long) {
            now += ms
            val ready = delayed.filter { it.first <= now }
            delayed.removeAll(ready.toSet())
            ready.forEach { it.second() }
            queue.drain()
        }
        fun manual(callback: (AdbRecoveryManager.Result) -> Unit = {}) {
            assertTrue(manager.recoverManualAsync(requireNotNull(gateway.authority), callback))
            queue.drain()
        }
    }

    @Test fun defaultOffDoesNotObserveWriteProbeGrantOrRecoverWirelessInAnyMode() {
        for (mode in listOf("default", "dilink4", "dilink5", "camera2")) {
            val f = Fixture()
            f.gateway.authority = "$mode:committed-generation"
            f.gateway.tls = mode == "dilink5"
            f.start()
            assertEquals(mode, 0, f.gateway.registrations)
            assertEquals(mode, 0, f.gateway.writes)
            assertEquals(mode, 0, f.gateway.probes)
            assertEquals(mode, 0, f.gateway.grants)
            assertEquals(mode, 0, f.wireless.automaticTriggers)
        }
    }

    @Test fun localAutomaticRecoveryWorksAcrossModesWithoutWirelessEligibility() {
        for (mode in listOf("default", "dilink4", "camera2", "dilink5")) {
            val f = Fixture()
            f.gateway.authority = "$mode:committed-generation"
            f.gateway.automatic = true
            f.start()
            assertEquals(mode, 1, f.gateway.flag)
            assertEquals(mode, 1, f.gateway.writes)
            assertEquals(mode, 1, f.gateway.registrations)
            assertEquals(mode, AdbRecoveryManager.Outcome.SETTING_ENABLED, f.manager.lastResult()?.outcome)
            assertFalse(f.manager.lastResult()!!.authenticated)
            assertEquals(mode, 0, f.wireless.automaticTriggers)
        }
    }

    @Test fun anEnabledSettingIsNotAnAuthenticatedConnection() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.flag = 1
        f.start()
        assertEquals(0, f.gateway.writes)
        assertEquals(AdbRecoveryManager.Outcome.SETTING_ENABLED, f.manager.lastResult()?.outcome)
        assertFalse(f.manager.lastResult()!!.authenticated)
    }

    @Test fun realClassicSuccessStartsHelpersWithoutWirelessForOlderModes() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.classic = true
        f.start()
        assertEquals(AdbRecoveryManager.Outcome.AUTHENTICATED, f.manager.lastResult()?.outcome)
        assertTrue(f.manager.lastResult()!!.authenticated)
        assertEquals(1, f.gateway.helperStarts)
        assertEquals(0, f.wireless.automaticTriggers)
    }

    @Test fun missingPermissionAndUnavailableAdbCannotBootstrapThemselves() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.permission = false
        f.start()
        assertEquals(AdbRecoveryManager.Outcome.MISSING_PERMISSION, f.manager.lastResult()?.outcome)
        assertEquals(0, f.gateway.writes)
        assertEquals(0, f.gateway.grants)
        assertFalse(f.manager.lastResult()!!.authenticated)
    }

    @Test fun authenticatedClassicConnectionCanSeedAndVerifyThePermissionThenRestore() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.permission = false
        f.gateway.classic = true
        f.start()
        assertEquals(1, f.gateway.grants)
        assertTrue(f.gateway.permission)
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.gateway.flag)
        assertEquals(AdbRecoveryManager.Outcome.AUTHENTICATED, f.manager.lastResult()?.outcome)
    }

    @Test fun refusedGrantCannotClaimFutureFlagRecovery() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.permission = false
        f.gateway.classic = true
        f.gateway.grantAccepted = false
        f.start()
        assertEquals(1, f.gateway.grants)
        assertEquals(0, f.gateway.writes)
        assertEquals(AdbRecoveryManager.Outcome.MISSING_PERMISSION, f.manager.lastResult()?.outcome)
        assertTrue(f.manager.lastResult()!!.authenticated)
        assertFalse(f.manager.lastResult()!!.settingEnabled)
    }

    @Test fun permissionSeedProbeCannotProveTheTransportAfterTheFlagWrite() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.permission = false
        f.gateway.classic = true
        f.gateway.writeHook = { f.gateway.classic = false }
        f.start()
        assertEquals(1, f.gateway.grants)
        assertEquals(1, f.gateway.writes)
        assertEquals(2, f.gateway.probes)
        assertEquals(AdbRecoveryManager.Outcome.SETTING_ENABLED, f.manager.lastResult()?.outcome)
        assertFalse(f.manager.lastResult()!!.authenticated)
        assertEquals(0, f.gateway.helperStarts)
    }

    @Test fun connectedEventSeedsPermissionEvenWhenFlagWasAlreadyEnabled() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.flag = 1
        f.gateway.permission = false
        f.start()
        assertEquals(0, f.gateway.grants)
        f.gateway.classic = true
        f.manager.trigger(AdbRecoveryManager.Trigger.CONNECTION)
        f.queue.drain()
        assertEquals(1, f.gateway.grants)
        assertTrue(f.gateway.permission)
        assertEquals(0, f.gateway.writes)
    }

    @Test fun aPostProbeGrantDoesNotEraseAnEarlierWriteAttempt() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.classic = true
        f.gateway.probeHook = { f.gateway.permission = false }
        f.start()
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.gateway.grants)
        assertTrue(f.manager.lastResult()!!.settingWriteAttempted)
    }

    @Test fun turningOptInOffUnregistersWithoutChangingAPreexistingSetting() {
        val f = Fixture()
        f.gateway.automatic = true
        f.start()
        assertTrue(f.manager.setAutomaticRestore(false))
        f.queue.drain()
        assertEquals(1, f.gateway.unregistrations)
        assertNull(f.gateway.observerCallback)
        assertEquals(1, f.gateway.flag)
        assertEquals(1, f.gateway.writes)
    }

    @Test fun enablingOptInImmediatelyRegistersAndRestores() {
        val f = Fixture()
        f.start()
        assertTrue(f.manager.setAutomaticRestore(true))
        f.queue.drain()
        assertEquals(1, f.gateway.registrations)
        assertEquals(1, f.gateway.writes)
    }

    @Test fun persistenceFailureIsReturnedAndDoesNotEnableDefaultOffRecovery() {
        val f = Fixture()
        f.wireless.persistenceWorks = false
        assertFalse(f.manager.setAutomaticRestore(true))
        f.queue.drain()
        assertEquals(0, f.gateway.writes)
        assertEquals(0, f.gateway.registrations)
    }

    @Test fun unavailableDurablePreferenceFailsClosed() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.preferenceFailure = true
        f.start()
        assertEquals(0, f.gateway.writes)
        assertEquals(0, f.gateway.probes)
        assertEquals(0, f.gateway.registrations)
        assertEquals(0, f.wireless.automaticTriggers)
    }

    @Test fun missingModeAuthorityCannotAuthorizeAutomaticWrites() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.authority = null
        f.start()
        assertEquals(0, f.gateway.writes)
        assertEquals(0, f.gateway.registrations)
    }

    @Test fun parkedStanddownBlocksLocalAndWirelessUntilWake() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.tls = true
        f.gateway.parkedAllowed = false
        f.start()
        assertEquals(0, f.gateway.writes)
        assertEquals(0, f.wireless.automaticTriggers)
        f.gateway.parkedAllowed = true
        f.manager.triggerAutomatic()
        f.queue.drain()
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.wireless.automaticTriggers)
    }

    @Test fun observerRestoresAnOffEdgeWithoutNeedingASettingsView() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.flag = 1
        f.start()
        f.gateway.flag = 0
        f.gateway.observerCallback!!.invoke()
        f.queue.drain()
        assertEquals(1, f.gateway.flag)
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.gateway.registrations)
    }

    @Test fun selfNotificationWithFlagOneDoesNotProbeOrRetryWirelessAgain() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.tls = true
        f.gateway.writeHook = { f.gateway.observerCallback?.invoke() }
        f.start()
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.gateway.probes)
        assertEquals(1, f.wireless.automaticTriggers)
    }

    @Test fun firmwareThatImmediatelyResetsTheFlagCannotCauseAnObserverSpin() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.firmwareResetsFlag = true
        f.gateway.writeHook = { f.gateway.observerCallback?.invoke() }
        f.start()
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.delayed.size)
        assertEquals(AdbRecoveryManager.Outcome.SETTING_FAILED, f.manager.lastResult()?.outcome)
        f.advance(1_999)
        assertEquals(1, f.gateway.writes)
        f.advance(1)
        assertEquals(2, f.gateway.writes)
        assertEquals(1, f.delayed.size)
    }

    @Test fun disablingWhileADeferredRetryIsQueuedPreventsTheWrite() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.firmwareResetsFlag = true
        f.gateway.writeHook = { f.gateway.observerCallback?.invoke() }
        f.start()
        assertTrue(f.manager.setAutomaticRestore(false))
        f.queue.drain()
        f.advance(2_000)
        assertEquals(1, f.gateway.writes)
        assertNull(f.gateway.observerCallback)
    }

    @Test fun disablingAndReenablingCannotLoseTheNewEpochsDeferredRetry() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.firmwareResetsFlag = true
        f.gateway.writeHook = { f.gateway.observerCallback?.invoke() }
        f.start()
        assertEquals(1, f.gateway.writes)
        assertTrue(f.manager.setAutomaticRestore(false))
        f.queue.drain()
        assertTrue(f.manager.setAutomaticRestore(true))
        f.queue.drain()
        f.advance(2_000)
        assertEquals(2, f.gateway.writes)
        assertEquals(1, f.delayed.size)
    }

    @Test fun failedUnregisterRetainsItsOwnerAndCannotCreateADuplicateObserver() {
        val f = Fixture()
        f.gateway.automatic = true
        f.start()
        f.gateway.unregisterFailure = true
        assertTrue(f.manager.setAutomaticRestore(false))
        f.queue.drain()
        assertNotNull(f.gateway.observerCallback)
        val ownedCallback = f.gateway.observerCallback
        assertTrue(f.manager.setAutomaticRestore(true))
        f.queue.drain()
        assertEquals(1, f.gateway.registrations)
        assertSame(ownedCallback, f.gateway.observerCallback)
        assertTrue(f.manager.setAutomaticRestore(false))
        f.queue.drain()
        f.gateway.unregisterFailure = false
        assertTrue(f.manager.setAutomaticRestore(false))
        f.queue.drain()
        assertNull(f.gateway.observerCallback)
        assertEquals(1, f.gateway.registrations)
    }

    @Test fun unrelatedConfigurationNotificationsDoNotCreateARecoveryLoop() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.tls = true
        f.start()
        repeat(20) { f.manager.trigger(AdbRecoveryManager.Trigger.CONFIGURATION) }
        f.queue.drain()
        assertEquals(1, f.gateway.probes)
        assertEquals(1, f.wireless.automaticTriggers)
    }

    @Test fun transientProviderFailureDuringTheRateCheckKeepsTheObserverAndRetries() {
        val f = Fixture()
        f.gateway.automatic = true
        f.start()
        f.gateway.flag = 0
        f.gateway.readHook = { throw IllegalStateException("provider temporarily unavailable") }
        f.gateway.observerCallback!!.invoke()
        f.queue.drain()
        assertEquals(AdbRecoveryManager.Outcome.SETTING_FAILED, f.manager.lastResult()?.outcome)
        assertEquals(0, f.gateway.unregistrations)
        assertNotNull(f.gateway.observerCallback)
        f.gateway.readHook = {}
        f.advance(2_000)
        assertEquals(2, f.gateway.writes)
        assertEquals(1, f.gateway.flag)
        assertEquals(1, f.gateway.registrations)
    }

    @Test fun revokingOptInBetweenReadAndWritePreventsMutation() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.readHook = { f.gateway.automatic = false }
        f.start()
        assertEquals(0, f.gateway.writes)
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, f.manager.lastResult()?.outcome)
    }

    @Test fun cancellationOrModeChangeDuringThePolicyReadCannotAuthorizeALocalWrite() {
        for (invalidation in listOf("cancel", "mode")) {
            val f = Fixture()
            f.gateway.automatic = true
            var reads = 0
            f.gateway.automaticReadHook = {
                if (++reads == 2) {
                    if (invalidation == "cancel") f.manager.cancelAutomatic()
                    else f.gateway.authority = "dilink4:replacement-generation"
                }
            }
            f.start()
            assertEquals(0, f.gateway.writes)
            assertEquals(AdbRecoveryManager.Outcome.CANCELLED, f.manager.lastResult()?.outcome)
            assertEquals(0, f.wireless.automaticTriggers)
        }
    }

    @Test fun changedModeDuringPermissionProbeCannotGrantOrWrite() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.permission = false
        f.gateway.classic = true
        f.gateway.probeHook = { f.gateway.authority = "dilink4:replacement-generation" }
        f.start()
        assertEquals(0, f.gateway.grants)
        assertEquals(0, f.gateway.writes)
        assertFalse(f.manager.lastResult()!!.authenticated)
    }

    @Test fun manualLocalRecoveryWorksWithoutEnablingAutomaticRecovery() {
        val f = Fixture()
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        assertEquals(1, f.gateway.writes)
        assertFalse(f.gateway.automatic)
        assertEquals(0, f.gateway.registrations)
        assertEquals(AdbRecoveryManager.Outcome.SETTING_ENABLED, result?.outcome)
        assertEquals(0, f.wireless.manualRequests)
    }

    @Test fun cancellationBeforeManualDispatchDoesNotMutateSettings() {
        val f = Fixture()
        var result: AdbRecoveryManager.Result? = null
        assertTrue(f.manager.recoverManualAsync(f.gateway.authority!!) { result = it })
        assertTrue(f.manager.cancelManual())
        f.queue.drain()
        assertEquals(0, f.gateway.writes)
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, result?.outcome)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun changedManualScopeBeforeDispatchDoesNotMutateSettings() {
        val f = Fixture()
        var result: AdbRecoveryManager.Result? = null
        assertTrue(f.manager.recoverManualAsync(f.gateway.authority!!) { result = it })
        f.gateway.authority = "default:replacement-generation"
        f.queue.drain()
        assertEquals(0, f.gateway.writes)
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, result?.outcome)
    }

    @Test fun repeatedManualRequestsCoalesceOnlyWithinTheSameScope() {
        val f = Fixture()
        val results = mutableListOf<AdbRecoveryManager.Result>()
        assertTrue(f.manager.recoverManualAsync(f.gateway.authority!!) { results.add(it) })
        assertTrue(f.manager.recoverManualAsync(f.gateway.authority!!) { results.add(it) })
        assertFalse(f.manager.recoverManualAsync("different-scope") { fail("Rejected callback invoked") })
        f.queue.drain()
        assertEquals(2, results.size)
        assertEquals(1, f.gateway.writes)
        assertEquals(1, f.gateway.probes)
    }

    @Test fun wirelessFallbackKeepsItsTypedFailureAndOwnershipResult() {
        val f = Fixture()
        f.gateway.tls = true
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        assertEquals(1, f.wireless.manualRequests)
        assertTrue(f.manager.hasLocalManualRequest())
        val failure = DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE,
            settingOwned = true, rollbackPartial = true,
        )
        f.wireless.complete(failure)
        assertEquals(failure, result?.wirelessResult)
        assertFalse(result!!.authenticated)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun immediateWirelessCallbackDoesNotCancelACompletedSuccessfulOwner() {
        val f = Fixture()
        f.gateway.tls = true
        f.wireless.immediateResult = DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.RESTORED,
            transportRestored = true, authenticated = true,
        )
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        assertTrue(result!!.authenticated)
        assertEquals(0, f.wireless.manualCancels)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun cancellationRacingWirelessOwnerPublicationPreventsAdmission() {
        val f = Fixture()
        f.gateway.tls = true
        f.wireless.handoffHook = { f.manager.cancelManual() }
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        assertEquals(0, f.wireless.manualAdmissions)
        assertTrue(f.wireless.manualCancels >= 2)
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, result?.outcome)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun changedScopeDuringWirelessHandoffPreventsAdmission() {
        val f = Fixture()
        f.gateway.tls = true
        f.wireless.handoffHook = { f.gateway.authority = "dilink4:replacement-generation" }
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        assertEquals(0, f.wireless.manualAdmissions)
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, result?.outcome)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun dialogOwnerKeepsItsScopeAfterTheFacadeCallbackAndCanBeRetried() {
        val f = Fixture()
        f.gateway.tls = true
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        val firstGuard = f.wireless.callerCheck!!
        f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, settingOwned = true,
        ))
        assertFalse(f.manager.hasLocalManualRequest())
        assertTrue(f.manager.hasManualRecoveryOwner())
        assertTrue(firstGuard())
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, result?.wirelessResult?.outcome)
        f.manual { result = it }
        assertEquals(2, f.wireless.manualAdmissions)
        f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.RESTORED, authenticated = true,
        ))
        assertTrue(result!!.authenticated)
        assertFalse(f.manager.hasManualRecoveryOwner())
        f.gateway.authority = "default:replacement-generation"
        assertFalse(firstGuard())
    }

    @Test fun authenticatedLocalRetryStillResumesARetainedWirelessOwner() {
        val f = Fixture()
        f.gateway.tls = true
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, settingOwned = true,
        ))
        assertTrue(f.manager.hasManualRecoveryOwner())
        f.gateway.classic = true
        f.manual { result = it }
        assertEquals(2, f.wireless.manualAdmissions)
        assertTrue(f.manager.hasLocalManualRequest())
        f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.CLASSIC_USABLE, authenticated = true,
        ))
        assertTrue(result!!.authenticated)
        assertFalse(f.manager.hasManualRecoveryOwner())
    }

    @Test fun failedWirelessContinuationCannotRetainAnAuthenticatedOutcome() {
        for (failure in listOf(
            DiLink5AdbRecoveryCoordinator.Outcome.TLS_FAILURE,
            DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG,
            DiLink5AdbRecoveryCoordinator.Outcome.BUSY,
        )) {
            val f = Fixture()
            f.gateway.tls = true
            var result: AdbRecoveryManager.Result? = null
            f.manual { result = it }
            f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(
                DiLink5AdbRecoveryCoordinator.Outcome.NEEDS_DIALOG, settingOwned = true,
            ))
            f.gateway.classic = true
            f.manual { result = it }
            f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(failure, settingOwned = true))
            assertEquals(AdbRecoveryManager.Outcome.SETTING_ENABLED, result?.outcome)
            assertFalse(result!!.authenticated)
            assertEquals(failure, result?.wirelessResult?.outcome)
        }
    }

    @Test fun wirelessNetworkCancellationPropagatesWithoutClaimingAuthentication() {
        val f = Fixture()
        f.gateway.tls = true
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(
            DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED,
        ))
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, result?.outcome)
        assertFalse(result!!.authenticated)
        assertTrue(result!!.settingEnabled)
    }

    @Test fun changedScopeDuringWirelessWaitCancelsThroughTheConfigurationLane() {
        val f = Fixture()
        f.gateway.tls = true
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        f.gateway.authority = "dilink4:replacement-generation"
        f.manager.trigger(AdbRecoveryManager.Trigger.CONFIGURATION)
        f.queue.drain()
        assertEquals(1, f.wireless.manualCancels)
        assertEquals(AdbRecoveryManager.Outcome.CANCELLED, result?.outcome)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun automaticWorkWaitsForAnExplicitManualWirelessOperation() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.tls = true
        f.manager.trigger(AdbRecoveryManager.Trigger.STARTUP)
        assertTrue(f.manager.recoverManualAsync(f.gateway.authority!!) {})
        f.queue.drain()
        assertEquals(1, f.wireless.manualRequests)
        assertEquals(0, f.wireless.automaticTriggers)
        f.wireless.complete(DiLink5AdbRecoveryCoordinator.Result(DiLink5AdbRecoveryCoordinator.Outcome.NO_LISTENER))
        f.queue.drain()
        assertEquals(1, f.wireless.automaticTriggers)
    }

    @Test fun aRejectedWirelessRequestCompletesWithBusyInsteadOfLeavingAnOwner() {
        val f = Fixture()
        f.gateway.tls = true
        f.wireless.acceptsManual = false
        var result: AdbRecoveryManager.Result? = null
        f.manual { result = it }
        assertEquals(DiLink5AdbRecoveryCoordinator.Outcome.BUSY, result?.wirelessResult?.outcome)
        assertFalse(f.manager.hasLocalManualRequest())
    }

    @Test fun rejectedWriteNeverClaimsSettingRecoveryEvenWithALiveConnection() {
        val f = Fixture()
        f.gateway.automatic = true
        f.gateway.rejectWrite = true
        f.gateway.firmwareResetsFlag = true
        f.gateway.classic = true
        f.start()
        assertEquals(AdbRecoveryManager.Outcome.SETTING_FAILED, f.manager.lastResult()?.outcome)
        assertTrue(f.manager.lastResult()!!.authenticated)
        assertFalse(f.manager.lastResult()!!.settingEnabled)
    }
}
